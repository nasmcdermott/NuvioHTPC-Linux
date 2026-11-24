package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/*
 * fork:sports-clock - between two provider reads, the card's clock is drawn locally.
 *
 * No free feed publishes a per-second clock: ESPN's fastest surface holds an answer for 2-7 s, its
 * scoreboard for 9, and the NHL's own CDN for 19. So the provider's clock is the anchor, and the
 * display counts from it, one second at a time, until the next read replaces it. Nothing here can
 * make the *score* fresher than the read that carried it.
 *
 * The rules are deliberately conservative, because a clock that lies is worse than one that pauses:
 *  - tick only between reads, and resync the moment a read disagrees;
 *  - hold when the provider's clock has not moved for CLOCK_HELD_AFTER_MS (a timeout, an interval,
 *    a review);
 *  - never count below 0:00, and never tick a reading older than CLOCK_TICK_MAX_AGE_MS (the app was
 *    asleep, the network went away, the cache was restored);
 *  - count down only for sports whose clock is time remaining. Soccer's clock counts up in minutes
 *    and stoppage time ("90'+5'"), and rugby's in match minutes ("80'"), so their provider strings
 *    are left exactly as they came.
 */

/** The longest a reading may keep ticking without a newer one behind it. */
internal const val CLOCK_TICK_MAX_AGE_MS = 20_000L

/** How long the same reading must persist before the clock is treated as stopped. */
internal const val CLOCK_HELD_AFTER_MS = 15_000L

/** "9:42" as seconds (582), or null when the provider sent something that is not mm:ss. */
internal fun clockSeconds(clock: String?): Int? {
    val text = clock?.trim().orEmpty()
    if (text.isEmpty() || text.count { it == ':' } != 1) return null
    val minutes = text.substringBefore(':')
    val seconds = text.substringAfter(':')
    if (minutes.isEmpty() || minutes.length > 3 || minutes.any { !it.isDigit() }) return null
    if (seconds.length != 2 || seconds.any { !it.isDigit() }) return null
    val s = seconds.toInt()
    if (s !in 0..59) return null
    return minutes.toInt() * 60 + s
}

/** Seconds back as the provider writes them: 582 -> "9:42", 0 -> "0:00". */
internal fun clockText(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

/** Sports whose provider clock counts down the time left in the period. */
private fun countsDown(sport: Sport): Boolean = when (sport) {
    Sport.BASKETBALL, Sport.AMERICAN_FOOTBALL, Sport.HOCKEY, Sport.FIGHTING, Sport.AUSSIE_RULES -> true
    else -> false
}

/**
 * The clock to draw for [clock], read at [readAtMs] and drawn at [nowMs].
 *
 * Returns [clock] unchanged when it cannot honestly be advanced: it is not mm:ss, the sport counts
 * up (soccer, rugby), the reading is too old to trust, or the provider has shown the same value long
 * enough that the clock is stopped. Otherwise it counts down, never below 0:00.
 */
internal fun clockAt(
    clock: String?,
    sport: Sport,
    readAtMs: Long,
    nowMs: Long,
    moving: Boolean,
    maxAgeMs: Long = CLOCK_TICK_MAX_AGE_MS,
): String? {
    val base = clock?.takeIf { it.isNotBlank() } ?: return null
    // Soccer and rugby count elapsed match time, not the seconds left in a period.
    if (!countsDown(sport)) return base
    val seconds = clockSeconds(base) ?: return base
    if (!moving || readAtMs <= 0L || nowMs < readAtMs) return base
    val age = nowMs - readAtMs
    if (age > maxAgeMs) return base
    return clockText(seconds - (age / 1000L).toInt())
}

/** The live clock on its own, as the game page draws it above the score. */
internal fun eventClockAt(event: SportsEvent, nowMs: Long): String? =
    clockAt(event.clock, sportOf(event), event.clockReadAtMs, nowMs, event.clockMoving)

/**
 * The provider's status line ("3:53 - 4th Quarter") with its clock replaced by the locally drawn
 * one. Only the clock substring is touched, so the provider's own words for the period survive.
 */
internal fun clockStatusAt(event: SportsEvent, nowMs: Long): String? {
    val status = event.status?.takeIf { it.isNotBlank() } ?: return null
    val clock = event.clock?.takeIf { it.isNotBlank() } ?: return status
    val shown = clockAt(clock, sportOf(event), event.clockReadAtMs, nowMs, event.clockMoving)
        ?: return status
    if (shown == clock) return status
    val at = status.indexOf(clock)
    return if (at >= 0) status.replaceRange(at, at + clock.length, shown) else status
}

private fun sportOf(event: SportsEvent): Sport =
    SportsCatalog.league(event.leagueId)?.sport ?: Sport.OTHER

/**
 * [fresh] with the clock anchor of every live game worked out against what [previous] showed.
 *
 * A reading that differs from the previous one is a new anchor and the clock runs; an unchanged
 * one keeps its original anchor, so a clock that has already been read the same value for
 * [heldAfterMs] is marked stopped. Values equal the old ones when neither the clock nor the
 * running state changed, which is what keeps a poll that found nothing new from re-rendering.
 */
internal fun stampLiveClocks(
    previous: List<SportsEvent>,
    fresh: List<SportsEvent>,
    nowMs: Long,
    heldAfterMs: Long = CLOCK_HELD_AFTER_MS,
): List<SportsEvent> {
    if (fresh.isEmpty()) return fresh
    val before = previous.associateBy { it.id }
    return fresh.map { game ->
        val clock = game.clock?.takeIf { it.isNotBlank() }
        if (game.state != GameState.LIVE || clock == null) {
            return@map if (game.clockReadAtMs == 0L && !game.clockMoving) {
                game
            } else {
                game.copy(clockReadAtMs = 0L, clockMoving = false)
            }
        }
        val prior = before[game.id]
        val priorClock = prior?.clock?.takeIf { it.isNotBlank() }
        if (prior == null || prior.state != GameState.LIVE || priorClock == null) {
            return@map game.copy(clockReadAtMs = nowMs, clockMoving = true)
        }
        if (priorClock != clock) {
            return@map game.copy(clockReadAtMs = nowMs, clockMoving = true)
        }
        // The same value again: keep the moment it was first seen. A cached reading carries no
        // anchor (it is not serialized), so this read has to become the anchor itself.
        val readAt = prior.clockReadAtMs.takeIf { it > 0L }
            ?: return@map game.copy(clockReadAtMs = nowMs, clockMoving = true)
        val stillMoving = prior.clockMoving && nowMs - readAt < heldAfterMs
        game.copy(clockReadAtMs = readAt, clockMoving = stillMoving)
    }
}

/**
 * A wall clock that advances once a second while [event]'s clock is worth drawing locally, and
 * stands still otherwise. Composed by the badge and the game page's large clock; every consumer
 * gets the same second because they share the system clock, not a private counter.
 */
@Composable
internal fun rememberClockNow(event: SportsEvent): Long {
    val ticks = event.isLive && event.clockMoving && event.clockReadAtMs > 0L &&
        !event.clock.isNullOrBlank() && countsDown(sportOf(event)) &&
        clockSeconds(event.clock) != null
    if (!ticks) return System.currentTimeMillis()
    var now by remember(event.id, event.clockReadAtMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(event.id, event.clockReadAtMs) {
        while (true) {
            val at = System.currentTimeMillis()
            now = at
            delay(1_000L - at % 1_000L)
        }
    }
    return now
}
