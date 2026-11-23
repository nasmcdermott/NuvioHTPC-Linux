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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Sports page's vertical ladder, walked on the real page with real key presses.
 *
 * Up from the calendar has to reach the sport band drawn above it **at the day's own position**: a
 * day on the right comes up on the right-hand chips and a day on the left on the left-hand ones. It
 * used to ask the app's top bar for the icon nearest the day, skipping the sport categories, and the
 * day's reported position was 0 whenever the strip composed that day already focused - so every day
 * came up at the far left instead.
 */
@OptIn(ExperimentalTestApi::class)
class SportsCalendarUpTest {

    private fun ComposeUiTest.focused(): SemanticsNode? =
        onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()

    private fun ComposeUiTest.describeFocused(): String {
        val node = focused() ?: return "nothing focused"
        val texts = onNode(isFocused()).printToString(6).lines()
            .filter { it.contains("Text = ") }
            .joinToString(" ") { it.trim() }
        return "$texts (x ${node.boundsInWindow.left.toInt()}..${node.boundsInWindow.right.toInt()})"
    }

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun composePageAndClosePicker(test: ComposeUiTest) = with(test) {
        setContent { NuvioTheme { SportsScreen() } }
        waitForIdle()
        // The follow picker opens on a profile that has never been set up: Escape leaves its search
        // box for the results, the next closes the dialog.
        repeat(3) { press(Key.Escape) }
        assertTrue(focused() != null, "the page took the keys after the picker closed")
    }

    @Test
    fun `up from a day on the right comes up on the right-hand chips`() = runComposeUiTest {
        composePageAndClosePicker(this)
        press(Key.DirectionDown) // header -> sport band
        press(Key.DirectionDown) // sport band -> calendar
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
        // Chips as the band lays them out: "All" at the left, the sports across, "Change Leagues"
        // at the right. A day's own x picks the chip over it, and one that falls between two chips
        // picks the nearer - never the first whatever x came in, which is what the page did before.
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
        assertEquals(null, sportsBandChipAt(emptyMap(), 500f), "band not placed yet: no claim")
    }

    @Test
    fun `up from the sport chips reaches the header row`() = runComposeUiTest {
        composePageAndClosePicker(this)
        press(Key.DirectionDown) // header -> sport band
        val band = focused()!!

        press(Key.DirectionUp)

        val landed = focused()!!
        assertTrue(
            landed.boundsInWindow.top < band.boundsInWindow.top,
            "Up from the chips reaches the row above them: ${describeFocused()}",
        )
    }

    @Test
    fun `the picker's search box hands the keys to the results`() = runComposeUiTest {
        setContent { NuvioTheme { SportsScreen() } }
        waitForIdle()

        // The box opens focused: Down must open the first team or league, Up must come back to the
        // box, and Escape must return to the results rather than closing the picker.
        press(Key.DirectionDown)
        val afterDown = describeFocused()
        assertTrue(afterDown.contains("Follow league"), "Down opens the first result: $afterDown")

        press(Key.DirectionUp)
        val afterUp = describeFocused()
        assertTrue(afterUp.contains("EditableText"), "Up comes back to the box: $afterUp")

        press(Key.Escape)
        val afterEscape = describeFocused()
        assertTrue(
            afterEscape.contains("Follow league"),
            "Back from the box returns to the results, not out of the picker: $afterEscape",
        )
    }
}
