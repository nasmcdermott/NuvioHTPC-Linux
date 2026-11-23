package com.nuvio.app.features.sports

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
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

/**
 * The two header controls the floating bar steps into hand the keys back into the bar: Right from
 * the Tables chip enters at the bar's left end (Home, slot 0), Left from the Follow pill at its
 * right end (the Settings gear, the bar's last slot).
 *
 * The bar itself is not composed here, so what is checked is the request the page makes: which slot
 * the bar is asked for. The bar coerces a slot into its own range, so `Int.MAX_VALUE` means "its
 * last", the gear. The controls are reached the way the app reaches them — through the bar's own
 * hand-off — because the band's focusRestorer redirects a direct focus request to its fallback chip.
 */
@OptIn(ExperimentalTestApi::class)
class SportsHeaderBarReturnsTest {

    private var before: SportsSettings? = null

    @AfterTest
    fun cleanUp() {
        before?.let { SportsSettingsRepository.replaceForBackup(it) }
        RootNavFocus.topBarAvailable = false
        RootNavFocus.settingsTopBarAvailable = false
        RootNavFocus.sidebarAvailable = false
        KeyRouteLog.composeFocus(false)
    }

    private fun seedFollowedLeague() {
        before = SportsSettingsRepository.settings.value
        SportsSettingsRepository.markSetUp()
        SportsSettingsRepository.clearFollows()
        SportsSettingsRepository.setLeagueFollowed("soccer/eng.1", true)
    }

    @Test
    fun rightFromTheTablesChipAsksForTheBarsLeftEnd() = runComposeUiTest {
        val asked = mutableStateListOf<Int>()
        seedFollowedLeague()
        setContent {
            LaunchedEffect(Unit) { RootNavFocus.topBarEnterAtRequests.collect { asked += it } }
            NuvioTheme { SportsScreen(modifier = Modifier, topChromePadding = null) }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        Thread.sleep(3_000)
        waitForIdle()
        runOnIdle { KeyRouteLog.composeFocus(true) }
        RootNavFocus.topBarAvailable = true

        // The bar's left end steps in to the Tables chip; from there Right must step back in.
        var took = false
        runOnIdle { took = RootNavFocus.focusBarSide(left = true) }
        waitForIdle()
        assert(took) { "the page must answer the bar's left end, or the test is not on the chip" }
        onNodeWithText("Tables").assertIsFocused()

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        mainClock.advanceTimeBy(400)
        waitForIdle()

        assertEquals(listOf(0), asked.toList(), "Right from Tables must ask for the bar's left end")
    }

    @Test
    fun leftFromTheFollowPillAsksForTheBarsRightEnd() = runComposeUiTest {
        val asked = mutableStateListOf<Int>()
        seedFollowedLeague()
        setContent {
            LaunchedEffect(Unit) { RootNavFocus.topBarEnterAtRequests.collect { asked += it } }
            NuvioTheme { SportsScreen(modifier = Modifier, topChromePadding = null) }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        Thread.sleep(3_000)
        waitForIdle()
        runOnIdle { KeyRouteLog.composeFocus(true) }
        RootNavFocus.topBarAvailable = true

        var took = false
        runOnIdle { took = RootNavFocus.focusBarSide(left = false) }
        waitForIdle()
        assert(took) { "the page must answer the bar's right end, or the test is not on the pill" }
        onNodeWithText(FOLLOW_PILL).assertIsFocused()

        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        mainClock.advanceTimeBy(400)
        waitForIdle()

        assertEquals(
            listOf(Int.MAX_VALUE),
            asked.toList(),
            "Left from the Follow pill must ask for the bar's right end (the gear)",
        )
    }

    private companion object {
        const val FOLLOW_PILL = "✎ Follow Teams & Leagues"
    }
}
