package com.example.fivethreeonelifter

data class Exercise(
    val id: Long = 0,
    val name: String,
    val mode: String,
    val oneRepMax: Double = 0.0,
    val tmPercent: Double = 0.90,
    val trainingMax: Double = 0.0,
    val roundTo: Double = 2.5,
    val increment: Double = 2.5,
    val defaultWeight: Double = 0.0,
    val defaultSets: Int = 3,
    val repMin: Int = 8,
    val repMax: Int = 12
) {
    val isFiveThreeOne: Boolean get() = mode == MODE_531

    companion object {
        const val MODE_531 = "531"
        const val MODE_MANUAL = "MANUAL"
    }
}

data class WorkoutTemplate(
    val id: Long,
    val name: String
)

data class WorkoutSummary(
    val id: Long,
    val templateName: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: String,
    val completedSets: Int = 0,
    val totalSets: Int = 0
) {
    companion object {
        const val ACTIVE = "ACTIVE"
        const val COMPLETED = "COMPLETED"
    }
}

data class WorkoutExerciseRecord(
    val id: Long,
    val exerciseName: String,
    val mode: String,
    val sortOrder: Int
)

data class WorkoutSetRecord(
    val id: Long,
    val workoutExerciseId: Long,
    val setNumber: Int,
    val targetReps: String,
    val percentage: Double?,
    val plannedWeight: Double,
    val actualWeight: Double,
    val actualReps: Int?,
    val completed: Boolean
)
