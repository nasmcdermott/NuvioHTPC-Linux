package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The team/league picker's search box must not trap the keys. Compose leaves the arrow keys to a
 * text field's cursor, and a dialog declines Back while a field holds it, so unless the box answers
 * those two presses itself the picker has a control the focus cannot leave.
 */
class SportsFollowPickerNavTest {

    @Test
    fun downOpensTheFirstResultRatherThanResuming() {
        // Down is the way into the list, so it always starts at the top even when a deeper row was
        // visited before - resuming there would look like a jump to a row nobody pointed at.
        assertEquals(0, searchBoxFocusTarget(isDown = true, isBack = false, hasResults = true, lastRow = 7))
    }

    @Test
    fun backReturnsToTheRowTheViewerLastHad() {
        assertEquals(7, searchBoxFocusTarget(isDown = false, isBack = true, hasResults = true, lastRow = 7))
    }

    @Test
    fun backBeforeAnyRowWasVisitedOpensTheFirst() {
        assertEquals(0, searchBoxFocusTarget(isDown = false, isBack = true, hasResults = true, lastRow = -1))
        assertEquals(0, searchBoxFocusTarget(isDown = false, isBack = true, hasResults = true, lastRow = 0))
    }

    @Test
    fun withoutResultsNeitherKeyMovesAndBackStillCloses() {
        // Nothing to focus: the press falls through, so Back closes the picker exactly as a modal
        // with no list in it should, and Down does nothing at all.
        assertNull(searchBoxFocusTarget(isDown = true, isBack = false, hasResults = false, lastRow = 0))
        assertNull(searchBoxFocusTarget(isDown = false, isBack = true, hasResults = false, lastRow = 0))
    }

    @Test
    fun everyOtherKeyBelongsToTheTextBox() {
        assertNull(searchBoxFocusTarget(isDown = false, isBack = false, hasResults = true, lastRow = 3))
    }
}
