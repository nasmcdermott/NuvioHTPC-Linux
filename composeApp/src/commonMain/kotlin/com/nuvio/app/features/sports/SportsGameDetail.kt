package com.nuvio.app.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import com.nuvio.app.core.ui.KeyRouteLayer
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.iptv.IptvRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScorer
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.isSelectableForPlayback
import com.nuvio.app.features.tonight.TonightSettingsRepository
import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val detailLog = co.touchlab.kermit.Logger.withTag("Sports")

/**
 * The quarter whose toggle Left/Right moves to from [index], or null at either end (the key keeps
 * whatever meaning it had there). The quarters are stacked, so they are siblings of one another
 * sideways; Up and Down stay with the page's scrolling.
 */
internal fun periodSideStepTarget(key: Key?, index: Int, lastIndex: Int): Int? = when (key) {
    Key.DirectionLeft -> index - 1
    Key.DirectionRight -> index + 1
    else -> null
}?.takeIf { it in 0..lastIndex }

/**
 * Reading rank of a play-by-play period: descending order puts the newest first. A numbered period
 * reads its number ("1st Quarter" → 1, "4th" → 4); overtime and any other untimed period rank above
 * regulation, and a bucket the feed could not name ("Other") sorts last.
 */
internal fun sportsPeriodOrder(period: String): Int {
    val text = period.trim()
    if (text.equals("Other", ignoreCase = true)) return -1
    if (text.isEmpty()) return -1
    return Regex("""^\d+""").find(text)?.value?.toIntOrNull() ?: 50
}

/**
 * Puts play-by-play in newest-first order before it is split into periods. ESPN stamps every play
 * with a wall-clock instant, which is the one key that does not depend on whether the feed listed
 * the plays oldest-first or newest-first; sorting on it fixes a live summary whose drives arrive in
 * reverse. Feeds without wall clocks fall back to their own direction, read from the period on the
 * first and last play.
 */
internal fun playByPlayNewestFirst(plays: List<PlayByPlayEntry>): List<PlayByPlayEntry> {
    if (plays.isEmpty()) return plays
    if (plays.all { it.wallclockMs != null }) {
        // Later feed entries win a wall-clock tie, so a same-second "END GAME" follows the play
        // it ends rather than being left above it by a stable sort.
        return plays.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<PlayByPlayEntry>> { it.value.wallclockMs }
                    .thenByDescending { it.index },
            )
            .map { it.value }
    }
    val chronological = sportsPeriodOrder(plays.first().period.orEmpty()) <=
        sportsPeriodOrder(plays.last().period.orEmpty())
    return if (chronological) plays.asReversed() else plays
}

/** Play-by-play split into periods, newest first inside every period too. */
internal fun playByPlayPeriodsNewestFirst(
    plays: List<PlayByPlayEntry>,
    limit: Int? = null,
): List<Pair<String, List<PlayByPlayEntry>>> {
    val newestFirst = playByPlayNewestFirst(plays).let { if (limit != null) it.take(limit) else it }
    return newestFirst
        .groupBy { it.period?.takeIf(String::isNotBlank) ?: "Other" }
        .toList()
}

/**
 * A game's detail page: live scoreboard, period linescores, quick IPTV playback & channel matching
 * controls, and live-updating scoring summary + team/player box scores.
 */
@Composable
internal fun SportsGameDetail(
    event: SportsEvent,
    league: League?,
    hideScores: Boolean,
    onBack: () -> Unit,
    entryFocus: FocusRequester = remember { FocusRequester() },
    onUpToHeader: () -> Boolean = { false },
    onOpenTable: (() -> Unit)? = null,
    onFindSportsStreams: (String?) -> Unit = {},
    onPlaySportsStream: (StreamItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val settings by SportsSettingsRepository.settings.collectAsState()
    val clockSettings by TonightSettingsRepository.state.collectAsState()
    remember { TonightSettingsRepository.ensureLoaded() }
    val streamState by StreamsRepository.uiState.collectAsState()
    val debridSettings by DebridSettingsRepository.uiState.collectAsStateWithLifecycle()
    val scoreProfile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    remember { DebridSettingsRepository.ensureLoaded() }
    remember { StreamScoreRepository.ensureLoaded() }
    remember { IptvRepository.ensureLoaded() }
    val iptvSettings by IptvRepository.settings.collectAsState()

    val allOptions = remember(
        event,
        settings.picks,
        settings.teamChannels,
        settings.networkChannels,
        settings.leagueChannels,
        settings.blocked,
        settings.preferredPlaylistId,
        settings.channelRegion,
        settings.bestQualityFirst,
        iptvSettings.playlists,
    ) {
        SportsWatch.choicesFor(event, SportsWatch.optionsFor(event, settings))
    }

    val sportsStreamId = league?.let { sportsEventStreamId(it, event) }
    val sportsRequestToken = sportsStreamId?.let { videoId ->
        StreamsRepository.requestToken("sports", videoId, manualSelection = true)
    }
    val addonGroups = if (sportsRequestToken != null && streamState.requestToken == sportsRequestToken) {
        streamState.groups.filter { it.addonId != "local-library" }
    } else {
        emptyList()
    }
    LaunchedEffect(event.id, sportsStreamId) {
        if (sportsStreamId != null) {
            StreamsRepository.load(
                type = "sports",
                videoId = sportsStreamId,
                parentMetaId = sportsStreamId,
                title = event.displayTitle,
                manualSelection = true,
            )
        }
    }

    val regionChoice = settings.channelRegion
    var showOtherRegions by remember(event.id, regionChoice) { mutableStateOf(false) }
    val regionFiltered = remember(allOptions, regionChoice) {
        if (regionChoice.isBlank()) {
            allOptions
        } else {
            allOptions.filter { it.isManualPick || SportsChannelRegions.includes(it.regionCode, regionChoice) }
        }
    }
    val noneInChosenRegion = regionChoice.isNotBlank() && regionFiltered.isEmpty() && allOptions.isNotEmpty()
    val visibleOptions = when {
        regionChoice.isBlank() || showOtherRegions || noneInChosenRegion -> allOptions
        else -> regionFiltered
    }
    val hiddenRegionCount = (allOptions.size - regionFiltered.size).coerceAtLeast(0)
    val distinctRegions = remember(allOptions) {
        allOptions.mapNotNull { it.regionCode }.distinct()
    }
    val showRegionFilter = distinctRegions.size > 1 || regionChoice.isNotBlank()
    val showOtherRegionsToggle = visibleOptions.isNotEmpty() &&
        regionChoice.isNotBlank() &&
        !noneInChosenRegion &&
        hiddenRegionCount > 0

    var channelPickerOpen by remember { mutableStateOf(false) }
    var regionDialogOpen by remember { mutableStateOf(false) }
    var expandedPlayPeriods by remember(event.id) { mutableStateOf(emptySet<String>()) }

    // Fetch box score / scoring summary on open, and keep refreshing while the game is live.
    var summary by remember(event.id) { mutableStateOf<GameSummary?>(null) }
    var summaryFailed by remember(event.id) { mutableStateOf(false) }
    val latestEvent by rememberUpdatedState(event)
    LaunchedEffect(event.id, event.state) {
        while (true) {
            val loaded = SportsRepository.summary(latestEvent.leagueId, latestEvent.id)
            if (loaded != null) {
                summary = loaded
                summaryFailed = false
            } else if (summary == null) {
                summaryFailed = true
            }
            if (!latestEvent.isLive) break
            delay(SportsRepository.liveRefreshMs)
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pageFocus = LocalFocusManager.current

    val backFocus = remember { FocusRequester() }
    val playByPlayFocus = remember { FocusRequester() }
    val actionsBandFocus = remember { FocusRequester() }
    val primaryActionFocus = remember { FocusRequester() }
    val channelsHeaderBandFocus = remember { FocusRequester() }
    val fixChannelFocus = remember { FocusRequester() }
    val emptyPickFocus = remember { FocusRequester() }
    val otherRegionsFocus = remember { FocusRequester() }
    val channelBandRequesters = remember { HashMap<Int, FocusRequester>() }
    val channelMainRequesters = remember { HashMap<Int, FocusRequester>() }
    fun channelBand(idx: Int): FocusRequester = channelBandRequesters.getOrPut(idx) { FocusRequester() }
    fun channelMain(idx: Int): FocusRequester = channelMainRequesters.getOrPut(idx) { FocusRequester() }
    val highlightBandRequesters = remember(event.id) { HashMap<Int, FocusRequester>() }
    val highlightMainRequesters = remember(event.id) { HashMap<Int, FocusRequester>() }
    fun highlightBand(idx: Int): FocusRequester = highlightBandRequesters.getOrPut(idx) { FocusRequester() }
    fun highlightMain(idx: Int): FocusRequester = highlightMainRequesters.getOrPut(idx) { FocusRequester() }

    val bestOption = if (event.state == GameState.POST) null else visibleOptions.firstOrNull() ?: allOptions.firstOrNull()
    val replayOption = visibleOptions.firstOrNull { it.canCatchup } ?: allOptions.firstOrNull { it.canCatchup }

    // fork:game-highlights - a finished game's official YouTube highlights sit at the top of the
    // replay group, alongside any IPTV catch-up channels the user's playlists offer. Null while
    // the search is in flight, empty when nothing official was found (the page stays as it was).
    val wantHighlights = event.state == GameState.POST
    var youtubeHighlights by remember(event.id) { mutableStateOf<List<YouTubeSearchHit>?>(null) }
    LaunchedEffect(event.id, wantHighlights) {
        if (!wantHighlights) {
            youtubeHighlights = null
        } else {
            youtubeHighlights = SportsYouTubeHighlights.highlights(event, league)
        }
    }
    val isFieldEvent = event.away.team.id.endsWith(":event")
    val hasPrimaryWatchAction = (bestOption != null && event.state != GameState.POST) ||
        (replayOption != null && event.state == GameState.POST)
    val hasAddonWatchAction = event.state != GameState.POST

    LaunchedEffect(event.id) {
        val initial = if (hasPrimaryWatchAction || hasAddonWatchAction) primaryActionFocus else backFocus
        repeat(10) {
            if (initial.requestFocusLogged()) return@LaunchedEffect
            delay(48)
        }
    }

    val playPeriodGroups = playByPlayPeriodsNewestFirst(
        plays = summary?.playByPlay.orEmpty(),
        limit = MAX_LIVE_PLAY_BY_PLAY.takeIf { event.isLive },
    )
    val playPeriodNames = playPeriodGroups.map { it.first }
    val periodFocusRequesters = remember(event.id, playPeriodNames) {
        List(playPeriodNames.size) { index -> if (index == 0) playByPlayFocus else FocusRequester() }
    }
    // Each quarter's header row needs to be revealed inside the one tall plays/stats item, which a
    // list index alone cannot do.
    val periodBringIntoViews = remember(event.id, playPeriodNames) {
        List(playPeriodNames.size) { BringIntoViewRequester() }
    }
    val hasPlayByPlayControl = !hideScores && playPeriodGroups.isNotEmpty()
    // Items 0..3 are header, scoreboard, actions and channels-header; channel rows start at index 4.
    // fork:game-highlights - the highlight rows are lazy items of their own between the channels
    // header and the channel rows, so every index below them moves by their count.
    val highlightRows = if (event.state == GameState.POST) youtubeHighlights.orEmpty() else emptyList()
    val firstChannelLazyIndex = 4 + highlightRows.size
    // The play-by-play toggle sits at the top of the plays/stats card, after the channel rows (or the
    // empty-channel card) and the optional "other regions" toggle.
    val statsLazyIndex = firstChannelLazyIndex +
        if (visibleOptions.isEmpty()) 1 else visibleOptions.size + if (showOtherRegionsToggle) 1 else 0
    // Only the latest request may chase focus. A held or repeatedly pressed key used to start one
    // loop per press, and each loop animated the list to its target, so the page was yanked around
    // under a scroll that was already in progress.
    val focusChase = remember { arrayOfNulls<Job>(1) }
    fun cancelFocusChase() {
        focusChase[0]?.cancel()
        focusChase[0] = null
    }
    /**
     * Brings [lazyIndex] into the list, then asks [target] for the keys until it is composed. The
     * jump is not animated: focus can only be requested on a composed control, and an animation
     * cancelled by the next key left the item off screen while the request kept being refused. When
     * [reveal] is given it scrolls the control's own row into view inside a taller item.
     *
     * A lazy item can also be missing from the list the index was counted for (its section changed
     * while the key was in flight, or a jammed frame used up the retries). The retries wait for
     * frames, so a busy frame cannot use them up, and then the key is handed to the focus system
     * itself ([fallbackDirection] from the control that has the keys): the page still moves, and a
     * navigation key never dies on the way. Null skips that (a sideways step between quarters has
     * already been brought into view).
     */
    fun chaseFocus(
        lazyIndex: Int,
        target: FocusRequester,
        reveal: BringIntoViewRequester? = null,
        fallbackDirection: FocusDirection? = FocusDirection.Down,
    ): Boolean {
        cancelFocusChase()
        // A control inside a taller item is revealed first even when it already holds the keys, so a
        // sideways step between quarters lands on something the person can actually see.
        if (reveal == null && runCatching { target.requestFocusLogged() }.getOrDefault(false)) return true
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == lazyIndex }
        focusChase[0] = scope.launch {
            if (!visible) runCatching { listState.requestScrollToItem(lazyIndex.coerceAtLeast(0)) }
            repeat(10) {
                withFrameNanos { }
                if (reveal != null) runCatching { reveal.bringIntoView() }
                if (runCatching { target.requestFocusLogged() }.getOrDefault(false)) return@launch
            }
            if (fallbackDirection != null && pageFocus.moveFocus(fallbackDirection)) return@launch
            // Nothing on the page would take the keys at all: still move it a step, so a navigation
            // key is never a dead press.
            if (reveal != null || fallbackDirection == null) return@launch
            val info = listState.layoutInfo
            detailLog.w {
                "focus chase missed item $lazyIndex of ${info.totalItemsCount} " +
                    "(visible ${info.visibleItemsInfo.joinToString { it.index.toString() }})"
            }
            val back = fallbackDirection == FocusDirection.Up
            val canScroll = if (back) listState.canScrollBackward else listState.canScrollForward
            if (canScroll) {
                val step = (listState.layoutInfo.viewportSize.height * 0.72f).coerceAtLeast(1f)
                runCatching { listState.animateScrollBy(if (back) -step else step) }
            }
        }
        return true
    }

    fun scrollAndFocus(
        lazyIndex: Int,
        target: FocusRequester,
        direction: FocusDirection = FocusDirection.Down,
    ): Boolean = chaseFocus(lazyIndex, target, fallbackDirection = direction)

    fun focusPeriod(index: Int, fallbackDirection: FocusDirection? = null): Boolean {
        val target = periodFocusRequesters.getOrNull(index) ?: return false
        return chaseFocus(
            statsLazyIndex,
            target,
            periodBringIntoViews.getOrNull(index),
            fallbackDirection,
        )
    }

    fun scrollDetail(direction: Int): Boolean {
        val canScroll = if (direction > 0) listState.canScrollForward else listState.canScrollBackward
        if (!canScroll) return false
        scope.launch {
            val step = (listState.layoutInfo.viewportSize.height * 0.72f).coerceAtLeast(1f)
            runCatching { listState.animateScrollBy(step * direction) }
        }
        return true
    }

    fun stepStatsDown(): Boolean = scrollDetail(1)

    fun stepStatsUpIfScrolled(minAnchorIndex: Int): Boolean {
        if (listState.firstVisibleItemIndex <= minAnchorIndex) return false
        val prev = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
        scope.launch { runCatching { listState.animateScrollToItem(prev) } }
        return true
    }

    fun focusPlayByPlay(): Boolean = if (hasPlayByPlayControl) {
        focusPeriod(0, FocusDirection.Down)
    } else {
        stepStatsDown()
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (keyEvent.navigationKey()) {
                    Key.DirectionDown -> scrollDetail(1)
                    Key.DirectionUp -> scrollDetail(-1)
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.sectionGap),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sportsHorizontalBand(
                        bandRequester = entryFocus,
                        fallbackRequester = backFocus,
                        onUp = {
                            scope.launch { runCatching { listState.scrollToItem(0) } }
                            onUpToHeader()
                        },
                        onDown = { scrollAndFocus(2, actionsBandFocus) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                SportsBackButton(
                    onClick = onBack,
                    focusRequester = backFocus,
                )
                Text(
                    text = listOfNotNull(league?.name, summary?.venue).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenTable != null) {
                    SportsSmallPill(
                        label = "League table",
                        selected = false,
                        onClick = onOpenTable,
                    )
                }
            }
        }

        item(key = "scoreboard") {
            SportsScoreboard(
                event = event,
                sport = league?.sport ?: Sport.OTHER,
                hideScores = hideScores,
            )
        }

        item(key = "actions") {
            SportsDetailActionRow(
                event = event,
                bestOption = bestOption,
                replayOption = replayOption,
                allOptions = allOptions,
                settings = settings,
                isFieldEvent = isFieldEvent,
                onFindSportsStreams = { onFindSportsStreams(null) },
                addonGroups = addonGroups,
                isAddonLoading = streamState.requestToken == sportsRequestToken &&
                    (streamState.isAnyLoading || addonGroups.any { it.isLoading }),
                debridEnabled = debridSettings.canResolvePlayableLinks,
                scoreProfile = scoreProfile,
                onPlaySportsStream = onPlaySportsStream,
                bandRequester = actionsBandFocus,
                primaryFocus = primaryActionFocus,
                onUp = { scrollAndFocus(0, entryFocus, FocusDirection.Up) },
                onDown = { scrollAndFocus(3, channelsHeaderBandFocus) },
                onOpenChannelPicker = { channelPickerOpen = true },
            )
        }

        item(key = "channels-header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sportsHorizontalBand(
                            bandRequester = channelsHeaderBandFocus,
                            fallbackRequester = fixChannelFocus,
                            onUp = { scrollAndFocus(2, actionsBandFocus, FocusDirection.Up) },
                            onDown = {
                                when {
                                    // fork:game-highlights - highlights sit at the top of the replay
                                    // group, so Down from the header reaches them first.
                                    highlightRows.isNotEmpty() -> scrollAndFocus(4, highlightBand(0))
                                    visibleOptions.isNotEmpty() ->
                                        scrollAndFocus(firstChannelLazyIndex, channelBand(0))
                                    iptvSettings.playlists.isNotEmpty() ->
                                        scrollAndFocus(firstChannelLazyIndex, emptyPickFocus)
                                    hasPlayByPlayControl -> focusPlayByPlay()
                                    else -> stepStatsDown()
                                }
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                ) {
                    Text(
                        text = when {
                            event.state == GameState.POST -> when {
                                allOptions.isEmpty() && highlightRows.isNotEmpty() -> "Official YouTube highlights"
                                allOptions.isEmpty() -> "Replay unavailable"
                                else -> "Replay options (${visibleOptions.size + highlightRows.size})"
                            }
                            allOptions.isEmpty() -> "Watch on your channels"
                            allOptions.size == 1 -> "On your channels"
                            else -> "Watch on your channels (${visibleOptions.size})"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (showRegionFilter) {
                        SportsSmallPill(
                            label = "🌐 ${SportsChannelRegions.shortName(regionChoice)} ▾",
                            selected = regionChoice.isNotBlank(),
                            onClick = { regionDialogOpen = true },
                        )
                    }
                    if (event.state != GameState.POST) {
                        SportsSmallPill(
                            label = if (allOptions.isEmpty()) "Pick a channel…" else "Fix channel…",
                            selected = false,
                            focusRequester = fixChannelFocus,
                            onClick = { channelPickerOpen = true },
                        )
                    }
                }
                if (noneInChosenRegion) {
                    Text(
                        text = "No ${SportsChannelRegions.shortName(regionChoice)} channels matched this game — showing all matched channels.",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                }
                SportsUnblockRow(
                    event = event,
                    blocked = settings.blocked,
                    onUnblock = { channelKey -> SportsSettingsRepository.unblockChannel(event.id, channelKey) },
                )
            }
        }

        // fork:game-highlights - the official YouTube highlight uploads at the top of the replay
        // group, above any IPTV catch-up channels. Each row plays like a channel row: Enter
        // resolves the video on the device and opens the player.
        highlightRows.forEachIndexed { index, hit ->
            item(key = "highlight:$index") {
                SportsYouTubeHighlightRow(
                    hit = hit,
                    bandRequester = highlightBand(index),
                    mainRequester = highlightMain(index),
                    onUp = {
                        if (index == 0) {
                            if (stepStatsUpIfScrolled(4)) true
                            else scrollAndFocus(3, channelsHeaderBandFocus, FocusDirection.Up)
                        } else {
                            runCatching { highlightBand(index - 1).requestFocusLogged() }.getOrDefault(false)
                        }
                    },
                    onDown = {
                        when {
                            index < highlightRows.lastIndex ->
                                runCatching { highlightBand(index + 1).requestFocusLogged() }.getOrDefault(false)
                            visibleOptions.isNotEmpty() ->
                                scrollAndFocus(firstChannelLazyIndex, channelBand(0))
                            iptvSettings.playlists.isNotEmpty() ->
                                scrollAndFocus(firstChannelLazyIndex, emptyPickFocus)
                            else -> stepStatsDown()
                        }
                    },
                    onClick = {
                        onPlaySportsStream(SportsYouTubeHighlights.streamItem(league, event, hit))
                    },
                )
            }
        }

        if (visibleOptions.isEmpty()) {
            item(key = "channels-empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            // fork:game-highlights - the highlight rows live in their own items
                            // above, so Up from the card walks back into the last of them.
                            if (iptvSettings.playlists.isNotEmpty()) {
                                Modifier.sportsHorizontalBand(
                                    bandRequester = emptyPickFocus,
                                    onUp = {
                                        when {
                                            stepStatsUpIfScrolled(firstChannelLazyIndex) -> true
                                            highlightRows.isNotEmpty() -> scrollAndFocus(
                                                firstChannelLazyIndex - 1,
                                                highlightBand(highlightRows.lastIndex),
                                                FocusDirection.Up,
                                            )
                                            else -> scrollAndFocus(3, channelsHeaderBandFocus, FocusDirection.Up)
                                        }
                                    },
                                    onDown = { stepStatsDown() },
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(tokens.shapes.card)
                        .background(tokens.colors.surfaceCard)
                        .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.card)
                        .padding(tokens.spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                ) {
                    Text(
                        text = if (event.state == GameState.POST) {
                            "No replay is available from your channels."
                        } else if (iptvSettings.playlists.isEmpty()) {
                            "No IPTV playlists configured yet."
                        } else {
                            "Not automatically matched in your playlists' guides yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = tokens.colors.textPrimary,
                    )
                    Text(
                        text = if (event.state == GameState.POST) {
                            "Completed games only offer catch-up replay when your provider supports it; live channel choices are hidden."
                        } else if (iptvSettings.playlists.isEmpty()) {
                            "Add an M3U, Xtream or Stalker playlist in Live TV settings to stream live games directly from here."
                        } else {
                            "Know which channel carries ${event.displayTitle}? Pick it manually once — you can save it for this game, the team, or the broadcaster."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                    )
                    if (event.state != GameState.POST && iptvSettings.playlists.isNotEmpty()) {
                        Row {
                            SportsSmallPill(
                                label = "Search & pick a channel…",
                                selected = true,
                                onClick = { channelPickerOpen = true },
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(
                items = visibleOptions,
                key = { _, it -> "${it.playlist.id}::${it.channel.key}" },
            ) { index, option ->
                val lazyIdx = firstChannelLazyIndex + index
                SportsChannelOption(
                    event = event,
                    option = option,
                    bandRequester = channelBand(index),
                    mainRequester = channelMain(index),
                    onUp = {
                        if (index == visibleOptions.lastIndex && !showOtherRegionsToggle && stepStatsUpIfScrolled(lazyIdx)) {
                            true
                        } else if (index == 0) {
                            // fork:game-highlights - the highlight rows sit above the channel rows,
                            // so Up from the first channel lands on the last of them.
                            if (highlightRows.isNotEmpty()) {
                                scrollAndFocus(firstChannelLazyIndex - 1, highlightBand(highlightRows.lastIndex), FocusDirection.Up)
                            } else {
                                scrollAndFocus(3, channelsHeaderBandFocus, FocusDirection.Up)
                            }
                        } else {
                            scrollAndFocus(lazyIdx - 1, channelBand(index - 1), FocusDirection.Up)
                        }
                    },
                    onDown = {
                        when {
                            index < visibleOptions.lastIndex ->
                                scrollAndFocus(lazyIdx + 1, channelBand(index + 1))
                            showOtherRegionsToggle ->
                                scrollAndFocus(firstChannelLazyIndex + visibleOptions.size, otherRegionsFocus)
                            else -> focusPlayByPlay()
                        }
                    },
                    onPlay = {
                        if (event.state == GameState.POST && option.canCatchup) {
                            SportsWatch.playFromStart(event, option, allOptions)
                        } else {
                            SportsWatch.play(option, allOptions, SportsWatch.eventStreamId(event))
                        }
                    },
                    onPlayFromStart = if (option.canCatchup) {
                        { SportsWatch.playFromStart(event, option, allOptions) }
                    } else {
                        null
                    },
                    onMakePick = {
                        SportsSettingsRepository.setPick(event.id, "${option.playlist.id}::${option.channel.key}")
                    },
                    onClearPick = {
                        when (option.pickScope) {
                            SportsWatch.PickScope.EVENT -> SportsSettingsRepository.setPick(event.id, null)
                            SportsWatch.PickScope.TEAM -> {
                                SportsSettingsRepository.setTeamChannel(
                                    SportsFollows.teamKey(event.leagueId, event.away.team.id),
                                    null,
                                )
                                SportsSettingsRepository.setTeamChannel(
                                    SportsFollows.teamKey(event.leagueId, event.home.team.id),
                                    null,
                                )
                            }
                            SportsWatch.PickScope.LEAGUE -> SportsSettingsRepository.setLeagueChannel(event.leagueId, null)
                            SportsWatch.PickScope.NETWORK -> event.allNetworks.forEach { net ->
                                SportsSettingsRepository.setNetworkChannel(net, null)
                            }
                            null -> SportsSettingsRepository.setPick(event.id, null)
                        }
                    },
                    onBlock = {
                        SportsSettingsRepository.blockChannel(event.id, option.channel.key)
                        SportsSettingsRepository.blockChannel(event.id, "${option.playlist.id}::${option.channel.key}")
                        if (option.pickScope == SportsWatch.PickScope.EVENT) {
                            SportsSettingsRepository.setPick(event.id, null)
                        }
                    },
                )
            }
            if (showOtherRegionsToggle) {
                val toggleLazyIdx = firstChannelLazyIndex + visibleOptions.size
                item(key = "channels-other-regions") {
                    Row(
                        modifier = Modifier.sportsHorizontalBand(
                            bandRequester = otherRegionsFocus,
                            onUp = {
                                if (stepStatsUpIfScrolled(toggleLazyIdx)) {
                                    true
                                } else {
                                    val lastIdx = visibleOptions.lastIndex
                                    scrollAndFocus(firstChannelLazyIndex + lastIdx, channelBand(lastIdx), FocusDirection.Up)
                                }
                            },
                            onDown = { if (hasPlayByPlayControl) focusPlayByPlay() else stepStatsDown() },
                        ),
                    ) {
                        SportsSmallPill(
                            label = if (showOtherRegions) {
                                "Show only ${SportsChannelRegions.shortName(regionChoice)} channels"
                            } else {
                                "+ $hiddenRegionCount from other regions"
                            },
                            selected = false,
                            onClick = { showOtherRegions = !showOtherRegions },
                        )
                    }
                }
            }
        }

        val loadedSummary = summary
        val moneyline = event.moneyline ?: loadedSummary?.moneyline
        if (!hideScores && loadedSummary != null) {
            if (
                loadedSummary.playByPlay.isNotEmpty() ||
                loadedSummary.teamStats.isNotEmpty() ||
                loadedSummary.categories.isNotEmpty() ||
                loadedSummary.leaders.isNotEmpty() ||
                loadedSummary.news.isNotEmpty() ||
                loadedSummary.injuries.isNotEmpty() ||
                moneyline != null
            ) {
                item(key = "play-by-play-stats-odds") {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val playsContent: @Composable () -> Unit = {
                            if (loadedSummary.playByPlay.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    playPeriodGroups.forEachIndexed { index, (period, periodPlays) ->
                                        val periodExpanded = period in expandedPlayPeriods
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(tokens.shapes.compactCard)
                                                .background(tokens.colors.surfaceCard)
                                                .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.compactCard)
                                                .padding(tokens.spacing.cardPaddingCompact),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .bringIntoViewRequester(periodBringIntoViews[index])
                                                    .onPreviewKeyEvent { keyEvent ->
                                                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                        when (keyEvent.navigationKey()) {
                                                            // Up and Down scroll the page as they always
                                                            // have; Left and Right walk the quarters'
                                                            // toggles, which is how each one is reached
                                                            // without a focus stop per play row.
                                                            Key.DirectionDown -> scrollDetail(1)
                                                            Key.DirectionUp -> scrollDetail(-1)
                                                            else -> {
                                                                val target = periodSideStepTarget(
                                                                    keyEvent.navigationKey(),
                                                                    index,
                                                                    playPeriodGroups.lastIndex,
                                                                )
                                                                if (target != null) focusPeriod(target) else false
                                                            }
                                                        }
                                                    },
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                                            ) {
                                                Text(
                                                    text = period,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = tokens.colors.textSecondary,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                SportsSmallPill(
                                                    label = if (periodExpanded) "Show less" else "Latest 5",
                                                    selected = periodExpanded,
                                                    focusRequester = periodFocusRequesters[index],
                                                    onClick = {
                                                        expandedPlayPeriods = if (periodExpanded) {
                                                            expandedPlayPeriods - period
                                                        } else {
                                                            expandedPlayPeriods + period
                                                        }
                                                    },
                                                )
                                            }
                                            // Every period is newest first now, live or final, so
                                            // its first five are its latest five.
                                            val visiblePeriodPlays = if (periodExpanded) periodPlays else periodPlays.take(5)
                                            visiblePeriodPlays.forEach { play ->
                                                SportsPlayByPlayRow(
                                                    play = play,
                                                    twelveHour = clockSettings.twelveHour,
                                                    awayTeam = event.away.team,
                                                    homeTeam = event.home.team,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val hasPlayByPlay = loadedSummary.playByPlay.isNotEmpty()
                        val hasTeamStats = loadedSummary.teamStats.isNotEmpty()
                        val hasPlayerStats = loadedSummary.categories.isNotEmpty()
                        val hasLeaders = loadedSummary.leaders.isNotEmpty()
                        val hasNews = loadedSummary.news.isNotEmpty()
                        val hasInjuries = loadedSummary.injuries.isNotEmpty()
                        if (maxWidth >= 1100.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (hasPlayByPlay) {
                                    Column(Modifier.weight(1.3f)) { playsContent() }
                                }
                                val hasMiddle = hasTeamStats || hasPlayerStats
                                val hasRight = moneyline != null || hasNews || hasLeaders || hasInjuries
                                if (hasMiddle || hasRight) {
                                    Column(
                                        modifier = Modifier.weight(1.95f),
                                        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                                    ) {
                                        if (hasTeamStats) {
                                            // Team stats and the odds/news/leaders stack share one
                                            // height: the news card stretches so season leaders finish
                                            // level with the bottom of team stats, not player stats.
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(IntrinsicSize.Min),
                                                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Column(Modifier.weight(1.05f)) {
                                                    SportsTeamStats(event, loadedSummary.teamStats)
                                                }
                                                if (hasRight) {
                                                    SportsOddsNewsColumn(
                                                        event = event,
                                                        moneyline = moneyline,
                                                        news = loadedSummary.news,
                                                        leaders = loadedSummary.leaders,
                                                        injuries = loadedSummary.injuries,
                                                        stretchNews = true,
                                                        modifier = Modifier.weight(0.9f).fillMaxHeight(),
                                                    )
                                                }
                                            }
                                            if (hasPlayerStats) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                                                ) {
                                                    Box(Modifier.weight(1.05f)) {
                                                        SportsPlayerStats(loadedSummary.categories)
                                                    }
                                                    if (hasRight) Spacer(Modifier.weight(0.9f))
                                                }
                                            }
                                        } else {
                                            // Nothing to pin the leaders against (no team stats): lay
                                            // player stats and the odds/news/leaders column side by
                                            // side at their natural heights.
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                if (hasPlayerStats) {
                                                    Box(Modifier.weight(1.05f)) {
                                                        SportsPlayerStats(loadedSummary.categories)
                                                    }
                                                }
                                                if (hasRight) {
                                                    SportsOddsNewsColumn(
                                                        event = event,
                                                        moneyline = moneyline,
                                                        news = loadedSummary.news,
                                                        leaders = loadedSummary.leaders,
                                                        injuries = loadedSummary.injuries,
                                                        stretchNews = false,
                                                        modifier = Modifier.weight(if (hasPlayerStats) 0.9f else 1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
                                playsContent()
                                if (hasTeamStats) SportsTeamStats(event, loadedSummary.teamStats)
                                if (hasPlayerStats) SportsPlayerStats(loadedSummary.categories)
                                if (moneyline != null) SportsMoneylinePanel(event, moneyline, Modifier.fillMaxWidth())
                                if (hasNews) SportsTeamNews(event, loadedSummary.news)
                                if (hasLeaders) SportsSeasonLeaders(event, loadedSummary.leaders)
                                if (hasInjuries) SportsInjuryReport(event, loadedSummary.injuries)
                            }
                        }
                    }
                }
            }
            if (loadedSummary.scoring.isNotEmpty() && loadedSummary.playByPlay.isEmpty()) {
                item(key = "scoring-header") {
                    Text(
                        text = if (event.isLive) "Latest scoring" else "Scoring summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                }
                item(key = "scoring-body") {
                    val orderedPlays = if (event.isLive) loadedSummary.scoring.asReversed() else loadedSummary.scoring
                    SportsScoringPlays(orderedPlays)
                }
            }
        } else if (moneyline != null) {
            item(key = "moneyline-fallback-header") {
                Text(
                    text = "Moneyline & odds",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }
            item(key = "moneyline-fallback") {
                SportsMoneylinePanel(event = event, odds = moneyline, modifier = Modifier.fillMaxWidth())
            }
            if (hideScores && event.state != GameState.PRE) {
                item(key = "scores-hidden-note") {
                    Text(
                        text = "Box score and scoring plays are hidden while Hide scores is turned on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        } else if (hideScores && event.state != GameState.PRE) {
            item(key = "scores-hidden-note") {
                Text(
                    text = "Box score and scoring plays are hidden while Hide scores is turned on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        } else if (summaryFailed) {
            item(key = "summary-failed") {
                Text(
                    text = "Detailed box score stats are not available for this event yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }

        item(key = "bottom-pad") {
            Spacer(Modifier.size(20.dp))
        }
    }

    if (channelPickerOpen && event.state != GameState.POST) {
        SportsChannelPickerDialog(
            event = event,
            league = league,
            settings = settings,
            onDismiss = { channelPickerOpen = false },
            onSavedAndWatch = { pickedItem ->
                SportsWatch.play(
                    SportsWatch.Option(
                        playlist = pickedItem.playlist,
                        channel = pickedItem.channel,
                        reason = "Your pick",
                        isManualPick = true,
                    ),
                    allOptions,
                    SportsWatch.eventStreamId(event),
                )
            },
        )
    }

    if (regionDialogOpen) {
        SportsRegionFilterDialog(
            codes = allOptions.map { it.regionCode },
            current = regionChoice,
            onPick = SportsSettingsRepository::setChannelRegion,
            onDismiss = { regionDialogOpen = false },
        )
    }
}

@Composable
private fun SportsDetailActionRow(
    event: SportsEvent,
    bestOption: SportsWatch.Option?,
    replayOption: SportsWatch.Option?,
    allOptions: List<SportsWatch.Option>,
    settings: SportsSettings,
    isFieldEvent: Boolean,
    onFindSportsStreams: () -> Unit,
    addonGroups: List<AddonStreamGroup>,
    isAddonLoading: Boolean,
    debridEnabled: Boolean,
    scoreProfile: com.nuvio.app.features.streams.StreamScoreProfile,
    onPlaySportsStream: (StreamItem) -> Unit,
    bandRequester: FocusRequester,
    primaryFocus: FocusRequester,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onOpenChannelPicker: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val hasWatchPrimary = (bestOption != null && event.state != GameState.POST) ||
        (replayOption != null && event.state == GameState.POST)
    val fallbackFocus = remember { FocusRequester() }
    var fallbackAssigned = false

    val playableGroups = remember(addonGroups, debridEnabled) {
        addonGroups.mapNotNull { group ->
            val streams = group.streams.filter { it.isSelectableForPlayback(debridEnabled) }
            group.copy(streams = streams).takeIf { streams.isNotEmpty() }
        }
    }
    val scoreContext = remember(event.id, event.displayTitle) {
        StreamScoreContexts.forPlayback(isEpisode = false, contentType = "sports")
    }
    val playableStreams = remember(playableGroups) {
        playableGroups.flatMap { group -> group.streams.map { stream -> group to stream } }
    }
    val bestAddonStream = remember(playableStreams, scoreProfile, scoreContext) {
        val streams = playableStreams.map { it.second }
        if (streams.isEmpty()) null
        else if (scoreProfile.enabled && scoreProfile.appliesToFirstStream()) {
            StreamScorer.rank(streams, scoreProfile, scoreContext).firstOrNull() ?: streams.first()
        } else {
            streams.firstOrNull { it.playableDirectUrl != null } ?: streams.first()
        }
    }
    val hasAddonAction = event.state != GameState.POST
    val addonBestFocus = remember { FocusRequester() }
    val addonSourceFocusRequesters = remember(playableStreams.size) {
        List(playableStreams.size + 1) { FocusRequester() }
    }
    var addonMenuFocusIndex by remember(event.id) { mutableIntStateOf(0) }
    var addonMenuExpanded by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(addonMenuExpanded, addonSourceFocusRequesters) {
        if (addonMenuExpanded) {
            delay(60)
            addonMenuFocusIndex = 0
            addonSourceFocusRequesters.first().requestFocusLogged()
        }
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .sportsHorizontalBand(
                bandRequester = bandRequester,
                fallbackRequester = if (hasWatchPrimary || hasAddonAction) primaryFocus else fallbackFocus,
                onUp = onUp,
                onDown = onDown,
            ),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bestOption != null && event.state != GameState.POST) {
            val chName = bestOption.channel.name.ifBlank { "Channel ${bestOption.channel.num}" }
            val qualSuffix = bestOption.quality?.let { " ($it)" }.orEmpty()
            item(key = "act:watch") {
                SportsSmallPill(
                    label = "▶ Watch on $chName$qualSuffix",
                    selected = true,
                    focusRequester = primaryFocus,
                    onClick = { SportsWatch.play(bestOption, allOptions, SportsWatch.eventStreamId(event)) }
                )
            }
        } else if (replayOption != null && event.state == GameState.POST) {
            val chName = replayOption.channel.name.ifBlank { "Channel ${replayOption.channel.num}" }
            item(key = "act:replay") {
                SportsSmallPill(
                    label = "⏮ Watch replay on $chName",
                    selected = true,
                    focusRequester = primaryFocus,
                    onClick = { SportsWatch.playFromStart(event, replayOption, allOptions) },
                )
            }
        }

        if (event.state != GameState.POST) {
            val providerName = bestAddonStream?.addonName?.ifBlank { "add-on" }
            val sourceLoading = isAddonLoading
            item(key = "act:best-addon") {
                SportsSmallPill(
                    label = when {
                        bestAddonStream != null -> "▶ Best add-on · $providerName"
                        sourceLoading -> "◌ Searching add-ons…"
                        else -> "▶ Search add-ons…"
                    },
                    selected = false,
                    focusRequester = if (hasWatchPrimary) addonBestFocus else primaryFocus,
                    onClick = {
                        if (bestAddonStream != null) onPlaySportsStream(bestAddonStream)
                        else onFindSportsStreams()
                    },
                )
            }
            item(key = "act:addon-menu") {
                Box {
                    SportsSmallPill(
                        label = "▾ Sources (${playableStreams.size})",
                        selected = addonMenuExpanded,
                        onClick = { addonMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = addonMenuExpanded,
                        onDismissRequest = { addonMenuExpanded = false },
                        modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val nextIndex = when (keyEvent.navigationKey()) {
                                Key.DirectionDown -> (addonMenuFocusIndex + 1).coerceAtMost(playableStreams.size)
                                Key.DirectionUp -> (addonMenuFocusIndex - 1).coerceAtLeast(0)
                                else -> return@onPreviewKeyEvent false
                            }
                            if (nextIndex != addonMenuFocusIndex) {
                                addonMenuFocusIndex = nextIndex
                                addonSourceFocusRequesters.getOrNull(nextIndex)?.requestFocusLogged()
                            }
                            true
                        },
                    ) {
                        KeyRouteLayer("sports add-on sources")
                        playableStreams.forEachIndexed { index, (group, stream) ->
                            val streamName = stream.title?.takeIf(String::isNotBlank)
                                ?: stream.name?.takeIf(String::isNotBlank)
                                ?: stream.description?.takeIf(String::isNotBlank)
                                ?: "Sports stream ${index + 1}"
                            val label = "${group.addonName}: $streamName"
                            DropdownMenuItem(
                                modifier = Modifier.focusRequester(addonSourceFocusRequesters[index]),
                                text = {
                                    Text(
                                        text = label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = stream.addonLogo?.takeIf(String::isNotBlank)?.let { logo ->
                                    {
                                        NuvioAsyncImage(
                                            model = logo,
                                            contentDescription = "${group.addonName} logo",
                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)),
                                            contentScale = ContentScale.Fit,
                                        )
                                    }
                                },
                                onClick = {
                                    addonMenuExpanded = false
                                    onPlaySportsStream(stream)
                                },
                            )
                        }
                        if (playableStreams.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (sourceLoading) "Searching enabled add-ons…" else "No playable streams yet",
                                        maxLines = 2,
                                    )
                                },
                                enabled = false,
                                onClick = {},
                            )
                        }
                        DropdownMenuItem(
                            modifier = Modifier.focusRequester(addonSourceFocusRequesters.last()),
                            text = { Text("Find streams in all add-ons") },
                            onClick = {
                                addonMenuExpanded = false
                                onFindSportsStreams()
                            },
                        )
                    }
                }
            }
        }

        if (replayOption != null && event.state == GameState.LIVE) {
            item(key = "act:from-start") {
                SportsSmallPill(
                    label = "⏮ From start",
                    selected = false,
                    onClick = { SportsWatch.playFromStart(event, replayOption, allOptions) },
                )
            }
        }

        if (!isFieldEvent) {
            listOf(event.away.team, event.home.team)
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .forEach { team ->
                    val key = SportsFollows.teamKey(event.leagueId, team.id)
                    val following = key in settings.followedTeams
                    val name = team.shortName.ifBlank { team.name }
                    val req = if (!hasWatchPrimary && !fallbackAssigned) {
                        fallbackAssigned = true
                        fallbackFocus
                    } else {
                        null
                    }
                    item(key = "act:follow:${team.id}") {
                        SportsSmallPill(
                            label = if (following) "★ $name" else "☆ Follow $name",
                            selected = following,
                            focusRequester = req,
                            onClick = { SportsSettingsRepository.setTeamFollowed(key, !following) },
                        )
                    }
                }
        }

        if (bestOption == null && event.state != GameState.POST) {
            val req = if (!hasWatchPrimary && !fallbackAssigned) {
                fallbackAssigned = true
                fallbackFocus
            } else {
                null
            }
            item(key = "act:pick") {
                SportsSmallPill(
                    label = "Pick a channel…",
                    selected = false,
                    focusRequester = req,
                    onClick = onOpenChannelPicker,
                )
            }
        }
    }
}

/** The scoreboard: both teams either side of the score, live clock/status, situation & period linescores. */
@Composable
private fun SportsScoreboard(
    event: SportsEvent,
    sport: Sport,
    hideScores: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val isFieldEvent = event.away.team.id.endsWith(":event")
    val hasLinescores = !hideScores && !isFieldEvent &&
        (event.away.linescores.isNotEmpty() || event.home.linescores.isNotEmpty())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .sportsMatchupGradientBackground(event)
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.card)
            .padding(tokens.spacing.cardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        if (isFieldEvent) {
            Text(
                text = event.displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SportsScoreTeam(event.away, Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = when {
                            hideScores -> "• – •"
                            event.state == GameState.PRE && !event.hasScore -> "vs"
                            else -> "${event.away.score ?: 0} – ${event.home.score ?: 0}"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.colors.textPrimary,
                    )
                    if (!hideScores && event.isLive && event.clock != null) {
                        val now = rememberClockNow(event)
                        Text(
                            text = eventClockAt(event, now) ?: event.clock,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.colors.danger,
                        )
                    }
                }
                SportsScoreTeam(event.home, Modifier.weight(1f))
            }
        }

        SportsStatusBadge(event, hideScores = hideScores)

        if (!hideScores && !event.situation.isNullOrBlank()) {
            Text(
                text = event.situation,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val networks = event.allNetworks
        if (networks.isNotEmpty()) {
            Text(
                text = "Broadcast: ${networks.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
        }

        if (hasLinescores) {
            SportsPeriodLinescores(event = event, sport = sport)
        }
    }
}

@Composable
private fun SportsPeriodLinescores(event: SportsEvent, sport: Sport) {
    val tokens = MaterialTheme.nuvio
    val count = maxOf(event.away.linescores.size, event.home.linescores.size)
    if (count == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceElevated.copy(alpha = tokens.opacity.subtle))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = periodHeading(sport),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            for (i in 1..count) {
                val label = when {
                    sport != Sport.BASEBALL && i > 4 -> "OT"
                    sport == Sport.BASEBALL && i > 9 -> "E$i"
                    else -> "$i"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(28.dp),
                )
            }
            Text(
                text = "T",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(34.dp),
            )
        }
        listOf(event.away, event.home).forEach { side ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = side.team.abbreviation.ifBlank { side.team.shortName },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                for (i in 0 until count) {
                    Text(
                        text = side.linescores.getOrNull(i) ?: "–",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(28.dp),
                    )
                }
                Text(
                    text = side.score ?: "0",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp),
                )
            }
        }
    }
}

private fun periodHeading(sport: Sport): String = when (sport) {
    Sport.BASEBALL -> "INNINGS"
    Sport.HOCKEY -> "PERIODS"
    Sport.FOOTBALL, Sport.RUGBY -> "HALVES"
    else -> "QUARTERS"
}

@Composable
private fun SportsScoreTeam(side: GameSide, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            val logo = side.team.logo?.takeIf { it.isNotBlank() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(side.team.color?.let(::Color) ?: tokens.colors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (logo == null) {
                    Text(
                        text = side.team.abbreviation.take(4),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
            logo?.let {
                NuvioAsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text(
            text = side.team.shortName.ifBlank { side.team.name },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (side.record != null) {
            Text(text = side.record, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
        }
    }
}

/** One deduplicated channel group showing the game. Clicking or pressing Select plays it. */
@Composable
private fun SportsChannelOption(
    event: SportsEvent,
    option: SportsWatch.Option,
    bandRequester: FocusRequester,
    mainRequester: FocusRequester,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onPlay: () -> Unit,
    onPlayFromStart: (() -> Unit)?,
    onMakePick: () -> Unit,
    onClearPick: () -> Unit,
    onBlock: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val mainInteraction = rememberSportsInteraction(baseScale = 1.02f, label = "sportsChannelOptionZoom")
    val shape = tokens.shapes.compactCard
    val subtitle = buildList {
        add(option.playlist.name.ifBlank { "Playlist" })
        option.categoryName.takeIf { it.isNotBlank() }?.let(::add)
        add(option.reason)
    }.joinToString(" · ")

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
                color = when {
                    mainInteraction.focused -> tokens.colors.borderFocus
                    option.isManualPick -> tokens.colors.borderSelected
                    else -> tokens.colors.borderSubtle
                },
                shape = shape,
            )
            .padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .sportsInteractive(
                    state = mainInteraction,
                    focusRequester = mainRequester,
                    elevateOnFocus = false,
                    onClick = onPlay,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.colors.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "#${option.channel.num}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
                option.channel.logo?.takeIf { it.isNotBlank() }?.let { logo ->
                    NuvioAsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = option.channel.name.ifBlank { "Channel ${option.channel.num}" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (option.isManualPick || mainInteraction.focused) FontWeight.SemiBold else FontWeight.Medium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    option.quality?.let { q ->
                        ChannelMetaBadge(q)
                    }
                    if (option.backups.isNotEmpty()) {
                        ChannelMetaBadge("+${option.backups.size} ${if (option.backups.size == 1) "backup" else "backups"}")
                    }
                    if (option.canCatchup) {
                        ChannelMetaBadge("Catch-up")
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onPlayFromStart != null && event.state == GameState.LIVE) {
            SportsInlineAction("From start", onPlayFromStart)
        }
        if (option.isManualPick) {
            SportsInlineAction("Auto", onClearPick)
        } else {
            SportsInlineAction("This one", onMakePick)
        }
        SportsInlineAction("Wrong", onBlock)
        SportsInlineAction(
            label = if (event.state == GameState.POST && option.canCatchup) "Replay" else "Watch",
            onClick = onPlay,
            emphasise = true,
        )
    }
}

@Composable
private fun ChannelMetaBadge(text: String) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tokens.colors.surfaceElevated)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SportsInlineAction(label: String, onClick: () -> Unit, emphasise: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.05f, label = "sportsInlineActionZoom")
    val shape = tokens.shapes.chip
    Box(
        modifier = Modifier
            .sportsInteractive(state = interaction, onClick = onClick)
            .clip(shape)
            .background(
                when {
                    interaction.highlighted -> SolidColor(tokens.colors.focusBackground)
                    emphasise -> SolidColor(tokens.colors.surfaceElevated)
                    else -> SolidColor(Color.Transparent)
                },
            )
            .then(
                if (interaction.focused) {
                    Modifier.smoothBorder(2.dp, tokens.colors.borderFocus, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = tokens.spacing.controlGap, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (emphasise || interaction.focused) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                interaction.highlighted -> tokens.colors.accent
                emphasise -> tokens.colors.textPrimary
                else -> tokens.colors.textSecondary
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun SportsUnblockRow(
    event: SportsEvent,
    blocked: Set<String>,
    onUnblock: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val mine = blocked.mapNotNull { key ->
        val id = key.substringBefore('|')
        if (id != event.id) null else key.substringAfter('|')
    }
    if (mine.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Text(
            text = "Hidden for this game: ${mine.size}.",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
        )
        SportsInlineAction(
            label = "Reset hidden channels",
            onClick = { mine.forEach(onUnblock) },
        )
    }
}

@Composable
private fun SportsBackButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = rememberSportsInteraction(baseScale = 1.05f, label = "sportsBackBtnZoom")
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .sportsInteractive(
                state = interaction,
                focusRequester = focusRequester,
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
            .padding(
                horizontal = tokens.components.chipHorizontalPadding,
                vertical = tokens.components.chipVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "‹ Back",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (interaction.focused) FontWeight.SemiBold else FontWeight.Medium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** A section heading with an optional count, matching the LIVE NOW / COMING UP treatment. */
@Composable
internal fun SportsSectionHeader(
    title: String,
    count: Int?,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        if (live) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tokens.colors.danger)
                    .padding(4.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textSecondary,
        )
        if (count != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = if (count == 1) "1 game" else "$count games",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun SportsTeamStats(
    event: SportsEvent,
    stats: List<TeamStatPair>,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val awayColor = event.away.team.color?.let(::Color) ?: Color(0xFF232A32)
    val homeColor = event.home.team.color?.let(::Color) ?: Color(0xFF232A32)
    val awayBarColor = sportsTeamShade(awayColor, 0.34f)
    val homeBarColor = sportsTeamShade(homeColor, 0.62f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.card)
            .padding(tokens.spacing.cardPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "TEAM STATS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textSecondary,
        )
        stats.forEach { stat ->
            val awayNumber = stat.away.substringBefore('(').replace(",", "").replace("%", "").trim()
                .toFloatOrNull()?.let { kotlin.math.abs(it) }
            val homeNumber = stat.home.substringBefore('(').replace(",", "").replace("%", "").trim()
                .toFloatOrNull()?.let { kotlin.math.abs(it) }
            val total = (awayNumber ?: 0f) + (homeNumber ?: 0f)
            val awayShare = if (total > 0f) ((awayNumber ?: 0f) / total).coerceIn(0.04f, 0.96f) else 0.5f
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stat.away,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (stat.awayLeads) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (stat.awayLeads) tokens.colors.textPrimary else tokens.colors.textSecondary,
                        modifier = Modifier.weight(0.65f),
                        maxLines = 1,
                    )
                    Text(
                        text = stat.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.weight(1.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stat.home,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (stat.homeLeads) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (stat.homeLeads) tokens.colors.textPrimary else tokens.colors.textSecondary,
                        modifier = Modifier.weight(0.65f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        Modifier.weight(awayShare).fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(awayBarColor),
                    )
                    Box(
                        Modifier.weight(1f - awayShare).fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(homeBarColor),
                    )
                }
            }
        }
    }
}

private const val MAX_LIVE_PLAY_BY_PLAY = 30

/**
 * The odds, news and season-leaders stack to the right of the stats column.
 *
 * When [stretchNews] is set the stack shares its height with the team-stats card, so the news card
 * absorbs the spare space and the season leaders below it finish on the team-stats bottom edge
 * rather than running down beside player stats.
 */
@Composable
internal fun SportsOddsNewsColumn(
    event: SportsEvent,
    moneyline: MoneylineOdds?,
    news: List<TeamNews>,
    leaders: List<TeamLeaders>,
    injuries: List<TeamInjuries>,
    stretchNews: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val gap = tokens.spacing.controlGap
    val hasNews = news.isNotEmpty()
    val hasLeaders = leaders.isNotEmpty()
    val hasInjuries = injuries.isNotEmpty()
    if (!stretchNews) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (moneyline != null) SportsMoneylinePanel(event, moneyline, Modifier.fillMaxWidth())
            if (hasNews) SportsTeamNews(event = event, news = news)
            if (hasLeaders) SportsSeasonLeaders(event, leaders)
            if (hasInjuries) SportsInjuryReport(event, injuries)
        }
        return
    }

    val contents = buildList<@Composable () -> Unit> {
        if (moneyline != null) {
            add { SportsMoneylinePanel(event, moneyline, Modifier.fillMaxWidth()) }
        }
        if (hasNews) add { SportsTeamNews(event = event, news = news) }
        if (hasLeaders) add { SportsSeasonLeaders(event, leaders) }
        if (hasInjuries) add { SportsInjuryReport(event, injuries) }
    }
    if (contents.isEmpty()) return

    var cursor = 0
    if (moneyline != null) cursor++
    val newsIndex = if (hasNews) cursor++ else -1
    val leadersIndex = if (hasLeaders) cursor++ else -1
    val flexible = listOfNotNull(
        newsIndex.takeIf { it >= 0 },
        leadersIndex.takeIf { it >= 0 },
    )

    // The column's height is set by team stats. Take whatever space the odds and the two cards'
    // own content leave over and share it evenly between the news and the leaders, rather than
    // letting the news card swallow all of it.
    Layout(
        contents = contents,
        modifier = modifier.fillMaxWidth(),
        measurePolicy = object : MultiContentMeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<List<Measurable>>,
                constraints: Constraints,
            ): MeasureResult {
                val width = constraints.maxWidth
                val gapPx = gap.roundToPx()
                val children = measurables.map { it.first() }
                val natural = arrayOfNulls<Placeable>(children.size)
                val stretch = constraints.hasBoundedWidth &&
                    constraints.hasBoundedHeight &&
                    flexible.isNotEmpty()
                children.forEachIndexed { index, child ->
                    if (!stretch || index !in flexible) {
                        natural[index] = child.measure(Constraints(maxWidth = width))
                    }
                }
                val gaps = gapPx * (children.size - 1).coerceAtLeast(0)
                // A child cannot be measured twice. The cards that receive the spare height are
                // therefore sized from their intrinsic height, then measured exactly once at the
                // final height below.
                val heights = children.indices.map { index ->
                    natural[index]?.height ?: children[index].maxIntrinsicHeight(width)
                }.toMutableList()
                if (stretch) {
                    val extra = (constraints.maxHeight - heights.sum() - gaps).coerceAtLeast(0)
                    val share = extra / flexible.size
                    var remainder = extra % flexible.size
                    flexible.forEach { index ->
                        heights[index] += share
                        if (remainder > 0) {
                            heights[index]++
                            remainder--
                        }
                    }
                }
                val placed = children.mapIndexed { index, child ->
                    natural[index] ?: child.measure(Constraints.fixed(width, heights[index]))
                }
                val total = placed.sumOf { it.height } + gaps
                return layout(
                    width = width,
                    height = if (constraints.hasBoundedHeight) maxOf(total, constraints.maxHeight) else total,
                ) {
                    var y = 0
                    placed.forEachIndexed { index, placeable ->
                        placeable.place(0, y)
                        y += placeable.height
                        if (index != placed.lastIndex) y += gapPx
                    }
                }
            }
        },
    )
}

@Composable
private fun SportsMoneylinePanel(
    event: SportsEvent,
    odds: MoneylineOdds?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .clip(tokens.shapes.card)
            .sportsMatchupGradientBackground(event, awayShare = moneylineAwayShare(odds))
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.card)
            .padding(tokens.spacing.cardPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MONEYLINE & ODDS",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                odds?.provider?.takeIf { it.isNotBlank() }?.let { provider ->
                    Text(
                        text = provider,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Spread and total read as part of the heading, so the moneyline numbers keep the
            // card's body to themselves and the panel stays short above the news.
            val spread = odds?.spread?.takeIf { it.isNotBlank() }
            val total = odds?.overUnder?.takeIf { it.isNotBlank() }
            if (spread != null || total != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    spread?.let {
                        Text(
                            text = "Spread  $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                    total?.let {
                        Text(
                            text = "Total  $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(event.away.team.abbreviation, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
                Text(odds?.away ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(event.home.team.abbreviation, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
                Text(odds?.home ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1)
            }
        }
        if (odds == null) {
            Text("Odds aren't available for this event.", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        }
    }
}

@Composable
private fun SportsPlayByPlayRow(
    play: PlayByPlayEntry,
    twelveHour: Boolean,
    awayTeam: Team,
    homeTeam: Team,
) {
    val tokens = MaterialTheme.nuvio
    val localTime = play.wallclockMs?.let { formatSportsPlayTime(it, twelveHour) }
    val meta = listOfNotNull(localTime, play.clock?.takeIf { it.isNotBlank() }).joinToString(" · ")
    val scoringTeam = when (play.scoringTeamId) {
        awayTeam.id -> awayTeam
        homeTeam.id -> homeTeam
        else -> null
    }
    // The hero's round team card uses the brand colour straight from the feed; the points use the
    // same so the two sides stay distinguishable instead of both washing out toward white.
    val scoringColor = scoringTeam?.color?.let(::Color) ?: tokens.colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = tokens.spacing.cardPaddingCompact, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = meta.ifBlank { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.width(142.dp),
            maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = play.text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (play.scoring) FontWeight.SemiBold else FontWeight.Normal,
                color = if (play.scoring) tokens.colors.accent else tokens.colors.textPrimary,
            )
            if (play.awayScore != null || play.homeScore != null) {
                Text(
                    text = "${play.awayScore ?: "—"} – ${play.homeScore ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        // The points this play scored, tinted in the colour of the team that got them.
        play.points?.takeIf { it > 0 }?.let { points ->
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "+$points",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = scoringColor,
                    maxLines = 1,
                )
                scoringTeam?.let { team ->
                    Text(
                        text = team.abbreviation.ifBlank { team.shortName.take(3) },
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SportsScoringPlays(plays: List<ScoringPlay>) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.card)
            .padding(tokens.spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        plays.forEach { play ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = listOfNotNull(play.period, play.clock).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.widthIn(min = 72.dp),
                )
                Text(
                    text = play.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (play.awayScore != null && play.homeScore != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${play.awayScore}–${play.homeScore}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SportsPlayerStats(categories: List<StatCategory>, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(tokens.colors.surfaceCard)
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.card)
            .padding(tokens.spacing.cardPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "PLAYER STATS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textSecondary,
        )
        categories.forEachIndexed { categoryIndex, category ->
            if (categoryIndex > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(tokens.borders.hairline)
                        .background(tokens.colors.borderSubtle),
                )
            }
            val labels = category.players.firstOrNull()?.stats?.map { it.label }.orEmpty()
            val columns = sportsPlayerStatColumns(labels)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
                // Column headings once per category, then a dense one-line row per player.
                if (columns.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1.7f))
                        columns.forEach { index ->
                            Text(
                                text = labels.getOrNull(index).orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                                modifier = Modifier.weight(0.62f),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                category.players.forEach { player ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = listOfNotNull(player.name, player.position).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textSecondary,
                            modifier = Modifier.weight(1.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        columns.forEach { index ->
                            Text(
                                text = player.stats.getOrNull(index)?.value?.takeIf { it.isNotBlank() } ?: "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textPrimary,
                                modifier = Modifier.weight(0.62f),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // A provider with no column labels still gets its numbers shown.
                        if (columns.isEmpty() && player.headline != null) {
                            Text(
                                text = player.headline,
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                                modifier = Modifier.weight(1.2f),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
