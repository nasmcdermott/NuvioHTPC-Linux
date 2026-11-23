package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.input.consumeOnScreenKeyboardEscapeDismissal
import com.nuvio.app.features.input.dismissOnScreenKeyboard

/**
 * Follow teams and leagues.
 *
 * Leagues come from the catalog (instant, no network); a league's teams are fetched the first time
 * it is opened and cached for a week by the repository, so the list is usable while a league is
 * still loading rather than blocking on it.
 *
 * The search box filters both at once: a viewer who knows they want "Arsenal" should not have to
 * work out which league to open first.
 */
@Composable
internal fun SportsFollowPicker(
    onDismiss: () -> Unit,
    onNavigateToLiveTv: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val settings by SportsSettingsRepository.settings.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // League id -> its teams, as they arrive. Fetching is per league and guarded by the repository.
    val teamsByLeague = remember { mutableStateMapOf<String, List<Team>>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }
    val leagues = remember { SportsCatalog.leagues }

    val needle = remember(query) { query.trim().lowercase() }
    val matchingLeagues = remember(needle, leagues) {
        if (needle.isEmpty()) leagues
        else leagues.filter { league ->
            league.name.lowercase().contains(needle) ||
                league.shortName.lowercase().contains(needle) ||
                league.guideWords.any { it.lowercase().contains(needle) }
        }
    }

    // Open the picker on the user's followed things, so it reads as a summary as well as a picker.
    LaunchedEffect(Unit) {
        leagues.forEach { league -> if (league.id in settings.followedLeagues) loadTeams(league.id, teamsByLeague, loading) }
    }

    val followedCount = SportsFollows.count(settings)

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Follow teams and leagues",
        subtitle = if (followedCount == 0) {
            "Pick the teams and competitions you care about. Their games lead the Sports page."
        } else {
            "$followedCount followed. Their games lead the Sports page."
        },
        maxWidth = 720.dp,
        actions = {
            com.nuvio.app.features.settings.SettingsGlassButton(
                text = if (followedCount == 0) "Done" else "Clear",
                onClick = {
                    if (followedCount == 0) onDismiss() else SportsSettingsRepository.clearFollows()
                },
            )
            com.nuvio.app.features.settings.SettingsGlassButton(
                text = "Done",
                onClick = onDismiss,
                accent = true,
            )
        },
    ) {
        // Flattened into rows before the list: LazyColumn items cannot nest, and a league's teams
        // are part of the same scroll rather than a sub-list. Built before the search box because
        // the box's keys need to know what there is to move to.
        val rows = buildList<PickerRow> {
            matchingLeagues.forEach { league ->
                val followed = SportsFollows.isLeagueFollowed(settings, league.id)
                val expanded = needle.isNotEmpty() || followed || league.id in teamsByLeague
                if (expanded) loadTeams(league.id, teamsByLeague, loading)
                add(PickerRow.LeagueRow(league, followed))
                if (!expanded) return@forEach
                val teams = teamsByLeague[league.id]
                when {
                    loading[league.id] == true && teams == null ->
                        add(PickerRow.NoteRow("Loading ${league.name} teams…", spinner = true))
                    teams.isNullOrEmpty() ->
                        add(PickerRow.NoteRow("No teams available for ${league.name}."))
                    else -> {
                        val shown = if (needle.isEmpty()) {
                            teams
                        } else {
                            teams.filter { team ->
                                team.name.lowercase().contains(needle) ||
                                    team.shortName.lowercase().contains(needle) ||
                                    team.abbreviation.lowercase().contains(needle)
                            }
                        }
                        if (shown.isEmpty() && needle.isNotEmpty()) {
                            add(PickerRow.NoteRow("No ${league.name} team matches “$query”."))
                        } else {
                            shown.forEach { team ->
                                add(
                                    PickerRow.TeamRow(
                                        leagueId = league.id,
                                        team = team,
                                        followed = SportsFollows.isTeamFollowed(settings, league.id, team.id),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        val listState = rememberLazyListState()
        val listFocus = rememberSportsListFocus(listState)
        val focusableLazyIndices = remember(rows) {
            rows.mapIndexedNotNull { index, row ->
                index.takeIf { row is PickerRow.LeagueRow || row is PickerRow.TeamRow }
            }
        }
        val focusRowForLazyIndex = remember(focusableLazyIndices) {
            focusableLazyIndices.withIndex().associate { it.value to it.index }
        }
        val searchFocus = remember { FocusRequester() }

        /** Moves the keys into the results; false when there is nothing to move to. */
        fun focusResultRow(row: Int): Boolean {
            if (focusableLazyIndices.isEmpty()) return false
            val safeRow = row.coerceIn(0, focusableLazyIndices.lastIndex)
            return listFocus.focusRow(safeRow, focusableLazyIndices[safeRow], focusableLazyIndices.size)
        }

        NuvioTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val isDown = event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown
                    // Only Escape counts as Back in here: Backspace still deletes characters while
                    // the box has them, and the dialog's own Back declines while a field holds the
                    // keys (see dismissOnBackKey), so Escape is the one press that would otherwise
                    // be swallowed with nothing happening.
                    val target = searchBoxFocusTarget(
                        isDown = isDown,
                        isBack = event.navigationKey() == Key.Escape,
                        hasResults = focusableLazyIndices.isNotEmpty(),
                        lastRow = listFocus.focusedRowIndex,
                    ) ?: return@onPreviewKeyEvent false
                    if (isDown) return@onPreviewKeyEvent focusResultRow(target)
                    // Back: the on-screen keyboard goes first, then the results it pulled up.
                    if (event.type == KeyEventType.KeyUp) {
                        when {
                            consumeOnScreenKeyboardEscapeDismissal() -> Unit
                            dismissOnScreenKeyboard() -> Unit
                            else -> focusResultRow(target)
                        }
                    }
                    // Both halves are eaten while there are results, so the dialog's own Back does
                    // not close the picker on the same press. With none, Back still closes it.
                    true
                },
            placeholder = "Search teams and leagues",
            focusRequester = searchFocus,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .onKeyEvent { event ->
                    listFocus.handleVerticalAndSideKeys(
                        event = event,
                        rowCount = focusableLazyIndices.size,
                        lazyIndexOfRow = { row -> focusableLazyIndices.getOrElse(row) { 0 } },
                        // Up from the first result returns to the box that produced it.
                        onUpFromFirst = { searchFocus.requestFocusLogged() },
                    )
                },
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { lazyIndex, row ->
                val focusRow = focusRowForLazyIndex[lazyIndex]
                val focusRequester = focusRow?.let(listFocus::requester)
                val onFocused = focusRow?.let { rowIndex ->
                    { listFocus.focusedRowIndex = rowIndex }
                }
                when (row) {
                    is PickerRow.LeagueRow -> SportsPickerLeagueHeader(
                        league = row.league,
                        followed = row.followed,
                        focusRequester = focusRequester,
                        onFocused = onFocused,
                        onToggleFollow = {
                            SportsSettingsRepository.setLeagueFollowed(row.league.id, !row.followed)
                        },
                    )
                    is PickerRow.TeamRow -> SportsPickerTeamRow(
                        team = row.team,
                        followed = row.followed,
                        focusRequester = focusRequester,
                        onFocused = onFocused,
                        onToggle = {
                            SportsSettingsRepository.setTeamFollowed(
                                SportsFollows.teamKey(row.leagueId, row.team.id),
                                !row.followed,
                            )
                        },
                    )
                    is PickerRow.NoteRow -> SportsPickerNote(row.text, showSpinner = row.spinner)
                }
            }
        }

        if (onNavigateToLiveTv != null) {
            Text(
                text = "Games are found on your Live TV channels. Add a playlist under Live TV.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onNavigateToLiveTv)
                    .padding(top = tokens.spacing.controlGap),
            )
        }
    }
}

/**
 * Where a key pressed inside the search box sends the keys, as a result row, or null when the press
 * is not the box's to answer.
 *
 * The box must not be a trap: Compose keeps the raw arrow keys for a text field's cursor, so nothing
 * outside it will move the focus, and Back inside a modal is the one press that quietly does nothing
 * at all (the dialog declines it while a field holds the keys). Down therefore always opens the
 * **first** result, and Back returns to the row the viewer last had - the results it pulled up -
 * rather than closing the picker.
 */
internal fun searchBoxFocusTarget(
    isDown: Boolean,
    isBack: Boolean,
    hasResults: Boolean,
    lastRow: Int,
): Int? = when {
    !hasResults -> null
    isDown -> 0
    isBack -> lastRow.coerceAtLeast(0)
    else -> null
}

/** One line of the picker: a league header, one of its teams, or a note under either. */
private sealed interface PickerRow {
    val key: String

    data class LeagueRow(val league: League, val followed: Boolean) : PickerRow {
        override val key = "league:${league.id}"
    }

    data class TeamRow(val leagueId: String, val team: Team, val followed: Boolean) : PickerRow {
        override val key = "team:$leagueId:${team.id}"
    }

    data class NoteRow(val text: String, val spinner: Boolean = false) : PickerRow {
        override val key = "note:$text"
    }
}

/** Teams are fetched once per league and kept for the life of the picker. */
private fun loadTeams(
    leagueId: String,
    into: MutableMap<String, List<Team>>,
    loading: MutableMap<String, Boolean>,
) {
    if (into.containsKey(leagueId) || loading[leagueId] == true) return
    loading[leagueId] = true
    SportsRepository.teams(leagueId) { teams ->
        into[leagueId] = teams
        loading.remove(leagueId)
    }
}

/** A league row: the follow toggle for the whole competition. */
@Composable
private fun SportsPickerLeagueHeader(
    league: League,
    followed: Boolean,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onToggleFollow: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.02f, label = "sportsFollowLeagueZoom")
    val shape = tokens.shapes.compactCard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
                onFocused = onFocused,
                onClick = onToggleFollow,
            )
            .clip(shape)
            .background(
                if (interaction.highlighted) SolidColor(tokens.colors.focusBackground)
                else SolidColor(tokens.colors.surfaceCard),
            )
            .smoothBorder(
                width = if (interaction.focused) 2.dp else tokens.borders.hairline,
                color = when {
                    interaction.focused -> tokens.colors.borderFocus
                    followed -> tokens.colors.borderSelected
                    else -> tokens.colors.borderSubtle
                },
                shape = shape,
            )
            .padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Box(
            Modifier
                .size(tokens.icons.sm)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(league.color)),
        )
        Text(
            text = league.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (interaction.focused || followed) FontWeight.SemiBold else FontWeight.Medium,
            color = tokens.colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (followed) "Following" else "Follow league",
            style = MaterialTheme.typography.labelSmall,
            color = if (followed) tokens.colors.accent else tokens.colors.textMuted,
        )
        SportsFollowCheck(followed)
    }
}

/** A team row, indented under its league. */
@Composable
private fun SportsPickerTeamRow(
    team: Team,
    followed: Boolean,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.02f, label = "sportsFollowTeamZoom")
    val shape = tokens.shapes.compactCard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = tokens.icons.md)
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
                onFocused = onFocused,
                onClick = onToggle,
            )
            .clip(shape)
            .background(
                if (interaction.highlighted) SolidColor(tokens.colors.focusBackground)
                else SolidColor(Color.Transparent),
            )
            .then(
                if (interaction.focused) {
                    Modifier.smoothBorder(2.dp, tokens.colors.borderFocus, shape)
                } else {
                    Modifier
                },
            )
            .padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Box(
            Modifier
                .size(tokens.icons.sm)
                .clip(RoundedCornerShape(3.dp))
                .background(team.color?.let(::Color) ?: tokens.colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            val logo = team.logo?.takeIf { it.isNotBlank() }
            if (logo == null) {
                Text(
                    text = team.abbreviation.take(4),
                    fontSize = 8.sp,
                    color = Color.White,
                    maxLines = 1,
                )
            } else {
                NuvioAsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.icons.sm),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = team.name,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (team.shortName.isNotBlank() && team.shortName != team.name) {
                Text(
                    text = team.shortName,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        SportsFollowCheck(followed)
    }
}

/** The tick, or an empty slot so rows do not jump when one is followed. */
@Composable
private fun SportsFollowCheck(followed: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(Modifier.size(tokens.icons.md), contentAlignment = Alignment.Center) {
        if (followed) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Following",
                tint = tokens.colors.accent,
                modifier = Modifier.size(tokens.icons.sm),
            )
        }
    }
}

@Composable
private fun SportsPickerNote(text: String, showSpinner: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = tokens.icons.md, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(tokens.icons.xs),
                strokeWidth = 1.dp,
                color = tokens.colors.textMuted,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}
