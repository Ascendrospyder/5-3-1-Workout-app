package com.example.fivethreeonelifter

data class ExercisePreset(
    val name: String,
    val category: String,
    val sets: Int,
    val repMin: Int,
    val repMax: Int
) {
    fun toExercise(): Exercise = Exercise(
        name = name,
        mode = Exercise.MODE_MANUAL,
        defaultWeight = 0.0,
        defaultSets = sets,
        repMin = repMin,
        repMax = repMax
    )
}

object ExercisePresets {
    const val CHEST = "Chest"
    const val BACK = "Back"
    const val LEGS = "Legs"
    const val ARMS = "Arms"
    const val SHOULDERS = "Shoulders"

    val categories = listOf(CHEST, BACK, LEGS, ARMS, SHOULDERS)

    val all = listOf(
        ExercisePreset("Bench Press", CHEST, 3, 5, 8),
        ExercisePreset("Incline Bench Press", CHEST, 3, 6, 10),
        ExercisePreset("Dumbbell Bench Press", CHEST, 3, 8, 12),
        ExercisePreset("Incline Dumbbell Press", CHEST, 3, 8, 12),
        ExercisePreset("Cable Fly", CHEST, 3, 10, 15),
        ExercisePreset("Pec Deck", CHEST, 3, 10, 15),
        ExercisePreset("Dips", CHEST, 3, 6, 12),

        ExercisePreset("Chest-Supported Row", BACK, 4, 6, 10),
        ExercisePreset("Lat Pulldown", BACK, 4, 6, 10),
        ExercisePreset("Pull-Up", BACK, 3, 5, 10),
        ExercisePreset("Seated Cable Row", BACK, 3, 8, 12),
        ExercisePreset("Barbell Row", BACK, 3, 6, 10),
        ExercisePreset("One-Arm Dumbbell Row", BACK, 3, 8, 12),
        ExercisePreset("Face Pull", BACK, 3, 12, 20),

        ExercisePreset("Back Squat", LEGS, 3, 5, 8),
        ExercisePreset("Front Squat", LEGS, 3, 5, 8),
        ExercisePreset("Leg Press", LEGS, 3, 8, 12),
        ExercisePreset("Romanian Deadlift", LEGS, 3, 6, 10),
        ExercisePreset("Bulgarian Split Squat", LEGS, 3, 8, 12),
        ExercisePreset("Leg Curl", LEGS, 3, 10, 15),
        ExercisePreset("Leg Extension", LEGS, 3, 10, 15),
        ExercisePreset("Hip Thrust", LEGS, 3, 8, 12),
        ExercisePreset("Standing Calf Raise", LEGS, 4, 10, 15),

        ExercisePreset("Dumbbell Curl", ARMS, 3, 8, 12),
        ExercisePreset("Hammer Curl", ARMS, 3, 10, 15),
        ExercisePreset("Preacher Curl", ARMS, 3, 8, 12),
        ExercisePreset("Cable Curl", ARMS, 3, 10, 15),
        ExercisePreset("Triceps Pushdown", ARMS, 3, 10, 15),
        ExercisePreset("Overhead Triceps Extension", ARMS, 3, 10, 15),
        ExercisePreset("Skull Crusher", ARMS, 3, 8, 12),
        ExercisePreset("Close-Grip Bench Press", ARMS, 3, 6, 10),

        ExercisePreset("Overhead Press", SHOULDERS, 3, 5, 8),
        ExercisePreset("Dumbbell Shoulder Press", SHOULDERS, 3, 8, 12),
        ExercisePreset("Lateral Raise", SHOULDERS, 3, 12, 20),
        ExercisePreset("Rear-Delt Fly", SHOULDERS, 3, 12, 20),
        ExercisePreset("Cable Lateral Raise", SHOULDERS, 3, 12, 20)
    )

    fun inCategory(category: String): List<ExercisePreset> = all.filter { it.category == category }
}
