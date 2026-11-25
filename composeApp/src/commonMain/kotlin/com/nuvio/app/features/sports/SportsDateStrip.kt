package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/** One pill in the date strip. */
internal data class SportsDay(
    /** `yyyyMMdd`, the key the repository and the feed both use. */
    val day: String,
    /** "Today", "Yesterday", "Tomorrow", else the month and day ("Nov 20"). */
    val label: String,
    /** The weekday, always: "Thu". */
    val weekday: String,
)

private val DayKeyFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * The days the strip offers, [radius] either side of [today].
 */
internal fun sportsDays(
    today: LocalDate = LocalDate.now(),
    radius: Int = 3,
    locale: Locale = Locale.getDefault(),
): List<SportsDay> = (-radius..radius).map { offset ->
    val date = today.plusDays(offset.toLong())
    val label = when (offset) {
        -1 -> "Yesterday"
        0 -> "Today"
        1 -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d", locale))
    }
    SportsDay(
        day = date.format(DayKeyFormatter),
        label = label,
        weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
    )
}

/**
 * The date strip. Connected into the Sports page's vertical focus ladder: Up steps to the sport
 * filter chips, Down steps into the multi-row games grid, and entering the strip restores focus to
 * the currently selected day.
 */
@Composable
internal fun SportsDateStrip(
    days: List<SportsDay>,
    selected: String,
    bandRequester: FocusRequester,
    onSelect: (String) -> Unit,
    /** [focusedWindowX] is the focused day's centre in window space: Up may ask the bar by position. */
    onUp: (focusedWindowX: Float?) -> Boolean,
    onDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    // One clock for the strip; only the Today pill draws it. Keeping it here rather than in the
    // header means the time sits with the day it describes and moves with the calendar.
    val todayClock = rememberSportsClockText()
    val pillRequesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(dayKey: String): FocusRequester = pillRequesters.getOrPut(dayKey) { FocusRequester() }
    val selectedRequester = requesterFor(selected)
    // Where the day that holds the keys is, so Up can go to the bar icon nearest it, the way the
    // Settings rail asks the sidebar for its nearest row by height.
    var focusedWindowX by remember { mutableStateOf<Float?>(null) }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .sportsHorizontalBand(
                bandRequester = bandRequester,
                fallbackRequester = selectedRequester,
                onUp = { onUp(focusedWindowX) },
                onDown = onDown,
            ),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(days, key = { it.day }) { day ->
            SportsDayPill(
                day = day,
                selected = day.day == selected,
                focusRequester = requesterFor(day.day),
                clockText = todayClock.takeIf { day.label == "Today" },
                onFocusedWindowX = { focusedWindowX = it },
                onClick = { onSelect(day.day) },
            )
        }
    }
}

@Composable
private fun SportsDayPill(
    day: SportsDay,
    selected: Boolean,
    focusRequester: FocusRequester,
    clockText: String?,
    onFocusedWindowX: (Float) -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.05f, label = "sportsDayPillZoom")
    val shape = RoundedCornerShape(50)
    val labelColor = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary
    val sublabelColor = if (selected) {
        tokens.colors.onAccent.copy(alpha = tokens.opacity.secondary)
    } else {
        tokens.colors.textSecondary
    }
    val borderWidth = if (interaction.focused) 2.dp else tokens.borders.hairline
    var centerX by remember { mutableStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    // The keys can land on a day in the same frame the strip composes it, before it has been
    // placed: the first report was then x=0, so Up from a day on the right asked the row above for
    // the control at the far left. The position is reported again as soon as it is known, and every
    // time the strip scrolls under the keys.
    LaunchedEffect(centerX, focused) {
        if (focused && centerX > 0f) onFocusedWindowX(centerX)
    }

    Column(
        modifier = Modifier
            .widthIn(min = 84.dp)
            .onGloballyPositioned { centerX = it.boundsInWindow().center.x }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && centerX > 0f) onFocusedWindowX(centerX)
            }
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
                onClick = onClick,
            )
            .clip(shape)
            .background(
                when {
                    selected -> tokens.colors.accentFill
                    interaction.highlighted -> SolidColor(tokens.colors.focusBackground)
                    else -> SolidColor(tokens.colors.surfaceCard)
                },
            )
            .smoothBorder(
                width = borderWidth,
                color = when {
                    interaction.focused -> tokens.colors.borderFocus
                    selected -> tokens.colors.borderSelected
                    else -> tokens.colors.borderSubtle
                },
                shape = shape,
            )
            .padding(
                horizontal = tokens.components.chipHorizontalPadding,
                vertical = tokens.components.chipVerticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = day.weekday,
            style = MaterialTheme.typography.labelSmall,
            color = sublabelColor,
        )
        Text(
            text = day.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected || interaction.focused) FontWeight.SemiBold else FontWeight.Medium,
            color = labelColor,
        )
        // Today carries the live local time under its label. It is the same h:mm a text as the
        // kick-off times on the cards, so the two can be read against each other at a glance.
        if (clockText != null) {
            Text(
                text = clockText,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = sublabelColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun rememberSportsClockText(): String {
    val clockText by produceState(initialValue = kickoffText(System.currentTimeMillis())) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            value = kickoffText(System.currentTimeMillis())
        }
    }
    return clockText
}
