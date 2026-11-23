package com.nuvio.app.features.sports

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quarter toggles ("Latest 5" / "Show less") sit in one tall plays card, so Down and Up belong
 * to scrolling the page. Left and Right are what walk from one quarter's toggle to the next; at
 * either end they keep their usual meaning instead of pinning focus.
 */
class SportsPlayByPlayFocusTest {

    @Test
    fun `left and right move between the quarters' toggles`() {
        assertEquals(0, periodSideStepTarget(Key.DirectionLeft, index = 1, lastIndex = 3))
        assertEquals(2, periodSideStepTarget(Key.DirectionRight, index = 1, lastIndex = 3))
    }

    @Test
    fun `left at the first toggle and right at the last keep their usual meaning`() {
        assertNull(periodSideStepTarget(Key.DirectionLeft, index = 0, lastIndex = 3))
        assertNull(periodSideStepTarget(Key.DirectionRight, index = 3, lastIndex = 3))
    }

    @Test
    fun `only sideways keys step, and a card with no quarters steps nowhere`() {
        assertNull(periodSideStepTarget(Key.DirectionDown, index = 1, lastIndex = 3))
        assertNull(periodSideStepTarget(Key.DirectionUp, index = 1, lastIndex = 3))
        assertNull(periodSideStepTarget(Key.DirectionRight, index = 0, lastIndex = -1))
    }

    @Test
    fun `periods read newest first, with overtime above regulation and Other last`() {
        val periods = listOf("1st Quarter", "4th Quarter", "OT", "3rd Quarter", "2nd Quarter", "Other")

        val ordered = periods.sortedByDescending(::sportsPeriodOrder)

        assertEquals(
            listOf("OT", "4th Quarter", "3rd Quarter", "2nd Quarter", "1st Quarter", "Other"),
            ordered,
        )
        assertTrue(sportsPeriodOrder("2nd Half") > sportsPeriodOrder("1st Half"))
    }

    @Test
    fun `wallclock puts newest first whichever way the feed lists the plays`() {
        val oldest = play("oldest", "1st Quarter", "15:00", wallclock = 1_000L)
        val middle = play("middle", "2nd Quarter", "10:00", wallclock = 2_000L)
        val newest = play("newest", "4th Quarter", "0:30", wallclock = 3_000L)

        val periods = playByPlayPeriodsNewestFirst(listOf(oldest, middle, newest))
        assertEquals(listOf("4th Quarter", "2nd Quarter", "1st Quarter"), periods.map { it.first })
        assertEquals(listOf("newest", "middle", "oldest"), periods.flatMap { it.second }.map { it.id })

        // A live summary can send its drives newest-first; the wall clock still wins.
        val reversedFeed = playByPlayPeriodsNewestFirst(listOf(newest, middle, oldest))
        assertEquals(listOf("newest", "middle", "oldest"), reversedFeed.flatMap { it.second }.map { it.id })
    }

    @Test
    fun `without wallclock chronological feeds reverse and newest-first feeds stay`() {
        val first = play("first", "1st Half", "10:00")
        val second = play("second", "2nd Half", "80:00")

        assertEquals(
            listOf("second", "first"),
            playByPlayPeriodsNewestFirst(listOf(first, second)).flatMap { it.second }.map { it.id },
        )
        assertEquals(
            listOf("second", "first"),
            playByPlayPeriodsNewestFirst(listOf(second, first)).flatMap { it.second }.map { it.id },
        )
    }

    @Test
    fun `a live history keeps only its newest plays`() {
        val plays = (1..8).map { play("p$it", "1st", it.toString(), wallclock = it.toLong()) }

        val periods = playByPlayPeriodsNewestFirst(plays, limit = 3)

        assertEquals(listOf("p8", "p7", "p6"), periods.single().second.map { it.id })
    }

    private fun play(
        id: String,
        period: String,
        clock: String,
        wallclock: Long? = null,
    ) = PlayByPlayEntry(
        id = id,
        period = period,
        clock = clock,
        text = id,
        wallclockMs = wallclock,
    )
}
