# VPN bind — design

Implements the user requirement: a gamepad/keyboard-navigable VPN settings screen that lists VPN
profiles already present on the system (any distro — Debian, Ubuntu, Fedora, Arch, mutable and
immutable) and adds a torrent-client-style interface bind so app traffic has no leak path.

Grounded in `docs/vpn-bind-research.md` (fork mechanism, Linux primitives, codebase hook points).
Testing branch: `feat/vpn-bind-test` (base `linux-tv-sports` @ `cad31cf9e`).

## 1. What "0% chance of leaking" means here, exactly

The forks' own invariant, imported unchanged: **a selected mode is intent, never proof; enabled
protection fails closed** — when the bound tunnel is not verified live, connections BLOCK rather
than silently fall back to DIRECT (`StreamRoutePolicy.decide` in homelessbrian/NuvioPlusIPTV,
`vpn-core/.../ProtectionMode.java`). "0%" is therefore defined per component:

| Traffic class | Origin | Guarantee mechanism | Leak path left open |
| --- | --- | --- | --- |
| Playback streams (mpv: HTTP/HLS/DASH/HTTPS) | FFmpeg sockets | All playback goes through the token-authenticated loopback relay (the proven `IptvProxyRelay` shape); the relay's **upstream** sockets are bound to the VPN interface with `SO_BINDTODEVICE`; relay fails closed | none for relayed hosts; loopback targets stay direct by policy |
| API / scraper / provider HTTP(S) | Ktor (CIO) clients, e.g. `createApiHttpClient`, TMDB/Stremio/debrid | Same loopback relay used as an **HTTP proxy** for Ktor (CIO supports HTTP proxies, not SOCKS); upstream sockets bound as above | none for proxied hosts |
| OkHttp traffic | `IptvProxiedClients`, relay internals | Direct: custom `SocketFactory` returning device-bound sockets | none |
| P2P / torrent engine | libtorrent in `nuvio-engine` | `settings_pack`: `outgoing_interfaces` = VPN interface name, `proxy_*` unset, plus listen-side pinning — the exact mechanism qBittorrent's "bind to interface" uses; engine config via the existing `nuvio-engine` patch mechanism | none; if the interface disappears, libtorrent errors out (fail closed) |
| DNS | glibc resolver inside the app | Resolution happens in the relay/engine, not via unbound system resolver: relay resolves CONNECT hostnames over a UDP socket **bound to the VPN interface**, targeting the VPN profile's DNS (discovered from NetworkManager); libtorrent uses `proxy_hostnames`-equivalent via engine config | none for protected classes; direct-bound sockets that receive raw IPs are the user's explicit choice |
| Everything else (update checks, Discord RPC, …) | misc clients | Each client class is opted in at its creation choke point; un-enumerated one-off sockets are NOT covered in v1 | documented, listed in §6 |

Per-socket `SO_BINDTODEVICE` semantics are the actual 0%-claim: once bound, the kernel only routes
that socket over the named device — if the device goes away, sends error out, they never re-route
over the default gateway (man 7 socket; unprivileged binding of *unbound* sockets is allowed since
kernel 5.7, torvalds/linux `c427bfec18f2`; see research doc §2.1 for the commit and capability
rules). This is unprivileged, needs no root, no packages, and works identically on mutable and
immutable distros because it only depends on an existing active VPN interface.

**What this does not claim:** protection for processes outside the app, or for app code paths
added later without going through the choke points. That is the fork's own honesty
("foreground activation, not a promise of protection"). For a system-wide guarantee, §7 documents
the namespace wrapper as the packaging-level follow-up.

## 2. Profile discovery — distro-agnostic, zero new dependencies

NetworkManager is the common denominator on Debian, Ubuntu, Fedora, Arch and the immutable
variants (Bazzite ships NM; it is the standard on all of them). Discovery is **read-only, CLI via
`nmcli -t`** (stable terse format, no D-Bus library dependency, works under any desktop):

1. `nmcli -t -f NAME,UUID,TYPE,DEVICE connection show` → all profiles; VPN-interesting types:
   `wireguard`, `vpn` (OpenVPN/StrongSwan/L2TP plugins…), and externally-managed `tun` devices
   (tailscale etc., shown as `connected (externally)`).
2. `nmcli -t --fields DEVICE,TYPE,STATE,CONNECTION device status` + `ip -o link` → which VPN
   interface is currently **up** (the bind target, e.g. `wg0`, `tun0`, `tailscale0`).
3. `nmcli -t -f IP4.DNS device show <iface>` → the interface's DNS servers (feeds §1 DNS row).
4. Fallback for non-NM setups: `wg-quick` configs under `/etc/wireguard/*.conf` (listed by
   filename only — files are root-owned 0600; a profile there shows as *detected, not activatable
   from the app*), and `ip -o link` `tun*`/`wg*` interfaces that no NM connection owns.

The app never opens a socket to enumerate. `nmcli` failures (missing NM, Docker containers) are a
degraded mode: interface-level discovery from `ip -o link` still lets the user bind to a live VPN
interface even without profile metadata.

Activation: v1 offers `nmcli connection up <uuid>` as an explicit button (NM's polkit agent
handles authorization — same flow as the desktop's own VPN toggle, no app-side root). Binding
itself only ever needs the interface to exist and be up.

## 3. Bind engine — one native helper, one relay, three consumers

New Linux-only module, isolated from shared code per repo rules:

```
composeApp/src/desktopMain/native/nuvio-bind/        (new CMake target: libnuvio-bind)
  nvb.c   — nvb_ifindex(name) → int
            nvb_bind_fd(fd, ifindex) → SO_BINDTODEVICE or error
            nvb_dns_a(hostname, ifindex, dns_ip) → A record over UDP bound to the device
```

Consumers:

- **`BindRelay`** (generalizes `IptvProxyRelay`): loopback-only, token-authenticated HTTP proxy
  (GET + CONNECT). Every accepted upstream connection is a `Socket` created through the native
  helper: `socket` → `SO_BINDTODEVICE(ifindex)` → connect. If the helper fails (no ifindex, bind
  error, connect refused by kernel after the device drops), the relay closes the client
  connection — **fail closed, never a direct retry**. mpv gets the relay URL via the existing
  `mpvProxyUrl()`-style path; Ktor clients get it as their HTTP proxy.
- **OkHttp and Ktor ride the relay**: both are pointed at the loopback relay (OkHttp via
  `proxy` + `proxyAuthenticator` — it does not derive Proxy-Auth from proxy-URL userinfo; Ktor CIO
  via its HTTP-proxy config), so they inherit the kernel-level bind with no per-stack work.
  A direct `BoundSocketFactory` stays optional for future enumerated clients that must bypass the
  relay.
  JVM fd note (as-built): the JVM creates the OS file descriptor lazily and JDK ≥ 25 delegates
  through `SocksSocketImpl` → `DelegatingSocketImpl` → `SocketImpl`, so `VpnBindNative` sets
  `SO_REUSEADDR` to force fd creation and walks the `delegate` chain before binding; the shipped
  JBR is fixed and the startup probe turns any layout change into "bind unavailable".
- **Engine** (`NuvioEngineConfig` new fields, applied in the `nuvio-engine` patch):
  `outgoing_interfaces = <iface>`, and when the relay is enabled also the proxy settings, so
  P2P both binds directly *and* is covered if the interface set changes.

State model (mirrors the fork's ProtectionMode vocabulary, desktop scope names):

- `OFF` — nothing bound (default; zero behavior change upstream-merge risk).
- `STREAMS` — playback + API/scraper HTTP(S) through the relay.
- `STREAMS_AND_P2P` — adds the libtorrent interface bind.
- `FULL_APP` — everything above plus remaining enumerated clients; v1 covers the enumerated set
  and the UI states the residual honestly (§1 last row).

`BindPolicy` (port of `StreamRoutePolicy.decide`, unchanged invariants): loopback/`127.x`/`::1`/
`.local` → DIRECT always (local playback proxies must stay local); verified live interface →
BIND; enabled-but-unverified or interface-gone → BLOCK; malformed target → BLOCK.

Interface liveness: a 2s poll of `ip -o link` (or `getifaddrs`) flips the verified state; on
transition to down, policy flips to BLOCK, the relay drops in-flight upstream sockets (they are
already kernel-erroring), and the UI shows the fail-closed banner. No silent rebind to another
interface without an explicit user action.

## 4. Settings UI — gamepad/keyboard first

New `features/vpn/` screen following the existing desktop settings/focus patterns
(`FocusedControl.desktop.kt`, gamepad bindings repo). Structure:

- Section "VPN protection": mode selector as a focusable row group (left/right cycles, A/Enter
  commits — the repo's established key contract style).
- Section "Profiles": focusable list of discovered profiles — name, type, state chip
  (`active (wg0)` / `available` / `external (tun0)`). Enter on an inactive profile offers
  "Activate via NetworkManager" (the `nmcli connection up` button, §2). Enter on the active one
  selects it as the bind target.
- Section "Bind target": shows the interface + DNS the bind will use, with a live "verified"
  indicator; a "Test route" row does a probe HEAD through the relay and prints the observed exit
  (echo service over HTTPS is deliberately avoided; probe is metadata-only, e.g. an HTTPS request
  to the VPN's DNS-over-TLS-unreachable-by-design… final probe URL chosen at implementation).
- Every control reachable by D-pad only; no pointer-only affordances; Escape/B returns.

## 5. Stages

1. **Discovery + policy** ✅ — `VpnProfile`/`VpnBindTarget` models, `VpnProfileDiscovery`
   (nmcli/ip parsers as pure functions over captured output — unit-tested without nmcli),
   `BindPolicy`, tests.
2. **Native bind + relay** ✅ — `libnuvio-bind` (as `nuvio_bind_jni.c` in the player_bridge target:
   ifIndex / bindSocket / bindDatagramChannel / probe / describeFd), `BindRelay`,
   `BoundDnsResolver`, fail-closed tests (a nonexistent interface refuses with 502 and **zero**
   origin requests; JDK ≥ 25's SocksSocketImpl/DelegatingSocketImpl fd chase + lazy-fd forcing
   via `SO_REUSEADDR` verified on the live machine).
3. **Choke-point wiring** ✅ (in-repo part) — `IptvProxiedClients` (plain-traffic branch rides the
   relay; the user's own IPTV proxy keeps precedence and the combination is a UI warning),
   `iptvMpvOptions` (bind relay takes precedence over the IPTV relay),
   `NativePlayerController` (generic remote sources get `vpnBindMpvProxyOption`; loopback engine
   stream server stays direct), `ApiHttpClient` (Ktor CIO proxy + request-level
   `Proxy-Authorization` via a `Send` plugin — CIO never derives credentials from userinfo).
   Controller contract: a mode that is on always runs the relay (fail-closed even when unverified
   or native-unavailable); `verified` stays a policy/UI concern.
4. **UI** ✅ — `SettingsPage.VpnBind` (fork:vpn-bind, gated by `AppFeaturePolicy.vpnBindEnabled` =
   Linux desktop), `VpnBindSettingsController` (discovery, mode changes, `nmcli connection up`
   activation with polkit), the gamepad-navigable page (existing focusable settings rows only —
   switch rows for the four modes, action rows with glass buttons for refresh/bind/activate), the
   Live-TV-proxy combination warning, and the search-index registration the settings search
   requires (`SettingsSearchIndexBuildTest` enforces page registrations).
5. **Docs/packaging notes** — kernel ≥ 5.7 note, NM presence note (all target distros ship it),
   AppImage/Flatpak/RPM/DEB notes (Flatpak: `nmcli` needs `--talk-permission`/sandbox consideration
   — defer to packaging stage).

**Deferred to the engine-config stage (needs the `nuvio-engine` checkout):** the libtorrent
`outgoing_interfaces`/proxy plumbing. `NuvioEngineConfig` fields alone are inert — `nativeCreate`
has a fixed JNI signature against the external `nuvio-engine` DLL whose source checkout is not
present in this environment, so the Kotlin signature cannot change without a matched native
change, and neither side can be built or tested here. Per repo rules this cross-repo divergence
(one config struct + one settings-pack line in a `nuvio-engine` patch) is discussed before it
lands. Until then `STREAMS_AND_P2P`/`FULL_APP` cover the same set as `STREAMS`.

## 6. Residual risks (honest list)

- Ktor CIO as HTTP-proxy client is verified by docs and by the research, but the relay's
  CONNECT path for HTTPS needs a live test on the target FFmpeg/JBR build (research doc §2.4
  flagged the manual/source discrepancy).
- App-external processes (e.g. a TorrServer daemon if used) are out of scope for the in-app bind;
  §7's namespace wrapper is the answer there.
- `FULL_APP` coverage is only as complete as the client enumeration; the UI must not imply more.
- Kernel < 5.7 (none of the target distros ship one) would need root for `SO_BINDTODEVICE`;
  detected at startup and surfaced as "bind unavailable" rather than silently disabled.

## 7. Namespace wrapper — documented follow-up for system-guarantee parity

For a system-level guarantee (also covers external daemons and DNS wholesale): a
`systemd-run --property=PrivateNetwork=true --property=JoinsNamespaceOf=…` launcher, or a
rootless `pasta` invocation, that starts the app inside the VPN's namespace. Zero code in the app
— it is packaging/ops and belongs in the packaging stage, not in the app.
