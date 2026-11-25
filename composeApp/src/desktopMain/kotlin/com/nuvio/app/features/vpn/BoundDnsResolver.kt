package com.nuvio.app.features.vpn

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.security.SecureRandom

/**
 * DNS over a device-bound UDP socket: the query leaves only through the VPN interface, so names
 * resolve through the tunnel instead of leaking to the default route's resolver
 * (docs/vpn-bind-design.md §1, DNS row). Resolves A records (falling back to AAAA) using the
 * interface's own DNS servers; no servers or a failed query is a failure, never a system-resolver
 * fallback — that fallback is exactly the leak this exists to prevent.
 */
internal object BoundDnsResolver {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    /** Addresses for [host], or null when the bound path could not answer. */
    fun resolveA(host: String, target: VpnBindTarget, lowLevel: VpnBindLowLevel = VpnBindNative): List<InetAddress>? {
        if (target.dnsServers.isEmpty()) return null
        if (host.isIpLiteral()) {
            return runCatching { listOf(InetAddress.getByName(host)) }.getOrNull()
        }
        for (type in listOf(TYPE_A, TYPE_AAAA)) {
            for (server in target.dnsServers) {
                query(host, type, server, target.interfaceName, target.dnsPort, lowLevel)?.let { return listOf(it) }
            }
        }
        return null
    }

    private fun query(
        host: String,
        type: Int,
        server: String,
        ifName: String,
        dnsPort: Int,
        lowLevel: VpnBindLowLevel,
    ): InetAddress? {
        val serverAddress = runCatching { InetAddress.getByName(server) }.getOrNull() ?: return null
        val packet = buildQuery(host, type) ?: return null
        val channel = DatagramChannel.open()
        try {
            if (!lowLevel.bindDatagramChannel(channel, ifName)) return null // fail closed
            channel.configureBlocking(true)
            if (runCatching {
                    channel.send(ByteBuffer.wrap(packet), InetSocketAddress(serverAddress, dnsPort))
                }.getOrDefault(0) <= 0
            ) return null
            val response = ByteBuffer.allocate(4096)
            channel.socket().soTimeout = 2000
            try {
                channel.receive(response) ?: return null
            } catch (e: SocketTimeoutException) {
                return null
            }
            return parseAnswer(response.array(), response.position(), type, host)
        } finally {
            runCatching { channel.close() }
        }
    }

    internal fun buildQuery(host: String, type: Int): ByteArray? {
        val labels = host.trim('.').split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
        val nameLength = labels.sumOf { it.length + 1 } + 1
        val packet = ByteArray(12 + nameLength + 4)
        val id = ByteArray(2).also { SecureRandom().nextBytes(it) } // random transaction id only
        id.copyInto(packet)
        packet[2] = 0x01 // RD
        packet[5] = 1 // QDCOUNT
        var offset = 12
        for (label in labels) {
            packet[offset++] = label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).copyInto(packet, offset)
            offset += label.length
        }
        packet[offset++] = 0
        packet[offset++] = (type ushr 8).toByte()
        packet[offset++] = type.toByte()
        packet[offset++] = (CLASS_IN ushr 8).toByte()
        packet[offset++] = CLASS_IN.toByte()
        return packet
    }

    /** Minimal reply parser: header, question, then resource records; skips compressed names. */
    private fun parseAnswer(
        packet: ByteArray,
        length: Int,
        type: Int,
        host: String,
    ): InetAddress? {
        if (length < 12 || (packet[2].toInt() and 0x80) == 0) return null // not a response
        if ((packet[3].toInt() and 0x0F) != 0) return null // rcode != NOERROR
        val questionCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val answerCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        var offset = 12
        repeat(questionCount) {
            offset = skipName(packet, offset) ?: return null
            offset += 4
        }
        repeat(answerCount) {
            offset = skipName(packet, offset) ?: return null
            if (offset + 10 > length) return null
            val recordType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val recordClass = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            val dataLength = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
            val dataStart = offset + 10
            val expectedLength = if (type == TYPE_A) 4 else 16
            if (recordClass == CLASS_IN && recordType == type && dataLength == expectedLength) {
                if (dataStart + dataLength > length) return null
                val bytes = packet.copyOfRange(dataStart, dataStart + dataLength)
                return runCatching { InetAddress.getByAddress(host, bytes) }.getOrNull()
            }
            offset = dataStart + dataLength
        }
        return null
    }

    /** Advances past a (possibly compressed) domain name; compression pointers are terminal. */
    private fun skipName(packet: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            if (length == 0) return offset + 1
            if (length and 0xC0 == 0xC0) return offset + 2
            offset += 1 + length
        }
        return null
    }

    private fun String.isIpLiteral(): Boolean = IPV4.matches(this) || this.contains(':')

    private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
}
