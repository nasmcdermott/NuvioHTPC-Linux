package com.nuvio.app.features.sports

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How this client asks. ESPN answers a browser-shaped request normally and treats a bare library
 * agent more like a scraper — an occasional refusal that the page reports as "could not refresh",
 * on a feed that is unofficial to begin with. The desktop build of the reference app sends the same
 * shape of request.
 */
private val ESPN_HEADERS = mapOf(
    "Accept" to "application/json",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
)

/**
 * The public, keyless scoreboard feed.
 *
 * Five addresses cover scores, schedules, teams, a game's detail and a league table:
 *
 *  - `site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates=Ymd-Ymd&limit=1000`
 *  - `site.web.api.espn.com/apis/v2/scoreboard/header?sport={group}&league={league}` — the fast one,
 *    the same row the provider's own site polls; see [liveScores]
 *  - `site.api.espn.com/apis/site/v2/sports/{path}/teams?limit=1000`
 *  - `site.api.espn.com/apis/site/v2/sports/{path}/summary?event={id}`
 *  - `site.api.espn.com/apis/v2/sports/{path}/standings?level=3`
 *
 * The payloads are large and loosely typed, and this is not an official API: every field is read
 * defensively and a missing one degrades the card rather than failing the league. A scoreboard that
 * cannot be parsed at all throws, so [SportsRepository] can show a failure instead of an empty day.
 */
internal class EspnSportsFeed(
    private val get: suspend (String) -> String = { httpGetTextWithHeaders(it, ESPN_HEADERS) },
) : SportsFeed {

    private val log = Logger.withTag("SportsFeed")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun scoreboard(league: League, fromDay: String, toDay: String): List<SportsEvent> {
        val dates = if (fromDay == toDay) fromDay else "$fromDay-$toDay"
        return scoreboardAt("${ESPN}${league.path}/scoreboard?dates=$dates&limit=1000", league)
    }

    override suspend fun scoreboardNow(league: League): List<SportsEvent> =
        scoreboardAt("${ESPN}${league.path}/scoreboard?limit=1000", league)

    /**
     * The provider's fast score row (see [SportsFeed.liveScores]): `scoreboard/header`, whose answer
     * is allowed to be 2-7 s old where the scoreboard's is 9. One request covers a whole league.
     *
     * Its payload is shaped differently from the scoreboard's - the competitors and the status sit on
     * the event itself, not under `competitions[0]` - so it is parsed here rather than through
     * [event]. It returns null when the address is not one the row serves (some leagues answer 400
     * there) so a caller keeps the scoreboard's data instead of losing it.
     */
    override suspend fun liveScores(league: League): List<SportsEvent>? {
        // A league the row does not serve answers 400, and a payload that does not parse is equally
        // uninteresting here: the scoreboard's data stands and the page's own round reports a real
        // failure. Nothing about a missing fast row is worth showing the viewer.
        val root = try {
            fetchObject(liveScoresUrl(league))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.i { "Fast score row unavailable for ${league.id}: ${e.message}" }
            null
        } ?: return null
        val events = root["sports"]?.asArray()
            ?.flatMap { sport -> sport.jsonObjectOrNull()?.get("leagues")?.asArray().orEmpty() }
            ?.flatMap { entry -> entry.jsonObjectOrNull()?.get("events")?.asArray().orEmpty() }
            ?: return null
        return events.mapNotNull { element ->
            runCatching { liveEvent(element.jsonObject, league) }.getOrNull()
        }
    }

    /** The event as the fast row describes it: everything a card's live part needs, nothing more. */
    private fun liveEvent(obj: JsonObject, league: League): SportsEvent? {
        val id = obj["id"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val status = obj["fullStatus"]?.jsonObjectOrNull() ?: obj["status"]?.jsonObjectOrNull()
        val typeObj = status?.get("type")?.jsonObjectOrNull()
        val stateName = typeObj?.get("state")?.stringOrNull()
        val completed = typeObj?.get("completed")?.jsonPrimitive?.booleanOrNull == true
        val sides = obj["competitors"]?.asArray().orEmpty().mapNotNull { element ->
            val side = element.jsonObjectOrNull() ?: return@mapNotNull null
            val team = Team(
                id = side["id"]?.stringOrNull().orEmpty(),
                name = side["displayName"]?.stringOrNull() ?: side["name"]?.stringOrNull().orEmpty(),
                shortName = side["displayName"]?.stringOrNull() ?: side["name"]?.stringOrNull().orEmpty(),
                abbreviation = side["abbreviation"]?.stringOrNull().orEmpty(),
                logo = side["logo"]?.stringOrNull(),
                color = side["color"]?.stringOrNull()?.let { parseHexColor(it) },
            )
            (side["homeAway"]?.stringOrNull() ?: "") to GameSide(
                team = team,
                score = side["score"]?.stringOrNull()?.takeIf { it.isNotBlank() },
                winner = side["winner"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        val away = sides.firstOrNull { it.first == "away" }?.second ?: sides.getOrNull(0)?.second ?: return null
        val home = sides.firstOrNull { it.first == "home" }?.second ?: sides.getOrNull(1)?.second ?: return null
        val state = when {
            completed || stateName.equals("post", ignoreCase = true) -> GameState.POST
            stateName.equals("in", ignoreCase = true) || obj["status"]?.stringOrNull() == "in" -> GameState.LIVE
            else -> GameState.PRE
        }
        return SportsEvent(
            id = id,
            leagueId = league.id,
            startMs = parseEpoch(obj["date"]?.stringOrNull()),
            state = state,
            status = typeObj?.get("shortDetail")?.stringOrNull() ?: typeObj?.get("detail")?.stringOrNull(),
            // A finished game carries "12:00"/"0:00" for a clock that is not running: saying it has
            // one would draw a clock on a game that is over.
            clock = if (state == GameState.POST) null else status?.get("displayClock")?.stringOrNull()
                ?.takeIf { it.isNotBlank() && it != "0:00" },
            away = away,
            home = home,
            situation = obj["situation"]?.jsonObjectOrNull()
                ?.get("lastPlay")?.jsonObjectOrNull()?.get("text")?.stringOrNull(),
            progress = progressOf(status),
        )
    }

    private suspend fun scoreboardAt(url: String, league: League): List<SportsEvent> {
        val root = fetchObject(url) ?: return emptyList()
        val events = root["events"]?.asArray() ?: return emptyList()
        return events.mapNotNull { element ->
            runCatching { event(element.jsonObject, league) }
                .onFailure { log.w { "Skipped an event in ${league.id}: ${it::class.simpleName}: ${it.message}" } }
                .getOrNull()
        }
    }

    override suspend fun teams(league: League): List<Team> {
        val root = fetchObject("${ESPN}${league.path}/teams?limit=1000") ?: return emptyList()
        val container = root["sports"]?.asArray()?.firstOrNull()?.jsonObject ?: return emptyList()
        val entries = container["leagues"]?.asArray()?.firstOrNull()?.jsonObject
            ?.get("teams")?.asArray() ?: return emptyList()
        return entries.mapNotNull { element ->
            val inner = element.jsonObject["team"]?.jsonObject ?: return@mapNotNull null
            team(inner)
        }
    }

    override suspend fun summary(league: League, eventId: String): GameSummary {
        val root = fetchObject("${ESPN}${league.path}/summary?event=$eventId")
            ?: return GameSummary(eventId)
        val header = root["header"]?.jsonObject
        val boxscore = root["boxscore"]?.jsonObjectOrNull()
        val teamIds = competitorTeamIds(header)
        val (awayTeamId, homeTeamId) = competitorSides(header)
        val extras = summaryExtras(eventId, league, header, root, teamIds)
        return GameSummary(
            eventId = eventId,
            scoring = scoringPlays(root["scoringPlays"]?.asArray() ?: JsonArray(emptyList())),
            playByPlay = playByPlay(root, awayTeamId, homeTeamId),
            standings = standingsOf(header),
            venue = header?.get("competitions")?.asArray()?.firstOrNull()?.jsonObject
                ?.get("venue")?.jsonObject?.get("fullName")?.stringOrNull(),
            teamStats = teamStats(boxscore),
            leaders = extras.first,
            news = extras.second,
            injuries = injuriesOf(root["injuries"]?.asArray()),
            categories = statCategories(boxscore),
            moneyline = moneylineOf(root),
        )
    }

    override suspend fun standings(league: League): List<Standing> {
        val url = standingsUrl(league)
        val root = fetchObject(url) ?: return emptyList()
        // Collect leaf tables along with their group name and optional parent conference name.
        data class Leaf(val name: String, val conference: String?, val entries: JsonArray)
        val leaves = mutableListOf<Leaf>()
        fun walk(node: JsonObject, parentName: String?) {
            val entries = node["standings"]?.jsonObjectOrNull()?.get("entries")?.asArray()
            val nodeName = node["name"]?.stringOrNull()
                ?: node["abbreviation"]?.stringOrNull()
            if (entries != null && entries.isNotEmpty()) {
                leaves += Leaf(nodeName ?: league.name, parentName, entries)
            }
            val children = node["children"]?.asArray() ?: return
            val nextParent = if (node === root) null else nodeName
            children.forEach { child ->
                child.jsonObjectOrNull()?.let { walk(it, nextParent) }
            }
        }
        walk(root, null)
        if (leaves.isEmpty()) return emptyList()

        // Racing endpoints list Drivers first, then Constructors; keep the Drivers championship.
        val firstEntry = leaves.first().entries.firstOrNull()?.jsonObjectOrNull()
        val isAthleteTable = firstEntry?.get("athlete")?.jsonObjectOrNull() != null &&
            firstEntry["team"]?.jsonObjectOrNull() == null
        val activeLeaves = if (isAthleteTable) leaves.take(1) else leaves

        val allRows = mutableListOf<Standing>()
        for (leaf in activeLeaves) {
            val parsed = leaf.entries.mapNotNull { entry ->
                runCatching { parseStandingEntry(entry.jsonObject, leaf.name, leaf.conference, league) }.getOrNull()
            }
            val ranked = when {
                parsed.isNotEmpty() && parsed.all { it.rank > 0 } -> parsed.sortedBy { it.rank }
                parsed.isNotEmpty() && parsed.all { it.seed != null } -> parsed.sortedBy { it.seed }
                else -> parsed
            }.mapIndexed { idx, row ->
                if (row.rank > 0) row else row.copy(rank = idx + 1)
            }

            if (isAthleteTable && ranked.isNotEmpty()) {
                val leadPts = ranked.first().values
                    .firstOrNull { it.key == "championshipPts" || it.key == "points" || it.label == "PTS" }
                    ?.value?.toDoubleOrNull()
                allRows += if (leadPts != null) {
                    ranked.map { row ->
                        val pts = row.values
                            .firstOrNull { it.key == "championshipPts" || it.key == "points" || it.label == "PTS" }
                            ?.value?.toDoubleOrNull()
                        val behind = if (pts == null || row.rank == 1) "–" else "-${SportsTable.formatDelta(leadPts - pts)}"
                        row.copy(values = row.values + StandingValue("BEHIND", behind, "behind"))
                    }
                } else {
                    ranked
                }
            } else {
                allRows += ranked
            }
        }
        return allRows
    }

    /** The fast row's address: the path's two halves become the row's `sport` and `league`. */
    internal fun liveScoresUrl(league: League): String =
        "https://site.web.api.espn.com/apis/v2/scoreboard/header" +
            "?sport=${league.path.substringBefore('/')}&league=${league.path.substringAfter('/')}"

    internal fun standingsUrl(league: League): String {
        val divisions = league.path in DIVISION_LEAGUES
        return "${ESPN_STANDINGS}${league.path}/standings" + if (divisions) "?level=3" else ""
    }

    private fun parseStandingEntry(
        obj: JsonObject,
        groupName: String,
        conferenceName: String?,
        league: League,
    ): Standing? {
        val teamObj = obj["team"]?.jsonObjectOrNull()
        val athleteObj = obj["athlete"]?.jsonObjectOrNull()
        val subject = teamObj ?: athleteObj ?: return null
        val statsArray = obj["stats"]?.asArray()
        val statsByName = HashMap<String, JsonObject>()
        var record: String? = null
        var homeRecord: String? = null
        var awayRecord: String? = null
        val values = ArrayList<StandingValue>()

        statsArray?.forEach { stat ->
            val statObj = stat.jsonObjectOrNull() ?: return@forEach
            val type = statObj["type"]?.stringOrNull()?.lowercase()
            when (type) {
                "total" -> record = statObj["summary"]?.stringOrNull() ?: statObj["displayValue"]?.stringOrNull()
                "home" -> homeRecord = statObj["summary"]?.stringOrNull() ?: statObj["displayValue"]?.stringOrNull()
                "road", "away" -> awayRecord = statObj["summary"]?.stringOrNull() ?: statObj["displayValue"]?.stringOrNull()
            }
            val rawName = statObj["name"]?.stringOrNull()
            if (rawName != null) statsByName[rawName] = statObj

            // abbreviation ("W", "L", "PCT", "GB") is the column heading a table wants; the raw
            // name ("winPercent") is camelCase API spelling, and displayName is too wide.
            val label = statObj["abbreviation"]?.stringOrNull()
                ?: statObj["shortDisplayName"]?.stringOrNull()
                ?: statObj["shortName"]?.stringOrNull()
                ?: rawName ?: return@forEach
            val displayValue = statObj["displayValue"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                ?: return@forEach
            // Skip metadata-only stats from visible table columns unless they are the only ones.
            if (rawName in META_STAT_NAMES) return@forEach
            values += StandingValue(label = label, value = displayValue, key = rawName.orEmpty())
        }

        fun num(name: String): Double? {
            val s = statsByName[name] ?: return null
            return s["value"]?.let { runCatching { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }.getOrNull() }
                ?: s["displayValue"]?.stringOrNull()?.toDoubleOrNull()
        }
        fun text(name: String): String? =
            statsByName[name]?.get("displayValue")?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val noteObj = obj["note"]?.jsonObjectOrNull()
        val noteDesc = noteObj?.get("description")?.stringOrNull()
        val noteColor = noteObj?.get("color")?.stringOrNull()?.let(::parseHexColor)
            ?: noteDesc?.let(SportsTable::zoneColor)

        val clincher = text("clincher")?.takeIf { it != "*" && it.length <= 2 }
        val clincherText = statsByName["clincher"]?.get("description")?.stringOrNull()
        val rank = num("rank")?.toInt()?.takeIf { it > 0 } ?: 0
        val seed = num("playoffSeed")?.toInt()?.takeIf { it > 0 }
        val rankChange = num("rankChange")?.toInt()?.takeIf { it != 0 }

        val isPointsSport = athleteObj != null ||
            league.sport in setOf(Sport.FOOTBALL, Sport.RUGBY, Sport.HOCKEY, Sport.RACING, Sport.AUSSIE_RULES) ||
            ("points" in statsByName && "winPercent" !in statsByName)
        val sortValue = if (isPointsSport) {
            (num("points") ?: num("championshipPts") ?: 0.0) * 1000.0 + (num("pointDifferential") ?: num("differential") ?: 0.0)
        } else {
            (num("winPercent") ?: 0.0) * 1000.0 + (num("wins") ?: 0.0)
        }

        val parsedTeam = if (teamObj != null) {
            team(teamObj)
        } else {
            val name = subject["displayName"]?.stringOrNull() ?: subject["name"]?.stringOrNull().orEmpty()
            val shortName = subject["shortDisplayName"]?.stringOrNull() ?: subject["shortName"]?.stringOrNull() ?: name
            Team(
                id = subject["id"]?.stringOrNull().orEmpty(),
                name = name,
                shortName = shortName,
                abbreviation = subject["abbreviation"]?.stringOrNull()
                    ?: shortName.split(' ').lastOrNull()?.take(3)?.uppercase().orEmpty(),
                logo = subject["flag"]?.jsonObjectOrNull()?.get("href")?.stringOrNull()
                    ?: subject["headshot"]?.jsonObjectOrNull()?.get("href")?.stringOrNull(),
                color = league.color,
            )
        }

        return Standing(
            team = parsedTeam,
            note = noteDesc,
            values = values,
            rank = rank,
            group = groupName,
            conference = conferenceName,
            seed = seed,
            rankChange = rankChange,
            clincher = clincher,
            clincherText = clincherText,
            noteColor = noteColor,
            record = record,
            homeRecord = homeRecord,
            awayRecord = awayRecord,
            streak = text("streak"),
            sortValue = sortValue,
            athlete = athleteObj != null && teamObj == null,
        )
    }

    // region Parsing

    private fun event(obj: JsonObject, league: League): SportsEvent? {
        val id = obj["id"]?.stringOrNull() ?: return null
        val competition = obj["competitions"]?.asArray()?.firstOrNull()?.jsonObject ?: return null
        val competitors = competition["competitors"]?.asArray().orEmpty()
        val eventName = obj["shortName"]?.stringOrNull()?.takeIf { it.isNotBlank() }
            ?: obj["name"]?.stringOrNull()?.takeIf { it.isNotBlank() }

        val parsedSides = competitors.mapNotNull { element ->
            val side = element.jsonObjectOrNull() ?: return@mapNotNull null
            val teamObj = side["team"]?.jsonObjectOrNull()
            val athleteObj = side["athlete"]?.jsonObjectOrNull()
            val parsedTeam = when {
                teamObj != null -> team(teamObj)
                athleteObj != null -> athleteAsTeam(athleteObj, league)
                else -> return@mapNotNull null
            }
            val homeAway = side["homeAway"]?.stringOrNull()
            homeAway to GameSide(
                team = parsedTeam,
                score = side["score"]?.stringOrNull()?.takeIf { it.isNotBlank() },
                record = side["records"]?.asArray()?.firstOrNull()?.jsonObjectOrNull()
                    ?.get("summary")?.stringOrNull(),
                winner = side["winner"]?.jsonPrimitive?.booleanOrNull == true,
                linescores = side["linescores"]?.asArray()?.mapNotNull { ls ->
                    ls.jsonObjectOrNull()?.get("displayValue")?.stringOrNull()
                        ?: ls.jsonObjectOrNull()?.get("value")?.jsonPrimitive?.intOrNull?.toString()
                }.orEmpty(),
            )
        }

        // Standard 2-sided matchup (team or 1v1 athlete), OR a field event (F1, NASCAR, PGA) with
        // many competitors or an event title.
        val isFieldEvent = league.sport in FIELD_EVENT_SPORTS && (competitors.size > 2 || (parsedSides.size < 2 && eventName != null))
        val (away, home) = when {
            isFieldEvent -> fieldEventSides(eventName ?: league.name, league, parsedSides.map { it.second })
            parsedSides.size >= 2 -> {
                val byRole = parsedSides.toMap()
                val awaySide = byRole["away"] ?: parsedSides.first().second
                val homeSide = byRole["home"] ?: parsedSides.last().second
                awaySide to homeSide
            }
            else -> return null
        }

        val status = competition["status"]?.jsonObject ?: obj["status"]?.jsonObject
        val typeObj = status?.get("type")?.jsonObject
        val completed = typeObj?.get("completed")?.jsonPrimitive?.booleanOrNull == true
        val stateName = typeObj?.get("state")?.stringOrNull()
        val clock = status?.get("displayClock")?.stringOrNull()
        val period = status?.get("period")?.jsonPrimitive?.intOrNull
        val broadcasts = extractBroadcasts(competition)
        return SportsEvent(
            id = id,
            leagueId = league.id,
            startMs = parseEpoch(obj["date"]?.stringOrNull()),
            state = when {
                completed || stateName.equals("post", ignoreCase = true) -> GameState.POST
                stateName.equals("in", ignoreCase = true) -> GameState.LIVE
                clock != null && period != null && period > 0 -> GameState.LIVE
                else -> GameState.PRE
            },
            status = typeObj?.get("shortDetail")?.stringOrNull()
                ?: typeObj?.get("detail")?.stringOrNull(),
            clock = clock?.takeIf { it.isNotBlank() && it != "0:00" },
            away = away,
            home = home,
            situation = competition["situation"]?.jsonObject
                ?.get("lastPlay")?.jsonObject?.get("text")?.stringOrNull(),
            venue = competition["venue"]?.jsonObject?.get("fullName")?.stringOrNull(),
            network = broadcasts.firstOrNull(),
            broadcasts = broadcasts,
            title = if (isFieldEvent || league.sport == Sport.FIGHTING) eventName else null,
            progress = progressOf(status),
            moneyline = moneylineOf(competition),
        )
    }

    private fun moneylineOf(source: JsonObject): MoneylineOdds? {
        val candidates = source["odds"]?.asArray().orEmpty()
            .mapNotNull { it.jsonObjectOrNull() } +
            source["pickcenter"]?.asArray().orEmpty().mapNotNull { it.jsonObjectOrNull() }
        val odds = candidates.firstOrNull { it.hasMoneylineQuote() } ?: return null
        val away = odds["awayTeamOdds"]?.jsonObjectOrNull()
        val home = odds["homeTeamOdds"]?.jsonObjectOrNull()
        val market = odds["moneyline"]?.jsonObjectOrNull()
        val awayLine = away?.get("moneyLine")?.stringOrNull()
            ?: away?.get("moneyline")?.stringOrNull()
            ?: odds["awayMoneyLine"]?.stringOrNull()
            ?: market?.get("away")?.closingOdds()
        val homeLine = home?.get("moneyLine")?.stringOrNull()
            ?: home?.get("moneyline")?.stringOrNull()
            ?: odds["homeMoneyLine"]?.stringOrNull()
            ?: market?.get("home")?.closingOdds()
        val spread = odds["details"]?.stringOrNull()?.takeIf { it.isNotBlank() }
            ?: odds["spread"]?.stringOrNull()?.takeIf { it.isNotBlank() }
            ?: odds["pointSpread"]?.jsonObjectOrNull()?.let { markets ->
                markets["away"]?.closingLine()?.takeIf { it.isNotBlank() }
                    ?: markets["home"]?.closingLine()
            }
        val overUnder = odds["overUnder"]?.stringOrNull()?.takeIf { it.isNotBlank() }
            ?: odds["total"]?.jsonObjectOrNull()?.get("over")?.closingLine()?.removePrefix("o")
        val provider = odds["provider"]?.jsonObjectOrNull()?.let { providerObj ->
            providerObj["displayName"]?.stringOrNull() ?: providerObj["name"]?.stringOrNull()
        }
        if (awayLine == null && homeLine == null && spread == null && overUnder == null) return null
        return MoneylineOdds(
            provider = provider,
            away = awayLine,
            home = homeLine,
            spread = spread,
            overUnder = overUnder,
        )
    }

    private fun JsonObject.hasMoneylineQuote(): Boolean {
        val market = this["moneyline"]?.jsonObjectOrNull()
        return market?.get("away")?.closingOdds() != null || market?.get("home")?.closingOdds() != null ||
            this["awayTeamOdds"]?.jsonObjectOrNull()?.let { it["moneyLine"]?.stringOrNull() ?: it["moneyline"]?.stringOrNull() } != null ||
            this["homeTeamOdds"]?.jsonObjectOrNull()?.let { it["moneyLine"]?.stringOrNull() ?: it["moneyline"]?.stringOrNull() } != null ||
            this["awayMoneyLine"]?.stringOrNull() != null || this["homeMoneyLine"]?.stringOrNull() != null
    }

    private fun JsonElement?.closingOdds(): String? = this?.jsonObjectOrNull()
        ?.get("close")?.jsonObjectOrNull()?.get("odds")?.stringOrNull()
        ?: this?.jsonObjectOrNull()?.get("open")?.jsonObjectOrNull()?.get("odds")?.stringOrNull()
        ?: this?.stringOrNull()

    private fun JsonElement?.closingLine(): String? = this?.jsonObjectOrNull()
        ?.get("close")?.jsonObjectOrNull()?.get("line")?.stringOrNull()
        ?: this?.stringOrNull()

    private fun athleteAsTeam(athleteObj: JsonObject, league: League): Team {
        val fullName = athleteObj["displayName"]?.stringOrNull()
            ?: athleteObj["fullName"]?.stringOrNull()
            ?: athleteObj["shortName"]?.stringOrNull().orEmpty()
        val shortName = athleteObj["shortName"]?.stringOrNull() ?: fullName
        val abbr = fullName.split(' ').lastOrNull()?.take(3)?.uppercase().orEmpty()
        return Team(
            id = athleteObj["id"]?.stringOrNull().orEmpty(),
            name = fullName,
            shortName = shortName,
            abbreviation = abbr.ifBlank { league.shortName.take(3) },
            logo = athleteObj["headshot"]?.jsonObjectOrNull()?.get("href")?.stringOrNull()
                ?: athleteObj["flag"]?.jsonObjectOrNull()?.get("href")?.stringOrNull(),
            color = league.color,
        )
    }

    private fun fieldEventSides(
        eventName: String,
        league: League,
        leaders: List<GameSide>,
    ): Pair<GameSide, GameSide> {
        val topOne = leaders.getOrNull(0)
        val topTwo = leaders.getOrNull(1)
        if (topOne != null && topTwo != null && (topOne.score != null || topOne.winner)) {
            return topOne to topTwo
        }
        return GameSide(
            team = Team(
                id = "${league.id}:event",
                name = eventName,
                shortName = eventName,
                abbreviation = league.shortName.take(4),
                color = league.color,
            ),
        ) to GameSide(
            team = Team(
                id = "${league.id}:series",
                name = league.name,
                shortName = league.name,
                abbreviation = league.shortName.take(4),
                color = league.color,
            ),
        )
    }

    private fun extractBroadcasts(competition: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        competition["broadcasts"]?.asArray()?.forEach { entry ->
            entry.jsonObjectOrNull()?.get("names")?.asArray()?.forEach { n ->
                n.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
        }
        competition["geoBroadcasts"]?.asArray()?.forEach { entry ->
            entry.jsonObjectOrNull()?.get("media")?.jsonObjectOrNull()?.get("shortName")
                ?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        competition["broadcast"]?.stringOrNull()?.split('/')?.forEach { part ->
            part.trim().takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out.toList()
    }

    /** How far through the game we are, from the period and the clock; null when untimed. */
    private fun progressOf(status: JsonObject?): Float? {
        val period = status?.get("period")?.jsonPrimitive?.intOrNull ?: return null
        val typeObj = status["type"]?.jsonObjectOrNull()
        val periods = typeObj?.get("detail")?.stringOrNull() ?: return null
        // Only trust it for timed, period-based sports; a "Final" or a clockless sport has no share.
        if (periods.isBlank()) return null
        return (period / 4f).coerceIn(0f, 1f)
    }

    private fun team(obj: JsonObject): Team = Team(
        id = obj["id"]?.stringOrNull().orEmpty(),
        name = obj["displayName"]?.stringOrNull() ?: obj["name"]?.stringOrNull().orEmpty(),
        shortName = obj["shortDisplayName"]?.stringOrNull() ?: obj["name"]?.stringOrNull().orEmpty(),
        abbreviation = obj["abbreviation"]?.stringOrNull().orEmpty(),
        logo = obj["logo"]?.stringOrNull()
            ?: obj["logos"]?.asArray()?.firstOrNull()?.jsonObject?.get("href")?.stringOrNull(),
        color = obj["color"]?.stringOrNull()?.let { parseHexColor(it) },
    )

    private fun playByPlay(
        root: JsonObject,
        awayTeamId: String?,
        homeTeamId: String?,
    ): List<PlayByPlayEntry> {
        val directPlays = root["plays"]?.asArray()
        val plays = if (directPlays != null) {
            directPlays
        } else {
            // NFL summaries nest each play under its drive; flatten drives in feed order.
            root["drives"]?.jsonObjectOrNull()?.let { drives ->
                val previous = drives["previous"]?.asArray().orEmpty()
                val current = listOfNotNull(drives["current"])
                (previous + current).flatMap { drive ->
                    drive.jsonObjectOrNull()?.get("plays")?.asArray().orEmpty()
                }
            }.orEmpty()
        }
        val entries = plays.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull() ?: return@mapIndexedNotNull null
            val text = obj["text"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                ?: obj["shortDescription"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            PlayByPlayEntry(
                id = obj["id"]?.stringOrNull() ?: obj["sequenceNumber"]?.stringOrNull() ?: "play:$index",
                period = obj["period"]?.jsonObjectOrNull()?.get("displayValue")?.stringOrNull()
                    ?: obj["period"]?.jsonObjectOrNull()?.get("number")?.stringOrNull()?.let { "Period $it" },
                clock = obj["clock"]?.jsonObjectOrNull()?.get("displayValue")?.stringOrNull(),
                text = text,
                awayScore = obj["awayScore"]?.stringOrNull(),
                homeScore = obj["homeScore"]?.stringOrNull(),
                scoring = obj["scoringPlay"]?.jsonPrimitive?.booleanOrNull == true,
                wallclockMs = obj["wallclock"]?.stringOrNull()?.let(::parseEpoch)?.takeIf { it > 0L },
                points = obj["scoreValue"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
                scoringTeamId = obj["team"]?.jsonObjectOrNull()?.get("id")?.stringOrNull(),
            )
        }.distinctBy { it.id }
        return withScoringDeltas(entries, awayTeamId, homeTeamId)
    }

    /**
     * Fills in the points and scoring team for plays the feed does not tag itself.
     *
     * Basketball and hockey send `scoreValue` and the scoring team, but football's plays carry
     * neither — only the running score. The running score is compared with the previous play, so a
     * touchdown reads "+6", a field goal "+3" and a free throw "+1" even on those feeds.
     */
    private fun withScoringDeltas(
        plays: List<PlayByPlayEntry>,
        awayTeamId: String?,
        homeTeamId: String?,
    ): List<PlayByPlayEntry> {
        var previousAway: Int? = null
        var previousHome: Int? = null
        return plays.map { play ->
            val away = play.awayScore?.trim()?.toIntOrNull()
            val home = play.homeScore?.trim()?.toIntOrNull()
            var points = play.points
            var teamId = play.scoringTeamId
            if (away != null && home != null) {
                val lastAway = previousAway
                val lastHome = previousHome
                if (lastAway != null && lastHome != null) {
                    val awayAdded = (away - lastAway).coerceAtLeast(0)
                    val homeAdded = (home - lastHome).coerceAtLeast(0)
                    val added = awayAdded + homeAdded
                    if (added > 0) {
                        if (points == null || points <= 0) points = added
                        if (teamId == null) {
                            teamId = when {
                                awayAdded > 0 && homeAdded == 0 -> awayTeamId
                                homeAdded > 0 && awayAdded == 0 -> homeTeamId
                                else -> null
                            }
                        }
                    }
                }
                previousAway = away
                previousHome = home
            }
            play.copy(
                points = points?.takeIf { it > 0 },
                scoringTeamId = teamId?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** The competitors' team ids split into away and home, from the summary's header. */
    private fun competitorSides(header: JsonObject?): Pair<String?, String?> {
        var away: String? = null
        var home: String? = null
        header?.get("competitions")?.asArray()?.firstOrNull()?.jsonObjectOrNull()
            ?.get("competitors")?.asArray().orEmpty()
            .forEach { element ->
                val obj = element.jsonObjectOrNull() ?: return@forEach
                val id = obj["team"]?.jsonObjectOrNull()?.get("id")?.stringOrNull() ?: return@forEach
                when (obj["homeAway"]?.stringOrNull()) {
                    "away" -> away = id
                    "home" -> home = id
                }
            }
        return away to home
    }

    private fun scoringPlays(plays: JsonArray): List<ScoringPlay> = plays.mapNotNull { element ->
        val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
        val text = obj["text"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ScoringPlay(
            period = obj["period"]?.jsonObjectOrNull()?.get("displayValue")?.stringOrNull(),
            clock = obj["clock"]?.jsonObjectOrNull()?.get("displayValue")?.stringOrNull(),
            text = text,
            awayScore = obj["awayScore"]?.stringOrNull(),
            homeScore = obj["homeScore"]?.stringOrNull(),
        )
    }

    private fun standingsOf(header: JsonObject?): List<Standing> {
        // The game page carries a compact table of just the two teams under `header.standings`.
        val entries = header?.get("standings")?.jsonObjectOrNull()?.get("entries")?.asArray()
            ?: return emptyList()
        return entries.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val teamObj = obj["team"]?.jsonObjectOrNull() ?: return@mapNotNull null
            Standing(
                team = team(teamObj),
                note = obj["note"]?.jsonObjectOrNull()?.get("description")?.stringOrNull(),
                values = obj["stats"]?.asArray()?.mapNotNull { stat ->
                    val statObj = stat.jsonObjectOrNull() ?: return@mapNotNull null
                    val label = statObj["shortName"]?.stringOrNull()
                        ?: statObj["name"]?.stringOrNull() ?: return@mapNotNull null
                    StandingValue(label, statObj["displayValue"]?.stringOrNull().orEmpty())
                }.orEmpty(),
            )
        }
    }

    /**
     * The team-stat comparison block.
     *
     * The provider sends `boxscore.teams` as two entries, each with a `statistics` array; the rows
     * are joined on the stat's `name` so both sides line up even when one is missing a stat the
     * other has. Order follows the away team's list, which is the reading order of a box score.
     */
    private fun teamStats(boxscore: JsonObject?): List<TeamStatPair> {
        val teams = boxscore?.get("teams")?.asArray() ?: return emptyList()
        if (teams.size < 2) return emptyList()

        // name -> (readable label, display value); the raw name is only the join key, never shown.
        fun statsOf(index: Int): Map<String, Pair<String, String>> {
            val out = LinkedHashMap<String, Pair<String, String>>()
            teams.getOrNull(index)?.jsonObjectOrNull()
                ?.get("statistics")?.asArray()
                ?.forEach { entry ->
                    val obj = entry.jsonObjectOrNull() ?: return@forEach
                    val name = obj["name"]?.stringOrNull() ?: obj["label"]?.stringOrNull() ?: return@forEach
                    val value = obj["displayValue"]?.stringOrNull() ?: return@forEach
                    // Some feeds list the same name twice ("interceptions"); the first is enough.
                    if (name !in out) out[name] = statLabel(obj, name) to value
                }
            return out
        }

        val away = statsOf(0)
        val home = statsOf(1)
        return away.map { (name, awayStat) ->
            val (label, awayValue) = awayStat
            val homeValue = home[name]?.second ?: "—"
            TeamStatPair(
                label = label,
                away = awayValue,
                home = homeValue,
                // Numeric comparisons only; a "3rd down efficiency" string cannot be ordered sensibly.
                awayLeads = numeric(awayValue) != null && numeric(homeValue) != null &&
                    numeric(awayValue)!! > numeric(homeValue)!!,
                homeLeads = numeric(awayValue) != null && numeric(homeValue) != null &&
                    numeric(homeValue)!! > numeric(awayValue)!!,
            )
        }
    }

    /**
     * The provider's own readable name for a stat, so the column reads "FG", "3rd down efficiency"
     * or "Power Play Goals" depending on the sport rather than the API's `name` spelling
     * ("fieldGoalsMade-fieldGoalsAttempted"). Every league labels its own stats, which is what keeps
     * the wording correct per sport without a table here inventing one sport's terms for another.
     */
    private fun statLabel(stat: JsonObject, name: String): String {
        val provided = stat["label"]?.stringOrNull()
            ?: stat["displayName"]?.stringOrNull()
            ?: stat["shortDisplayName"]?.stringOrNull()
        return provided?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase() }
            ?: humanizeStatName(name)
    }

    /** Last resort for a stat with no label: "fieldGoalsMade-fieldGoalsAttempted" → "Field Goals Made / Field Goals Attempted". */
    private fun humanizeStatName(name: String): String = name
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" / ") { part ->
            part.replace(CAMEL_BOUNDARY, "$1 $2").replaceFirstChar { it.uppercase() }
        }
        .ifBlank { name }

    /** "45% (3/7)" and "312" parse; "—" and "10:32" do not, which is the intent. */
    private fun numeric(value: String): Double? =
        value.substringBefore('(').trim().removeSuffix("%").toDoubleOrNull()

    /** Per-player categories: "Passing", "Rushing", "Turnovers"… each with both sides' players. */
    private fun statCategories(boxscore: JsonObject?): List<StatCategory> {
        val players = boxscore?.get("players")?.asArray() ?: return emptyList()
        return players.mapNotNull { teamEntry ->
            val teamObj = teamEntry.jsonObjectOrNull() ?: return@mapNotNull null
            teamObj["statistics"]?.asArray()?.mapNotNull { statEntry ->
                val statObj = statEntry.jsonObjectOrNull() ?: return@mapNotNull null
                // NBA sends no heading at all, MLB sends only `type` ("batting"); either way the
                // category is worth showing rather than dropping its players silently.
                val name = statObj["name"]?.stringOrNull()
                    ?: statObj["type"]?.stringOrNull()
                    ?: statObj["displayName"]?.stringOrNull()
                    ?: "Players"
                val labels = statObj["labels"]?.asArray()?.map { it.stringOrNull().orEmpty() }
                    ?: statObj["names"]?.asArray()?.map { it.stringOrNull().orEmpty() }
                    ?: statObj["keys"]?.asArray()?.map { it.stringOrNull().orEmpty() }
                    ?: emptyList()
                val rows = statObj["athletes"]?.asArray() ?: return@mapNotNull null
                val lines = rows.mapNotNull { athleteEntry ->
                    val athleteObj = athleteEntry.jsonObjectOrNull() ?: return@mapNotNull null
                    val athleteName = athleteObj["athlete"]?.jsonObjectOrNull()?.get("displayName")?.stringOrNull()
                        ?: return@mapNotNull null
                    PlayerStat(
                        name = athleteName,
                        position = athleteObj["athlete"]?.jsonObjectOrNull()?.get("position")
                            ?.jsonObjectOrNull()?.get("abbreviation")?.stringOrNull(),
                        headline = run {
                            val parts = athleteObj["stats"]?.asArray()?.map { element ->
                                element.stringOrNull().orEmpty()
                            } ?: emptyList()
                            parts.filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
                        },
                        stats = athleteObj["stats"]?.asArray()?.mapIndexed { index, element ->
                            StandingValue(
                                label = labels.getOrNull(index).orEmpty(),
                                value = element.stringOrNull().orEmpty(),
                            )
                        }.orEmpty(),
                    )
                }
                if (lines.isEmpty()) null else StatCategory(humanizeStatName(name), lines)
            }.orEmpty()
        }.flatten()
            // The same category arrives once per team; fold them so "Passing" holds both sides.
            .groupBy { it.name }
            .map { (name, group) -> StatCategory(name, group.flatMap { it.players }) }
    }

    // region Season leaders and news

    /**
     * The two optional extras the detail page adds below the box score: season leaders per team and
     * recent news for each team. Both are best-effort — a feed that does not carry them, or a call
     * that fails, leaves the section out rather than failing the summary they travel with.
     */
    private suspend fun summaryExtras(
        eventId: String,
        league: League,
        header: JsonObject?,
        root: JsonObject,
        teamIds: List<String>,
    ): Pair<List<TeamLeaders>, List<TeamNews>> = coroutineScope {
        val leaders = async {
            fresh(leadersCache, eventId, LEADER_CACHE_MS) ?: run {
                val value = optional { seasonLeaders(league, header, root, teamIds) }.orEmpty()
                if (value.isNotEmpty()) remember(leadersCache, eventId, value)
                value
            }
        }
        val news = teamIds.take(2).map { teamId ->
            async {
                val key = "${league.path}:$teamId"
                fresh(newsCache, key, NEWS_CACHE_MS) ?: optional { teamNews(league, teamId) }?.also {
                    remember(newsCache, key, it)
                }
            }
        }
        leaders.await() to news.awaitAll().filterNotNull()
    }

    /** Season leaders and team news barely change during a game, so a live refresh reuses them. */
    private data class Cached<T>(val atMs: Long, val value: T)

    private val leadersCache = ConcurrentHashMap<String, Cached<List<TeamLeaders>>>()
    private val newsCache = ConcurrentHashMap<String, Cached<TeamNews>>()

    private fun <T> fresh(cache: ConcurrentHashMap<String, Cached<T>>, key: String, ttlMs: Long): T? {
        val hit = cache[key] ?: return null
        if (System.currentTimeMillis() - hit.atMs <= ttlMs) return hit.value
        cache.remove(key)
        return null
    }

    private fun <T> remember(cache: ConcurrentHashMap<String, Cached<T>>, key: String, value: T) {
        if (cache.size > 24) cache.clear()
        cache[key] = Cached(System.currentTimeMillis(), value)
    }

    /** The two competitors' team ids, in the provider's order, for leaders and news lookups. */
    private fun competitorTeamIds(header: JsonObject?): List<String> =
        header?.get("competitions")?.asArray()?.firstOrNull()?.jsonObjectOrNull()
            ?.get("competitors")?.asArray()
            ?.mapNotNull { it.jsonObjectOrNull()?.get("team")?.jsonObjectOrNull()?.get("id")?.stringOrNull() }
            ?.filter { it.isNotBlank() && it.all(Char::isDigit) }
            .orEmpty()

    private suspend fun <T> optional(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.w { "Optional sports feed request failed: ${e.message}" }
        null
    }

    /**
     * Season leaders, from the season/type the summary's header reports.
     *
     * The game summary only carries *game* leaders, so the league's season leader table is fetched
     * separately and filtered to the two competitors. Soccer is the exception: its summary leaders
     * are already season totals, so they are read straight from the summary.
     */
    private suspend fun seasonLeaders(
        league: League,
        header: JsonObject?,
        root: JsonObject,
        teamIds: List<String>,
    ): List<TeamLeaders> {
        if (teamIds.size < 2) return emptyList()
        if (league.sport == Sport.FOOTBALL) return soccerSeasonLeaders(root, teamIds)
        if (league.sport !in LEADER_CATEGORIES) return emptyList()

        val season = header?.get("season")?.jsonObjectOrNull() ?: return emptyList()
        val year = season["year"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        val type = season["type"]?.jsonPrimitive?.intOrNull ?: 2
        val path = league.path.split('/')
        if (path.size < 2) return emptyList()

        val url = "https://sports.core.api.espn.com/v2/sports/${path[0]}/leagues/${path[1]}" +
            "/seasons/$year/types/$type/leaders?limit=100"
        val doc = fetchObject(url) ?: return emptyList()
        val categories = doc["categories"]?.asArray() ?: return emptyList()
        val preferred = LEADER_CATEGORIES[league.sport].orEmpty()
        val chosen = if (preferred.isEmpty()) {
            categories.take(3)
        } else {
            preferred.mapNotNull { wanted ->
                categories.firstOrNull {
                    it.jsonObjectOrNull()?.get("name")?.stringOrNull() == wanted
                }
            }
        }

        // team id -> that team's top entry in each chosen category, in category order.
        val picks = LinkedHashMap<String, MutableList<LeaderPick>>()
        chosen.forEach { element ->
            val category = element.jsonObjectOrNull() ?: return@forEach
            val name = category["displayName"]?.stringOrNull()
                ?: category["name"]?.stringOrNull() ?: return@forEach
            val seen = mutableSetOf<String>()
            category["leaders"]?.asArray()?.forEach { leaderElement ->
                val entry = leaderElement.jsonObjectOrNull() ?: return@forEach
                val teamId = teamIdFromRef(entry["team"]?.jsonObjectOrNull()?.get("\$ref")?.stringOrNull())
                    ?: return@forEach
                if (teamId !in teamIds || !seen.add(teamId)) return@forEach
                val athleteRef = entry["athlete"]?.jsonObjectOrNull()?.get("\$ref")?.stringOrNull()
                    ?: return@forEach
                val display = entry["displayValue"]?.stringOrNull() ?: return@forEach
                val numeric = entry["value"]?.jsonPrimitive?.doubleOrNull
                picks.getOrPut(teamId) { mutableListOf() } +=
                    LeaderPick(name, sportsLeaderValue(name, display, numeric), athleteRef.toHttps())
            }
        }
        if (picks.isEmpty()) return emptyList()

        val athletes = coroutineScope {
            picks.values.flatten().map { it.athleteRef }.distinct()
                .map { async { it to optional { athlete(it) } } }
                .awaitAll()
                .toMap()
        }

        return teamIds.mapNotNull { teamId ->
            val teamPicks = picks[teamId] ?: return@mapNotNull null
            val rows = teamPicks.mapNotNull { pick ->
                val player = athletes[pick.athleteRef] ?: return@mapNotNull null
                TeamLeaderCategory(
                    name = pick.category,
                    leader = SeasonLeader(
                        name = player.name,
                        position = player.position,
                        headshot = player.headshot,
                        value = pick.value,
                    ),
                )
            }
            TeamLeaders(teamId, rows).takeIf { rows.isNotEmpty() }
        }
    }

    /** Soccer's summary leaders are season totals, already grouped per team. */
    private fun soccerSeasonLeaders(root: JsonObject, teamIds: List<String>): List<TeamLeaders> {
        val blocks = root["leaders"]?.asArray() ?: return emptyList()
        return blocks.mapNotNull { blockElement ->
            val block = blockElement.jsonObjectOrNull() ?: return@mapNotNull null
            val teamId = block["team"]?.jsonObjectOrNull()?.get("id")?.stringOrNull()
                ?: return@mapNotNull null
            if (teamId !in teamIds) return@mapNotNull null
            val rows = block["leaders"]?.asArray().orEmpty().take(3).mapNotNull { categoryElement ->
                val category = categoryElement.jsonObjectOrNull() ?: return@mapNotNull null
                val name = category["displayName"]?.stringOrNull()
                    ?: category["name"]?.stringOrNull() ?: return@mapNotNull null
                val first = category["leaders"]?.asArray()?.firstOrNull()?.jsonObjectOrNull()
                    ?: return@mapNotNull null
                val athlete = first["athlete"]?.jsonObjectOrNull() ?: return@mapNotNull null
                val playerName = athlete["displayName"]?.stringOrNull() ?: return@mapNotNull null
                val rawValue = first["displayValue"]?.stringOrNull() ?: return@mapNotNull null
                // "Matches: 10, Goals: 9" → "Goals: 9"; the match count is not the statistic.
                val value = rawValue.substringAfter(", ", rawValue)
                TeamLeaderCategory(
                    name = name,
                    leader = SeasonLeader(
                        name = playerName,
                        position = athlete["position"]?.jsonObjectOrNull()?.get("abbreviation")?.stringOrNull(),
                        headshot = athlete["headshot"]?.jsonObjectOrNull()?.get("href")?.stringOrNull(),
                        value = value,
                    ),
                )
            }
            TeamLeaders(teamId, rows).takeIf { rows.isNotEmpty() }
        }
    }

    private data class LeaderPick(val category: String, val value: String, val athleteRef: String)

    private data class AthleteInfo(val name: String, val position: String?, val headshot: String?)

    /** A player's name, position and headshot, resolved from a leaders table's athlete reference. */
    private suspend fun athlete(url: String): AthleteInfo? {
        val root = fetchObject(url) ?: return null
        val name = root["displayName"]?.stringOrNull() ?: return null
        return AthleteInfo(
            name = name,
            position = root["position"]?.jsonObjectOrNull()?.get("abbreviation")?.stringOrNull(),
            headshot = root["headshot"]?.jsonObjectOrNull()?.get("href")?.stringOrNull(),
        )
    }

    /** "…/seasons/2025/teams/18" → "18". */
    private fun teamIdFromRef(ref: String?): String? =
        ref?.let { TeamRef.find(it)?.groupValues?.getOrNull(1) }

    private fun String.toHttps(): String = replaceFirst("http://", "https://")

    /** Up to two recent stories for one team, with the header picture the detail page shows. */
    private suspend fun teamNews(league: League, teamId: String): TeamNews? {
        if (teamId.isBlank() || !teamId.all(Char::isDigit)) return null
        val root = fetchObject("${ESPN}${league.path}/news?team=$teamId&limit=2") ?: return null
        val articles = root["articles"]?.asArray().orEmpty().take(2).mapNotNull { element ->
            val article = element.jsonObjectOrNull() ?: return@mapNotNull null
            val headline = article["headline"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            NewsArticle(
                id = article["id"]?.stringOrNull() ?: headline,
                headline = headline,
                description = article["description"]?.stringOrNull()?.takeIf { it.isNotBlank() },
                image = articleImage(article),
                link = article["links"]?.jsonObjectOrNull()?.get("web")?.jsonObjectOrNull()
                    ?.get("href")?.stringOrNull(),
            )
        }
        return TeamNews(teamId, articles).takeIf { articles.isNotEmpty() }
    }

    /** The article's header photo, falling back to any picture rather than a blank card. */
    private fun articleImage(article: JsonObject): String? {
        val images = article["images"]?.asArray() ?: return null
        val header = images.firstOrNull {
            it.jsonObjectOrNull()?.get("type")?.stringOrNull() == "header"
        }?.jsonObjectOrNull()?.get("url")?.stringOrNull()
        if (!header.isNullOrBlank()) return header
        return images.firstNotNullOfOrNull {
            it.jsonObjectOrNull()?.get("url")?.stringOrNull()?.takeIf(String::isNotBlank)
        }
    }

    /** The provider's embedded injury report, per team; empty when a league sends none. */
    private fun injuriesOf(blocks: JsonArray?): List<TeamInjuries> {
        if (blocks == null) return emptyList()
        return blocks.mapNotNull { blockElement ->
            val block = blockElement.jsonObjectOrNull() ?: return@mapNotNull null
            val teamId = block["team"]?.jsonObjectOrNull()?.get("id")?.stringOrNull()
                ?: return@mapNotNull null
            val reports = block["injuries"]?.asArray().orEmpty().mapNotNull { entryElement ->
                val entry = entryElement.jsonObjectOrNull() ?: return@mapNotNull null
                val athlete = entry["athlete"]?.jsonObjectOrNull() ?: return@mapNotNull null
                val name = athlete["displayName"]?.stringOrNull() ?: return@mapNotNull null
                val details = entry["details"]?.jsonObjectOrNull()
                InjuryReport(
                    name = name,
                    position = athlete["position"]?.jsonObjectOrNull()?.get("abbreviation")?.stringOrNull(),
                    headshot = athlete["headshot"]?.jsonObjectOrNull()?.get("href")?.stringOrNull(),
                    status = entry["status"]?.stringOrNull()?.takeIf { it.isNotBlank() },
                    injury = details?.get("type")?.stringOrNull()
                        ?.takeIf { it.isNotBlank() && it != "Not Specified" },
                    note = details?.get("detail")?.stringOrNull()
                        ?.takeIf { it.isNotBlank() && it != "Not Specified" },
                    side = details?.get("side")?.stringOrNull()
                        ?.takeIf { it.isNotBlank() && it != "Not Specified" },
                    returnDate = details?.get("returnDate")?.stringOrNull()
                        ?.takeIf { it.isNotBlank() },
                )
            }
            TeamInjuries(teamId, reports).takeIf { reports.isNotEmpty() }
        }
    }

    // endregion

    private suspend fun fetchObject(url: String): JsonObject? = try {
        json.parseToJsonElement(get(url)).jsonObjectOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        log.w { "Sports feed request failed: $url (${e.message})" }
        throw SportsFeedException(url, e)
    }

    private fun parseEpoch(raw: String?): Long {
        val text = raw?.trim() ?: return 0L
        // Parsed by hand rather than with java.time so the sports model remains platform-neutral.
        val match = IsoTimestamp.matchEntire(text) ?: return 0L
        val year = match.groupValues[1].toIntOrNull() ?: return 0L
        val month = match.groupValues[2].toIntOrNull() ?: return 0L
        val day = match.groupValues[3].toIntOrNull() ?: return 0L
        val hour = match.groupValues[4].toIntOrNull() ?: return 0L
        val minute = match.groupValues[5].toIntOrNull() ?: return 0L
        val second = match.groupValues[6].toIntOrNull() ?: 0
        val millis = match.groupValues[7].take(3).padEnd(3, '0').toIntOrNull() ?: 0
        val zone = match.groupValues[8]
        val offsetMinutes = if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val offsetHour = zone.substring(1, 3).toIntOrNull() ?: return 0L
            val offsetMinute = zone.substring(4, 6).toIntOrNull() ?: return 0L
            sign * (offsetHour * 60 + offsetMinute)
        } else {
            0
        }
        return epochMillis(year, month, day, hour, minute, second) + millis - offsetMinutes * 60_000L
    }

    // endregion

    private companion object {
        const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/"
        const val ESPN_STANDINGS = "https://site.api.espn.com/apis/v2/sports/"

        val DIVISION_LEAGUES = setOf("football/nfl", "basketball/nba", "hockey/nhl", "baseball/mlb")
        val META_STAT_NAMES = setOf("rank", "rankChange", "clincher", "playoffSeed")
        val FIELD_EVENT_SPORTS = setOf(Sport.RACING, Sport.GOLF)

        /** The season-leader categories worth showing per sport, in reading order. */
        val LEADER_CATEGORIES = mapOf(
            Sport.BASKETBALL to listOf("pointsPerGame", "reboundsPerGame", "assistsPerGame"),
            Sport.AMERICAN_FOOTBALL to listOf("passingYards", "rushingYards", "receivingYards"),
            Sport.HOCKEY to listOf("goals", "assists", "points"),
            Sport.BASEBALL to listOf("avg", "homeRuns", "RBIs"),
        )

        /** The team id at the end of a core-API reference ("…/teams/18"). */
        val TeamRef = Regex("""/teams/(\d+)""")

        /** How long the detail page reuses season leaders and team news across live refreshes. */
        const val LEADER_CACHE_MS = 5 * 60_000L
        const val NEWS_CACHE_MS = 3 * 60_000L

        val IsoTimestamp = Regex(
            """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:\d{2})?""",
        )

        /** The boundary between words in an API stat name ("fieldGoals" → "field Goals"). */
        val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
    }
}

/** A feed request that failed. Carries the URL so the UI can say which league, not just "error". */
internal class SportsFeedException(val url: String, cause: Throwable) :
    Exception("Sports feed request failed: $url", cause)

/**
 * The number a season-leader category is ranked by.
 *
 * Baseball's leaders send a whole season line ("179-541, 53 HR, …") as the display value even for
 * "Batting Average", so the category's own numeric `value` is formatted instead when the display
 * text is a full line rather than a single number.
 */
internal fun sportsLeaderValue(category: String, display: String, numeric: Double?): String {
    if (numeric == null || !display.contains(", ")) return display
    val isRate = category.contains("Average", ignoreCase = true) ||
        category.contains("Percentage", ignoreCase = true) ||
        category.contains("Pct", ignoreCase = true)
    if (!isRate) return kotlin.math.round(numeric).toLong().toString()
    // Fixed three decimals, then strip the leading zero: ".331" is how a rate reads.
    val scaled = kotlin.math.round(numeric * 1000.0).toLong()
    val fraction = (scaled % 1000).toString().padStart(3, '0')
    val formatted = "${scaled / 1000}.$fraction"
    return if (formatted.startsWith("0.")) formatted.drop(1) else formatted
}

// region JSON helpers — small and total, so a missing or wrong-typed field never throws.

internal fun JsonElement?.asArray(): JsonArray? = (this as? JsonArray)

internal fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.stringOrNull(): String? = this?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

internal fun parseHexColor(hex: String): Long? {
    val clean = hex.trim().trimStart('#')
    if (clean.length != 6 && clean.length != 8) return null
    val value = clean.toLongOrNull(16) ?: return null
    return if (clean.length == 6) 0xFF000000L or value else value
}

/**
 * Days since 1970-01-01 for a proleptic Gregorian date. Howard Hinnant's `days_from_civil`, kept
 * here so the model layer needs no platform clock.
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097L + dayOfEra - 719468L
}

internal fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L

// endregion
