# De-prioritizing junk sources: error placeholders and duration-liars

Research question: how should Nuvio demote sources that (a) are addon error placeholders —
"AIOStreams stream not found", "Too many requests" — and (b) are obviously nowhere near the length
of the real media being fetched (a 2-minute clip standing in for a 40-minute episode)?

Companion file: [external-placeholder-stream-research.md](external-placeholder-stream-research.md)
(primary-source findings on how AIOStreams, Comet, MediaFusion, StremThru and the addon protocol
encode these placeholders — researched against the actual addon repos, every claim cited to source
files).

## 1. What the codebase already does

The surprising finding of this research is how much of the machinery already exists. Every gap
below is a gap of *scope* (where a check runs) or *memory* (whether a verdict is remembered), not of
mechanism.

### 1.1 Placeholder-URL recognition — player side, at attach time

`composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/ProviderDiagnosticVideo.kt`
classifies failure URLs without any addon allow-list:

- `playbackSourceFailure(url)` — matches rate-limit placeholders encoded in the URL
  (`title=too+many+requests`, `debrid+provider+is+rate-limiting` on aio/aiostreams hosts) →
  `PlaybackSourceFailure.DebridRateLimited`.
- `isExplicitProviderDiagnosticVideoUrl(url)` — any `?title=…&body=…` / `?error=…` URL, or a
  `/error.` `/status.` `/failed.` path. Nuvio attributes this shape to AIOStreams, but the
  companion research could not find it in current AIOStreams v1/v2 — the closest verified
  generator is Sootio's `/error?message=…` ffmpeg video (§1d, §4 of the companion file). The
  detector is still correct to keep: it is provider-neutral and harmless when it does not fire.
- `isProviderStatusClipUrl(url)` — AIOStreams' `/static/<status>.mp4` clip set (`429.mp4`,
  `no_matching_file.mp4`, `download_failed.mp4`, …) plus any `.mp4` whose filename says
  failed/error/status. **Gap found by the companion research:** the other three major clip
  generators encode the error in basenames that contain none of those substrings — MediaFusion's
  `/static/exceptions/too_many_requests.mp4` and `stream_not_found.mp4`, Comet's
  `status_videos/TOO_MANY_REQUESTS.mp4` (uppercase stems), StremThru's store clips. A basename
  regex over the shared families (`429`, `too_many_requests`, `no_matching_file`,
  `download_failed`, `stream_not_found`, `payment_required`, `store_limit_exceeded`,
  `content_proxy_limit_reached`, `torrent_downloading`, …) would cover all four generators with
  zero network probing — see §5.1 of the companion file.
- `isProviderStatusClipUrl(url)` — AIOStreams' `/static/<status>.mp4` clip set (`429.mp4`,
  `no_matching_file.mp4`, `download_failed.mp4`, …) plus any `.mp4` whose filename says
  failed/error/status.
- `isPlaybackPlaceholderUrl(url)` — the union. Used by `PlayerScreenRuntimeEffects.kt:196` and by
  `StreamLinkCacheRepository.kt:118,197` so a placeholder is never *cached* as "the link" for a
  title — a critical rule, since a status clip describes one failed resolve and reusing it replays
  the failure after the provider has recovered.

Detection of the *dynamic* case (an endpoint that resolves to either media or a clip) lives in
`isProviderPlaybackEndpoint` (Comet `/playback/?torrent_name=…&name=…`, StremThru store/newz
shapes) plus a runtime mpv-metadata probe (`resolveProviderDiagnosticVideo`) and
`isLikelyProviderWaitVideo` (the fixed ~120 s "file is being downloaded" clip, recognized only when
a resume point lies beyond it).

### 1.2 Failover and rate-limit scoping — player side, after a failure

- `PlaybackSourceFailure` (`player/PlaybackSourceFailure.kt`) is currently a one-value enum:
  `DebridRateLimited`. Detection has two feeds: URL shape at attach, and mpv/FFmpeg error text
  (`playbackErrorFailure`: "http error 429", "429 too many requests").
- `rateLimitScopeKey()` scopes the failure to a provider — debrid provider id first, client-resolve
  `service` second, URL domain last — so a 429 from one provider skips only that provider's streams
  within the session. The docstring honestly notes the weak link: inside one aggregator proxy, only
  streams that reached (1) or (2) can be split by provider.
- `StreamFailoverLog` and `PlaybackErrorLog` record what happened, for diagnostics.
- `PlaceholderSourceMemory` (`player/PlaceholderSources.kt`) remembers *which addons* produced a
  placeholder clip this player session and de-prefers them in next-episode autoplay — but only in
  the binge direction, session-only, and only while "Preload Next Episode Sources" is on. Comet's
  South Park behaviour motivated it (the same addon re-picked its clip three times in a row because
  nothing remembered).

### 1.3 Scoring — the list side, before anything is picked

`features/streams/StreamScorer.kt` is the single ranking engine shared by the picker, first-stream
selection and failover. Relevant traits:

- `IMPLAUSIBLE_SIZE` (`StreamSizeSanity`) — a **bitrate-floor lie detector**: size below
  (floor Mbps × duration) marks the row. Floors are per resolution × source tier, halved for
  animation. Duration prefers addon-supplied `durationSeconds` (`StreamTraitDetector`), then TMDB
  `runtimeMinutes` from `StreamScoreContext`, then a biased-short assumption. This is exactly the
  "not anywhere near the length of the real media" detector the research question asks about — it
  already exists, with careful false-positive discipline.
- `SIZE_IN_BAND` / `SIZE_FAR_OUT` — the taste-side size band (distinct from validity).
- `SRC_JUNK` (-3000) — CAM/TS/TC/SCR qualities.
- `MISSING_METADATA` — no resolution AND no source tag.
- The whole catalogue is user-tunable in Settings → Stream Scoring (`StreamScoringSettingsPage`),
  and adding a trait is a one-enum-entry change (the enum doc says so explicitly).

Also relevant: `isScorableStream` (`StreamModels.kt:367`) already separates *non-stream* rows
(diagnostics panels, "notify me" hyperlinks — externalUrl-only) from badly-labelled real streams,
ranking neither but keeping both visible. This is better news than it first looks: AIOStreams'
listing-time error rows ("[❌] …", "Stream not found" text in the description) are
`externalUrl`-only by construction (companion §1b), so they already rank last and cannot be
picked by autoplay (`isSelectableForPlayback` excludes `externalUrl`). The rows that *do* get
picked are the ones with a playable `url` — playback-time placeholder clips reached by 307
redirect from a stable playback URL, MediaFusion's TV exception rows (a real `url` to a clip,
tagged `notWebReady`), and NexoTV-style dead URLs. That is the precise shape of G1.

### 1.4 Fetch-side knowledge

`StreamsRepository` merges per-addon answers; `StreamFetchSupport.kt` sends
`User-Agent: NuvioDesktop/1.0 AIOStreams/1.0` specifically so AIOStreams returns structured
`streamData` (its formatter text is user-controlled). HTTP-level 429s from the *addon itself* are
logged and skipped (`MetaDetailsRepository.kt:1210` shows the pattern for metadata).

## 2. The gaps

G1. **Playable placeholders are ranked as streams.** A row whose `url` resolves to an error clip
    (`/static/429.mp4`, `status_videos/TOO_MANY_REQUESTS.mp4`, MediaFusion's TV exception rows)
    passes `isScorableStream` (it has a `url`), gets a meaningless score (usually 0 — no
    res/source tags), and sits in the list where autoplay / first-stream / SCORED mode can pick
    it. The player then has to *fail it at attach time* —
    paying a probe round-trip or a brief clip flash — instead of the list having demoted it
    before selection.

G2. **The size-sanity check is size-only; the duration signal is discarded when size is unknown.**
    `StreamSizeSanity` needs `size` to fire. Rows without any size but *with* an addon-supplied
    duration (e.g. `duration: 120` on a "40-minute episode") carry the lie in `durationSeconds`
    and nothing compares it to `runtimeMinutes`. The comparison is one expression; it does not
    exist as a trait. The companion research (§2) confirms the addon protocol has **no duration
    field on streams at all** — `behaviorHints.videoSize` and `filename` are the only per-stream
    real-media descriptors (documented merely as subtitle-selection aids) — so this signal exists
    only where Nuvio already parses it: AIOStreams' structured `streamData` (which its own
    request User-Agent unlocks, `StreamFetchSupport.kt`) and description-text parsing in
    `StreamTraitDetector`. That bounds how often D2 can fire, and says plainly that the
    size-vs-duration math must stay derived locally, never awaited from the addon.

G3. **Failure memory is session-shaped.** `PlaceholderSourceMemory` is per-binge; `rateLimitScopeKey`
    failures live inside one player session. Nothing persists "this addon/domain handed me a
    placeholder at 21:04" across restarts, so the same addon can win first-stream again tomorrow.
    (Contrast: `NebulaAvailability.kt` in this repo already implements escalating backoff memory
    for a different domain — the pattern exists locally to copy.)

G4. **No feedback from playback outcome into the list ranking.** The player learns a source was a
    lie (probe verdict, mpv 429, wait-video detection, failover) and acts within the session; the
    scorer has no way to hear about it. There is no trait like "this addon has produced N
    placeholders in the last M days."

## 3. Design directions (with trade-offs)

### D1. Promote placeholder recognition into the list layer (fixes G1)

Add a boolean to `StreamTraits` — `isErrorPlaceholder` — set by `StreamTraitDetector.detect()` by
running the existing player-side classifiers (`isPlaybackPlaceholderUrl`, plus the
`playbackSourceFailure` URL test) over `stream.playableDirectUrl` / `externalUrl` / `description`.
Then:

- Add one score trait, e.g. `SOURCE_ERROR` (group `RELEASE_FLAGS` or a new `AVAILABILITY` slot),
  default deep negative (SRC_JUNK tier, -3000), so it de-prioritizes rather than hides — consistent
  with the "a false positive silently discards a good stream is worse" doctrine already encoded in
  `StreamSizeSanity`'s comments.
- Exclude the row from *autoplay candidacy* the way `autoPlayEligible()` excludes stale cached
  rows — a demotion, still visible and manually selectable.

The companion research sharpens the detection spec in two ways:

1. **Basename families are the de-facto standard, and Nuvio misses part of them.** Widen
   `isProviderStatusClipUrl` (or its list-side successor) with a case-insensitive basename regex
   over the four generators' shared families — `429`, `too_many_requests`, `no_matching_file`,
   `download_failed`, `stream_not_found`, `payment_required`, `store_limit_exceeded`,
   `content_proxy_limit_reached`, `torrent_downloading`. This covers AIOStreams, Comet,
   MediaFusion and StremThru with zero network probing.
2. **AIOStreams' listing error rows need no new work.** They are `externalUrl`-only and already
   fall out of `isScorableStream`; only playback-time redirect clips and MediaFusion-style
   playable exception rows need the trait.

Trade-offs: the detectors live in `features/player` today; moving/sharing them needs a small
platform-neutral home (e.g. `features/streams/PlaybackPlaceholder.kt` that the player imports) to
avoid a features-player → features-streams cycle. Detection from `description` text is riskier than
from URL shape ("Too many requests" can legitimately appear in a formatted title) — URL-shape-only
is the safe v1, exactly the posture AIOStreams itself takes toward *other* addons' error text
(its parser raises on a narrow regex set, companion §1b).

### D2. Duration-vs-runtime trait (fixes G2)

A trait like `DURATION_TOO_SHORT`: fires when `traits.durationSeconds` is present,
`context.runtimeMinutes` is present, and
`durationSeconds < runtimeMinutes * 60 * SUSPICION_FRACTION` with SUSPICION_FRACTION ≈ 0.5.
Same shape as `IMPLAUSIBLE_SIZE`, same "lie detector, not preference" calibration burden: bias
toward not firing (trailers legitimately attach to some addons; specials vary). Cheap, pure, and
testable — the scorer already receives both numbers today and throws one away.

### D3. Persistent failure memory (fixes G3, partially G4)

Generalize `PlaceholderSourceMemory` from a session `Set<String>` to a persisted store keyed the way
`rateLimitScopeKey()` keys (provider id > service > domain), storing timestamps of placeholder
events with decay — mirroring `NebulaAvailability`'s escalating-trust design, and `CwStreamCache`'s
"never cache a placeholder URL" rule. The scorer reads it as a context flag rather than a trait (a
"this provider served a placeholder recently" context signal), so it stays out of the user-tunable
catalogue until proven useful.

Trade-off: persistence invites *staleness harm* — a provider that was rate-limited last week must
not be demoted today. Any design needs explicit decay and needs to demote, never exclude, at least
until a large body of events backs it.

### D4. Content-length pre-flight probe (heavier)

Before attaching, issue a `HEAD`/range request and compare `Content-Length` against the expected
duration × floor bitrate — the same math as `StreamSizeSanity`, executed at bind time instead of
from metadata. `ProviderProbeGate` already serializes attach for suspicious endpoints, so there is
a hook. Cost: an extra round-trip on every dynamic-endpoint attach, and it only covers endpoints
that advertise length. Recommendation: only for `isProviderPlaybackEndpoint` URLs, where the probe
already runs and the placeholder rate is highest.

## 4. Recommended order

1. **D2** (duration trait) — smallest, pure, no cross-package movement, immediate win for the
   "nowhere near the length" complaint.
2. **D1** (placeholder trait) — reuses battle-tested classifiers; needs the classifier file moved
   to a shared home; ship with `NavigationActionRatchetTest`-style mutation-verified Compose tests
   per AGENTS.md (scoring tests are pure — see existing `StreamScorer` test conventions).
3. **D3** — only after 1–2 land and real usage shows which keys (addon? domain? provider?) actually
   correlate with repeat offenders.
4. **D4** — probably never, unless dynamic endpoints remain a visible pain after D1–D3.

Each step is additive to the scorer's trait enum, so merging future
UmbraProjects/windows-tv-adaptive updates stays low-conflict.

## 5. Verification notes

- Existing tests to extend rather than replace: scoring tests around `StreamScorer.rankWithScores`
  (stable sort, NEUTRAL passthrough for non-scorables), `StreamSizeSanity` floor tests, and the
  player placeholder tests (`ProviderProbeGate.clearForTest` exists for test isolation).
- AGENTS.md ratchet rules apply to *navigation* changes; scoring changes need the mutation-verified
  offscreen test discipline but not the navigation funnels.
- Live window tests were not run for this research (none needed; it is doc-only).
