package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvNavigationTest {

    @Test
    fun theRailGoesToTheBarOnlyAboveTheFirstPageRow() {
        val headerBottom = 72f
        // Rows beside the bar/header: Right goes up into the navigation bar.
        assertTrue(iptvSidebarRightLeavesForBar(itemTop = 24f, pageHeaderBottom = headerBottom))
        assertTrue(iptvSidebarRightLeavesForBar(itemTop = 60f, pageHeaderBottom = headerBottom))
        // Rows beside the channel list/wall: Right goes into those rows instead.
        assertFalse(iptvSidebarRightLeavesForBar(itemTop = 72f, pageHeaderBottom = headerBottom))
        assertFalse(iptvSidebarRightLeavesForBar(itemTop = 140f, pageHeaderBottom = headerBottom))
    }

    @Test
    fun unknownLayoutFallsBackToTheBar() {
        assertTrue(iptvSidebarRightLeavesForBar(itemTop = null, pageHeaderBottom = 72f))
        assertTrue(iptvSidebarRightLeavesForBar(itemTop = 140f, pageHeaderBottom = 0f))
    }
}
