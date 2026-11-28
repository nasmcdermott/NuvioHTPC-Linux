package com.nuvio.app.features.sports

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * fork:game-highlights — the past-game page's official-YouTube-highlights fallback: the query it
 * asks for, the official-channel filter, the game-day published filter, and the stream handed to
 * the player.
 */
class SportsYouTubeHighlightsTest {

    /** Milliseconds for a game played two days before the filter's reading of "now". */
    private val now = 1_800_000_000_000L
    private val twoDaysAgo = now - 2 * 24 * 60 * 60 * 1_000L

    private fun event(title: String? = null, startMs: Long = 1L) = SportsEvent(
        id = "event-1",
        leagueId = "nba",
        startMs = startMs,
        state = GameState.POST,
        title = title,
        away = GameSide(Team("away", "Boston Celtics", "Celtics", "BOS")),
        home = GameSide(Team("home", "Los Angeles Lakers", "Lakers", "LAL")),
    )

    private fun league() = League(
        id = "nba",
        sport = Sport.OTHER,
        name = "NBA",
        shortName = "NBA",
        path = "basketball/nba",
    )

    private fun hit(
        videoId: String,
        title: String,
        channel: String,
        verified: Boolean = true,
        duration: Int = 600,
        views: Long = 1_000,
        published: String = "2 days ago",
    ) = YouTubeSearchHit(
        videoId = videoId,
        title = title,
        channel = channel,
        verified = verified,
        durationSeconds = duration,
        views = views,
        publishedText = published,
    )

    @Test
    fun `the query names both sides, the highlight word and the league`() {
        assertEquals("Celtics Lakers highlights NBA", SportsYouTubeHighlights.searchQuery(event(), league()))
    }

    @Test
    fun `a titled event searches its own title instead of the sides`() {
        assertEquals("Las Vegas Grand Prix highlights", SportsYouTubeHighlights.searchQuery(event(title = "Las Vegas Grand Prix"), null))
    }

    @Test
    fun `only league or team channels keep their uploads`() {
        val hits = listOf(
            hit("a", "Celtics vs Lakers Highlights", "NBA", views = 5),
            hit("b", "Celtics vs Lakers Highlights", "Boston Celtics", views = 4),
            hit("c", "Celtics vs Lakers Highlights", "Hoops Central", views = 9),
            hit("d", "Celtics vs Lakers Highlights", "NBA", verified = false, views = 99),
        )

        val kept = SportsYouTubeHighlights.official(event(), league(), hits, nowMs = now)

        assertEquals(listOf("a", "b"), kept.map { it.videoId })
    }

    @Test
    fun `league channels sort above team channels and views break ties`() {
        val hits = listOf(
            hit("team", "Celtics vs Lakers Highlights", "Boston Celtics", views = 100),
            hit("league", "Celtics vs Lakers Highlights", "NBA", views = 10),
            hit("league2", "Celtics vs Lakers Full Game Highlights", "NBA", views = 50),
        )

        val kept = SportsYouTubeHighlights.official(event(), league(), hits, nowMs = now)

        assertEquals(listOf("league2", "league", "team"), kept.map { it.videoId })
    }

    @Test
    fun `nothing official survives without a league or team channel`() {
        val hits = listOf(hit("a", "Celtics vs Lakers Highlights", "Hoops Daily"))
        assertTrue(SportsYouTubeHighlights.official(event(), null, hits, nowMs = now).isEmpty())
    }

    @Test
    fun `uploads that are not highlight cuts are dropped`() {
        val hits = listOf(
            hit("analysis", "Celtics vs Lakers breakdown", "NBA"),
            hit("short", "Celtics vs Lakers Highlights", "NBA", duration = 0),
            hit("marathon", "Celtics vs Lakers Highlights", "NBA", duration = 7_200),
        )

        assertTrue(SportsYouTubeHighlights.official(event(), league(), hits, nowMs = now).isEmpty())
    }

    @Test
    fun `uploads published before the game are a different game and drop out`() {
        val hits = listOf(
            // A previous meeting of the same teams, years old.
            hit("old", "Celtics vs Lakers Highlights", "NBA", published = "5 years ago"),
            // Published the night of this game.
            hit("fresh", "Celtics vs Lakers Highlights", "NBA", published = "1 day ago"),
        )

        val kept = SportsYouTubeHighlights.official(
            event(startMs = twoDaysAgo),
            league(),
            hits,
            nowMs = now,
        )

        assertEquals(listOf("fresh"), kept.map { it.videoId })
    }

    @Test
    fun `an upload with no stated age cannot be checked against the game day and drops out`() {
        val hits = listOf(
            hit("unknown", "Celtics vs Lakers Highlights", "NBA", published = ""),
            hit("known", "Celtics vs Lakers Highlights", "NBA", published = "3 hours ago"),
        )

        val kept = SportsYouTubeHighlights.official(
            event(startMs = twoDaysAgo),
            league(),
            hits,
            nowMs = now,
        )

        assertEquals(listOf("known"), kept.map { it.videoId })
    }

    @Test
    fun `titles that do not name both teams drop out`() {
        val hits = listOf(
            // Some other matchup wearing the same search words.
            hit("other", "Celtics vs Warriors Highlights", "NBA"),
            hit("half", "Lakers Highlights", "NBA"),
            hit("match", "Celtics vs Lakers Highlights", "NBA"),
        )

        val kept = SportsYouTubeHighlights.official(event(), league(), hits, nowMs = now)

        assertEquals(listOf("match"), kept.map { it.videoId })
    }

    @Test
    fun `the full team name in an upload title counts as naming it`() {
        val hits = listOf(
            hit("long", "Boston Celtics vs Los Angeles Lakers Highlights", "NBA"),
        )

        assertEquals(1, SportsYouTubeHighlights.official(event(), league(), hits, nowMs = now).size)
    }

    @Test
    fun `the row cap holds whatever the search returned`() {
        val leagueHits = (1..6).map { hit("l$it", "Celtics vs Lakers Highlights $it", "NBA") }
        assertEquals(
            SportsYouTubeHighlights.MAX_HIGHLIGHTS,
            SportsYouTubeHighlights.official(event(), league(), leagueHits, nowMs = now).size,
        )
    }

    @Test
    fun `the stream handed to the player carries only the video id to resolve`() {
        val item = SportsYouTubeHighlights.streamItem(league(), event(), hit("abc123", "Celtics vs Lakers Highlights", "NBA"))

        assertEquals("abc123", item.ytId)
        assertNull(item.url)
        assertEquals("YouTube", item.addonName)
        assertTrue(item.description.orEmpty().contains("Official highlights"))
        assertTrue(item.description.orEmpty().contains("NBA"))
    }

    @Test
    fun `durations read like a player clock`() {
        assertEquals("10:00", SportsYouTubeHighlights.durationLabel(600))
        assertEquals("10:05", SportsYouTubeHighlights.durationLabel(605))
        assertEquals("60:00", SportsYouTubeHighlights.durationLabel(3_600))
        assertEquals("", SportsYouTubeHighlights.durationLabel(0))
    }
}
