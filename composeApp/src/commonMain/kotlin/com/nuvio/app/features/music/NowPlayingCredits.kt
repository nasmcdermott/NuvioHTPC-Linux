package com.nuvio.app.features.music
// FORK UI (Spyder): credits on the Now Playing lyric page, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NavAction
import com.nuvio.app.core.ui.navAction
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.features.music.booklet.bookletTrackNumber
import com.nuvio.app.features.music.library.AlbumCredits
import com.nuvio.app.features.music.library.CreditKind
import com.nuvio.app.features.music.library.CreditPerson
import com.nuvio.app.features.music.library.ElsewhereRecord
import com.nuvio.app.features.music.library.PeopleCreditsBook
import com.nuvio.app.features.music.library.creditPeople
import com.nuvio.app.features.music.library.creditsKey
import com.nuvio.app.features.music.library.elsewhere
import com.nuvio.app.features.music.library.musicGlass
import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.music.library.peopleOnTrack
import com.nuvio.app.features.music.library.trackCreditLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * fork:credits-stage - the playing song's credits, on the lyric page: a "Credits" pill in the lyric pane's top right
 * corner that expands the MusicBrainz credits (the album lens's store, PeopleCreditsBook) into a deck in the lyrics'
 * place. The deck copies the booklet's mechanics (NowPlayingBooklet.kt): FitToHeight pages, Left and Right turn,
 * Up and Down walk the rows, Select opens. Keys go through event.navAction() (core/ui/NavAction.kt), so a rebound
 * key or a pad binding follows; at either end turn and walk return false, so focus walks on past the expansion —
 * Up from the corner lands on the Options button above it, and Back (the pane's own key) leaves the stage as before.
 */

/** The pages of the credits deck, in turn order. Pure, so it is tested without Compose. */
internal sealed interface CreditsPage {
    /** The playing song: its "Written by … · Produced by …" line and everyone credited on it. */
    data class Song(val title: String, val line: String?, val people: List<CreditPerson>) : CreditsPage

    /** One kind of credit across the whole album, only when somebody has it. */
    data class Kind(val kind: CreditKind, val people: List<CreditPerson>) : CreditsPage
}

/** The fixed pages for [credits] as it is playing track [trackNumber] (1 up; null when the track is not of this album). */
internal fun nowPlayingCreditsPages(
    credits: AlbumCredits,
    trackNumber: Int?,
    trackTitles: List<String>,
): List<CreditsPage> {
    val people = creditPeople(credits.credits)
    val pages = ArrayList<CreditsPage>()
    if (trackNumber != null && trackNumber >= 1) {
        val songPeople = peopleOnTrack(people, trackNumber)
        val line = trackCreditLine(credits.credits, trackNumber)
        if (songPeople.isNotEmpty() || line != null) {
            pages += CreditsPage.Song(trackTitles.getOrNull(trackNumber - 1).orEmpty(), line, songPeople)
        }
    }
    for (kind in CreditKind.entries) {
        val kindPeople = people.filter { kind in it.kinds }
        if (kindPeople.isNotEmpty()) pages += CreditsPage.Kind(kind, kindPeople)
    }
    return pages
}

/** How many rows a page walks with Up and Down. */
private fun rowCount(page: CreditsPage): Int = when (page) {
    is CreditsPage.Song -> page.people.size
    is CreditsPage.Kind -> page.people.size
}

private fun pageLabel(page: CreditsPage): String = when (page) {
    is CreditsPage.Song -> "THE SONG"
    is CreditsPage.Kind -> page.kind.plural.uppercase()
}

private fun pageHint(page: CreditsPage): String = when {
    rowCount(page) > 0 -> "Up and Down choose · Select opens"
    else -> "Left and Right turn the page"
}

/** The pill in the lyric pane's top right corner, and the credits deck it opens in the lyrics' place. */
@Composable
internal fun NowPlayingCreditsPane(
    album: MusicAlbum?,
    artist: String?,
    playingVideoId: String?,
    reduced: Boolean,
    onOpenAlbum: (String) -> Unit,
    onJumpToTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The lyrics themselves, drawn until the deck is open. */
    lyrics: @Composable (Modifier) -> Unit,
) {
    var open by remember(album?.browseId) { mutableStateOf(false) }
    var credits by remember(album?.browseId) { mutableStateOf<AlbumCredits?>(null) }
    var asked by remember(album?.browseId) { mutableStateOf(false) }
    var loading by remember(album?.browseId) { mutableStateOf(false) }
    // The album lens's store also fills in the background (the facts filler); this bumps the read when it does.
    val creditsTick = PeopleCreditsBook.changes.collectAsStateWithLifecycle().value
    val hasTarget = album != null && !artist.isNullOrBlank() && album.title.isNotBlank()
    LaunchedEffect(open, hasTarget, album?.browseId, creditsTick) {
        val target = album?.takeIf { hasTarget } ?: return@LaunchedEffect
        if (!open || credits != null || asked) return@LaunchedEffect
        asked = true
        loading = true
        credits = PeopleCreditsBook.load(artist!!, target.title, target.browseId)
        loading = false
    }
    // The whole library's kept credits, for the person page's "elsewhere" panel; read once, off the main thread.
    var kept by remember(album?.browseId) { mutableStateOf<Map<String, AlbumCredits>>(emptyMap()) }
    LaunchedEffect(open, creditsTick) {
        if (open) kept = withContext(Dispatchers.Default) { PeopleCreditsBook.allKept() }
    }

    val pillFocus = remember { FocusRequester() }
    val deckFocus = remember { FocusRequester() }
    LaunchedEffect(open, credits, loading) {
        when {
            !open -> runCatching { pillFocus.requestFocusLogged() }
            credits?.found == true -> runCatching { deckFocus.requestFocusLogged() }
        }
    }

    Box(modifier) {
        if (!open) {
            lyrics(Modifier.fillMaxSize())
        } else {
            val found = credits?.takeIf { it.found }
            val trackNumber = album?.let { bookletTrackNumber(it.tracks.map { t -> t.videoId }, playingVideoId) }
            val titles = album?.tracks?.map { it.title }.orEmpty()
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 34.dp) // the corner pill stays above the deck
                    .musicGlass(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    found != null -> CreditsDeck(
                        credits = found,
                        trackNumber = trackNumber,
                        trackTitles = titles,
                        kept = kept,
                        hereKey = creditsKey(artist.orEmpty(), album!!.title),
                        reduced = reduced,
                        onOpenAlbum = onOpenAlbum,
                        onJumpToTrack = onJumpToTrack,
                        focusRequester = deckFocus,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                    loading || !asked -> Message("Asking MusicBrainz…")
                    credits != null -> Message("MusicBrainz has no credits for this album")
                    else -> Message("MusicBrainz could not be reached")
                }
            }
        }
        CreditsPill(
            open = open,
            enabled = hasTarget,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .focusRequester(pillFocus),
            onClick = { open = !open },
        )
    }
}

/** The corner pill: "Credits" closed, "Lyrics" open, so there is always a way back by pointer too. */
@Composable
private fun CreditsPill(open: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (open) 0.6f else 0.45f))
            .border(1.dp, Color.White.copy(alpha = if (open) 0.5f else 0.25f), CircleShape)
            .then(if (enabled) Modifier.musicKeyFocusRing(CircleShape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (open) "Lyrics" else "Credits",
            color = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The deck: the song's page, then one page per credit kind; Select opens a person. Internal for its navigation test. */
@Composable
internal fun CreditsDeck(
    credits: AlbumCredits,
    trackNumber: Int?,
    trackTitles: List<String>,
    kept: Map<String, AlbumCredits>,
    hereKey: String,
    reduced: Boolean,
    onOpenAlbum: (String) -> Unit,
    onJumpToTrack: (Int) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val pages = remember(credits, trackNumber, trackTitles) { nowPlayingCreditsPages(credits, trackNumber, trackTitles) }
    if (pages.isEmpty()) {
        Message("No credits on this track")
        return
    }
    var page by remember(pages) { mutableIntStateOf(0) }
    var cursor by remember(pages) { mutableIntStateOf(0) }
    // The person page opened from a row, and where to return to: a page index and a cursor.
    var person by remember(pages) { mutableStateOf<CreditPerson?>(null) }
    var personReturn by remember(pages) { mutableStateOf(0 to 0) }
    var forward by remember { mutableStateOf(true) }
    val personRecords = remember(person, kept, hereKey) { person?.let { elsewhere(it, kept, hereKey) }.orEmpty() }
    val personRowCount = (person?.tracks?.size ?: 0) + personRecords.size

    /** Turns toward [direction]; false when it cannot (an end, or a person page going right). */
    fun turn(direction: Int): Boolean {
        if (person != null) {
            if (direction >= 0) return false
            person = null
            page = personReturn.first
            cursor = personReturn.second
            return true
        }
        val next = page + direction
        if (next !in pages.indices) return false
        forward = direction > 0
        page = next
        cursor = 0
        return true
    }

    /** Up or Down through the page's rows; false at either end, so focus moves on past the deck. */
    fun walk(direction: Int): Boolean {
        val count = if (person != null) personRowCount else rowCount(pages[page])
        val next = cursor + direction
        if (next !in 0 until count) return false
        cursor = next
        return true
    }

    fun select() {
        if (person != null) {
            when {
                cursor < person!!.tracks.size -> onJumpToTrack(person!!.tracks.elementAt(cursor) - 1)
                else -> personRecords.getOrNull(cursor - person!!.tracks.size)?.albumId?.let(onOpenAlbum)
            }
            return
        }
        when (val current = pages[page]) {
            is CreditsPage.Song -> current.people.getOrNull(cursor)?.let {
                personReturn = page to cursor
                person = it
                cursor = 0
            }
            is CreditsPage.Kind -> current.people.getOrNull(cursor)?.let {
                personReturn = page to cursor
                person = it
                cursor = 0
            }
        }
    }

    /** A row's pick by pointer: put the deck's cursor on it, then act as Select would. */
    fun pick(index: Int) {
        cursor = index
        select()
    }

    AnimatedContent(
        targetState = (person != null) to page,
        modifier = modifier
            .testTag("creditsDeck")
            .musicKeyFocusRing(RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // The funnel, not raw keys: a rebound Move Left or a pad bound to it arrives as the same action.
                when (event.navAction()) {
                    NavAction.Left -> turn(-1)
                    NavAction.Right -> if (person == null) turn(1) else false
                    NavAction.Up -> walk(-1)
                    NavAction.Down -> walk(1)
                    NavAction.Select -> {
                        select()
                        true
                    }
                    else -> false
                }
            },
        transitionSpec = {
            if (reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (slideInHorizontally(tween(260)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(180)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(160)))
            }
        },
        label = "creditsPage",
    ) { (isPerson, pageIndex) ->
        val current = pages.getOrNull(pageIndex) ?: CreditsPage.Kind(CreditKind.Writer, emptyList())
        val shownPerson = if (isPerson) person else null
        if (shownPerson != null) {
            PersonPage(shownPerson, personRecords, trackTitles, cursor, accent) { pick(it) }
        } else {
            when (val shown = current) {
                is CreditsPage.Song -> PagePage(shown, pageLabel(shown), cursor, accent) { pick(it) }
                is CreditsPage.Kind -> PagePage(shown, pageLabel(shown), cursor, accent) { pick(it) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        NavPill("‹", page > 0 || person != null) { turn(-1) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            pages.indices.forEach { i ->
                Box(Modifier.height(7.dp).width(7.dp).clip(CircleShape).background(if (i == page && person == null) accent else Color.White.copy(alpha = 0.25f)))
            }
        }
        NavPill("›", page < pages.lastIndex && person == null) { turn(1) }
    }
    LinerHintRow(
        if (person != null) "Select plays or opens · Left goes back" else pageHint(pages[page]),
        switch = {},
    )
}

/** A fixed page: its label, then its rows with the deck's cursor marked. */
@Composable
private fun PagePage(
    page: CreditsPage,
    label: String,
    cursor: Int,
    accent: Color,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageLabel(label, accent)
        Spacer(Modifier.height(6.dp))
        if (page is CreditsPage.Song) {
            Text(
                page.title,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontSize = 24.sp,
                lineHeight = 27.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (page.line != null) {
                Text(
                    page.line,
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        val people = when (page) {
            is CreditsPage.Song -> page.people
            is CreditsPage.Kind -> page.people
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            people.forEachIndexed { i, p -> PersonRow(p, null, i == cursor, accent) { onPick(i) } }
        }
    }
}

/** The person page: what they did here, their tracks on this album, and where they turn up elsewhere in the library. */
@Composable
private fun PersonPage(
    person: CreditPerson,
    records: List<ElsewhereRecord>,
    trackTitles: List<String>,
    cursor: Int,
    accent: Color,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageLabel("CREDITS", accent)
        Spacer(Modifier.height(6.dp))
        Text(person.name, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(person.what, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            person.tracks.forEachIndexed { i, t ->
                PersonRow(
                    CreditPerson(trackTitles.getOrNull(t - 1) ?: "Track $t", null, emptySet(), person.what, emptySet(), false),
                    trailing = "Play",
                    selected = i == cursor,
                    accent = accent,
                ) { onPick(i) }
            }
            records.forEachIndexed { i, r ->
                PersonRow(
                    CreditPerson("${r.album} — ${r.artist}", null, emptySet(), r.what, emptySet(), false),
                    trailing = "Open",
                    selected = person.tracks.size + i == cursor,
                    accent = accent,
                ) { onPick(person.tracks.size + i) }
            }
        }
        if (person.tracks.isEmpty() && records.isEmpty()) {
            Text("Nothing else here", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
        }
    }
}

/** One row of a credits page: the person's name and what they did, marked when the deck's cursor is on it. */
@Composable
private fun PersonRow(person: CreditPerson, trailing: String?, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, accent.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                person.name,
                color = Color.White.copy(alpha = if (selected) 1f else 0.85f),
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (person.what.isNotBlank()) {
                Text(person.what, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
}
