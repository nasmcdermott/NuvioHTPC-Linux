# Where the sports data comes from

The Sports tab and the Live TV sport badges read from public feeds. This page lists them, says what
each one is used for, and records the licence position, because two of them are other people's
services and one of them is a third-party GitHub repository that has no licence at all.

## Sources in use today

| Source | What it feeds | Key | Terms |
| --- | --- | --- | --- |
| **ESPN site API** (`site.api.espn.com/apis/site/v2/sports/…`) | Everything on the Sports page: scoreboards, clocks, team and athlete records, summaries (scoring, box score, team stats, leaders, injuries, news, standings), odds, broadcasts, league and team logos | none | Unofficial public endpoint. No published licence or terms of use for the API; it is used by many third-party clients and can change or disappear without notice. Attribution is in Settings › Licenses & Attribution. |
| **TheSportsDB** (`thesportsdb.com/api/v1/json/123`) | Fight cards for the live score pop-ups (boxing); match-day pictures, sport artwork and team colours for Live TV's sport badges | the public free key `123` | Free tier of a commercial API. The free key is rate limited (the client self-throttles to one request every two seconds and backs off on HTTP 429). Attribution is in Settings › Licenses & Attribution. |
| **Wikipedia / Wikimedia** | Fallback team and club artwork | none | CC BY-SA 4.0, credited in Settings › Licenses & Attribution. |

The sports *catalog* (`features/sports/SportsCatalog.kt`) lists only leagues whose ESPN path answers.
That is deliberate: a league whose path fails shows a chip that is permanently empty. Two examples
worth remembering — `boxing/*` answers HTTP 400 on this API, and `cricket/8039` (which the catalog
used to carry) answers HTTP 502; both were removed rather than left broken.

## `homelessbrian/nuvio-data` — not used, and why that matters

The reference Linux/TV implementation of this sports page (`NuvioPlusIPTV`, the project the Sports
tab was ported from) depends on a third-party repository,
`https://github.com/homelessbrian/nuvio-data`, for a large part of its non-ESPN coverage. That
repository:

- runs GitHub Actions jobs that call **TheSportsDB with the author's own paid key** and publish the
  answers as plain JSON (`sportsdb/index.json`, `sportsdb/<league>.json`, TV listings, colours,
  per-game details, players) plus a shared ESPN scoreboard cache (`espn/*.json`);
- also mirrors `sporting-events.org` fixture dates, the HockeyTech and MLB Stats APIs, and a
  `wrestling.json` built from TheSportsDB;
- **has no licence file**. No LICENSE, no terms, no statement of permitted use. Under default
  copyright, that means no rights are granted to copy, redistribute or depend on it.

That is the objection, and it is a fair one:

1. **No licence means no permission.** Reading it at run time is not "vendoring", but it is still a
   dependency on someone else's repository with no grant — including for the data derived from a paid
   TheSportsDB plan.
2. **It can vanish or change.** The app would break for every user the day the repository is
   renamed, made private or restructured, with nothing we can do about it.
3. **It moves the licence question, it does not answer it.** TheSportsDB's own terms still apply to
   the data; the GitHub job is one hobbyist's arrangement with them, not a licence we hold.

So this fork does **not** read `nuvio-data`, and the Sports page needs no attribution for it. If that
ever changes, it changes twice: the runtime dependency *and* an entry beside ESPN and TheSportsDB in
Settings › Licenses & Attribution, plus a note here saying exactly which files are read and under
what permission.

### What it would buy us

The `nuvio-data` route is attractive for one real reason: **sports ESPN does not carry**, and a
shared cache that spares every client from polling ESPN. The catalog here carries 55 ESPN
competitions; the reference implementation carries ~200 competitions across fighting, darts,
snooker, MotoGP, cycling, horse racing, Gaelic games, handball, netball, rugby league and non-league
football.

### The alternatives, in the order this fork should consider them

1. **Stay on ESPN, add leagues.** Free, no new dependency, no new licence question. This is what
   `SportsCatalog.kt` does, and it is why PFL, Bellator, RIZIN, NRL, AFL, IPL, WTA, LPGA, IndyCar and
   the rest were added. Ceiling: no boxing, no darts, no snooker, no MotoGP, no handball, no Gaelic.
2. **Call TheSportsDB directly, per league.** No third party in between, but the free key throttles at
   roughly one request every two seconds, and each additional league is another request on every
   refresh round for every user. Fine for the couple of lookups already made (boxing cards, badges);
   not fine for a page that polls a league list every 30 seconds. It would need a real key, which is
   a cost and a signup the user would have to own.
3. **Ask `homelessbrian` for a licence**, or mirror the job ourselves (our own repository, our own
   TheSportsDB key, our own published JSON). Same benefits, clean rights. This is the only route that
   gives non-ESPN sports at scale with a defensible licence position, and it is a project of its own
   — hosting, a key, a scheduled job and its failures.
4. **Depend on `nuvio-data` as it stands.** Not recommended while it carries no licence.

Until one of 2–4 is chosen deliberately, the Sports page stays ESPN-only and says so in the
attribution list.

## A one-second page: what the providers actually publish

Measured on 24 Nov 2026 from a Linux workstation, one 60–90 s sample per address at one request a
second, recording when the payload changed and the CDN's own freshness headers:

| Address | `cache-control` it answers with | What was observed |
| --- | --- | --- |
| `site.api.espn.com/…/scoreboard` (what the page polls) | `max-age=9` | the clock string moved about every 10 s; a score change showed up once in a 50 s window |
| `site.api.espn.com/…/summary?event=` (the game page) | `max-age=9` | same generation of the data |
| `site.web.api.espn.com/apis/v2/scoreboard/header?sport=…&league=…` | `max-age=2` … `max-age=7`, varies per answer | **the fastest ESPN surface found**: scores, `fullStatus.clock`, period and `situation.lastPlay` for a whole league in one request; a new play id appeared within ~3 s (NHL), ~10 s (NBA); the clock string is still 10 s granular. One call covers every league of a sport for soccer |
| `sports.core.api.espn.com/…/competitions/{id}/status` | `max-age=10` | 3 changes in 90 s: the per-game status is as coarse as the scoreboard |
| `sports.core.api.espn.com/…/competitions/{id}/plays?limit=10` | `max-age=4` | changes with each play; the finest-grained ESPN address |
| `api-web.nhle.com/v1/score/{date}`, `/gamecenter/{id}/play-by-play`, `/right-rail` | `max-age=19, s-maxage=19` | new plays at +15.7 s and +35.7 s; **NHL's own floor is 19 s** |
| `statsapi.mlb.com/api/v1.1/game/{pk}/feed/live` and `…/diffPatch` | `max-age=10` | the payload carries `metaData.wait: 10` — the server telling clients to come back in 10 s; `diffPatch` answers with `gameEvents`/`logicalEvents` and a `timeStamp` to pass back |
| `cdn.nba.com/static/json/liveData/…` | — | HTTP 403 from this host (needs a home connection or an app-shaped request to test) |
| `api.sofascore.com/api/v1/…` | — | HTTP 403 (Varnish); unofficial and against their terms — not a route to take |

Three conclusions follow, and they decide the design:

1. **No free source publishes a per-second clock.** The providers say so themselves: ESPN's CDN holds
   an answer for 2–10 s, the NHL sets `s-maxage=19`, and MLB hands out a `wait` of 10. Asking faster
   than the `max-age` returns the same bytes.
2. **A one-second display therefore has to be rendered locally** between authoritative reads. That is
   what Apple Sports does: the scores arrive as pushes (Live Activities are updated over APNs) and the
   clock ticks on the device, so the display is per-second without a request per second. It is also
   what the page can do without any new provider.
3. **The authoritative read can still get a little faster than today** — not below the provider's
   floor, but from 10 s to the 2–7 s the header widget allows, for the handful of leagues that
   actually have a game in progress.

### What the clock needs to tick locally, and where it breaks

ESPN gives `fullStatus.clock` (a number), `period`, and `status.type` (`state`: `pre`/`in`/`post`,
plus `detail` such as `Halftime` or `End of 3rd`). The number is not the same thing in every sport,
which was checked rather than assumed:

- **Basketball, hockey, football (US)** — seconds *remaining* in the period ("3:53 - 4th Quarter"
  reads `clock: 233`).
- **Soccer** — seconds *elapsed* in the match: a finished Premier League game reads `clock: 5400`
  with `displayClock: "90'+5'"` and `period: 2`. Stoppage time is in the display string only.

The rules an interpolator has to obey, or it will lie:

- **Tick between reads, resync on every read.** A poll that returns a different clock replaces the
  estimate; nothing drifts longer than one poll interval.
- **Hold when the clock is stopped.** If two consecutive reads ten seconds apart report the same
  clock, the clock is not running (a timeout, a stoppage, a review) — the ticker stops instead of
  counting down through it.
- **Never run past the period.** Period length is per sport (12 min quarters, 20 min periods, 45 min
  halves), so the ticker stops at zero and waits for the next read to say what happened; it never
  rolls into a period that has not started.
- **Never show a second the provider has not reached.** The estimate is capped at the last read's
  clock, so the page is never *ahead* of the source — the failure mode is a clock that stops for a
  moment, not one that invents time.

That is the honest shape of "as live as Apple Sports" on ESPN's data: the display moves every second,
while the score itself cannot be newer than ESPN's own 2–10 s publication.

### Going below the provider's floor, if it is ever worth paying for

Only a push feed does that — a socket the provider writes to when something happens, rather than an
address we ask. Everything in this table is a commercial product whose documentation is marketing
copy; the numbers are what to ask each one for, not verified facts:

| Route | Transport to ask about | The question that decides it |
| --- | --- | --- |
| **Sportradar** (official NBA/NHL/MLB/data partner) | push/streaming feeds beside the pull API | in-play latency SLA per sport, and whether the trial covers the leagues in the catalog |
| **SportsDataIO** (now DCSG) | their real-time push feeds | same, plus price for the 55 competitions here |
| **Sportmonks** | webhooks over in-play | their published in-play interval (seconds per update) per league |
| **Enetpulse / LSports** | real-time/highlight feeds used by bookmakers | cost, and whether a single developer can license it at all |
| **Genius Sports / Stats Perform** | enterprise distribution | almost certainly out of scope for a desktop app |

What those buy is the *score* arriving in about a second. Nothing in this repository pays for that
today, and the free route above already makes the page look live.

### If this is built, this is where it goes

- `SportsLiveStatus.kt` already re-reads a card's own status every 5 s (`POLL_MS`); the header widget
  is the cheaper address (one request per live league, all of its games) and is allowed at 2–7 s.
- The interpolator belongs beside it as a pure function — `clockAt(lastRead, nowMs, sport, period)`
  — so the hold/stop/cap rules above are unit-testable without a window, the way
  `pollDueThisRound`/`sportsBandChipAt` already are.
- The page's wording stays as it is: nothing here changes what "data from N minutes ago" means, and a
  live card must not claim a fresher score than the last read it actually got.

## Adding a league

1. Check the path answers: `curl -s -o /dev/null -w '%{http_code}' "https://site.api.espn.com/apis/site/v2/sports/<path>/scoreboard?limit=2"` must be `200`, and the response's `leagues[0].logos[0].href` is the logo to use (every league needs one — `SportsCatalogTest` enforces it).
2. Check the logo URL answers `200`; ESPN returns useful logos for some paths and 404 for others.
3. Add the `League(...)` entry: `id`, `sport`, `path`, `shortName`, `guideWords` (what TV guides call
   it), `minutes` (usual running time, which bounds "is it still live"), and `regions`.
4. Add its id to the list at the end of `SportsCatalogTest` only if the page would be wrong without it.
5. `./gradlew :composeApp:desktopTest --tests 'com.nuvio.app.features.sports.*'`.

## Request volume

The page asks ESPN for the selected day's scoreboard, per league, at one of three paces:

- **live** (a game is in progress): every 10 s — `Freshness.LIVE_MS`, the interval the page's refresh
  round runs at. The score and the clock are read from the provider and never counted locally, so 10 s
  is how fresh a live game can look today; the section below measures what the providers actually
  publish, and what a one-second clock would cost.
- **near** (nothing in progress, but a game starts or finished within six hours): once a minute —
  `NEAR_ROUNDS`.
- **nothing on that day**: every five minutes — `COLD_ROUNDS`; an empty scoreboard does not change.

Only leagues in the first group are asked every round, so the catalog can be long without multiplying
what ESPN sees. Two other loops run beside it: a live card's own clock comes from the small per-game
status address every 5 s (`SportsLiveStatus.POLL_MS`), and an open game page re-reads its summary
(box score, plays, stats — a hundred times the size of a scoreboard) every 30 s
(`Freshness.LIVE_DETAIL_MS`).

Three things keep that traffic acceptable for an unofficial endpoint:

- **No burst.** Opening the page asks for every league at once, so the scoreboard requests queue
  through a semaphore of four (`SportsRepository.feedSlots`). Before that, 50-odd simultaneous
  requests made ESPN answer a handful with a rate limit, which the page reported as "could not
  refresh" over data that was seconds old.
- **Backoff.** A league whose last attempt failed is not asked again that round; it drops to the
  minute pace until it answers (`DayState.lastAttemptMs`, `pollDueThisRound(..., failing = true)`).
  Without it a failing league looks "never fetched" and is retried every round, which only extends
  a rate limit.
- **A browser-shaped request.** ESPN requests carry a desktop Chrome `User-Agent`
  (`ESPN_HEADERS` in `EspnSportsFeed`), as the reference app's do; a bare library agent is more
  likely to be treated as a scraper.

The "could not refresh" note only speaks up when the failure can cost something: a competition whose
games are on screen, or one the viewer follows. A league with nothing on that day failing changes
nothing, and the note never says "showing just now" — it only quotes an age when the data is
actually old (`staleNoteText`).

See the `LaunchedEffect` in `SportsScreen.kt`, `pollTierFor`/`pollDueThisRound` beside it, and
`SportsRepository.Freshness` for the numbers.
