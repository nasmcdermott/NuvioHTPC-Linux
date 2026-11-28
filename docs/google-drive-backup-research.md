# Google Drive backup — research findings

Research for the fork's Google Drive backup (`features/backup/`): how the current desktop
implementation works, what the upstream companion app (NuvioPlusIPTV) does, the Drive API rules the
choices must live inside, which settings domains the backup covers, and what existing Linux clients
offer. Everything here is from primary sources: this repo's source, the NuvioPlusIPTV source
(`homelessbrian/NuvioPlusIPTV`), upstream `UmbraProjects/NuvioDesktop`, other Linux Drive clients, and
developers.google.com.

## 1. The current desktop implementation (as of this research)

Two files per Google account, both in Drive **root**:

| File | Contents | Who else reads it |
| --- | --- | --- |
| `Nuvio Live TV backup.json` | Live TV settings in NuvioPlusIPTV's DataStore envelope (format 1: `savedAt`, `deviceId`, `device`, `data{key:{t,v}}`). Desktop keys are laid over the remote map, so unknown entries survive a round trip (`LegacyIptvBackup`). | NuvioPlusIPTV (Android TV/phone) — that is the premise, see §3 |
| `Nuvio Desktop Settings backup v1.zip` | Every `*.properties` store from the machine dir (`MACHINE_STORE_NAMES`) and the active account folder, **credential-free** — the same builder the local Settings → Backup file uses (`buildSettingsBackupArchive`), so the two paths cannot drift. | This app only |

Mechanics (`GoogleDriveBackup.desktop.kt`):

- OAuth 2.0 **authorization code + PKCE over a loopback redirect** (`http://127.0.0.1:<random>`),
  browser or `xdg-open` hands off; refresh token stored via `secret-tool` (libsecret keyring) with a
  0600 properties-file fallback (`LinuxDriveTokenStore`), deliberately outside every settings store.
- Client config from `NUVIO_DRIVE_CLIENT_ID` / `NUVIO_DRIVE_CLIENT_SECRET` / `NUVIO_DRIVE_SCOPE`
  (env or system property; never in source). Default scope: `https://www.googleapis.com/auth/drive`.
  The secret is optional — PKCE alone is accepted for installed apps.
- Drive REST v3 over `HttpURLConnection`: find by name (`spaces=drive … 'root' in parents`),
  multipart upload for create, `uploadType=media` PATCH for update, `alt=media` download.
- Backup is **manual** (Backup now / Restore now with a domain picker). No auto-sync, no merge;
  a `localHashAtBackup` in the token store drives the "remote is older than local settings" warning.
- Restore re-uses the settings-import staging path (`pending-settings-import.zip`, applied by
  `applyPendingSettingsImport` at next launch before any store opens).

## 2. Upstream ground truth

### Upstream NuvioDesktop (`windows-tv-adaptive`)

Has **no** Google Drive backup of any kind (sparse-checked out and searched: no
`GoogleDrive*`, `NUVIO_DRIVE*`, `drive.appdata` references in `composeApp/src`). The whole feature is
fork-desktop-native, so there is no upstream backup UI to stay compatible with — only the Live TV
file's format, which must match NuvioPlusIPTV.

### NuvioPlusIPTV (`LiveTvDriveSync.kt`)

The companion Android app (TV + mobile). Its Drive sync differs from ours in several deliberate ways:

| Aspect | NuvioPlusIPTV | This fork (desktop) |
| --- | --- | --- |
| Primary location | **`appDataFolder`** (hidden, per-app; scope `drive.appdata`), with a fallback chain `drive.appdata` → plain `drive.file` file in Drive | **Drive root**, scope `drive` |
| Sign-in | **Device-code flow** ("TVs and limited input devices": code + QR, approve on phone) | Loopback + PKCE in the system browser |
| Client credentials | Baked into `BuildConfig` (`GOOGLE_DRIVE_CLIENT_ID/SECRET`) | Environment variables |
| Sync trigger | **Automatic**: push debounced 8 s after any settings change; pull at app start; plus manual Back up now / Restore now | Manual only |
| Multi-device conflicts | **Three-way merge** against a sealed local "base" copy of the last-synced data (`livetv_drive_base.json`), with per-key, per-item and per-source merges and tombstones for removed sources; "newest wins" only as a last resort | None: desktop overwrites the keys it manages; unknown entries are preserved |
| Empty-data guard | Never writes a source-less setup over the backup; same guard exists on restore | Same guard, both directions |
| Skip-if-unchanged | SHA-256 of the exported data vs last pushed | `localHashAtBackup` warning only |
| Per-device state | `SYNC_EXCLUDED` keys (last channel, recents, buffer, audio passthrough, frame-rate, recording storage…) never leave the device | N/A (desktop keeps such state in per-machine stores) |
| Other backup paths | Nuvio Account Sync (newer, encrypted server-side), `.nptv` backup file, phone↔TV QR transfer | Not implemented, and not needed on desktop |

Envelope compatibility: our `format 1` envelope, `FILE_NAME` (`"Nuvio Live TV backup.json"`),
`deviceId`/`device`/`savedAt` fields and typed-entry shapes match the Android source exactly. The
Android `exportForSync` zeroes per-download state (`lastUpdatedMs`, `channelCount`, `serverTimezone`…)
when exporting; our desktop key `nuvio_desktop_iptv_settings` is an unknown key to Android and is
carried through its merge untouched — but only while a *base* exists on the TV (see §5, G4).

## 3. Drive API rules the choices sit in (developers.google.com)

- **`appDataFolder` is per-app and cannot be shared.** "Only the application that created the data in
  the appDataFolder can access it" (Drive API › Store application-specific data). It cannot be
  shared, moved, or trashed. So a desktop app can **never** read the Android app's appdata backup,
  whatever scope it holds. The only place two differently-signed apps can meet is a named file in the
  user's Drive.
- **`drive.file` is per-file *and* per-app**: access only to "files that you open with an app or that
  the user shares with an app" (Choose Google Drive API scopes). A file created by the *desktop's*
  OAuth client is invisible to NuvioPlusIPTV in its `drive.file` fallback mode, and vice versa.
  **Consequence:** the Android↔desktop handoff through a shared file works only in the direction
  Android→desktop while Android runs in its FILE-fallback mode and the desktop holds a broad scope.
  Whether Google associates per-file access by OAuth *client* or by Cloud *project* decides the
  desktop→Android direction; the clients are different projects, so it must be verified empirically
  (see §5, G2) rather than assumed either way.
- **`drive` is a *restricted* scope.** Non-sensitive scopes (`drive.appdata`, `drive.file`,
  `drive.install`) need only basic verification; restricted scopes (`drive`) for a publicly
  distributed app require full verification (and for some, a security assessment), and unverified
  restricted-scope use is capped (100 users per project). Since the desktop client is configured by
  env var, today's model is "each user (or the fork) brings their own Google Cloud project", which
  sidesteps verification — worth stating explicitly in user-facing docs, because a project whose
  consent screen is in **"Testing" publishing status issues refresh tokens that expire in 7 days**
  (Using OAuth 2.0 to Access Google APIs › expiration). That will present as weekly re-auth and is
  the most likely first support question.
- **Loopback + PKCE is the right desktop pattern.** Loopback IP redirects are deprecated only for
  *mobile* apps; for desktop apps the docs prescribe "open the system browser and supply a local
  redirect URI (such as a loopback IP address)" and say "Always use PKCE… Desktop apps are public
  clients". NuvioPlusIPTV uses device-code because a TV has no browser; desktop should keep loopback.
- **Refresh-token limits**: 100 refresh tokens per account per client (oldest silently invalidated) —
  relevant only to heavy re-connect testing.
- **Uploads**: multipart is recommended for files ≤ 5 MB; resumable above that. Both files here are
  far below that in practice (settings zip of small properties files), so the current multipart
  create + media PATCH is correct; if a settings archive ever grew, switch to `uploadType=resumable`.
- **No ETag/If-Match in v3.** Drive v3 removed ETags; `fields=etag` is an invalid selection and
  conditional PATCH is not supported. Optimistic concurrency must be app-managed: either a local
  base copy of the last-synced data and a three-way merge (what NuvioPlusIPTV does), or revision
  checks via `files.get(fields=headRevisionId)` before writing. There is no server-side race guard.

## 4. Why the YouTube auth cannot be reused for Drive

The app's YouTube / YouTube Music sign-in is **not OAuth**. It is InnerTube web-session auth: a
browser login window captures the account's cookie jar (`SID`, `__Secure-3PSID`,
`__Secure-3PAPISID`, `SAPISID`…), and requests are signed with `SAPISIDHASH` headers derived from the
cookies (`YouTubeWebClient`, `YouTubeMusicBrowserLogin`). Those cookies authorize YouTube/InnerTube
web endpoints only; `googleapis.com` (Drive) requires an OAuth 2.0 access token minted against an
OAuth client with the Drive scopes — Google will not accept a YouTube session cookie there, and no
"YouTube scope" includes Drive. `DeviceLoginApi` is likewise unusable: it is device pairing against
the Nuvio backend (Supabase), not Google. The Drive backup's own OAuth client (env-configured,
loopback + PKCE) is the correct implementation. What *is* reusable in spirit: the browser-window
sign-in UX pattern, and the credential-hygiene rule (tokens in a dedicated store the settings backup
excludes).

## 5. Prioritized gaps, each with the smallest merge-friendly approach

- **G1 (decision, not code) — scope & distribution.** The default `drive` scope is restricted; the
  env-var model makes each user bring their own project, and a project in "Testing" status
  re-authenticates weekly. Document the required Google Cloud setup (Drive API enabled, consent
  screen *In production*) in the fork's docs/README before anything else. If the scope is ever
  narrowed, `drive.file` cannot replace it (per-app visibility, see §3); `drive.appdata` would isolate
  the desktop companion file from nothing that matters (only this app reads the zip) but would kill
  the Live TV interop premise — keep root as long as interop matters.
- **G2 (optional — de-prioritized).** Whether NuvioPlusIPTV can read the desktop's root file was an
  open question, but the user does not require Android interop: the goal is Linux/desktop-native
  backup, which the current implementation already is. The root-file placement stays (harmless, and
  still open to the Android→desktop direction if anyone wants it); no test is needed before shipping
  a desktop↔desktop backup.
- **G3 (small) — auto-sync.** NuvioPlusIPTV syncs automatically; the desktop is manual. Smallest
  change: an opt-in "keep synced" toggle in `GoogleDriveBackupSection` that pushes on app exit and a
  periodic check, and pulls at startup when the remote is newer — exactly the Android `start()`
  behaviour minus the debounce we cannot yet observe. Fits the existing controller; no new files.
- **G4 (medium, deferrable) — desktop↔desktop merge.** Two desktops will overwrite each other's
  managed keys (no three-way merge). Adopting Android's base-copy pattern (store the last-synced
  data locally, merge on pull) is the correct fix but touches the restore path; defer until a
  second-desktop user actually hits it. The `localHashAtBackup` warning is the interim guard.
- **G5 (small-medium) — user-curated non-properties state.** `games/library.json` and `Badges/` are
  the only real losses in the matrix (§6). Both writer and reader (`buildSettingsBackupArchive` /
  `readSettingsBackup` + `applyPendingSettingsImport`) would need a second entry prefix (e.g.
  `files/`) alongside `preferences/`; the existing reader ignores unknown prefixes already. These
  files are not credential-shaped, so the filtering story stays simple.
- **G6 (code smell, opportunistic) — `HttpDriveApi.request` calls `runBlocking` inside a suspend
  path.** Harmless today (IO dispatcher), but `accessToken()` should be called in the suspend
  context instead of `runBlocking` when that file is next touched.

## 6. Coverage matrix — every persisted-state domain

The settings archive carries every `*.properties` store under the data root and the active account
folder, credential-free (sensitive-key/value denylist + URL allowlist in
`DesktopSettingsBackup.desktop.kt`). Profile-scoped payloads (`ProfileScopedKey` — IPTV, sports,
lights…) live *inside* those stores, so all profiles ride along.

**Covered by the archive** (rule: any `*.properties` in root/account dirs, not a list — sampled
domains): account/Trakt/Simkl/Yamtrack settings, `nuvio_addons`, `nuvio_iptv` (+ per-profile
payload), `nuvio_sports` (+ per-profile follows/picks), `nuvio_live_scores`, `nuvio_lights`,
`nuvio_usenet`, `nuvio_debrid_settings`, `nuvio_library`, `nuvio_watch*` (progress /
continue-watching / resume), `nuvio_music_*` (library, history, pins, credits, loudness, lyric
sync), `nuvio_navbar_layout`, `nuvio_theme`, `nuvio_collections`, `nuvio_downloads` (settings),
`nuvio_games_settings`, `nuvio_screensaver`, `nuvio_voice_control` (fork), `nuvio_linux_hud` /
`nuvio_linux_player` (fork), player settings/shortcuts/track preferences, preroll/preshow settings,
discover/home-catalog settings, search history, server configuration, window state, updater,
image-cache settings, local-library settings, library PVR, external players, cast, P2P engine
settings, mdblist/tmdb/tvdb caches that are properties stores, account slots registry — i.e.
essentially **all settings UI state**. Live TV rides in its own root file (§1).

**Intentionally excluded from the Drive backup** (credential stores; the local file backup can
include them, the Drive one never does): `nuvio_auth`, `nuvio_trakt_auth`, `nuvio_simkl_auth`,
`nuvio_profile_pin_cache`, `nuvio_youtube_music_auth` (fork: YTMusic cookies), plus the Drive token
store itself (`google_drive_auth.properties` / keyring entry) and `google_drive_device_id`
(correct: two machines must not share a deviceId, or NuvioPlusIPTV's `fromThisTv` logic breaks).

**NOT covered — user-curated state outside `*.properties`** (losing it loses user work):

| State | Location | Notes |
| --- | --- | --- |
| Game library entries | `rootDir/games/library.json` (`GameLibraryRepository.defaultDataFile`) | The user's manually built game library; its *settings* are backed up, the library itself is not |
| Custom hero badges | `rootDir/Badges/` (`HeroCustomBadge`, `HeroDiscoveryConfigStorage`) | User-created images/config |
| Headphone-surround / player extras | `rootDir/player/` (`DesktopHeadphoneSurround`) | Calibration data |
| Preshow local files | `rootDir/preshow/` (default folder only) | Only the default location; user-chosen folders are theirs anyway |

**NOT covered — caches, correctly excluded** (refetchable or per-machine; the code comments say so
explicitly for the first two): `sports-cache/` (account dir; "what Settings > Backup carries is
`*.properties`, so fetched games stay out of a backup by not being one"), `iptv-cache/` (downloaded
playlists/EPG XMLTV; re-downloadable from the restored source URLs), `home-catalog-cache/`,
`DesktopBlobFile` JSONs (calendar, anime-filler, lightweight-meta, mdblist ratings, hero cast),
image/animated-image caches, `cache/backdrop-framing`, `musicbrainz/`, `music-maps/`, `innertubex/`,
`skipdb-dump.json`, `logs/`, `runtime/`, engine/torrserver runtime dirs.

## 7. Prior art: other Linux Google Drive clients

There is **no InnerTube-style cookie shortcut for Drive**. YouTube's private web API accepts browser
sessions because it is a website frontend; Drive has no equivalent — every working Linux client
authenticates with standard OAuth 2.0, i.e. exactly what this fork already implements. What prior
art can offer is robustness detail, not a different mechanism:

| Client | License | Relevance |
| --- | --- | --- |
| **rclone** | MIT (GPL3-compatible) | The most battle-tested desktop implementation: loopback flow, refresh-token handling, chunked **resumable** uploads, exponential backoff on 403/500 and `userRateLimit`, per-client scope choice. Best single study target; MIT permits copying small pieces with attribution, though porting ideas is simpler than porting Go. |
| **Grive2** | GPLv2-only, C++ | Added the OAuth loopback redirect flow in 0.5.3 (2022) — mirrors our approach; otherwise a directory-sync tool, wrong shape for settings backup. GPLv2-only means study/idea-porting only, no code mixing into this GPL3 project. |
| **KIO GDrive + libKGAPI** | GPL / LGPL-2.1, Qt/KDE | Full Drive integration, but Qt/KIO dependencies are a foreign toolkit here. **Cautionary tale:** KIO GDrive is currently broken for everyone because Google has not authorized the Drive API for KDE's Google project (KDE bug 480779) — a live demonstration of the restricted-scope verification risk in G1. |
| **GNOME Online Accounts / libgdata** | LGPL-2.1+ | Account + keyring plumbing; the fork already gets the useful part by shelling out to `secret-tool` (GNOME keyring's CLI), so nothing further to take. |
| **NuvioPlusIPTV** | same GPL family as upstream | Already the source of truth for the Live TV file format and the device-code flow; only useful if a TV-style no-browser flow were ever wanted on Linux. |

**Bottom line:** the protocol plumbing in this fork already matches the best-practice pattern every
Linux client uses — standard OAuth 2.0 against the official Drive API, no private-API or cookie
hacks (those exist only for YouTube's web frontend and have no Drive equivalent). Borrowing prior
art would only buy backoff/retry and chunked-upload robustness, portable as small isolated additions
(license permitting). The open question is distribution/verification (G1), not auth implementation.

## Sources

- This repo: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/backup/GoogleDriveBackup.desktop.kt`,
  `…/backup/LegacyIptvBackup.kt`, `…/settings/DesktopSettingsBackup.desktop.kt`,
  `…/core/storage/DesktopAccountSlots.kt`, `…/core/storage/DesktopBlobFile.kt`,
  `…/features/sports/SportsCacheFiles.kt` + `.desktop.kt`, `…/features/iptv/IptvStorage.desktop.kt`,
  `…/features/youtubemusic/YouTubeMusicAuthPlatform.desktop.kt`, `…/features/youtube/YouTubeWebClient.kt`,
  `…/core/auth/DeviceLoginApi.kt`, `…/features/games/GameLibraryRepository.kt`, `…/features/home/HeroCustomBadge.desktop.kt`.
- NuvioPlusIPTV: `app/src/main/java/com/nuvio/tv/livetv/sync/LiveTvDriveSync.kt`,
  `…/data/LiveTvPreferences.kt` (`exportForSync`, `SYNC_EXCLUDED_BASE`), `…/sync/LiveTvCloudSync.kt`
  (`importFromDrive`), README + privacy policy (homelessbrian.github.io/privacy.html).
- Upstream NuvioDesktop `windows-tv-adaptive` (sparse clone, no Drive references in `composeApp/src`).
- Other clients: github.com/rclone/rclone (MIT), github.com/vitalif/grive2 (GPLv2, 0.5.3 loopback
  release notes), community.kde.org/KIO_GDrive (bug 480779), github.com/KDE/libkgapi (LGPL),
  wiki.gnome.org Projects/GnomeOnlineAccounts.
- developers.google.com: Workspace Drive API › Store application-specific data; › Choose Google Drive
  API scopes; › Manage uploads; Identity › Using OAuth 2.0 to Access Google APIs (token expiration);
  Identity › Using OAuth 2.0 for Desktop applications. ETag facts corroborated by Drive v2→v3
  migration references (v3 has no `etag` field / `If-Match`).
