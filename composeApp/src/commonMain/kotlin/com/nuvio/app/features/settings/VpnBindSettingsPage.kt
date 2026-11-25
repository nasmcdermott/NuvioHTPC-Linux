package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope

/** fork:vpn-bind — Settings > VPN (desktop actual; see VpnBindSettingsPage.desktop.kt). */
internal expect fun LazyListScope.vpnBindSettingsContent(isTablet: Boolean)
