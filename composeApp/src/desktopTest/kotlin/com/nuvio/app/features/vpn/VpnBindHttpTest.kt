package com.nuvio.app.features.vpn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The OkHttp/Ktor wiring contract: mode on → relay, loopback → direct, mode off → upstream. */
class VpnBindHttpTest {

    private class FakeLowLevel(var index: Int = 5) : VpnBindLowLevel {
        override fun ifIndex(ifName: String): Int = index
        override fun bindSocket(socket: java.net.Socket, ifName: String): Boolean = index > 0
        override fun bindDatagramChannel(channel: java.nio.channels.DatagramChannel, ifName: String): Boolean = index > 0
        override fun probe(): Boolean = index > 0
    }

    private val target = VpnBindTarget("wg0", listOf("10.64.0.1"))

    @AfterTest
    fun reset() {
        VpnBindController.resetForTest()
    }

    @Test
    fun `mode off leaves clients direct`() {
        assertNull(VpnBindHttp.relayProxy())
        assertNull(VpnBindHttp.relayProxyAuthorization())
    }

    @Test
    fun `mode on hands out the loopback relay and matching credentials`() {
        VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = FakeLowLevel(),
            availability = VpnBindAvailability.AVAILABLE,
        )
        val proxy = VpnBindHttp.relayProxy()
        assertTrue(proxy != null)
        assertEquals(InetAddress.getLoopbackAddress(), (proxy.address() as InetSocketAddress).address)

        val relayUrl = VpnBindController.relayProxyUrl()!!
        val expectedToken = relayUrl.substringBefore('@').substringAfterLast(':')
        val authorization = VpnBindHttp.relayProxyAuthorization()
        val decoded = String(Base64.getDecoder().decode(authorization!!.removePrefix("Basic ")))
        assertEquals("nuvio:$expectedToken", decoded)
    }

    @Test
    fun `selector keeps loopback direct and remote hosts on the relay`() {
        VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = FakeLowLevel(),
            availability = VpnBindAvailability.AVAILABLE,
        )
        val relay = VpnBindHttp.relayProxy()!!
        val selector = VpnBindProxySelector(relay)
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://127.0.0.1:8080/hls/index.m3u8")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://localhost:9/live")))
        assertEquals(listOf(relay), selector.select(URI("http://example.com/stream")))
    }

    @Test
    fun `selector returns to direct when the bind is off again`() {
        VpnBindController.enable(
            VpnBindController.Mode.STREAMS, target,
            lowLevel = FakeLowLevel(),
            availability = VpnBindAvailability.AVAILABLE,
        )
        VpnBindController.disable()
        val selector = VpnBindProxySelector(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://example.com/stream")))
    }
}
