package com.nuvio.app.features.sports

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The locally drawn clock: how a provider reading becomes a per-second display, when it must stop,
 * and what the two reads around it have to agree on before the ticker is allowed to move.
 *
 * Every test passes its own clock values: none of these rules may depend on the wall clock of the
 * machine running them.
 */
class SportsClockTest {

    private fun event(
        clock: String? = "3:53",
        state: GameState = GameState.LIVE,
        status: String? = "3:53 - 4th Quarter",
        leagueId: String = "basketball/nba",
        readAtMs: Long = 1_000L,
        moving: Boolean = true,
    ) = SportsEvent(
        id = "401910036",
        leagueId = leagueId,
        startMs = 1_790_000_000_000,
        state = state,
        status = status,
        clock = clock,
        away = GameSide(team = Team("29", "Memphis Grizzlies", "Grizzlies", "MEM"), score = "101"),
        home = GameSide(team = Team("30", "Charlotte Hornets", "Hornets", "CHA"), score = "97"),
        clockReadAtMs = readAtMs,
        clockMoving = moving,
    )

    // ---- reading and writing the provider's clock

    @Test
    fun `a provider clock is read as seconds`() {
        assertEquals(233, clockSeconds("3:53"))
        assertEquals(720, clockSeconds("12:00"))
        assertEquals(0, clockSeconds("0:00"))
        assertEquals(582, clockSeconds(" 9:42 "))
    }

    @Test
    fun `a clock that is not a plain mm ss count is not read`() {
        assertNull(clockSeconds(null))
        assertNull(clockSeconds(""))
        assertNull(clockSeconds("90'+5'"))
        assertNull(clockSeconds("3:5"))
        assertNull(clockSeconds("3:60"))
        assertNull(clockSeconds("1:2:3"))
        assertNull(clockSeconds("End of 3rd"))
    }

    @Test
    fun `seconds are written back the way the provider writes them`() {
        assertEquals("3:53", clockText(233))
        assertEquals("12:00", clockText(720))
        assertEquals("0:00", clockText(0))
        assertEquals("0:00", clockText(-5))
    }

    // ---- the interpolator

    @Test
    fun `a running clock counts down from the moment it was read`() {
        assertEquals("3:53", clockAt("3:53", Sport.BASKETBALL, 1_000L, 1_000L, moving = true))
        assertEquals("3:48", clockAt("3:53", Sport.BASKETBALL, 1_000L, 6_000L, moving = true))
        assertEquals("16:41", clockAt("17:00", Sport.AMERICAN_FOOTBALL, 1_000L, 20_000L, moving = true))
    }

    @Test
    fun `a clock the provider has stopped is held where it stopped`() {
        assertEquals("3:53", clockAt("3:53", Sport.BASKETBALL, 1_000L, 9_000L, moving = false))
    }

    @Test
    fun `a reading too old to trust is shown exactly as it came`() {
        assertEquals(
            "3:53",
            clockAt("3:53", Sport.BASKETBALL, 1_000L, 1_000L + CLOCK_TICK_MAX_AGE_MS + 1, moving = true),
        )
    }

    @Test
    fun `a countdown never passes zero`() {
        assertEquals("0:00", clockAt("0:02", Sport.BASKETBALL, 1_000L, 11_000L, moving = true))
    }

    @Test
    fun `soccer's elapsed clock is left to the provider`() {
        assertEquals("67'", clockAt("67'", Sport.FOOTBALL, 1_000L, 6_000L, moving = true))
        assertEquals("90'+5'", clockAt("90'+5'", Sport.FOOTBALL, 1_000L, 6_000L, moving = true))
    }

    @Test
    fun `rugby's elapsed clock is left to the provider too`() {
        assertEquals("67:12", clockAt("67:12", Sport.RUGBY, 1_000L, 6_000L, moving = true))
        assertEquals("80'", clockAt("80'", Sport.RUGBY, 1_000L, 6_000L, moving = true))
    }

    @Test
    fun `a clock that cannot be read is left alone`() {
        assertEquals("End of 3rd", clockAt("End of 3rd", Sport.BASKETBALL, 1_000L, 6_000L, moving = true))
        assertNull(clockAt(null, Sport.BASKETBALL, 1_000L, 6_000L, moving = true))
    }

    @Test
    fun `the status line keeps the provider's words and swaps only the clock`() {
        assertEquals("3:48 - 4th Quarter", clockStatusAt(event(), 6_000L))
        assertEquals("4th · 3:48", clockStatusAt(event(status = "4th · 3:53"), 6_000L))
        assertEquals("3:53 - 4th Quarter", clockStatusAt(event(moving = false), 6_000L))
    }

    @Test
    fun `a status that cannot be advanced is left as it came`() {
        assertEquals("Halftime", clockStatusAt(event(clock = null, status = "Halftime"), 6_000L))
        assertEquals("End of 3rd", clockStatusAt(event(status = "End of 3rd"), 6_000L))
    }

    // ---- which reads make a clock run or stop

    @Test
    fun `a live game's first reading starts the clock now`() {
        val stamped = stampLiveClocks(emptyList(), listOf(event(readAtMs = 0L, moving = false)), 5_000L).single()
        assertEquals(5_000L, stamped.clockReadAtMs)
        assertEquals(true, stamped.clockMoving)
    }

    @Test
    fun `the same reading keeps the moment it was first seen`() {
        val previous = listOf(event(readAtMs = 1_000L, moving = true))
        val stamped = stampLiveClocks(
            previous,
            listOf(event(readAtMs = 0L, moving = false)),
            5_000L,
        ).single()
        assertEquals(1_000L, stamped.clockReadAtMs)
        assertEquals(true, stamped.clockMoving)
    }

    @Test
    fun `the same reading held for the window means the clock is stopped`() {
        val previous = listOf(event(readAtMs = 1_000L, moving = true))
        val stamped = stampLiveClocks(
            previous,
            listOf(event(readAtMs = 0L, moving = false)),
            1_000L + CLOCK_HELD_AFTER_MS,
        ).single()
        assertEquals(1_000L, stamped.clockReadAtMs, "the anchor is still when the value was first seen")
        assertEquals(false, stamped.clockMoving)
    }

    @Test
    fun `a changed reading resyncs and starts the clock again`() {
        val previous = listOf(event(clock = "3:53", readAtMs = 1_000L, moving = false))
        val stamped = stampLiveClocks(
            previous,
            listOf(event(clock = "3:45", readAtMs = 0L, moving = false)),
            6_000L,
        ).single()
        assertEquals("3:45", stamped.clock)
        assertEquals(6_000L, stamped.clockReadAtMs)
        assertEquals(true, stamped.clockMoving)
    }

    @Test
    fun `a reading with no anchor behind it is anchored by this read`() {
        // Games restored from the disk cache carry no anchor, because it is not serialized.
        val previous = listOf(event(readAtMs = 0L, moving = false))
        val stamped = stampLiveClocks(
            previous,
            listOf(event(readAtMs = 0L, moving = false)),
            5_000L,
        ).single()
        assertEquals(5_000L, stamped.clockReadAtMs)
        assertEquals(true, stamped.clockMoving)
    }

    @Test
    fun `a finished or not yet started game has no anchor`() {
        val post = stampLiveClocks(
            emptyList(),
            listOf(event(state = GameState.POST, clock = null, status = "Final")),
            5_000L,
        ).single()
        assertEquals(0L, post.clockReadAtMs)
        assertEquals(false, post.clockMoving)

        val pre = stampLiveClocks(emptyList(), listOf(event(state = GameState.PRE)), 5_000L).single()
        assertEquals(0L, pre.clockReadAtMs)
        assertEquals(false, pre.clockMoving)
    }

    @Test
    fun `a poll that found nothing new leaves the games equal`() {
        val once = stampLiveClocks(emptyList(), listOf(event(readAtMs = 0L, moving = false)), 1_000L)
        val twice = stampLiveClocks(once, listOf(event(readAtMs = 0L, moving = false)), 5_000L)
        assertEquals(once, twice, "an equal list is what tells the page not to re-render")
    }

    @Test
    fun `the clock anchor is not written to the cache`() {
        val encoded = Json.encodeToString(SportsEvent.serializer(), event())
        assertFalse(encoded.contains("clockReadAtMs"))
        assertFalse(encoded.contains("clockMoving"))
        val restored = Json.decodeFromString(SportsEvent.serializer(), encoded)
        assertEquals(0L, restored.clockReadAtMs)
        assertEquals(false, restored.clockMoving)
    }
}
