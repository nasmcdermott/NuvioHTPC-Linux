# Expansive credits on the music lyric page — research

Question: how would an expansive credits view on the Now Playing (lyric) stage work, with every
option reachable by keyboard *and* gamepad/controller, on `linux-tv-sports`? Research only; no code
changed.

## The data already exists — no new network code

The fork's `fork:credits` work (album page, "Who made it" lens) built the whole credit pipeline:

- `music/library/PeopleCredits.kt` — pure parsing and modelling: `Credit` (person, MBID, kind
  Writer/Producer/Player/Engineer, detail like "guitar", track number 1-up, 0 = whole album),
  `creditPeople()` (people with summed credits, most-credited first), `creditCounts()`,
  `elsewhere()` (the other library albums a person is credited on), encode/decode, `creditsKey()`.
- `music/library/MusicSourceClients.kt` — `MusicBrainzClient.peopleCredits(artist, album)` (and
  `trackCredits()` for the per-track "Written by … · Produced by …" line). It walks up to
  `CREDIT_EDITIONS` editions until one has per-song credits. Two paced MusicBrainz calls per
  edition; results are kept forever (`CreditsStorage`, key `people3|artist|album`; a "no such
  release" answer is kept 30 days).
- `music/library/PeopleCreditsBook.kt` — the store front-end: `peek()` (no request),
  `load()` (asks when nothing is kept), `allKept()` (whole library, for the "elsewhere" panel),
  `changes` flow. The background facts filler already asks for every library album, so a track
  from a library album usually has credits on disk **before** the page is ever opened.
- `music/library/TrackCredits.kt` — the per-track one-line form, ideal for a strip under the
  lyrics ("Written by A, B · Produced by C").

So "expansive credits" is a UI task over data that is already fetched, cached and tested.

## Where the playing track's credits come from on the stage

`MusicNowPlayingScreen` already passes `playingVideoId` and `album: MusicAlbum?` into
`NowPlayingLinerBox` (`MusicNowPlaying.kt` ~line 565). `NowPlayingBooklet.kt` turns the video id
into a track number with `bookletTrackNumber(album.tracks.map { it.videoId }, playingVideoId)`.
The song's own credits are then `AlbumCredits.credits.filter { it.track == n or it.track == 0 }`
(whole-album credits count too), and the one-line form is `parseMbTrackCredits`'s `map[n]` from the
same lookup — or filtered straight out of the `Credit` list, which needs no second request.

## Where the entry point can live

Three placements, in the order they were considered:

1. **A page in the existing booklet deck** (`NowPlayingBooklet.kt`, `LinerPage.Credits`). Smallest
   diff, but the deck belongs to the right-hand liner box, not to the lyric page, and "expansive"
   asks for more room than the glass box has.
2. **A Stage overlay/flip of the lyric pane** — a `Credits` chip or an Options row swaps the
   lyrics pane (`LyricsPane` in `MusicNowPlaying.kt`) for a credits deck of the same bounds, the
   way the pane already swaps Scroll/Calm/Loud styles. This *is* "credits on the lyric page".
3. **Fullscreen credits** via the `MusicVideoView.Fullscreen` precedent (`MusicPlayback.setVideoView`),
   like an end-credit crawl. Expansive indeed, but the most new plumbing (a new view mode, HUD,
   Back handling).

Recommendation: **2**, with the deck machinery below, plus a per-song credits line that can also
sit in the lyric pane's foot band regardless of the deck. Option 3 can be layered later on the same
composable.

## The view: a credits deck copying the booklet's mechanics

`BookletDeck` already solves every problem an "expansive credits" page has:

- pages of fixed bounds, each shrunk as a whole to fit (`FitToHeight`) so nothing scrolls or jumps;
- `Left`/`Right` turn pages (`turn()`, returns false at an end so the key moves focus on — the
  two-way handoff rule), `Up`/`Down` walk a page's rows (`walk()`), Select acts;
- `NavPill` arrows, page dots, and a `LinerHintRow` telling the player the keys;
- `AnimatedContent` slide with `Motion` reduced to none.

A credits deck would have:

- **Song page** — the one-line "Written by … · Produced by …" plus this song's credited people.
- **Kind pages** — Writers / Producers / Players / Engineers (the `CreditKind` order
  `CreditsSidePanel` already lists), one row per `CreditPerson`: name, `what` ("Guitar, backing
  vocals"), `songsText` ("All songs", "6 songs"). The `CreditRowUi` row shape from
  `AlbumLenses.kt`'s `CreditsSidePanel` mirrors this exactly.
- **Person page** — their tracks on this album and their `elsewhere()` records; Select opens the
  album (`onOpenAlbum`, the Timeline page's move) or jumps to a track (`onJumpToTrack`).

## Keyboard *and* gamepad — what makes a surface pad-navigable

The app's rule (`GamepadAction.kt`): the pad **synthesizes the keystroke** of the action it is
bound to; it has no path of its own. So whatever is navigable by keyboard is navigable by pad, and
a user's rebind moves both at once. There is nothing pad-specific to write — but the keyboard side
must go through the funnels or the pad's rebinds will not follow:

- `core/ui/NavAction.kt`: read `event.navAction()` (or `navActionUnlessTyping()`), never compare
  `event.key == Key.DirectionLeft`. AGENTS.md locks this ("keys go through the funnels",
  `NavigationActionRatchetTest`); a raw-key comparison is a ratchet failure that quietly ignores
  rebound and pad keys.
- `musicKeyFocusRing(...)` on every focusable (the Music section's focus visibility),
  `musicKeyboardRoot` on the page root, `KeyRouteLayer` for popups — as `StageOptionsMenu` does.

Two ratchet violations found in the neighbours we would copy — fix them in the new code, and
ideally in the copied-from files too:

1. `BookletDeck`'s `onKeyEvent` compares `event.key == Key.DirectionLeft/Right/Up/Down/Enter`
   directly. Rebinding Move Left or Select in Settings > Keyboard shortcuts leaves the deck on the
   historical keys; a pad bound the same way works today only because the pad's default binding
   synthesizes those same arrow keys. A deck rewritten with `event.navAction()` inherits rebinds
   and stays testable through the funnel.
2. `StageOptionsMenu`'s close-on-Left compares `event.key == Key.DirectionLeft` as well.

Also worth noting: the deck is `focusable()` and takes arrows itself; at either end `turn()`/`walk()`
return false so the router moves focus out — keep that contract so Left at the first page steps
back to the stage's controls, per the navigation model in AGENTS.md.

## Proposed shape of the change

New file, e.g. `music/NowPlayingCredits.kt` (fork, FORK UI header like its neighbours), containing:

- `CreditsDeck(album, credits, trackNumber, onOpenAlbum, onJumpToTrack, …)` — the pages and keys;
- pure helpers in `music/library/PeopleCredits.kt` (already the home of pure credit logic):
  `trackCreditsOf(AlbumCredits, trackNumber)` and page-building functions, so they are unit-tested
  without Compose, as the existing file's comment prescribes.

Wiring:

- `MusicNowPlayingScreen`: a `lyricsOr` swap in the three-panel and two-column branches (the
  `lyricsPane` lambda sites at ~line 521 and ~590), plus a Credits chip beside the lyric sync pill
  or a row in `StageOptionsMenu` ("Credits · On/Off" fits the menu's row/pill pattern; a third
  row is free in its right column, which currently holds only a `Spacer`).
- Fetch: `PeopleCreditsBook.peek(artist, album)` at open; `load()` when missing (foreground
  priority, as the album page does). No new settings storage needed if the choice is session-only;
  a remembered on/off would follow `MusicLibrarySettings` as `stageBoxView` does.

## Tests (AGENTS.md: every navigation change ships a guard)

- Pure: `trackCreditsOf`, page building, `elsewhere` reuse — extend the existing
  `PeopleCredits`-pattern tests in `desktopTest`.
- Compose offscreen: the deck's key walk — Left/Right turn, Up/Down walk, Select opens, and that
  keys arrive through `navAction()` (a mutation-verified test fails when reverted to raw-key
  comparisons). Follow `NavigationActionRatchetTest` for the funnel contract.

## Uncertainties

- Whether "expansive" should eventually mean fullscreen; the deck composable would carry over, so
  this is not locked in now.
- `StageOptionsMenu`'s 2×3 grid: a Credits row fits the empty right-column slot, but the menu
  closes on an 8 s idle timer, which is fine for a toggle.
- Albums not in the library (YouTube Music one-off plays): credits depend on artist+title matching
  a MusicBrainz release; the page must degrade to "No credits found for this album" the way the
  album lens does.
- The `sports-test-hub` worktree has uncommitted changes in `NativePlayerBridge.kt`,
  `NativePlayerController.kt` and `player_bridge.cpp` from other work; this feature touches none of
  them.

## Is there a more extensive credits API than MusicBrainz? (researched 2026-11-25)

Checked against live documentation and one captured real response (Music-Assistant's Tidal v2
fixture).

**TIDAL Credits API — the recommendation.** Official, at `openapi.tidal.com/v2` (JSON:API). A free
TIDAL account (no subscription) registers a developer app at developer.tidal.com and gets a client
id + secret; the client-credentials flow (`POST auth.tidal.com/v1/oauth2/token`, `grant_type=client_credentials`)
mints a catalog-scoped bearer token — no user data, exactly what credits need. A track lookup by
search (`/v2/searchResults/{query}?include=tracks`, match artist + title + duration ±2 s, like the
lyric sources match) returns the track with a `credits` relationship; the included credit resources
are simply `{name, role}` — the fixture for "7 Years" holds **15 credits on one track** (composers,
performers, producers), where MusicBrainz's best edition usually names a few writers and a producer.
Roles arrive as clean strings that map straight onto the existing `Credit(person, kind, detail,
track)` model. Cost per song: one token mint (cacheable ~24 h), one search, one credits read.
The user supplies the client id + secret in Music settings — the same optional-key pattern as
fanart.tv, TheAudioDB and Last.fm already use.

**Apple Music (amp-api) — the best data, but unofficial and heavier.** Apple's official MusicKit
API does not expose credits at all. The web player's `amp-api.music.apple.com` does, but it wants
the web player's bearer token and (reliably) a media-user-token, meaning the user's own Apple
Music session; token-scraping breaks without notice and sits in Apple's ToS grey zone. The fork
already tolerates that for YouTube (InnerTubeX), but an Apple account requirement is a bigger ask
than a Tidal app registration, and it breaks more often. Rank it below Tidal.

**Discogs — extensive but edition-risky.** Personal access token (free), 60 requests/minute, and
per-release plus per-track `extraartists` with free-text roles (session players, engineers,
remixers — genuinely expansive). But its tracks are physical-release positions ("A", "B2", "1-02"),
roles are free text needing a mapping into the four kinds, and picking the wrong edition of an
album quietly gives the wrong credits. Better as a later "elsewhere"-trivia source than as the
primary credits source.

**Not usable for credits:** Spotify (no public credits API), YouTube Music (none), Deezer
(already integrated; contributors only, sparse), AllMusic (scraping only), Wikipedia/Wikidata
(already used by the booklet; too sparse).

**If Tidal is added:** keep MusicBrainz as the no-key default and offline-capable fallback, and
stack the sources the way the lyric sources stack — Tidal when the user's client credentials are
set, MusicBrainz otherwise; `AlbumCredits` gains a source field (so a cached answer carries its
provenance and the deck's note says "Tidal" or "MusicBrainz", as the lyric view names its source),
and the Tidal client follows the `MusicSourceClients.kt` pattern (paced, cached token, pure parsers
under `music/library/` with tests).
