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
    val sortOrder: Int,
    val exerciseId: Long? = null
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

data class WorkoutStats(
    val exerciseCount: Int,
    val completedSets: Int,
    val totalSets: Int,
    val totalReps: Int,
    val totalVolume: Double,
    val topWeight: Double,
    val durationSeconds: Long,
    val prCount: Int = 0
)

data class ExerciseWorkoutStats(
    val workoutExerciseId: Long,
    val exerciseName: String,
    val completedSets: Int,
    val totalSets: Int,
    val totalReps: Int,
    val volume: Double,
    val topWeight: Double,
    val estimatedOneRepMax: Double
)

data class HistoryOverview(
    val totalWorkouts: Int,
    val workoutsLast30Days: Int,
    val totalCompletedSets: Int,
    val totalReps: Int,
    val totalVolume: Double,
    val averageDurationSeconds: Long
)

data class ExercisePersonalRecords(
    val exerciseId: Long?,
    val exerciseName: String,
    val heaviestWeight: Double,
    val heaviestWeightReps: Int,
    val heaviestAt: Long,
    val estimatedOneRepMax: Double,
    val e1rmWeight: Double,
    val e1rmReps: Int,
    val e1rmAt: Long
)

data class ScheduledWorkoutDay(
    val dayOfWeek: Int,
    val templateId: Long?,
    val templateName: String?
)

