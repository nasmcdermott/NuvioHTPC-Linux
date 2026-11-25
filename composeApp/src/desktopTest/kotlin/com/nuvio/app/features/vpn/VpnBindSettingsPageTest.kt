package com.nuvio.app.features.vpn

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.vpnBindSettingsContent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guard for the fork:vpn-bind settings page (docs/vpn-bind-design.md §4): the page is composed
 * offscreen and must expose its controls. Reverting the page or its dispatch fails this test
 * (mutation-verified), and every control it shows is an existing focusable settings row, so
 * keyboard/gamepad movement stays in the repo's funnels. Matches are substring-based because
 * settings headings go through [settingsTitleCase] (e.g. "VPN protection" renders "Vpn protection").
 */
@OptIn(ExperimentalTestApi::class)
class VpnBindSettingsPageTest {

    @Test
    fun `settings page exposes protection modes, profiles and bind health`() = runComposeUiTest {
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                LazyColumn(Modifier.fillMaxSize()) {
                    vpnBindSettingsContent(isTablet = false)
                }
            }
        }
        waitForIdle()
        try {
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("protection", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            for (text in listOf(
                "protection", // the VPN protection section heading
                "Off", // the selected value of the one choice row
                "Refresh",
                "Bind health",
                "Profiles on this system",
            )) {
                assertTrue(
                    onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
                    "expected row/text: $text",
                )
            }
        } catch (e: Throwable) {
            val out = StringBuilder("error=$e\n")
            for (probe in listOf("VPN", "Off", "Refresh", "FULLCOMMON-ACTUAL")) {
                out.append("probe ").append(probe).append("=")
                try {
                    out.append(onAllNodesWithText(probe, substring = true).fetchSemanticsNodes().isNotEmpty())
                } catch (pe: Throwable) {
                    out.append("probe-failed:").append(pe)
                }
                out.append("\n")
            }
            java.io.File("build/vpn-page-probes.txt").writeText(out.toString())
            throw e
        }
    }
}
