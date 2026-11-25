package com.nuvio.app.core.build

import com.nuvio.app.features.player.desktop.DesktopHostOs

actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = true
    actual val downloadsEnabled: Boolean = true
    actual val notificationsEnabled: Boolean = false
    actual val p2pEnabled: Boolean = true
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    actual val heroTrailerPlaybackSupported: Boolean = true
    actual val inAppUpdaterEnabled: Boolean = true
    actual val imdbRatingLogoEnabled: Boolean = true
    actual val customServerConnectionsEnabled: Boolean = true
    // fork:sports — always on: Sports is part of the normal build in this fork.
    actual val sportsEnabled: Boolean = true
    // fork:vpn-bind — Linux only: the bind is native code in the Linux player bridge.
    actual val vpnBindEnabled: Boolean = DesktopHostOs.current == DesktopHostOs.LINUX
}
