package com.nuvio.app.features.youtube

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isFocused
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
 * Left at the floating bar's left end must enter the YouTube page. While the bar hosts the search
 * box the tab draws no search control of its own, so the hand-off has to fall through to the first
 * section tab instead of pointing at a requester that is not attached (a dead end).
 */
@OptIn(ExperimentalTestApi::class)
class YouTubeBarSideLeftTest {

    @AfterTest
    fun cleanUp() {
        YouTubeTopBarSearch.barHosted = false
        RootNavFocus.topBarAvailable = false
        RootNavFocus.settingsTopBarAvailable = false
        RootNavFocus.sidebarAvailable = false
        // The composition is gone, so the window's focus observer would report a drop: clear the
        // flag rather than leave it latched for whichever test runs next.
        KeyRouteLog.composeFocus(false)
    }

    @Test
    fun leftFromTheBarEntersTheFirstSectionTabWhenTheBarHostsTheSearch() = runComposeUiTest {
        YouTubeTopBarSearch.barHosted = true
        setContent {
            NuvioTheme {
                YouTubeTabScreen(onPlay = {}, onOpenSettings = {}, modifier = Modifier, topChromePadding = null)
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        runOnIdle { KeyRouteLog.composeFocus(true) }
        RootNavFocus.topBarAvailable = true
        var took = false
        runOnIdle { took = RootNavFocus.focusBarSide(left = true) }
        waitForIdle()
        assertTrue(took, "the page must answer the bar's left end")
        // The header row is the page's top strip: the keys must land in it, not be refused.
        val focused = onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue(
            focused.top.value < 140f,
            "Left from the bar must land in the header row, not at ${focused.top}",
        )
    }
}
