package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 4a: results appear, and a stale page says so.
 *
 * The change that matters here is that finished games are no longer dropped. For a followed team
 * the last result is the thing people open the page for, so the split and its ordering are pinned.
 */
class SportsResultsAndStalenessTest {

    private fun game(id: String, state: GameState, startMs: Long) = SportsEvent(
        id = id,
        leagueId = "football/nfl",
        startMs = startMs,
        state = state,
        away = GameSide(Team("1", "Chicago Bears", "Bears", "CHI"), score = "17"),
        home = GameSide(Team("2", "Detroit Lions", "Lions", "DET"), score = "20"),
    )

    @Test
    fun `finished games are results, not dropped`() {
        val split = splitDay(listOf(game("a", GameState.POST, 1_000L)))

        assertEquals(listOf("a"), split.results.map { it.id })
        assertTrue(split.live.isEmpty())
        assertTrue(split.upcoming.isEmpty())
    }

    @Test
    fun `results run most recent first, the other sections run soonest first`() {
        val split = splitDay(
            listOf(
                game("old", GameState.POST, 1_000L),
                game("new", GameState.POST, 9_000L),
                game("soon", GameState.PRE, 1_000L),
                game("later", GameState.PRE, 9_000L),
            ),
        )

        // The last result is the interesting one; the next game is what matters in the other two.
        assertEquals(listOf("new", "old"), split.results.map { it.id })
        assertEquals(listOf("soon", "later"), split.upcoming.map { it.id })
    }

    @Test
    fun `past selected dates categorize every event as past games`() {
        val split = splitDayForDate(
            listOf(game("post", GameState.POST, 3L), game("stale-live", GameState.LIVE, 2L), game("pre", GameState.PRE, 1L)),
            day = "20260109",
            today = "20260110",
        )

        assertTrue(split.live.isEmpty())
        assertTrue(split.upcoming.isEmpty())
        assertEquals(listOf("post", "stale-live", "pre"), split.results.map { it.id })
    }

    @Test
    fun `future selected dates categorize every event as upcoming`() {
        val split = splitDayForDate(
            listOf(game("post", GameState.POST, 3L), game("live", GameState.LIVE, 2L), game("pre", GameState.PRE, 1L)),
            day = "20260111",
            today = "20260110",
        )

        assertTrue(split.live.isEmpty())
        assertTrue(split.results.isEmpty())
        assertEquals(listOf("pre", "live", "post"), split.upcoming.map { it.id })
    }

    @Test
    fun `today retains actual live upcoming and past states`() {
        val games = listOf(game("live", GameState.LIVE, 2L), game("pre", GameState.PRE, 1L), game("post", GameState.POST, 3L))

        val split = splitDayForDate(games, day = "20260110", today = "20260110")

        assertEquals(listOf("live"), split.live.map { it.id })
        assertEquals(listOf("pre"), split.upcoming.map { it.id })
        assertEquals(listOf("post"), split.results.map { it.id })
    }

    @Test
    fun `a day of nothing is empty, which drives the empty state`() {
        assertTrue(splitDay(emptyList()).isEmpty)
        // One finished game is not nothing: a past day with results must not say "No games".
        assertTrue(!splitDay(listOf(game("a", GameState.POST, 1_000L))).isEmpty)
    }

    @Test
    fun `every game lands in exactly one section`() {
        val games = listOf(
            game("l", GameState.LIVE, 1L),
            game("p", GameState.PRE, 2L),
            game("f", GameState.POST, 3L),
        )

        val split = splitDay(games)

        assertEquals(games.map { it.id }.toSet(), (split.live + split.upcoming + split.results).map { it.id }.toSet())
        assertEquals(3, split.live.size + split.upcoming.size + split.results.size)
    }

    @Test
    fun `a followed team's finished game is still in My Sports`() {
        // The end-to-end shape of the request: favourite a team, see its recent result.
        val settings = SportsSettings(followedTeams = setOf(SportsFollows.teamKey("football/nfl", "1")))
        val finished = game("a", GameState.POST, 1_000L)

        assertTrue(SportsFollows.isInMySports(settings, finished))
        assertEquals(listOf("a"), splitDay(SportsFollows.mySports(settings, listOf(finished))).results.map { it.id })
    }

    @Test
    fun `sports event stream id carries ESPN league path and event id`() {
        val league = SportsCatalog.league("football/nfl")!!
        val event = game("401234567", GameState.PRE, 1L)

        assertEquals("espn:football/nfl:401234567", sportsEventStreamId(league, event))
    }

    @Test
    fun `staleness reads in whole minutes, then hours`() {
        val now = 1_000_000_000_000L
        fun age(minutesAgo: Long) = relativeAge(now - minutesAgo * 60_000L, now)

        assertEquals("just now", age(0))
        assertEquals("1 minute ago", age(1))
        assertEquals("42 minutes ago", age(42))
        assertEquals("1 hour ago", age(60))
        assertEquals("3 hours ago", age(180))
    }

    @Test
    fun `never fetched has no age to show`() {
        // The note then reads plainly "Could not refresh", rather than "showing null".
        assertNull(relativeAge(0L))
    }

    @Test
    fun `a clock that moved backwards does not read as a negative age`() {
        val now = 1_000_000_000_000L
        assertEquals("just now", relativeAge(now + 60_000L, now))
    }

    // ---- Live-state sanity: the feed's state is trusted unless it is impossible.

    private val now = 1_760_000_000_000L

    @Test
    fun `a recently started live game stays live`() {
        val live = game("l", GameState.LIVE, now - 90 * 60_000L)

        assertEquals(GameState.LIVE, resolvedGameState(live, now))
    }

    @Test
    fun `a live game whose start was many hours ago reads as finished`() {
        // The stale snapshot a past-day cache or a stuck feed can leave behind: the game ended
        // last night but still carries "Live 3rd · 9:42". It must not draw a live interface.
        val stale = game("l", GameState.LIVE, now - 20 * 60 * 60_000L)

        assertEquals(GameState.POST, resolvedGameState(stale, now))
        assertEquals(GameState.POST, stale.withResolvedState(now).state)
    }

    @Test
    fun `a game marked live before its kick-off reads as upcoming`() {
        // The provider flips some games to "in" early; within the slack it stays live, beyond it
        // the feed is plainly wrong about the clock.
        assertEquals(GameState.LIVE, resolvedGameState(game("l", GameState.LIVE, now + 5 * 60_000L), now))
        assertEquals(GameState.PRE, resolvedGameState(game("l", GameState.LIVE, now + 3 * 60 * 60_000L), now))
    }

    @Test
    fun `a cricket match running into a second day is still live`() {
        // Test matches legitimately run for days; the long-window clamp must not end them.
        val cricket = SportsEvent(
            id = "c",
            leagueId = "cricket/8048",
            startMs = now - 30 * 60 * 60_000L, // 30 hours ago
            state = GameState.LIVE,
            away = GameSide(Team("1", "England", "England", "ENG")),
            home = GameSide(Team("2", "Australia", "Australia", "AUS")),
        )

        assertEquals(GameState.LIVE, resolvedGameState(cricket, now))
    }

    @Test
    fun `pre and post states pass through untouched`() {
        assertEquals(GameState.PRE, resolvedGameState(game("p", GameState.PRE, now + 60_000L), now))
        assertEquals(GameState.POST, resolvedGameState(game("f", GameState.POST, now - 60_000L), now))
    }

    @Test
    fun `resolving an already-true state keeps the same game`() {
        val live = game("l", GameState.LIVE, now - 90 * 60_000L)

        assertTrue(live.withResolvedState(now) === live, "unchanged games must keep identity so keyed items do not rebuild")
    }

    // ---- Snapshot staleness: what the repository refetches, and what it may keep serving.

    private val today = SportsRepository.today()
    private val yesterday = SportsRepository.day(1)

    private fun snapshotGames(state: GameState) = listOf(game("g", state, now - 3 * 60 * 60_000L))

    @Test
    fun `a past-day snapshot within its TTL with finished games is fresh`() {
        val fetched = now - 2 * 60 * 60_000L

        assertTrue(!SportsRepository.daySnapshotStale(yesterday, snapshotGames(GameState.POST), fetched, now))
    }

    @Test
    fun `a past-day snapshot that caught games mid-play is stale, whatever its age`() {
        // Frozen ten minutes ago or ten hours ago: a game that was live then and is cached now
        // would otherwise draw yesterday as a live match interface for the rest of the TTL.
        val fetched = now - 10 * 60_000L

        assertTrue(SportsRepository.daySnapshotStale(yesterday, snapshotGames(GameState.LIVE), fetched, now))
        assertTrue(SportsRepository.daySnapshotStale(yesterday, snapshotGames(GameState.PRE), fetched, now))
    }

    @Test
    fun `beyond the TTL any snapshot is stale, finished games included`() {
        val fetched = now - SportsRepository.Freshness.PAST_MS - 60_000L

        assertTrue(SportsRepository.daySnapshotStale(yesterday, snapshotGames(GameState.POST), fetched, now))
    }

    @Test
    fun `never fetched is stale, and a live today snapshot inside its TTL is not`() {
        assertTrue(SportsRepository.daySnapshotStale(yesterday, emptyList(), 0L, now))
        assertTrue(
            !SportsRepository.daySnapshotStale(today, snapshotGames(GameState.LIVE), now - 5_000L, now),
            "today's own 10-second TTL already governs its live scoreboard",
        )
        assertTrue(
            SportsRepository.daySnapshotStale(today, snapshotGames(GameState.LIVE), now - 15_000L, now),
            "a live scoreboard older than 10 s is fetched again",
        )
    }

    @Test
    fun `today with nothing in progress is kept for a minute, not ten seconds`() {
        // Nothing is being played, so only a kick-off or a final whistle can change the day; the
        // catalog as a whole can be asked at that pace.
        assertTrue(!SportsRepository.daySnapshotStale(today, snapshotGames(GameState.PRE), now - 45_000L, now))
        assertTrue(SportsRepository.daySnapshotStale(today, snapshotGames(GameState.PRE), now - 61_000L, now))
    }
}
