package com.example.fivethreeonelifter

object WorkoutDefaults {
    data class SetDefaults(
        val targetReps: String,
        val percentage: Double?,
        val plannedWeight: Double,
        val weight: Double,
        val reps: Int?
    )

    fun sets(exercise: Exercise, week: Int, previous: List<WorkoutSetRecord>, saved: List<SetDefaults> = emptyList()): List<SetDefaults> {
        val prescriptions = if (exercise.isFiveThreeOne) {
            ProgramMath.weekSets(week).map { prescription ->
                val weight = ProgramMath.workingWeight(exercise.trainingMax, prescription.percentage, exercise.roundTo)
                SetDefaults(prescription.reps, prescription.percentage, weight, weight, null)
            } + saved.drop(3)
        } else {
            val target = if (exercise.repMin == exercise.repMax) exercise.repMin.toString() else "${exercise.repMin}-${exercise.repMax}"
            saved.ifEmpty { List(exercise.defaultSets.coerceAtLeast(1)) {
                SetDefaults(target, null, exercise.defaultWeight, exercise.defaultWeight, null)
            } }
        }

        // A new 5/3/1 week or Training Max must take precedence over logged overrides.
        val samePrescription = !exercise.isFiveThreeOne ||
            (previous.size == prescriptions.size && prescriptions.withIndex().all { (index, prescription) ->
                previous.any { old ->
                    old.setNumber == index + 1 && old.targetReps == prescription.targetReps &&
                        old.percentage == prescription.percentage && old.plannedWeight == prescription.plannedWeight
                }
            })
        val completedByNumber = previous.filter { it.completed }.associateBy { it.setNumber }
        return prescriptions.mapIndexed { index, prescription ->
            val old = if (samePrescription) completedByNumber[index + 1] else null
            if (old == null) prescription else prescription.copy(weight = old.actualWeight, reps = old.actualReps)
        }
    }
}
