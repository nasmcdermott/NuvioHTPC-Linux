package com.nuvio.app.features.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parser contract against real `nmcli -t` / `ip -o link` output shapes (docs/vpn-bind-design.md §2). */
class VpnDiscoveryParsingTest {

    private val connectionShow = """
        TMOBILE-77A1:d096f49d-30e6-4492-92d0-4587dc517dab:802-11-wireless:wlp16s0
        tailscale0:05b141f4-cff4-4e1b-87a3-9c3c10c2309b:tun:tailscale0
        lo:98ccc0c8-75fe-4388-987b-26b42c10dfeb:loopback:lo
        docker0:31fb1953-4b33-43ae-9b11-cbd1578b4caf:bridge:docker0
        My WireGuard:1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d:wireguard:wg0
        Home OpenVPN:0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0:vpn:
        Office\:Sandbox\:Lab:77889900-aabb-ccdd-eeff-001122334455:vpn:tun0
    """.trimIndent()

    private val deviceStatus = """
        wlp16s0:wifi:connected:TMOBILE-77A1
        tailscale0:tun:connected (externally):tailscale0
        lo:loopback:connected (externally):lo
        docker0:bridge:connected (externally):docker0
        9C\:60\:76\:EC\:2A\:53:bt:disconnected:
        wg0:wireguard:connected:My WireGuard
        tun0:tun:connecting:Office\:Sandbox\:Lab
    """.trimIndent()

    private val ipLink = """
        1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT group default qlen 1000\    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
        2: enp9s0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc fq_codel state DOWN mode DEFAULT group default qlen 1000
        3: wlp16s0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP mode DORMANT group default qlen 1000
        4: docker0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc noqueue state DOWN mode DEFAULT group default
        5: wg0: <POINTOPOINT,NOARP,UP,LOWER_UP> mtu 1420 qdisc noqueue state UNKNOWN mode DEFAULT group default qlen 1000
        6: tailscale0: <POINTOPOINT,UP,LOWER_UP> mtu 1280 qdisc fq_codel state UNKNOWN group default qlen 500
        7: mullvad-tun0000: <POINTOPOINT,UP,LOWER_UP> mtu 1420 qdisc fq_codel state UNKNOWN group default qlen 500
    """.trimIndent()

    @Test
    fun `connections keep only vpn-interesting types with live devices marked active`() {
        val profiles = VpnProfileDiscovery.parseNmcliConnections(connectionShow)
        assertEquals(4, profiles.size)
        val wireguard = profiles.first { it.name == "My WireGuard" }
        assertEquals(VpnProfileKind.WIREGUARD, wireguard.kind)
        assertEquals("wg0", wireguard.device)
        assertTrue(wireguard.active)
        val openvpn = profiles.first { it.name == "Home OpenVPN" }
        assertEquals(VpnProfileKind.VPN_PLUGIN, openvpn.kind)
        assertEquals(null, openvpn.device)
        assertEquals(false, openvpn.active)
    }

    @Test
    fun `escaped colons survive in names`() {
        val profiles = VpnProfileDiscovery.parseNmcliConnections(connectionShow)
        assertEquals("Office:Sandbox:Lab", profiles.last { it.kind == VpnProfileKind.VPN_PLUGIN }.name)
    }

    @Test
    fun `device status rows unescape and keep every field`() {
        val rows = VpnProfileDiscovery.parseNmcliDevices(deviceStatus)
        assertEquals(7, rows.size)
        val bt = rows.first { it.type == "bt" }
        assertEquals("9C:60:76:EC:2A:53", bt.device)
        assertEquals("", bt.connection)
        val office = rows.first { it.connection.startsWith("Office") }
        assertEquals("Office:Sandbox:Lab", office.connection)
    }

    @Test
    fun `ip link yields only up interfaces with vpn names`() {
        val interfaces = VpnProfileDiscovery.parseIpLink(ipLink)
        assertEquals(listOf("wg0", "tailscale0", "mullvad-tun0000"), interfaces)
    }

    @Test
    fun `device dns collects both families and skips empties`() {
        val dns = VpnProfileDiscovery.parseDeviceDns(
            """
            IP4.DNS[1]:10.64.0.1
            IP4.DNS[2]:
            IP6.DNS[1]:fd00\:\:53
            IP4.GATEWAY:10.64.0.1
            """.trimIndent(),
        )
        assertEquals(listOf("10.64.0.1", "fd00::53"), dns)
    }

    @Test
    fun `wg quick listing reduces conf paths to profile names`() {
        val names = VpnProfileDiscovery.parseWgQuickListing("/etc/wireguard/home.conf\n/etc/wireguard/office.conf\n")
        assertEquals(listOf("home", "office"), names)
    }

    @Test
    fun `discover merges nm profiles external flags and wg quick fallback`() {
        val canned = mapOf(
            listOf("nmcli", "-t", "-f", "NAME,UUID,TYPE,DEVICE", "connection", "show") to connectionShow,
            listOf("nmcli", "-t", "--fields", "DEVICE,TYPE,STATE,CONNECTION", "device", "status") to deviceStatus,
            listOf("ip", "-o", "link", "show") to ipLink,
            listOf("sh", "-c", "ls /etc/wireguard/*.conf 2>/dev/null") to "/etc/wireguard/home.conf\n",
        )
        val discovery = VpnProfileDiscovery(VpnCommandRunner { cmd -> canned[cmd.toList()] })
        val result = discovery.discover()

        val profiles = result.profiles
        assertEquals(listOf("home"), profiles.filter { it.kind == VpnProfileKind.WG_QUICK }.map { it.name })
        val tailscale = profiles.first { it.name == "tailscale0" }
        assertTrue(tailscale.externallyManaged)
        assertEquals("tailscale0", tailscale.device)
        assertTrue(tailscale.active)

        val live = result.liveInterfaces.toSet()
        assertEquals(setOf("tailscale0", "wg0", "mullvad-tun0000"), live) // tun0 is only connecting, not live
        val dns = discovery.dnsServers("wg0")
        assertTrue(dns.isEmpty()) // the canned map has no device-show command; dns degrades to empty
    }

    @Test
    fun `discover synthesizes a bindable profile for daemon tunnels no profile claims`() {
        val canned = mapOf(
            listOf("nmcli", "-t", "-f", "NAME,UUID,TYPE,DEVICE", "connection", "show") to connectionShow,
            listOf("nmcli", "-t", "--fields", "DEVICE,TYPE,STATE,CONNECTION", "device", "status") to deviceStatus,
            listOf("ip", "-o", "link", "show") to ipLink,
            listOf("sh", "-c", "ls /etc/wireguard/*.conf 2>/dev/null") to "",
        )
        val result = VpnProfileDiscovery(VpnCommandRunner { cmd -> canned[cmd.toList()] }).discover()

        // Mullvad's daemon tunnel has no NetworkManager profile: it still gets a row, bindable
        // but not activatable (no uuid), while claimed devices (tailscale0, wg0) are not duplicated.
        val mullvad = result.profiles.filter { it.device == "mullvad-tun0000" }
        assertEquals(1, mullvad.size)
        assertEquals(VpnProfileKind.EXTERNAL_TUN, mullvad.first().kind)
        assertTrue(mullvad.first().active)
        assertTrue(mullvad.first().externallyManaged)
        assertEquals(null, mullvad.first().uuid)
        assertEquals(1, result.profiles.count { it.device == "tailscale0" })
    }
}
