package com.nuvio.app.features.sports

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.RootNavFocus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bar's right end and the Sports header share one line: Right must land on the leftmost control of
 * the header's right-hand group, not on Refresh or a card below — and when the corner player is up,
 * that leftmost control is its "Expand player" button, so the expand and close buttons are reachable at
 * all. Offscreen Compose test, no window or display.
 */
@OptIn(ExperimentalTestApi::class)
class SportsHeaderBarHandoffTest {

    @AfterTest
    fun cleanUp() {
        RootNavFocus.sidebarAvailable = false
        RootNavFocus.topBarAvailable = false
    }

    @Test
    fun rightFromTheBarFocusesTheFollowTeamsButton() = runComposeUiTest {
        band(showExpandPlayer = false)
        RootNavFocus.topBarAvailable = true

        runOnIdle {
            assertTrue(RootNavFocus.focusBarSide(left = false), "the header must register a bar-side handoff")
        }
        waitForIdle()
        onNodeWithText("✎ Follow Teams & Leagues").assertIsFocused()
    }

    @Test
    fun rightFromTheBarReachesTheCornerPlayersButtons() = runComposeUiTest {
        band(showExpandPlayer = true)
        RootNavFocus.topBarAvailable = true

        runOnIdle {
            assertTrue(RootNavFocus.focusBarSide(left = false), "the header must register a bar-side handoff")
        }
        waitForIdle()
        // The group's left edge: the expand button, not the Follow pill two places to its right.
        // Both corner-player pills are icon-only, so their name is the icon's description.
        onAllNodesWithContentDescription("Expand player").onFirst().assertIsFocused()

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        // Right from here walks to the close button beside it, as it does between the chips:
        // the hand-off's job is to enter the group at its left edge, which it now does.
        onAllNodesWithContentDescription("Close mini player").onFirst().assertExists()
    }

    @Test
    fun leftFromTheCornerPlayersExpandButtonReturnsToTheBar() = runComposeUiTest {
        val asked = mutableStateListOf<Int>()
        band(showExpandPlayer = true, asked = asked)
        RootNavFocus.topBarAvailable = true

        runOnIdle {
            assertTrue(RootNavFocus.focusBarSide(left = false), "the header must register a bar-side handoff")
        }
        waitForIdle()
        onAllNodesWithContentDescription("Expand player").onFirst().assertIsFocused()

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(
            listOf(Int.MAX_VALUE),
            asked.toList(),
            "Left at the group's left edge must ask for the bar's right end (the gear)",
        )
    }

    /** The band as the page draws it, with a fresh ide-free requester set. */
    private fun androidx.compose.ui.test.ComposeUiTest.band(
        showExpandPlayer: Boolean,
        asked: MutableList<Int> = mutableListOf(),
    ) {
        setContent {
            LaunchedEffect(Unit) { RootNavFocus.topBarEnterAtRequests.collect { asked += it } }
            NuvioTheme {
                SportsHeaderBand(
                    tablesMode = false,
                    mySportsOnly = false,
                    followedCount = 0,
                    liveCount = 0,
                    showExpandPlayer = showExpandPlayer,
                    onExpandPlayer = {},
                    onClosePlayer = {},
                    headerBandFocus = FocusRequester(),
                    modeGamesFocus = FocusRequester(),
                    onSelectTablesMode = {},
                    onToggleMySports = {},
                    onOpenPicker = {},
                    onRefresh = {},
                    onDown = { false },
                )
            }
        }
        waitForIdle()
    }
}
