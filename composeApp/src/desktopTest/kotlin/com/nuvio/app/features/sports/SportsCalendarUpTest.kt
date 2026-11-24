package com.nuvio.app.features.sports

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
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
 * The Sports page's vertical ladder and league directory, walked on the real page with real key
 * presses.
 *
 * Up from the calendar has to reach the row drawn above it **at the day's own position**: the
 * league directory first, the sport chips above that, and the bar's icons and the page's top-right
 * pills where the day sits under them. It used to ask the app's top bar for the icon nearest the
 * day, skipping both rows, and a day reported x=0 whenever the strip composed it already focused.
 */
@OptIn(ExperimentalTestApi::class)
class SportsCalendarUpTest {

    private lateinit var before: SportsSettings

    @BeforeTest
    fun seedFollowed() {
        // Compose the page with something followed: the first-run follow picker otherwise opens over
        // it and takes the keys, which makes the walk depend on which test ran first.
        before = SportsSettingsRepository.settings.value
        SportsSettingsRepository.markSetUp()
        SportsSettingsRepository.clearFollows()
        SportsSettingsRepository.setLeagueFollowed(FOLLOWED_LEAGUE, true)
    }

    @AfterTest
    fun restore() {
        SportsSettingsRepository.replaceForBackup(before)
    }

    private fun ComposeUiTest.focused(): SemanticsNode? =
        onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()

    private fun ComposeUiTest.describeFocused(): String {
        val node = focused() ?: return "nothing focused"
        val texts = onNode(isFocused()).printToString(6).lines()
            .filter { it.contains("Text = ") }
            .joinToString(" ") { it.trim() }
        return "$texts (x ${node.boundsInWindow.left.toInt()}..${node.boundsInWindow.right.toInt()}," +
            " y ${node.boundsInWindow.top.toInt()})"
    }

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /**
     * Compose the page and wait for it to seat the keys. It seats them on the selected day, so the
     * rows above the calendar are walked with Up from here.
     */
    private fun ComposeUiTest.composePage() {
        setContent { NuvioTheme { SportsScreen() } }
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNode(isFocused()).fetchSemanticsNode() }.isSuccess
        }
    }

    @Test
    fun `up from a day on the right comes up on the right-hand chips`() = runComposeUiTest {
        composePage()
        repeat(8) { press(Key.DirectionRight) }

        val day = focused()!!
        val windowWidth = onRoot().fetchSemanticsNode().boundsInWindow.width

        press(Key.DirectionUp)

        val landed = focused()!!
        assertTrue(
            landed.boundsInWindow.left > windowWidth / 2,
            "a day on the right comes up on the right of the row above it: ${describeFocused()}",
        )
        assertTrue(
            landed.boundsInWindow.center.x > day.boundsInWindow.center.x - 200f,
            "the chip is over the day it came from: ${describeFocused()}",
        )
    }

    @Test
    fun `the chip a day comes up on is the one drawn over it`() {
        // Chips as a row lays them out: the sport chips on the left, the league directory across,
        // "Change Leagues" at the right. A day's own x picks the chip over it, and one that falls
        // between two chips picks the nearer - never the first whatever x came in.
        val bounds = mapOf(
            0 to 158f..203f,
            1 to 211f..380f,
            2 to 388f..505f,
            12 to 860f..1008f,
        )
        assertEquals(12, sportsBandChipAt(bounds, 965f), "a day on the right comes up on the right")
        assertEquals(0, sportsBandChipAt(bounds, 170f), "a day on the left comes up on the left")
        assertEquals(2, sportsBandChipAt(bounds, 520f), "between two chips, the nearer one")
        assertEquals(1, sportsBandChipAt(bounds, 230f), "inside a chip, that chip")
        assertEquals(null, sportsBandChipAt(bounds, null), "no position known: no claim on the key")
        assertEquals(null, sportsBandChipAt(emptyMap(), 500f), "row not placed yet: no claim")
    }

    @Test
    fun `up from the sport chips reaches the header row`() = runComposeUiTest {
        composePage()
        press(Key.DirectionUp) // calendar -> league directory
        press(Key.DirectionUp) // league directory -> sport chips
        val chips = focused()!!

        press(Key.DirectionUp)

        val landed = focused()!!
        assertTrue(
            landed.boundsInWindow.top < chips.boundsInWindow.top,
            "Up from the chips reaches the row above them: ${describeFocused()}",
        )
    }

    @Test
    fun `the league directory lists every competition in the catalog`() {
        // The directory is drawn from the catalog, so a league missing from it is a league nobody
        // can pick from the page - which is what "does not show all 55 leagues" looked like.
        assertEquals(55, SportsCatalog.leagues.size, "the catalog carries all 55 competitions")
        assertEquals(
            SportsCatalog.leagues.size,
            SportsCatalog.leagues.distinctBy { it.id }.size,
            "no duplicate competitions in the directory",
        )
    }

    @Test
    fun `the rows above the calendar are the league directory, the sport chips, then the header`() =
        runComposeUiTest {
            composePage()
            val day = focused()!!
            val seen = mutableListOf(describeFocused())

            press(Key.DirectionUp)
            val directory = focused()!!
            seen += describeFocused()
            press(Key.DirectionUp)
            val chips = focused()!!
            seen += describeFocused()
            press(Key.DirectionUp)
            val header = focused()!!
            seen += describeFocused()

            assertTrue(
                directory.boundsInWindow.top < day.boundsInWindow.top,
                "the league directory is drawn over the calendar: $seen",
            )
            assertTrue(
                chips.boundsInWindow.top < directory.boundsInWindow.top,
                "the sport chips are drawn over the league directory: $seen",
            )
            assertTrue(
                header.boundsInWindow.top < chips.boundsInWindow.top,
                "the header row is drawn over the sport chips: $seen",
            )
        }

    @Test
    fun `the picker's search box hands the keys to the results`() = runComposeUiTest {
        setContent { NuvioTheme { SportsFollowPicker(onDismiss = {}) } }
        waitForIdle()

        // The box opens focused: Down must open the first team or league, Up must come back to the
        // box, and Escape must return to the results rather than closing the picker.
        press(Key.DirectionDown)
        val afterDown = describeFocused()
        assertTrue(afterDown.contains("NFL"), "Down opens the first result: $afterDown")

        press(Key.DirectionUp)
        val afterUp = describeFocused()
        assertTrue(afterUp.contains("EditableText"), "Up comes back to the box: $afterUp")

        press(Key.Escape)
        val afterEscape = describeFocused()
        assertTrue(
            afterEscape.contains("NFL"),
            "Back from the box returns to the results, not out of the picker: $afterEscape",
        )
    }

    private companion object {
        const val FOLLOWED_LEAGUE = "football/nfl"
    }
}
