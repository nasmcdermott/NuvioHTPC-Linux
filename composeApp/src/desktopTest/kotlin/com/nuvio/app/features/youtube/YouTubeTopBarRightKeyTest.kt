package com.nuvio.app.features.youtube

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.AppScreenTab
import com.nuvio.app.TabletFloatingTopBar
import com.nuvio.app.core.ui.KeyRouteLog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.RootNavFocus
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The real floating bar and the real YouTube tab in one tree: Right at the bar's right end (the
 * Settings gear) must cross into the page's right-hand header group, not stop on the bar or be
 * pulled back to the header's home tab by its focus restorer.
 *
 * [YouTubeBarSideRightTest] covers the hand-off closure by itself; this covers the key path and the
 * header's focus group together, which is where the regression was.
 */
@OptIn(ExperimentalTestApi::class)
class YouTubeTopBarRightKeyTest {

    private var previousAuth: YouTubeMusicAuthState? = null
    private var forceSignedIn = false

    /** Signed in without persisting a cookie, so the Hide watched pill is drawn. */
    private fun signedInForTest() {
        val authClass = YouTubeMusicAuth::class.java
        val stateField = authClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(YouTubeMusicAuth) as MutableStateFlow<YouTubeMusicAuthState>
        previousAuth = state.value
        authClass.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(YouTubeMusicAuth, true)
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
        KeyRouteLog.composeFocus(false)
    }

    @Test
    fun rightOnTheSettingsGearEntersHideWatched() = runComposeUiTest {
        signedInForTest()
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    YouTubeTabScreen(onPlay = {}, onOpenSettings = {}, modifier = Modifier, topChromePadding = null)
                    TabletFloatingTopBar(
                        selectedTab = AppScreenTab.YouTube,
                        onTabSelected = {},
                        onProfileSelected = {},
                        onAddProfileRequested = {},
                    )
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        RootNavFocus.topBarAvailable = true
        runOnIdle { RootNavFocus.enterTopBarAt(Int.MAX_VALUE) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithContentDescription("Hide watched").assertIsFocused()
    }

    @Test
    fun rightOnTheSettingsGearEntersTheWatchPagePillWhileBrowsing() = runComposeUiTest {
        YouTubeWatchLayout.begin()
        YouTubeWatchLayout.startBrowsing()
        setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    YouTubeTabScreen(onPlay = {}, onOpenSettings = {}, modifier = Modifier, topChromePadding = null)
                    TabletFloatingTopBar(
                        selectedTab = AppScreenTab.YouTube,
                        onTabSelected = {},
                        onProfileSelected = {},
                        onAddProfileRequested = {},
                    )
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        RootNavFocus.topBarAvailable = true
        runOnIdle { RootNavFocus.enterTopBarAt(Int.MAX_VALUE) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithContentDescription("Watch page").assertIsFocused()
    }
}
