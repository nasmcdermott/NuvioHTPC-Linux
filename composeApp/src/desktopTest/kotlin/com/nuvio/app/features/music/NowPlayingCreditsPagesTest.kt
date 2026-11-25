package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.AlbumCredits
import com.nuvio.app.features.music.library.Credit
import com.nuvio.app.features.music.library.CreditKind
import com.nuvio.app.features.music.library.peopleOnTrack
import com.nuvio.app.features.music.library.creditPeople
import com.nuvio.app.features.music.library.trackCreditLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credits deck's pure side: the per-track credit line, the people on a track, and the page order
 * (the song first, then one page per kind that has somebody, in the album lens's kind order).
 */
class NowPlayingCreditsPagesTest {
    private val credits = listOf(
        Credit("A Writer", null, CreditKind.Writer, "composer", 1),
        Credit("B Writer", null, CreditKind.Writer, "lyricist", 1),
        Credit("C Producer", null, CreditKind.Producer, null, 0),
        Credit("D Player", null, CreditKind.Player, "guitar", 2),
    )

    private val album = AlbumCredits(0L, "id", "Artist", "Album", credits, found = true)

    @Test
    fun theSongLineNamesTheWritersAndProducersOfThatTrack() {
        assertEquals("Written by A Writer, B Writer", trackCreditLine(credits, 1))
        assertEquals("Produced by C Producer", trackCreditLine(credits, 0))
        assertNull(trackCreditLine(credits, 2), "a player is not a writer or producer")
    }

    @Test
    fun aTrackCarriesItsOwnCreditsAndTheAlbumWideOnes() {
        val people = creditPeople(credits)
        val onOne = peopleOnTrack(people, 1).map { it.name }
        assertTrue("C Producer" in onOne, "the album-wide producer plays on every song")
        assertTrue("A Writer" in onOne)
        assertFalse("D Player" in onOne, "a track-2 player is not on track 1")
    }

    @Test
    fun theSongPageComesFirstAndOnlyKindsWithPeopleFollow() {
        val pages = nowPlayingCreditsPages(album, 1, listOf("Song One", "Song Two"))
        assertEquals(listOf("THE SONG", "WRITERS", "PRODUCERS", "PLAYERS"), pages.map { pageLabelOf(it) })
        val song = pages.first() as CreditsPage.Song
        assertEquals("Song One", song.title)
        assertEquals("Written by A Writer, B Writer", song.line)
    }

    @Test
    fun aTrackOutsideTheAlbumHasNoSongPageButTheKindsRemain() {
        val pages = nowPlayingCreditsPages(album, null, listOf("Song One", "Song Two"))
        assertFalse(pages.any { it is CreditsPage.Song })
        assertEquals(3, pages.size)
    }

    @Test
    fun anAlbumWithoutCreditsHasNoPages() {
        val none = AlbumCredits(0L, "id", "Artist", "Album", emptyList(), found = true)
        assertTrue(nowPlayingCreditsPages(none, 1, listOf("Song One")).isEmpty())
    }

    private fun pageLabelOf(page: CreditsPage): String = when (page) {
        is CreditsPage.Song -> "THE SONG"
        is CreditsPage.Kind -> page.kind.plural.uppercase()
    }
}
