package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.RootNavFocus
import com.nuvio.app.core.ui.arrowFocusDirection
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
import com.nuvio.app.core.ui.keyLayer
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.smoothBorder
import java.time.LocalDate
import com.nuvio.app.features.iptv.IptvContentState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.iptv.IptvEpgRepository
import com.nuvio.app.features.iptv.IptvRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * The Sports tab.
 *
 * Connected into a complete vertical keyboard/controller focus ladder:
 *  Floating Top Bar ⇄ Sports Header (Games/Tables · Refresh · Settings)
 *                   ⇄ Sport & My Sports Filter Chips
 *                   ⇄ Date Strip (Games) / League Strip (Tables)
 *                   ⇄ Multi-Row Scoreboard Grid / League Table / Game Detail
 *
 * All interactive elements honour Settings > Appearance > Focus zoom via [rememberSportsInteraction].
 */
@Composable
internal fun SportsScreen(
    modifier: Modifier = Modifier,
    topChromePadding: androidx.compose.ui.unit.Dp? = null,
    onFindSportsStreams: (SportsEvent, League, String?) -> Unit = { _, _, _ -> },
    onPlaySportsStream: (SportsEvent, League, StreamItem) -> Unit = { _, _, _ -> },
) {
    val tokens = MaterialTheme.nuvio
    val browsePipActive by com.nuvio.app.features.player.PlayerBrowsePip.active.collectAsStateWithLifecycle()
    val settings by SportsSettingsRepository.settings.collectAsState()
    val days = remember { sportsDays(LocalDate.now(), radius = 8) }
    var selectedDay by rememberSaveable {
        mutableStateOf(days.firstOrNull { it.label == "Today" }?.day ?: days.getOrNull(3)?.day ?: "")
    }
    var selectedSport by rememberSaveable { mutableStateOf<String?>(null) }
    var openGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var mySportsOnly by rememberSaveable { mutableStateOf(true) }
    var tablesMode by rememberSaveable { mutableStateOf(false) }
    var tableRequest by remember { mutableStateOf<TableOpenRequest?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // The game the hero banner leads with, reported up from the day list so the ladder above it can
    // step into the hero before the grid.
    var heroGame by remember { mutableStateOf<SportsEvent?>(null) }

    // Focus bands in top-to-bottom ladder order.
    val headerBandFocus = remember { FocusRequester() }
    // A pushed route (the player, a game's details) closes and leaves no focused node behind: the
    // window then drops every key, Back included, until something is clicked. Registering the page's
    // own way in lets the return ask for the keys instead (AppTabHost's returning-route hand-back).
    com.nuvio.app.core.ui.BarPageEntry { headerBandFocus.requestFocusLogged() }
    val modeGamesFocus = remember { FocusRequester() }
    val chipsBandFocus = remember { FocusRequester() }
    val dateBandFocus = remember { FocusRequester() }
    val tablesBandFocus = remember { FocusRequester() }
    val detailBandFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    var enterGamesGridRequests by remember { mutableIntStateOf(0) }

    // Offer the follow picker once on the very first visit when nothing is followed yet.
    LaunchedEffect(Unit) {
        if (!settings.setUp && SportsFollows.isEmpty(settings)) {
            pickerOpen = true
            SportsSettingsRepository.markSetUp()
        }
    }

    // Pull the playlists' channels and EPG guides in when the tab opens, so games match to the
    // user's channels without a trip through Live TV first (the reference fetches these too).
    LaunchedEffect(Unit) {
        IptvRepository.ensureLoaded()
        IptvRepository.settings.value.playlists.forEach { playlist ->
            val contentMissing = IptvRepository.contentState(playlist.id).value.content == null
            val guideMissing = IptvEpgRepository.guideState(playlist.id).value.guide == null
            if (contentMissing || guideMissing) IptvRepository.refreshInBackground(playlist.id)
        }
    }

    val leagues = remember(settings.region, settings.hiddenLeagues) {
        SportsCatalog.leaguesIn(settings.region.ifBlank { null })
            .filter { it.id !in settings.hiddenLeagues }
    }
    val sports = remember(leagues) { SportsCatalog.sportsOf(leagues) }
    val sport = selectedSport?.let { name -> sports.firstOrNull { it.name == name } }
    val visibleLeagues = remember(leagues, sport) {
        if (sport == null) leagues else leagues.filter { it.sport == sport }
    }

    // Fetch the selected day for visible leagues, then keep polling while there is something live
    // to follow: today always (its schedule and kick-offs change), other days only while one of
    // their games is actually in progress — which is also how yesterday's still-live feed entries
    // finish out to Final without a restart. Leaving the tab disposes this effect, which is the
    // cleanup: no timer outlives the screen, and a change of day or league set restarts this one
    // effect rather than stacking a second loop alongside it.
    LaunchedEffect(selectedDay, visibleLeagues) {
        val isToday = selectedDay == SportsRepository.today()
        while (true) {
            visibleLeagues.forEach { SportsRepository.refresh(it.id, selectedDay) }
            // The provider rewrites a game's clock only when it stops (a score, a timeout, a dead
            // ball), roughly once a minute, so polling every second buys nothing; ~30 s tracks a
            // live score, clock and play-by-play without re-rendering the page between fetches —
            // the per-league/day state flows update the grid's keyed cards in place.
            delay(SportsRepository.liveRefreshMs)
            if (!isToday) {
                val anyLive = visibleLeagues.any { league ->
                    SportsRepository.dayState(league.id, selectedDay).value.games.any {
                        resolvedGameState(it, System.currentTimeMillis()) == GameState.LIVE
                    }
                }
                if (!anyLive) return@LaunchedEffect
            }
        }
    }

    val openGame = openGameId?.let { id ->
        leagues.asSequence()
            .mapNotNull { league ->
                val dayHit = SportsRepository.dayState(league.id, selectedDay).value.games.firstOrNull { it.id == id }
                    // "Games ahead" rows list future days that no day-state holds, so the hero and the
                    // team sections can open into the detail from those too.
                    ?: SportsRepository.aheadState(league.id).value.games.firstOrNull { it.id == id }
                    ?: SportsRepository.knownGames(league.id).firstOrNull { it.id == id }
                // The raw feed state can be a stale snapshot; the detail page must reason about the
                // state as of now, or a finished yesterday game opens as a live scoreboard.
                dayHit?.let { it.withResolvedState() to league }
            }
            .firstOrNull()
    }

    // Seat initial keyboard/controller focus when arriving on the tab.
    //
    // A returning route can still own focus for part of its exit animation — closing the player over
    // this page is the case that showed it: one request at a fixed delay was refused and never
    // retried, so the page was left with no focused control at all, and every key (Back included)
    // then went nowhere. Retry over a short bounded window, and stop the moment focus lands.
    LaunchedEffect(Unit) {
        repeat(20) { attempt ->
            delay(if (attempt == 0) 60 else 50)
            if (pickerOpen) return@LaunchedEffect
            val took = when {
                tablesMode -> tablesBandFocus.requestFocusLogged()
                openGame != null -> detailBandFocus.requestFocusLogged()
                else -> dateBandFocus.requestFocusLogged()
            }
            if (took) return@LaunchedEffect
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .keyLayer("Sports", active = !pickerOpen)
            .arrowKeyFocusNavigation(releaseWhenStuck = true)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.navigationKey() == Key.Escape) {
                    when {
                        tablesMode && tableRequest?.returnToGameId != null -> {
                            val returnId = tableRequest?.returnToGameId
                            tableRequest = null
                            tablesMode = false
                            openGameId = returnId
                            true
                        }
                        openGameId != null -> {
                            openGameId = null
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topChromePadding ?: 24.dp)
                .padding(horizontal = tokens.spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap + 4.dp),
        ) {
            SportsHeaderBand(
                tablesMode = tablesMode,
                mySportsOnly = mySportsOnly,
                followedCount = SportsFollows.count(settings),
                liveCount = rememberLiveGameCount(visibleLeagues, selectedDay),
                showExpandPlayer = browsePipActive,
                onExpandPlayer = com.nuvio.app.features.player.PlayerBrowsePip::requestExpand,
                onClosePlayer = com.nuvio.app.features.player.PlayerBrowsePip::requestClose,
                headerBandFocus = headerBandFocus,
                modeGamesFocus = modeGamesFocus,
                onSelectTablesMode = { wantTables ->
                    tablesMode = wantTables
                    tableRequest = null
                    openGameId = null
                },
                onToggleMySports = { mySportsOnly = !mySportsOnly; openGameId = null },
                onOpenPicker = { pickerOpen = true },
                onRefresh = {
                    if (tablesMode) {
                        visibleLeagues.forEach { league ->
                            // Cleared via force on next load; also refresh today's scoreboard.
                            SportsRepository.refresh(league.id, selectedDay, force = true)
                        }
                    } else {
                        visibleLeagues.forEach { SportsRepository.refresh(it.id, selectedDay, force = true) }
                    }
                },
                onDown = {
                    when {
                        openGame != null && !tablesMode -> detailBandFocus.requestFocusLogged()
                        tablesMode -> tablesBandFocus.requestFocusLogged()
                        !mySportsOnly -> chipsBandFocus.requestFocusLogged()
                        heroGame != null -> heroFocus.requestFocusLogged()
                        else -> dateBandFocus.requestFocusLogged()
                    }
                },
            )

            if (!tablesMode && openGame == null && !mySportsOnly) {
                SportsChooseLeaguesBand(
                    sports = sports,
                    selected = sport,
                    leagueCount = visibleLeagues.size,
                    bandRequester = chipsBandFocus,
                    onSelect = { selectedSport = it?.name },
                    onOpenPicker = { pickerOpen = true },
                    onUp = { headerBandFocus.requestFocusLogged() },
                    onDown = { dateBandFocus.requestFocusLogged() },
                )
            }

            if (!tablesMode && openGame == null) {
                SportsDateStrip(
                    days = days,
                    selected = selectedDay,
                    bandRequester = dateBandFocus,
                    onSelect = { selectedDay = it; openGameId = null },
                    // Up asks the bar by where the day is, the way the Settings rail asks the
                    // sidebar for its nearest row: a day on the right reaches the right of the bar
                    // (and the page's top-right buttons from there), a day on the left the icon
                    // nearest it. With no bar drawn it keeps the old ladder — the sport chips, or
                    // the header band on the My Sports layout.
                    onUp = { focusedWindowX ->
                        (focusedWindowX != null &&
                            (
                                RootNavFocus.enterHorizontalEntry(focusedWindowX) ||
                                    RootNavFocus.enterTopBarNear(focusedWindowX)
                                )
                            ) ||
                            if (!mySportsOnly) {
                                chipsBandFocus.requestFocusLogged()
                            } else {
                                headerBandFocus.requestFocusLogged()
                            }
                    },
                    onDown = {
                        if (mySportsOnly && heroGame != null) {
                            heroFocus.requestFocusLogged()
                        } else {
                            enterGamesGridRequests++
                            true
                        }
                    },
                )
            }

            if (tablesMode) {
                SportsTablesView(
                    leagues = visibleLeagues,
                    mySportsOnly = mySportsOnly,
                    settings = settings,
                    request = tableRequest,
                    leagueStripFocus = tablesBandFocus,
                    onUpToChips = { chipsBandFocus.requestFocusLogged() },
                    onBackToGame = {
                        val returnId = tableRequest?.returnToGameId
                        tableRequest = null
                        tablesMode = false
                        openGameId = returnId
                    },
                    onOpenGame = { gameId ->
                        tableRequest = null
                        tablesMode = false
                        openGameId = gameId
                    },
                    modifier = Modifier.weight(1f),
                )
            } else if (openGame != null) {
                val gameLeague = openGame.second
                val canShowTable = gameLeague.sport !in NO_TABLE_SPORTS
                SportsGameDetail(
                    event = openGame.first,
                    league = gameLeague,
                    hideScores = settings.hideScores,
                    onFindSportsStreams = { addonId -> onFindSportsStreams(openGame.first, gameLeague, addonId) },
                    onPlaySportsStream = { stream -> onPlaySportsStream(openGame.first, gameLeague, stream) },
                    entryFocus = detailBandFocus,
                    onUpToHeader = { headerBandFocus.requestFocusLogged() },
                    onBack = { openGameId = null },
                    onOpenTable = if (canShowTable) {
                        {
                            tableRequest = TableOpenRequest(
                                leagueId = gameLeague.id,
                                markedTeamIds = setOf(openGame.first.away.team.id, openGame.first.home.team.id)
                                    .filter { it.isNotBlank() }.toSet(),
                                returnToGameId = openGame.first.id,
                            )
                            tablesMode = true
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                SportsDayList(
                    day = selectedDay,
                    leagues = visibleLeagues,
                    settings = settings,
                    mySportsOnly = mySportsOnly,
                    hideScores = settings.hideScores,
                    enterGridRequests = enterGamesGridRequests,
                    heroFocus = heroFocus,
                    onHeroAvailable = { heroGame = it },
                    onUpToDateStrip = { dateBandFocus.requestFocusLogged() },
                    onUpFromGrid = {
                        if (mySportsOnly && heroGame != null) heroFocus.requestFocusLogged()
                        else dateBandFocus.requestFocusLogged()
                    },
                    onEnterGrid = {
                        enterGamesGridRequests++
                        true
                    },
                    onOpenGame = { openGameId = it },
                    onOpenPicker = { pickerOpen = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (pickerOpen) {
            SportsFollowPicker(
                onDismiss = {
                    pickerOpen = false
                    SportsSettingsRepository.markSetUp()
                },
            )
        }
    }
}

@Composable
internal fun SportsHeaderBand(
    tablesMode: Boolean,
    mySportsOnly: Boolean,
    followedCount: Int,
    liveCount: Int,
    showExpandPlayer: Boolean,
    onExpandPlayer: () -> Unit,
    onClosePlayer: () -> Unit,
    headerBandFocus: FocusRequester,
    modeGamesFocus: FocusRequester,
    onSelectTablesMode: (Boolean) -> Unit,
    onToggleMySports: () -> Unit,
    onOpenPicker: () -> Unit,
    onRefresh: () -> Unit,
    onDown: () -> Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val mySportsChipFocus = remember { FocusRequester() }
    val allEventsChipFocus = remember { FocusRequester() }
    val tablesChipFocus = remember { FocusRequester() }
    val followPillFocus = remember { FocusRequester() }
    // The mini player's own two pills are drawn between the bar's right end and the Follow pill, so
    // the group's leftmost control is the one the bar steps into — and the one Left steps back out
    // of. Pointing at the Follow pill skipped them: the expand and close buttons could not be
    // reached with the keys at all while a stream played in the corner.
    val expandPillFocus = remember { FocusRequester() }
    val closePillFocus = remember { FocusRequester() }
    val refreshPillFocus = remember { FocusRequester() }
    val rightGroupEdgeFocus = if (showExpandPlayer) expandPillFocus else followPillFocus
    // Where each control of this row is drawn, so a control below it (the calendar's Up) can reach
    // the one over it: the chips on the left, the corner player's buttons and the picker on the
    // right, and nothing under the middle, where the floating bar answers instead.
    val controlBounds = remember { androidx.compose.runtime.mutableStateMapOf<Any, ClosedFloatingPointRange<Float>>() }
    fun boundsOf(key: Any): Modifier = Modifier.onGloballyPositioned { coords ->
        val bounds = coords.boundsInWindow()
        controlBounds[key] = bounds.left.toFloat()..bounds.right.toFloat()
    }
    com.nuvio.app.core.ui.HorizontalEntryHandoff { windowX ->
        val hit = listOf(
            mySportsChipFocus, allEventsChipFocus, tablesChipFocus,
            expandPillFocus, closePillFocus, followPillFocus, refreshPillFocus,
        ).firstOrNull { controlBounds[it]?.contains(windowX) == true } ?: return@HorizontalEntryHandoff false
        com.nuvio.app.core.ui.requestFocusInsideGroup(headerBandFocus, hit)
    }
    val leftLeavesTheGroup: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { ev ->
        arrowFocusDirection(ev) == FocusDirection.Left && RootNavFocus.enterTopBarEnd(left = false)
    }
    // The chips and pills are the two halves of the row the floating bar sits in the middle of.
    // Registering them lets the bar's ends step into them; the helper drops the registration while
    // the page is covered, so a screen behind another cannot answer for it.
    com.nuvio.app.core.ui.BarSideFocusHandoff(
        // Enter the band first: its focusRestorer otherwise redirects a direct request back to
        // the last chip the person used, so Left/Right from the bar appeared to do nothing.
        left = { com.nuvio.app.core.ui.requestFocusInsideGroup(headerBandFocus, tablesChipFocus) },
        right = {
            com.nuvio.app.core.ui.requestFocusInsideGroup(headerBandFocus, rightGroupEdgeFocus)
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sportsHorizontalBand(
                bandRequester = headerBandFocus,
                fallbackRequester = if (mySportsOnly) mySportsChipFocus else allEventsChipFocus,
                // Returning false at the top band lets App's tabSidebarHandoff enter the floating top bar!
                onUp = { false },
                onDown = onDown,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "my-sports") {
                SportsChip(
                    label = if (followedCount == 0) "★ My Sports" else "★ My Sports ($followedCount)",
                    selected = mySportsOnly,
                    focusRequester = mySportsChipFocus,
                    modifier = boundsOf(mySportsChipFocus),
                    onClick = onToggleMySports,
                )
            }
            item(key = "all-events") {
                SportsChip(
                    label = if (liveCount > 0) "All Events · $liveCount live" else "All Events",
                    selected = !mySportsOnly,
                    focusRequester = allEventsChipFocus,
                    modifier = boundsOf(allEventsChipFocus),
                    onClick = { if (mySportsOnly) onToggleMySports() },
                )
            }
            item(key = "tables") {
                SportsChip(
                    label = "Tables",
                    selected = tablesMode,
                    focusRequester = tablesChipFocus,
                    modifier = boundsOf(tablesChipFocus),
                    // The bar is drawn to the right of these chips: Right enters it at its left end
                    // (Home), so the icons read on from there instead of the focus stepping over the
                    // bar to the pills on its other side.
                    onExtraKey = { ev ->
                        arrowFocusDirection(ev) == FocusDirection.Right &&
                            RootNavFocus.enterTopBarEnd(left = true)
                    },
                    onClick = { onSelectTablesMode(!tablesMode) },
                )
            }
        }
        if (showExpandPlayer) {
            SportsHeaderPill(
                label = "Expand player",
                icon = Icons.Rounded.Fullscreen,
                focusRequester = expandPillFocus,
                modifier = boundsOf(expandPillFocus),
                // The group's left edge, beside the bar's right end: Left goes back into the bar.
                onExtraKey = leftLeavesTheGroup,
                onClick = onExpandPlayer,
            )
            SportsHeaderPill(
                label = "Close mini player",
                icon = Icons.Rounded.Close,
                focusRequester = closePillFocus,
                modifier = boundsOf(closePillFocus),
                onClick = onClosePlayer,
            )
        }
        SportsHeaderPill(
            label = "✎ Follow Teams & Leagues",
            focusRequester = followPillFocus,
            modifier = boundsOf(followPillFocus),
            // Only the group's left edge goes back into the bar; with the mini player up that is the
            // expand button, so this pill keeps its normal Left (to the close button beside it).
            onExtraKey = if (showExpandPlayer) null else leftLeavesTheGroup,
            onClick = onOpenPicker,
        )
        SportsHeaderPill(
            label = "↻",
            focusRequester = refreshPillFocus,
            modifier = boundsOf(refreshPillFocus),
            onClick = onRefresh,
        )
    }
}

@Composable
private fun SportsHeaderPill(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onExtraKey: ((KeyEvent) -> Boolean)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.05f, label = "sportsHeaderPillZoom")
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
                onExtraKey = onExtraKey,
                onClick = onClick,
            )
            .clip(shape)
            .background(
                if (interaction.highlighted) SolidColor(tokens.colors.focusBackground)
                else SolidColor(tokens.colors.surfaceCard),
            )
            .smoothBorder(
                width = if (interaction.focused) 2.dp else tokens.borders.hairline,
                color = if (interaction.focused) tokens.colors.borderFocus else tokens.colors.borderSubtle,
                shape = shape,
            )
            .then(if (icon == null) Modifier.padding(horizontal = 12.dp, vertical = 6.dp) else Modifier.size(36.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (interaction.highlighted) tokens.colors.accent else tokens.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (interaction.focused) FontWeight.SemiBold else FontWeight.Medium,
                color = if (interaction.highlighted) tokens.colors.accent else tokens.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The "Choose leagues" band: the sport filter chips plus the follow picker, shown on All Events.
 * My Sports hides it — the reference page keeps its sport tabs on the everything-view only.
 */
@Composable
private fun SportsChooseLeaguesBand(
    sports: List<Sport>,
    selected: Sport?,
    leagueCount: Int,
    bandRequester: FocusRequester,
    onSelect: (Sport?) -> Unit,
    onOpenPicker: () -> Unit,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val firstChipFocus = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sportsHorizontalBand(
                bandRequester = bandRequester,
                fallbackRequester = firstChipFocus,
                onUp = onUp,
                onDown = onDown,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Text(
            text = if (leagueCount == 1) "Showing 1 league" else "Showing $leagueCount leagues",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "all-sports") {
                SportsChip(
                    label = "All",
                    selected = selected == null,
                    focusRequester = firstChipFocus,
                    onClick = { onSelect(null) },
                )
            }
            items(sports, key = { it.name }) { sport ->
                SportsChip(
                    label = sport.label,
                    selected = sport == selected,
                    onClick = { onSelect(if (sport == selected) null else sport) },
                    icon = sportIcon(sport),
                )
            }
        }
        SportsChip(label = "Change Leagues ›", selected = false, onClick = onOpenPicker)
    }
}

/** How many of the page's games are live right now, for the All Events chip's "· 6 live" tag. */
@Composable
private fun rememberLiveGameCount(leagues: List<League>, day: String): Int {
    val states = leagues.map { league ->
        val state by remember(league.id, day) { SportsRepository.dayState(league.id, day) }.collectAsState()
        state
    }
    return states.sumOf { state ->
        state.games.count { resolvedGameState(it, System.currentTimeMillis()) == GameState.LIVE }
    }
}

/**
 * The games view: the hero banner leading the day's game set, then a multi-column, multi-row
 * scoreboard grid.
 *
 * On My Sports the grid is the reference page's shape: one "Live & Upcoming" section followed by a
 * "Steelers · 2nd in AFC North — 8 games ahead" section per followed team, with that team's coming
 * games. On All Events it stays grouped into LIVE NOW and per-league COMING UP / RESULTS sections.
 */
@Composable
private fun SportsDayList(
    day: String,
    leagues: List<League>,
    settings: SportsSettings,
    mySportsOnly: Boolean,
    hideScores: Boolean,
    enterGridRequests: Int,
    heroFocus: FocusRequester,
    onHeroAvailable: (SportsEvent?) -> Unit,
    onUpToDateStrip: () -> Boolean,
    onUpFromGrid: () -> Boolean,
    onEnterGrid: () -> Boolean,
    onOpenGame: (String) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    remember { IptvRepository.ensureLoaded() }
    val iptvSettings by IptvRepository.settings.collectAsState()
    val channelSourceStates by remember(iptvSettings.playlists) {
        val sources = iptvSettings.playlists.map { IptvRepository.contentState(it.id) }
        if (sources.isEmpty()) flowOf<List<IptvContentState>>(emptyList())
        else combine(sources) { states -> states.toList() }
    }.collectAsState(initial = emptyList())
    val guideStates by remember(iptvSettings.playlists) {
        val sources = iptvSettings.playlists.map { IptvEpgRepository.guideState(it.id) }
        if (sources.isEmpty()) flowOf<List<IptvEpgRepository.GuideState>>(emptyList())
        else combine(sources) { states -> states.toList() }
    }.collectAsState(initial = emptyList())
    var sections = collectSections(leagues, day, settings, mySportsOnly)
    // My Sports never hides a live game: when nothing followed is on today's slate, fall back to
    // everything, so a live game in a league the user has not picked still shows up.
    val fellBackToAll = mySportsOnly && !SportsFollows.isEmpty(settings) &&
        sections.live.isEmpty() && sections.upcoming.isEmpty() && sections.results.isEmpty()
    if (fellBackToAll) {
        sections = collectSections(leagues, day, settings, mySportsOnly = false)
    }
    val byId = remember(leagues) { leagues.associateBy { it.id } }
    val emptyActionFocus = remember { FocusRequester() }
    val teamMode = mySportsOnly && !SportsFollows.isEmpty(settings)

    if (mySportsOnly && SportsFollows.isEmpty(settings)) {
        LaunchedEffect(enterGridRequests) {
            if (enterGridRequests > 0) emptyActionFocus.requestFocusLogged()
        }
        LaunchedEffect(Unit) { onHeroAvailable(null) }
        SportsFollowPrompt(
            actionFocus = emptyActionFocus,
            onUp = onUpToDateStrip,
            onOpenPicker = onOpenPicker,
            modifier = modifier,
        )
        return
    }

    // The "games ahead" rows: one per followed team, fed by the day's scoreboard plus each
    // followed league's ahead-of-today fetch.
    val followedLeagues = remember(settings.followedTeams) {
        settings.followedTeams.mapNotNull { SportsFollows.leagueOfTeamKey(it) }.distinct()
    }
    LaunchedEffect(followedLeagues) {
        followedLeagues.forEach { SportsRepository.refreshAhead(it) }
    }
    val aheadStates = followedLeagues.map { leagueId ->
        val state by remember(leagueId) { SportsRepository.aheadState(leagueId) }.collectAsState()
        state
    }
    val teamsByLeague by produceState<Map<String, List<Team>>>(initialValue = emptyMap(), followedLeagues) {
        val acc = mutableMapOf<String, List<Team>>()
        followedLeagues.forEach { leagueId ->
            SportsRepository.teams(leagueId) { teams ->
                acc[leagueId] = teams
                value = acc.toMap()
            }
        }
    }
    val notesByTeamKey by produceState<Map<String, String>>(initialValue = emptyMap(), followedLeagues) {
        val acc = mutableMapOf<String, String>()
        followedLeagues.forEach { leagueId ->
            runCatching { SportsRepository.standings(leagueId) }.getOrNull()?.forEach { row ->
                val key = SportsFollows.teamKey(leagueId, row.team.id)
                if (key in settings.followedTeams) standingsNote(row)?.let { acc[key] = it }
            }
            value = acc.toMap()
        }
    }
    val teamSections = if (teamMode) {
        val gamesByLeague = (sections.live + sections.upcoming + sections.results)
            .groupBy { it.leagueId }
            .let { dayMap ->
                aheadStates.fold(dayMap) { acc, state ->
                    val merged = (acc[state.leagueId].orEmpty() + state.games).distinctBy { it.id }
                    acc + (state.leagueId to merged)
                }
            }
        buildTeamAheadSections(settings, teamsByLeague, notesByTeamKey, gamesByLeague, System.currentTimeMillis())
            .filter { it.games.isNotEmpty() }
    } else {
        emptyList()
    }

    val allGames = remember(sections.live, sections.upcoming, sections.results, teamSections) {
        (sections.live + sections.upcoming + sections.results + teamSections.flatMap { it.games })
            .distinctBy { "${it.leagueId}:${it.id}" }
    }

    if (allGames.isEmpty() && !sections.anyLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (mySportsOnly) "None of your teams play on this date" else "No games on this date",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = when {
                        sections.anyError -> "Some leagues could not be reached."
                        mySportsOnly -> "Try another day, or follow more teams."
                        else -> "Try another day."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
        return
    }

    // Resolve matched IPTV channel names asynchronously once per day/settings snapshot for all cards.
    val matchState by produceState(
        initialValue = false to emptyMap<String, String>(),
        allGames,
        settings.picks,
        settings.teamChannels,
        settings.networkChannels,
        settings.leagueChannels,
        settings.blocked,
        settings.preferredPlaylistId,
        settings.channelRegion,
        settings.bestQualityFirst,
        channelSourceStates,
        guideStates,
    ) {
        val matches = withContext(Dispatchers.Default) {
            allGames.associate { game ->
                val best = SportsWatch.choicesFor(game, SportsWatch.optionsFor(game, settings)).firstOrNull()
                game.id to (best?.channel?.name?.takeIf { it.isNotBlank() }.orEmpty())
            }.filterValues { it.isNotEmpty() }
        }
        val scanComplete = channelSourceStates.size == iptvSettings.playlists.size &&
            channelSourceStates.all { it.content != null || it.error != null } &&
            guideStates.size == iptvSettings.playlists.size &&
            guideStates.all { !it.loading }
        value = scanComplete to matches
    }
    val channelsResolved = matchState.first
    val matchedByEvent = matchState.second

    val shownLive = if (settings.onlyGamesOnMyChannels && channelsResolved) {
        filterGamesByChannelAvailability(sections.live, settings, matchedByEvent.keys)
    } else sections.live
    val shownUpcoming = if (settings.onlyGamesOnMyChannels && channelsResolved) {
        filterGamesByChannelAvailability(sections.upcoming, settings, matchedByEvent.keys)
    } else sections.upcoming
    val shownResults = if (settings.onlyGamesOnMyChannels && channelsResolved) {
        filterGamesByChannelAvailability(sections.results, settings, matchedByEvent.keys)
    } else sections.results
    val shownTeamSections = if (day != SportsRepository.today()) {
        emptyList()
    } else if (settings.onlyGamesOnMyChannels && channelsResolved) {
        teamSections.map { section ->
            section.copy(games = filterGamesByChannelAvailability(section.games, settings, matchedByEvent.keys))
        }.filter { it.games.isNotEmpty() }
    } else teamSections
    val heroEvent = shownUpcoming.firstOrNull()
        ?: shownLive.firstOrNull()
        ?: shownTeamSections.firstNotNullOfOrNull { it.games.firstOrNull() }
    LaunchedEffect(heroEvent) { onHeroAvailable(heroEvent) }

    // My Sports: one Live & Upcoming section, then a games-ahead section per followed team.
    // All Events: LIVE NOW, then Upcoming and Results grouped per league.
    val sectionInputs = remember(
        shownLive, shownUpcoming, shownResults, leagues,
        settings.followedLeagues, settings.followedTeams, shownTeamSections,
    ) {
        buildGridSections(
            live = shownLive,
            upcoming = shownUpcoming,
            results = shownResults,
            dayCategory = when {
                day == SportsRepository.today() -> SportsDayCategory.TODAY
                day < SportsRepository.today() -> SportsDayCategory.PAST
                else -> SportsDayCategory.FUTURE
            },
            leagues = leagues,
            settings = settings,
            teamMode = teamMode,
            teamSections = shownTeamSections,
            liveCaption = if (fellBackToAll) {
                "Nothing followed is playing — everything today"
            } else {
                null
            },
        )
    }

    val gridState = rememberLazyGridState()
    val gridFocus = rememberSportsGridFocus(gridState)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        if (sections.anyError) {
            SportsStaleNote(sections.oldestFetchMs)
        }

        if (teamMode) heroEvent?.let { hero ->
            SportsHero(
                event = hero,
                league = byId[hero.leagueId],
                hideScores = hideScores,
                followedTeamName = SportsFollows.followedSideName(settings, hero),
                focusRequester = heroFocus,
                onUp = onUpToDateStrip,
                onDown = onEnterGrid,
                onClick = { onOpenGame(hero.id) },
            )
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val hGap = tokens.spacing.listGap
            // My Sports keeps the wide list cards; All Events uses the reference page's compact
            // score rows, so more of the slate fits across.
            val minCardWidth = if (teamMode) 310.dp else 470.dp
            val maxColumns = if (teamMode) 5 else 4
            val columns = ((maxWidth + hGap) / (minCardWidth + hGap)).toInt().coerceIn(1, maxColumns)
            val gridLayout = remember(sectionInputs, columns) {
                SportsSectionGridLayout.build(sectionInputs, columns)
            }

            LaunchedEffect(enterGridRequests) {
                if (enterGridRequests > 0) {
                    gridFocus.focusGrid(gridLayout)
                }
            }

            // Reset cursor row when switching day or sport filter.
            LaunchedEffect(day, leagues) {
                gridFocus.recordFocus(0, 0)
                runCatching { gridState.scrollToItem(0) }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        gridFocus.handleKey(
                            event = event,
                            layout = gridLayout,
                            onUpFromTopRow = onUpFromGrid,
                        )
                    },
                // Padding inside the viewport so zoomed cards at row 0 or outer columns never clip.
                contentPadding = PaddingValues(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(hGap),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                gridLayout.entries.forEach { entry ->
                    when (entry) {
                        is SportsSectionGridLayout.Entry.Header -> {
                            item(
                                key = entry.key,
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                SportsGridSectionHeader(
                                    title = entry.title,
                                    count = entry.count,
                                    live = entry.live,
                                    accentColor = entry.accentColor,
                                    caption = entry.caption,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                )
                            }
                        }
                        is SportsSectionGridLayout.Entry.Card -> {
                            val game = entry.item
                            item(key = entry.key) {
                                if (teamMode) {
                                    SportsGameCard(
                                        event = game,
                                        league = byId[game.leagueId],
                                        hideScores = hideScores,
                                        followed = SportsFollows.isFollowedTeamGame(settings, game),
                                        followedSideIds = SportsFollows.followedSideIds(settings, game),
                                        matchedChannelName = matchedByEvent[game.id],
                                        focusRequester = gridFocus.requester(entry.entryIndex),
                                        onFocused = { gridFocus.recordFocus(entry.row, entry.col) },
                                        onQuickWatch = { SportsWatch.playBest(game, settings) },
                                        onClick = { onOpenGame(game.id) },
                                    )
                                } else {
                                    SportsScoreRow(
                                        event = game,
                                        league = byId[game.leagueId],
                                        hideScores = hideScores,
                                        followedSideIds = SportsFollows.followedSideIds(settings, game),
                                        matchedChannelName = matchedByEvent[game.id],
                                        focusRequester = gridFocus.requester(entry.entryIndex),
                                        onFocused = { gridFocus.recordFocus(entry.row, entry.col) },
                                        onQuickWatch = { SportsWatch.playBest(game, settings) },
                                        onClick = { onOpenGame(game.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "Enter or click: game info · ⏯ watch it · ◄ ► through a row",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun buildGridSections(
    live: List<SportsEvent>,
    upcoming: List<SportsEvent>,
    results: List<SportsEvent>,
    leagues: List<League>,
    settings: SportsSettings,
    dayCategory: SportsDayCategory = SportsDayCategory.TODAY,
    teamMode: Boolean = false,
    teamSections: List<SportsTeamAhead> = emptyList(),
    liveCaption: String? = null,
): List<SportsSectionGridLayout.Companion.SectionInput<SportsEvent>> {
    // My Sports retains its team-ahead rows, while the selected day itself uses the same
    // Live / Upcoming / Past categories as All Events.
    if (teamMode) {
        return buildList {
            if (live.isNotEmpty()) add(
                SportsSectionGridLayout.Companion.SectionInput(
                    key = "mysports-live",
                    title = "Live",
                    items = live,
                    live = true,
                    caption = liveCaption ?: "Everything you follow, live now",
                    itemKey = { "${it.leagueId}:${it.id}" },
                ),
            )
            if (upcoming.isNotEmpty()) add(
                SportsSectionGridLayout.Companion.SectionInput(
                    key = "mysports-upcoming",
                    title = if (dayCategory == SportsDayCategory.FUTURE) "Upcoming Games" else "Upcoming",
                    items = upcoming,
                    live = false,
                    itemKey = { "${it.leagueId}:${it.id}" },
                ),
            )
            if (results.isNotEmpty()) add(
                SportsSectionGridLayout.Companion.SectionInput(
                    key = "mysports-past",
                    title = "Past Games",
                    items = results,
                    live = false,
                    itemKey = { "${it.leagueId}:${it.id}" },
                ),
            )
            (if (dayCategory == SportsDayCategory.TODAY) teamSections else emptyList()).forEach { teamSection ->
                val league = leagues.firstOrNull { it.id == teamSection.leagueId }
                val name = teamSection.team?.shortName?.takeIf { it.isNotBlank() }
                    ?: teamSection.team?.name
                    ?: teamSection.games.firstNotNullOfOrNull { SportsFollows.followedSideName(settings, it) }
                    ?: "Followed team"
                val title = teamSection.note?.takeIf { it.isNotBlank() }?.let { "$name · $it" } ?: name
                add(
                    SportsSectionGridLayout.Companion.SectionInput(
                        key = "team:${teamSection.teamKey}",
                        title = title,
                        items = teamSection.games,
                        live = false,
                        accentColor = league?.color,
                        caption = gamesAheadCaption(teamSection.games.size),
                        itemKey = { "${it.leagueId}:${it.id}" },
                    ),
                )
            }
        }
    }

    val leagueOrder = leagues.mapIndexed { idx, l -> l.id to idx }.toMap()
    val byId = leagues.associateBy { it.id }

    fun groupByLeagueOrdered(
        games: List<SportsEvent>,
        stagePrefix: String,
        stageSuffix: String,
    ): List<SportsSectionGridLayout.Companion.SectionInput<SportsEvent>> {
        if (games.isEmpty()) return emptyList()
        val distinctLeagues = games.map { it.leagueId }.distinct()
        if (distinctLeagues.size == 1) {
            val singleLeague = byId[distinctLeagues.single()]
            val title = if (singleLeague != null && leagues.size > 1) {
                "${singleLeague.name} · $stageSuffix"
            } else {
                stageSuffix.uppercase()
            }
            return listOf(
                SportsSectionGridLayout.Companion.SectionInput(
                    key = "$stagePrefix:all",
                    title = title,
                    items = games,
                    live = false,
                    accentColor = singleLeague?.color,
                    itemKey = { "${it.leagueId}:${it.id}" },
                ),
            )
        }
        val grouped = games.groupBy { it.leagueId }
        val sortedLeagueIds = grouped.keys.sortedWith(
            compareByDescending<String> { id ->
                id in settings.followedLeagues ||
                    grouped[id].orEmpty().any { SportsFollows.isFollowedTeamGame(settings, it) }
            }.thenBy { leagueOrder[it] ?: Int.MAX_VALUE },
        )
        return sortedLeagueIds.map { leagueId ->
            val league = byId[leagueId]
            val items = grouped[leagueId].orEmpty()
            SportsSectionGridLayout.Companion.SectionInput(
                key = "$stagePrefix:$leagueId",
                title = "${league?.name ?: leagueId} · $stageSuffix",
                items = items,
                live = false,
                accentColor = league?.color,
                itemKey = { "${it.leagueId}:${it.id}" },
            )
        }
    }

    return buildList {
        if (live.isNotEmpty()) {
            add(
                SportsSectionGridLayout.Companion.SectionInput(
                    key = "live",
                    title = "LIVE NOW",
                    items = live,
                    live = true,
                    itemKey = { "${it.leagueId}:${it.id}" },
                ),
            )
        }
        val upcomingTitle = if (dayCategory == SportsDayCategory.FUTURE) "Upcoming Games" else "Upcoming"
        addAll(groupByLeagueOrdered(upcoming, stagePrefix = "upcoming", stageSuffix = upcomingTitle))
        addAll(groupByLeagueOrdered(results, stagePrefix = "results", stageSuffix = "Past Games"))
    }
}

/** "1 game ahead" / "8 games ahead" — the per-team row's caption. */
internal fun gamesAheadCaption(count: Int): String =
    if (count == 1) "1 game ahead" else "$count games ahead"

@Composable
private fun SportsGridSectionHeader(
    title: String,
    count: Int,
    live: Boolean,
    accentColor: Long?,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            live -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.danger),
            )
            accentColor != null -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(accentColor)),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (live) tokens.colors.textPrimary else tokens.colors.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = caption ?: if (count == 1) "1 game" else "$count games",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
        )
    }
}

@Composable
private fun SportsStaleNote(fetchedAtMs: Long) {
    val tokens = MaterialTheme.nuvio
    val age = remember(fetchedAtMs) { relativeAge(fetchedAtMs) }
    Text(
        text = if (age == null) "Could not refresh" else "Could not refresh · showing $age",
        style = MaterialTheme.typography.labelSmall,
        color = tokens.colors.warning,
    )
}

/** "42 minutes ago", or null when nothing has ever been fetched. */
internal fun relativeAge(fetchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
    if (fetchedAtMs <= 0L) return null
    val minutes = (nowMs - fetchedAtMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        else -> {
            val hours = minutes / 60L
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
    }
}

@Composable
private fun SportsFollowPrompt(
    actionFocus: FocusRequester,
    onUp: () -> Boolean,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                arrowFocusDirection(event) == FocusDirection.Up && onUp()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            Text(
                text = "Nothing followed yet",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = "Follow teams and leagues to have their games lead this page.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            SportsChip(
                label = "Follow teams and leagues",
                selected = true,
                focusRequester = actionFocus,
                onClick = onOpenPicker,
            )
        }
    }
}

private data class DaySections(
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>,
    val results: List<SportsEvent>,
    val anyLoading: Boolean,
    val anyError: Boolean,
    val oldestFetchMs: Long,
)

@Composable
private fun collectSections(
    leagues: List<League>,
    day: String,
    settings: SportsSettings,
    mySportsOnly: Boolean,
): DaySections {
    val states = leagues.map { league ->
        val state by remember(league.id, day) { SportsRepository.dayState(league.id, day) }.collectAsState()
        state
    }
    val allGames = states.flatMap { it.games }
        // Resolve each game's state as of now, so a stale "Live" from a snapshot taken while the
        // game was on reads as finished here and everywhere downstream (cards, badges, detail).
        .map { it.withResolvedState() }
        .filter { !mySportsOnly || SportsFollows.isInMySports(settings, it) }
    val split = splitDayForDate(allGames, day, SportsRepository.today())
    fun followedFirst(list: List<SportsEvent>): List<SportsEvent> =
        if (SportsFollows.isEmpty(settings)) list
        else list.sortedByDescending {
            when {
                SportsFollows.isFollowedTeamGame(settings, it) -> 2
                SportsFollows.isInMySports(settings, it) -> 1
                else -> 0
            }
        }
    val fetched = states.map { it.fetchedAtMs }.filter { it > 0L }
    return DaySections(
        live = followedFirst(split.live),
        upcoming = followedFirst(split.upcoming),
        results = followedFirst(split.results),
        anyLoading = states.any { it.loading },
        anyError = states.any { it.error != null },
        oldestFetchMs = fetched.minOrNull() ?: 0L,
    )
}

internal enum class SportsDayCategory { TODAY, PAST, FUTURE }

internal data class SplitDay(
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>,
    val results: List<SportsEvent>,
) {
    val isEmpty: Boolean get() = live.isEmpty() && upcoming.isEmpty() && results.isEmpty()
}

internal fun splitDay(games: List<SportsEvent>): SplitDay = SplitDay(
    live = games.filter { it.state == GameState.LIVE }.sortedBy { it.startMs },
    upcoming = games.filter { it.state == GameState.PRE }.sortedBy { it.startMs },
    results = games.filter { it.state == GameState.POST }.sortedByDescending { it.startMs },
)

/** Historical and future date tabs are categories of the selected date, not stale feed states. */
internal fun splitDayForDate(games: List<SportsEvent>, day: String, today: String): SplitDay = when {
    day < today -> SplitDay(emptyList(), emptyList(), games.sortedByDescending { it.startMs })
    day > today -> SplitDay(emptyList(), games.sortedBy { it.startMs }, emptyList())
    else -> splitDay(games)
}

/** How long a game may legitimately stay live after kick-off; multi-day sports are exempt. */
private const val MAX_LIVE_WINDOW_MS = 8 * 60 * 60_000L

/** The provider may flip a game to "in" a few minutes before its scheduled start. */
private const val LIVE_EARLY_SLACK_MS = 10 * 60_000L

/**
 * The state a game can claim as of [nowMs], sanity-checked against the clock.
 *
 * The feed's own state is trusted unless it is impossible: a game marked live before its kick-off
 * (beyond the early slack) has not begun, and a game marked live long after kick-off cannot be —
 * that is the stale snapshot a cache or a stuck feed left behind, and it must read as finished
 * rather than draw a live match interface for yesterday. Cricket is exempt from the long-window
 * clamp: a Test match runs for days.
 */
internal fun resolvedGameState(event: SportsEvent, nowMs: Long): GameState {
    if (event.state != GameState.LIVE) return event.state
    return when {
        event.startMs > nowMs + LIVE_EARLY_SLACK_MS -> GameState.PRE
        SportsCatalog.league(event.leagueId)?.sport == Sport.CRICKET -> GameState.LIVE
        nowMs - event.startMs > MAX_LIVE_WINDOW_MS -> GameState.POST
        else -> GameState.LIVE
    }
}

/**
 * [resolvedGameState] applied: the same game with a state that is true right now, so sections,
 * cards and the detail page all read one consistent story. Returns [SportsEvent] unchanged when
 * the feed state already holds, so unchanged games keep their identity and keyed grid items are
 * not rebuilt.
 */
internal fun SportsEvent.withResolvedState(nowMs: Long = System.currentTimeMillis()): SportsEvent {
    val resolved = resolvedGameState(this, nowMs)
    return if (resolved == state) this else copy(state = resolved)
}

@Composable
internal fun rememberSportsTabVisible(): Boolean {
    if (!com.nuvio.app.core.build.AppFeaturePolicy.sportsEnabled) return false
    remember { com.nuvio.app.features.iptv.IptvRepository.ensureLoaded() }
    val iptv by com.nuvio.app.features.iptv.IptvRepository.settings.collectAsState()
    val sports by SportsSettingsRepository.settings.collectAsState()
    return sports.enabled && iptv.playlists.isNotEmpty()
}
