package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * fork:game-highlights — official YouTube game highlights on a past game's detail page, in the
 * replay group's "no replay available" state. Search, playback and thumbnails all reuse the
 * trailer machinery ([TrailerPlaybackResolver.searchYouTube] for the search, the addon protocol's
 * ytId for playback through [com.nuvio.app.features.streams.YouTubeStreamResolver]), so no new
 * network stack is involved; the sports-specific work is the query and the official-only filter.
 *
 * Official means a verified channel whose name is the league or one of the two teams — the
 * channels that publish a game's own highlights ("NBA" posting "Celtics vs Lakers Highlights").
 * Unverified or unrelated uploads never appear: an empty result leaves the page as it was.
 */
internal object SportsYouTubeHighlights {

    /** Rows shown per game; more is noise on a detail page. */
    internal const val MAX_HIGHLIGHTS = 4

    /** Full-game cuts can run long; anything past an hour is not a highlights upload. */
    internal const val MAX_DURATION_SECONDS = 3_600

    /**
     * How far after kick-off an upload may still read as "on the game day": highlights go up the
     * same night, and YouTube's relative ages are coarse ("3 days ago" spans two days).
     */
    internal const val PUBLISHED_GRACE_MS = 12 * 60 * 60 * 1_000L

    private val cache = HashMap<String, Pair<Long, List<YouTubeSearchHit>>>()
    private val cacheLock = Mutex()

    /** The search asked for a game's highlights ("Celtics vs Lakers highlights NBA"). */
    internal fun searchQuery(event: SportsEvent, league: League?): String {
        // Tournaments, races and fight cards have an explicit event title; the sides may be
        // people or teams whose names search worse than the card's own title.
        event.title?.takeIf { it.isNotBlank() }?.let { title ->
            return listOfNotNull(title, "highlights", league?.shortName?.takeIf(String::isNotBlank))
                .joinToString(" ")
        }
        val away = event.away.team.shortName.ifBlank { event.away.team.name }
        val home = event.home.team.shortName.ifBlank { event.home.team.name }
        return buildList {
            add(away)
            add(home)
            add("highlights")
            league?.shortName?.takeIf(String::isNotBlank)?.let(::add)
        }.joinToString(" ")
    }

    /**
     * Whether an upload's title names this game: both teams' names, or for a titled event (races,
     * fight cards) most of the event's own title. Team matching accepts either the full or the
     * short name, so "Celtics vs Lakers Highlights" names "Boston Celtics" and the Lakers alike.
     */
    internal fun titleNamesTheMatchup(event: SportsEvent, title: String): Boolean {
        val words = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        fun named(vararg names: String?): Boolean = names.filterNotNull().any { name ->
            name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.let { parts ->
                parts.isNotEmpty() && words.containsAll(parts)
            }
        }
        event.title?.takeIf { it.isNotBlank() }?.let { eventTitle ->
            val titleWords = eventTitle.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
            return titleWords.isEmpty() || titleWords.count { it in words } >= (titleWords.size + 1) / 2
        }
        return named(event.away.team.name, event.away.team.shortName) &&
            named(event.home.team.name, event.home.team.shortName)
    }

    /**
     * One "who may have posted this" name: every word of it must appear in a channel's name.
     * Word-equality, not substring, so a "BOS" abbreviation cannot ride inside another word.
     */
    internal fun nameSpec(vararg names: String?): Set<String> = names.filterNotNull()
        .flatMap { it.lowercase().split(Regex("[^a-z0-9]+")) }
        .filter { it.length >= 3 }
        .toSet()

    /**
     * 0 when [channel] carries the league's name, 1 when it carries one of the two teams',
     * 2 (not official) otherwise.
     */
    internal fun channelRank(channel: String, leagueSpecs: List<Set<String>>, teamSpecs: List<Set<String>>): Int {
        val words = channel.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        fun matches(specs: List<Set<String>>) = specs.any { spec -> spec.isNotEmpty() && words.containsAll(spec) }
        return when {
            matches(leagueSpecs) -> 0
            matches(teamSpecs) -> 1
            else -> 2
        }
    }

    /**
     * Keeps only the official-looking highlight uploads: verified channel, a title (or channel)
     * that talks about highlights, a highlight-sized duration, and a channel the league or one of
     * the teams runs. League channels sort above team channels, then view counts, then search
     * order.
     */
    internal fun official(event: SportsEvent, league: League?, hits: List<YouTubeSearchHit>, nowMs: Long = System.currentTimeMillis()): List<YouTubeSearchHit> {
        if (hits.isEmpty()) return hits
        // The cutoff for "published after or on the game day": uploads older than the game's own
        // kickoff (minus the grace) are a different game from years past, whatever their title.
        val maxAgeMs = (nowMs - event.startMs).coerceAtLeast(0L) + PUBLISHED_GRACE_MS
        val leagueSpecs = listOfNotNull(
            nameSpec(league?.name),
            nameSpec(league?.shortName),
        ).filter { it.isNotEmpty() }
        val teamSpecs = listOfNotNull(
            nameSpec(event.away.team.name),
            nameSpec(event.away.team.shortName),
            nameSpec(event.away.team.abbreviation),
            nameSpec(event.home.team.name),
            nameSpec(event.home.team.shortName),
            nameSpec(event.home.team.abbreviation),
        ).filter { it.isNotEmpty() }
        return hits.withIndex()
            .mapNotNull { (index, hit) ->
                val rank = channelRank(hit.channel, leagueSpecs, teamSpecs)
                val talksHighlights = hit.title.contains("highlight", ignoreCase = true) ||
                    hit.channel.contains("highlight", ignoreCase = true)
                // An upload whose age YouTube does not state cannot be checked against the game
                // day, so it does not appear either.
                val age = com.nuvio.app.features.trailer.parsePublishedAgeMs(hit.publishedText)
                val publishedAfterGame = age != null && age <= maxAgeMs
                val plausible = hit.verified &&
                    rank <= 1 &&
                    talksHighlights &&
                    titleNamesTheMatchup(event, hit.title) &&
                    publishedAfterGame &&
                    hit.durationSeconds in 1..MAX_DURATION_SECONDS
                if (plausible) Triple(rank, index, hit) else null
            }
            .sortedWith(
                compareBy<Triple<Int, Int, YouTubeSearchHit>> { it.first }
                    .thenByDescending { it.third.views }
                    .thenBy { it.second },
            )
            .take(MAX_HIGHLIGHTS)
            .map { it.third }
    }

    /**
     * The highlights for one past game, searched once and remembered for the session. A search
     * that found nothing is not remembered: a game only just finished has its highlights appear
     * some time later, and the next open of the page should look again.
     */
    suspend fun highlights(event: SportsEvent, league: League?): List<YouTubeSearchHit> {
        cacheLock.withLock { cache[event.id] }?.let { (_, remembered) ->
            if (remembered.isNotEmpty()) return remembered
        }
        val found = runCatching {
            official(event, league, TrailerPlaybackResolver.searchYouTube(searchQuery(event, league)))
        }.getOrDefault(emptyList())
        if (found.isNotEmpty()) {
            cacheLock.withLock {
                if (cache.size > 64) cache.clear()
                cache[event.id] = System.currentTimeMillis() to found
            }
        }
        return found
    }

    /** The stream handed to the player: playback resolves the ytId on the device like trailers do. */
    fun streamItem(league: League?, event: SportsEvent, hit: YouTubeSearchHit): StreamItem = StreamItem(
        name = hit.title,
        title = hit.title,
        description = listOfNotNull(
            "Official highlights",
            hit.channel,
            league?.name,
        ).joinToString(" · "),
        ytId = hit.videoId,
        sources = listOf("youtube"),
        sourceName = "YouTube",
        addonName = "YouTube",
        addonId = "youtube-official-highlights",
    )

    fun thumbnailUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    fun durationLabel(seconds: Int): String {
        if (seconds <= 0) return ""
        val minutes = seconds / 60
        val rest = seconds % 60
        return "$minutes:" + rest.toString().padStart(2, '0')
    }

    internal fun seedForTest(eventId: String, hits: List<YouTubeSearchHit>) {
        cache[eventId] = 0L to hits
    }

    internal fun clearForTest() {
        cache.clear()
    }
}

/**
 * One highlight row inside the replay group's empty card. Same shape and key handling as a channel
 * row: a focus band whose Up/Down come from the page's chain, with the row itself activating on
 * Enter / rebound Select and playing through [SportsGameDetail]'s sports-stream launch.
 */
@Composable
internal fun SportsYouTubeHighlightRow(
    hit: YouTubeSearchHit,
    bandRequester: FocusRequester,
    mainRequester: FocusRequester,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val mainInteraction = rememberSportsInteraction(baseScale = 1.02f, label = "sportsHighlightZoom")
    val shape = tokens.shapes.compactCard

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sportsHorizontalBand(
                bandRequester = bandRequester,
                fallbackRequester = mainRequester,
                onUp = onUp,
                onDown = onDown,
            )
            .clip(shape)
            .background(
                if (mainInteraction.highlighted) SolidColor(tokens.colors.focusBackground)
                else SolidColor(tokens.colors.surfaceCard),
            )
            .smoothBorder(
                width = if (mainInteraction.focused) 2.dp else tokens.borders.hairline,
                color = if (mainInteraction.focused) tokens.colors.borderFocus else tokens.colors.borderSubtle,
                shape = shape,
            )
            .padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Box(
            modifier = Modifier
                .size(width = 88.dp, height = 50.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.colors.surfaceElevated),
        ) {
            NuvioAsyncImage(
                model = SportsYouTubeHighlights.thumbnailUrl(hit.videoId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .sportsInteractive(
                    state = mainInteraction,
                    focusRequester = mainRequester,
                    elevateOnFocus = false,
                    onClick = onClick,
                ),
        ) {
            Text(
                text = hit.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (mainInteraction.focused) FontWeight.SemiBold else FontWeight.Medium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    "✓ ${hit.channel}",
                    SportsYouTubeHighlights.durationLabel(hit.durationSeconds).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
