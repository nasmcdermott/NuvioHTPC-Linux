package com.nuvio.app.features.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

/** The fail-closed contract ported from the forks' StreamRoutePolicy (docs/vpn-bind-design.md §1). */
class VpnRoutePolicyTest {

    @Test
    fun `disabled protection is always direct`() {
        assertEquals(
            VpnRouteDecision.DIRECT,
            VpnRoutePolicy.decide("example.com", protectionEnabled = false, tunnelVerified = false),
        )
    }

    @Test
    fun `enabled but unverified tunnel blocks instead of silently falling back`() {
        assertEquals(
            VpnRouteDecision.BLOCK,
            VpnRoutePolicy.decide("example.com", protectionEnabled = true, tunnelVerified = false),
        )
    }

    @Test
    fun `enabled and verified tunnels bind`() {
        assertEquals(
            VpnRouteDecision.BIND,
            VpnRoutePolicy.decide("example.com", protectionEnabled = true, tunnelVerified = true),
        )
    }

    @Test
    fun `loopback stays direct even when protection is on`() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]", "::1", "127.255.9.9")) {
            assertEquals(
                VpnRouteDecision.DIRECT,
                VpnRoutePolicy.decide(host, protectionEnabled = true, tunnelVerified = true),
                "loopback $host must stay direct",
            )
        }
    }

    @Test
    fun `uninterpretable targets block`() {
        for (host in listOf(null, "", "   ")) {
            assertEquals(
                VpnRouteDecision.BLOCK,
                VpnRoutePolicy.decide(host, protectionEnabled = true, tunnelVerified = true),
            )
            assertEquals(
                VpnRouteDecision.BLOCK,
                VpnRoutePolicy.decide(host, protectionEnabled = false, tunnelVerified = false),
            )
        }
    }
}
