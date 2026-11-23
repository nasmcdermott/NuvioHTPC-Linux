package com.nuvio.app.features.navbar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bar answers a page control's position, the way the sidebar answers the Settings rail's height
 * (RootNavFocus.enterTopBarNear). The Sports calendar's Up uses it: a day under the right of the bar
 * must reach the icon drawn there, not always the bar's first one.
 */
class NearestTopBarSlotTest {

    // A 600px bar starting at x=200 holding four icons: 200..350, 350..500, 500..650, 650..800.
    private fun slot(x: Float) = nearestTopBarSlot(x, barLeftPx = 200f, barWidthPx = 600f, slotCount = 4)

    @Test
    fun aPositionLandsOnTheIconDrawnOverIt() {
        assertEquals(0, slot(210f))
        assertEquals(0, slot(340f))
        assertEquals(1, slot(360f))
        assertEquals(2, slot(520f))
        assertEquals(3, slot(700f))
    }

    @Test
    fun positionsPastEitherEndClampToTheEndIcons() {
        assertEquals(0, slot(0f))
        assertEquals(0, slot(-500f))
        assertEquals(3, slot(5000f))
    }

    @Test
    fun aBarThatIsNotMeasuredYetOrHoldsOneIconIsSafe() {
        assertEquals(0, nearestTopBarSlot(640f, barLeftPx = 0f, barWidthPx = 0f, slotCount = 10))
        assertEquals(0, nearestTopBarSlot(640f, barLeftPx = 200f, barWidthPx = 600f, slotCount = 1))
        assertEquals(0, nearestTopBarSlot(640f, barLeftPx = 200f, barWidthPx = 600f, slotCount = 0))
    }
}
