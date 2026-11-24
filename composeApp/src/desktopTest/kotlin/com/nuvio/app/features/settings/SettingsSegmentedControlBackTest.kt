package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.TextInputFocusTracker
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An On/Off control that sits inside a settings row which owns the keyboard stop still takes focus
 * (pointer, or an arrow walking onto it); Back from it must climb to the categories, not die there.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSegmentedControlBackTest {

    @Test
    fun backFromAnOnOffControlThatIsNotTheRowStopReturnsToTheCategories() = runComposeUiTest {
        TextInputFocusTracker.reset()
        var sidebarFocused = false
        val control = FocusRequester()
        val sidebarEntry = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                // What the page's list does around its content: a control with no Back of its own
                // lets the key bubble here, and the page climbs to the categories.
                val pageFocusManager = LocalFocusManager.current
                Column(
                    modifier = Modifier.settingsPageBack { settingsBackToCategories(pageFocusManager) },
                ) {
                    CompositionLocalProvider(
                        LocalSettingsFocusRegion provides SettingsFocusRegion.Content,
                        LocalSettingsPage provides SettingsPage.RandomPlay,
                    ) {
                        SettingsSegmentedControl(
                            options = listOf(
                                SettingsChoiceOption(true, "On"),
                                SettingsChoiceOption(false, "Off"),
                            ),
                            selectedValue = true,
                            enabled = true,
                            isTablet = true,
                            modifier = Modifier
                                .size(180.dp, 44.dp)
                                .focusRequester(control),
                            keyboardStop = false,
                            onSelected = {},
                        )
                    }
                    SidebarEntryBox(
                        requester = sidebarEntry,
                        onFocused = { sidebarFocused = it },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { control.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(sidebarFocused, "Back from an On/Off control returns to the categories")
    }

    /** A focusable that also serves as the registered sidebar entry, so Back's destination is observable. */
    @Composable
    private fun SidebarEntryBox(requester: FocusRequester, onFocused: (Boolean) -> Unit) {
        val focusManager = LocalFocusManager.current
        DisposableEffect(requester, focusManager) {
            SettingsSidebarFocus.register(requester, focusManager)
            onDispose { SettingsSidebarFocus.unregister(requester) }
        }
        Box(
            Modifier
                .size(60.dp)
                .focusRequester(requester)
                .onFocusChanged { onFocused(it.hasFocus) }
                .focusable(),
        )
    }
}