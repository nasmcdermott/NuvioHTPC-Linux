# The details hero trailer: why it looks the way it does, and where it can go

The details page's trailer (the bounded video in the upper right of the hero) draws two recurring
complaints: it sits *over* the page's own UI, and videos show black bars they do not contain. This
page records how the trailer is actually drawn, why each complaint follows from that design, what
other interfaces do instead, and the options this codebase has — measured against the real
constraint, which is not taste but window layering.

## How it is drawn today

**The video is a heavyweight OS surface, not Compose content.**
`HeroTrailerPlayerSurface.desktop.kt` hosts mpv through `SwingPanel` — a real native surface inside
the Compose window, the same mechanism as the player and the Home hero. A native surface always
covers whatever Compose has drawn beneath it; content painted after it in the same `Box` does not
show through. This is the root fact the whole design works around: the hero's title, overview, cast
and actions can never be drawn *on* the video, only *beside* it or *under* it.

**Placement is a fixed rectangle.** `DetailHero.kt` places the surface at:

```
start = min(64dp + min(contentMaxWidth, 620dp) + 64dp, 44% of the hero width)
top   = the window's top edge
bottom = hero height − 358dp
```

`contentMaxWidth` is 760dp in the desktop layout, so the box's left edge lands a fixed distance past
the text lane — and inside it whenever the window is narrow. The bottom edge stops 358dp above the
hero's bottom, hard-coded. The box's aspect ratio is therefore whatever the window gives; it is not
shaped to the video.

**The text gets out of the video's way.** When the docked source picker would overlap the content
lane, the lane fades out under the video's left edge (`laneFadeStart` with a 96dp ramp,
`DetailDesktopHeroOverlay`). Combined with the fixed placement, that is the screenshot: the title
does not render over the video — it *ends* where the video begins, and the lane fades rather than
collide.

**The surround is a setting.** While the trailer plays, the hero's scrims recolour to the
`MetaHeroTrailerBackgroundMode` — Theme, **Black (default)**, or a dimmed Backdrop wash. The black
band below the video and around it in the default look is this setting, not the video.

**Bars inside the video are the auto-crop's conservatism.** The bounded surface selects
`PlayerResizeMode.AutoCrop` with `quickAutoCrop` (`auto_crop.h`, ported from the Windows bridge):
bars baked into the frame are measured from screenshot grabs and trimmed with `video-crop`, and
`panscan=1` fills the box with what is left. The rules are deliberately safe, and every one of them
can leave a bar visible:

- only what **both** edges agree on is trimmed (bars are made symmetric) — a clean bottom and a
  dark-but-not-black top trims only the smaller of the two;
- bars under **1.2%** of the frame are treated as none;
- a **dark scene** is rejected outright (picture under 40% of the frame, or wider than 2.9:1);
- the quick decision needs **4 samples, 400 ms apart, agreeing within 1.5%** — about two seconds —
  and until it settles the crop may only *loosen*, never tighten;
- a locked crop only re-checks every 30 s, and only to loosen.

So in the first ~2 s (and for dark trailers, indefinitely) the baked bars show as they are. There is
also the surface's own black `SwingPanel` background while the first frames buffer.

**What already exists.** Two playback modes in Settings › Metadata screens: **Hero** (the bounded
box) and **Fullscreen** (the trailer takes the page — the cinematic option, already built). And the
Home hero already solved text-over-video: its trailer renders the item's logo, title, meta line and
description *inside* the video's own controls chrome (`#heroTrailerContent` in `controls.css/js`,
fed from `PlayerHeroTrailerState.logoUrl/title/meta/description` — fields that already exist and
that the details surface currently passes empty). Home places its trailer **full-bleed** —
`align(CenterEnd).fillMaxHeight().fillMaxWidth()` within the hero region — precisely because a
native surface can only cover: a full rectangle with the text drawn in the chrome reads as a
backdrop, while a smaller rectangle reads as an overlay.

## Why the two complaints follow

**"It overlays the UI."** Inherent to the heavyweight surface: it must cover, so the page hides
whatever would sit under it. The smaller the rectangle, the more the surrounding page has to
contort (faded lane, recoloured scrims, the picker's dock) to make room, and the more it reads as a
pasted-on box.

**"Black bars in videos without bars."** Three separate sources, in order of how often each is the
one you are seeing:

1. the black band below the box — the 358dp bottom padding showing the **Black** background mode;
2. baked bars the auto-crop has only partially trimmed (symmetry rule, 1.2% floor, dark scenes, the
   ~2 s settle window);
3. the box's own aspect mismatch — mostly invisible because `panscan=1` fills, but the crop's
   settle window and the surface's black background show through during it.

## What other interfaces do

| Interface | Pattern | Note |
| --- | --- | --- |
| **Netflix (TV)** | The trailer *is* the hero: full-bleed, the title/buttons over it on a left-to-right scrim | No box, no bars, no overlap — the video replaces the backdrop |
| **Disney+** | Same full-bleed hero video behind a gradient scrim | |
| **Apple TV app** | Full-bleed ambient loop, soft edge into the page | |
| **Plex / Jellyfin** | Letterboxed card in the art area | Closest to today's bounded box |
| **This app's Home hero** | Full-bleed width within the hero region, text in the video chrome | The proven in-codebase precedent |

The pattern shared by the services people call "aesthetically pleasing" is the one this codebase
already uses on Home: **the trailer becomes the hero, and the text moves into the video's chrome.**

## Options

### 1. Full-bleed hero trailer, text in the chrome — the real fix

Place the surface as Home does (`fillMaxHeight().fillMaxWidth()` inside the hero region), and while
it plays:

- hand the details hero's logo/title/meta/description to `PlayerHeroTrailerState` — the fields are
  already in the engine and rendered by `#heroTrailerContent` (left-bottom, scrim-shadowed, exactly
  where the page's lane sits);
- fade the Compose lane out completely while the trailer plays (it is unreadable under the native
  surface anyway — the chrome text replaces it);
- the stream picker keeps docking in the trailer region, as now;
- bars from the surround disappear by construction: no 358dp band, no background mode, no scrim
  recolour — the video fills the hero and the auto-crop trims the baked ones.

Cost: moderate, confined to `DetailHero` and the surface's state plumbing (both already carry the
fields). Risk: the chrome's `#heroTrailerContent` was designed for the Home layout — its 46%
max-width and bottom padding are set in CSS and would need a details-page variant. Compose cannot
draw the text over a native surface, so the chrome variant is the only workable path on all three
desktop platforms.

### 2. Keep the bounded box, fix its geometry — the quick win

- **Shape the box to the video, not the window**: right-aligned, width ≈ 44% of the hero, height =
  width × 9/16, anchored above the cast row instead of `heroHeight − 358dp`. A 16:9 box is what
  AutoCrop assumes; the arbitrary paddings are what make bars likely.
- **Clear the text lane**: `start = max(laneEnd + gap, current)` — the title is never cut, and the
  lane never has to fade for the *video* (only for the picker, as today).
- **Soften the box**: rounded corners on the *surface region* (Compose can decorate the box's
  border — the video is inside it) and a fade at the box's left edge, so the rectangle reads as a
  card in the art area rather than a window punched into the page.

Cost: small, all inside `DetailHero.kt`. Keeps the current interaction (dock, keyboard, mute pill).

### 3. Auto-crop: reveal after the crop settles — helps every mode

- Gate the reveal on the crop, not just first paint: `onReady` currently fires at
  `isPlaying && positionMs ≥ 150`; extend it to "auto-crop applied or locked" (needs one small JNI
  callback or mpv property read from `LinuxAutoCrop`), so the video fades in *after* its baked bars
  are trimmed instead of over them.
- Drop `minimumBar` from 1.2% to ~0.5% for the quick path — hairline bars are common in YouTube
  trailers.
- Weight asymmetric edges instead of requiring full symmetry when one edge is confidently clean.

Cost: small-medium, native, with existing tests (`auto_crop_test.cpp`) to extend.

### Not recommended

- **Fullscreen as the default** — it already exists as a choice; it loses the page context the
  details screen exists for.
- **A Compose-native video pipeline for the hero** — it would trade away the player's headers,
  failover, loudness chain and SVP exclusion list for one Composable, and the Home and details
  heroes share that pipeline deliberately.

## If built, in this order

1. Option 2 (geometry) + Option 3 (reveal gating) — small diffs, answer both complaints, no
   architecture change. The auto-crop tests cover the trim rules; the geometry is testable in a
   Compose test the way the hero's other placement rules are.
2. Option 1 (full-bleed with chrome text) — the aesthetic end state, reusing Home's proven path;
   needs a `#heroTrailerContent` variant for the details layout and a decision on what the picker
   docks over while it plays.
