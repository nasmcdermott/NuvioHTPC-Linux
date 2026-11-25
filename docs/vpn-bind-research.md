# VPN bind — research findings

Research for a fork-desktop "VPN bind" feature: what the Android TV forks actually built (read
against their source), which Linux primitives can deliver the equivalent in a Compose Desktop app,
and where each would hook into *this* codebase. Everything is from primary sources — the forks'
GitHub source, man pages, kernel source and commits, first-party docs of WireGuard/Mullvad/mpv/
FFmpeg/libtorrent/Ktor/sing-box/systemd. Nothing here is a green light to copy code: per repo rules
the value of other projects is as references for mechanisms, not as patch material.

Two headline facts up front:

- The Android TV feature is **not** a socket-level "bind to VPN interface". It is a **built-in VPN
  engine inside the app** (userspace WireGuard/OpenVPN), applied to playback through a **local
  authenticated proxy** or (in "Full app" mode) Android's VpnService. No fork binds sockets to a
  tun device; there is no root anywhere in it.
- On Linux, the same shape is achievable **without any privileges** by reusing mechanics this repo
  already has (`IptvProxyRelay`) plus two small native config additions (Ktor proxy, libtorrent
  proxy/interface options in the `nuvio-engine` config).

## 1. What "VPN bind" is in the Android TV forks

### 1.1 Which forks have it

| Fork | VPN feature | Source of truth |
| --- | --- | --- |
| **homelessbrian/NuvioPlusIPTV** (Google TV / Android TV / Fire TV + phone companion) | Full built-in VPN: WireGuard + OpenVPN 3/OpenVPN 2, four protection modes, per-profile scope, "Settings → VPN profiles" UI. README credits ghbarker: "who built the VPN and contributed it to Nuvio + IPTV" | `vpn-core/`, `vpn-android/` (own Gradle modules), `app/.../core/vpn/PlaybackVpn.kt`, `app/.../settings/VpnSettingsContent.kt` |
| **zoga2o3-ops/Nuvio-One-M** (phone/tablet fork of NuvioMobile) | Same `vpn-android` module design, **WireGuard-only** (no OpenVPN engine), same four modes, QR "send to TV" profile import | `vpn-android/README.md`, `composeApp/src/commonMain/.../features/vpn/VpnHost.kt` |
| ysosrs123/NuvioTV-Fork (Nuvio Arc) | **None** — its many `*Tunnel*` files are USB/audio-route tunneling and AV-sync policy, not VPN; the full tree contains no `vpn`/`wireguard`/`socks` paths | full tree listing (no VPN paths) |
| Upstream NuvioMedia/NuvioTV (`dev`) and UmbraProjects/NuvioDesktop (`windows-tv-adaptive`) | **None** — no `vpn`/`wireguard`/`socks` paths in either tree | full tree listings |

So the feature is fork-native (like the Drive backup), which means there is no upstream VPN UI to
stay compatible with — only the fork's own UX vocabulary matters.

### 1.2 The four protection modes (`ProtectionMode.java`, `vpn-android/README.md`)

| Mode | What it protects | Mechanism |
| --- | --- | --- |
| `OFF` | Nothing; ordinary connection | — |
| `HTTP_ONLY` ("HTTP streams only") | Remote **HTTP** media; HTTPS stays direct | Local playback proxy |
| `HTTP_HTTPS` ("HTTP + HTTPS streams") | Both protocols; "HTTPS keeps its original TLS session" | Local playback proxy |
| `APP_WIDE` ("Full app") | Everything the app does, **and DNS** | Android `VpnService` + wireguard-go |

Two design constants worth importing into the desktop thinking:

- **A selected mode is only intent, never proof.** `ProtectionMode`'s javadoc: "Saved intent;
  selecting a mode alone never proves a tunnel is connected." `StreamRoutePolicy`'s: "A decision
  only: it does not establish or verify a tunnel."
- **Fail closed, never silently fall back.** `StreamRoutePolicy.decide` returns `TUNNEL` only when
  the tunnel is verified ready, otherwise `BLOCK`: "Protection is opt-in; when enabled, HTTP never
  falls back to DIRECT." Loopback hosts are always `DIRECT` ("local playback proxies must stay
  local; do not resolve names to decide bypasses") and malformed URIs are `BLOCK`.

### 1.3 The two engines

**HTTP-scoped modes — an in-process userspace VPN exposed as a local playback proxy.**
`HttpStreamTunnel` loads a native Go library (`nuvio-http-vpn`) that runs a userspace WireGuard IP
stack — the pinned `gvisor` netstack (`golang.zx2c4.com/wireguard` + `gvisor.dev/gvisor`, shipped
as a vendored `native/internal/netstack` with a README listing "the changes needed by the playback
tunnel") — and also hosts OpenVPN 3 (C++ `session.cpp`, pinned mbedTLS/Asio/LZ4) or an OpenVPN
2.7.9 compatibility subprocess for `fragment` profiles. None of this touches Android's VPN
machinery: the stack dials real sockets **through the userspace tunnel** (`tunnel_dial.go` bounds
setup and races address families so "neither failure nor cancellation can select the system
network"). The proxy listens on loopback with a per-run random token; the Go side re-checks the
tunnel session on every request (`preparePlayback` → `VpnPlaybackProtection.beforeRequest`).

The app routes playback through it at every client seam, not just one:

- OkHttp players: `VpnHttpClient.wrap(client)`; the media3 datasource factory is swapped for
  `OkHttpDataSource.Factory(VpnHttpClient.wrap(...))` (`PlaybackVpn.kt: protectHttpFactory`).
- Legacy `HttpURLConnection` callers get a compatibility shim (`PlaybackHttpURLConnection`) backed
  by the same OkHttp client.
- The **native player** is handed the proxy explicitly: `awaitNativeVpnProxy()` returns
  `VpnPlaybackProtection.NativeProxy` (a local proxy URL) for the native player to use.

Readiness is verified, not assumed: the connection "must authenticate and pass the tunneled HTTPS
probe before playback is ready" (`tunnel_probe.go`, `vpn-android/README.md`), and the settings UI
shows Connected only then.

**APP_WIDE — Android VpnService, scoped to the app.** `AppWideWireGuardEngine` uses wireguard-go's
`GoBackend` with a tun named `"NuvioVPN"`; the README: "Full app uses Android VPN consent and
restricts the VPN interface to the host package" (VpnService's per-app routing;
`VpnService.prepare()` is the user-consent gate — foreground activation only, "not Android
always-on VPN"). One desktop-relevant wrinkle: full-app mode cuts the app off from its home
network, so `VpnHomeBypass` lets the user whitelist a NAS address, recomputes the tun routes as
"everything, minus the home addresses that can safely skip it", and tells the user to use
"HTTP + HTTPS streams" mode when the bypass clashes with the VPN's own addresses.

### 1.4 What is covered, and the UX

Covered traffic classes, from the README and `StreamRoutePolicy` ("evaluate every network request,
including redirects and playlist child requests"): playback media requests, redirects, byte
ranges, subtitles and preloads. `APP_WIDE` adds "non-HTTP sockets and DNS". The torrents/debrid
story is implicit: NuvioPlusIPTV is IPTV-centric; torrent-engine sockets are covered only under
`APP_WIDE` (all app sockets) — there is no torrent-engine-specific bind. The **profile scoping**
is a notable UX idea: "Apply VPN settings to" defaults to all profiles with a per-Nuvio-profile
override, and Setup Sync restores both.

UX summary (`VpnSettingsContent.kt`): Settings → VPN profiles page; mode picker dialog
(Off / HTTP streams only / HTTP + HTTPS streams / Full app) with per-mode subtitles; profile
import/preflight (WireGuard `.conf` or self-contained OpenVPN TUN profile, provider ZIP inlining
of referenced CA/cert/key files with hard size limits, `auth-user-pass` credential prompt);
connect/state display gated on the probe; Keystore-encrypted profile storage; explicit connect
via user-visible activation only.

## 2. Linux desktop implementation options

### 2.1 Per-socket `SO_BINDTODEVICE` (bind to the VPN interface)

`SO_BINDTODEVICE` takes an interface name and restricts the socket to that device; per
`socket(7)` inbound packets are only processed from it. The privilege story changed twice, and
both matter here:

- Since **kernel 5.7** (commit `c427bfec18f2`, "net: core: enable SO_BINDTODEVICE for non-root
  users"), an **unprivileged** process may bind a socket to an interface *if the socket is not
  already bound*; re-binding requires `CAP_NET_RAW` (`net/core/sock.c`,
  `sock_bindtoindex_locked`). The commit was motivated exactly by our use case: "allowing
  SO_BINDTODEVICE would allow TCP clients to get the same privilege" as the Wine-motivated
  `IP_UNICAST_IF`/`IPV6_UNICAST_IF`.
- Binding is fail-closed: if the VPN interface is down or unrouted, connections fail instead of
  leaking through another interface — effectively a per-socket kill switch for the sockets it
  covers.

**Limits.** (a) It requires an existing VPN interface — creating one (`/dev/net/tun` +
`TUNSETIFF`) needs `CAP_NET_ADMIN` (kernel `Documentation/networking/tuntap.txt`), so the app can
only bind to a WireGuard/OpenVPN/sing-box TUN that the **user's system VPN** already created
(`wg0`, `tun0`…). (b) Coverage is only sockets the app explicitly sets it on, and the JVM/ktor
stacks do not expose per-socket options from Kotlin — every stack needs its own hook (see §3).
(c) DNS: name resolution happens on unbound sockets unless resolution is pushed through the VPN
too.

### 2.2 Policy routing: `SO_MARK` + `ip rule`

`ip rule` selects routes per mark (`fwmark` selector, `ip-rule(8)`); `SO_MARK` "requires the
CAP_NET_ADMIN or CAP_NET_RAW (since Linux 5.17) capability" (`socket(7)`). This is the mechanism
`wg-quick` itself uses (fwmark on the UDP socket + rule with `suppress_prefixlength 0`,
`wg-quick(8)`), and `wg-quick`'s man page shows the pattern for policy-routing selected traffic
into the tunnel table (`PostUp = ip rule add ipproto tcp dport 22 table 1234`). For this app it
is a poor fit: the rules are system state (root to install), the app still needs `CAP_NET_RAW`
for `SO_MARK` (file caps / `AmbientCapabilities` — packaging complications), and, like §2.1, it
only affects sockets the app can set options on. Not recommended beyond documentation.

### 2.3 Network namespaces (`ip-netns`, systemd)

The strongest isolation: put the process in a namespace whose only route is the VPN — DNS
included. `ip-netns(8)` defines named namespaces; `systemd.exec(5)` `PrivateNetwork=` "sets up a
new network namespace … and configures only the loopback network device lo inside it", with
`JoinsNamespaceOf=` to share. Two variants:

- **Whole app**: launch the desktop app inside the VPN's namespace (a `.desktop` wrapper, or a
  systemd user unit with `NetworkNamespacePath=`). Zero per-socket code, everything covered —
  but it is a system-integration feature of the user's setup, not the app. Caveats worth
  documenting: all LAN features (DLNA cast, IPTV subtitle server, Discord RPC) go through the
  tunnel too — exactly the problem `VpnHomeBypass` solves on Android — and `PrivateNetwork=`
  "disconnect[s] all socket families from the host, including … AF_UNIX" abstract sockets
  (`systemd.exec(5)`), which must be verified against portals/Wayland plumbing.
- **Just the torrent engine**: namespaces are per-*process*, so the in-process Nuvio Engine
  (libtorrent behind JNI) cannot be isolated alone. But the external **TorrServer** backend is an
  external binary the app starts with `ProcessBuilder` (`TorrServerP2pBackend`), so it can be
  spawned via `ip netns exec` / `systemd-run --private-network` when the user has set that up —
  Linux-only, no shared-code change.
- **Rootless**: `unshare -Urn` + `pasta`/`slirp4netns` can give a user namespace network without
  root (`passt` — "Plug A Simple Socket Transport"), but the routing decision then depends on the
  pasta/slirp process's own configuration; complexity is high for marginal gain over §2.4.

### 2.4 Local proxy binding (SOCKS5/HTTP) — the closest Linux analogue of the fork's design

VPN clients and generic engines expose proxies the app can point at:

- **Mullvad**: "The SOCKS5 proxy is listening on 10.64.0.1 port 1080. The proxy is only accessible
  when you are connected to Mullvad VPN" — the connection is established *inside* the encrypted
  tunnel (Mullvad help, SOCKS5 proxy). Requires the WireGuard tunnel up; useful both as an
  in-tunnel fixed-IP exit and as an explicit choke point the app can enforce.
- **sing-box / mihomo (clash)**: a `mixed` inbound is "a socks4, socks4a, socks5 and http server"
  (sing-box inbound docs), so one local port serves both proxy types; sing-box can additionally
  run its own TUN (§2.5).

App-side routing, protocol by protocol (all verified against first-party docs):

- **mpv / libmpv**: `--http-proxy` — "URL of the HTTP/HTTPS proxy … This option is silently
  ignored if it does not start with http://" (mpv manual). No SOCKS: FFmpeg's http protocol only
  accepts `http://` proxy URLs (`libavformat/http.c`: `av_strstart(proxy_path, "http://", NULL)`)
  and tunnels HTTPS through it via CONNECT. The repo already depends on that path:
  `IptvProxyRelay.mpvProxyUrl()` returns `http://nuvio:<token>@127.0.0.1:<port>` for mpv's
  `http-proxy`, and its doc comment records the two constraints: "mpv (FFmpeg) only speaks HTTP
  proxies" and "mpv has no SOCKS support". (The mpv manual's older line "Proxies are not used for
  https URLs" does not match current FFmpeg behavior, which does CONNECT-tunnel HTTPS; note it
  when testing, and note mpv master also gained a libcurl network backend honoring
  `--http-proxy` via `--curl-enabled`.)
- **Ktor**: HTTP proxies are supported by every engine; **SOCKS is not supported by CIO** (Ktor
  docs table: CIO HTTP ✅ / SOCKS ✖️; OkHttp, Android, Darwin, Curl support SOCKS). The repo's
  desktop API clients are CIO (`ApiHttpClient.desktop.kt`), so a SOCKS-only VPN proxy cannot be
  applied directly — an HTTP-format proxy (sing-box `mixed`) or a local relay is needed.
- **OkHttp**: native SOCKS + HTTP proxy support (`Proxy.Type.SOCKS`), already used by
  `IptvProxiedClients` and the CONNECT-tunneling `IptvProxyRelay` (`openUpstream`).
- **libtorrent** (torrent engine): full proxy support in `settings_pack` — `proxy_type`
  (none/socks4/socks5/http…), `proxy_hostname/username/password`, `proxy_peer_connections`
  ("peer connections are made (and accepted) over the configured proxy"), `proxy_tracker_connections`,
  and `proxy_hostnames` (DNS via proxy). With a proxy configured, "the listen_interfaces setting
  is overridden and only a single interface is created, just to contact the proxy … All traffic
  is supposed to be channeled through the proxy" (`settings_pack.hpp`). Alternatively
  `outgoing_interfaces`/`listen_interfaces` accept a device name and bind outgoing/listen sockets
  to it — the literal "VPN bind" for the P2P class, assuming the VPN interface exists.
- **JDK `HttpURLConnection`** (Drive backup): honors `http.proxyHost` system properties or an
  explicit `Proxy` argument; no SOCKS-gap issue since JDK supports SOCKS natively.

**Limits.** Proxy binding is opt-in per component: anything not pointed at the proxy is unprotected.
It is a routing discipline, not a guarantee — pair with §2.1/§2.3 for hard enforcement, and copy
the fork's fail-closed rule (`StreamRoutePolicy`: protection on ⇒ `TUNNEL` or `BLOCK`, never
`DIRECT` fallback) at the component level.

### 2.5 System-level TUN router (sing-box/mihomo TUN mode)

The "seamless" option that needs nothing from the app: the user runs sing-box (or mihomo) with a
TUN inbound — root or a setcap helper, entirely outside the app — and all app traffic goes through
the VPN. sing-box route rules can even match **per process** (`process_name`, `user` in the rule
fields, sing-box route-rule docs) and its `mixed` inbound's `set_system_proxy` can auto-configure
the desktop proxy. The app's job is documentation (a recipe) plus optional in-app proxy config so
per-class modes (§1.2 vocabulary) still work on top. Coverage: everything, including DNS. From
the app's perspective it is zero code — the least divergent possible option.

### 2.6 Windows/macOS equivalents (for upstream `windows-tv-adaptive` compatibility)

- **Windows**: no `SO_BINDTODEVICE`, but `IP_UNICAST_IF` sets "the outgoing interface for sending
  IPv4 traffic … interface index" per socket (`IPPROTO_IP` socket options, learn.microsoft.com).
  WireGuard Windows implements its tunnel firewall/kill-switch via **WFP** — Windows Filtering
  Platform sessions (`tunnel/firewall/blocker.go`: `fwpmEngineOpen0`, "WireGuard dynamic
  session") — and per-app split tunneling on Windows is done with WFP filters. An upstream
  equivalent would follow that model, not sockets.
- **macOS**: the `IP_BOUND_IF` socket option binds a socket to an interface index (`IP_BOUND_IF 25
  — "set/get bound interface"`, XNU `bsd/netinet/in.h`); system-wide protection would use PF or a
  NetworkExtension provider.
- Upstream NuvioDesktop has no VPN feature at all (§1.1), so a Linux implementation behind an
  expect/actual seam (as Nuvio-One-M does with commonMain `VpnHost` plus android/ios actuals and a
  no-op default) merges cleanly whether or not other platforms ever fill it in.

## 3. Fit to this codebase

### 3.1 Where network traffic originates

| Traffic class | Component(s) | Current proxy/bind surface |
| --- | --- | --- |
| Scrapers, add-ons, debrid account/API, Trakt/Simkl, servers | Ktor clients; ~11 call sites of `createApiHttpClient` (expect/actual), desktop actual = **CIO** (`ApiHttpClient.desktop.kt`) | none; expect/actual is a single choke point |
| Games metadata | Ktor CIO clients (`IgdbClient`, `SteamArtworkClient`, `SteamGridDbClient`, `SteamMetadataClient`) | none |
| YouTube/InnerTube fallback | Ktor **OkHttp** engine (`InnerTubeXFallback`) | SOCKS-capable engine |
| IPTV playlist/EPG/playback clients | OkHttp (`IptvProxiedClients`) | **already proxy-capable** (SOCKS + HTTP, per-run credentials) |
| IPTV mpv playback | mpv via `http-proxy=` ← `IptvProxyRelay.mpvProxyUrl()` | **already built**: local token-authenticated HTTP relay (GET + CONNECT) → OkHttp → user's SOCKS/HTTP proxy |
| Other mpv playback (debrid https, YouTube, music, trailers) | mpv/libmpv; per-source options in `IptvMpvOptions`/player runtime; `stream-lavf-o` set in the native bridge | `http-proxy=` option available (no SOCKS in ffmpeg) |
| Torrent engine (P2P) | in-process **Nuvio Engine** (libtorrent via JNI; separate `NuvioMedia/nuvio-engine` checkout built with `patches/0001…0005`) **or** external TorrServer daemon (`ProcessBuilder`) | none: `NuvioEngineConfig` has no proxy/interface fields; TorrServer binary is not configurable this way |
| Torrent playback itself | mpv ← engine's **loopback HTTP range endpoint** ("the engine serves the selected file over a loopback HTTP range endpoint; the URL it returns is handed to mpv unchanged", `NuvioEngineP2pBackend`) | local, must stay DIRECT (fork's loopback rule) |
| Artwork/images | Ktor/Skia loaders | low privacy value; classify out |
| Non-private local/LAN | DLNA cast (SSDP), lights, Discord RPC, party sync, Drive backup (`HttpURLConnection`), updater | classify out (except Drive: JDK proxy is easy) |

Two existing assets de-risk this considerably: the repo already runs **an authenticated local
loopback proxy in front of mpv** (`IptvProxyRelay`) with per-run random credentials and
CONNECT-tunneling — the exact shape of NuvioPlusIPTV's playback proxy, minus the in-app VPN
engine — and already has **an OkHttp proxy client factory** (`IptvProxiedClients`) covering both
SOCKS and HTTP with credentials.

### 3.2 What each option would hook

- **Proxy mode (§2.4)**: (1) mpv playback — reuse/extend `IptvProxyRelay` from an IPTV-specific
  object into a shared desktop relay, set `http-proxy=` for playback source URLs when protection
  is on; (2) Ktor API/scraper clients — install `ProxyBuilder.http(...)` inside the desktop
  actuals at client creation (CIO supports HTTP proxy; SOCKS via `mixed` inbound or relay);
  (3) P2P — add proxy + optional `outgoing_interfaces` fields to `NuvioEngineConfig` and thread
  them through the engine's C API (this touches the *external* `nuvio-engine` checkout — a new
  patch there, consistent with the existing patch mechanism, or an upstream discussion with
  NuvioMedia; flag this divergence before doing it). Fail-closed gate per `StreamRoutePolicy`
  semantics: when protection is on and the tunnel/proxy is not verified up, block rather than
  fall back.
- **`SO_BINDTODEVICE` hardening (§2.1)**: a small Linux-only native helper binding the non-proxy
  sockets it can reach (or, more realistically, applied only to the libtorrent side via
  `outgoing_interfaces`, which is already a device-name setting). JVM stacks keep it off the
  table without JNI work everywhere.
- **Namespace wrapper (§2.3)**: zero shared-code change — a wrapper script/systemd unit around
  the app (or `ip netns exec` for the TorrServer spawn in `TorrServerP2pBackend`); document it
  for users who run their VPN as a service.
- **Upstream-merge shape**: all new files (relay generalization, a `VpnProtection` expect/actual
  seam mirroring Nuvio-One-M's `VpnHost` pattern, settings keys), `expect/actual` declarations in
  commonMain only where upstream already has the pattern, and no renames of shared code. The only
  cross-repo change is the engine config field (§3.2 first bullet).

### 3.3 Honest caveats

- Proxy-based protection **without** a system-level VPN is not a guarantee: it protects the
  components that opt in. State it in the UI (the fork's own README does: "This is foreground
  activation, not … a promise of protection while the app process is stopped").
- DNS is the classic leak: route resolution through the proxy/relay (SOCKS with remote
  resolution, libtorrent `proxy_hostnames`) or the namespace approach; never trust a mode toggle
  alone (fork rule: intent ≠ tunnel).
- `SO_BINDTODEVICE` for unprivileged sockets needs **Linux ≥ 5.7** (§2.1) — fine for Bazzite, but
  document it.
- mpv proxy behavior should be validated live on the target FFmpeg build (manual/source
  discrepancy in §2.4), especially for HTTPS-through-CONNECT and the new curl backend.

## 4. Recommendation — viable architectures compared

| # | Architecture | Privileges | Coverage | Upstream-merge friendliness | Effort |
| --- | --- | --- | --- | --- | --- |
| 1 | **Fork-style modes on a local proxy** ("HTTP streams" / "HTTP+HTTPS" / "P2P" vocabulary): shared `IptvProxyRelay`-style loopback relay for mpv + Ktor/OkHttp proxy config + libtorrent proxy/interface via engine config | **None** | Playback (HTTP+HTTPS), API/scraper, P2P engine; DNS when proxies resolve remotely | Excellent: new files + desktop actuals + one engine-config field (new `nuvio-engine` patch) | Medium |
| 2 | **`SO_BINDTODEVICE` hardening layer** (bind remaining/bound-capable sockets to the user's VPN interface, fail-closed) | None (Linux ≥ 5.7, unbound socket) | Only stacks it hooks; strongest per-socket guarantee; requires user's system VPN up | Good (Linux-only actual behind a seam) | Medium-high (per-stack JNI), so do narrowly or skip |
| 3 | **Network-namespace wrapper** (whole app or TorrServer only) | Root/system integration (or rootless pasta) | Everything incl. DNS; LAN features need a home-bypass story | Excellent (packaging/docs only) | Low code, ops-heavy UX |
| 4 | **In-app userspace VPN engine** (port the fork's shape: wireguard-go + gVisor netstack in-process exposing a local proxy, profile import + probe) | None | Whatever routes through its local proxy, incl. DNS | Moderate: isolated native Go module + new toolchain, GPL-adjacent sources to license-account (reference, do not copy) | High |

**Recommendation:** build **#1 first** — it mirrors the forks' actual semantics (protection
*scopes*, not socket options), costs no privileges, rides on already-proven repo mechanics
(`IptvProxyRelay`, `IptvProxiedClients`, the `createApiHttpClient` expect/actual choke point), and
keeps the only divergence (engine config field) inside the existing `nuvio-engine` patch
mechanism. Design its modes with the fork's vocabulary and invariants: per-class scopes
(Off / HTTP streams / HTTP+HTTPS / P2P), fail-closed when the tunnel is not verified (BLOCK, never
silent DIRECT fallback), loopback always direct. Layer **#2** later only if users actually run
system VPNs and want socket-level enforcement for the paths #1 cannot reach. **#3** is the
correct answer for "Full app" parity on Linux and should be documented rather than coded; **#4**
is a genuine parity project for a later stage — its architecture (userspace netstack + local
proxy + probe) is the blueprint to follow, as reference.

## Sources

- Forks: homelessbrian/NuvioPlusIPTV — `vpn-core/src/main/java/com/nuvio/vpn/core/ProtectionMode.java`,
  `…/core/StreamRoutePolicy.java`, `app/src/main/java/com/nuvio/tv/core/vpn/PlaybackVpn.kt`,
  `vpn-android/src/main/java/com/nuvio/vpn/android/HttpStreamTunnel.java`, `…/AppWideWireGuardEngine.java`,
  `…/PlaybackHttpURLConnection.java`, `…/VpnHomeBypass.java`,
  `vpn-android/native/tunnel_dial.go`, `vpn-android/README.md`,
  `app/src/main/java/com/nuvio/tv/ui/screens/settings/VpnSettingsContent.kt`
  (github.com/homelessbrian/NuvioPlusIPTV, branch `dev`).
- zoga2o3-ops/Nuvio-One-M — `vpn-android/README.md`,
  `composeApp/src/commonMain/kotlin/com/nuvio/app/features/vpn/VpnHost.kt` (branch `livetv-nownext-sports`).
- Trees searched (no VPN paths): ysosrs123/NuvioTV-Fork `main`; NuvioMedia/NuvioTV `dev`;
  UmbraProjects/NuvioDesktop `windows-tv-adaptive`.
- This repo: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/iptv/IptvProxyRelay.kt`,
  `…/IptvHttp.desktop.kt` (`IptvProxiedClients`), `…/IptvMpvOptions.kt`, `…/IptvLivePreview.desktop.kt`,
  `composeApp/src/desktopMain/kotlin/com/nuvio/app/core/network/ApiHttpClient.desktop.kt`,
  `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/p2p/NuvioEngineP2pBackend.kt`,
  `…/TorrServerP2pBackend.kt`, `…/p2p/P2pStreamingEngine.desktop.kt`,
  `composeApp/src/desktopMain/kotlin/com/nuvio/engine/NuvioEngineConfig.kt`,
  `composeApp/src/desktopMain/native/nuvio-engine/CMakeLists.txt`.
- Kernel/man pages: man7.org `socket.7` (SO_BINDTODEVICE, SO_MARK capability rules); torvalds/linux
  commit `c427bfec18f2` ("net: core: enable SO_BINDTODEVICE for non-root users") and
  `net/core/sock.c` (`sock_bindtoindex_locked`); `Documentation/networking/tuntap.txt` (CAP_NET_ADMIN
  for device creation); `ip-rule(8)` and `ip-netns(8)` (manpages.debian.org, iproute2 bookworm);
  `wg-quick(8)` (git.zx2c4.com wireguard-tools).
- systemd: freedesktop.org `systemd.exec(5)` (`PrivateNetwork=`, `JoinsNamespaceOf=`).
- passt: passt.top/passt/about/.
- VPN clients: mullvad.net/en/help/socks5-proxy (10.64.0.1:1080, tunnel-only reachability);
  sing-box `mixed` inbound and `tun` inbound and route-rule fields (`process_name`),
  sing-box.sagernet.org docs.
- Player/HTTP stacks: mpv manual (mpv.io/manual/master — `--http-proxy`, `--stream-lavf-o`,
  libcurl backend `--curl-enabled`); FFmpeg `ffmpeg-protocols.html` (`http_proxy` AVOption) and
  `libavformat/http.c` (http://-only proxies, CONNECT tunneling for HTTPS); Ktor client proxy docs
  (ktor.io/docs/client-proxy.html — engine support table, CIO: HTTP only); libtorrent
  `settings_pack.hpp` (`outgoing_interfaces`, `listen_interfaces`, `proxy_*`,
  arvidn/libtorrent RC_2_0).
- Other platforms: learn.microsoft.com `IPPROTO_IP` socket options (`IP_UNICAST_IF`);
  WireGuard/wireguard-windows `tunnel/firewall/blocker.go` (WFP); XNU `bsd/netinet/in.h`
  (`IP_BOUND_IF`); developer.android.com `VpnService` (`prepare()`, per-app builder).
