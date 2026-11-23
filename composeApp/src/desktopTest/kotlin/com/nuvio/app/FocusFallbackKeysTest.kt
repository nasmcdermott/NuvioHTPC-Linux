package com.nuvio.app

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window's focus fallback (Main.kt) sends a key that nothing in the window took on to the screen on
 * top, after putting focus back there. Back and Escape were left out of it, so with no focused node —
 * which is what closing the player over the Sports page left behind — Back was delivered to nobody and
 * never retried, while an arrow first recovered the window. These are the keys that must be in it.
 */
class FocusFallbackKeysTest {

    @Test
    fun backReachesTheFallbackThatSendsItAgain() {
        assertTrue(KeyEvent.VK_ESCAPE in FOCUS_FALLBACK_KEYS, "Escape must be replayed when nothing took it")
        assertTrue(KeyEvent.VK_BACK_SPACE in FOCUS_FALLBACK_KEYS, "Backspace must be replayed when nothing took it")
        assertTrue(isFallbackFollowedKey(KeyEvent.VK_ESCAPE))
        assertTrue(isFallbackFollowedKey(KeyEvent.VK_BACK_SPACE))
    }

    @Test
    fun navigationAndSelectKeepTheirFallback() {
        listOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER)
            .forEach { assertTrue(it in FOCUS_FALLBACK_KEYS, "arrow/select $it must keep its fallback") }
    }

    @Test
    fun ordinaryKeysAreNotReplayed() {
        // A hotkey reaching no one is the "screen changed under the key" case, handled by the other
        // path; a letter must never be sent twice.
        assertFalse(isFallbackFollowedKey(KeyEvent.VK_A))
        assertFalse(KeyEvent.VK_A in FOCUS_FALLBACK_KEYS)
    }
}
