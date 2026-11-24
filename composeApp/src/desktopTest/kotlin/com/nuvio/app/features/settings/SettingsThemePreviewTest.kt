package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.core.ui.trackTextInputFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsThemePreviewTest {

    @Test
    fun backLeavesThePreviewBeforeAnyPageLadder() {
        SettingsThemePreview.clearForTest()
        SettingsBackBridge.clearForTest()
        try {
            var pageHandled = 0
            SettingsBackBridge.register(Any()) {
                pageHandled += 1
                true
            }
            SettingsThemePreview.begin()

            assertTrue(SettingsThemePreview.active)
            assertTrue(SettingsBackBridge.handle())
            assertTrue(SettingsThemePreview.returnRequested)
            assertEquals(0, pageHandled, "preview Back must not walk the page ladder underneath")
        } finally {
            SettingsThemePreview.clearForTest()
            SettingsBackBridge.clearForTest()
        }
    }

    @Test
    fun inactivePreviewDoesNotAnswerBack() {
        SettingsThemePreview.clearForTest()
        SettingsBackBridge.clearForTest()
        try {
            assertFalse(SettingsThemePreview.returnRequested)
            assertFalse(SettingsBackBridge.handle())
            SettingsThemePreview.requestReturn()
            assertFalse(SettingsThemePreview.returnRequested)
        } finally {
            SettingsThemePreview.clearForTest()
            SettingsBackBridge.clearForTest()
        }
    }

    /**
     * The key path into Settings uses the preview modifier on the screen root. A focused text field
     * keeps physical Backspace for editing, but Escape (and a rebound Back) leaves the preview.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun previewBackLeavesTextFieldsOnlyForEscapeNotBackspace() = runComposeUiTest {
        SettingsThemePreview.begin()
        TextInputFocusTracker.reset()
        var value by mutableStateOf("")
        val field = FocusRequester()
        try {
            setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    Box(modifier = Modifier.settingsThemePreviewBack()) {
                        BasicTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier
                                .size(160.dp, 40.dp)
                                .focusRequester(field)
                                .trackTextInputFocus(),
                        )
                    }
                }
            }
            waitForIdle()
            runOnIdle { field.requestFocus() }
            waitForIdle()
            assertTrue(TextInputFocusTracker.active.value, "the field must register as text input")

            onRoot().performKeyInput { pressKey(Key.Backspace) }
            waitForIdle()
            assertFalse(SettingsThemePreview.returnRequested, "Backspace belongs to the text field")

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertTrue(SettingsThemePreview.returnRequested, "Escape leaves the preview")
        } finally {
            SettingsThemePreview.clearForTest()
            TextInputFocusTracker.reset()
        }
    }

    @Test
    fun finishClearsBothFlags() {
        SettingsThemePreview.begin()
        SettingsThemePreview.requestReturn()
        assertTrue(SettingsThemePreview.active)
        assertTrue(SettingsThemePreview.returnRequested)

        SettingsThemePreview.finish()

        assertFalse(SettingsThemePreview.active)
        assertFalse(SettingsThemePreview.returnRequested)
    }
}
