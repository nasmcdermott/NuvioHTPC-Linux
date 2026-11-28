# Multiple-screen playback — design

Implements the user requirement: multiple-screen playback that is seamless enough to just work
through a user control/setting — two or more videos playing at once, each on its own screen.

Grounded in `docs/multi-screen-playback-research.md` (primary-source findings with citations;
this doc's `file:line` references come from there and were re-verified here). Branch: a new
`feat/multi-screen` cut from `linux` (or from `feat/vpn-bind-test` if the two ship together).
Upstream merge surface is kept small: new Linux files, one seam in `NativePlayerController`,
one localized native refactor — no renames of shared code, no second navigation router.

## 1. What "seamless" means here, exactly

Per-aspect definitions, the way the VPN bind defined "0% leaking": a selected behaviour is
intent, and each row names its guarantee and its residual.

| Aspect | Today (one player) | Designed (N = 2 in v1) | Residual |
| --- | --- | --- | --- |
| Video | One X11-embedded player in the one `Window` (`Main.kt:750`) | Main player unchanged; a second libmpv embedded via the same `wid` path into a second `ComposeWindow`'s Canvas, placed/fullscreened on the chosen display through AWT (XWayland translates to per-output xdg fullscreen) | HUD override-redirect windows on a non-primary output are unproven on Bazzite (§7) |
| Audio | One active stream; MPRIS carries it | **Both audible by default**, per-player volume; the second player does not feed `persistedVolumeFraction` | Passthrough to one sink with a second stream claiming it is untested (§7) |
| Media keys | Route to the single MPRIS owner | Route to the **most-recently focused** player's MPRIS instance | A pad mapping always targets the focused window; that is the intended control surface |
| Player arbitration | `LinuxPlayerOwnership`: HERO < MUSIC < PLAYER, one active (`LinuxPlayerOwnership.kt`) | HERO and MUSIC keep yielding; **PLAYER-priority owners may coexist as peers**. A third PLAYER request still evicts the oldest peer — arbitration stays a bounded queue, not unbounded | Eviction order between peers is a policy choice (oldest-first) |
| Native lifetime | `x11PlayerLifetimeReserved` throws on a second X11 player (`player_bridge.cpp:309-310`) | Replaced by per-canvas reservation (a set of live canvas XIDs); `nativeProcessLifecycleLock` stays — VapourSynth is process-global | FFmpeg's global log callback can cross-attribute instance logs (mpv #14880); recovery readers are per-handle, a targeted test is required (Stage 0) |
| Controls / HUD | `LinuxControlsOverlay` per player, one GLib dispatch thread | Already per-player; nothing structural | N WebKitGTK WebProcesses unmeasured (§7) |
| MPRIS identity | One `org.mpris.MediaPlayer2.Nuvio` (`LinuxMediaSession.owner`) | Main player keeps the primary name; the second player registers `org.mpris.MediaPlayer2.Nuvio.instance1` — the MPRIS spec's own instance-suffix policy | External clients that hard-list one name see only the main player; that is correct |
| Stream/data layer | Engine serves per-stream loopback URLs; IPTV relay forwards per connection | Nothing changes: N mpv readers point at different `streamId` URLs (or the same relay with its one token) | Concurrent preload pacing across two engine streams unverified (§7) |
| Navigation | Positional, one router, one focus root per window (AGENTS.md rule 11) | The second window is **its own focus root over the same `core/ui/` funnels** — it is a second *window*, not a second router and not a route in the main router | Needs the design pass in §5 before Stage 3 |
| Teardown | `dispose()` ends the one player | Each player owns its lifetime; closing the second window disposes only its player; app quit disposes all under the same lifecycle lock | — |

**What this does not claim:** more than two simultaneous players in v1; a second *app window*
with the full UI (catalogs, browsing); HDR second-screen sessions (`LinuxHdrSessionCoordinator`
stays main-player-only, and a second-screen player runs the ordinary X11 path — documented).

## 2. Architecture — one process, one window per screen, bare-canvas second window

Decision and the alternatives, with the reasons the research found:

- **One process, window-per-screen (chosen).** libmpv supports N handles per process
  (mpv #4379); this repo already runs several (seek thumbnails, `seek_thumbnails.cpp:124-151`).
  Each instance embeds via `--wid` into its own `ComposeWindow` Canvas — the exact existing
  per-player path (`player_session_options.h:25-40`). Compose Desktop's multiple-window pattern
  is official; placement and `setFullScreenWindow(device)` are plain AWT, which Compose itself
  defers to (compose-multiplatform #4117). Two windows, one `application {}` block.
- **Grid in one window (Stage 4, designed in §5b).** Feasible and closer to the existing code
  than the render-API framing suggested: a 2x2 layout of Canvas hosts inside the main window,
  each embedding its own mpv via the same `wid` path — Stage 1's mechanism multiplied, no new
  video-out architecture. The render API (one `mpv_render_context` per core, render.h:127; N
  contexts per process works, ynoTV proves it) stays the documented alternative for compositing
  video *into* the UI — needed only if the grid must sit under other Compose content, which it
  must not (the HUD overlay already draws over).
- **Second mpv subprocess (rejected for the integrated player).** The repo rule says the
  integrated player must not silently become an external mpv process. ynoTV's popout-as-
  subprocess is the documented fallback if the in-process path hits a wall (§7), not the plan.
- **Second window content: bare Canvas + HUD (chosen).** VLC's detached-video shape: the video
  surface is its own window; controls stay with the player — here, the second window's
  `LinuxControlsOverlay` (its own WebKitGTK HUD, already per-player) IS the controls surface,
  driven by the same per-player HUD plumbing the main player uses. A full Compose player route
  in the second window would drag the main router into a second-window question; the bare HUD
  keeps the second window's Compose content to the Canvas host plus a tiny key layer (§5).

## 3. Native changes (player_bridge) — small and localized

1. **Per-canvas reservation.** `x11PlayerLifetimeReserved{false}` (`player_bridge.cpp:219`)
   becomes `x11CanvasReservations` — a mutex-protected set of canvas XIDs; `reserveX11Lifetime`
   takes a XID and succeeds while the XID is new; `close()` removes only its own XID
   (`:372-376`). The guard's *purpose* stays intact: Xlib's error handler is process-global, so
   create/teardown remains serialized under `nativeProcessLifecycleLock`; what changes is that
   one *live* player no longer forbids another. The comment block at `:215-217` is updated to
   say exactly that.
2. **Bezel keying.** `now_playing_glass.cpp:36` singleton (`Glass &glass()`) is fine to keep as
   the window/thread; its *state* (`linuxBezelSetWindowFocused` global bool, consumed at
   `player_bridge.cpp:1783-1785`) gets a canvas-XID key so the bezel follows whichever player
   holds focus, and two playing players do not fight over one boolean.
3. **Display refresh scoping.** `display_refresh.cpp` switches "the" display the player is on;
   with two players only the *focused* player's session may switch, and a switch while the other
   player is fullscreen-on-that-output must restore correctly. The switch/restore record gains
   an owner (canvas XID) and a rule: non-focused players never switch.
4. **Nothing else.** Seek thumbnails, separate audio, HUD overlays, the JNI surface, HDR probe —
   already per-player. HDR sessions keep main-player-only reservation (out of scope for v1).

## 4. Kotlin changes — one seam, new files elsewhere

1. **Peer controller.** `NativePlayerController` gains one constructor flag:
   `linuxSecondScreen: Boolean = false`. It changes three behaviours, all inside the controller:
   (a) its ownership claim is a peer PLAYER request that may coexist with the main player's;
   (b) it never touches `persistedVolumeFraction` (`:155-158`) — it restores its own last-used
   volume from the new `SecondScreenSettings` store instead; (c) it does not participate in the
   hero/music yield ladder beyond what `LinuxPlayerOwnership` already arbitrates.
2. **Ownership widening.** `LinuxPlayerOwnership` (`LinuxPlayerOwnership.kt`) gets the one new
   rule: `active` becomes a small ordered set for PLAYER-priority owners (insertion order =
   eviction order), while `request()`'s existing "a higher-priority non-yielding owner blocks"
   check treats *peer PLAYER owners* as peers rather than blockers. HERO and MUSIC semantics,
   `yielding`, quiesce/prepared state, and the no-queue honesty are unchanged.
3. **Per-instance MPRIS.** `LinuxMediaSession.owner` stays the main player's primary-name
   session (shutdown hook unchanged, `LinuxMprisSession.kt:217-219`). The second player builds
   its own `LinuxMprisSession(nativeMprisBackend(busName = "org.mpris.MediaPlayer2.Nuvio.instance1"))`
   — the spec's instance-suffix policy — via the existing `linuxMprisOwner` injection seam
   (`NativePlayerController.kt:120`). Media-key routing picks the focused player: the second
   window's focus notifications flip a `focusedPlayer` pointer; the main window's
   window-activated handler flips it back. `LinuxMprisNative.acquire` already returns 0 without
   crashing on name collision, so a stuck second session degrades to "no MPRIS for screen 2".
4. **Second-screen host (new file).** `features/player/desktop/LinuxSecondScreenWindow.kt`:
   owns the `ComposeWindow` (created in `Main.kt`'s `application` block only when the feature
   setting is on), the Canvas host, the per-window key layer (§5), display placement
   (`GraphicsEnvironment.screenDevices`, remembered geometry per display in
   `SecondScreenSettings`), and the `NativePlayerHost` that drives a peer controller. Written
   like `LinuxHdrSession*`: new file, no shared-code rewrites.
5. **Settings + feature gate.** `AppFeaturePolicy.secondScreenEnabled` (expect/actual, Linux
   desktop only — the VPN pattern). Settings → **Player** → new section "Second screen"
   (not a new settings page — the page-length lesson from 1.46.2): a toggle row and a display
   choice row (AWT device index + friendly name, resolved at placement time so a monitor swap
   falls back to the primary). The store is the existing settings mechanism
   (`SecondScreenSettings`, enabled + display + volume + window geometry per display).

## 5. The user control, and the second window's keys

**Entry points (both, they are the same state):**
- Settings → Player → Second screen → toggle + display picker. Turning it on with nothing
  playing opens the second window on the chosen display, ready ("pick something on the other
  screen" prompt in its HUD); turning it off closes the window and disposes its player.
- Player menu → **"Open on second screen"** (moves the *current* playback: source handoff via
  the existing `replaceSource` path — same instance, no restart of the network fetch — or a
  fresh peer controller if the main player is idle). Menu label flips to "Move back to main
  screen". This is the "just work" path: one press, the film appears on the other screen.

**Mutual exclusions:** corner player / PiP and the second screen are states of *different*
players (the second screen is a peer, not a morph), so they can coexist — but the corner player
never hosts a peer's playback. `LinuxPictureInPictureTransition` stays main-player-only in v1.

**Second window keys (the design pass §1 promised):** the second window registers its own focus
root through the existing `core/ui/` primitives — `RootNavFocus` + `keyLayer` are per-window
already — over exactly three focus regions: the HUD (its own WebKitGTK overlay; transport keys
pass through the existing HUD funnel), the window's own controls (close, swap-screens,
display-cycle — three icon buttons in the window's top edge, following the corner-player's
pattern), and the bar-end handoff (its edge controls step out; nothing crosses into the main
window — focus between windows is the window manager's job, and Escape in the second window
returns to its HUD, never to the main window). Movement rules are the repo's: positional,
neighbour-drawn-over, every region owns the keys it can see; `NavigationActionRatchetTest`
stays the funnel ratchet for both windows, and the second window's key layer ships its own
mutation-verified offscreen test.

**What the second window shows for live TV / sports:** the normal player surface playing a
channel or a game — no extra UI; the "wall" (grid of N games in one window) is Stage 4 and not
promised.

### 5b. The 4-up wall (Stage 4) — feasible, deliberately staged after the second screen

Four games at once in the **main window**: a 2x2 Compose layout of Canvas hosts, each with its
own peer `NativePlayerController` embedding one mpv via `wid`. Everything already per-player
stays per-player (HUD plumbing, handles); the additions:

- **Mechanism.** Four `wid` child windows clipped by their parent Canvas cells — not four
  top-level windows, not the render API. mpv resizes each child to cover its parent exactly
  (`DOCS/man/options.rst:3844-3852`), which is what makes the grid layout work for free. The
  render-API path is the documented alternative only if video must ever composite *under*
  Compose content.
- **Audio is follow-focus, one stream at a time.** The focused cell's player owns the audio
  session (claim through `LinuxPlayerOwnership`); the other three run muted (their mpv volume
  at 0, not disposed). Select on a cell moves focus; audio follows. This is the one honest
  product choice in wall mode — four simultaneous audio streams is not something any surveyed
  player does, and passthrough could not survive it anyway (§4 of the research doc).
- **Navigation is positional, and that is the good news:** a 2x2 grid is the exact shape
  `core/ui`'s arrow navigation already handles — the grid registers as its own focus group in
  the main window's router (no second router; rule 11), Up/Down/Left/Right move cells,
  Enter expands the focused cell to fullscreen (the cell's player promoted to the ordinary
  player surface, the grid collapsing back on Back).
- **HUD: one overlay, for the focused cell.** The bezel/focus keying from §3 already scopes
  HUD state by canvas XID; wall mode just points that keying at the focused cell.
- **Budget gates (checked at wall entry, not per frame):** two streams with shader stacks
  already worries §7; a wall requires Anime4K/SVP **off** for grid cells, low-vram presets
  respected, and four decode slots confirmed below the hardware's throughput (a Bazzite
  measurement task, not an assumption). Failing the gate says so in the grid's own HUD note
  ("second screen off · shaders off for wall mode") instead of stuttering.
- **Scope guard:** the wall is four *player-shaped cells*, not a browsing surface — no per-cell
  channel lists, no independent seeking UI beyond the focused cell's HUD. More than four cells
  is not planned: six decode slots plus six HUDs crosses the memory budget on the low-vram
  paths this fork already guards (`low_vram.h`).

## 6. Stages

1. **Stage 0 — native reservation lift** — per-canvas reservation, bezel keying, display-refresh
   scoping; native smoke test that two players on two drawables each get video and audio without
   a display (live window runs need `NUVIO_RUN_LIVE_DISPLAY_TESTS=1` permission per AGENTS.md);
   a targeted two-instance log-attribution test against the recovery readers
   (`http_recovery_events.h`, `audio_passthrough_recovery.h`).
2. **Stage 1 — second window, no UX** — `LinuxSecondScreenWindow` + peer controller + ownership
   widening; debug-only trigger (a `nuvio.desktop.secondScreen=true` property); verified by
   two-stream playback in a live run with the user present.
3. **Stage 2 — per-instance MPRIS + media-key focus routing + volume policy.**
4. **Stage 3 — the seamless UX** — settings section, display picker, player-menu action,
   second-window key layer with its guard test, corner-player/PiP interplay.
5. **Stage 4 — the 4-up wall (§5b)** — after the second-screen path is stable.
6. **Stage 5 — docs/packaging** — AppImage notes (nothing extra needed; same process), the
   honest §7 list folded into the feature's docs page.

Each Kotlin stage ships its mutation-verified offscreen Compose test and a green
`NavigationActionRatchetTest`, per AGENTS.md. Stage 0's native edits land as one localized
diff to the reservation block, so the upstream merge stays reviewable.

## 7. Residual risks (honest list)

- **No two-stream measurements exist on Bazzite hardware with this fork's presets.** Shader
  stacks (Anime4K/SVP) multiply GPU memory per player, and VapourSynth's process-global state
  means SVP on two players at once must be tested before it is allowed (likely: second-screen
  player refuses SVP in v1 with a one-line note, not a crash).
- **KWin/XWayland edge cases:** HUD override-redirect windows on a non-primary output;
  fullscreen of a window that just moved outputs; focus-stealing when both windows play. Needs
  a live Bazzite run (Stage 1's exit criterion).
- **N WebKitGTK HUDs:** one WebProcess per view is the design, but N of them is unmeasured; the
  fork already carries a Bubblewrap sandbox workaround (`controls_overlay.cpp:456-459`).
- **Audio policy is a product decision we made for you (both audible, per-player volume,
  keys-follow-focus);** it is one flag in `SecondScreenSettings` to change after using it.
- **FFmpeg log cross-talk** could mis-attribute a recovery event; the Stage 0 test is the gate —
  if cross-talk corrupts recovery detection, the fallback is per-instance
  `--log-file` redirection, not dropping the recovery logic.
- **Engine preload pacing** under two concurrent readers is unverified; libtorrent serves
  multiple torrents fine, but the fork's preload verification has never run concurrently.
- **The fallback precedent:** if the in-process path hits a wall on any platform, ynoTV's
  popout-as-subprocess is the documented escape hatch — named here so it is a decision, not a
  surprise. The repo player rule (no silent external-player replacement) still applies.
