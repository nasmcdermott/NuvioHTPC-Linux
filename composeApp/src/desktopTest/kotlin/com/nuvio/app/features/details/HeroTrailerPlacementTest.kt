package com.nuvio.app.features.details

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.components.HeroTrailerBottomClearance
import com.nuvio.app.features.details.components.heroTrailerPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bounded hero trailer's box rules (see [heroTrailerPlacement]): shaped to the video rather than
 * the window's spare space, never over the text lane, and pinned to the top-right corner even when
 * the art area clamps its height.
 */
class HeroTrailerPlacementTest {

    /** The desktop layout's lane: 64dp start, at most 760dp wide. */
    private val laneEnd = 824.dp

    private fun placement(heroWidth: Int, heroHeight: Int, laneEnd: androidx.compose.ui.unit.Dp = this.laneEnd) =
        heroTrailerPlacement(heroWidth.dp, heroHeight.dp, laneEnd)

    @Test
    fun `a roomy window gets the full top-right corner at the video's own aspect`() {
        val p = placement(heroWidth = 2000, heroHeight = 760)
        // Clear of the lane, right edge at the window's edge.
        assertTrue(p.start >= laneEnd + 24.dp, "clear of the text lane: start ${p.start}")
        assertEquals(2000.dp, p.start + p.width)
        // 16:9, not the spare space's aspect.
        assertEquals(p.width * 9f / 16f, p.height)
    }

    @Test
    fun `the box is never taller than the art area above the cast row`() {
        // The wanted 16:9 height for a 2000dp hero is 630dp; only 402dp is art above the clearance.
        val p = placement(heroWidth = 2000, heroHeight = 760)
        assertEquals(760.dp - HeroTrailerBottomClearance, p.height)
        assertTrue(p.height <= 760.dp - HeroTrailerBottomClearance)
    }

    @Test
    fun `a clamped box right-aligns instead of drifting left`() {
        val p = placement(heroWidth = 2000, heroHeight = 760)
        // Height was clamped (402dp), so the box narrows to 16:9 of that and hugs the right edge.
        assertEquals(2000.dp, p.start + p.width)
        assertEquals(p.height * 16f / 9f, p.width)
        assertTrue(p.start > 880.dp, "pinned to the corner, not the unclamped start: ${p.start}")
    }

    @Test
    fun `a narrow window keeps a box, left of the lane when it must`() {
        // At 1400dp the lane leaves only 576dp: the 56% cap wins and the box may overlap the lane,
        // which the lane-fade handles - the point is that a box still exists and stays right-anchored.
        val p = placement(heroWidth = 1400, heroHeight = 760)
        assertEquals(1400.dp, p.start + p.width)
        assertTrue(p.width > 0.dp)
        assertTrue(p.height > 0.dp)
        assertTrue(p.start >= 1400.dp * 0.44f - 1.dp, "never left of the old start: ${p.start}")
    }

    @Test
    fun `the box sits above the cast row, top anchored`() {
        val p = placement(heroWidth = 1600, heroHeight = 700)
        // start..heroWidth horizontally, 0..height vertically: the bottom edge is hero start + height
        // measured from the top, which the caller turns into bottom padding = heroHeight - height.
        assertTrue(p.height <= 700.dp - HeroTrailerBottomClearance)
    }
}
