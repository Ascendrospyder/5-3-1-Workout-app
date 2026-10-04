package com.example.fivethreeonelifter

import org.junit.Assert.*
import org.junit.Test

class WorkoutDefaultsTest {
    private val manual = Exercise(id = 1, name = "Row", mode = Exercise.MODE_MANUAL, defaultWeight = 20.0)
    private val fiveThreeOne = manual.copy(mode = Exercise.MODE_531, trainingMax = 100.0)

    private fun oldSet(number: Int, weight: Double, reps: Int?, done: Boolean = true) = WorkoutSetRecord(
        id = number.toLong(), workoutExerciseId = 1, setNumber = number,
        targetReps = "8-12", percentage = null, plannedWeight = 20.0,
        actualWeight = weight, actualReps = reps, completed = done
    )

    private fun previous531() = ProgramMath.weekSets(1).mapIndexed { index, prescription ->
        val planned = ProgramMath.workingWeight(100.0, prescription.percentage, 2.5)
        oldSet(index + 1, planned + 2.5, 7 + index).copy(
            targetReps = prescription.reps, percentage = prescription.percentage, plannedWeight = planned
        )
    }

    @Test fun carriesEachManualSetAcrossWeeks() {
        val sets = WorkoutDefaults.sets(manual, 3, listOf(oldSet(2, 27.5, 9), oldSet(1, 25.0, 10)))
        assertEquals(25.0, sets[0].weight, 0.001)
        assertEquals(10, sets[0].reps)
        assertEquals(27.5, sets[1].weight, 0.001)
        assertEquals(9, sets[1].reps)
        assertEquals(20.0, sets[2].weight, 0.001)
        assertNull(sets[2].reps)
    }

    @Test fun skippedSetsDoNotCarryPrefilledValuesForward() {
        val sets = WorkoutDefaults.sets(manual, 1, listOf(oldSet(1, 30.0, 12, done = false)))
        assertEquals(20.0, sets[0].weight, 0.001)
        assertNull(sets[0].reps)
    }

    @Test fun firstWorkoutUsesTemplateDefaults() {
        val sets = WorkoutDefaults.sets(manual, 1, emptyList())
        assertEquals(3, sets.size)
        assertTrue(sets.all { it.weight == 20.0 && it.targetReps == "8-12" && it.reps == null })
    }

    @Test fun carries531OverridesWhenPrescriptionIsUnchanged() {
        val sets = WorkoutDefaults.sets(fiveThreeOne, 1, previous531())
        assertEquals(67.5, sets[0].weight, 0.001)
        assertEquals(7, sets[0].reps)
        assertEquals(65.0, sets[0].plannedWeight, 0.001)
    }

    @Test fun advancing531WeekUsesNewWeightsAndReps() {
        val sets = WorkoutDefaults.sets(fiveThreeOne, 2, previous531())
        assertEquals(70.0, sets[0].weight, 0.001)
        assertEquals("3", sets[0].targetReps)
        assertEquals("3+", sets[2].targetReps)
        assertTrue(sets.all { it.reps == null })
    }

    @Test fun changingTrainingMaxRecalculates531Loads() {
        val sets = WorkoutDefaults.sets(fiveThreeOne.copy(trainingMax = 110.0), 1, previous531())
        assertEquals(72.5, sets[0].weight, 0.001)
        assertTrue(sets.all { it.reps == null })
    }

    @Test fun reducingSetCountKeepsOnlyCurrentSetsAndPreservesBlankReps() {
        val sets = WorkoutDefaults.sets(manual.copy(defaultSets = 1), 1,
            listOf(oldSet(1, 25.0, null), oldSet(2, 30.0, 10)))
        assertEquals(1, sets.size)
        assertEquals(25.0, sets[0].weight, 0.001)
        assertNull(sets[0].reps)
    }

    @Test fun savedTemplateDefaultsRetainPerSetWeightsAndRepTargets() {
        val saved = listOf(
            WorkoutDefaults.SetDefaults("10", null, 25.0, 25.0, 10),
            WorkoutDefaults.SetDefaults("8", null, 30.0, 30.0, 8)
        )
        val sets = WorkoutDefaults.sets(manual, 1, emptyList(), saved)
        assertEquals(2, sets.size)
        assertEquals(25.0, sets[0].weight, 0.001)
        assertEquals("8", sets[1].targetReps)
        assertEquals(8, sets[1].reps)
    }

    @Test fun lastSessionOverridesSavedManualDefaultsWithoutChangingStructure() {
        val saved = listOf(WorkoutDefaults.SetDefaults("6-8", null, 40.0, 40.0, 6))
        val sets = WorkoutDefaults.sets(manual, 4, listOf(oldSet(1, 42.5, 8)), saved)
        assertEquals(1, sets.size)
        assertEquals("6-8", sets[0].targetReps)
        assertEquals(42.5, sets[0].weight, 0.001)
    }

    @Test fun templateUpdatesCannotOverride531Progression() {
        val saved = List(4) { WorkoutDefaults.SetDefaults("12", null, 200.0, 200.0, 12) }
        val sets = WorkoutDefaults.sets(fiveThreeOne, 2, previous531(), saved)
        assertEquals(4, sets.size)
        assertEquals(70.0, sets[0].weight, 0.001)
        assertEquals("3", sets[0].targetReps)
        assertNull(sets[0].reps)
        assertEquals(200.0, sets[3].weight, 0.001)
    }
}
