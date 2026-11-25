package com.nuvio.app.features.vpn

import com.nuvio.app.features.iptv.IptvPlaybackSession
import java.net.URI

/**
 * mpv's share of the bind (docs/vpn-bind-design.md §3): a remote playback source under an active
 * mode gets the bind relay as `http-proxy`, so FFmpeg's sockets never leave through the default
 * route. Once mpv holds the option, every (re)connect of the stream goes through the relay — an
 * interface drop then fails the stream instead of leaking, and a mode that is on but unverified
 * points at a relay that refuses upstream: BLOCK, never a direct fallback.
 *
 * Torrent playback stays out: its source is the engine's loopback stream server (always direct),
 * and the engine's own sockets carry the bind through the engine-config wiring.
 */
internal fun vpnBindMpvProxyOption(sourceUrl: String): String? {
    if (VpnBindController.state().mode == VpnBindController.Mode.OFF) return null
    if (IptvPlaybackSession.isIptvUrl(sourceUrl)) return null // handled inside iptvMpvOptions
    val host = runCatching { URI(sourceUrl).host }.getOrNull()
    if (host != null && VpnRoutePolicy.decide(
            host,
            protectionEnabled = true,
            tunnelVerified = true,
        ) == VpnRouteDecision.DIRECT
    ) {
        return null // loopback and LAN-direct sources never enter the tunnel
    }
    val relayUrl = VpnBindController.relayProxyUrl()
        ?: return null // relay gone mid-play: the established sockets already fail closed
    return "http-proxy=$relayUrl"
}
