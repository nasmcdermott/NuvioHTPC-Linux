package com.nuvio.app.features.sports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fast score row: the address the provider serves it on, what this client takes from its payload,
 * and what [mergeLiveRow] is allowed to overwrite in a card the scoreboard already filled in.
 *
 * The fixtures are the real shape ESPN answers `scoreboard/header` with, trimmed to the fields this
 * client reads - the row nests competitors and the status on the event itself, unlike the scoreboard.
 */
class SportsLiveRowTest {

    private val league = SportsCatalog.league("basketball/nba")!!

    // A finished game, as the row serves it (captured from a live slate on 24 Nov 2026).
    private val finishedRow = """
        {"sports":[{"id":"40","name":"Basketball","slug":"basketball","leagues":[{"id":"46","name":
        "National Basketball Association","abbreviation":"NBA","shortName":"NBA","slug":"nba","events":
        [{"id":"401910036","date":"2026-11-24T00:00:00Z","name":"Memphis Grizzlies at Charlotte Hornets",
        "shortName":"MEM @ CHA","status":"post","fullStatus":{"clock":720,"displayClock":"12:00",
        "period":4,"displayPeriod":"4th","type":{"id":"3","name":"STATUS_FINAL","state":"post",
        "completed":true,"description":"Final","detail":"Final","shortDetail":"Final"}},"competitors":
        [{"id":"29","homeAway":"away","abbreviation":"MEM","displayName":"Memphis Grizzlies",
        "score":"117","winner":true,"logo":"https://a.espncdn.com/i/teamlogos/nba/500/scoreboard/mem.png",
        "color":"5d76a9"},{"id":"30","homeAway":"home","abbreviation":"CHA",
        "displayName":"Charlotte Hornets","score":"111","winner":false,
        "logo":"https://a.espncdn.com/i/teamlogos/nba/500/scoreboard/cha.png","color":"008ca8"}],
        "situation":{"lastPlay":{"id":"1","text":"Miles Bridges makes 25-foot three point jumper",
        "clock":{"displayValue":"0:04"}}}}]}]}]}
    """.trimIndent().replace("\n", "")

    // The same game while it was in progress: clock counting down, scores a few points behind.
    private val liveRow = finishedRow
        .replace("\"status\":\"post\"", "\"status\":\"in\"")
        .replace("\"state\":\"post\"", "\"state\":\"in\"")
        .replace("\"completed\":true", "\"completed\":false")
        .replace("\"shortDetail\":\"Final\"", "\"shortDetail\":\"3:53 - 4th Quarter\"")
        .replace("\"detail\":\"Final\"", "\"detail\":\"3:53 - 4th Quarter\"")
        .replace("\"displayClock\":\"12:00\"", "\"displayClock\":\"3:53\"")
        .replace("\"clock\":720", "\"clock\":233")
        .replace("\"score\":\"117\"", "\"score\":\"103\"")
        .replace("\"score\":\"111\"", "\"score\":\"99\"")

    private fun feed(payload: String) = EspnSportsFeed(get = { payload })

    @Test
    fun `the fast row is asked for by the league's own two path halves`() {
        assertEquals(
            "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=basketball&league=nba",
            feed("{}").liveScoresUrl(league),
        )
        // Soccer's second half carries a dot, and must survive the split.
        assertEquals(
            "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=soccer&league=eng.1",
            feed("{}").liveScoresUrl(SportsCatalog.league("soccer/eng.1")!!),
        )
    }

    @Test
    fun `a game in progress is read with its score, clock, period and last play`() = runTest {
        val games = feed(liveRow).liveScores(league)!!
        assertEquals(1, games.size)
        val game = games.single()
        assertEquals("401910036", game.id)
        assertEquals(GameState.LIVE, game.state)
        assertEquals("3:53", game.clock)
        assertEquals("3:53 - 4th Quarter", game.status)
        assertEquals("103", game.away.score)
        assertEquals("99", game.home.score)
        assertEquals("MEM", game.away.team.abbreviation)
        // The row carries logos too, so a card that had none is not left blank.
        assertTrue(game.away.team.logo!!.endsWith("mem.png"))
        assertTrue(game.situation!!.contains("three point jumper"))
    }

    @Test
    fun `a finished game comes back as post with Final`() = runTest {
        val game = feed(finishedRow).liveScores(league)!!.single()
        assertEquals(GameState.POST, game.state)
        assertEquals("Final", game.status)
        // "0:00"/"12:00" is not a running clock.
        assertNull(game.clock)
    }

    @Test
    fun `a league the fast row refuses leaves the scoreboard data alone`() = runTest {
        assertNull(feed("{\"code\":400}").liveScores(league))
        assertNull(feed("not json at all").liveScores(league))
    }

    // ---- mergeLiveRow: what the fast round is allowed to change

    private fun scoreboardGame(
        state: GameState = GameState.LIVE,
        clock: String? = "4:10",
        status: String? = "4:10 - 4th Quarter",
        awayScore: String = "101",
        homeScore: String = "97",
        situation: String = "old play",
    ) = SportsEvent(
        id = "401910036",
        leagueId = league.id,
        startMs = 1_790_000_000_000,
        state = state,
        status = status,
        clock = clock,
        away = GameSide(
            team = Team("29", "Memphis Grizzlies", "Grizzlies", "MEM", logo = "logo-mem", color = 0x5d76a9),
            score = awayScore,
            record = "12-8",
        ),
        home = GameSide(
            team = Team("30", "Charlotte Hornets", "Hornets", "CHA", logo = "logo-cha", color = 0x008ca8),
            score = homeScore,
            record = "9-11",
        ),
        situation = situation,
        venue = "Spectrum Center",
        network = "ESPN",
        broadcasts = listOf("ESPN"),
        progress = 0.75f,
    )

    @Test
    fun `the live parts come from the fast row`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val merged = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh)).single()

        assertEquals("103", merged.away.score)
        assertEquals("99", merged.home.score)
        assertEquals("3:53", merged.clock)
        assertEquals("3:53 - 4th Quarter", merged.status)
        assertEquals(GameState.LIVE, merged.state)
        assertTrue(merged.situation!!.contains("three point jumper"))
    }

    @Test
    fun `everything the scoreboard parsed that the fast row lacks is kept`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val merged = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh)).single()

        // The row has no venue, broadcast, record or moneyline, and its logos are the small
        // scoreboard set: a card already showing the scoreboard's must not lose them.
        assertEquals("Spectrum Center", merged.venue)
        assertEquals(listOf("ESPN"), merged.broadcasts)
        assertEquals("12-8", merged.away.record)
        assertEquals("logo-mem", merged.away.team.logo)
    }

    @Test
    fun `a game that finishes through the fast row loses its clock and says Final`() = runTest {
        val fresh = feed(finishedRow).liveScores(league)!!.single()
        val merged = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh)).single()

        assertEquals(GameState.POST, merged.state)
        assertNull(merged.clock, "a finished game has no running clock")
        assertEquals("Final", merged.status)
        assertEquals("117", merged.away.score)
    }

    @Test
    fun `a game the fast row does not list keeps exactly what it had`() = runTest {
        val other = scoreboardGame().copy(id = "999")
        val fresh = feed(liveRow).liveScores(league)!!.single()
        assertEquals(listOf(other), mergeLiveRow(listOf(other), listOf(fresh)))
    }

    @Test
    fun `a second copy of the same row changes nothing, so the page does not re-render`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val once = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L)
        // Equal lists mean no re-render: the page bumps its version only on a real change.
        assertEquals(once, mergeLiveRow(once, listOf(fresh), nowMs = 54_000L))
    }

    @Test
    fun `the fast row anchors the clock it brought`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val merged = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L).single()
        assertEquals("3:53", merged.clock)
        assertEquals(50_000L, merged.clockReadAtMs)
        assertTrue(merged.clockMoving)
    }

    @Test
    fun `the same fast reading twice keeps the first anchor, and eventually stops the clock`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val once = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L)
        val twice = mergeLiveRow(once, listOf(fresh), nowMs = 54_000L)
        assertEquals(50_000L, twice.single().clockReadAtMs)

        // A timeout: the provider keeps sending 3:53, so the ticker must stop at 3:53 rather than
        // count down through the stoppage.
        val held = mergeLiveRow(once, listOf(fresh), nowMs = 50_000L + CLOCK_HELD_AFTER_MS).single()
        assertEquals("3:53", held.clock)
        assertEquals(false, held.clockMoving)
    }

    @Test
    fun `a live row between periods drops the clock rather than leaving the old one to tick`() = runTest {
        val betweenPeriods = liveRow
            .replace("\"displayClock\":\"3:53\"", "\"displayClock\":\"0:00\"")
            .replace("\"shortDetail\":\"3:53 - 4th Quarter\"", "\"shortDetail\":\"End of 4th\"")
        val fresh = feed(betweenPeriods).liveScores(league)!!.single()
        val merged = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L).single()
        assertEquals("End of 4th", merged.status)
        assertNull(merged.clock)
        assertEquals(0L, merged.clockReadAtMs)
        assertEquals(false, merged.clockMoving)
    }

    @Test
    fun `a fresher fast row keeps its score when a slower scoreboard answers`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val patched = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L)
        val slower = scoreboardGame(
            clock = "4:10",
            status = "4:10 - 4th Quarter",
            awayScore = "101",
            homeScore = "97",
        )
        val kept = preferFastLive(
            newGames = listOf(slower),
            current = patched,
            patchedAtMs = mapOf("401910036" to 50_000L),
            nowMs = 54_000L,
        ).single()
        assertEquals("103", kept.away.score)
        assertEquals("99", kept.home.score)
        assertEquals("3:53", kept.clock)
        assertEquals(50_000L, kept.clockReadAtMs)
        assertEquals("3:53 - 4th Quarter", kept.status)
        assertEquals("Miles Bridges makes 25-foot three point jumper", kept.situation)
        // Everything the fast row does not carry still comes from the scoreboard.
        assertEquals("logo-mem", kept.away.team.logo)
    }

    @Test
    fun `once the fast row has been quiet, the scoreboard wins again`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val patched = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L)
        val newer = scoreboardGame(
            clock = "3:48",
            status = "3:48 - 4th Quarter",
            awayScore = "105",
            homeScore = "101",
        )
        val kept = preferFastLive(
            newGames = listOf(newer),
            current = patched,
            patchedAtMs = mapOf("401910036" to 50_000L),
            nowMs = 50_000L + LIVE_PATCH_TRUST_MS,
        ).single()
        assertEquals("105", kept.away.score)
        assertEquals("3:48", kept.clock)
    }

    @Test
    fun `a game that finished is not kept live by an old fast-row score`() = runTest {
        val fresh = feed(liveRow).liveScores(league)!!.single()
        val patched = mergeLiveRow(listOf(scoreboardGame()), listOf(fresh), nowMs = 50_000L)
        val final = scoreboardGame(
            state = GameState.POST,
            clock = null,
            status = "Final",
            awayScore = "110",
            homeScore = "104",
        )
        val kept = preferFastLive(
            newGames = listOf(final),
            current = patched,
            patchedAtMs = mapOf("401910036" to 50_000L),
            nowMs = 52_000L,
        ).single()
        assertEquals(GameState.POST, kept.state)
        assertEquals("Final", kept.status)
        assertEquals("110", kept.away.score)
    }
}
