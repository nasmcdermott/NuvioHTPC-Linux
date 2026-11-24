package com.nuvio.app.features.sports

import co.touchlab.kermit.Logger
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The Sports tab's data, cached first and refreshed behind.
 *
 * One state per league and day, so the date strip can move without refetching the league it is
 * already showing. A failed refresh keeps what was cached and records the failure next to it — the
 * feed is unofficial and will fail, and an empty screen is a worse answer than a slightly old one.
 */
internal object SportsRepository {
    private val log = Logger.withTag("Sports")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * How often the Sports page re-asks for the day it is showing, while something is live. The
     * scoreboard carries a running clock, period and score, so a page that fetches once shows a
     * frozen game; this is the interval the page's timer uses, and the repository's own cache TTL
     * for a live day, so the timer's ask is never served from the cache it just filled.
     */
    internal val liveRefreshMs: Long get() = Freshness.LIVE_MS

    /**
     * How often the game page re-reads its summary (box score, plays, stats) while the game is on.
     * That answer is the expensive one — a summary is a hundred times the size of a scoreboard — and
     * the numbers on it change at the pace of a stat keeper, not of the clock, so it is read less
     * often than the scoreboard is.
     */
    internal val liveDetailRefreshMs: Long get() = Freshness.LIVE_DETAIL_MS

    internal object Freshness {
        // A game in progress is re-read every 10 s. ESPN rewrites the scoreboard as each play lands
        // and the page is showing a clock and a scoreline the viewer is watching, so half a minute
        // made a live game look late on every basket. Only leagues with a game on are asked this
        // often (see the page's refresh loop); a card's own clock comes from the fast row every 4 s
        // (LIVE_ROW_MS) and ticks locally between reads.
        const val LIVE_MS = 10_000L
        // Today, with nothing in progress: kick-offs and final whistles are the only things that
        // change, so a minute is enough and the whole catalog can be asked at that pace.
        const val TODAY_MS = 60_000L
        // The summary behind an open game page: stats and plays, at a stat keeper's pace.
        const val LIVE_DETAIL_MS = 30_000L
        /**
         * How often a league with a game in progress is re-read from the fast row. The row's own
         * answer is allowed to be 2-7 s old, so this is the point where asking again is worth the
         * request; the scoreboard round (10 s) stays as the schedule-and-state backstop.
         */
        const val LIVE_ROW_MS = 4_000L
        const val FUTURE_MS = 30 * 60_000L
        const val PAST_MS = 24 * 60 * 60_000L
        const val TEAMS_MS = 7 * 24 * 60 * 60_000L
        const val STANDINGS_MS = 60 * 60_000L
    }

    data class DayState(
        val leagueId: String,
        val day: String,
        val games: List<SportsEvent> = emptyList(),
        val loading: Boolean = false,
        /** Set when the last refresh failed; [games] may still hold the cached (older) day. */
        val error: String? = null,
        val fetchedAtMs: Long = 0L,
        /**
         * When the feed was last *asked*, successfully or not. Kept apart from [fetchedAtMs] so a
         * league that keeps failing is not asked again on every refresh round (see the page's
         * loop): without it, a failing league looks permanently "never fetched" and is hammered.
         */
        val lastAttemptMs: Long = 0L,
        /** When the provider's fast row last answered for this day, so it is not asked twice over. */
        val liveRowAtMs: Long = 0L,
        /** When the fast row may be asked again after a refusal, so a broken address is not hammered. */
        val liveRowRetryAtMs: Long = 0L,
        /**
         * When the fast row last confirmed each live game. A scoreboard answer can be older than
         * the fast row reading it would replace (see `preferFastLive`), so this is carried across
         * scoreboard refreshes rather than rebuilt from them.
         */
        val livePatchedAt: Map<String, Long> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = games.isEmpty()
    }

    private val states = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<DayState>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val teamsCache = java.util.concurrent.ConcurrentHashMap<String, List<Team>>()
    private val standingsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Standing>, Long>>()
    private val standingsLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val _version = MutableStateFlow(0)

    /** Bumps when anything is fetched, so a screen showing several leagues can re-read once. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * How many scoreboards may be in flight at once. Opening the page asks for every league in the
     * region — 50-odd competitions — and a burst that wide makes ESPN answer some of them with a
     * rate limit or a 5xx, which the page then reports as "could not refresh". They queue instead,
     * a few at a time: the day fills in progressively and no request is refused.
     */
    private val feedSlots = Semaphore(4)

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun stateKey(leagueId: String, day: String) = "$leagueId|$day"

    fun dayState(leagueId: String, day: String): StateFlow<DayState> =
        states.getOrPut(stateKey(leagueId, day)) {
            MutableStateFlow(DayState(leagueId = leagueId, day = day))
        }.asStateFlow()

    /** Today in the user's own zone, as `yyyymmdd`. */
    fun today(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).format(dayFormatter)

    fun day(minusDays: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDate.now(zone).minusDays(minusDays).format(dayFormatter)

    /**
     * Makes [leagueId]'s games for [day] available: the disk cache first (shown at once), then a
     * download when the cache is missing or stale. [force] skips the freshness check.
     *
     * Concurrent calls for one league and day collapse into one, as [IptvRepository.refresh] does:
     * the date strip re-composing must not start a second download.
     */
    fun refresh(leagueId: String, day: String, force: Boolean = false) {
        val league = SportsCatalog.league(leagueId) ?: return
        val state = states.getOrPut(stateKey(leagueId, day)) {
            MutableStateFlow(DayState(leagueId = leagueId, day = day))
        }
        val lock = locks.getOrPut(stateKey(leagueId, day)) { Mutex() }
        if (!lock.tryLock()) return
        scope.launch {
            try {
                if (state.value.games.isEmpty()) {
                    SportsCache.readGames(leagueId, day, day)?.let { cached ->
                        state.update {
                            it.copy(games = cached.games, fetchedAtMs = cached.fetchedAtMs)
                        }
                        _version.update { it + 1 }
                    }
                }
                val now = System.currentTimeMillis()
                if (!force && !daySnapshotStale(day, state.value.games, state.value.fetchedAtMs, now)) return@launch

                state.update { it.copy(loading = true, error = null, lastAttemptMs = now) }
                try {
                    val games = feedSlots.withPermit { SportsFeeds.current.scoreboard(league, day, day) }
                    // Anchor the clocks when the answer actually arrived, not when the request
                    // started: a slow response would otherwise age every clock by the round trip.
                    val readAt = System.currentTimeMillis()
                    // Work out which live clocks are running against what the page already shows,
                    // so the card's local ticker knows when to stop (see stampLiveClocks). A live
                    // game the fast row confirmed moments ago keeps those live fields: the
                    // scoreboard's cached answer can be older, and must not rewind the score.
                    state.update { before ->
                        val stamped = stampLiveClocks(before.games, games, readAt)
                        before.copy(
                            games = preferFastLive(stamped, before.games, before.livePatchedAt, readAt),
                            loading = false,
                            error = null,
                            fetchedAtMs = readAt,
                            lastAttemptMs = readAt,
                            livePatchedAt = before.livePatchedAt
                                .filterValues { readAt - it < LIVE_PATCH_TRUST_MS },
                        )
                    }
                    SportsCache.writeGames(leagueId, day, day, state.value.games, readAt)
                    _version.update { it + 1 }
                    log.i { "Loaded ${league.name} $day: ${games.size} games" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Keep whatever is on screen; the error is shown beside it, not instead of it.
                    state.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: "Could not load games",
                            lastAttemptMs = System.currentTimeMillis(),
                        )
                    }
                    log.w { "Sports refresh failed for ${league.id} $day: ${e.message}" }
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /** [leagueId]'s teams, from memory, then disk, then the feed. For the follow picker. */
    fun teams(leagueId: String, onLoaded: (List<Team>) -> Unit = {}) {
        teamsCache[leagueId]?.let { cached ->
            onLoaded(cached)
            return
        }
        val league = SportsCatalog.league(leagueId) ?: return
        scope.launch {
            val cached = SportsCache.readTeams(leagueId)
            if (cached != null) {
                teamsCache[leagueId] = cached.teams
                onLoaded(cached.teams)
                if (System.currentTimeMillis() - cached.fetchedAtMs < Freshness.TEAMS_MS) return@launch
            }
            runCatching { SportsFeeds.current.teams(league) }
                .onSuccess { teams ->
                    teamsCache[leagueId] = teams
                    SportsCache.writeTeams(leagueId, teams, System.currentTimeMillis())
                    onLoaded(teams)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Could not load ${league.name} teams: ${error.message}" }
                }
        }
    }

    private val liveRowInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** How long a refused fast row waits before it is tried again. */
    private const val LIVE_ROW_RETRY_MS = 30_000L

    /**
     * Re-reads [leagueId]'s games in progress from the provider's fast row and patches what the cards
     * draw - score, clock, period, state, last play - leaving everything else (logos, venue, odds,
     * the matched channel) as the scoreboard parsed it. Silent on failure: the scoreboard's data
     * stays, and the page's own round reports a real failure if the league is really gone.
     *
     * Called often (a few seconds), so it declines a second request for the same league while one is
     * in flight, and does nothing when the day has nothing in progress.
     */
    fun refreshLive(leagueId: String, day: String) {
        val league = SportsCatalog.league(leagueId) ?: return
        val state = states[stateKey(leagueId, day)] ?: return
        val current = state.value
        if (current.games.none { it.state == GameState.LIVE }) return
        val now = System.currentTimeMillis()
        if (now - current.liveRowAtMs < Freshness.LIVE_ROW_MS) return
        if (now < current.liveRowRetryAtMs) return
        if (!liveRowInFlight.add(leagueId)) return
        scope.launch {
            try {
                val fresh = try {
                    SportsFeeds.current.liveScores(league)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (fresh == null) {
                    // Refused or unreachable: leave the scoreboard's data alone and wait before
                    // asking a broken address again.
                    state.update { it.copy(liveRowRetryAtMs = System.currentTimeMillis() + LIVE_ROW_RETRY_MS) }
                    return@launch
                }
                if (fresh.isEmpty()) return@launch
                val readAt = System.currentTimeMillis()
                val confirmed = fresh.filter { it.isLive }.associate { it.id to readAt }
                var changed = false
                state.update { before ->
                    val merged = mergeLiveRow(before.games, fresh, readAt)
                    changed = merged != before.games
                    before.copy(
                        games = merged,
                        liveRowAtMs = now,
                        liveRowRetryAtMs = 0L,
                        livePatchedAt = before.livePatchedAt
                            .filterValues { readAt - it < LIVE_PATCH_TRUST_MS } + confirmed,
                    )
                }
                if (changed) _version.update { it + 1 }
            } finally {
                liveRowInFlight.remove(leagueId)
            }
        }
    }

    /** One game's detail, fetched on open rather than with the scoreboard. */
    suspend fun summary(leagueId: String, eventId: String): GameSummary? {
        val league = SportsCatalog.league(leagueId) ?: return null
        return runCatching { SportsFeeds.current.summary(league, eventId) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Could not load summary for $eventId: ${error.message}" }
            }
            .getOrNull()
    }

    sealed interface StandingsResult {
        data class Loaded(val rows: List<Standing>, val stale: Boolean = false) : StandingsResult
        data object NoTable : StandingsResult
        data object Failed : StandingsResult
    }

    /**
     * Loads [leagueId]'s standings from memory/disk cache (fresh for [Freshness.STANDINGS_MS]) or
     * the feed. Distinguishes a cup competition with no table ([StandingsResult.NoTable]) from a
     * failed network request ([StandingsResult.Failed]), and keeps cached rows if a refresh fails.
     */
    suspend fun loadStandings(leagueId: String, force: Boolean = false): StandingsResult {
        val league = SportsCatalog.league(leagueId) ?: return StandingsResult.NoTable
        val lock = standingsLocks.getOrPut(leagueId) { Mutex() }
        return lock.withLock {
            val now = System.currentTimeMillis()
            val inMem = standingsCache[leagueId]
            if (!force && inMem != null && now - inMem.second < Freshness.STANDINGS_MS) {
                return@withLock if (inMem.first.isEmpty()) StandingsResult.NoTable else StandingsResult.Loaded(inMem.first)
            }
            val onDisk = if (inMem == null) SportsCache.readStandings(leagueId) else null
            if (onDisk != null) {
                standingsCache[leagueId] = onDisk.rows to onDisk.fetchedAtMs
                if (!force && now - onDisk.fetchedAtMs < Freshness.STANDINGS_MS) {
                    return@withLock if (onDisk.rows.isEmpty()) StandingsResult.NoTable else StandingsResult.Loaded(onDisk.rows)
                }
            }
            val fallbackRows = inMem?.first ?: onDisk?.rows
            try {
                val fresh = SportsFeeds.current.standings(league)
                val fetchedAt = System.currentTimeMillis()
                standingsCache[leagueId] = fresh to fetchedAt
                SportsCache.writeStandings(leagueId, fresh, fetchedAt)
                if (fresh.isEmpty()) StandingsResult.NoTable else StandingsResult.Loaded(fresh)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Could not load ${league.name} standings: ${error.message}" }
                if (!fallbackRows.isNullOrEmpty()) {
                    StandingsResult.Loaded(fallbackRows, stale = true)
                } else {
                    StandingsResult.Failed
                }
            }
        }
    }

    /** A league's table, or null when it has none or could not be reached. */
    suspend fun standings(leagueId: String): List<Standing>? =
        (loadStandings(leagueId) as? StandingsResult.Loaded)?.rows

    /** Every game already loaded in memory for [leagueId], ordered by kick-off. */
    fun knownGames(leagueId: String): List<SportsEvent> =
        states.values.asSequence()
            .map { it.value }
            .filter { it.leagueId == leagueId }
            .flatMap { it.games.asSequence() }
            .distinctBy { it.id }
            .sortedBy { it.startMs }
            .toList()

    /**
     * The days after today, fetched as one range call for the "games ahead" rows under a followed
     * team. Memory only — the day cache stays keyed to the day strip — and refreshed on the same
     * cadence as any future-day snapshot.
     */
    data class AheadState(
        val leagueId: String,
        val games: List<SportsEvent> = emptyList(),
        val loading: Boolean = false,
        val fetchedAtMs: Long = 0L,
    )

    private val aheadCache = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<AheadState>>()
    private val aheadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    fun aheadState(leagueId: String): StateFlow<AheadState> =
        aheadCache.getOrPut(leagueId) { MutableStateFlow(AheadState(leagueId)) }.asStateFlow()

    fun refreshAhead(leagueId: String, aheadDays: Int = 21, force: Boolean = false) {
        val league = SportsCatalog.league(leagueId) ?: return
        val state = aheadCache.getOrPut(leagueId) { MutableStateFlow(AheadState(leagueId)) }
        val lock = aheadLocks.getOrPut(leagueId) { Mutex() }
        if (!lock.tryLock()) return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                if (!force && state.value.games.isNotEmpty() && now - state.value.fetchedAtMs < Freshness.FUTURE_MS) {
                    return@launch
                }
                state.update { it.copy(loading = true) }
                val from = LocalDate.now().plusDays(1).format(dayFormatter)
                val to = LocalDate.now().plusDays(aheadDays.toLong()).format(dayFormatter)
                try {
                    val games = SportsFeeds.current.scoreboard(league, from, to)
                    state.value = AheadState(leagueId, games, loading = false, fetchedAtMs = now)
                    _version.update { it + 1 }
                    log.i { "Loaded ${league.name} ahead: ${games.size} games" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    state.update { it.copy(loading = false) }
                    log.w { "Sports ahead fetch failed for ${league.id}: ${e.message}" }
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /** Drops every league's games and tables in memory and on disk, for Settings > Clear sports cache. */
    fun clear() {
        states.values.forEach { state -> state.update { DayState(it.leagueId, it.day) } }
        standingsCache.clear()
        SportsCache.clearGames()
        _version.update { it + 1 }
    }

    internal fun forgetAll() {
        states.clear()
        locks.clear()
        teamsCache.clear()
        standingsCache.clear()
        standingsLocks.clear()
    }

    /**
     * Whether [day]'s cached snapshot is old enough that a fetch is worth making. Age against the
     * day's TTL decides in the normal case — a live day changes constantly, a future day barely at
     * all, and a finished day never again — so the intervals stay far apart and the cheap ones do
     * not hammer a feed that has no rate limit but also no obligation to us.
     *
     * The content check is the bug this exists for: a past-day snapshot captured while its games
     * were still in progress used to sit under the 24-hour TTL, so going back to yesterday served
     * the frozen "Live" scoreboard of the moment it was cached — a live match interface, clock
     * stopped, for a game that had been over for hours. A snapshot of a past day in which any game
     * has not clearly finished is treated as stale, forcing one corrective fetch that overwrites
     * the cache with the finished states. (A past day can legitimately hold a live game — a match
     * that bleeds across local midnight, or a multi-day contest — and the corrective fetch keeps
     * it live rather than breaking it.)
     */
    internal fun daySnapshotStale(day: String, games: List<SportsEvent>, fetchedAtMs: Long, nowMs: Long): Boolean {
        if (fetchedAtMs <= 0L) return true
        val today = today()
        val ttl = when {
            day > today -> Freshness.FUTURE_MS
            day < today -> Freshness.PAST_MS
            // Today is the only day that changes while it is being watched: the scoreboard carries
            // a running clock and a live score. A five-minute cache over it (which is what this
            // once was) served the same frozen numbers for the whole first five minutes of every
            // visit, so the page looked dead even once it started asking again on a timer.
            // Nothing on yet (or everything finished): a minute, which is how fast a kick-off or a
            // final whistle needs to be noticed.
            games.any { it.state == GameState.LIVE } -> Freshness.LIVE_MS
            else -> Freshness.TODAY_MS
        }
        if (nowMs - fetchedAtMs >= ttl) return true
        return day < today && games.any { it.state != GameState.POST }
    }
}
