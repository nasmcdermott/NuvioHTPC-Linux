# NuvioDesktop Linux Port

## Project goal

This repository is a Linux port of:

https://github.com/UmbraProjects/NuvioDesktop

The upstream development branch is:

windows-tv-adaptive

Our Linux development branch is:

linux

The primary goal is to add first-class Linux support while keeping the fork as close to UmbraProjects upstream as possible.

## Core rules

1. Preserve Windows and macOS behavior.
2. Never remove or weaken existing Windows/macOS functionality just to make Linux work.
3. Keep the Linux patch set as small and isolated as reasonably possible.
4. Prefer new Linux-specific implementations/files over duplicating or rewriting large shared files.
5. Reuse existing shared desktop logic whenever possible.
6. Do not perform unrelated refactors.
7. Do not rename or reorganize upstream code unless required for Linux support.
8. Before changing shared code, determine whether the change can instead live behind a platform abstraction.
9. Every significant change must remain easy to merge with future updates from UmbraProjects/windows-tv-adaptive.
10. Do not commit generated build output, secrets, credentials, local.properties, caches, or machine-specific paths.
11. Navigation is positional and owned by one router. Extend `core/ui/` rather than bypassing it; see Navigation.

## Linux target

Initial supported architecture:

- Linux x86_64

Primary development/test system:

- Bazzite
- Wayland desktop

Linux support should not be unnecessarily Bazzite-specific.

## Player

The integrated desktop player is a critical requirement.

The final Linux port should support, where technically possible:

- integrated libmpv playback
- video and audio playback
- hardware decoding
- pause/play
- seeking
- playback progress
- audio track selection
- subtitle selection
- external subtitles
- subtitle styling where supported
- fullscreen
- keyboard controls
- gamepad support
- media keys
- Picture-in-Picture where practical
- stream headers
- stream failover
- existing Nuvio desktop playback logic

Linux-specific features may use Linux-native implementations when Windows APIs are not applicable.

Do not silently replace the integrated player with an external mpv process as the final implementation.

## Navigation

Movement is **positional**, and every region owns the keys it can see. A control enters the region
beside or above it at the neighbour drawn over it, by window coordinate — not by a fixed "next row up"
ladder. Two instances, and the pattern to copy: Settings' category rail asks the side bar for the row
nearest its height (`enterSidebarNear`); the Sports calendar asks the header row, then the top bar, for
the control over the day (`enterHorizontalEntry`, then `enterTopBarNear`). A day under a chip or a pill
reaches that button; a day under the middle, where the row is empty, reaches the bar icon there.

1. **The handoff is two-way.** The bar's ends step into the page's edge controls, and that control's
   Left/Right steps back into the same end of the bar (gear with Follow Teams & Leagues and Hide
   watched, Home with Tables and the tab's search icon). A one-way handoff strands whatever sits
   between the bar and the control it names: the corner player's expand and close buttons were
   unreachable that way.
2. **Aim at the group's leftmost control, and enter a focus group before the control inside it**
   (`requestFocusInsideGroup`). A `focusRestorer` group restores over a direct request, and aiming at a
   later control silently skips the ones before it. Log a handoff that does not land: a silent no-op
   reads like a dead key.
3. **Never leave Back without a destination.** The corner-box player hands Back to the app around it
   (never to its own HUD, which hides an invisible HUD and swallows the key); a return to the tabs
   requests a settle and the page asks for focus with a bounded retry; the window fallback replays Back
   and Escape as well as arrows and Enter, and must resolve the window the Wayland way, where AWT often
   reports no focused window at all. (Locked by `FocusFallbackKeysTest`, `LinuxPlayerEscapeTest`.)
4. **Every page that draws controls on the bar's line registers for it:** `BarSideFocusHandoff` when it
   draws controls beside the bar's ends, `BarPageEntry` when it can be returned to from a pushed route,
   `keyLayer` for its focus root. Whichever apply — not more, never fewer.
5. **Keys go through the funnels**: `navigationKey()`, `navAction()`, `arrowFocusDirection()`. A raw
   `event.key == Key.X` comparison is a ratchet failure (`NavigationActionRatchetTest`), because rebound
   and pad keys arrive as the same action, not the same key.
6. **Extend the router; do not add a second one.** New movement behaviour belongs in `core/ui/` and
   registers with `RootNavFocus`, so every page inherits it. A page-local key walk that duplicates the
   router is how one bug gets fixed three times.
7. **A navigation change ships with its guard**: an offscreen Compose test that fails when the change is
   reverted (mutation-verified), and a green `NavigationActionRatchetTest`. Live window runs need
   explicit permission, as in Git and changes.

Owners: `core/ui/KeyLayers.kt` (router, settle), `core/ui/NavFocus.kt` (regions, entries, handoffs),
`core/ui/NavAction.kt` and `ArrowKeyFocusNavigation.kt` (key funnels), `features/navbar/NavBarSlots.kt`
(slots and position to slot).

## Development order

Work in small, verifiable stages.

1. Make the unmodified upstream application build/run on Linux.
2. Identify all existing Linux blockers and platform assumptions.
3. Implement the minimum Linux native player bridge.
4. Verify actual playback.
5. Add player functionality incrementally.
6. Test application behavior on Bazzite/Wayland.
7. Only after the application is working, add packaging.

Do not start Flatpak/AppImage/RPM work before the application itself works correctly from source.

## Packaging goal

Eventually produce all four formats from the same source commit:

- DEB
- RPM
- Flatpak
- AppImage

Packaging must be reproducible through GitHub Actions.

Do not create one package format by repackaging another package format if a direct source build is practical.

## Git and changes

Do not commit or push unless explicitly asked.

Before finishing a task:

- show the files changed
- summarize why they changed
- run the most relevant build/tests available
- report commands run and their results
- mention any remaining uncertainty

If a task uncovers a larger architectural issue, stop and explain it before performing a broad rewrite.

Never run live X11/XWayland/Compose/GTK/WebKit window tests against the user's active desktop session unless explicitly requested. Default automated validation must use non-display unit tests and native fixtures. Live window tests require `NUVIO_RUN_LIVE_DISPLAY_TESTS=1`; native-smoke or focus-test flags alone do not authorize them.

## External references

Other Linux implementations or upstream Nuvio repositories may be studied as references.

Do not blindly copy code.

Understand how an implementation works, compare it to the current UmbraProjects source, and adapt only what is appropriate for this codebase.

## Compatibility

Future syncs from UmbraProjects/windows-tv-adaptive are a first-class requirement.

When choosing between two implementations, prefer the one that produces fewer conflicts with future upstream changes.
