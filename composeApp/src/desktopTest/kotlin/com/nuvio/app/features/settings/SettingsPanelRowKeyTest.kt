package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The context panel's rows are key funnels, not plain clickables (fork:vpn-bind finding).
 *
 * A Right pressed on a page row hands focus to the panel by design ("Right from the content
 * reaches the context panel"), so a panel row must register as a [SettingsContextFocus] entry and
 * answer the keys — otherwise focus lands on a row with dead arrows and no way back, and the
 * window fallback eventually wanders off. Regression that produced exactly that: favorites and
 * recent-searches rows were `settingsBackOnly`-only clickables.
 *
 * The rows sit below the content entry in this composition, so Right cannot reach them spatially —
 * the handoff must go through [SettingsContextFocus.requestEntry] (which is what the real panel
 * rows depend on; reverting their wiring makes the panel region empty and this test fails).
 *
 * Offscreen, like the rest of the desktop suite.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPanelRowKeyTest {

    @Test
    fun rightFromContentEntersThePanelEnterActivatesAndLeftReturns() = runComposeUiTest {
        var contentFocused = false
        var panelFocused = false
        var secondPanelFocused = false
        var activated = 0
        val contentEntry = FocusRequester()
        setContent {
            CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.Content) {
                PanelRow(
                    modifier = Modifier
                        .focusRequester(contentEntry)
                        .onFocusChanged { contentFocused = it.hasFocus },
                )
            }
            Column {
                CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.ContextPanel) {
                    PanelRow(
                        modifier = Modifier.onFocusChanged { panelFocused = it.hasFocus },
                        onActivate = { activated += 1 },
                    )
                    PanelRow(
                        modifier = Modifier.onFocusChanged { secondPanelFocused = it.hasFocus },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { contentEntry.requestFocus() }
        waitForIdle()
        assertTrue(contentFocused, "the content row under test has the keyboard")

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertTrue(panelFocused, "Right from the content entered the panel (registered entries)")
        assertFalse(contentFocused)

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, activated, "Select activates the panel row once")

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(secondPanelFocused, "Down walks the panel rows")
        assertFalse(panelFocused)

        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertTrue(contentFocused, "Left at the panel's edge returns to the content")
        assertFalse(secondPanelFocused)
    }

    /** A panel row exactly as the panel composes it: [settingsNavItem] before the focusable. */
    @Composable
    private fun PanelRow(
        modifier: Modifier = Modifier,
        onActivate: () -> Unit = {},
    ) {
        Box(
            modifier
                .size(40.dp)
                .settingsNavItem(onActivate = onActivate)
                .focusable(),
        )
    }
}
