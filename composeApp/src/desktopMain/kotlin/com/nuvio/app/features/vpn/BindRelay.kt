package com.nuvio.app.features.vpn

import co.touchlab.kermit.Logger
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * The fail-closed playback/HTTP relay (docs/vpn-bind-design.md §3): a loopback-only, token-checked
 * HTTP proxy (absolute-URI requests plus CONNECT) whose upstream sockets are device-bound with
 * SO_BINDTODEVICE and name-resolved over the bound device. There is no direct fallback: when the
 * bind or the bound DNS fails, the client connection is refused, which the fork's policy calls
 * BLOCK — a selected protection mode is intent, and enabled protection must never leak.
 *
 * Shape follows [com.nuvio.app.features.iptv.IptvProxyRelay]: loopback bind only, per-run random
 * password, one request per connection for plain HTTP; CONNECT tunnels pump both directions.
 */
internal object BindRelay {
    private val log = Logger.withTag("VpnBindRelay")
    private const val USER = "nuvio"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val HEADER_LIMIT = 64 * 1024

    private val token: String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val expectedAuthorization =
        "Basic " + Base64.getEncoder().encodeToString("$USER:$token".toByteArray())

    @Volatile private var server: ServerSocket? = null
    @Volatile private var target: VpnBindTarget? = null
    @Volatile private var lowLevel: VpnBindLowLevel = VpnBindNative

    /** `http://nuvio:<token>@127.0.0.1:<port>` for mpv's http-proxy / Ktor / OkHttp. */
    @Synchronized
    fun start(newTarget: VpnBindTarget, lowLevelFacade: VpnBindLowLevel = VpnBindNative): String? {
        target = newTarget
        lowLevel = lowLevelFacade
        val socket = server ?: startServer() ?: return null
        return "http://$USER:$token@127.0.0.1:${socket.localPort}"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** The proxy URL while running, or null when stopped — callers treat null as BLOCK. */
    @Synchronized
    fun currentProxyUrl(): String? {
        val socket = server ?: return null
        return "http://$USER:$token@127.0.0.1:${socket.localPort}"
    }

    /** For tests: a fresh token per run. */
    @Synchronized
    internal fun resetForTest() {
        stop()
    }

    private fun startServer(): ServerSocket? = runCatching {
        val socket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        server = socket
        thread(name = "bind-relay-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                log.i { "relay accepted a connection" }
                thread(name = "bind-relay-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: IOException) {
                        // client disconnects mid-stream are normal (seek, stop, close)
                    } catch (e: Exception) {
                        log.w { "relay request failed: ${e.javaClass.simpleName}: ${e.message}" }
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
        log.i { "bind relay listening on 127.0.0.1:${socket.localPort}" }
        socket
    }.getOrNull().also { if (it == null) log.w { "bind relay failed to start" } }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream(), 8192)
        val requestLine = input.readRequestLine() ?: return
        val headers = input.readHeaders() // consumed once, here
        if (!authorized(headers)) {
            respond(client, "HTTP/1.1 407 Proxy Authentication Required\r\nConnection: close\r\n\r\n")
            return
        }
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            respond(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return
        }
        when (parts[0]) {
            "CONNECT" -> tunnel(client, parts[1])
            "GET", "HEAD", "POST" -> forward(client, input, headers, parts[0], parts[1])
            else -> respond(client, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
        }
    }

    private fun authorized(headers: String): Boolean {
        return headers.lineSequence()
            .firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() == expectedAuthorization
    }

    /** CONNECT host:port — resolve over the bound device, connect bound, then pump. */
    private fun tunnel(client: Socket, authority: String) {
        val host = authority.substringBeforeLast(':')
        val port = authority.substringAfterLast(':').toIntOrNull() ?: 443
        if (!connectUpstream(client, host, port) { upstream ->
                respond(client, "HTTP/1.1 200 Connection established\r\n\r\n")
                pumpBoth(client, upstream)
            }
        ) {
            // BLOCK: refuse, never fall back to an unbound connect.
            respond(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
        }
    }

    /** Absolute-URI plain HTTP: rewrite to origin form, forward, stream the response to EOF. */
    private fun forward(client: Socket, input: InputStream, headers: String, method: String, target0: String) {
        val uri = runCatching { URI(target0) }.getOrNull()
        if (uri?.host == null || uri.scheme?.equals("http", ignoreCase = true) != true) {
            // HTTPS must arrive as CONNECT (clients do); refusing here cannot become a leak.
            respond(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            return
        }
        val port = if (uri.port > 0) uri.port else 80
        val head = requestHead(method, uri, headers, port)
        if (!connectUpstream(client, uri.host, port) { upstream ->
                upstream.getOutputStream().use { out ->
                    out.write(head)
                    out.flush()
                    // Any request body travels behind the head; the response then streams back
                    // until the upstream closes (one request per connection, Connection: close).
                    val contentLength = head.decodeToString()
                        .lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
                    if (contentLength > 0) {
                        val buffer = ByteArray(32 * 1024)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                        out.flush()
                    }
                    try {
                        copyStream(upstream.getInputStream(), client.getOutputStream())
                    } catch (e: IOException) {
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        ) {
            respond(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
        }
    }

    /** Shared fail-closed upstream establishment: bound DNS + bound socket or nothing. */
    private inline fun connectUpstream(
        client: Socket,
        host: String,
        port: Int,
        onceConnected: (Socket) -> Unit,
    ): Boolean {
        val currentTarget = target ?: return false
        // IP literals never touch DNS; names resolve over the bound device only.
        val address = if (host.isIpLiteral()) {
            runCatching { InetAddress.getByName(host) }.getOrNull()
        } else {
            BoundDnsResolver.resolveA(host, currentTarget, lowLevel)?.firstOrNull()
        } ?: return false
        val upstream = Socket()
        // The JVM creates the OS file descriptor lazily (at connect); setting a standard socket
        // option forces it now, so SO_BINDTODEVICE can be applied BEFORE the connect (JDK 25 also
        // delegates through SocksSocketImpl/DelegatingSocketImpl, which describeFd diagnoses).
        runCatching { upstream.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true) }
        if (!lowLevel.bindSocket(upstream, currentTarget.interfaceName)) {
            runCatching { upstream.close() }
            return false
        }
        return try {
            upstream.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            upstream.tcpNoDelay = true
            onceConnected(upstream)
            true
        } catch (e: IOException) {
            runCatching { upstream.close() }
            false
        }
    }

    private fun requestHead(method: String, uri: URI, headers: String, port: Int): ByteArray {
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
        }
        val kept = headers.lineSequence()
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            .filter { !it.startsWith("Host:", ignoreCase = true) } // the relay owns Host on the origin-form head
            .joinToString("\r\n")
        val hostHeader = if (port == 80) "Host: ${uri.host}" else "Host: ${uri.host}:$port"
        return "$method $path HTTP/1.1\r\n$hostHeader\r\n$kept\r\n\r\n".toByteArray()
    }

    private fun pumpBoth(client: Socket, upstream: Socket) {
        thread(name = "bind-relay-c2u", isDaemon = true) {
            try {
                copyStream(client.getInputStream(), upstream.getOutputStream())
            } catch (e: IOException) {
            } finally {
                runCatching { upstream.close() }
                runCatching { client.close() }
            }
        }
        try {
            copyStream(upstream.getInputStream(), client.getOutputStream())
        } catch (e: IOException) {
        } finally {
            runCatching { client.close() }
            runCatching { upstream.close() }
            // the reverse direction unblocks via the closes above
        }
    }

    private fun copyStream(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            out.write(buffer, 0, read)
            out.flush()
        }
    }

    private fun respond(socket: Socket, head: String) {
        runCatching {
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
        }
    }

    private fun String.isIpLiteral(): Boolean = IPV4.matches(this) || this.contains(':')

    private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    private fun InputStream.readRequestLine(): String? {
        val line = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return null
            if (c == '\n'.code) {
                if (line.isEmpty()) continue // tolerate a stray blank line
                return line.toString().trimEnd('\r')
            }
            line.append(c.toChar())
            if (line.length > HEADER_LIMIT) return null
        }
    }

    /** Headers as one string (lines kept, final blank line consumed). */
    private fun InputStream.readHeaders(): String {
        val head = StringBuilder()
        while (true) {
            val line = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0 || c == '\n'.code) break
                line.append(c.toChar())
                if (line.length > HEADER_LIMIT) throw IOException("header too large")
            }
            if (line.isBlank()) break // a final "\r" from "\r\n" is the blank line, not a header
            head.append(line.toString().trimEnd('\r')).append('\n')
        }
        return head.toString()
    }
}
