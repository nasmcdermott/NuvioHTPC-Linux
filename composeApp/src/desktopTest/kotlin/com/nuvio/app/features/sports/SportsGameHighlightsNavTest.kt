package com.nuvio.app.features.sports

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * fork:game-highlights — the highlight rows inside the replay group's empty card take part in the
 * page's ladder: Down from the "Replay unavailable" header lands on the first highlight row, the
 * rows walk between themselves, and Up from the first row comes back to the header. A page without
 * the rows (the mutation) must not claim these keys.
 */
@OptIn(ExperimentalTestApi::class)
class SportsGameHighlightsNavTest {

    private val event = SportsEvent(
        id = "event-1",
        leagueId = "nba",
        startMs = 1L,
        state = GameState.POST,
        away = GameSide(Team("away", "Boston Celtics", "Celtics", "BOS")),
        home = GameSide(Team("home", "Los Angeles Lakers", "Lakers", "LAL")),
    )

    @BeforeTest
    fun seedHighlights() {
        SportsYouTubeHighlights.seedForTest(
            "event-1",
            listOf(
                com.nuvio.app.features.trailer.YouTubeSearchHit(
                    videoId = "one",
                    title = "Highlights One",
                    channel = "Boston Celtics",
                    verified = true,
                    durationSeconds = 600,
                    views = 1,
                ),
                com.nuvio.app.features.trailer.YouTubeSearchHit(
                    videoId = "two",
                    title = "Highlights Two",
                    channel = "Boston Celtics",
                    verified = true,
                    durationSeconds = 600,
                    views = 1,
                ),
                com.nuvio.app.features.trailer.YouTubeSearchHit(
                    videoId = "three",
                    title = "Highlights Three",
                    channel = "Boston Celtics",
                    verified = true,
                    durationSeconds = 600,
                    views = 1,
                ),
            ),
        )
    }

    @AfterTest
    fun clear() {
        SportsYouTubeHighlights.clearForTest()
    }

    private fun ComposeUiTest.focused(): SemanticsNode? =
        onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()

    private fun ComposeUiTest.describeFocused(): String {
        val node = focused() ?: return "nothing focused"
        val texts = onNode(isFocused()).printToString(6).lines()
            .filter { it.contains("Text = ") }
            .map { it.trim().take(60) }
            .take(2)
            .joinToString(" | ")
        return "$texts (y ${node.boundsInWindow.top.toInt()})"
    }

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /** Presses [key] until the focused node's text contains [text], bounded. */
    private fun ComposeUiTest.pressUntil(text: String, maxPresses: Int = 6): Boolean {
        repeat(maxPresses) {
            if (onNode(isFocused()).printToString(6).contains(text)) return true
            press(key = Key.DirectionDown)
        }
        return onNode(isFocused()).printToString(6).contains(text)
    }

    private fun ComposeUiTest.composePage() {
        setContent {
            NuvioTheme {
                SportsGameDetail(
                    event = event,
                    league = null,
                    hideScores = false,
                    onBack = {},
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("Official YouTube highlights").fetchSemanticsNode() }.isSuccess
        }
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNode(isFocused()).fetchSemanticsNode() }.isSuccess
        }
    }

    @Test
    fun `down walks from the page into the highlight rows and between them`() = runComposeUiTest {
        composePage()
        // The past game's entry focus starts on the back button; walk down into the replay group.
        assertTrue(
            pressUntil("Highlights One"),
            "Down from the page top reaches the first highlight row: ${describeFocused()}",
        )
        val firstRowTop = focused()!!.boundsInWindow.top

        press(Key.DirectionDown)
        assertTrue(
            onNode(isFocused()).printToString(6).contains("Highlights Two"),
            "Down moves to the second highlight row: ${describeFocused()}",
        )

        press(Key.DirectionDown)
        assertTrue(
            onNode(isFocused()).printToString(6).contains("Highlights Three"),
            "Down moves to the last highlight row: ${describeFocused()}",
        )

        // Up walks back the same way it came.
        press(Key.DirectionUp)
        assertTrue(
            onNode(isFocused()).printToString(6).contains("Highlights Two"),
            "Up returns to the second row: ${describeFocused()}",
        )
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        // A finished game's replay header holds no focusable child of its own, so Up from the
        // first row leaves the group for the band above it (as the empty card did before).
        val leftUp = focused()!!
        assertTrue(
            leftUp.boundsInWindow.top < firstRowTop &&
                !onNode(isFocused()).printToString(6).contains("Highlights"),
            "Up from the first row leaves the highlights group upward: ${describeFocused()}",
        )
    }
}
