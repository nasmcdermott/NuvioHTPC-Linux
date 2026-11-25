package com.nuvio.app.features.vpn

/** How the VPN bind feature sees a profile that already exists on the system. */
enum class VpnProfileKind {
    /** A NetworkManager `wireguard` connection. */
    WIREGUARD,

    /** A NetworkManager `vpn` connection (OpenVPN, StrongSwan, L2TP, …). */
    VPN_PLUGIN,

    /** An externally managed tun device (tailscale, nebula, …). */
    EXTERNAL_TUN,

    /** A `wg-quick` config under `/etc/wireguard` (detected by filename only; root-owned). */
    WG_QUICK,

    UNKNOWN,
}

/**
 * A VPN profile discovered on the system. [active] means NetworkManager (or the device list)
 * reports a live device for it — that device is the only thing the bind can target.
 */
data class VpnProfile(
    val name: String,
    val uuid: String?,
    val kind: VpnProfileKind,
    /** Interface the profile is currently up on, e.g. `wg0`; null while inactive. */
    val device: String?,
    val active: Boolean,
    /** Managed by an external daemon, not activatable from inside the app. */
    val externallyManaged: Boolean,
)

/** The live interface a bind targets, with the DNS the interface hands out. */
data class VpnBindTarget(
    val interfaceName: String,
    val dnsServers: List<String> = emptyList(),
    /** Tests bind a canned DNS responder on an unprivileged port; production always uses 53. */
    val dnsPort: Int = 53,
)

/** What discovery produced. `interfaces` is the set of live VPN interfaces bindable right now. */
data class VpnDiscoveryResult(
    val profiles: List<VpnProfile>,
    val liveInterfaces: List<String>,
)
