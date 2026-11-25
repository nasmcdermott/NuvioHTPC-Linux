package com.nuvio.app.features.vpn

import java.net.Socket
import java.nio.channels.DatagramChannel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The controller's fail-closed state machine, no native code involved. */
class VpnBindControllerTest {

    private class FakeLowLevel(var index: Int) : VpnBindLowLevel {
        override fun ifIndex(ifName: String): Int = index
        override fun bindSocket(socket: Socket, ifName: String): Boolean = index > 0
        override fun bindDatagramChannel(channel: DatagramChannel, ifName: String): Boolean = index > 0
        override fun probe(): Boolean = index > 0
    }

    private val target = VpnBindTarget("wg0", listOf("10.64.0.1"))

    @AfterTest
    fun reset() {
        VpnBindController.resetForTest()
    }

    @Test
    fun `off is direct and unverified`() {
        assertEquals(VpnRouteDecision.DIRECT, VpnBindController.decide("example.com"))
        assertEquals(VpnBindController.Mode.OFF, VpnBindController.state().mode)
        assertFalse(VpnBindController.state().verified)
    }

    @Test
    fun `native unavailable blocks even when the user asked for protection`() {
        val enabled = VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = FakeLowLevel(5),
            availability = VpnBindAvailability.LIBRARY_UNAVAILABLE,
        )
        assertFalse(enabled)
        assertFalse(VpnBindController.state().verified)
        assertEquals(VpnRouteDecision.BLOCK, VpnBindController.decide("example.com"))
        // Fail closed made real: the relay is up but refuses everything (never a direct fallback).
        assertTrue(VpnBindController.relayProxyUrl()?.startsWith("http://nuvio:") == true)
    }

    @Test
    fun `interface gone at enable time blocks`() {
        val enabled = VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = FakeLowLevel(0),
            availability = VpnBindAvailability.AVAILABLE,
        )
        assertFalse(enabled)
        assertEquals(VpnRouteDecision.BLOCK, VpnBindController.decide("example.com"))
        assertTrue(VpnBindController.relayProxyUrl()?.startsWith("http://nuvio:") == true)
    }

    @Test
    fun `verified target binds and a vanished interface blocks without silent fallback`() {
        val fake = FakeLowLevel(5)
        val enabled = VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = fake,
            watchIntervalMs = 30,
            availability = VpnBindAvailability.AVAILABLE,
        )
        assertTrue(enabled)
        assertTrue(VpnBindController.state().verified)
        assertEquals(VpnRouteDecision.BIND, VpnBindController.decide("example.com"))
        assertEquals(VpnRouteDecision.DIRECT, VpnBindController.decide("127.0.0.1")) // loopback stays local
        assertTrue(VpnBindController.relayProxyUrl()?.startsWith("http://nuvio:") == true)

        fake.index = 0 // the VPN goes down
        waitFor { !VpnBindController.state().verified }
        assertEquals(VpnRouteDecision.BLOCK, VpnBindController.decide("example.com"))

        fake.index = 5 // the same interface comes back: the SAME target re-verifies, never rebinds elsewhere
        waitFor { VpnBindController.state().verified }
        assertEquals(VpnRouteDecision.BIND, VpnBindController.decide("example.com"))
    }

    @Test
    fun `disable returns to upstream behavior`() {
        VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = FakeLowLevel(5),
            availability = VpnBindAvailability.AVAILABLE,
        )
        VpnBindController.disable()
        assertEquals(VpnBindController.Mode.OFF, VpnBindController.state().mode)
        assertEquals(VpnRouteDecision.DIRECT, VpnBindController.decide("example.com"))
        assertNull(VpnBindController.relayProxyUrl())
    }

    private fun waitFor(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }
}
