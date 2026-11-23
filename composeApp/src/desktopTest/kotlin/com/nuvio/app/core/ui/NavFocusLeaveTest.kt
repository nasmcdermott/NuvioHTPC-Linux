package com.nuvio.app.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Back out of the bar: the control the page had when the bar took the keys gets them again, and a
 * control remembered for another page is never handed the keys. The bar is drawn left of the page,
 * so the row here puts it first too and the fallback moves focus right.
 *
 * Offscreen, like the rest of the desktop suite: no window and no display are involved.
 */
@OptIn(ExperimentalTestApi::class)
class NavFocusLeaveTest {

    @AfterTest
    fun forgetRememberedControl() {
        // RootNavFocus is a singleton shared by every test in the run.
        RootNavFocus.forgetContentFocus()
        RootNavFocus.sidebarAvailable = false
        RootNavFocus.topBarAvailable = false
    }

    @Test
    fun leftAtEmbeddedPageEdgeHandsFocusToTheSidebar() = runComposeUiTest {
        var pageFocused = false
        var sidebarFocused = false
        val sidebarRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            LaunchedEffect(sidebarRequester) {
                RootNavFocus.enterRequests.collect { sidebarRequester.requestFocus() }
            }
            // The app's sidebar answers the height-carrying request too: a content control that
            // tracked its height (a chip, a card) leaves one remembered, and the plain request is
            // not what arrives then.
            LaunchedEffect(sidebarRequester) {
                RootNavFocus.positionEnterRequests.collect { sidebarRequester.requestFocus() }
            }
            Box(
                Modifier
                    .tabSidebarHandoff(
                        homeSelected = { false },
                        sidebarAvailable = { true },
                        onBack = { false },
                        // This scenario is the sidebar's: a bar drawn by another test's leftover
                        // global (the default reads anyTopBarDrawn) would take Back instead.
                        topBarAvailable = { false },
                    ),
            ) {
                Row {
                    Box(
                        Modifier
                            .testTag("sidebar")
                            .size(40.dp)
                            .focusRequester(sidebarRequester)
                            .onFocusChanged { sidebarFocused = it.hasFocus }
                            .focusable(),
                    )
                    Box(
                        Modifier
                            .testTag("page")
                            .size(40.dp)
                            .focusRequester(pageRequester)
                            .arrowKeyFocusNavigation(releaseWhenStuck = true)
                            .onFocusChanged { pageFocused = it.hasFocus }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()
        RootNavFocus.sidebarAvailable = true
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        assertTrue(pageFocused)

        onNodeWithTag("page").performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(sidebarFocused, "Back with no tab page to close should enter the sidebar")
        assertFalse(pageFocused)

        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        onNodeWithTag("page").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertTrue(sidebarFocused, "Left with no further focusable control should enter the sidebar")
        assertFalse(pageFocused)
    }

    @Test
    fun leftAtThePageEdgeEntersTheFloatingTopBarWhenNoSidebarIsDrawn() = runComposeUiTest {
        // fork:sports-navbar - a window navigated by the bar across the top has no sidebar on the
        // left, so a Left the page could not use steps out into that bar instead of doing nothing.
        var pageFocused = false
        var barFocused = false
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            LaunchedEffect(barRequester) {
                RootNavFocus.topBarEnterRequests.collect { barRequester.requestFocus() }
            }
            Box(
                Modifier
                    .tabSidebarHandoff(
                        homeSelected = { false },
                        sidebarAvailable = { false },
                        onBack = { false },
                        topBarAvailable = { true },
                    ),
            ) {
                Row {
                    Box(
                        Modifier
                            .testTag("topbar")
                            .size(40.dp)
                            .focusRequester(barRequester)
                            .onFocusChanged { barFocused = it.hasFocus }
                            .focusable(),
                    )
                    Box(
                        Modifier
                            .testTag("page")
                            .size(40.dp)
                            .focusRequester(pageRequester)
                            .arrowKeyFocusNavigation(releaseWhenStuck = true)
                            .onFocusChanged { pageFocused = it.hasFocus }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()
        RootNavFocus.topBarAvailable = true
        try {
            runOnIdle { pageRequester.requestFocus() }
            waitForIdle()
            assertTrue(pageFocused)

            onNodeWithTag("page").performKeyInput { pressKey(Key.DirectionLeft) }
            waitForIdle()
            assertTrue(barFocused, "Left with no sidebar and no further control enters the top bar")
            assertFalse(pageFocused)
        } finally {
            RootNavFocus.topBarAvailable = false
        }
    }

    @Test
    fun leavingTheBarGivesTheKeysBackToThePagesControl() = runComposeUiTest {
        val page = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { page },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        assertTrue(pageFocused, "the page starts with the keys")

        runOnIdle {
            // What the bar does as it takes the keys: remember the page's control, then focus itself.
            RootNavFocus.rememberContentFocus(page, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)
        assertFalse(pageFocused)

        runOnIdle { RootNavFocus.leaveSidebar() }
        waitForIdle()
        assertTrue(pageFocused, "Back out of the bar returns to the page's control")
        assertFalse(barFocused)
    }

    @Test
    fun aControlRememberedForAnotherPageIsNotHandedTheKeys() = runComposeUiTest {
        val openPage = Any()
        val leftPage = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { openPage },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        assertTrue(pageFocused)

        runOnIdle {
            // The bar was entered from another page: this control is not the one to go back to.
            RootNavFocus.rememberContentFocus(leftPage, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)

        runOnIdle { RootNavFocus.leaveSidebar() }
        waitForIdle()
        assertTrue(pageFocused, "the page beside the bar takes the keys instead")
        assertFalse(barFocused)
    }

    @Test
    fun aLeaveThatHandsTheKeysToSomethingElseLeavesThemOnTheBar() = runComposeUiTest {
        val page = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { page },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        runOnIdle {
            RootNavFocus.rememberContentFocus(page, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)

        // The Games row: game mode takes the keys itself, so the page must not be focused first.
        runOnIdle { RootNavFocus.leaveSidebar(restoreContent = false) }
        waitForIdle()
        assertTrue(barFocused, "the keys stay where the caller is about to put them")
        assertFalse(pageFocused)
    }

    @Test
    fun aLeaveAfterThePageAlreadyTookTheKeysLeavesThemThere() = runComposeUiTest {
        val page = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { page },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        runOnIdle {
            RootNavFocus.rememberContentFocus(page, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)

        // The page took the keys on its own before the emission was collected.
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        runOnIdle { RootNavFocus.leaveSidebar() }
        waitForIdle()
        assertTrue(pageFocused, "focus stays where the page put it")
        assertFalse(barFocused)
    }

    @Test
    fun theBarSideHandoffStopsAnsweringWhenThePageDisablesIt() = runComposeUiTest {
        var enabled by mutableStateOf(true)
        setContent {
            BarSideFocusHandoff(left = { true }, right = { false }, enabled = enabled)
        }
        waitForIdle()
        assertTrue(RootNavFocus.focusBarSide(left = true))
        assertFalse(RootNavFocus.focusBarSide(left = false))

        runOnIdle { enabled = false }
        waitForIdle()
        assertFalse(RootNavFocus.focusBarSide(left = true), "a disabled page must stop answering for the bar")
    }
}
