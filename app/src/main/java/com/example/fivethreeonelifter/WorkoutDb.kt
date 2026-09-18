package com.example.fivethreeonelifter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WorkoutDb(context: Context) : SQLiteOpenHelper(context, "liftlog.db", null, 2) {

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
        createScheduleTable(db)
        seedWorkoutDays(db)
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('reminder_times', '08:30,13:00,17:30,20:30')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createScheduleTable(db)
            seedWorkoutDays(db)
            db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('reminder_times', '08:30,13:00,17:30,20:30')")
        }
    }

    private fun createScheduleTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS workout_schedule (
                day_of_week INTEGER PRIMARY KEY,
                template_id INTEGER,
                FOREIGN KEY(template_id) REFERENCES templates(id) ON DELETE SET NULL
            )
        """.trimIndent())
    }

    private fun seedWorkoutDays(db: SQLiteDatabase) {
        listOf(3, 5, 6, 7).forEach { day ->
            db.execSQL("INSERT OR IGNORE INTO workout_schedule(day_of_week, template_id) VALUES(?, NULL)", arrayOf(day))
        }
    }

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

    fun getSettingString(key: String, fallback: String): String {
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: fallback else fallback
        }
    }

    fun setSettingString(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getWorkoutSchedule(): List<ScheduledWorkoutDay> {
        val result = mutableListOf<ScheduledWorkoutDay>()
        val sql = """
            SELECT s.day_of_week, s.template_id, t.name
            FROM workout_schedule s
            LEFT JOIN templates t ON t.id=s.template_id
            WHERE s.day_of_week IN (3,5,6,7)
            ORDER BY CASE s.day_of_week WHEN 3 THEN 1 WHEN 5 THEN 2 WHEN 6 THEN 3 WHEN 7 THEN 4 ELSE 5 END
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                result += ScheduledWorkoutDay(
                    dayOfWeek = c.getInt(0),
                    templateId = if (c.isNull(1)) null else c.getLong(1),
                    templateName = if (c.isNull(2)) null else c.getString(2)
                )
            }
        }
        return result
    }

    fun setScheduledTemplate(dayOfWeek: Int, templateId: Long?) {
        val cv = ContentValues().apply {
            put("day_of_week", dayOfWeek)
            if (templateId == null) putNull("template_id") else put("template_id", templateId)
        }
        writableDatabase.insertWithOnConflict("workout_schedule", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getScheduledDay(dayOfWeek: Int): ScheduledWorkoutDay? {
        val sql = """
            SELECT s.day_of_week, s.template_id, t.name
            FROM workout_schedule s LEFT JOIN templates t ON t.id=s.template_id
            WHERE s.day_of_week=? LIMIT 1
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(dayOfWeek.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return ScheduledWorkoutDay(
                c.getInt(0),
                if (c.isNull(1)) null else c.getLong(1),
                if (c.isNull(2)) null else c.getString(2)
            )
        }
    }

    fun hasCompletedTemplateBetween(templateId: Long, startMs: Long, endMs: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM workouts WHERE status=? AND template_id=? AND completed_at>=? AND completed_at<?",
            arrayOf(WorkoutSummary.COMPLETED, templateId.toString(), startMs.toString(), endMs.toString())
        ).use { c ->
            c.moveToFirst()
            return c.getInt(0) > 0
        }
    }

    fun hasActiveWorkoutForTemplate(templateId: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM workouts WHERE status=? AND template_id=?",
            arrayOf(WorkoutSummary.ACTIVE, templateId.toString())
        ).use { c ->
            c.moveToFirst()
            return c.getInt(0) > 0
        }
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

    fun findExerciseByName(name: String): Exercise? {
        val sql = "SELECT * FROM exercises WHERE name = ? COLLATE NOCASE LIMIT 1"
        readableDatabase.rawQuery(sql, arrayOf(name.trim())).use { c ->
            if (!c.moveToFirst()) return null
            return Exercise(
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

    fun ensureExercise(exercise: Exercise): Long {
        val existing = findExerciseByName(exercise.name)
        return existing?.id ?: saveExercise(exercise)
    }

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
            "SELECT id, exercise_name, mode, sort_order, exercise_id FROM workout_exercises WHERE workout_id=? ORDER BY sort_order, id",
            arrayOf(workoutId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result += WorkoutExerciseRecord(
                    id = c.getLong(0),
                    exerciseName = c.getString(1),
                    mode = c.getString(2),
                    sortOrder = c.getInt(3),
                    exerciseId = if (c.isNull(4)) null else c.getLong(4)
                )
            }
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


    fun getWorkoutStats(workoutId: Long): WorkoutStats {
        val summary = getWorkout(workoutId) ?: return WorkoutStats(0, 0, 0, 0, 0.0, 0.0, 0L)
        var completedSets = 0
        var totalSets = 0
        var totalReps = 0
        var totalVolume = 0.0
        var topWeight = 0.0
        val exercises = getWorkoutExercises(workoutId)
        exercises.forEach { exercise ->
            getWorkoutSets(exercise.id).forEach { set ->
                totalSets++
                if (set.completed) {
                    completedSets++
                    val reps = set.actualReps ?: 0
                    totalReps += reps
                    totalVolume += set.actualWeight * reps
                    if (reps > 0 && set.actualWeight > topWeight) topWeight = set.actualWeight
                }
            }
        }
        val end = summary.completedAt ?: System.currentTimeMillis()
        val duration = ((end - summary.startedAt) / 1000L).coerceAtLeast(0L)
        val prCount = getWorkoutPrSetIds(workoutId).values.sumOf { it.size }
        return WorkoutStats(exercises.size, completedSets, totalSets, totalReps, totalVolume, topWeight, duration, prCount)
    }

    fun getExerciseWorkoutStats(workoutId: Long): List<ExerciseWorkoutStats> =
        getWorkoutExercises(workoutId).map { exercise ->
            val sets = getWorkoutSets(exercise.id)
            val completed = sets.filter { it.completed }
            val reps = completed.sumOf { it.actualReps ?: 0 }
            val volume = completed.sumOf { it.actualWeight * (it.actualReps ?: 0) }
            val topWeight = completed.filter { (it.actualReps ?: 0) > 0 }.maxOfOrNull { it.actualWeight } ?: 0.0
            val bestE1rm = completed.maxOfOrNull { ProgramMath.estimatedOneRepMax(it.actualWeight, it.actualReps ?: 0) } ?: 0.0
            ExerciseWorkoutStats(exercise.id, exercise.exerciseName, completed.size, sets.size, reps, volume, topWeight, bestE1rm)
        }

    fun getHistoryOverview(): HistoryOverview {
        var totalWorkouts = 0
        var totalSets = 0
        var totalReps = 0
        var totalVolume = 0.0
        var avgDuration = 0L
        val sql = """
            SELECT COUNT(DISTINCT w.id),
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN 1 ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN COALESCE(s.actual_reps,0) ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN s.actual_weight * COALESCE(s.actual_reps,0) ELSE 0 END),0),
                   0
            FROM workouts w
            LEFT JOIN workout_exercises we ON we.workout_id=w.id
            LEFT JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.status=?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(WorkoutSummary.COMPLETED)).use { c ->
            if (c.moveToFirst()) {
                totalWorkouts = c.getInt(0)
                totalSets = c.getInt(1)
                totalReps = c.getInt(2)
                totalVolume = c.getDouble(3)
                avgDuration = c.getDouble(4).toLong()
            }
        }
        readableDatabase.rawQuery(
            "SELECT COALESCE(AVG((completed_at - started_at) / 1000.0),0) FROM workouts WHERE status=? AND completed_at IS NOT NULL",
            arrayOf(WorkoutSummary.COMPLETED)
        ).use { c -> if (c.moveToFirst()) avgDuration = c.getDouble(0).toLong() }

        val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        var recent = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM workouts WHERE status=? AND completed_at>=?",
            arrayOf(WorkoutSummary.COMPLETED, cutoff.toString())
        ).use { c -> if (c.moveToFirst()) recent = c.getInt(0) }
        return HistoryOverview(totalWorkouts, recent, totalSets, totalReps, totalVolume, avgDuration)
    }

    fun getPersonalRecords(): List<ExercisePersonalRecords> {
        data class Holder(
            var exerciseId: Long?,
            var name: String,
            var heavyWeight: Double = 0.0,
            var heavyReps: Int = 0,
            var heavyAt: Long = 0L,
            var e1rm: Double = 0.0,
            var e1rmWeight: Double = 0.0,
            var e1rmReps: Int = 0,
            var e1rmAt: Long = 0L
        )

        val map = linkedMapOf<String, Holder>()
        val sql = """
            SELECT we.exercise_id, we.exercise_name, s.actual_weight, s.actual_reps, w.completed_at
            FROM workouts w
            JOIN workout_exercises we ON we.workout_id=w.id
            JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.status=? AND s.completed=1 AND s.actual_reps IS NOT NULL AND s.actual_reps>0
            ORDER BY w.completed_at ASC, w.id ASC, we.sort_order ASC, s.set_number ASC
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(WorkoutSummary.COMPLETED)).use { c ->
            while (c.moveToNext()) {
                val exerciseId = if (c.isNull(0)) null else c.getLong(0)
                val name = c.getString(1)
                val weight = c.getDouble(2)
                val reps = c.getInt(3)
                val at = c.getLong(4)
                val key = exerciseId?.let { "id:$it" } ?: "name:${name.lowercase()}"
                val holder = map.getOrPut(key) { Holder(exerciseId, name) }
                holder.name = name
                val e1rm = ProgramMath.estimatedOneRepMax(weight, reps)
                if (weight > holder.heavyWeight) {
                    holder.heavyWeight = weight
                    holder.heavyReps = reps
                    holder.heavyAt = at
                }
                if (e1rm > holder.e1rm) {
                    holder.e1rm = e1rm
                    holder.e1rmWeight = weight
                    holder.e1rmReps = reps
                    holder.e1rmAt = at
                }
            }
        }
        return map.values.map { h ->
            ExercisePersonalRecords(
                h.exerciseId, h.name,
                h.heavyWeight, h.heavyReps, h.heavyAt,
                h.e1rm, h.e1rmWeight, h.e1rmReps, h.e1rmAt
            )
        }.sortedBy { it.exerciseName.lowercase() }
    }

    /**
     * Returns set IDs mapped to PR badges earned in this workout. A PR is only
     * awarded when the best completed set in this workout beats all completed
     * workouts before it for the same exercise.
     */
    fun getWorkoutPrSetIds(workoutId: Long): Map<Long, Set<String>> {
        val workout = getWorkout(workoutId) ?: return emptyMap()
        if (workout.status != WorkoutSummary.COMPLETED || workout.completedAt == null) return emptyMap()
        val result = linkedMapOf<Long, MutableSet<String>>()

        getWorkoutExercises(workoutId).forEach { exercise ->
            val currentSets = getWorkoutSets(exercise.id).filter { it.completed && (it.actualReps ?: 0) > 0 }
            if (currentSets.isEmpty()) return@forEach
            val previous = previousBests(exercise.exerciseId, exercise.exerciseName, workout.completedAt, workoutId)

            val heavySet = currentSets.maxByOrNull { it.actualWeight }
            if (heavySet != null && heavySet.actualWeight > previous.first + 0.0001) {
                result.getOrPut(heavySet.id) { linkedSetOf() }.add("WEIGHT PR")
            }

            val e1rmSet = currentSets.maxByOrNull { ProgramMath.estimatedOneRepMax(it.actualWeight, it.actualReps ?: 0) }
            if (e1rmSet != null) {
                val currentE1rm = ProgramMath.estimatedOneRepMax(e1rmSet.actualWeight, e1rmSet.actualReps ?: 0)
                if (currentE1rm > previous.second + 0.0001) {
                    result.getOrPut(e1rmSet.id) { linkedSetOf() }.add("e1RM PR")
                }
            }
        }
        return result
    }

    private fun previousBests(exerciseId: Long?, exerciseName: String, completedAt: Long, workoutId: Long): Pair<Double, Double> {
        var bestWeight = 0.0
        var bestE1rm = 0.0
        val identityClause = if (exerciseId != null) "we.exercise_id=?" else "LOWER(we.exercise_name)=LOWER(?)"
        val identityValue = exerciseId?.toString() ?: exerciseName
        val sql = """
            SELECT s.actual_weight, s.actual_reps
            FROM workouts w
            JOIN workout_exercises we ON we.workout_id=w.id
            JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.status=? AND s.completed=1 AND s.actual_reps IS NOT NULL AND s.actual_reps>0
              AND (w.completed_at < ? OR (w.completed_at = ? AND w.id < ?))
              AND $identityClause
        """.trimIndent()
        readableDatabase.rawQuery(
            sql,
            arrayOf(WorkoutSummary.COMPLETED, completedAt.toString(), completedAt.toString(), workoutId.toString(), identityValue)
        ).use { c ->
            while (c.moveToNext()) {
                val weight = c.getDouble(0)
                val reps = c.getInt(1)
                if (weight > bestWeight) bestWeight = weight
                val e1rm = ProgramMath.estimatedOneRepMax(weight, reps)
                if (e1rm > bestE1rm) bestE1rm = e1rm
            }
        }
        return bestWeight to bestE1rm
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
