package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The de-suffixed title the MusicBrainz credits lookup retries with (fork:credits-stage). */
class StripTitleSuffixesTest {
    @Test
    fun aFeatSuffixComesOff() {
        assertEquals("Instant Crush", stripTitleSuffixes("Instant Crush (feat. Julian Casablancas)"))
    }

    @Test
    fun stackedSuffixesAllComeOff() {
        assertEquals("Abbey Road", stripTitleSuffixes("Abbey Road (Remastered) [2011 Mix]"))
    }

    @Test
    fun aTitleWithoutSuffixesHasNoFallback() {
        assertNull(stripTitleSuffixes("Discovery"))
        assertNull(stripTitleSuffixes(" Discovery "))
    }

    @Test
    fun strippingEverythingIsNotAFallback() {
        assertNull(stripTitleSuffixes("(feat. X)"))
    }

    @Test
    fun aTitleThatOnlyEndsInABracketLookingWordIsUnchanged() {
        // The bracket is not at the end, so there is no trailing suffix to strip and no fallback query.
        assertNull(stripTitleSuffixes("Still Life (Exile), Vol. 1"))
    }
}
