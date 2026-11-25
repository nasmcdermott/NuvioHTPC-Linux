package com.nuvio.app.features.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.features.music.library.AlbumCredits
import com.nuvio.app.features.music.library.Credit
import com.nuvio.app.features.music.library.CreditKind
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutsRepository
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The credits deck's keys. The guard the navigation rules demand: the pages turn through the
 * `navAction()` funnel, not raw arrow keys, so the test rebinds Move Right to X and asserts the
 * historical Right arrow is refused while the rebound key turns — a raw-key implementation fails
 * both, which is the mutation check. Turning stops at the deck's ends, Select opens a person and
 * their track row plays.
 */
@OptIn(ExperimentalTestApi::class)
class NowPlayingCreditsDeckTest {
    private val credits = AlbumCredits(
        0L, "browseId", "Artist", "Album",
        listOf(
            Credit("A Writer", null, CreditKind.Writer, "composer", 1),
            Credit("B Writer", null, CreditKind.Writer, "lyricist", 1),
            Credit("C Producer", null, CreditKind.Producer, null, 0),
            Credit("D Player", null, CreditKind.Player, "guitar", 2),
        ),
        found = true,
    )

    private val jumps = mutableListOf<Int>()
    private val opened = mutableListOf<String>()

    @AfterTest
    fun restoreBindings() {
        AppShortcutsRepository.resetAll()
        jumps.clear()
        opened.clear()
    }

    private fun deck(trackNumber: Int?): @Composable () -> Unit = {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocusLogged() }
        Box(Modifier.fillMaxSize().requiredSize(900.dp, 600.dp)) {
            CreditsDeck(
                credits = credits,
                trackNumber = trackNumber,
                trackTitles = listOf("Song One", "Song Two"),
                kept = emptyMap(),
                hereKey = "people3|artist|album",
                reduced = true, // no animated transition, so the next page is there when the test looks
                onOpenAlbum = { opened += it },
                onJumpToTrack = { jumps += it },
                focusRequester = focus,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun rightArrowIsRefusedOnceMoveRightIsReboundAndTheReboundKeyTurns() = runComposeUiTest {
        AppShortcutsRepository.setBinding(AppShortcutAction.MoveRight, KeyEvent.VK_X)
        setContent { deck(1)() }
        waitForIdle()
        onNodeWithText("THE SONG", useUnmergedTree = true).assertIsDisplayed()

        // The historical arrow is no longer Move Right: the deck must not turn on it.
        onNodeWithText("THE SONG", useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText("THE SONG", useUnmergedTree = true).assertIsDisplayed()

        // The rebound key turns to the Writers page.
        onNodeWithText("THE SONG", useUnmergedTree = true).performKeyInput { pressKey(Key.X) }
        waitForIdle()
        onNodeWithText("WRITERS", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun turningStopsAtTheLastKindPageAndSelectOpensAPerson() = runComposeUiTest {
        AppShortcutsRepository.setBinding(AppShortcutAction.MoveRight, KeyEvent.VK_X)
        setContent { deck(1)() }
        waitForIdle()
        onNodeWithText("THE SONG", useUnmergedTree = true).assertIsDisplayed()

        // Song → Writers → Producers → Players (the last kind with somebody).
        onNodeWithText("THE SONG", useUnmergedTree = true).performKeyInput { pressKey(Key.X) }
        waitForIdle()
        onNodeWithText("WRITERS", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("WRITERS", useUnmergedTree = true).performKeyInput { pressKey(Key.X) }
        waitForIdle()
        onNodeWithText("PRODUCERS", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("PRODUCERS", useUnmergedTree = true).performKeyInput { pressKey(Key.X) }
        waitForIdle()
        onNodeWithText("PLAYERS", useUnmergedTree = true).assertIsDisplayed()

        // Right at the end must not wrap: the page stays.
        onNodeWithText("PLAYERS", useUnmergedTree = true).performKeyInput { pressKey(Key.X) }
        waitForIdle()
        onNodeWithText("PLAYERS", useUnmergedTree = true).assertIsDisplayed()

        // Select opens the person: their page has the CREDITS header and their track row.
        onNodeWithText("PLAYERS", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("CREDITS", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Play", useUnmergedTree = true).assertIsDisplayed()

        // The track row plays that track (1 up → 0 up).
        onNodeWithText("Play", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(1), jumps)

        // Left goes back to the Players page.
        onNodeWithText("CREDITS", useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithText("PLAYERS", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(opened.isEmpty(), "nothing opened an album yet")
    }
}
