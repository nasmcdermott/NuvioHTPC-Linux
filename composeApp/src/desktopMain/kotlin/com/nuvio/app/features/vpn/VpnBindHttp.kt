package com.nuvio.app.features.vpn

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Base64

/**
 * OkHttp + Ktor wiring of the fail-closed bind relay (docs/vpn-bind-design.md §3).
 *
 * One contract for every HTTP client: when the bind mode is on, all non-loopback traffic rides
 * the loopback relay (whose upstream sockets are device-bound and fail closed); loopback targets
 * never enter the tunnel. When the mode is off, everything stays as upstream. A mode that is on
 * but unverified still points clients at the relay — the relay refuses upstream, so failing
 * closed instead of leaking is the behavior, and the verified flag stays a UI/policy concern.
 */
internal object VpnBindHttp {

    /** The relay proxy while protection is on, else null — callers keep their direct behavior. */
    fun relayProxy(): Proxy? {
        val relayUrl = VpnBindController.relayProxyUrl() ?: return null
        val port = relayUrl.substringAfterLast(':').toIntOrNull() ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    }

    /** Proxy-Authorization matching [relayProxy]; non-null exactly when it is. */
    fun relayProxyAuthorization(): String? {
        val relayUrl = VpnBindController.relayProxyUrl() ?: return null
        val token = relayUrl.substringBefore('@').substringAfterLast(':')
        return "Basic " + Base64.getEncoder().encodeToString("nuvio:$token".toByteArray())
    }
}

/**
 * OkHttp proxy selector with the fork's loopback invariant built in: `VpnRoutePolicy` keeps local
 * playback proxies local, everything else rides the relay. Reads controller state per lookup, so
 * enabling or disabling the bind needs no client rebuild.
 */
internal class VpnBindProxySelector(private val relayProxy: Proxy) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        if (VpnBindController.state().mode == VpnBindController.Mode.OFF) return listOf(Proxy.NO_PROXY)
        if (VpnRoutePolicy.decide(host, protectionEnabled = true, tunnelVerified = true) ==
            VpnRouteDecision.DIRECT
        ) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(relayProxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, failure: IOException?) {
        // The relay already failed closed; nothing to retry on the direct path.
    }
}
