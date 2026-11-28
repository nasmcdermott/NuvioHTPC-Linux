# Multiple-screen playback — research findings

Research for a fork-desktop "multiple-screen playback" feature: two or more videos playing at
once, each on its own screen/window, enabled by something seamless (a toggle or an "open on
second screen" action). Linux-first, on top of the existing libmpv JNI bridge, without breaking
Windows/macOS behavior or the upstream merge surface. Everything below is from primary sources —
the mpv API headers and source, bug trackers where the numbers live, the projects' own docs and
source, Compose/AWT documentation, the MPRIS spec — plus `file:line` readings of this repo.
Per repo rules, other projects are references for mechanisms, not patch material.

Two headline facts up front:

- **One process hosting N mpv instances is supported and is already half-true here.** libmpv
  supports several handles per process (mpv #4379: "Should work with libmpv … you'll have to
  create child windows for each"), and this repo already runs multiple mpv handles per process
  today — the main player plus the windowless seek-thumbnail decoder
  (`seek_thumbnails.cpp:124-151`) and the gapless/speech side decoders. The single-player
  behavior is enforced by *deliberate guards in the fork*, not by libmpv.
- **The player UI assumes exactly one top-level window and one X11-embedded player.** The app
  has one `Window` (`Main.kt:750`); its PiP variants morph the *same* window
  (`LinuxPictureInPictureAwtWindow` wraps the existing `ComposeWindow`,
  `LinuxPictureInPictureTransition.kt:57-58); and the bridge throws on a second X11 player
  (`player_bridge.cpp:309-310`). A second screen needs a second `ComposeWindow` + Canvas + one
  more `NativePlayerController` — the JNI surface itself is already fully per-handle.

**Recommended approach (summary; details in §4-§5).** Stay in one process: N (start with 2)
libmpv instances, each embedded via the existing `wid` path into its own `ComposeWindow`'s
Canvas, each with its own HUD overlay (already per-player), per-instance MPRIS using the spec's
instance-suffix bus-name policy, and the ownership arbitration widened from "one active player"
to "peer players may coexist, priorities still order evictions." Entry points: a
Settings → Player → Second screen toggle (display picker) and an "Open on second screen"
action in the player's menu. Do not spawn external mpv processes for the integrated player
(repo player rule), though ynoTV's popout-as-subprocess is the fallback precedent if in-process
hits a wall.

## 1. What comparable players actually do

| Project | Multi-video mechanism | What it got right / wrong | Source |
| --- | --- | --- | --- |
| mpv CLI | One video window per process, full stop. "It can't open more than one window anyway"; "several windows are theoretically possible but are not within the scope of mpv itself (the core doesn't support this) but rather of libmpv users" (Akemi, mpv maintainer) | The core refuses to grow multi-window; everything is delegated to embedders | mpv #8742 (github.com/mpv-player/mpv/issues/8742); one-process linking via `umpv` script, mpv #3811 |
| libmpv (API) | N handles per process; each handle = one core, one video output, one window or one render context | Supported since forever, but embedders must own windows; known cross-instance wart: FFmpeg's global `av_log_set_callback` leaks messages between instances ("there is no separation. So it will log to one of the mpv instances always" — kasper93) | mpv #4379 (github.com/mpv-player/mpv/issues/4379); mpv #14880 (github.com/mpv-player/mpv/issues/14880) |
| ynoTV (IPTV player, Tauri+mpv, "Stremio & Nuvio integration") | Closest real analogue. In-process multiview: **one libmpv instance + `mpv_render_context` per slot** rendered (software) into DOM canvases; 2x2 grid up to 4 streams; "popout" player is a **separate mpv process** controlled over a Unix socket/named pipe | Multi-stream grids in one process work via the render API; window embedding stays one-stream-per-window; subprocess popout used exactly where a second OS window is wanted | `mpv_canvas.rs` (`create_canvas_mpv_instance`, `mpv_render_context_create` — lines 56-103), `mpv_popout.rs:1-4`, `useMultiview.ts` (github.com/tbeezy/ynotv, branch `main`) |
| VLC | libvlc explicitly allows "one (or more) instance(s) of LibVLC in a given process" (`libvlc.h:66-67`). Qt's "detached video" (4.0-era compositor work): "Send the video to a specific screen while keeping the controls on the current screen (presentation mode) … this operation can be done during the playback" | VLC's own RFC admits multi-vout/embedding is its weakest area: interfaces share state "through globals or VLC variables hacks", it "doesn't play very well with mosaic use cases"; vout-window provisioning was a years-long rework | `include/vlc/libvlc.h:65-67` (videolan/vlc, master); vlc-devel patchset 136154 + 136117 (mailman.videolan.org, Aug 2020); vlc RFC 00001 "window provisioning" (github.com/alexandre-janniaux/vlc-rfcs) |
| Kodi | None. "only one at a time; Kodi utilizes the gfx engine, libraries & it's memory to the exclusion of multiples" (Team-Kodi member). Even multi-*instance* arrived only in 22.0a2 — and only on Windows | Shows the cost of a GPU-stack-wide single-instance architecture | Kodi forum thread 372019 (forum.kodi.tv); Kodi 22.0a2-Piers changelog ("Multiple Kodi instances can now be run simultaneously on Windows") |
| Jellyfin Media Player | None — one `KonvergoWindow` (QQuickWindow) hosting the web UI and one embedded mpv | Embedding mpv in the app window is the same shape as Nuvio's; no multi-window to learn from | README ("Media plays within the same window", github.com/jellyfin/jellyfin-media-player); `src/ui/KonvergoWindow.cpp` |

Takeaways: nobody ships "N embedded mpv windows in one process" except via the render API
(ynoTV), and the one project that wanted cross-screen video+controls separation (VLC's detached
video) solved it by moving the *video surface* into a separate window while keeping the
interface where it was — structurally identical to what Nuvio's HUD already does
(`LinuxControlsOverlay` is a separate override-redirect GTK/WebKitGTK window drawn over the
Canvas, `controls_overlay.cpp:381-386`).

## 2. mpv multi-instance reality

**Handles.** `mpv_create()` per handle; "This instance is in" an un-initialized state you
configure before `mpv_initialize()` (`include/mpv/client.h`, `mpv_create` doc). Handles are
independent cores with their own event queues and per-handle wakeup callbacks
(`client.h` event-loop section). This repo's `Player` struct is already one handle per
`shared_ptr` with its own event thread (`player_bridge.cpp:151-160`), and the handle table
(`players` map) is keyed by handle (`publishPlayer`, `player_bridge.cpp:1158-1165`).

**Video out per window.** Two viable paths per instance:

- Embedding: `--wid` — "On X11, the ID is interpreted as a `Window` … mpv always creates its
  own window, and sets the wid window as parent. The window will always be resized to cover the
  parent window fully" (`DOCS/man/options.rst:3844-3852`, mpv master). This is exactly what the
  fork does per player (`linuxX11EmbeddingOptions`, `player_session_options.h:25-40`, setting
  `wid` to the Canvas drawable). One wid per instance; no process-level sharing.
- Render API: "Currently, there can be only 1 mpv_render_context at a time **per mpv core**"
  (`include/mpv/render.h:127`, and "only at most 1 context can exist per mpv core",
  `render.h:600`). Per-core, not per-process — ynoTV's per-slot contexts confirm N contexts in
  one process (`mpv_canvas.rs:101`). Relevant only if a future grid-in-one-window mode is
  wanted; not needed for window-per-screen.

**Audio.** mpv's PulseAudio AO creates **its own** `pa_threaded_mainloop` and `pa_context` per
AO instance and connects them separately (`audio/out/ao_pulse.c:350,361,375`, mpv master) —
there is no shared process-global Pulse connection, so N players each get their own stream from
PulseAudio or PipeWire (PipeWire's pulse replacement serves the same protocol), each settable to
its own `--audio-device`. The per-handle separation the repo relies on for separate audio
(`separate_audio.h`, `audio-files` per handle) is unaffected. The only genuinely shared decision
is policy: what two simultaneous audio streams mean (see §4 audio and §6).

**Hardware decode limits (real numbers).**

- NVIDIA: "NVDEC natively supports multiple hardware decoding contexts with negligible
  context-switching penalty. As a result, subject to the hardware performance limit and
  available memory, an application can decode multiple videos simultaneously" (NVIDIA Video
  Codec SDK 13.1, NVDEC Application Note). The consumer-GPU session cap applies to NVENC
  (encoding), not NVDEC: "The number of concurrent NVDEC decodes isn't restricted" (Tom's
  Hardware, 2023-03-24, on NVIDIA's own support matrix change; encode went 3 → 5 sessions).
- VA-API: no small per-driver cap for our scale. Chromium defaults to **16 concurrent
  VaapiVideoDecoders** and only caps it lower where file descriptors run out (its limit
  experiment measured ~1699 FDs with 16 H.264 decoders; commit `fa9b67b`, chromium/chromium)
  or where specific AMD APUs misbehave (Grunt capped at 10, commit `edb8ee0`). Intel markets
  16 simultaneous stream decode on its iGPU (Intel Metro Vision AI SDK tutorial-2). libva's own
  guide states all VA-API functions are thread-safe (intel.github.io/libva).
- Practical read: 2 players + a thumbnail decoder is far below any of these caps; the real
  constraints are decode *throughput* (shared VDBox/VCN engines) and per-instance surface
  memory — which the fork already worries about via `low_vram.h` and its buffer presets.

**Threading / process-global state.** Each handle runs its own core threads; X11 threading is
handled process-wide by `xlib_threads.cpp` (already loaded before AWT/GTK touch X,
`Main.kt:536-538`). Two documented cross-instance warts: (a) FFmpeg log attribution leaks
between instances (mpv #14880) — worth noting because the fork's recovery logic
(`http_recovery_events.h`, `audio_passthrough_recovery.h`) reads per-handle logs
(`mpv_request_log_messages` per handle); (b) VapourSynth keeps process-global state, which is
why native create/teardown is already serialized process-wide
(`NativePlayerController.kt:142-152`). Both are reasons to keep the existing
`nativeProcessLifecycleLock` even with N players, not to drop it.

## 3. Compose Multiplatform / AWT window mechanics on Linux

- **Multiple top-level windows are a supported Compose Desktop pattern**: the official tutorial
  hosts a list of `Window()` composables inside one `application {}` block
  (kotlinlang.org "Top-level windows management", § Manage multiple windows). Compose relies on
  AWT for window management, so per-window placement is done with plain AWT
  (JetBrains/compose-multiplatform #4117, MatkovIvan: "Compose relies on AWT for window
  management … you can achieve programmatically moving [windows across displays] with AWT").
- **"Send this window to screen N"**: `GraphicsEnvironment.getScreenDevices()` + setting the
  window location/device; `Frame.setFullScreenWindow(device)` for fullscreen. AWT exposes
  multiple `GraphicsDevice`s through X11 RandR.
- **Wayland specifics.** OpenJDK has **no native Wayland toolkit by default**: the Wakefield
  project's own plan is "a short to medium term solution for JDK running on Wayland in X11
  compatibility mode" first, with the pure Wayland toolkit as the long-term goal
  (wiki.openjdk.org Wakefield). ArchWiki: "The open source implementation of the Java platform
  OpenJDK does not yet have native support for Wayland"; you need a Wakefield build and
  `-Dawt.toolkit.name=WLToolkit`. Decisively for this app: "Today no JDK exposes a Wayland
  surface through JAWT: jawt_md.h is X11-only" (wakefield-dev mailing list, 2026-06-18) — so
  the mpv-in-Canvas embedding can only live under **XWayland**, as it does today
  (`gdk_set_allowed_backends("x11")`, `controls_overlay.cpp:78`).
- Under XWayland, multi-screen works anyway: Xwayland 23.1 emulates RandR per Wayland output,
  exposing output names (lists.x.org xwayland 23.1 announce: "Improved emulated XRandR support
  exposing the output names"), and an X11 window's fullscreen is translated to Wayland
  fullscreen on the output the window sits on (`xwayland-window.c`,
  `xwl_window_set_fullscreen`/`xwl_window_get_output` — it resolves the window's `wl_output`
  and fullscreens that; github.com/external-mirrors/xorg-xserver). The compositor side is
  `xdg_toplevel.set_fullscreen(output)` — "the output … indicates the client's preference as to
  which display it should be set fullscreen on" (xdg-shell spec). So two `ComposeWindow`s can
  be positioned and fullscreened per monitor by KWin through XWayland.
- **Per-window HUD**: each `LinuxControlsOverlay` is already its own override-redirect GTK
  toplevel + WebKitGTK view, created per player
  (`controls_overlay.cpp:381-386` window creation, `:413-434` per-view WebKit settings). The
  only shared piece is the single process-lifetime `GtkThread` dispatch loop
  (`controls_overlay.cpp:28-97`), which serializes N overlays on one GLib context — correct,
  and cheap.
- AWT fullscreen interplay for two player windows (focus stealing on entering/exiting
  fullscreen in one window while the other plays) is the main untested risk — see §6.

## 4. Cost map for THIS codebase — what is single-voice today

| Component | Location | Today | What N players need |
| --- | --- | --- | --- |
| X11 player lifetime reservation | `player_bridge.cpp:219` (`x11PlayerLifetimeReserved`), `:309-310` (throws "Linux X11 player is already active"), released in `close()` `:372-376` | Boolean process-wide guard: exactly one X11-embedded player | Replace with per-canvas accounting (a set of live `canvasXid`s); each player owns its own drawable. The HDR session already bypasses this guard (never calls `reserveX11Lifetime`), proving the guard is X11-scoped, not mpv-scoped |
| Player ownership arbitration | `NativePlayerController.kt:136-152` (`nativeProcessLifecycleLock`, `linuxOwnership`), `LinuxPlayerOwnership.kt` (priorities HERO < MUSIC < PLAYER; `claim` checks `active == null` before handoff) | One active player; a film evicts music, music yields to nothing above it; a hero waits for both | Keep the lifecycle lock (VapourSynth teardown, `NativePlayerController.kt:142-152`). Extend ownership so peer PLAYER-priority owners may be *active simultaneously* while HERO/MUSIC still yield; eviction between peers becomes a policy decision, not a precondition |
| MPRIS session | `LinuxMediaSession.owner` singleton with bus name `org.mpris.MediaPlayer2.Nuvio` (`LinuxMprisSession.kt:197,215-217`); native acquire requests the name with `DO_NOT_QUEUE` and returns 0 unless primary owner (`mpris_session.cpp:258-263`); music uses `…NuvioMusic` (`LinuxMusicMpris.kt:25-28`) | One MPRIS client per bus name; a second acquire would silently return 0 (no crash — good) | Per-instance `LinuxMprisSession` with spec-compliant suffixed names: "each additional instance should request a unique bus name, adding a dot and a unique identifier … such as one based on a UNIX process id. For example: `org.mpris.MediaPlayer2.vlc.instance7389`" (MPRIS spec v2.2, Bus Name Policy). `LinuxMprisPlayer` is already per-controller (`LinuxMprisPlayer.kt:16`) |
| Media-key routing | `Main.kt:667-674` comment block: desktop routes transport keys to music MPRIS, film MPRIS active while video is up | One "current" playback | MPRIS selection must pick a player (last-focused or a setting); D-Bus clients already list players by bus name, so per-instance MPRIS makes external (KDE/GNOME) media keys work per player for free |
| Now Playing bezel/glass | `now_playing_glass.cpp:36` (`Glass &glass()` singleton), `linuxBezelSetWindowFocused` single bool consumed by `player_bridge.cpp:1783-1785` | One bezel window tracking one canvas and one focus flag | Key the bezel state by canvas XID (or per-player object); `linuxBezelSetWindowFocused` must stop being a global toggle |
| Seek thumbnails / side decoders | Per `Player` already (`seekThumbnailsFor`, `player_bridge.cpp:1170-1178` region; `seek_thumbnails.cpp:124-151`) | Per-player | Nothing — already per-instance |
| HUD/controls overlay | Per `Player` already (`LinuxControlsOverlay::create` per create call, `player_bridge.cpp:1165-1174`) | Per-player | Nothing structurally; verify WebKitGTK tolerates N WebProcesses (each view has `hardware_acceleration_policy NEVER`, `controls_overlay.cpp:428`) |
| Separate audio / passthrough recovery | Per `Player` (`separate_audio.h` options per handle; `audio_passthrough_recovery.h` per-player state) | Per-player | Nothing structurally; *policy* for two streams claiming a passthrough-capable sink (§6) |
| Volume carry-over | `persistedVolumeFraction` in the controller's companion object (`NativePlayerController.kt:155-158`), restored on main-player attach only (`:340`, `:487-488`) | One shared volume for "the" player | Decide: second-screen player gets its own volume and only the *main* player feeds the persisted value |
| Display refresh switching | `display_refresh.cpp` (XRandR/Mutter `ApplyMonitorsConfig`), switch + restore record | Switches "the" display the player is on | Scope to a chosen output; with two players, only the focused/main player may switch (a per-display switch fight is a bad outcome) |
| HDR session | `LinuxHdrSessionCoordinator` main-player-only (`NativePlayerController.kt:262-268`), native side never reserves X11 (`player_bridge.cpp:1839-1844`) | Experimental, replaces the embedded player | Out of scope for v1; document that a second-screen player runs the ordinary X11 path |
| IPTV relay | `IptvProxyRelay` object with one per-run token (`IptvProxyRelay.kt:32-46`) | One relay, shared | Nothing — it forwards per connection; N mpv instances can point at the same `http://nuvio:<token>@127.0.0.1:<port>` proxy |
| Engine / data layer | `NuvioEngineP2pBackend` is a single engine object (`:83`) but the engine serves **per-stream** loopback URLs with stream ids (`NuvioEngine.kt:146-168` `prepared` event → `streamId`, `:257` `stopStream(streamId)`); preloads are verified per stream (`:203-254`) | One engine, streams addressed individually | Nothing structural for N mpv readers of different streams; verify concurrent reads don't starve each other's preload pacing (§6) |
| Top-level window | `Main.kt:750` — one `Window`; PiP variants morph the same window (`LinuxPictureInPictureAwtWindow(window, …)`, `Main.kt:885`) | One window owns navigation | Second `Window` from the same `application {}` block; see the navigation caveat below |
| Single app instance | `DesktopSingleInstance.claim` (`Main.kt:573-585`) | One data dir, one process | Unaffected — N windows are inside the one process |
| Navigation | `core/ui/` router; one player route inside the one window; positional nav rules (AGENTS.md) | One focus root per window, one router | The second window needs its own focus root and its own bar/page registration — it must not become a route in the main window's router; this is the largest open *design* question (§6) |

**Upstream-merge shape.** Nearly all new code should be new Linux files (a second-screen host
window + a multi-player manager), mirroring how `LinuxHdrSession*` was added: new files, one
seam in `NativePlayerController` (ownership + a "this controller is a peer, not THE player"
flag), settings keys, no renames of shared code. The `x11PlayerLifetimeReserved` change is the
only native edit to a heavily-shared file — keep it as a localized reservation refactor.

## 5. Staged implementation outline (research-level)

1. **Stage 0 — lift the native single-player guard.** Replace the boolean reservation with
   per-canvas reservation; keep `nativeProcessLifecycleLock`. Verify with a native smoke test
   that two players on two drawables each get video and audio (non-display test first; live
   window runs only with explicit permission per AGENTS.md).
2. **Stage 1 — second window, no UX.** A second `ComposeWindow` (Compose `Window()` in the same
   `application` block, `Main.kt` `application` scope) with its own Canvas + HUD, driven by a
   second `NativePlayerController` instance marked as a peer. Debug-only trigger.
3. **Stage 2 — per-instance MPRIS + audio policy.** Suffixed bus names per MPRIS spec; media
   keys follow the focused/most-recent player; decide what two audio streams mean.
4. **Stage 3 — seamless UX.** Settings → Player → Second screen (toggle + display picker);
   "Open on second screen" / "Move to screen N" in the player menu; per-output fullscreen via
   AWT `setFullScreenWindow` (translated to xdg fullscreen by XWayland, §3); interactions with
   PiP defined (corner player and second window are mutually exclusive states of the main
   player).
5. **Stage 4 — optional grid/wall for live TV.** In-window 2x2 via render-API contexts per cell
   (the ynoTV model) rather than more top-level windows — only if wanted, and only after §3's
   window work is stable.

Each stage ships its mutation-verified offscreen Compose test and a green
`NavigationActionRatchetTest` per AGENTS.md.

## 6. What we don't know yet

- **Two concurrent streams on Bazzite hardware with this fork's presets** (low-vram path,
  buffer presets, Anime4K/SVP shader stacks): no measurements exist. Shader stacks per player
  multiply GPU memory; VapourSynth's process-global state means SVP on two players at once
  must be tested before being allowed.
- **KWin/XWayland edge cases**: HUD override-redirect windows on a non-primary output;
  fullscreen of the *second* window (XWayland's output resolution for a window that just moved,
  `xwl_window_get_output` order of preference); focus-stealing rules when both windows play.
  These need a live run on Bazzite.
- **N WebKitGTK HUDs**: memory and stability of N WebProcesses (likely fine — one per view —
  but unmeasured; the fork already works around Bubblewrap sandbox failures
  `controls_overlay.cpp:456-459`).
- **Engine concurrency**: whether two mpv readers pulling different engine streams can each
  hold their preload targets without pacing fights (`NuvioEngineP2pBackend`'s phase machine is
  per call; libtorrent itself serves multiple torrents fine, but the repo's preload verification
  has never been exercised concurrently).
- **Audio policy**: what two simultaneous streams mean for the user (both audible? ducking?
  main-player-mute?) and whether passthrough to one sink survives a second SPDIF stream. No
  player surveyed answers this for us; it is a product decision plus a test.
- **Second-window navigation**: how a second `ComposeWindow` coexists with the
  "navigation is positional and owned by one router" rule (AGENTS.md). Most likely the second
  window is its own focus root with a minimal control set, not a route in the main router —
  but that needs a design pass against `core/ui/` before Stage 3.
- **FFmpeg log cross-talk** (mpv #14880): whether it can corrupt the fork's log-based recovery
  detection (`http_recovery_events.h`, `audio_passthrough_recovery.h`) when two players stream
  simultaneously. The recovery state is per-handle, but the log *source* is process-global in
  FFmpeg; needs a targeted test.
- **Compose window content model**: whether the second window shows a full Compose UI (its own
  player route) or a bare Canvas + HUD. The bare-Canvas variant avoids router entanglement and
  is what VLC's detached-video model does; the full-UI variant is more "Nuvio-like" but
  heavier.

## Sources

- mpv: `include/mpv/client.h`, `include/mpv/render.h` (lines 127, 600), `include/mpv/render_gl.h`
  (github.com/mpv-player/mpv, master); `DOCS/man/options.rst` (`--wid` 3844-3852);
  `audio/out/ao_pulse.c:350,361,375`; mpv issues #4379 (multi-instance supported), #8742
  (multi-window "not within the scope of mpv itself"), #14880 (FFmpeg av_log global callback),
  #3811 (one-instance `umpv`), #4070 (multi-monitor `--geometry`).
- NVIDIA: Video Codec SDK 13.1 NVDEC Application Note ("multiple hardware decoding contexts…
  decode multiple videos simultaneously", docs.nvidia.com); Tom's Hardware 2023-03-24 (NVDEC
  unrestricted; NVENC 3→5 sessions on consumer GPUs).
- VA-API: chromium commits `fa9b67b` (16-concurrent-decoder default, FD math) and `edb8ee0`
  (Grunt→10); Intel Metro Vision AI SDK tutorial-2 (16 simultaneous streams on iGPU);
  intel.github.io/libva (thread-safety guide).
- VLC: `include/vlc/libvlc.h:65-67` (multiple instances per process allowed); vlc-devel
  136154/136117 (detached video, presentation mode, Aug 2020); vlc RFC 00001 "window
  provisioning" (globals/variables hacks, mosaic).
- Kodi: forum thread 372019 (Team-Kodi: one at a time); 22.0a2-Piers changelog (multi-instance
  on Windows only).
- Jellyfin Media Player: README ("Media plays within the same window using the jellyfin-web
  interface"); `src/ui/KonvergoWindow.cpp` (one window hosts web + mpv).
- ynoTV: `packages/app/src-tauri/src/mpv_canvas.rs` (per-slot `mpv_create` +
  `mpv_render_context_create`, per-slot `audio-client-name`), `mpv_popout.rs:1-4`
  (popout = separate mpv process over socket/pipe), `packages/ui/src/hooks/useMultiview.ts`,
  `MultiviewCell/CanvasMultiviewCell.tsx` (github.com/tbeezy/ynotv, branch `main`).
- MPRIS: spec v2.2, Bus Name Policy (specifications.freedesktop.org/mpris/latest — per-instance
  `org.mpris.MediaPlayer2.<player>.instance<id>` naming).
- Compose/AWT: kotlinlang.org "Top-level windows management" (multiple `Window()` composables);
  JetBrains/compose-multiplatform #4117 (Compose delegates window management/placement to AWT);
  OpenJDK Project Wakefield wiki (X11-compatibility mode first; pure Wayland toolkit WIP);
  wakefield-dev mail 2026-06-18 ("Today no JDK exposes a Wayland surface through JAWT");
  ArchWiki Wayland/Java (WLToolkit not default).
- XWayland: xwayland 23.1 announce (lists.x.org — emulated RandR exposing output names);
  `hw/xwayland/xwayland-window.c` (`xwl_window_set_fullscreen` → per-output Wayland fullscreen);
  xdg-shell `xdg_toplevel.set_fullscreen(output)` (python-wayland.org spec mirror).
- This repo (all `file:line` in text): `composeApp/src/desktopMain/native/linux/player_bridge.cpp`,
  `controls_overlay.cpp`, `mpris_session.cpp`, `seek_thumbnails.cpp`, `now_playing_glass.cpp`,
  `display_refresh.cpp`, `player_session_options.h`, `xlib_threads.cpp`;
  `composeApp/src/desktopMain/kotlin/com/nuvio/app/Main.kt`,
  `…/features/player/desktop/NativePlayerController.kt`, `LinuxPlayerOwnership.kt`,
  `LinuxMprisSession.kt`, `LinuxMprisPlayer.kt`, `LinuxMusicMpris.kt`,
  `LinuxPictureInPictureTransition.kt`, `LinuxHdrSession.kt`, `LinuxAwtViewResolver.kt`;
  `…/features/iptv/IptvProxyRelay.kt`; `…/features/p2p/NuvioEngineP2pBackend.kt`;
  `…/engine/NuvioEngine.kt`.
