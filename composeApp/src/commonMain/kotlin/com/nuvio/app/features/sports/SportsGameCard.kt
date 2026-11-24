package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder

/**
 * One game card in the multi-column scoreboard grid, drawn as the reference TV page's list card:
 * a league header row, one shaded row per team (the followed side starred and lit), and a footer
 * with the kick-off date, venue and network.
 *
 * Scales smoothly on keyboard/controller focus and pointer hover using the user's shared Focus Zoom
 * setting ([rememberSportsInteraction]), draws an anti-aliased [smoothBorder] focus ring, and
 * supports MediaPlayPause quick-launch directly into the best matched IPTV channel.
 */
@Composable
internal fun SportsGameCard(
    event: SportsEvent,
    league: League?,
    hideScores: Boolean,
    followed: Boolean = false,
    followedSideIds: Set<String> = emptySet(),
    matchedChannelName: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onQuickWatch: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.04f, label = "sportsGameCardZoom")
    val shape = tokens.shapes.card
    val accent = league?.color?.let(::Color) ?: tokens.colors.accent
    val borderWidth = if (interaction.focused) 2.dp else tokens.borders.hairline

    Column(
        modifier = modifier
            .fillMaxWidth()
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
                onFocused = onFocused,
                onExtraKey = { keyEv ->
                    val quickPlay = onQuickWatch != null &&
                        (keyEv.key == Key.MediaPlayPause || keyEv.key == Key.MediaPlay)
                    if (quickPlay) {
                        if (keyEv.type == KeyEventType.KeyUp) onQuickWatch.invoke()
                        true
                    } else {
                        false
                    }
                },
                onClick = onClick,
            )
            .clip(shape)
            .background(if (interaction.highlighted) tokens.colors.focusBackground else tokens.colors.surfaceCard)
            .smoothBorder(
                width = borderWidth,
                color = when {
                    interaction.focused -> tokens.colors.borderFocus
                    event.isLive -> accent.copy(alpha = tokens.opacity.selected)
                    else -> tokens.colors.borderSubtle
                },
                shape = shape,
            )
            .padding(horizontal = tokens.spacing.cardPadding, vertical = tokens.spacing.controlGap),
    ) {
        // League header: mark + name, with the live clock / Final on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            if (league != null) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(accent.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val logo = league.logo?.takeIf(String::isNotBlank)
                    if (logo == null) {
                        Text(
                            text = league.shortName.take(4),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                        )
                    } else {
                        NuvioAsyncImage(
                            model = logo,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                Text(
                    text = league.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            SportsStatusBadge(event, hideScores = hideScores)
        }

        Spacer(Modifier.height(tokens.spacing.controlGap))

        if (event.away.team.id.endsWith(":event")) {
            SportsFieldEventRow(event, league, followed)
        } else {
            SportsTeamRow(event.away, hideScores, event.state, followedSideIds)
            Spacer(Modifier.height(4.dp))
            SportsTeamRow(event.home, hideScores, event.state, followedSideIds)
        }

        Spacer(Modifier.height(tokens.spacing.controlGap))

        // Footer: kick-off date · venue · a live channel, or a replay-capable source for a result.
        SportsGameFooter(event = event, league = league, matchedChannelName = matchedChannelName)
    }
}

@Composable
private fun SportsFieldEventRow(event: SportsEvent, league: League?, followed: Boolean) {
    val tokens = MaterialTheme.nuvio
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(event.away.team)
        Spacer(Modifier.width(tokens.spacing.controlGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = (if (followed) "★ " else "") + event.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (league != null) {
                Text(
                    text = league.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One side of the card: a shaded full-width row with the crest, name, record and score. The side a
 * followed team plays on is lit a touch brighter and starred, the way the reference page marks it.
 */
@Composable
private fun SportsTeamRow(
    side: GameSide,
    hideScores: Boolean,
    state: GameState,
    followedSideIds: Set<String>,
) {
    val tokens = MaterialTheme.nuvio
    val isFollowedSide = side.team.id in followedSideIds
    val dimmed = state == GameState.POST && !side.winner && !hideScores
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isFollowedSide) tokens.colors.focusBackground
                else tokens.colors.surfaceElevated.copy(alpha = 0.45f),
            )
            .padding(horizontal = tokens.spacing.controlGap, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamBadge(side.team)
        Spacer(Modifier.width(tokens.spacing.controlGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = side.team.shortName.ifBlank { side.team.name },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (state == GameState.POST && side.winner && !hideScores) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (dimmed) tokens.colors.textSecondary else tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isFollowedSide) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.accent,
                    )
                }
            }
            if (side.record != null) {
                Text(
                    text = side.record,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = when {
                hideScores && state != GameState.PRE -> "•"
                side.score != null && state != GameState.PRE -> side.score
                else -> ""
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (dimmed) tokens.colors.textSecondary else tokens.colors.textPrimary,
        )
    }
}

@Composable
private fun TeamBadge(team: Team) {
    val tokens = MaterialTheme.nuvio
    val logo = team.logo?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier.size(tokens.icons.lg),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(5.dp))
                .background(team.color?.let(::Color) ?: tokens.colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (logo == null) {
                Text(
                    text = team.abbreviation.take(4),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        logo?.let {
            NuvioAsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.size(tokens.icons.lg),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** The kick-off date, venue and matched IPTV channel / network under the team rows. */
@Composable
private fun SportsGameFooter(
    event: SportsEvent,
    league: League?,
    matchedChannelName: String?,
) {
    val tokens = MaterialTheme.nuvio
    val when2 = when (event.state) {
        GameState.PRE -> kickoffDayText(event.startMs).ifBlank { event.status }
        GameState.LIVE, GameState.POST -> null
    }
    val parts = listOfNotNull(
        when2,
        event.venue,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        val channelLabel = SportsWatch.cardChannelLabel(event, matchedChannelName)
        if (!channelLabel.isNullOrBlank()) {
            Text(
                text = channelLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (matchedChannelName != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (matchedChannelName != null) tokens.colors.accent else tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** "● 3rd · 9:42", "Final", or the kick-off time. A live clock ticks locally between reads. */
@Composable
internal fun SportsStatusBadge(event: SportsEvent, hideScores: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    val now = rememberClockNow(event)
    val (text, color) = when (event.state) {
        GameState.LIVE -> (if (hideScores) "Live" else (clockStatusAt(event, now) ?: "Live")) to tokens.colors.danger
        GameState.POST -> "Final" to tokens.colors.textMuted
        GameState.PRE -> kickoffText(event.startMs).ifBlank { event.status ?: "Scheduled" } to tokens.colors.textSecondary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = tokens.opacity.subtle))
            .padding(horizontal = tokens.spacing.controlGap, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (event.state == GameState.LIVE) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Local time of day for a kick-off: "7:30 PM" where the user is, not where the game is. */
/** The kick-off time in this device's own zone — the host OS clock, DST included. */
internal fun kickoffText(startMs: Long): String {
    if (startMs <= 0L) return ""
    return java.time.Instant.ofEpochMilli(startMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault()))
}

/** The kick-off with its date — "Sun 22 Nov 7:00 PM" — for card footers and the hero meta line. */
internal fun kickoffDayText(startMs: Long): String {
    if (startMs <= 0L) return ""
    return java.time.Instant.ofEpochMilli(startMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM h:mm a", java.util.Locale.getDefault()))
}

/** The kick-off date alone, uppercased — "SUN 22 NOV" — for the hero eyebrow. */
internal fun kickoffDayShortUpper(startMs: Long): String {
    if (startMs <= 0L) return ""
    return java.time.Instant.ofEpochMilli(startMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
        .uppercase(java.util.Locale.getDefault())
}

/**
 * The All Events row: the reference TV page's compact score line — the time or state on the left,
 * both teams inline with scores once known, and the network or matched channel at the end. These
 * rows fill the grid two to four across, so a whole day's slate stays within a glance.
 */
@Composable
internal fun SportsScoreRow(
    event: SportsEvent,
    league: League?,
    hideScores: Boolean,
    /** The league's short name, for a row inside a section grouped by sport rather than by league. */
    leagueTag: String? = null,
    followedSideIds: Set<String> = emptySet(),
    matchedChannelName: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onQuickWatch: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.04f, label = "sportsScoreRowZoom")
    val shape = tokens.shapes.card
    val accent = league?.color?.let(::Color) ?: tokens.colors.accent
    val borderWidth = if (interaction.focused) 2.dp else tokens.borders.hairline
    val isFieldEvent = event.away.team.id.endsWith(":event")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
                onFocused = onFocused,
                onExtraKey = { keyEv ->
                    val quickPlay = onQuickWatch != null &&
                        (keyEv.key == Key.MediaPlayPause || keyEv.key == Key.MediaPlay)
                    if (quickPlay) {
                        if (keyEv.type == KeyEventType.KeyUp) onQuickWatch.invoke()
                        true
                    } else {
                        false
                    }
                },
                onClick = onClick,
            )
            .clip(shape)
            .background(if (interaction.highlighted) tokens.colors.focusBackground else tokens.colors.surfaceCard)
            .smoothBorder(
                width = borderWidth,
                color = when {
                    interaction.focused -> tokens.colors.borderFocus
                    event.isLive -> accent.copy(alpha = tokens.opacity.selected)
                    else -> tokens.colors.borderSubtle
                },
                shape = shape,
            )
            .padding(horizontal = tokens.spacing.cardPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        // Time for a scheduled game, the running clock for a live one, Final for a past one.
        SportsStatusBadge(event, hideScores = hideScores)

        if (!leagueTag.isNullOrBlank()) {
            Text(
                text = leagueTag,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isFieldEvent) {
            Text(
                text = event.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            ScoreRowSide(event.away, hideScores, event.state, followedSideIds)
            Text(
                text = "@",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
            ScoreRowSide(event.home, hideScores, event.state, followedSideIds)
            Spacer(Modifier.weight(1f))
        }

        val channelLabel = SportsWatch.cardChannelLabel(event, matchedChannelName)
        if (!channelLabel.isNullOrBlank()) {
            Text(
                text = channelLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (matchedChannelName != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (matchedChannelName != null) tokens.colors.accent else tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One team inside a compact score row: crest, name, and its score once the game has started. */
@Composable
private fun ScoreRowSide(side: GameSide, hideScores: Boolean, state: GameState, followedSideIds: Set<String>) {
    val tokens = MaterialTheme.nuvio
    val dimmed = state == GameState.POST && !side.winner && !hideScores
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamBadge(side.team)
        Text(
            text = side.team.shortName.ifBlank { side.team.name },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (side.team.id in followedSideIds) FontWeight.SemiBold else FontWeight.Normal,
            color = if (dimmed) tokens.colors.textSecondary else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!hideScores && state != GameState.PRE && side.score != null) {
            Text(
                text = side.score,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (dimmed) tokens.colors.textSecondary else tokens.colors.textPrimary,
            )
        }
        if (side.team.id in followedSideIds) {
            Text(
                text = "★",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.accent,
            )
        }
    }
}
