package com.nuvio.app.features.sports

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// fork:sports — the data spine of the Sports tab. Nothing here touches the network, the disk or
// Compose: SportsFeeds produces these, SportsCache stores them and the UI only reads them.

/**
 * The sports the Sports page groups by. Deliberately wider than v1 shows: [SportsCatalog] decides
 * which leagues exist, and the chip row is built from whatever sports those leagues cover.
 */
@Serializable
enum class Sport(val label: String) {
    AMERICAN_FOOTBALL("American Football"),
    BASKETBALL("Basketball"),
    HOCKEY("Hockey"),
    BASEBALL("Baseball"),
    FOOTBALL("Football"),
    RUGBY("Rugby"),
    FIGHTING("Fighting"),
    RACING("Racing"),
    GOLF("Golf"),
    TENNIS("Tennis"),
    CRICKET("Cricket"),
    DARTS("Darts"),
    SNOOKER("Snooker"),
    WRESTLING("Wrestling"),
    AUSSIE_RULES("Aussie Rules"),
    VOLLEYBALL("Volleyball"),
    LACROSSE("Lacrosse"),
    GAELIC("Gaelic Games"),
    NETBALL("Netball"),
    OTHER("More Sports"),
}

/**
 * A competition the Sports page can show: a league, a tournament, a racing series.
 *
 * [path] is the provider's own address segment (`football/nfl`, `soccer/eng.1`), so a second feed
 * implementation can translate it rather than the catalog knowing about any one provider.
 *
 * [guideWords] are what [SportsChannelMatcher] looks for in a channel's name when the guide has
 * nothing better; [minutes] is the usual running time, used to bound "is this game still live".
 */
@Serializable
data class League(
    val id: String,
    val sport: Sport,
    val name: String,
    val shortName: String,
    val path: String,
    /** Regions ("US", "UK", "IE", "CA", [ALL_REGIONS]) this league is offered in by default. */
    val regions: Set<String> = ALL_REGIONS,
    val guideWords: List<String> = emptyList(),
    val minutes: Int = 150,
    /** The league's brand colour, for the chip and card accents. */
    val color: Long = 0xFF3B4A63,
    /** Public league mark from the feed/CDN, when available. */
    val logo: String? = null,
) {
    companion object {
        /** A league offered everywhere; the default for the ones no region should hide. */
        val ALL_REGIONS: Set<String> = setOf("US", "CA", "UK", "IE", "OTHER")
    }
}

/** A team, as the feed describes it. [logo] and [color] are optional: not every league sends them. */
@Serializable
data class Team(
    val id: String,
    val name: String,
    val shortName: String,
    val abbreviation: String,
    val logo: String? = null,
    val color: Long? = null,
)

/** Stable stream identifier passed to add-ons that explicitly support the `sports` type. */
internal fun sportsEventStreamId(league: League, event: SportsEvent): String =
    "espn:${league.path}:${event.id}"

/** Where a game is in its life. What the card shows and how often it is refreshed both key off this. */
@Serializable
enum class GameState { PRE, LIVE, POST }

/** One side of a game: the team and, once it has started, its score. */
@Serializable
data class GameSide(
    val team: Team,
    val score: String? = null,
    /** "12-4" or "3-1-2" — the provider's own wording, never reformatted here. */
    val record: String? = null,
    val winner: Boolean = false,
    /** Quarter, period, half or inning scores in order, when the provider sends them. */
    val linescores: List<String> = emptyList(),
)

/**
 * A game, which is what the Sports page is a list of.
 *
 * [network] is the primary broadcast network the provider lists ("ESPN", "TNT SPORT 2");
 * [broadcasts] carries every network listed across national and regional feeds so channel matching
 * can find any of them in the user's playlists.
 */
@Serializable
data class SportsEvent(
    val id: String,
    val leagueId: String,
    /** Kick-off, epoch milliseconds. */
    val startMs: Long,
    val state: GameState,
    /** "3rd · 9:42", "FT", "7:30 PM" — the provider's own status text. */
    val status: String? = null,
    /**
     * The running clock on its own ("9:42"), when the provider sends one. [status] folds it
     * together with the period; this is here so the two can be drawn and sized separately.
     */
    val clock: String? = null,
    val away: GameSide,
    val home: GameSide,
    /** A short situational line ("ECU · Jayla Hearp makes free throws"), when the provider sends one. */
    val situation: String? = null,
    /** Stadium or arena ("Lincoln Financial Field"), when the provider sends one. */
    val venue: String? = null,
    val network: String? = null,
    /** Every broadcast network listed for the event (e.g. ["ABC", "ESPN+", "TSN"]). */
    val broadcasts: List<String> = emptyList(),
    /** Explicit event title for tournaments, races and fight cards ("Las Vegas Grand Prix"). */
    val title: String? = null,
    /** 0f..1f through the game, when it can be known; null for untimed sports. */
    val progress: Float? = null,
    /** Moneyline quote from the scoreboard feed, when this league/provider supplies one. */
    val moneyline: MoneylineOdds? = null,
    /**
     * When [clock] was last confirmed by a provider read, for the locally drawn clock. Not part of
     * the cached model: a reading restored from disk has no anchor, and must not be ticked from
     * whatever moment the app happens to open.
     */
    @Transient val clockReadAtMs: Long = 0L,
    /**
     * Whether consecutive reads saw [clock] move. A clock that has read the same for
     * `CLOCK_HELD_AFTER_MS` is stopped (a timeout, an interval, a review), and the local ticker
     * holds it rather than counting through the stoppage.
     */
    @Transient val clockMoving: Boolean = false,
) {
    val isLive: Boolean get() = state == GameState.LIVE

    /** True when either side has a score to show, which is what "hide scores" suppresses. */
    val hasScore: Boolean get() = away.score != null || home.score != null

    /** Every broadcast network known for this event, preserving order without duplicates. */
    val allNetworks: List<String>
        get() = if (broadcasts.isNotEmpty()) broadcasts else listOfNotNull(network)

    /** Readable matchup or event title ("Bears @ Lions" or "Las Vegas Grand Prix"). */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: "${away.team.shortName.ifBlank { away.team.name }} @ ${home.team.shortName.ifBlank { home.team.name }}"
}

/** One line of a league table. [values] carry both the provider's column heading and stat key. */
@Serializable
data class Standing(
    val team: Team,
    /** e.g. "Champions League", "Playoffs", "1st in AFC East". */
    val note: String? = null,
    val values: List<StandingValue> = emptyList(),
    /** 1-based place within [group], when known. */
    val rank: Int = 0,
    /** Division, conference or competition group name ("AFC East", "Eastern Conference"). */
    val group: String? = null,
    /** Parent conference when [group] is a division ("American Football Conference"). */
    val conference: String? = null,
    /** Playoff seed in conference standings (US leagues). */
    val seed: Int? = null,
    /** Places moved since the previous round (+ up, - down). */
    val rankChange: Int? = null,
    /** Clincher tag ("x", "y", "z", "e", "*", "p"). */
    val clincher: String? = null,
    val clincherText: String? = null,
    /** Qualification or relegation band colour, ARGB. */
    val noteColor: Long? = null,
    val record: String? = null,
    val homeRecord: String? = null,
    val awayRecord: String? = null,
    val streak: String? = null,
    /** Recent results oldest-first ("WWDLW"), when known. */
    val form: String? = null,
    /** Composite sort score for merged conference / league views. */
    val sortValue: Double = 0.0,
    /** True when [team] represents an individual driver or athlete rather than a club. */
    val athlete: Boolean = false,
)

@Serializable
data class StandingValue(val label: String, val value: String, val key: String = "")

/** One scoring play, for the detail view's "latest scoring" list. */
@Serializable
data class ScoringPlay(
    val period: String? = null,
    val clock: String? = null,
    val text: String,
    val awayScore: String? = null,
    val homeScore: String? = null,
)

/** One event's moneyline quote from a sportsbook listing. */
@Serializable
data class MoneylineOdds(
    val provider: String? = null,
    val away: String? = null,
    val home: String? = null,
    val spread: String? = null,
    val overUnder: String? = null,
)

/** One chronological play-by-play entry from a game's provider summary. */
@Serializable
data class PlayByPlayEntry(
    val id: String,
    val period: String? = null,
    val clock: String? = null,
    val text: String,
    val awayScore: String? = null,
    val homeScore: String? = null,
    val scoring: Boolean = false,
    /** ESPN's per-play wall-clock instant, independent of the game clock. */
    val wallclockMs: Long? = null,
    /** Points this play put on the board ("+3", "+7"), when it scored. */
    val points: Int? = null,
    /** The team awarded those points, so the row can tint them in its colour. */
    val scoringTeamId: String? = null,
)

/** One numbered statistic of a team ("Total Yards", "3rd down efficiency"): label plus display text. */
@Serializable
data class TeamStat(val label: String, val value: String, val category: String? = null)

/** One player leading a team in a season stat category ("Points Per Game", "Passing Yards"). */
@Serializable
data class SeasonLeader(
    val name: String,
    val position: String? = null,
    val headshot: String? = null,
    val value: String,
)

/** One season stat category of a team's leaders, with the player who leads it. */
@Serializable
data class TeamLeaderCategory(
    val name: String,
    val leader: SeasonLeader,
)

/** A team's season leaders, keyed by the team the game is between. */
@Serializable
data class TeamLeaders(
    val teamId: String,
    val categories: List<TeamLeaderCategory> = emptyList(),
)

/** One news story for a team: the picture, headline and short body text the detail page shows. */
@Serializable
data class NewsArticle(
    val id: String,
    val headline: String,
    val description: String? = null,
    val image: String? = null,
    val link: String? = null,
)

/** The news stories for one of the game's teams. */
@Serializable
data class TeamNews(
    val teamId: String,
    val articles: List<NewsArticle> = emptyList(),
)

/** One player on a team's injury report. */
@Serializable
data class InjuryReport(
    val name: String,
    val position: String? = null,
    val headshot: String? = null,
    /** "Out", "Day-To-Day", "Injured Reserve". */
    val status: String? = null,
    /** The injury itself ("Hamstring", "Concussion"). */
    val injury: String? = null,
    /** Extra note from the provider ("Surgery"), when it says more than [injury]. */
    val note: String? = null,
    /** "Left", "Right" — dropped when the provider sends "Not Specified". */
    val side: String? = null,
    /** ISO date ("2026-11-29"), when the provider projects one. */
    val returnDate: String? = null,
)

/** One team's injury report. */
@Serializable
data class TeamInjuries(
    val teamId: String,
    val reports: List<InjuryReport> = emptyList(),
)

/** A stat line for one player. [headline] is the provider's own summary of them. */
@Serializable
data class PlayerStat(
    val name: String,
    val position: String? = null,
    val headline: String? = null,
    val stats: List<StandingValue> = emptyList(),
)

/**
 * One stat category of a game — "Passing", "Rushing", "Turnovers". Each holds both teams' players,
 * because a box score reads across the two sides rather than down one.
 */
@Serializable
data class StatCategory(
    val name: String,
    val players: List<PlayerStat> = emptyList(),
)

/**
 * A game's detail payload. Fetched on demand, not with the scoreboard: most games are never opened,
 * and this is the expensive call.
 *
 * [teamStats] and [categories] are what a viewer actually reads after a game: touchdowns, turnovers,
 * possession, then who threw for what. Both are optional — the provider sends neither for every
 * league, and a widget that never gets them must still draw.
 */
@Serializable
data class GameSummary(
    val eventId: String,
    val scoring: List<ScoringPlay> = emptyList(),
    val playByPlay: List<PlayByPlayEntry> = emptyList(),
    val standings: List<Standing> = emptyList(),
    val venue: String? = null,
    /** Both teams' comparison numbers, away first. */
    val teamStats: List<TeamStatPair> = emptyList(),
    /** Season leaders per team, away first when the provider supplies them. */
    val leaders: List<TeamLeaders> = emptyList(),
    /** Recent news for each of the game's two teams. */
    val news: List<TeamNews> = emptyList(),
    /** Injury reports per team, when the provider sends them. */
    val injuries: List<TeamInjuries> = emptyList(),
    /** Per-player categories (passing, rushing, receiving…), in the provider's order. */
    val categories: List<StatCategory> = emptyList(),
    /** Moneyline/line data from the summary's pickcenter when the scoreboard has none. */
    val moneyline: MoneylineOdds? = null,
)

/**
 * One comparison row of the team-stat block: a label and both sides' values, so the UI can draw the
 * familiar away / label / home three columns without knowing which stat it is.
 */
@Serializable
data class TeamStatPair(
    val label: String,
    val away: String,
    val home: String,
    /** Higher is better for both sides, or null when the stat is neutral (turnovers can be either). */
    val awayLeads: Boolean = false,
    val homeLeads: Boolean = false,
)
