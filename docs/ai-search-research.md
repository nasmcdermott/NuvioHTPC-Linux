# AI search — resolving "the South Park Wheel of Fortune episode"

The search bar today takes a title and forwards it verbatim to every enabled addon's search
catalog. It cannot answer a question. This page researches what it would take for a user to type
a sentence like *"the South Park Wheel of Fortune episode"* — or *"quote"* something they remember
— and land on the right episode. The answer to the flagship example is South Park S11E1,
**"With Apologies to Jesse Jackson"** (aired 2007-03-07), in which Randy Marsh reaches the Wheel
of Fortune bonus round with the puzzle `N_GGERS` under the category "People Who Annoy You"
([Wikipedia](https://en.wikipedia.org/wiki/With_Apologies_to_Jesse_Jackson)).

Constraints for the feature: it must be **free**; the default tier should work with **no account
at all**; and a user-supplied API key (BYOK) should be an optional escalation, not a requirement.

Everything marked "tested live" below was actually requested from the services on this machine
while writing this page (HTTP responses recorded in the traces); no claim about behaviour comes
from memory alone.

## How search works today

- `features/search/SearchRepository.kt` builds one catalog request per enabled addon that declares
  a `search` catalog (`buildCatalogUrl` with the `search` extra) and merges the rows. Media servers
  (Jellyfin/Plex) add their own rows.
- An addon's series *meta* document carries the full episode list with names and descriptions:
  `v3-cinemeta.strem.io/meta/series/tt0121955.json` returns 372 `videos` entries for South Park,
  each with `name` and `description` (tested live).
- The app already has a complete BYOK LLM client: `features/discover/DiscoverAiClient.kt` speaks
  OpenAI-compatible chat completions (any base URL — OpenAI, OpenRouter, Groq, Ollama, LM Studio,
  Gemini's compat endpoint) plus Anthropic's native Messages API, with per-key token accounting
  and `Retry-After` handling. Credentials live in `DiscoverAiSettings` (provider enum, `apiKey`,
  base URL, model, consent dialog). Groq is a preset choice there "so the user only pastes a key".

So the AI layer does not need a new HTTP client, a new key store, or a new provider abstraction.
What it needs is the *resolution* step: sentence in, `(show, season, episode)` out.

## The flagship query, tested live — and what fails

| Attempted resolver | Result |
| --- | --- |
| Cinemeta `catalog/series/search/south%20park.json` | **Broken** (tested live): every query — `south park`, `southpark`, even `zzzqqqxyz` — returns the *same* 100 metas (Supernatural, Family Guy, The Mentalist, …). "South Park" itself does not appear in the result set at all. The addon's search index currently ignores the query. |
| TVmaze `singlesearch/shows?q=south park` | Works instantly, no key: `id 112`, `externals.imdb tt0121955`. (`/shows/112/episodes` returns all 335 episodes with names, summaries, airdates.) |
| Keyword scan of those episode titles/summaries for "wheel"/"fortune"/"game show" | **Finds nothing but false positives.** The target episode's title is "With Apologies to Jesse Jackson" and its TVmaze summary says only "Randy Marsh is publicly chastised for using the N-word on national television" — it never mentions Wheel of Fortune. The scan instead hits episodes about wheelchairs and slot machines. Cinemeta's `description` for the same episode ("Randy uses the "N" word on live TV…") misses too. |
| Wikipedia full-text search (`generator=search` for `"the South Park Wheel of Fortune episode"`) | **Resolves it.** Top-5 hits include "With Apologies to Jesse Jackson", plus "Randy Marsh (South Park)" and "South Park season 11" (tested live). No account, no key. |
| Pollinations text API (`text.pollinations.ai`, no key) | Answers without any account, but **wrong both times** (first run: "S6E13", second run with a better prompt and `model=openai`: "S9E8 'You're on a Roll'", an episode that does not exist under that name) and rate-limited to 1 reply every 45 s per IP on the anonymous tier. Usable only as a curiosity, not as the layer the feature stands on. |

Two conclusions fall out of this table, both empirical:

1. **A no-LLM keyword scan cannot carry this feature.** The episode people describe ("the game
   show one") is described nowhere in the episode data; only its *title*, which is a reference to
   Jesse Jackson's apology over the incident, points at it. Machine-readable metadata alone does
   not contain the memory the user is quoting.
2. **Wikipedia's full-text search is the surprisingly strong no-account semantic layer.** Its
   articles are written by humans describing episodes in the same words people use, and the
   MediaWiki API is free, keyless, and already used in the app (sports artwork fallback) — so the
   licence position is already settled (CC BY-SA, credited in Settings › Licenses & Attribution).

## The no-account reality check

"AI search with no account" is achievable, but not the way one might assume:

- **Truly no account, no key:** TVmaze public API (no key exists at all — 20 calls / 10 s per IP,
  [tvmaze.com/api](https://www.tvmaze.com/api)), Stremio Cinemeta endpoints, the Wikipedia /
  MediaWiki API, iTunes Search API, AniList GraphQL (anime only), and — the only genuinely
  account-less *LLM* options — **local models through Ollama or LM Studio**, which the existing
  `DiscoverAiProvider.OpenAiCompat` provider already supports with zero new code.
- **Free account + key:** Groq (free plan ≈ 30 RPM / 1 000 requests/day / 8 K tokens-per-minute on
  the gpt-oss models — [inferenceapis tracker](https://inferenceapis.com/reference/rate-limits),
  [Groq rate limits](https://console.groq.com/docs/rate-limits)), Google Gemini free tier (Flash
  ≈ 10 RPM / 250 RPD — [ai.google.dev rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)),
  OpenRouter `:free` models (20 RPM / 50 requests per day, 1 000/day after a one-time $10 top-up —
  [OpenRouter docs via tracker](https://www.zerolimitai.com/free-llm-api/openrouter)), Cerebras
  (free-trial credits; `qwen-3.8-27b` at 300 RPM, no daily token cap —
  [Cerebras rate limits](https://inference-docs.cerebras.ai/support/rate-limits)), GitHub Models,
  Mistral. Every hosted LLM requires an account; there is no reputable keyless hosted LLM.
- **Keyless but unofficial:** Pollinations.ai is the main one. It worked but answered wrongly twice
  in a row for the flagship query and throttles anonymous users to ~1 reply/45 s. It should not be
  the default of anything; a last-resort fallback at most, flagged as unofficial and unstable.
- **Metadata APIs that need a key and cannot search episodes anyway:** TMDB (free key, requires
  account registration; `/search/tv` searches show names only —
  [developer.themoviedb.org](https://developer.themoviedb.org/reference/search-tv)), OMDb (key,
  requires account/email), Trakt, TVDB. The app already carries TMDB keys where the user supplied
  them, but TMDB adds nothing for episode-level text resolution that TVmaze + Wikipedia don't
  already give keylessly.

## Recommended architecture — the smallest isolated patch set

A new feature package, `features/aisearch/`, per the repo rule to prefer new Linux-specific /
feature-isolated files over touching shared ones:

1. **Query parse (local, no network).** Extract a candidate show name — quoted text first ("quote,
   the South Park episode, quote" is a real phrasing), then known-series heuristics, then the
   residual phrase kept as the *descriptive* part.
2. **Show resolution — TVmaze first** (`singlesearch/shows?q=…`): keyless, ranked well in the
   test above, and its `externals` carry the IMDb id the rest of the app navigates by
   (`tt0121955`). Fall back to any enabled addon's search catalog (which covers non-TVmaze
   content, e.g. Stremio-native catalogs), since Cinemeta's own search was broken at test time.
3. **Episode resolution, two stages:**
   - **Wikipedia full-text search** (`generator=search`) on the descriptive phrase, restricted to
     results whose title or extract names an episode, then **match the article title against the
     episode list** — Cinemeta's `meta/series/<imdb>.json` `videos` (already in-app) or
     TVmaze `/shows/<id>/episodes`. For the flagship query this hits S11E1 exactly.
   - **LLM escalation only if a key exists.** If the user has already configured Discover AI
     (`DiscoverAiSettings`), one small request turns the phrase into `{season, episode, title}` and
     the same episode-list match verifies it against real data — the model proposes, the episode
     list disposes, so a hallucinated episode number never reaches the UI. This reuses the
     existing credential, consent dialog, and rate-limit handling; it is *not* a new key store.
4. **Result routing.** The resolver returns `{showTitle, imdbId, season, episode, title}`;
   navigation opens the existing series details screen (which is id-driven via
   `MetaDetailsScreen`'s IMDb/TMDB identity) with the season/episode pre-selected. No changes to
   the details feature are needed to *display* it; only an entry point (a "search by description"
   mode on `SearchScreen`) and the resolver package itself.
5. **Settings / BYOK.** No new storage. Tier 0 (Wikipedia + TVmaze) is on with no configuration.
   The AI tier reuses the Discover AI credential exactly as the recap and trivia features already
   do — each an independent toggle, billed to the user's own key — following the same pattern in
   `DiscoverAiSettings`. Someone who wants no AI at all gets the keyless pipeline; someone who
   wants the smarter tier pastes one key they may already have entered.

### Why not "just add an LLM search box"

Because the LLM is the *weakest* link in the keyless chain and the *most optional* link overall:
Wikipedia answered the flagship query correctly with no account and no rate limit worth caring
about, while the keyless LLM endpoints tested answered wrongly and throttled. The LLM earns its
place only where its key already exists in the user's settings, as a proposal step verified
against the episode list — not as the foundation.

## Comparison table

| Service | Account? | Key? | Free | Limit | Episode-level resolution | Fits existing code |
| --- | --- | --- | --- | --- | --- | --- |
| Cinemeta (v3-cinemeta.strem.io) | no | no | yes | unspecified (Stremio-hosted) | meta has full episode list; **search broken at test time** | yes — already the app's metadata path |
| TVmaze public API | **no** | **no** | yes, CC BY-SA | 20 calls/10 s per IP ([docs](https://www.tvmaze.com/api)) | show search excellent; episode list by show; summaries thin | yes — raw HTTP like any addon |
| Wikipedia MediaWiki API | **no** | **no** | yes, CC BY-SA | generous, be polite ([docs](https://www.mediawiki.org/wiki/API)) | **full-text search resolves descriptive queries** | yes — already used (sports artwork) |
| Pollinations text | **no** | no (optional key) | yes | ~1 reply/45 s per IP anonymous | answered wrong twice on the flagship query | via OpenAiCompat base URL, unofficial |
| TMDB | yes | yes | yes | ~50 req/s guidance | show-name search only, no episode text search | already integrated (user key) |
| OMDb / Trakt / TVDB | yes | yes | free tier | per-service | no episode text search beyond titles | key patterns exist, adds nothing here |
| AniList GraphQL | no | no (token optional) | yes | 90 req/min | anime only | niche |
| Groq | yes | yes | free plan | ≈30 RPM / 1 K RPD / 8 K TPM (gpt-oss) | strong semantic proposals | **already a preset provider** |
| Gemini (AI Studio key) | yes | yes | free tier | ≈10 RPM / 250 RPD (Flash) | strong; via compat endpoint | already supported via OpenAiCompat |
| OpenRouter `:free` | yes | yes | yes | 20 RPM / 50 RPD (<$10 bought) | varies by model | already supported via OpenAiCompat |
| Cerebras | yes | yes | free trial credits | 300 RPM (`qwen-3.8-27b`) | fast, decent | via OpenAiCompat base URL |
| Ollama / LM Studio (local) | **no** | **no** | yes, fully local | your hardware | model-dependent | **already supported via OpenAiCompat** |

## Open questions and uncertainty

- Cinemeta's search was returning a query-independent 100-result page during testing; that may be
  a transient outage rather than permanent behaviour. The design above does not depend on it —
  TVmaze is the keyless show resolver, and addon catalogs remain a fallback — but it is worth
  re-checking before implementation, because the same failure would also degrade ordinary title
  search, which is a bug in the existing feature, not a research finding.
- Wikipedia's full-text search will not always surface an episode article for every phrasing; the
  design must handle "no confident match" honestly (show the LLM-tier prompt or a plain-text
  fallback, never a wrong episode).
- The Groq/Gemini/OpenRouter free-tier numbers move month to month; they are cited here as
  order-of-magnitude, and the app already handles 429 + `Retry-After` generically, which is the
  durable part.
