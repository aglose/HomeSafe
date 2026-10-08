package com.meticulouscreations.homesafe.navigation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FitnessSharesTest {

    @AfterTest
    fun clear() = FitnessShares.consume()

    @Test
    fun aSharedNoteWaitsUnderItsTitleUntilItIsConsumed() {
        FitnessShares.offer("Hack squat\n- 290lbs - 12 reps", title = "Legs")
        assertEquals("Legs\nHack squat\n- 290lbs - 12 reps", FitnessShares.pending.value)
        FitnessShares.consume()
        assertNull(FitnessShares.pending.value)
    }

    @Test
    fun aTitleTheTextAlreadyOpensWithIsNotRepeated() {
        FitnessShares.offer("legs\n\nHack squat\n- 290lbs - 12 reps", title = "Legs")
        assertEquals("legs\n\nHack squat\n- 290lbs - 12 reps", FitnessShares.pending.value)
    }

    @Test
    fun aNoteWithNoTitleIsItsTextTrimmed() {
        FitnessShares.offer("  Dips\n- 35 reps \n", title = " ")
        assertEquals("Dips\n- 35 reps", FitnessShares.pending.value)
    }

    @Test
    fun nothingAndTooMuchAreBothIgnored() {
        FitnessShares.offer(null, title = "Legs")
        assertNull(FitnessShares.pending.value)
        FitnessShares.offer("   ")
        assertNull(FitnessShares.pending.value)
        FitnessShares.offer("x".repeat(FitnessShares.MAX_LENGTH + 1))
        assertNull(FitnessShares.pending.value)
    }
}
