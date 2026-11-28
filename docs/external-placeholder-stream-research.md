# Placeholder / error streams from Stremio addons — primary-source research

Research notes for the Nuvio Linux port. Goal: understand exactly how addon
ecosystems encode *failure placeholders* (short generated error videos, or
streams whose only content is an error message) and what the addon protocol
offers for describing *real* media, so Nuvio can de-prioritize such rows.

All findings are from primary sources (GitHub source code, checked out locally;
official SDK docs). Line numbers refer to the following commits:

| Repo | Commit checked |
| --- | --- |
| [Viren070/AIOStreams](https://github.com/Viren070/AIOStreams) | `89f03f6` (main, 2026-11-24) |
| [g0ldyy/comet](https://github.com/g0ldyy/comet) | `a3b75ec` (2026-11-18) |
| [mhdzumair/MediaFusion](https://github.com/mhdzumair/MediaFusion) | `f01aba8` (v6.2.0, 2026-10-24) |
| [MunifTanjim/stremthru](https://github.com/MunifTanjim/stremthru) | `3472fd5` (v0.105.4, 2026-10-25) |
| [sooti/sootio-stremio-addon](https://github.com/sooti/sootio-stremio-addon) | `96afb45` |
| [joaosavi/nexotv](https://github.com/joaosavi/nexotv) | `fc6844a` |
| [Stremio/stremio-web](https://github.com/Stremio/stremio-web) | `a7e8823` |
| [Stremio/stremio-addon-sdk](https://github.com/Stremio/stremio-addon-sdk) | `796ca01` |
| [orion-lib/OrionTV](https://github.com/orion-lib/OrionTV) | `41c7a60` |
| [tapframe/NuvioDesktop](https://github.com/tapframe/NuvioDesktop) | `e9fe8c5` |

---

## 1. AIOStreams (maintained repo: `Viren070/AIOStreams`)

Note on naming: the user prompt's "Vynce/AIOStreams" does not exist; the
maintained, canonical repo is **`Viren070/AIOStreams`** (TypeScript, pnpm
monorepo, ~2k stars). It is currently at v2.x ("AIOStreams v2"); the pre-rewrite
v1 code was inspected via tag `v1.9.2` and the `feat!: rewrite` PR
[#179](https://github.com/Viren070/AIOStreams/pull/179) (base `927cb7f`).

AIOStreams has **two distinct placeholder mechanisms**:

### 1a. `/static/<status>.mp4` error clips served at *playback* time

Debrid/usenet/HTTP-resolved streams do not point at the debrid service
directly; they are rewritten to the AIOStreams server's own playback endpoint
(`PLAYBACK_PATH_PREFIX = '/api/v1/debrid/playback/'`,
[`packages/core/src/debrid/utils.ts` line 1081](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/debrid/utils.ts)).
When resolving fails during playback, the server **HTTP 307-redirects the
player to a pre-generated static error video**:

- Mapping of debrid error codes to videos:
  [`packages/server/src/utils/static-errors.ts`](https://github.com/Viren070/AIOStreams/blob/main/packages/server/src/utils/static-errors.ts),
  lines 20–64 (`mapDebridErrorToStaticFile`). The comment there is explicit
  about the design intent: *"Playback endpoints answer a player, so a failure
  has to be watchable to be legible."*
- Redirects: `packages/server/src/routes/api/debrid.ts` lines 259/266/271
  (`res.redirect(307, \`/static/${mapDebridErrorToStaticFile(err.code)}\`)`),
  `packages/server/src/routes/api/usenet.ts` line 50 (302),
  `packages/server/src/routes/api/proxy.ts` line 402
  (content-proxy limit), `packages/server/src/routes/builtins/library.ts`
  lines 172–223 (library streaming), and
  `packages/server/src/routes/jellyfin/items.ts` line 955 (Jellyfin
  `no_matching_file.mp4` URL).
- The clips are plain Express static files:
  `app.use('/static', corsMiddleware, express.static(staticRoot))` —
  [`packages/server/src/app.ts` line 317](https://github.com/Viren070/AIOStreams/blob/main/packages/server/src/app.ts);
  assets in `packages/server/src/static/`.

**Complete set of static filenames** (`StaticFiles` enum,
`packages/server/src/utils/static-errors.ts` lines 1–18; files verified on disk,
each 15–31 KB):

| File | Meaning |
| --- | --- |
| `429.mp4` | `TOO_MANY_REQUESTS` (rate limited) |
| `401.mp4` / `403.mp4` / `500.mp4` | HTTP auth/forbidden/server errors |
| `200.mp4` | success marker (library route, "OK") |
| `download_failed.mp4` | many codes: `DOWNLOAD_FAILED`, `UNPROCESSABLE_ENTITY`, `UNSUPPORTED_MEDIA_TYPE`, `STORE_MAGNET_INVALID`, `BAD_GATEWAY`, `GONE`, `SERVICE_UNAVAILABLE` |
| `downloading.mp4` | `TIMEOUT` / no resolved URL yet |
| `no_matching_file.mp4` | `NO_MATCHING_FILE` (torrent had no matching video file) |
| `content_proxy_limit_reached.mp4` | `TOO_MANY_ACTIVE_CONNECTIONS` |
| `store_limit_exceeded.mp4` | `STORE_LIMIT_EXCEEDED` |
| `payment_required.mp4` | `PAYMENT_REQUIRED` |
| `unavailable_for_legal_reasons.mp4` | `UNAVAILABLE_FOR_LEGAL_REASONS` |

History: the 429-specific clip was added in commit
[b893191](https://github.com/Viren070/AIOStreams/commit/b8931915a964abdffa9bee005a4129e01484c186)
("feat(debrid): handle TOO_MANY_REQUESTS with specific error video") and the
mapping was expanded in
[670d931](https://github.com/Viren070/AIOStreams/commit/670d93170a03eec89f10c6f8a11118c45a3c4bbc)
("fix(debrid): improve error video mapping") — see
[CHANGELOG.md](https://github.com/Viren070/AIOStreams/blob/main/CHANGELOG.md)
lines 1617 and 2671.

### 1b. Error *streams* (rows) in the `/stream` response — no video, no query params

When an upstream addon or the pipeline fails **during the listing request**
(not playback), AIOStreams appends an error row to the returned `streams`
array. These rows are **`externalUrl`-only** — they open a web page when
clicked, they are not playable clips:

- `StremioTransformer.createErrorStream` —
  [`packages/core/src/transformers/stremio.ts` lines 355–375](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/transformers/stremio.ts):
  `{ name: '[❌] <addon name>', description: <error text>, externalUrl: 'https://github.com/Viren070/AIOStreams' }`.
- Appended at the end of the list by `transformStreams` (lines 189–199) unless
  the user set `hideErrors` / `hideErrorsForResources` (`showError`, lines
  37–45; schema fields at `packages/core/src/db/schemas.ts` lines 1021–1022).
- If the whole `/stream` request throws, the route still answers **HTTP 200**
  with a single error stream ("Please configure the addon first", or the error
  text) — [`packages/server/src/routes/stremio/stream.ts`](https://github.com/Viren070/AIOStreams/blob/main/packages/server/src/routes/stremio/stream.ts).
  Only when errors are hidden does it fall through to a JSON error.
- Error rows are recognised internally by `type: 'error'`
  (`constants.ERROR_STREAM_TYPE`, `packages/core/src/utils/constants.ts`
  line 1563) and separated from usable streams in
  [`packages/core/src/streams/fetcher.ts` lines 148–160](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/streams/fetcher.ts).
- AIOStreams also detects *other addons'* error/placeholder text in stream
  descriptions and escalates it: `StreamParser.raiseErrorIfNecessary` throws on
  regexes like `/public\s+rate[-\s]?limit\s+exceeded/i` and
  `/invalid\s+\w+\s+(account|apikey|token)/i`
  ([`packages/core/src/parser/streams.ts` lines 47–57, 261–269](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/parser/streams.ts)).

### 1c. How rate limiting is signalled

Three distinct paths:

1. **AIOStreams' own IP rate limiter → real HTTP 429.** express-rate-limit
   middleware throws `APIError(ErrorCode.RATE_LIMIT_EXCEEDED)`
   ([`packages/server/src/middlewares/ratelimit.ts`](https://github.com/Viren070/AIOStreams/blob/main/packages/server/src/middlewares/ratelimit.ts)),
   which maps to `statusCode: 429, message: 'Too many requests from this IP,
   please try again later.'` (`packages/core/src/utils/constants.ts`
   lines 116–119). This is a JSON error, *not* a stream list.
2. **Upstream debrid 429 during playback → placeholder clip.** The debrid
   `DebridError` carries `code: 'TOO_MANY_REQUESTS'`
   (`packages/core/src/debrid/base.ts` lines 23, 94, 113; e.g. TorBox
   `packages/core/src/debrid/torbox.ts` lines 52, 113–114) and the playback
   route redirects to `/static/429.mp4` (§1a).
3. **Upstream debrid 429 during listing → cooldown, not an empty list.** The
   HTTP layer implements a per-service cooldown with `Retry-After` parsing:
   `RateLimitedError("Too Many Requests (retry after …)")` and a strike-based
   cooldown cache
   ([`packages/core/src/utils/http/cooldown.ts` lines 23–59](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/utils/http/cooldown.ts)).
   Streams still appear; resolution is deferred/retried, so the placeholder
   clip at §1a is the user-visible failure surface.

### 1d. Query-parameter error URLs (`?title=…&body=…`) — **not found**

**Could not be verified.** I searched the current `main` tree, the v1 tree
(tag `v1.9.2` and PR #179 base `927cb7f`), `packages/docs`, the changelog, and
issues for any code building an error-video URL with query parameters such as
`title=`, `body=`, or `message=`. There is none. v1's error streams were also
pure `externalUrl` rows
([v1 `packages/addon/src/responses.ts`](https://github.com/Viren070/AIOStreams/blob/927cb7f90f22ce87ee42ef3ef23c95e8b83e7914/packages/addon/src/responses.ts)),
and v2's error videos carry **no query string** — the error identity is in the
*filename* (`429.mp4`, `no_matching_file.mp4`, …), not in parameters.

If a Nuvio log really shows URLs like `…?title=Too+many+requests&body=…`, they
most likely come from a different addon. The closest primary-source matches
found anywhere in the ecosystem are:

- **Sootio** (`sooti/sootio-stremio-addon`) serves
  `GET /error?message=<text>` — an **ffmpeg-generated** 10-second video with the
  message burned in, cached by MD5 of the message:
  [`media-file-server/fastapi_file_server.py` lines 407–493, 738–758](https://github.com/sooti/sootio-stremio-addon/blob/main/media-file-server/fastapi_file_server.py);
  the older Python file server uses the same `message=` param
  (`media-file-server/usenet_file_server.py` lines 780–823).
  Query parameter name: **`message`**.
- **NexoTV** (`joaosavi/nexotv`) returns a placeholder stream row on rate
  limit with `url: 'https://example.com/ratelimited'` (a dead URL) and the
  message in the stream *name*:
  [`packages/backend/src/middleware/rateLimiter.ts` lines 13–60](https://github.com/joaosavi/nexotv/blob/main/packages/backend/src/middleware/rateLimiter.ts).

---

## 2. Stremio addon SDK / protocol: what a stream can say about real media

Source: [Stremio/stremio-addon-sdk](https://github.com/Stremio/stremio-addon-sdk)
(`src/types.d.ts`, `docs/api/responses/stream.md`, `docs/protocol.md`).

### Stream fields (`src/types.d.ts` lines 495–597; `docs/api/responses/stream.md`)

Transport fields: `url` (http/https/rtmp), `ytId`, `infoHash` + `fileIdx`,
`externalUrl` (open in browser), plus the newer usenet/archive fields
(`nzbUrl`, `servers`, `rarUrls`, `zipUrls`, `7zipUrls`, `tgzUrls`, `tarUrls`,
`fileMustInclude`), `subtitles`, `sources` (trackers/DHT). Descriptive fields:
`name`, `title` (deprecated) / `description`.

`behaviorHints` — all optional
([`src/types.d.ts` lines 547–596](https://github.com/Stremio/stremio-addon-sdk/blob/master/src/types.d.ts),
[`docs/api/responses/stream.md`](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md)):

| Field | Documented meaning |
| --- | --- |
| `countryWhitelist` | ISO-3166 alpha-3 codes the stream is accessible in |
| `notWebReady` | URL is not https/MP4 → must be proxied by client |
| `bingeGroup` | autoplay chaining across episodes |
| `proxyHeaders` | `{request, response}` headers the client must use (requires `notWebReady: true`) |
| `videoHash` | pre-calculated OpenSubtitles hash of the video |
| `videoSize` | **"Size of the video file in bytes. This value is passed to the subtitle addons to identify correct subtitles."** |
| `filename` | **"Filename of the video file … highly recommended to set it when using `stream.url` … to identify correct subtitles. This value is passed to the subtitle addons."** |

**Key facts for Nuvio:**

- **There is no duration field on a Stream.** Duration exists only in the
  `player` *event* extras (`action=start&currentTime={ms}&duration={ms}`,
  `docs/protocol.md`, `src/types.d.ts` lines 68–91 `PlayerHandlerExtra`) and in
  Meta `videos[]` objects — never on a stream row. Any "too short for the
  episode" heuristic must come from `videoSize` + bitrate reasoning, `filename`
  parsing (episode-number matching), or post-playback probing. There is no
  protocol signal for it.
- `videoSize` and `filename` are **documented only as subtitle-selection aids**,
  but they are also the only machine-readable per-stream "real media
  descriptors" an addon can provide, and aggregators do propagate them
  (AIOStreams re-emits `behaviorHints.videoSize: stream.size` and
  `filename: stream.filename` in its own output,
  `packages/core/src/transformers/stremio.ts` lines 100–113).
- A stream can also carry a size via `Source Objects` (`{url, bytes}`, "size of
  the file in bytes — while optional adding this can speed up the initial
  buffering", `docs/api/responses/stream.md`).

### Official error/status convention: **none**

The protocol has no error-row or status concept for `/stream` responses. The
documented pattern is to **return an empty list**:

> `builder.defineStreamHandler(...)` … *"otherwise return no streams"*
> `return Promise.resolve({ streams: [] })` —
> [`docs/api/requests/defineStreamHandler.md`](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineStreamHandler.md)

The response may additionally carry cache hints (`cacheMaxAge`,
`staleRevalidate`, `staleError`) which map to `Cache-Control: max-age /
stale-while-revalidate / stale-if-error` (`defineStreamHandler.md` lines 9–16).
Nothing in the protocol or SDK types sanctions placeholder rows; the addon
directory's status checker explicitly treats them as *not healthy* (§3).

---

## 3. How other open-source clients handle fake/placeholder/error streams

### Stremio web/desktop (`Stremio/stremio-web`) — opaque, no detection

stremio-web renders addon stream responses verbatim. It groups non-empty
`Ready` responses and otherwise only distinguishes loading / empty / error
response *transports* — it never inspects stream contents, URLs, or filenames:

- [`src/routes/Details/Streams/useStreamsByAddon.ts`](https://github.com/Stremio/stremio-web/blob/main/src/routes/Details/Streams/useStreamsByAddon.ts)
  (only `content.type === 'Ready' && content.content.length > 0` is displayed),
- empty state message `NO_STREAM` / `ERR_NO_ADDONS_FOR_STREAMS` in
  [`src/routes/Details/Streams/Streams.tsx` lines 150–168](https://github.com/Stremio/stremio-web/blob/main/src/routes/Details/Streams/Streams.tsx).

No URL pattern, content-length, or duration checks exist anywhere in
stremio-web's stream handling (repo-wide grep for `no_matching`,
`download_failed`, `too many requests`, `.mp4` in stream logic: no hits).

### The addon directory's status checker (`docs.stremio-addons.net`) — actively flags placeholders

The (unofficial but authoritative for the ecosystem) addon directory runs
automated "real user" checks per addon and **explicitly discounts placeholder
streams**: *"Placeholders aren't content. A 'please configure me' message, a
donation tile or the same fake stream for every title doesn't make an addon
look healthy."* For streams *"we check that the links actually open, so a list
of dead or expired links doesn't count as working"*, and rate limits on links
never mark an addon down on their own
([blog post "Addon status monitoring is here", 2026-11-21](https://docs.stremio-addons.net/blog/2026-11-21-real-addon-status-checks-are-here)).
This is a server-side checker, not a playback client, but it is the ecosystem's
only deployed placeholder-detection logic I could verify.

### Aggregators detecting *other addons'* placeholders

- **AIOStreams skips Meteor's placeholder streams** by URL pattern:
  *"Meteor's 'no results' and 'donate' placeholders, not streams."*
  `shouldSkip` drops streams whose `externalUrl` contains
  `/status/video/no_results` or ends with `/support`
  ([`packages/core/src/presets/meteor.ts` lines 72–77](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/presets/meteor.ts)).
  Its changelog documents the same behaviour for Meteor's "donate"/"no results"
  placeholder streams
  ([CHANGELOG.md lines 162–163](https://github.com/Viren070/AIOStreams/blob/main/CHANGELOG.md),
  commits `859e30d`, `3fc8968`).
- AIOStreams throws on error-text regexes found in descriptions/titles of
  upstream streams (§1b) — matching *text*, not URLs.

### OrionTV (`orion-lib/OrionTV`) — not a Stremio client

OrionTV (5.6k stars) is a MoonTV/LunaTV-companion React Native TV player; it
has no Stremio addon transport and no stream-placeholder handling (repo-wide
grep for `behaviorHints`, `429` in stream logic: no matches in stream code).
It does not belong in this comparison despite being frequently mentioned
alongside Stremio clients.

### Stremio-Addon-Manager (`Sammmy1036/Stremio-Addon-Manager`)

Manages addon lists only (reorganise/backup/restore); it never requests or
inspects streams. No placeholder handling.

### Upstream NuvioDesktop (`tapframe/NuvioDesktop`) — parses, does not detect

Upstream Nuvio already parses `behaviorHints` per stream —
`bingeGroup`, `notWebReady`, `videoHash`, `videoSize`, `filename`
([`composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamParser.kt` lines 54–61](https://github.com/tapframe/NuvioDesktop/blob/main/composeApp/src/commonMain/kotlin/com/nuvio/app/features/streams/StreamParser.kt);
`StreamModels.kt` lines 183–191) — but uses them only for file-size badges and
subtitle/hash plumbing; there is **no** placeholder/error-stream detection
(repo-wide greps for `no_matching`, `download_failed`, `stream_not_found`,
"too many requests" in stream code: no hits). Any de-prioritisation logic would
be new work layered on fields that are already parsed.

---

## 4. Other placeholder-clip generators and how they signal failure

### Comet (`g0ldyy/comet`) — status videos by *filename*, plus info rows

- **Asset set** (`comet/assets/status_videos/`, 53 clips): `TOO_MANY_REQUESTS.mp4`,
  `NO_MATCHING_FILE.mp4`, `DOWNLOAD_SERVER_ERROR.mp4`, `PROXY_LIMIT_REACHED.mp4`,
  `AUTH_*.mp4`, `STORE_LIMIT_EXCEEDED.mp4`, `GONE.mp4`, `UNKNOWN.mp4`, etc.
- Resolution is by **normalized filename stem** with an `UNKNOWN` fallback:
  [`comet/services/status_video.py`](https://github.com/g0ldyy/comet/blob/main/comet/services/status_video.py)
  (`_build_status_video_index` maps `*.mp4` stems → paths,
  `build_status_video_response` returns a `FileResponse`).
- Served on playback failures: `GET /{b64config}/playback/{hash}/{service_index}/{index}/{season}/{episode}`
  returns status videos for `BAD_REQUEST` config/auth errors
  ([`comet/api/endpoints/playback.py` lines 184–240, 329–372](https://github.com/g0ldyy/comet/blob/main/comet/api/endpoints/playback.py));
  the stream proxy returns `PROXY_LIMIT_REACHED` when the per-connection limit
  is hit ([`comet/services/streaming/manager.py` lines 136–146](https://github.com/g0ldyy/comet/blob/main/comet/services/streaming/manager.py)).
- Placeholder *rows* (not videos) for addon-level states: when torrents are
  disabled, Comet returns a single stream
  `{name: settings.TORRENT_DISABLED_STREAM_NAME ('[INFO] Comet'), description, url: TORRENT_DISABLED_STREAM_URL ('https://comet.feels.legal')}`
  ([`comet/api/endpoints/stream.py` lines 271–280](https://github.com/g0ldyy/comet/blob/main/comet/api/endpoints/stream.py);
  defaults at `comet/core/models.py` lines 234–238), and "unreleased" content
  yields an `externalUrl` row (`stream.py`, `MediaSearchStatus.UNRELEASED`).
  Invalid search → **empty list** (`{"streams": []}`, `stream.py` line 263).

### MediaFusion (`mhdzumair/MediaFusion`) — `/static/exceptions/*.mp4`

- **Asset set** (`resources/exceptions/`, 44 clips): `too_many_requests.mp4`,
  `stream_not_found.mp4`, `no_matching_file.mp4`, `provider_error.mp4`,
  `invalid_credentials.mp4`, `exceed_remote_traffic_limit.mp4`,
  `alldebrid_api_blocked.mp4`, `torrent_downloading.mp4`, etc.
  The clips are **generated from filenames** with ffmpeg + PIL text overlays;
  `stream_not_found.mp4` is explicitly worded *"Stream not found. This stream
  may have been removed or expired."*
  ([`python-deprecated/utils/exception_video.py` lines 44–60](https://github.com/mhdzumair/MediaFusion/blob/main/python-deprecated/utils/exception_video.py)).
- **Playback failure → 302 redirect to the clip**: the provider resolver
  returns a `ProviderError` carrying a `video_file` and the route redirects to
  `{host}/static/exceptions/{file}`
  ([`backend/src/routes/playback.rs` lines 218–226 and `error_video_url` at 1205–1207](https://github.com/mhdzumair/MediaFusion/blob/main/backend/src/routes/playback.rs)).
  Rate limiting maps to the clip: Real-Debrid codes 5/34 →
  `("Real-Debrid too many requests", "too_many_requests.mp4")`
  ([`backend/src/providers/torrents/realdebrid.rs` lines 71, 92](https://github.com/mhdzumair/MediaFusion/blob/main/backend/src/providers/torrents/realdebrid.rs)),
  StremThru store `TOO_MANY_REQUESTS` → same
  (`backend/src/providers/torrents/stremthru.rs` line 88). "Stream not found"
  → `stream_not_found.mp4` (`playback.rs` line 631). Error→HTTP-status mapping
  for non-playback APIs is separate (`backend/src/providers/mod.rs`, 422/502/500).
- **Listing failure → placeholder stream rows with `notWebReady: true`**: TV
  playback responses embed `create_tv_exception_stream(...)` rows whose `url`
  is the clip URL
  ([`backend/src/routes/stream.rs` lines 514–525](https://github.com/mhdzumair/MediaFusion/blob/main/backend/src/routes/stream.rs)),
  used for `no_streams_live.mp4` / `mediaflow_proxy_required.mp4` (lines
  300–321) and `invalid_config.mp4` on user-data errors (lines 222–238, 554–562).

### StremThru (`MunifTanjim/stremthru`) — embedded store status videos

- **Asset set** (`internal/store/video/*.mp4`, embedded via `go:embed`, 12
  clips): `200, 401, 403, 429, 451, 500, content_proxy_limit_reached,
  download_failed, downloading, no_matching_file, store_limit_exceeded,
  payment_required` —
  [`internal/store/video/video.go`](https://github.com/MunifTanjim/stremthru/blob/main/internal/store/video/video.go),
  served at `/v0/store/_/static/<name>.mp4` (`GetLink`, same file).
- **Torz/Wrap playback** resolves failures into `error_video` names and
  redirects to the static clip
  ([`internal/stremio/torz/playback.go` lines 100–265](https://github.com/MunifTanjim/stremthru/blob/main/internal/stremio/torz/playback.go)
  — e.g. HTTP 429 from the store → `429.mp4`;
  [`internal/stremio/wrap/playback.go` lines 28–32](https://github.com/MunifTanjim/stremthru/blob/main/internal/stremio/wrap/playback.go)).
  Same `no_matching_file` / `download_failed` / `429` / `500` naming family as
  AIOStreams.

### Sootio (`sooti/sootio-stremio-addon`) — **on-demand generated** error video with a query parameter

The only addon found whose error-video URL **carries the message in a query
parameter**: `GET /error?message=<text>` renders the message into a cached
ffmpeg video (`media-file-server/fastapi_file_server.py` `generate_error_video`,
lines 407–493; route at 738–758; legacy server `usenet_file_server.py`
lines 780–823). Parameter name: **`message`**. (Not `title`/`body`.)

### NexoTV (`joaosavi/nexotv`) — rate-limit placeholder rows, deliberately "Stremio-friendly"

Instead of answering `/stream/...` with HTTP 429, its rate limiter answers
**HTTP 200 with a placeholder stream**:
`{name: 'NexoTV', title: '⚠️ Rate limit exceeded\nPlease wait…', url: 'https://example.com/ratelimited'}`
([`packages/backend/src/middleware/rateLimiter.ts` lines 13–60](https://github.com/joaosavi/nexotv/blob/main/packages/backend/src/middleware/rateLimiter.ts)) —
i.e. a placeholder row whose URL is intentionally dead. Catalog/meta rate
limits get placeholder metas the same way.

### Meteor (closed-source instance; observed via AIOStreams)

Serves placeholder streams with URLs like `…/status/video/no_results` and
`…/support` (donation), which AIOStreams filters out by URL pattern
(§3). No public source repo could be identified; treat as observed behaviour
reported in AIOStreams' parser and changelog.

---

## 5. Summary of signals usable by Nuvio

1. **Static-clip filename families are the de-facto standard.** AIOStreams
   (`/static/429.mp4`, `no_matching_file.mp4`, …), StremThru
   (`/v0/store/_/static/429.mp4`, `no_matching_file.mp4`, …), Comet
   (`…/status_videos/TOO_MANY_REQUESTS.mp4`, `NO_MATCHING_FILE.mp4`, …) and
   MediaFusion (`/static/exceptions/too_many_requests.mp4`,
   `stream_not_found.mp4`, `no_matching_file.mp4`, …) all encode the error in
   the **basename**. A URL basename blacklist/regex
   (`429`, `too_many_requests`, `no_matching_file`, `download_failed`,
   `stream_not_found`, `provider_error`, `payment_required`,
   `store_limit_exceeded`, `content_proxy_limit_reached`,
   `unavailable_for_legal_reasons`, `torrent_downloading`, …) would catch the
   dominant placeholder generators with zero network probing.
2. **`behaviorHints.videoSize` + `filename` are the only per-stream real-media
   descriptors in the protocol** (documented for subtitles, but always present
   from AIOStreams output). A size-vs-expected-duration sanity check must be
   derived by Nuvio itself (protocol has no duration field); filename parsing
   for season/episode mismatch is the other available signal.
3. **No protocol error convention exists**; empty lists are the official
   failure response, placeholder rows are unofficial (AIOStreams error rows are
   `externalUrl`-only, MediaFusion TV rows point at clips, NexoTV rows point at
   dead URLs).
4. **Unverified / not found:** any addon that builds error-video URLs with
   `title=`/`body=` query parameters (as recalled in the task brief). The
   closest verified analogue is Sootio's `/error?message=…`. If such URLs show
   up in Nuvio logs, capture the full URL and check its host against the addon
   list before assuming AIOStreams.

### Things that could not be verified

- The exact provenance of `?title=Too+many+requests&body=…`-style URLs (§1d).
- Meteor's source code (§4): placeholder behaviour is documented only through
  AIOStreams' filter and changelog.
- FixMyStremio (fixmystremio.com) appears to classify playback error videos in
  the wild ("do not infer a numeric status from a colored error video"), but
  the service is closed-source; not usable as a primary source.
- GitHub's code-search API could not be used unauthenticated for ecosystem-wide
  searches; discovery relied on cloning the major addons/clients directly and
  targeted web searches, so a small/obscure addon with `title=`/`body=`-style
  URLs may exist but was not found.
