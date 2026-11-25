package com.nuvio.app.features.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The profile bubble in the top/floating bar is a keyboard and gamepad surface, not only a pointer
 * one: opening it focuses the active profile, Left/Right walk the row, Select chooses the focused
 * bubble (including Add profile), and Back/Dismiss closes it.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileSwitcherKeyboardTest {

    private var previousState: ProfileState? = null

    private fun seedProfiles(profiles: List<NuvioProfile>) {
        val field = ProfileRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(ProfileRepository) as MutableStateFlow<ProfileState>
        previousState = state.value
        state.value = ProfileState(
            profiles = profiles,
            activeProfile = profiles.firstOrNull(),
            isLoaded = true,
        )
    }

    @AfterTest
    fun restoreProfiles() {
        val field = ProfileRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(ProfileRepository) as MutableStateFlow<ProfileState>
        state.value = previousState ?: ProfileState()
        previousState = null
    }

    private fun profile(index: Int, name: String) = NuvioProfile(profileIndex = index, name = name)

    @Test
    fun rightThenSelectChoosesTheNextProfile() = runComposeUiTest {
        seedProfiles(listOf(profile(1, "Alice"), profile(2, "Bob"), profile(3, "Cara")))
        val chosen = AtomicReference<NuvioProfile?>()
        setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 480.dp)) {
                    ProfileSwitcherTab(
                        selected = false,
                        onClick = {},
                        onProfileSelected = { chosen.set(it) },
                        onAddProfileRequested = {},
                        openPopupOnClick = true,
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { ProfileSwitcherTabRequests.requestOpen() }
        mainClock.advanceTimeBy(700)
        waitForIdle()
        onNodeWithTag("profileSwitcherPopup").assertExists()
        onNodeWithTag("profileSwitcherPopup").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.Enter)
        }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        runOnIdle { assertEquals("Bob", chosen.get()?.name) }
    }

    @Test
    fun rightReachesTheAddProfileBubbleAndSelectRequestsIt() = runComposeUiTest {
        seedProfiles(listOf(profile(1, "Alice"), profile(2, "Bob"), profile(3, "Cara")))
        val added = AtomicBoolean(false)
        setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 480.dp)) {
                    ProfileSwitcherTab(
                        selected = false,
                        onClick = {},
                        onProfileSelected = {},
                        onAddProfileRequested = { added.set(true) },
                        openPopupOnClick = true,
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { ProfileSwitcherTabRequests.requestOpen() }
        mainClock.advanceTimeBy(700)
        waitForIdle()
        onNodeWithTag("profileSwitcherPopup").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.Enter)
        }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        runOnIdle { assertTrue(added.get(), "Select on the Add profile bubble must request the add flow") }
    }

    @Test
    fun escapeCancelsThePinPadWithoutClosingTheSelector() = runComposeUiTest {
        seedProfiles(listOf(profile(1, "Alice").copy(pinEnabled = true), profile(2, "Bob")))
        setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 480.dp)) {
                    ProfileSwitcherTab(
                        selected = false,
                        onClick = {},
                        onProfileSelected = {},
                        onAddProfileRequested = {},
                        openPopupOnClick = true,
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { ProfileSwitcherTabRequests.requestOpen() }
        mainClock.advanceTimeBy(700)
        waitForIdle()
        // Enter opens the PIN pad for the focused, locked profile.
        onNodeWithTag("profileSwitcherPopup").performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        onNodeWithTag("profileSwitcherPopup").performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        // Escape belongs to the PIN pad, not to the selector underneath it.
        onNodeWithTag("profileSwitcherPopup").assertExists()
    }

    @Test
    fun dismissClosesTheOpenSelector() = runComposeUiTest {
        seedProfiles(listOf(profile(1, "Alice"), profile(2, "Bob")))
        setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 480.dp)) {
                    ProfileSwitcherTab(
                        selected = false,
                        onClick = {},
                        onProfileSelected = {},
                        onAddProfileRequested = {},
                        openPopupOnClick = true,
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { ProfileSwitcherTabRequests.requestOpen() }
        mainClock.advanceTimeBy(700)
        waitForIdle()
        onNodeWithTag("profileSwitcherPopup").performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag("profileSwitcherPopup").assertDoesNotExist()
    }
}
