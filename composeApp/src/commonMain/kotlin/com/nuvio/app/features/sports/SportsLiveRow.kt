package com.nuvio.app.features.sports

/** How long a fast-row reading is trusted over a scoreboard answer that may be older. */
internal const val LIVE_PATCH_TRUST_MS = 20_000L

/**
 * [games] with the provider's fast score row folded into the games it lists (see
 * [SportsFeed.liveScores]).
 *
 * Only what the row is fresher at is taken from [fresh] - the score, the clock, the state, the last
 * play and the progress - and only for a game the day already holds, matched by id. Everything else
 * the scoreboard parsed (team logos, venue, broadcasts, odds, the matched IPTV channel) is kept: the
 * fast row carries less of it, and a card that is already showing those would otherwise lose them
 * for the sake of a fresher clock.
 *
 * A game the row does not list keeps what it had. The row describes the provider's "now", and a game
 * that dropped off it is the slower scoreboard round's business, not this one's - patching it here
 * would mean inventing a state from an absence.
 *
 * The result is equal to [games] when nothing moved, so a caller can tell a real change from a poll
 * that found nothing new and leave the UI alone.
 */
internal fun mergeLiveRow(
    games: List<SportsEvent>,
    fresh: List<SportsEvent>,
    nowMs: Long = System.currentTimeMillis(),
): List<SportsEvent> {
    if (games.isEmpty() || fresh.isEmpty()) return games
    val byId = stampLiveClocks(games, fresh, nowMs).associateBy { it.id }
    return games.map { game ->
        val row = byId[game.id] ?: return@map game
        val finished = row.state == GameState.POST
        game.copy(
            state = row.state,
            // The row's own words when it has them ("3:53 - 4th", "Final"); the scoreboard's when not.
            status = row.status?.takeIf { it.isNotBlank() } ?: game.status,
            // The row is the authority for a game it lists. It reports "0:00" at the end of a
            // period, which the parser drops as "no running clock": the scoreboard's last value
            // must go too, or the ticker would count it to 0:00 and then snap back to it when the
            // reading ages out.
            clock = if (finished) null else row.clock,
            clockReadAtMs = if (finished) 0L else row.clockReadAtMs,
            clockMoving = if (finished) false else row.clockMoving,
            away = game.away.copy(
                score = row.away.score ?: game.away.score,
                winner = row.away.winner || game.away.winner,
            ),
            home = game.home.copy(
                score = row.home.score ?: game.home.score,
                winner = row.home.winner || game.home.winner,
            ),
            situation = row.situation ?: game.situation,
            progress = row.progress ?: game.progress,
        )
    }
}

/**
 * [newGames] with the live parts of [current] kept for games the fast row has just confirmed.
 *
 * The scoreboard's answer can already be up to nine seconds old when it arrives (ESPN serves it
 * with `max-age=9`), while the fast row was read as recently as [LIVE_PATCH_TRUST_MS] ago. Letting
 * the slower answer replace it would make the score jump *backwards*, so a live-to-live game the
 * fast row has recently seen keeps the fresher score, clock, status, last play and progress. A game
 * that finished or kicked off is not protected: the scoreboard is the authority on its state, and
 * once the fast row has been quiet for the trust window the scoreboard wins again.
 */
internal fun preferFastLive(
    newGames: List<SportsEvent>,
    current: List<SportsEvent>,
    patchedAtMs: Map<String, Long>,
    nowMs: Long,
    trustMs: Long = LIVE_PATCH_TRUST_MS,
): List<SportsEvent> {
    if (newGames.isEmpty() || current.isEmpty() || patchedAtMs.isEmpty()) return newGames
    val byId = current.associateBy { it.id }
    return newGames.map { game ->
        val prior = byId[game.id] ?: return@map game
        val patchedAt = patchedAtMs[game.id] ?: return@map game
        if (!game.isLive || !prior.isLive || nowMs - patchedAt >= trustMs) return@map game
        game.copy(
            status = prior.status ?: game.status,
            clock = prior.clock,
            clockReadAtMs = prior.clockReadAtMs,
            clockMoving = prior.clockMoving,
            away = game.away.copy(score = prior.away.score ?: game.away.score),
            home = game.home.copy(score = prior.home.score ?: game.home.score),
            situation = prior.situation ?: game.situation,
            progress = prior.progress ?: game.progress,
        )
    }
}
