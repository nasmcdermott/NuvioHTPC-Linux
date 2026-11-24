package com.nuvio.app.features.sports

/**
 * Where the Sports page gets its data.
 *
 * One interface so the page never learns whether a game came from one provider or several: the
 * ESPN implementation is the only one today, and a second source (or a recorded fixture in a test)
 * is a new implementation rather than a change here.
 *
 * Every call either returns data or throws. Nothing here caches, retries or polls — that is
 * [SportsRepository]'s job — so these stay trivially testable against a fixture.
 */
internal interface SportsFeed {

    /**
     * [league]'s games from [fromDay] to [toDay] inclusive, as `yyyymmdd` strings, in the provider's
     * own order.
     */
    suspend fun scoreboard(league: League, fromDay: String, toDay: String): List<SportsEvent>

    /** [league]'s games right now, however the provider defines that (usually today and yesterday). */
    suspend fun scoreboardNow(league: League): List<SportsEvent>

    /**
     * The same league's scores from the provider's *fast* surface, for keeping a game in progress up
     * to date: it is the row the provider's own site polls, and it is served far fresher than the
     * scoreboard (measured: a cache of 2-7 s against 9 s, see `docs/sports-data-sources.md`).
     *
     * Only the live fields are meant to be taken from it - score, clock, period, state, last play -
     * because it carries less of everything else. Null when the provider has nothing or refused, so
     * a fast round that fails leaves the scoreboard's data alone.
     */
    suspend fun liveScores(league: League): List<SportsEvent>? = null

    /** [league]'s teams, for the follow picker and for a game whose team the scoreboard did not name. */
    suspend fun teams(league: League): List<Team>

    /** One game's detail: scoring plays, table, venue. The expensive call, made on open. */
    suspend fun summary(league: League, eventId: String): GameSummary

    /** [league]'s table. Throws when the league has none, which most cups do not. */
    suspend fun standings(league: League): List<Standing>
}

/** The feed a build uses. A single instance: nothing in it is stateful. */
internal object SportsFeeds {
    val current: SportsFeed = EspnSportsFeed()
}
