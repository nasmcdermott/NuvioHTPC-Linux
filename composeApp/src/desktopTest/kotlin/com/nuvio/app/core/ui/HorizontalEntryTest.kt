package com.nuvio.app.core.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page's own row of controls across the top answers by position ([RootNavFocus.enterHorizontalEntry]),
 * so a control below it can reach the one drawn over it. The Sports calendar's Up asks this row first
 * and the floating bar second, which is why a day under the chips reaches a chip and a day under the
 * middle reaches the bar.
 */
class HorizontalEntryTest {

    private val owners = mutableListOf<Any>()

    private fun register(pick: (Float) -> Boolean): Any {
        val owner = Any()
        owners += owner
        RootNavFocus.registerHorizontalEntry(owner, pick)
        return owner
    }

    @AfterTest
    fun cleanUp() {
        owners.forEach { RootNavFocus.unregisterHorizontalEntry(it) }
        owners.clear()
    }

    @Test
    fun theRowTakesThePositionItIsDrawnOver() {
        val chip = Any()
        var took = 0f
        RootNavFocus.registerHorizontalEntry(chip) { x ->
            if (x in 0f..120f) { took = x; true } else false
        }
        try {
            assertTrue(RootNavFocus.enterHorizontalEntry(60f))
            assertTrue(took == 60f)
            // Nothing is drawn over the middle: the bar, asked next, answers for it.
            assertFalse(RootNavFocus.enterHorizontalEntry(640f))
        } finally {
            RootNavFocus.unregisterHorizontalEntry(chip)
        }
    }

    @Test
    fun aRowWithNothingThereLetsTheNextAnswer() {
        val first = register { false }
        val second = register { x -> x > 500f }
        assertTrue(RootNavFocus.enterHorizontalEntry(700f), "the row that has a control there takes it")
        assertFalse(RootNavFocus.enterHorizontalEntry(100f))
        RootNavFocus.unregisterHorizontalEntry(first)
        RootNavFocus.unregisterHorizontalEntry(second)
    }

    @Test
    fun aPickerThatThrowsCountsAsDeclining() {
        register { error("no control there") }
        assertFalse(RootNavFocus.enterHorizontalEntry(10f))
    }
}
