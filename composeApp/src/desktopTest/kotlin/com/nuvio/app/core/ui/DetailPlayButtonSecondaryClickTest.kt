package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.features.details.components.DetailActionButtons
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real details Play button, not a stand-in. Play is a circle like the actions beside it now
 * (DetailIconAction's glass), so its clicks come from the circle's own handlers.
 */
@OptIn(ExperimentalTestApi::class)
class DetailPlayButtonSecondaryClickTest {

    @Test
    fun `right clicking the details play button runs the alternate action`() = runComposeUiTest {
        var primary = 0
        var alternate = 0
        setContent {
            Box(modifier = Modifier.testTag("row")) {
                DetailActionButtons(
                    playLabel = "Resume",
                    isTablet = true,
                    onPlayClick = { primary++ },
                    onPlayLongClick = { alternate++ },
                )
            }
        }

        // Play is a circle at the row's start now, named by its tooltip label, not a pill filling
        // the row: the click has to land on the circle, not the row's empty middle.
        onNodeWithContentDescription("Resume").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, alternate, "right-click should run the alternate play action")
        assertEquals(0, primary, "right-click must not also resume")
    }

    @Test
    fun `left clicking the details play button resumes`() = runComposeUiTest {
        var primary = 0
        var alternate = 0
        setContent {
            Box(modifier = Modifier.testTag("row")) {
                DetailActionButtons(
                    playLabel = "Resume",
                    isTablet = true,
                    onPlayClick = { primary++ },
                    onPlayLongClick = { alternate++ },
                )
            }
        }

        onNodeWithContentDescription("Resume").performMouseInput { click() }
        waitForIdle()

        assertEquals(1, primary, "left-click should resume")
        assertEquals(0, alternate, "left-click must not run the alternate action")
    }
}
