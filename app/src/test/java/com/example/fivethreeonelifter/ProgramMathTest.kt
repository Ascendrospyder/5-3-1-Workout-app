package com.example.fivethreeonelifter

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramMathTest {
    @Test
    fun weekThreeUsesFiveThreeOnePercentages() {
        val sets = ProgramMath.weekSets(3)
        assertEquals(0.75, sets[0].percentage, 0.0001)
        assertEquals("5", sets[0].reps)
        assertEquals(0.85, sets[1].percentage, 0.0001)
        assertEquals("3", sets[1].reps)
        assertEquals(0.95, sets[2].percentage, 0.0001)
        assertEquals("1+", sets[2].reps)
    }

    @Test
    fun roundsToNearestTwoPointFiveKg() {
        assertEquals(52.5, ProgramMath.roundToIncrement(51.9, 2.5), 0.0001)
        assertEquals(50.0, ProgramMath.roundToIncrement(50.7, 2.5), 0.0001)
    }

    @Test
    fun calculatesNinetyPercentTrainingMax() {
        assertEquals(90.0, ProgramMath.trainingMax(100.0, 0.90), 0.0001)
    }
}
