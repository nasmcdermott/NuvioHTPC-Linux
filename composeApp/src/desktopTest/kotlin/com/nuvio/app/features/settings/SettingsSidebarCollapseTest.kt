package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The category rail's name is also the handle for its unfolded section list. Choosing the category
 * that is already open folds the list away without leaving the page; choosing it again unfolds it.
 * A different category keeps the old behaviour (select it and open its sections).
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSidebarCollapseTest {

    private val player = DesktopSettingsSidebarItem(
        label = "Player",
        icon = Icons.Rounded.PlayArrow,
        page = SettingsPage.Player,
    )

    private val subItems = listOf(
        DesktopSettingsSidebarSubItem(label = "Player section", page = SettingsPage.Player, anchor = "player"),
        DesktopSettingsSidebarSubItem(label = "Subtitle section", page = SettingsPage.Player, anchor = "subtitles"),
    )

    @Test
    fun choosingTheOpenCategoryCollapsesAndRestoresItsSections() = runComposeUiTest {
        var requested: SettingsPage? = null
        setContent {
            NuvioTheme {
                DesktopSettingsSidebarList(
                    items = listOf(player),
                    allItems = listOf(player),
                    activeSidebarPage = SettingsPage.Player,
                    currentPage = SettingsPage.Player,
                    subItems = subItems,
                    onPageChange = { requested = it },
                    onSubItemClick = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Player section").assertIsDisplayed()

        onNodeWithText("Player").performClick()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("Player section").assertDoesNotExist()
        assertNull(requested, "collapsing the open category must not navigate away from it")

        onNodeWithText("Player").performClick()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("Player section").assertIsDisplayed()
    }
}
