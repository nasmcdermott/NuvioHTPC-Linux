package com.nuvio.app.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Leaving the floating bar with Back always ends up in the page on screen.
 *
 * [RootNavFocus.restoreContentFocus] puts the keys back on the control the page had when the bar took
 * them, but that control can be gone (the page reloaded, the row it was on went away) and the bar can
 * be reached by pointer with nothing remembered at all. The spatial fallback then found nothing below
 * the bar — on Home the keys were lost — so the page registers its own entry, and that is what the
 * leave asks for instead.
 */
@OptIn(ExperimentalTestApi::class)
class BarLeavePageEntryTest {

    @AfterTest
    fun cleanUp() {
        RootNavFocus.topBarAvailable = false
        RootNavFocus.forgetContentFocus()
    }

    @Test
    fun backFromTheBarLandsOnThePagesEntryWhenNothingWasRemembered() = runComposeUiTest {
        var pageFocused = false
        var barFocused = false
        var decoyFocused = false
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        val decoyRequester = FocusRequester()
        RootNavFocus.topBarAvailable = true
        setContent {
            TopBarLeaveRestoresContent(
                topBarHasFocus = { barFocused },
                pageOwner = { PAGE },
            )
            Column {
                Box2(
                    tag = "bar",
                    requester = barRequester,
                    onFocus = { barFocused = it },
                )
                // Directly under the bar, so the spatial search finds it: the page's own entry is
                // further away, as Home's row is from the bar across the top. The entry must still
                // win, or leaving the bar lands wherever the geometry happens to point.
                Box2(
                    tag = "decoy",
                    requester = decoyRequester,
                    onFocus = { decoyFocused = it },
                )
                Spacer(Modifier.size(400.dp, 200.dp))
                Box2(
                    tag = "page",
                    requester = pageRequester,
                    onFocus = { pageFocused = it },
                )
            }
            BarPageEntry { pageRequester.requestFocusLogged() }
        }
        waitForIdle()

        runOnIdle { barRequester.requestFocus() }
        waitForIdle()
        assertTrue(barFocused, "the test needs the bar to hold the keys first")

        runOnIdle { RootNavFocus.leaveTopBar() }
        waitForIdle()
        assertTrue(pageFocused, "Back from the bar must land on the page's own entry")
        assertFalse(decoyFocused, "the page's entry decides where the keys land, not the geometry")
        assertFalse(barFocused)
    }

    @Test
    fun backFromTheBarStillPrefersTheControlThePageHad() = runComposeUiTest {
        var rememberedFocused = false
        var pageEntryFocused = false
        var barFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val rememberedRequester = FocusRequester()
        val pageEntryRequester = FocusRequester()
        RootNavFocus.topBarAvailable = true
        setContent {
            focusManager = LocalFocusManager.current
            TopBarLeaveRestoresContent(
                topBarHasFocus = { barFocused },
                pageOwner = { PAGE },
            )
            Column {
                Box2("bar", barRequester) { barFocused = it }
                Box2("remembered", rememberedRequester) { rememberedFocused = it }
                Box2("entry", pageEntryRequester) { pageEntryFocused = it }
            }
            BarPageEntry { pageEntryRequester.requestFocusLogged() }
        }
        waitForIdle()

        runOnIdle { rememberedRequester.requestFocus() }
        waitForIdle()
        assertTrue(rememberedFocused)
        // As the bar takes the keys it remembers the page's control.
        runOnIdle { RootNavFocus.rememberContentFocus(PAGE, focusManager!!) }
        runOnIdle { barRequester.requestFocus() }
        waitForIdle()
        assertTrue(barFocused)

        runOnIdle { RootNavFocus.leaveTopBar() }
        waitForIdle()
        assertTrue(rememberedFocused, "the control the page had comes before the page's entry")
        assertFalse(pageEntryFocused)
    }

    private companion object {
        const val PAGE = "home"
    }
}

@androidx.compose.runtime.Composable
private fun Box2(tag: String, requester: FocusRequester, onFocus: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(120.dp, 40.dp)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { onFocus(it.hasFocus) }
            .focusable(),
    )
}
