package com.nuvio.app.features.vpn

import kotlin.concurrent.thread

/**
 * Reads VPN profiles and live VPN interfaces from the system. Read-only and dependency-free:
 * NetworkManager's stable `nmcli -t` terse output plus `ip -o link`, which together cover Debian,
 * Ubuntu, Fedora, Arch and the immutable variants (docs/vpn-bind-design.md §2).
 *
 * All parsing is done by pure functions on captured output, so [VpnDiscoveryParsingTest] covers
 * the formats without invoking nmcli. A missing NetworkManager degrades to interface-level
 * discovery from `ip -o link` only.
 */
class VpnProfileDiscovery(
    private val runCommand: VpnCommandRunner = VpnCommandRunner.Default,
) {

    fun discover(): VpnDiscoveryResult {
        val connections = runCommand.run(
            "nmcli", "-t", "-f", "NAME,UUID,TYPE,DEVICE", "connection", "show",
        )?.let { parseNmcliConnections(it) } ?: emptyList()

        val deviceStatus = runCommand.run(
            "nmcli", "-t", "--fields", "DEVICE,TYPE,STATE,CONNECTION", "device", "status",
        )?.let { parseNmcliDevices(it) } ?: emptyList()

        val linkInterfaces = runCommand.run("ip", "-o", "link", "show")
            ?.let { parseIpLink(it) } ?: emptyList()

        val externallyManagedDevices = deviceStatus
            .filter { it.type == "tun" && it.state.contains("externally") }
            .map { it.device }

        val profiles = connections.map { profile ->
            val status = deviceStatus.firstOrNull { it.connection == profile.name }
            profile.copy(
                externallyManaged = status != null && status.state.contains("externally"),
            )
        }

        val detected = linkInterfaces + deviceStatus
            .filter { it.type in VPN_DEVICE_TYPES && it.state.startsWith("connected") }
            .map { it.device }

        // wg-quick configs that NetworkManager does not own: filename-only (root-owned 0600).
        val wgQuickProfiles = runCommand.run("sh", "-c", "ls /etc/wireguard/*.conf 2>/dev/null")
            ?.let { parseWgQuickListing(it) }
            ?.filter { name -> profiles.none { it.name == name } }
            ?.map { name ->
                VpnProfile(
                    name = name,
                    uuid = null,
                    kind = VpnProfileKind.WG_QUICK,
                    device = null,
                    active = false,
                    externallyManaged = true,
                )
            } ?: emptyList()

        // Daemon-managed tunnels (Mullvad's mullvad-tun0000, a bare wg0, OpenVPN's tun0) have no
        // NetworkManager profile and no wg-quick config: they are only a live interface. They still
        // deserve a bindable row, so any live VPN-shaped interface no profile claims gets one.
        val claimedDevices = profiles.mapNotNull { it.device }.toSet()
        val daemonTunnelProfiles = (detected + linkInterfaces).distinct()
            .filter { it != "lo" && it !in claimedDevices }
            .map { name ->
                VpnProfile(
                    name = name,
                    uuid = null,
                    kind = VpnProfileKind.EXTERNAL_TUN,
                    device = name,
                    active = true,
                    externallyManaged = true,
                )
            }

        return VpnDiscoveryResult(
            profiles = profiles + wgQuickProfiles + daemonTunnelProfiles,
            liveInterfaces = (detected + linkInterfaces).distinct(),
        )
    }

    /** DNS servers the interface currently hands out (both families). */
    fun dnsServers(interfaceName: String): List<String> =
        runCommand.run(
            "nmcli", "-t", "-f", "IP4.DNS,IP6.DNS", "device", "show", interfaceName,
        )?.let { parseDeviceDns(it) } ?: emptyList()

    companion object {
        internal val VPN_DEVICE_TYPES = setOf("wireguard", "vpn", "tun")

        internal fun vpnKindOf(type: String): VpnProfileKind? = when (type) {
            "wireguard" -> VpnProfileKind.WIREGUARD
            "vpn" -> VpnProfileKind.VPN_PLUGIN
            "tun" -> VpnProfileKind.EXTERNAL_TUN
            else -> null
        }

        internal fun isVpnInterfaceName(name: String): Boolean =
            name.startsWith("wg") || name.startsWith("tun") || name.startsWith("tap") ||
                name.startsWith("tailscale") || name.startsWith("ppp") || name.startsWith("mullvad")

        /**
         * nmcli terse escapes a literal `:` inside a value as `\:`; split on unescaped colons
         * only, then unescape.
         */
        internal fun splitTerse(line: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < line.length) {
                when {
                    line[i] == '\\' && i + 1 < line.length && line[i + 1] == ':' -> {
                        current.append(':')
                        i += 2
                    }
                    line[i] == ':' -> {
                        fields.add(current.toString())
                        current.clear()
                        i++
                    }
                    else -> {
                        current.append(line[i])
                        i++
                    }
                }
            }
            fields.add(current.toString())
            return fields
        }

        internal fun parseNmcliConnections(output: String): List<VpnProfile> =
            output.lineSequence()
                .filter { it.isNotBlank() }
                .map { splitTerse(it) }
                .filter { it.size >= 3 }
                .mapNotNull { fields ->
                    val kind = vpnKindOf(fields[2]) ?: return@mapNotNull null
                    val device = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }
                    VpnProfile(
                        name = fields[0],
                        uuid = fields[1].takeIf { it.isNotEmpty() },
                        kind = kind,
                        device = device,
                        active = device != null,
                        externallyManaged = false,
                    )
                }
                .toList()

        internal data class DeviceStatusRow(
            val device: String,
            val type: String,
            val state: String,
            val connection: String,
        )

        internal fun parseNmcliDevices(output: String): List<DeviceStatusRow> =
            output.lineSequence()
                .filter { it.isNotBlank() }
                .map { splitTerse(it) }
                .filter { it.size >= 3 }
                .map { DeviceStatusRow(it[0], it[1], it[2], it.getOrNull(3) ?: "") }
                .toList()

        /** `ip -o link show` lines: `2: wlp16s0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 …` */
        internal fun parseIpLink(output: String): List<String> {
            val line = Regex("^(\\d+):\\s+([^:@\\s]+)(?:@[a-z0-9]+)?:\\s+<([^>]*)>")
            return output.lineSequence()
                .mapNotNull { l ->
                    val match = line.find(l.trim()) ?: return@mapNotNull null
                    val name = match.groupValues[2]
                    val flags = match.groupValues[3].split(',')
                    if (flags.any { it == "UP" } && isVpnInterfaceName(name)) name else null
                }
                .toList()
        }

        internal fun parseWgQuickListing(output: String): List<String> =
            output.lineSequence()
                .map { it.trim().substringAfterLast('/') }
                .filter { it.endsWith(".conf") }
                .map { it.removeSuffix(".conf") }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

        /** `IP4.DNS[1]:10.64.0.1` / `IP6.DNS[1]:` lines from a device show. */
        internal fun parseDeviceDns(output: String): List<String> =
            output.lineSequence()
                .map { splitTerse(it) }
                .filter { it.size >= 2 && (it[0].startsWith("IP4.DNS") || it[0].startsWith("IP6.DNS")) }
                .map { it[1] }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
    }
}

/** Runs one external command; stdout on success, null when the tool is missing or it fails. */
fun interface VpnCommandRunner {
    fun run(vararg command: String): String?

    companion object {
        val Default: VpnCommandRunner = VpnCommandRunner { command ->
            runCatching {
                val process = ProcessBuilder(*command)
                    .redirectErrorStream(false)
                    .start()
                val stdout = process.inputStream.readBytes().decodeToString()
                // Reap promptly without blocking discovery on a stubborn exit.
                thread(name = "vpn-cmd-reap", isDaemon = true) { runCatching { process.waitFor() } }
                stdout
            }.getOrNull()
        }
    }
}
