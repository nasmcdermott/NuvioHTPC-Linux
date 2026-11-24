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
  round runs at. The clock and score are read from the provider, never counted locally, so this is
  how fresh a live game can look.
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
