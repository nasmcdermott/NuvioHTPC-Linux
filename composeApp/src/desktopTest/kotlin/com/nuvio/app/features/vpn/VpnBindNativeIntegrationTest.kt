package com.nuvio.app.features.vpn

import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Live bind tests: they exercise the real SO_BINDTODEVICE path, so they need the native bridge
 * (`-Pnuvio.linux.nativeSmokeTest=true`) and a working unprivileged bind (probed). The loopback
 * interface stands in for the VPN device: sockets bound to `lo` route to loopback origins
 * normally, and everything else fails closed exactly like a dropped VPN would.
 */
class VpnBindNativeIntegrationTest {

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun reset() {
        closeables.forEach { runCatching { it.close() } }
        BindRelay.resetForTest()
    }

    private fun assumeBindable() {
        assumeTrue(VpnBindNative.availability() == VpnBindAvailability.AVAILABLE) // skips without the native gate
    }

    @Test
    fun `plain http request travels through the relay over the bound device`() {
        assumeBindable()
        val origin = MockWebServer()
        origin.enqueue(MockResponse().setBody("through-the-tunnel"))
        origin.start()
        closeables.add(origin)

        BindRelay.start(VpnBindTarget("lo"), VpnBindNative)
        val relayUrl = BindRelay.currentProxyUrl()
        assertTrue(relayUrl != null)
        val token0 = relayUrl.substringBefore('@').substringAfterLast(':')
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relayUrl.substringAfterLast(':').toInt())))
            .proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", okhttp3.Credentials.basic("nuvio", token0))
                    .build()
            }
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(
            Request.Builder().url("http://127.0.0.1:${origin.port}/protected").build(),
        ).execute()
        assertEquals("through-the-tunnel", response.body!!.string())
        assertEquals("/protected", origin.takeRequest().path)
    }

    @Test
    fun `connect tunnel reaches a plain origin and pumps both directions`() {
        assumeBindable()
        val origin = MockWebServer()
        origin.enqueue(MockResponse().setBody("tunneled-connect").setHeader("Connection", "close"))
        origin.start()
        closeables.add(origin)

        BindRelay.start(VpnBindTarget("lo"), VpnBindNative)
        val relayUrl = BindRelay.currentProxyUrl()!!
        val token = relayUrl.substringBefore('@').substringAfterLast(':')
        val port = relayUrl.substringAfterLast(':').toInt()

        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000 // no read may outlive the test if a pump stalls
        closeables.add(socket)
        socket.getOutputStream().apply {
            write("CONNECT 127.0.0.1:${origin.port} HTTP/1.1\r\n".toByteArray())
            write("Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("nuvio:$token".toByteArray())}\r\n\r\n".toByteArray())
            flush()
        }
        val head = socket.getInputStream().readHeaders()
        assertTrue(head.startsWith("HTTP/1.1 200"), "relay answered: $head")
        socket.getOutputStream().apply {
            write("GET /tunneled HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        // MockWebServer does not necessarily close after the body, so read the tunnel's own
        // response head first, then the body by its Content-Length.
        val tunnelHead = socket.getInputStream().readHeaders()
        val contentLength = tunnelHead.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = if (contentLength > 0) {
            socket.getInputStream().readNBytes(contentLength).decodeToString()
        } else {
            socket.getInputStream().readAllBytes().decodeToString()
        }
        assertEquals("tunneled-connect", body.trim())
    }

    @Test
    fun `a failed bind refuses the request instead of falling back to direct`() {
        assumeBindable()
        val origin = MockWebServer()
        origin.start()
        closeables.add(origin)

        BindRelay.start(VpnBindTarget("nvb-no-such-interface"), VpnBindNative)
        val relayUrl = BindRelay.currentProxyUrl()!!
        val token0 = relayUrl.substringBefore('@').substringAfterLast(':')
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relayUrl.substringAfterLast(':').toInt())))
            .proxyAuthenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", okhttp3.Credentials.basic("nuvio", token0))
                    .build()
            }
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val refused = client.newCall(Request.Builder().url("http://127.0.0.1:${origin.port}/").build()).execute()
        assertEquals(502, refused.code, "an unbindable upstream must be refused, not proxied direct")
        assertEquals(0, origin.requestCount, "an unbound direct connection must never have happened")
    }

    @Test
    fun `dns resolution leaves through the bound device`() {
        assumeBindable()
        val cannedIp = "93.184.216.34"
        val responder = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        responder.soTimeout = 2000
        closeables.add(responder)
        val responderPort = responder.localPort
        val stopResponder = thread(name = "canned-dns", isDaemon = true) {
            val buffer = ByteArray(1500)
            while (!responder.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    responder.receive(packet)
                } catch (e: Exception) {
                    return@thread
                }
                val answer = cannedResponse(packet.data, packet.length, cannedIp)
                responder.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
            }
        }

        val target = VpnBindTarget("lo", listOf("127.0.0.1"), dnsPort = responderPort)
        val resolved = BoundDnsResolver.resolveA("origin.example.test", target, VpnBindNative)
        assertEquals(listOf(cannedIp), resolved?.map { it.hostAddress })
        stopResponder.join(1000)
    }

    /** Echoes the question, answers one A record with the canned address. */
    private fun cannedResponse(query: ByteArray, length: Int, ip: String): ByteArray {
        val out = query.copyOfRange(0, length).copyOf(length + 16)
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte() // QR, RD, RA, rcode 0
        out[6] = 0
        out[7] = 1 // ANCOUNT
        var offset = length // the question is the tail of the packet
        out[offset++] = 0xC0.toByte()
        out[offset++] = 0x0C // pointer to the question name
        out[offset++] = 0
        out[offset++] = 1 // TYPE A
        out[offset++] = 0
        out[offset++] = 1 // CLASS IN
        out[offset++] = 0
        out[offset++] = 0
        out[offset++] = 0
        out[offset++] = 60 // TTL
        out[offset++] = 0
        out[offset++] = 4 // RDLENGTH
        ip.split('.').forEach { out[offset++] = it.toInt().toByte() }
        return out.copyOf(offset)
    }

    private fun InputStream.readHeaders(): String {
        val head = StringBuilder()
        while (true) {
            val line = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0 || c == '\n'.code) break
                line.append(c.toChar())
            }
            if (line.isBlank()) break // a final "\r" is the blank line, not a header
            head.append(line.toString().trimEnd('\r')).append('\n')
            if (head.length > 64 * 1024) break
        }
        return head.toString()
    }
}
