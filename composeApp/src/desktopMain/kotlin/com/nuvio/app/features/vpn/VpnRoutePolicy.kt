package com.nuvio.app.features.vpn

/**
 * Port of the Android TV forks' `StreamRoutePolicy` decision, with the invariants that make the
 * feature honest (docs/vpn-bind-design.md §1):
 *
 * - A selected protection mode is intent, never proof. Enabling protection without a verified
 *   live tunnel BLOCKs rather than silently falling back to DIRECT.
 * - Loopback targets are always DIRECT: local playback proxies must stay local, and the decision
 *   never resolves names to make that call.
 * - A target the policy cannot interpret BLOCKs.
 */
enum class VpnRouteDecision { BIND, DIRECT, BLOCK }

object VpnRoutePolicy {

    fun decide(
        targetHost: String?,
        protectionEnabled: Boolean,
        tunnelVerified: Boolean,
    ): VpnRouteDecision {
        val host = targetHost?.trim()?.takeIf { it.isNotEmpty() } ?: return VpnRouteDecision.BLOCK
        if (isLoopbackHost(host)) return VpnRouteDecision.DIRECT
        if (!protectionEnabled) return VpnRouteDecision.DIRECT
        return if (tunnelVerified) VpnRouteDecision.BIND else VpnRouteDecision.BLOCK
    }

    private fun isLoopbackHost(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        return bare == "localhost" ||
            bare == "::1" ||
            bare.startsWith("127.") ||
            bare.startsWith("::ffff:127.")
    }
}
