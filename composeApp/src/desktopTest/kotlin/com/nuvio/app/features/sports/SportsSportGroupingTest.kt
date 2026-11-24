package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The slate is grouped the way a viewer looks for a game.
 *
 * With several sports on a day, the heading worth having is the sport ("Basketball"), and each card
 * carries its own league. Narrowed to one sport by the chip, the league is the heading ("NBA"), not
 * a single "Basketball" band holding every league in it. A sport with something followed in it leads.
 */
class SportsSportGroupingTest {

    private fun game(id: String, leagueId: String, state: GameState, startMs: Long = 1_000L) = SportsEvent(
        id = id,
        leagueId = leagueId,
        startMs = startMs,
        state = state,
        away = GameSide(Team("1", "Away Side", "Away", "AWY")),
        home = GameSide(Team("2", "Home Side", "Home", "HOM")),
    )

    private val leagues = listOf("football/nfl", "basketball/nba", "mma/ufc", "mma/pfl")
        .mapNotNull { SportsCatalog.league(it) }

    private fun sections(
        upcoming: List<SportsEvent>,
        leagues: List<League> = this.leagues,
        settings: SportsSettings = SportsSettings(),
    ) = buildGridSections(
        live = emptyList(),
        upcoming = upcoming,
        results = emptyList(),
        leagues = leagues,
        settings = settings,
    )

    @Test
    fun `several sports give one section per sport, in the catalog's sport order`() {
        val built = sections(
            listOf(
                game("ufc-1", "mma/ufc", GameState.PRE),
                game("nfl-1", "football/nfl", GameState.PRE),
                game("nba-1", "basketball/nba", GameState.PRE),
            ),
        )

        assertEquals(
            listOf("American Football · Upcoming", "Basketball · Upcoming", "Fighting · Upcoming"),
            built.map { it.title },
        )
        assertEquals(listOf("nfl-1"), built[0].items.map { it.id })
        assertEquals(listOf("nba-1"), built[1].items.map { it.id })
        assertEquals(listOf("ufc-1"), built[2].items.map { it.id })
    }

    @Test
    fun `a followed sport leads, the rest keep their order`() {
        val built = sections(
            listOf(
                game("nfl-1", "football/nfl", GameState.PRE),
                game("nba-1", "basketball/nba", GameState.PRE),
                game("ufc-1", "mma/ufc", GameState.PRE),
                game("pfl-1", "mma/pfl", GameState.PRE),
            ),
            settings = SportsSettings(followedLeagues = setOf("mma/pfl")),
        )

        // Both fighting leagues sit in one section, and that section is first.
        assertEquals("Fighting · Upcoming", built.first().title)
        assertEquals(listOf("ufc-1", "pfl-1"), built.first().items.map { it.id })
    }

    @Test
    fun `one sport keeps its per-league sections`() {
        val fightLeagues = leagues.filter { it.sport == Sport.FIGHTING }
        val built = sections(
            listOf(
                game("ufc-1", "mma/ufc", GameState.PRE),
                game("pfl-1", "mma/pfl", GameState.PRE),
            ),
            leagues = fightLeagues,
        )

        assertEquals(listOf("UFC · Upcoming", "PFL · Upcoming"), built.map { it.title })
        assertTrue(built.all { it.items.size == 1 })
    }

    @Test
    fun `live games stay one band above the sport sections`() {
        val built = buildGridSections(
            live = listOf(game("nfl-live", "football/nfl", GameState.LIVE)),
            upcoming = listOf(game("nba-1", "basketball/nba", GameState.PRE)),
            results = emptyList(),
            leagues = leagues,
            settings = SportsSettings(),
        )

        assertEquals(listOf("LIVE NOW", "Basketball · Upcoming"), built.map { it.title })
    }

    @Test
    fun `results are grouped by sport too, and keep their own heading`() {
        val built = buildGridSections(
            live = emptyList(),
            upcoming = emptyList(),
            results = listOf(
                game("nfl-done", "football/nfl", GameState.POST),
                game("nba-done", "basketball/nba", GameState.POST),
            ),
            leagues = leagues,
            settings = SportsSettings(),
        )

        assertEquals(
            listOf("American Football · Past Games", "Basketball · Past Games"),
            built.map { it.title },
        )
    }

    @Test
    fun `my sports groups the day by sport and keeps its followed-team rows underneath`() {
        val built = buildGridSections(
            live = emptyList(),
            upcoming = listOf(
                game("nfl-1", "football/nfl", GameState.PRE),
                game("nba-1", "basketball/nba", GameState.PRE),
            ),
            results = emptyList(),
            leagues = leagues,
            settings = SportsSettings(),
            teamMode = true,
            teamSections = listOf(
                SportsTeamAhead(
                    teamKey = "football/nfl:1",
                    leagueId = "football/nfl",
                    team = Team("1", "Away Side", "Away", "AWY"),
                    note = "2nd in AFC North",
                    games = listOf(game("nfl-ahead", "football/nfl", GameState.PRE, startMs = 9_000L)),
                ),
            ),
        )

        // The day is grouped like the everything-view, and the team's own games-ahead row follows.
        assertEquals(
            listOf("American Football · Upcoming", "Basketball · Upcoming", "Away · 2nd in AFC North"),
            built.map { it.title },
        )
        assertEquals(listOf("nfl-ahead"), built.last().items.map { it.id })
    }

    @Test
    fun `a league with a game on is polled, one with nothing on is not`() {
        val now = 1_000_000_000_000L
        val live = game("live", "football/nfl", GameState.LIVE, startMs = now - 30 * 60_000L)
        val soon = game("soon", "basketball/nba", GameState.PRE, startMs = now + 90 * 60_000L)
        val tomorrow = game("tomorrow", "mma/ufc", GameState.PRE, startMs = now + 30 * 60 * 60_000L)
        val lastWeek = game("last-week", "mma/pfl", GameState.POST, startMs = now - 7 * 24 * 60 * 60_000L)

        assertTrue(worthPolling(live, now))
        assertTrue(worthPolling(soon, now))
        assertFalse(worthPolling(tomorrow, now))
        assertFalse(worthPolling(lastWeek, now))
    }

    @Test
    fun `a live game is asked every round, a near one every minute, an idle one every five`() {
        val now = 1_000_000_000_000L
        val live = game("live", "football/nfl", GameState.LIVE, startMs = now - 30 * 60_000L)
        val soon = game("soon", "basketball/nba", GameState.PRE, startMs = now + 90 * 60_000L)
        val tomorrow = game("tomorrow", "mma/ufc", GameState.PRE, startMs = now + 30 * 60 * 60_000L)

        assertEquals(SportsPollTier.LIVE, pollTierFor(listOf(live), now))
        assertEquals(SportsPollTier.NEAR, pollTierFor(listOf(soon), now))
        assertEquals(SportsPollTier.COLD, pollTierFor(listOf(tomorrow), now))
        // One live game in a league's day is enough to keep that league on the fast pace.
        assertEquals(SportsPollTier.LIVE, pollTierFor(listOf(tomorrow, live), now))
        assertEquals(SportsPollTier.COLD, pollTierFor(emptyList(), now))

        // Rounds are 10 s: LIVE every round, NEAR every sixth (a minute), COLD every thirtieth.
        assertTrue((0..29).all { pollDueThisRound(SportsPollTier.LIVE, it) })
        assertEquals((0..29).filter { pollDueThisRound(SportsPollTier.NEAR, it) }, listOf(0, 6, 12, 18, 24))
        assertEquals((0..59).filter { pollDueThisRound(SportsPollTier.COLD, it) }, listOf(0, 30))
        // A league whose last answer was a failure backs off to the minute pace, whatever it holds:
        // re-asking a rate-limited feed every round only extends the rate limit.
        assertEquals(
            (0..29).filter { pollDueThisRound(SportsPollTier.LIVE, it, failing = true) },
            listOf(0, 6, 12, 18, 24),
        )
    }

    @Test
    fun `a failed refresh is only reported when it can cost the viewer something`() {
        val now = 1_000_000_000_000L

        // Nothing worth saying: every league that failed has nothing on today anyway.
        assertNull(staleNoteText(failures = 0, leagues = 55, oldestFailedFetchMs = 0L, nowMs = now))
        // Data arrived seconds ago: "showing just now" is not a warning, so no age is claimed.
        assertEquals(
            "Some competitions could not refresh",
            staleNoteText(failures = 2, leagues = 55, oldestFailedFetchMs = now - 20_000L, nowMs = now),
        )
        // Some real age: say which.
        assertEquals(
            "Some competitions could not refresh · data from 42 minutes ago",
            staleNoteText(failures = 2, leagues = 55, oldestFailedFetchMs = now - 42 * 60_000L, nowMs = now),
        )
        // Everything failed: the page itself is the stale one.
        assertEquals(
            "Could not refresh · showing 2 hours ago",
            staleNoteText(failures = 55, leagues = 55, oldestFailedFetchMs = now - 120 * 60_000L, nowMs = now),
        )
        assertEquals("Could not refresh", staleNoteText(failures = 55, leagues = 55, oldestFailedFetchMs = 0L, nowMs = now))
    }
}
