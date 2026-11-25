package com.nuvio.app.core.build

enum class TrailerPlaybackMode {
    IN_APP,
    EXTERNAL,
}

expect object AppFeaturePolicy {
    val pluginsEnabled: Boolean
    val downloadsEnabled: Boolean
    val notificationsEnabled: Boolean
    val p2pEnabled: Boolean
    val trailerPlaybackMode: TrailerPlaybackMode
    val heroTrailerPlaybackSupported: Boolean
    val inAppUpdaterEnabled: Boolean
    val imdbRatingLogoEnabled: Boolean

    /** Whether the user may point this build at a self-hosted Nuvio backend. */
    val customServerConnectionsEnabled: Boolean

    /**
     * Whether this build carries the Sports tab (see fork:sports). Always true in this fork: the
     * Sports entry, its settings page and its store are part of the normal build, with no launch
     * parameter required.
     */
    val sportsEnabled: Boolean

    /** fork:vpn-bind — the fail-closed VPN bind and its settings page (Linux desktop only). */
    val vpnBindEnabled: Boolean
}
