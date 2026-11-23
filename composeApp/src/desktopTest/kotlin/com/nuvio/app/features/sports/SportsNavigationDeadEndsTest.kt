package com.nuvio.app.features.sports

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.KeyRouteLog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.RootNavFocus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Sports page's bar-side dead ends, offscreen (no window or display): Right at the floating
 * bar's right end must reach the header band's Follow pill, and Left the Tables chip — not the
 * band's first chip, which its focusRestorer restored to after the named control had taken the keys.
 *
 * Both need the header band drawn, which needs the page set up, so a league is followed first.
 */
@OptIn(ExperimentalTestApi::class)
class SportsNavigationDeadEndsTest {

    private var before: SportsSettings? = null

    @AfterTest
    fun cleanUp() {
        before?.let { SportsSettingsRepository.replaceForBackup(it) }
        RootNavFocus.topBarAvailable = false
        RootNavFocus.settingsTopBarAvailable = false
        RootNavFocus.sidebarAvailable = false
        // The composition is gone, so the window's focus observer would report a drop: clear the
        // flag rather than leave it latched for whichever test runs next.
        KeyRouteLog.composeFocus(false)
    }

    private fun seedFollowedLeague() {
        before = SportsSettingsRepository.settings.value
        SportsSettingsRepository.markSetUp()
        SportsSettingsRepository.clearFollows()
        SportsSettingsRepository.setLeagueFollowed(FOLLOWED_LEAGUE, true)
    }

    /** Composes the real page and waits for the day's games to arrive from the cache. */
    private fun ComposeUiTest.showSportsPage() {
        setContent {
            NuvioTheme {
                SportsScreen(modifier = Modifier, topChromePadding = null)
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        Thread.sleep(3_000)
        waitForIdle()
        // The window's root observer marks "focus is held"; without it the page's key layer
        // reseats focus into itself between frames, which is a harness artifact, not app behaviour.
        runOnIdle { KeyRouteLog.composeFocus(true) }
    }

    @Test
    fun barRightEndReachesTheFollowTeamsPill() = runComposeUiTest {
        seedFollowedLeague()
        showSportsPage()
        RootNavFocus.topBarAvailable = true
        var took = false
        runOnIdle { took = RootNavFocus.focusBarSide(left = false) }
        waitForIdle()
        assertTrue(took, "the page must answer the bar's right end")
        onNodeWithText(FOLLOW_PILL).assertIsFocused()
    }

    @Test
    fun barLeftEndReachesTheTablesChip() = runComposeUiTest {
        seedFollowedLeague()
        showSportsPage()
        RootNavFocus.topBarAvailable = true
        var took = false
        runOnIdle { took = RootNavFocus.focusBarSide(left = true) }
        waitForIdle()
        assertTrue(took, "the page must answer the bar's left end")
        onNodeWithText("Tables").assertIsFocused()
    }

    /**
     * Up from the calendar reaches the header row drawn over the day — the chips on the left, the
     * corner player's buttons and the picker on the right — and only asks the floating bar where the
     * row has nothing (the middle, where the bar floats). Which control it lands on depends on where
     * the day is drawn, so this checks the row it lands in, not a particular button.
     */
    @Test
    fun upFromTheCalendarReachesTheHeaderRowOverIt() = runComposeUiTest {
        val barAsked = mutableStateListOf<Float>()
        seedFollowedLeague()
        setContent {
            LaunchedEffect(Unit) { RootNavFocus.positionTopBarEnterRequests.collect { barAsked += it } }
            NuvioTheme { SportsScreen(modifier = Modifier, topChromePadding = null) }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        Thread.sleep(3_000)
        waitForIdle()
        runOnIdle { KeyRouteLog.composeFocus(true) }
        RootNavFocus.topBarAvailable = true

        // The page seats its own focus on the day strip; Up from there asks the header row.
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNode(isFocused()).fetchSemanticsNode() }.isSuccess
        }
        val dayY = onNode(isFocused()).getUnclippedBoundsInRoot().top
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        mainClock.advanceTimeBy(400)
        waitForIdle()

        val landed = onNode(isFocused()).getUnclippedBoundsInRoot().top
        assertTrue(landed < dayY, "Up must leave the calendar for the row above it ($dayY -> $landed)")
        assertTrue(landed.value < 80f, "the row above the calendar is the header row, not $landed")
        assertTrue(
            barAsked.isEmpty(),
            "a day drawn under a header button reaches that button, not the bar ($barAsked)",
        )
    }

    private companion object {
        /** Premier League: the local day cache always has games for it. */
        const val FOLLOWED_LEAGUE = "soccer/eng.1"
        const val FOLLOW_PILL = "✎ Follow Teams & Leagues"
    }
}
