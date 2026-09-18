package com.example.fivethreeonelifter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WorkoutDb(context: Context) : SQLiteOpenHelper(context, "liftlog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                mode TEXT NOT NULL,
                one_rep_max REAL NOT NULL DEFAULT 0,
                tm_percent REAL NOT NULL DEFAULT 0.90,
                training_max REAL NOT NULL DEFAULT 0,
                round_to REAL NOT NULL DEFAULT 2.5,
                increment REAL NOT NULL DEFAULT 2.5,
                default_weight REAL NOT NULL DEFAULT 0,
                default_sets INTEGER NOT NULL DEFAULT 3,
                rep_min INTEGER NOT NULL DEFAULT 8,
                rep_max INTEGER NOT NULL DEFAULT 12
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE template_exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                template_id INTEGER NOT NULL,
                exercise_id INTEGER NOT NULL,
                sort_order INTEGER NOT NULL,
                FOREIGN KEY(template_id) REFERENCES templates(id) ON DELETE CASCADE,
                FOREIGN KEY(exercise_id) REFERENCES exercises(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE workouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                template_id INTEGER,
                template_name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE workout_exercises (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                workout_id INTEGER NOT NULL,
                exercise_id INTEGER,
                exercise_name TEXT NOT NULL,
                mode TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                FOREIGN KEY(workout_id) REFERENCES workouts(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE workout_sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                workout_exercise_id INTEGER NOT NULL,
                set_number INTEGER NOT NULL,
                target_reps TEXT NOT NULL,
                percentage REAL,
                planned_weight REAL NOT NULL,
                actual_weight REAL NOT NULL,
                actual_reps INTEGER,
                completed INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(workout_exercise_id) REFERENCES workout_exercises(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO settings(key, value) VALUES('week', '1')")
        db.execSQL("INSERT INTO settings(key, value) VALUES('cycle', '1')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun getSettingInt(key: String, fallback: Int): Int {
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0).toIntOrNull() ?: fallback else fallback
        }
    }

    fun setSettingInt(key: String, value: Int) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value.toString())
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getExercises(): List<Exercise> {
        val result = mutableListOf<Exercise>()
        readableDatabase.rawQuery("SELECT * FROM exercises ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                result += Exercise(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    mode = c.getString(c.getColumnIndexOrThrow("mode")),
                    oneRepMax = c.getDouble(c.getColumnIndexOrThrow("one_rep_max")),
                    tmPercent = c.getDouble(c.getColumnIndexOrThrow("tm_percent")),
                    trainingMax = c.getDouble(c.getColumnIndexOrThrow("training_max")),
                    roundTo = c.getDouble(c.getColumnIndexOrThrow("round_to")),
                    increment = c.getDouble(c.getColumnIndexOrThrow("increment")),
                    defaultWeight = c.getDouble(c.getColumnIndexOrThrow("default_weight")),
                    defaultSets = c.getInt(c.getColumnIndexOrThrow("default_sets")),
                    repMin = c.getInt(c.getColumnIndexOrThrow("rep_min")),
                    repMax = c.getInt(c.getColumnIndexOrThrow("rep_max"))
                )
            }
        }
        return result
    }

    fun getExercise(id: Long): Exercise? = getExercises().firstOrNull { it.id == id }

    fun saveExercise(exercise: Exercise): Long {
        val cv = ContentValues().apply {
            put("name", exercise.name)
            put("mode", exercise.mode)
            put("one_rep_max", exercise.oneRepMax)
            put("tm_percent", exercise.tmPercent)
            put("training_max", exercise.trainingMax)
            put("round_to", exercise.roundTo)
            put("increment", exercise.increment)
            put("default_weight", exercise.defaultWeight)
            put("default_sets", exercise.defaultSets)
            put("rep_min", exercise.repMin)
            put("rep_max", exercise.repMax)
        }
        return if (exercise.id == 0L) {
            writableDatabase.insertOrThrow("exercises", null, cv)
        } else {
            writableDatabase.update("exercises", cv, "id=?", arrayOf(exercise.id.toString()))
            exercise.id
        }
    }

    fun deleteExercise(id: Long) {
        writableDatabase.delete("exercises", "id=?", arrayOf(id.toString()))
    }

    fun getTemplates(): List<WorkoutTemplate> {
        val result = mutableListOf<WorkoutTemplate>()
        readableDatabase.rawQuery("SELECT id, name FROM templates ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += WorkoutTemplate(c.getLong(0), c.getString(1))
        }
        return result
    }

    fun createTemplate(name: String): Long {
        val cv = ContentValues().apply { put("name", name) }
        return writableDatabase.insertOrThrow("templates", null, cv)
    }

    fun renameTemplate(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        writableDatabase.update("templates", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteTemplate(id: Long) {
        writableDatabase.delete("templates", "id=?", arrayOf(id.toString()))
    }

    fun getTemplateExercises(templateId: Long): List<Exercise> {
        val result = mutableListOf<Exercise>()
        val sql = """
            SELECT e.* FROM template_exercises te
            JOIN exercises e ON e.id = te.exercise_id
            WHERE te.template_id=? ORDER BY te.sort_order, te.id
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(templateId.toString())).use { c ->
            while (c.moveToNext()) {
                result += Exercise(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    mode = c.getString(c.getColumnIndexOrThrow("mode")),
                    oneRepMax = c.getDouble(c.getColumnIndexOrThrow("one_rep_max")),
                    tmPercent = c.getDouble(c.getColumnIndexOrThrow("tm_percent")),
                    trainingMax = c.getDouble(c.getColumnIndexOrThrow("training_max")),
                    roundTo = c.getDouble(c.getColumnIndexOrThrow("round_to")),
                    increment = c.getDouble(c.getColumnIndexOrThrow("increment")),
                    defaultWeight = c.getDouble(c.getColumnIndexOrThrow("default_weight")),
                    defaultSets = c.getInt(c.getColumnIndexOrThrow("default_sets")),
                    repMin = c.getInt(c.getColumnIndexOrThrow("rep_min")),
                    repMax = c.getInt(c.getColumnIndexOrThrow("rep_max"))
                )
            }
        }
        return result
    }

    fun addExerciseToTemplate(templateId: Long, exerciseId: Long) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM template_exercises WHERE template_id=? AND exercise_id=?",
            arrayOf(templateId.toString(), exerciseId.toString())
        ).use { c ->
            c.moveToFirst()
            if (c.getInt(0) > 0) return
        }
        var nextOrder = 0
        readableDatabase.rawQuery("SELECT COALESCE(MAX(sort_order), -1)+1 FROM template_exercises WHERE template_id=?", arrayOf(templateId.toString())).use { c ->
            if (c.moveToFirst()) nextOrder = c.getInt(0)
        }
        val cv = ContentValues().apply {
            put("template_id", templateId)
            put("exercise_id", exerciseId)
            put("sort_order", nextOrder)
        }
        writableDatabase.insertOrThrow("template_exercises", null, cv)
    }

    fun removeExerciseFromTemplate(templateId: Long, exerciseId: Long) {
        writableDatabase.delete("template_exercises", "template_id=? AND exercise_id=?", arrayOf(templateId.toString(), exerciseId.toString()))
    }

    fun getActiveWorkout(): WorkoutSummary? {
        val sql = """
            SELECT w.id, w.template_name, w.started_at, w.completed_at, w.status,
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN 1 ELSE 0 END),0), COUNT(s.id)
            FROM workouts w
            LEFT JOIN workout_exercises we ON we.workout_id=w.id
            LEFT JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.status=? GROUP BY w.id ORDER BY w.started_at DESC LIMIT 1
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(WorkoutSummary.ACTIVE)).use { c ->
            return if (c.moveToFirst()) summaryFromCursor(c) else null
        }
    }

    fun startWorkout(template: WorkoutTemplate, week: Int): Long {
        val exercises = getTemplateExercises(template.id)
        require(exercises.isNotEmpty()) { "Template has no exercises" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val workoutId = db.insertOrThrow("workouts", null, ContentValues().apply {
                put("template_id", template.id)
                put("template_name", template.name)
                put("started_at", System.currentTimeMillis())
                put("status", WorkoutSummary.ACTIVE)
            })

            exercises.forEachIndexed { exerciseIndex, exercise ->
                val workoutExerciseId = db.insertOrThrow("workout_exercises", null, ContentValues().apply {
                    put("workout_id", workoutId)
                    put("exercise_id", exercise.id)
                    put("exercise_name", exercise.name)
                    put("mode", exercise.mode)
                    put("sort_order", exerciseIndex)
                })

                if (exercise.isFiveThreeOne) {
                    ProgramMath.weekSets(week).forEachIndexed { setIndex, prescription ->
                        val weight = ProgramMath.workingWeight(exercise.trainingMax, prescription.percentage, exercise.roundTo)
                        db.insertOrThrow("workout_sets", null, ContentValues().apply {
                            put("workout_exercise_id", workoutExerciseId)
                            put("set_number", setIndex + 1)
                            put("target_reps", prescription.reps)
                            put("percentage", prescription.percentage)
                            put("planned_weight", weight)
                            put("actual_weight", weight)
                            put("completed", 0)
                        })
                    }
                } else {
                    val reps = if (exercise.repMin == exercise.repMax) exercise.repMin.toString() else "${exercise.repMin}-${exercise.repMax}"
                    repeat(exercise.defaultSets.coerceAtLeast(1)) { setIndex ->
                        db.insertOrThrow("workout_sets", null, ContentValues().apply {
                            put("workout_exercise_id", workoutExerciseId)
                            put("set_number", setIndex + 1)
                            put("target_reps", reps)
                            putNull("percentage")
                            put("planned_weight", exercise.defaultWeight)
                            put("actual_weight", exercise.defaultWeight)
                            put("completed", 0)
                        })
                    }
                }
            }
            db.setTransactionSuccessful()
            return workoutId
        } finally {
            db.endTransaction()
        }
    }

    fun getWorkout(id: Long): WorkoutSummary? {
        val sql = """
            SELECT w.id, w.template_name, w.started_at, w.completed_at, w.status,
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN 1 ELSE 0 END),0), COUNT(s.id)
            FROM workouts w
            LEFT JOIN workout_exercises we ON we.workout_id=w.id
            LEFT JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.id=? GROUP BY w.id
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) summaryFromCursor(c) else null
        }
    }

    fun getWorkoutExercises(workoutId: Long): List<WorkoutExerciseRecord> {
        val result = mutableListOf<WorkoutExerciseRecord>()
        readableDatabase.rawQuery(
            "SELECT id, exercise_name, mode, sort_order FROM workout_exercises WHERE workout_id=? ORDER BY sort_order, id",
            arrayOf(workoutId.toString())
        ).use { c ->
            while (c.moveToNext()) result += WorkoutExerciseRecord(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3))
        }
        return result
    }

    fun getWorkoutSets(workoutExerciseId: Long): List<WorkoutSetRecord> {
        val result = mutableListOf<WorkoutSetRecord>()
        readableDatabase.rawQuery(
            "SELECT id, workout_exercise_id, set_number, target_reps, percentage, planned_weight, actual_weight, actual_reps, completed FROM workout_sets WHERE workout_exercise_id=? ORDER BY set_number, id",
            arrayOf(workoutExerciseId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result += WorkoutSetRecord(
                    id = c.getLong(0),
                    workoutExerciseId = c.getLong(1),
                    setNumber = c.getInt(2),
                    targetReps = c.getString(3),
                    percentage = if (c.isNull(4)) null else c.getDouble(4),
                    plannedWeight = c.getDouble(5),
                    actualWeight = c.getDouble(6),
                    actualReps = if (c.isNull(7)) null else c.getInt(7),
                    completed = c.getInt(8) == 1
                )
            }
        }
        return result
    }

    fun updateSetWeight(setId: Long, weight: Double) {
        val cv = ContentValues().apply { put("actual_weight", weight) }
        writableDatabase.update("workout_sets", cv, "id=?", arrayOf(setId.toString()))
    }

    fun updateSetReps(setId: Long, reps: Int?) {
        val cv = ContentValues()
        if (reps == null) cv.putNull("actual_reps") else cv.put("actual_reps", reps)
        writableDatabase.update("workout_sets", cv, "id=?", arrayOf(setId.toString()))
    }

    fun updateSetCompleted(setId: Long, completed: Boolean) {
        val cv = ContentValues().apply { put("completed", if (completed) 1 else 0) }
        writableDatabase.update("workout_sets", cv, "id=?", arrayOf(setId.toString()))
    }

    fun completeWorkout(workoutId: Long) {
        val cv = ContentValues().apply {
            put("status", WorkoutSummary.COMPLETED)
            put("completed_at", System.currentTimeMillis())
        }
        writableDatabase.update("workouts", cv, "id=?", arrayOf(workoutId.toString()))
    }

    fun discardWorkout(workoutId: Long) {
        writableDatabase.delete("workouts", "id=?", arrayOf(workoutId.toString()))
    }

    fun getCompletedWorkouts(limit: Int = 100): List<WorkoutSummary> {
        val result = mutableListOf<WorkoutSummary>()
        val sql = """
            SELECT w.id, w.template_name, w.started_at, w.completed_at, w.status,
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN 1 ELSE 0 END),0), COUNT(s.id)
            FROM workouts w
            LEFT JOIN workout_exercises we ON we.workout_id=w.id
            LEFT JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.status=? GROUP BY w.id ORDER BY w.completed_at DESC LIMIT $limit
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(WorkoutSummary.COMPLETED)).use { c ->
            while (c.moveToNext()) result += summaryFromCursor(c)
        }
        return result
    }

    fun workoutCountsByDate(daysBack: Long = 84): Map<LocalDate, Int> {
        val zone = ZoneId.systemDefault()
        val cutoff = System.currentTimeMillis() - daysBack * 24L * 60L * 60L * 1000L
        val result = linkedMapOf<LocalDate, Int>()
        readableDatabase.rawQuery(
            "SELECT completed_at FROM workouts WHERE status=? AND completed_at>=? ORDER BY completed_at",
            arrayOf(WorkoutSummary.COMPLETED, cutoff.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val date = Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate()
                result[date] = (result[date] ?: 0) + 1
            }
        }
        return result
    }

    fun advanceFiveThreeOneWeek(): Pair<Int, Int> {
        var week = getSettingInt("week", 1)
        var cycle = getSettingInt("cycle", 1)
        if (week < 4) {
            week++
        } else {
            week = 1
            cycle++
            writableDatabase.execSQL("UPDATE exercises SET training_max = training_max + increment WHERE mode=?", arrayOf(Exercise.MODE_531))
        }
        setSettingInt("week", week)
        setSettingInt("cycle", cycle)
        return week to cycle
    }

    private fun summaryFromCursor(c: android.database.Cursor): WorkoutSummary = WorkoutSummary(
        id = c.getLong(0),
        templateName = c.getString(1),
        startedAt = c.getLong(2),
        completedAt = if (c.isNull(3)) null else c.getLong(3),
        status = c.getString(4),
        completedSets = c.getInt(5),
        totalSets = c.getInt(6)
    )
}
