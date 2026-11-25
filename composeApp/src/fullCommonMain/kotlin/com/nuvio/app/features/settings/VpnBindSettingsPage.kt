package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope

internal actual fun LazyListScope.vpnBindSettingsContent(isTablet: Boolean) {
    // The VPN bind is a Linux desktop feature; this target hides the page behind the feature
    // policy (AppFeaturePolicy.vpnBindEnabled), so the content stays empty.
}
