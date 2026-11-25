package com.nuvio.app.features.youtube

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.KeyRouteLog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.RootNavFocus
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Right at the floating bar's right end must enter the YouTube page's right-hand header group:
 * the corner player's "Watch page" while it is up, otherwise the "Hide watched" filter. The bar
 * owns the keys, so a hand-off that does not land in that row leaves Right on the Settings gear.
 */
@OptIn(ExperimentalTestApi::class)
class YouTubeBarSideRightTest {

    private var previousAuth: YouTubeMusicAuthState? = null
    private var forceSignedIn = false

    /**
     * Puts the shared auth state in the signed-in shape without persisting a cookie: the Hide
     * watched pill is drawn only for a signed-in account, and the right-hand group's first
     * control is what the bar must reach. Restored in [cleanUp].
     */
    private fun signedInForTest() {
        val authClass = YouTubeMusicAuth::class.java
        val stateField = authClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(YouTubeMusicAuth) as MutableStateFlow<YouTubeMusicAuthState>
        previousAuth = state.value
        val loadedField = authClass.getDeclaredField("loaded").apply { isAccessible = true }
        loadedField.setBoolean(YouTubeMusicAuth, true)
        forceSignedIn = true
        state.value = YouTubeMusicAuthState(signedIn = true)
    }

    private fun restoreAuth() {
        if (!forceSignedIn) return
        val authClass = YouTubeMusicAuth::class.java
        val stateField = authClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(YouTubeMusicAuth) as MutableStateFlow<YouTubeMusicAuthState>
        state.value = previousAuth ?: YouTubeMusicAuthState()
        previousAuth = null
        forceSignedIn = false
    }

    @AfterTest
    fun cleanUp() {
        YouTubeTopBarSearch.barHosted = false
        YouTubeWatchLayout.stopBrowsing()
        YouTubeWatchLayout.end()
        restoreAuth()
        RootNavFocus.topBarAvailable = false
        RootNavFocus.settingsTopBarAvailable = false
        RootNavFocus.sidebarAvailable = false
        // The composition is gone, so the window's focus observer would report a drop: clear the
        // flag rather than leave it latched for whichever test runs next.
        KeyRouteLog.composeFocus(false)
    }

    @Test
    fun rightFromTheBarEntersHideWatchedWhenTheCornerPlayerIsDown() = runComposeUiTest {
        signedInForTest()
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
        runOnIdle { took = RootNavFocus.focusBarSide(left = false) }
        waitForIdle()
        assertTrue(took, "the page must answer the bar's right end")
        onNodeWithContentDescription("Hide watched").assertIsFocused()
    }

    @Test
    fun rightFollowsTheCornerPlayerComingAndGoing() = runComposeUiTest {
        signedInForTest()
        setContent {
            NuvioTheme {
                YouTubeTabScreen(onPlay = {}, onOpenSettings = {}, modifier = Modifier, topChromePadding = null)
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        runOnIdle { KeyRouteLog.composeFocus(true) }
        RootNavFocus.topBarAvailable = true

        // The corner player starts after the tab: the handoff must read the new edge, not the
        // control that was first when the tab registered.
        runOnIdle {
            YouTubeWatchLayout.begin()
            YouTubeWatchLayout.startBrowsing()
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        var took = false
        runOnIdle { took = RootNavFocus.focusBarSide(left = false) }
        waitForIdle()
        assertTrue(took, "the page must answer the bar's right end with the corner player up")
        onNodeWithContentDescription("Watch page").assertIsFocused()

        // And back down again: the same handoff now points at Hide watched.
        runOnIdle { YouTubeWatchLayout.stopBrowsing() }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        runOnIdle { took = RootNavFocus.focusBarSide(left = false) }
        waitForIdle()
        assertTrue(took, "the page must answer again with the corner player down")
        onNodeWithContentDescription("Hide watched").assertIsFocused()
    }

    @Test
    fun rightFromTheBarEntersTheWatchPagePillWhileBrowsing() = runComposeUiTest {
        YouTubeWatchLayout.begin()
        YouTubeWatchLayout.startBrowsing()
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
        runOnIdle { took = RootNavFocus.focusBarSide(left = false) }
        waitForIdle()
        assertTrue(took, "the page must answer the bar's right end")
        onNodeWithContentDescription("Watch page").assertIsFocused()
    }
}