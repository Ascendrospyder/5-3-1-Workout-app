package com.example.fivethreeonelifter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import android.util.JsonWriter
import java.io.Writer
import java.io.Reader
import java.io.File
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WorkoutDb(context: Context) : SQLiteOpenHelper(context, "liftlog.db", null, 3) {

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
        addEditingSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createScheduleTable(db)
            seedWorkoutDays(db)
            db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('reminder_times', '08:30,13:00,17:30,20:30')")
        }
        if (oldVersion < 3) addEditingSchema(db)
    }

    private fun addEditingSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE exercises ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE templates ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE workouts ADD COLUMN workout_week INTEGER")
        db.execSQL("ALTER TABLE workout_exercises ADD COLUMN removed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE template_sets (
                template_id INTEGER NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
                exercise_id INTEGER NOT NULL REFERENCES exercises(id) ON DELETE CASCADE,
                set_number INTEGER NOT NULL,
                target_reps TEXT NOT NULL,
                planned_weight REAL NOT NULL,
                actual_reps INTEGER,
                PRIMARY KEY(template_id, exercise_id, set_number)
            )
        """.trimIndent())
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

    /** Export a consistent snapshot, including active workouts and all saved settings. */
    fun writeExport(output: Writer) {
        val database = readableDatabase
        database.beginTransactionNonExclusive()
        try {
            val tables = mutableListOf<String>()
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' ORDER BY name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            JsonWriter(output).use { json ->
                json.setIndent("  ")
                json.beginObject()
                json.name("format").value("liftlog-export")
                json.name("format_version").value(1L)
                json.name("database_version").value(database.version.toLong())
                json.name("exported_at").value(Instant.now().toString())
                json.name("tables").beginObject()
                tables.forEach { table ->
                    json.name(table).beginArray()
                    val quotedTable = "\"${table.replace("\"", "\"\"")}\""
                    database.rawQuery("SELECT * FROM $quotedTable", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            json.beginObject()
                            cursor.columnNames.forEachIndexed { index, column ->
                                json.name(column)
                                when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> json.nullValue()
                                    Cursor.FIELD_TYPE_INTEGER -> json.value(cursor.getLong(index))
                                    Cursor.FIELD_TYPE_FLOAT -> json.value(cursor.getDouble(index))
                                    Cursor.FIELD_TYPE_BLOB -> {
                                        json.beginObject()
                                        json.name("base64").value(Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                                        json.endObject()
                                    }
                                    else -> json.value(cursor.getString(index))
                                }
                            }
                            json.endObject()
                        }
                    }
                    json.endArray()
                }
                json.endObject()
                json.endObject()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Replace data atomically. A failed import leaves the original database intact. */
    fun restoreExport(input: Reader, safetyBackup: File) {
        val content = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(content.length + count <= 32 * 1024 * 1024) { "Export is too large (32 MB text limit)" }
            content.append(buffer, 0, count)
        }
        val backup = JSONObject(content.toString())
        require(backup.optString("format") == "liftlog-export" && backup.optInt("format_version") == 1) { "Choose a LiftLog JSON export" }
        require(backup.getInt("database_version") in 2..3) { "This export needs a different LiftLog version" }
        val tables = backup.getJSONObject("tables")
        require(backup.getInt("database_version") < 3 || tables.has("template_sets")) { "Export is missing template set defaults" }
        val order = listOf("exercises", "templates", "template_exercises", "workouts", "workout_exercises", "workout_sets", "settings", "workout_schedule", "template_sets")
        require(order.dropLast(1).all { tables.has(it) }) { "Export is missing required tables" }
        require(tables.keys().asSequence().all { it in order }) { "Export contains unsupported tables" }
        val database = writableDatabase
        // Validate column names and SQLite types before touching existing rows.
        val rows = order.associateWith { table ->
            val schema = mutableMapOf<String, String>()
            val keys = mutableSetOf<String>()
            database.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) {
                    schema[c.getString(1)] = c.getString(2)
                    if (c.getInt(5) > 0) keys += c.getString(1)
                }
            }
            val array = if (tables.has(table)) tables.getJSONArray(table) else org.json.JSONArray()
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                require(keys.all { row.has(it) && !row.isNull(it) }) { "Missing ID/key in $table" }
                require(keys.all { key ->
                    val value = row.get(key)
                    if (schema[key] == "INTEGER") value is Number && value.toLong() > 0 else value is String && value.isNotBlank()
                }) { "Invalid ID/key in $table" }
                ContentValues().apply {
                    row.keys().forEach { column ->
                        val type = requireNotNull(schema[column]) { "Unknown column: $table.$column" }
                        val value = row.get(column)
                        when {
                            value == JSONObject.NULL -> putNull(column)
                            type == "INTEGER" && value is Number -> {
                                require(value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "Invalid integer: $table.$column" }
                                put(column, value.toLong())
                            }
                            type == "REAL" && value is Number -> {
                                require(value.toDouble().isFinite()) { "Invalid number: $table.$column" }
                                put(column, value.toDouble())
                            }
                            type == "TEXT" && value is String -> put(column, value)
                            else -> error("Invalid value: $table.$column")
                        }
                    }
                }
            }
        }
        database.beginTransaction()
        try {
            val temporaryBackup = File(safetyBackup.parentFile, "${safetyBackup.name}.tmp")
            try {
                temporaryBackup.bufferedWriter(Charsets.UTF_8).use { writeExport(it) }
                require(temporaryBackup.renameTo(safetyBackup)) { "Could not save the pre-restore copy" }
            } finally { temporaryBackup.delete() }
            order.reversed().forEach { database.delete(it, null, null) }
            // Preserve imported IDs; SQLite updates AUTOINCREMENT counters on insertion.
            database.delete("sqlite_sequence", null, null)
            order.forEach { table -> rows.getValue(table).forEach { database.insertOrThrow(table, null, it) } }
            database.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Export has broken relationships" } }
            fun hasRows(sql: String) = database.rawQuery(sql, null).use { it.moveToFirst() }
            require(!hasRows("SELECT id FROM workouts WHERE status NOT IN ('ACTIVE','COMPLETED','DISCARDED') OR started_at<0 OR (status='COMPLETED' AND (completed_at IS NULL OR completed_at<started_at))")) { "Invalid workout dates or status" }
            require(!hasRows("SELECT id FROM workout_sets WHERE actual_weight<0 OR planned_weight<0 OR actual_reps<0 OR set_number<1 OR completed NOT IN (0,1) OR LENGTH(TRIM(target_reps))=0 OR (percentage IS NOT NULL AND (percentage<=0 OR percentage>1))")) { "Invalid workout sets" }
            require(!hasRows("SELECT workout_exercise_id FROM workout_sets GROUP BY workout_exercise_id,set_number HAVING COUNT(*)>1")) { "Duplicate set numbers" }
            require(!hasRows("SELECT id FROM exercises WHERE mode NOT IN ('MANUAL','531') OR default_weight<0 OR default_sets<1 OR default_sets>100 OR rep_min<1 OR rep_max<rep_min OR training_max<0 OR round_to<=0 OR increment<=0 OR deleted NOT IN (0,1)")) { "Invalid exercise settings" }
            require(!hasRows("SELECT id FROM templates WHERE deleted NOT IN (0,1) OR LENGTH(TRIM(name))=0")) { "Invalid templates" }
            require(!hasRows("SELECT id FROM workout_exercises WHERE removed NOT IN (0,1) OR mode NOT IN ('MANUAL','531')")) { "Invalid workout exercises" }
            require(!hasRows("SELECT id FROM workouts WHERE workout_week IS NOT NULL AND workout_week NOT BETWEEN 1 AND 4")) { "Invalid workout week" }
            require(!hasRows("SELECT day_of_week FROM workout_schedule WHERE day_of_week NOT BETWEEN 1 AND 7")) { "Invalid schedule day" }
            require(!hasRows("SELECT template_id FROM template_sets WHERE set_number<1 OR planned_weight<0 OR actual_reps<0 OR LENGTH(TRIM(target_reps))=0")) { "Invalid template sets" }
            require(getSettingInt("week", 0) in 1..4 && getSettingInt("cycle", 0) >= 1) { "Invalid 5/3/1 settings" }
            database.rawQuery("SELECT COUNT(*) FROM workouts WHERE status='ACTIVE'", null).use { it.moveToFirst(); require(it.getInt(0) <= 1) { "Export has multiple active workouts" } }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
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
            LEFT JOIN templates t ON t.id=s.template_id AND t.deleted=0
            WHERE s.day_of_week IN (3,5,6,7)
            ORDER BY CASE s.day_of_week WHEN 3 THEN 1 WHEN 5 THEN 2 WHEN 6 THEN 3 WHEN 7 THEN 4 ELSE 5 END
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                result += ScheduledWorkoutDay(
                    dayOfWeek = c.getInt(0),
                    templateId = if (c.isNull(1) || c.isNull(2)) null else c.getLong(1),
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
            FROM workout_schedule s LEFT JOIN templates t ON t.id=s.template_id AND t.deleted=0
            WHERE s.day_of_week=? LIMIT 1
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(dayOfWeek.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return ScheduledWorkoutDay(
                c.getInt(0),
                if (c.isNull(1) || c.isNull(2)) null else c.getLong(1),
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

    fun getExercises(deleted: Boolean = false): List<Exercise> {
        val result = mutableListOf<Exercise>()
        readableDatabase.rawQuery("SELECT * FROM exercises WHERE deleted=? ORDER BY name COLLATE NOCASE", arrayOf(if (deleted) "1" else "0")).use { c ->
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
        val sql = "SELECT * FROM exercises WHERE deleted=0 AND name = ? COLLATE NOCASE LIMIT 1"
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
        writableDatabase.execSQL("UPDATE exercises SET deleted=1 WHERE id=?", arrayOf(id))
    }

    fun restoreExercise(id: Long) = writableDatabase.execSQL("UPDATE exercises SET deleted=0 WHERE id=?", arrayOf(id))

    fun getTemplates(deleted: Boolean = false): List<WorkoutTemplate> {
        val result = mutableListOf<WorkoutTemplate>()
        readableDatabase.rawQuery("SELECT id, name FROM templates WHERE deleted=? ORDER BY name COLLATE NOCASE", arrayOf(if (deleted) "1" else "0")).use { c ->
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
        writableDatabase.execSQL("UPDATE templates SET deleted=1 WHERE id=?", arrayOf(id))
    }

    fun restoreTemplate(id: Long) = writableDatabase.execSQL("UPDATE templates SET deleted=0 WHERE id=?", arrayOf(id))

    fun getTemplateExercises(templateId: Long): List<Exercise> {
        val result = mutableListOf<Exercise>()
        val sql = """
            SELECT e.* FROM template_exercises te
            JOIN exercises e ON e.id = te.exercise_id
            WHERE te.template_id=? AND e.deleted=0 ORDER BY te.sort_order, te.id
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
            LEFT JOIN workout_exercises we ON we.workout_id=w.id AND we.removed=0
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
                put("workout_week", week)
            })

            exercises.forEachIndexed { exerciseIndex, exercise ->
                val workoutExerciseId = db.insertOrThrow("workout_exercises", null, ContentValues().apply {
                    put("workout_id", workoutId)
                    put("exercise_id", exercise.id)
                    put("exercise_name", exercise.name)
                    put("mode", exercise.mode)
                    put("sort_order", exerciseIndex)
                })

                val previous = lastCompletedExerciseSets(exercise)
                defaultsForTemplate(exercise, template.id, week, previous).forEachIndexed { setIndex, defaults ->
                    db.insertOrThrow("workout_sets", null, ContentValues().apply {
                        put("workout_exercise_id", workoutExerciseId)
                        put("set_number", setIndex + 1)
                        put("target_reps", defaults.targetReps)
                        if (defaults.percentage == null) putNull("percentage") else put("percentage", defaults.percentage)
                        put("planned_weight", defaults.plannedWeight)
                        put("actual_weight", defaults.weight)
                        if (defaults.reps == null) putNull("actual_reps") else put("actual_reps", defaults.reps)
                        put("completed", 0)
                    })
                }
            }
            db.setTransactionSuccessful()
            return workoutId
        } finally {
            db.endTransaction()
        }
    }

    private fun lastCompletedExerciseSets(exercise: Exercise): List<WorkoutSetRecord> {
        val sql = """
            SELECT we.id FROM workout_exercises we
            JOIN workouts w ON w.id = we.workout_id
            WHERE we.exercise_id = ? AND we.mode = ? AND w.status = ? AND we.removed=0
            ORDER BY w.completed_at DESC, w.id DESC, we.id DESC LIMIT 1
        """.trimIndent()
        val previousId = readableDatabase.rawQuery(
            sql, arrayOf(exercise.id.toString(), exercise.mode, WorkoutSummary.COMPLETED)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        return previousId?.let { getWorkoutSets(it) } ?: emptyList()
    }

    fun getWorkout(id: Long): WorkoutSummary? {
        val sql = """
            SELECT w.id, w.template_name, w.started_at, w.completed_at, w.status,
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN 1 ELSE 0 END),0), COUNT(s.id)
            FROM workouts w
            LEFT JOIN workout_exercises we ON we.workout_id=w.id AND we.removed=0
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
            "SELECT id, exercise_name, mode, sort_order, exercise_id FROM workout_exercises WHERE workout_id=? AND removed=0 ORDER BY sort_order, id",
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
        writableDatabase.execSQL("UPDATE workouts SET status='DISCARDED' WHERE id=?", arrayOf(workoutId))
    }

    fun undoDiscardWorkout(workoutId: Long) {
        require(getActiveWorkout() == null) { "A workout is already active" }
        writableDatabase.execSQL("UPDATE workouts SET status='ACTIVE' WHERE id=? AND status='DISCARDED'", arrayOf(workoutId))
    }

    fun getDiscardedWorkouts(): List<WorkoutSummary> = readableDatabase.rawQuery(
        "SELECT id FROM workouts WHERE status='DISCARDED' ORDER BY started_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) getWorkout(c.getLong(0))?.let { add(it) } } }

    fun getCompletedWorkouts(limit: Int = 100): List<WorkoutSummary> {
        val result = mutableListOf<WorkoutSummary>()
        val sql = """
            SELECT w.id, w.template_name, w.started_at, w.completed_at, w.status,
                   COALESCE(SUM(CASE WHEN s.completed=1 THEN 1 ELSE 0 END),0), COUNT(s.id)
            FROM workouts w
            LEFT JOIN workout_exercises we ON we.workout_id=w.id AND we.removed=0
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
            LEFT JOIN workout_exercises we ON we.workout_id=w.id AND we.removed=0
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
            JOIN workout_exercises we ON we.workout_id=w.id AND we.removed=0
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
            JOIN workout_exercises we ON we.workout_id=w.id AND we.removed=0
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

    private fun defaultsForTemplate(exercise: Exercise, templateId: Long?, week: Int, previous: List<WorkoutSetRecord>): List<WorkoutDefaults.SetDefaults> {
        val saved = mutableListOf<WorkoutDefaults.SetDefaults>()
        if (templateId != null) readableDatabase.rawQuery(
            "SELECT target_reps, planned_weight, actual_reps FROM template_sets WHERE template_id=? AND exercise_id=? ORDER BY set_number",
            arrayOf(templateId.toString(), exercise.id.toString())
        ).use { c ->
            while (c.moveToNext()) saved += WorkoutDefaults.SetDefaults(c.getString(0), null, c.getDouble(1), c.getDouble(1), if (c.isNull(2)) null else c.getInt(2))
        }
        return WorkoutDefaults.sets(exercise, week, previous, saved)
    }

    fun workoutTemplateId(workoutId: Long): Long? = readableDatabase.rawQuery(
        "SELECT template_id FROM workouts WHERE id=?", arrayOf(workoutId.toString())
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    private fun workoutWeek(workoutId: Long): Int {
        readableDatabase.rawQuery("SELECT workout_week FROM workouts WHERE id=?", arrayOf(workoutId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0)
        }
        // Old APKs did not store the week; infer it from the snapshot's first 5/3/1 set.
        val first = getWorkoutExercises(workoutId).firstOrNull { it.mode == Exercise.MODE_531 }
            ?.let { getWorkoutSets(it.id).firstOrNull()?.percentage }
        return (1..4).firstOrNull { ProgramMath.weekSets(it)[0].percentage == first } ?: getSettingInt("week", 1)
    }

    fun previousSessionSets(exerciseId: Long?, name: String, workoutId: Long): List<WorkoutSetRecord> {
        val current = getWorkout(workoutId) ?: return emptyList()
        val cutoff = current.completedAt ?: current.startedAt
        val identity = if (exerciseId == null) "we.exercise_name = ? COLLATE NOCASE" else "we.exercise_id = ?"
        val sql = """
            SELECT we.id FROM workout_exercises we JOIN workouts w ON w.id=we.workout_id
            WHERE $identity AND we.removed=0 AND w.status=? AND w.id<>?
              AND (w.completed_at<? OR (w.completed_at=? AND w.id<?))
            ORDER BY w.completed_at DESC, w.id DESC, we.id DESC LIMIT 1
        """.trimIndent()
        val id = readableDatabase.rawQuery(sql, arrayOf(exerciseId?.toString() ?: name,
            WorkoutSummary.COMPLETED, workoutId.toString(), cutoff.toString(), cutoff.toString(), workoutId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        return id?.let { getWorkoutSets(it) } ?: emptyList()
    }

    fun renameWorkout(id: Long, name: String) {
        require(name.isNotBlank())
        writableDatabase.execSQL("UPDATE workouts SET template_name=? WHERE id=?", arrayOf(name.trim(), id))
    }

    fun addWorkoutExercise(workoutId: Long, exerciseId: Long): Long {
        val exercise = requireNotNull(getExercise(exerciseId))
        val database = writableDatabase
        database.beginTransaction()
        try {
            require(getWorkout(workoutId) != null)
            require(getWorkoutExercises(workoutId).none { it.exerciseId == exerciseId }) { "Exercise is already in this workout" }
            val id = database.insertOrThrow("workout_exercises", null, ContentValues().apply {
                put("workout_id", workoutId); put("exercise_id", exerciseId); put("exercise_name", exercise.name)
                put("mode", exercise.mode); put("sort_order", getWorkoutExercises(workoutId).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0)
            })
            val defaults = defaultsForTemplate(exercise, workoutTemplateId(workoutId), workoutWeek(workoutId), lastCompletedExerciseSets(exercise))
            replaceWorkoutSets(id, defaults.mapIndexed { i, s -> WorkoutSetRecord(0, id, i + 1, s.targetReps, s.percentage, s.plannedWeight, s.weight, s.reps, false) })
            database.setTransactionSuccessful()
            return id
        } finally { database.endTransaction() }
    }

    fun replaceWorkoutSets(exerciseRecordId: Long, sets: List<WorkoutSetRecord>) {
        require(sets.isNotEmpty() && sets.size <= 100) { "Use between 1 and 100 sets" }
        require(sets.all { it.actualWeight.isFinite() && it.actualWeight >= 0 && (it.actualReps == null || it.actualReps >= 0) && it.targetReps.isNotBlank() })
        val originalIds = getWorkoutSets(exerciseRecordId).map { it.id }.toSet()
        val retainedIds = sets.filter { it.id != 0L }.map { it.id }
        require(retainedIds.all { it in originalIds } && retainedIds.distinct().size == retainedIds.size)
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("workout_sets", "workout_exercise_id=?", arrayOf(exerciseRecordId.toString()))
            sets.forEachIndexed { i, set ->
                database.insertOrThrow("workout_sets", null, ContentValues().apply {
                    if (set.id != 0L) put("id", set.id)
                    put("workout_exercise_id", exerciseRecordId); put("set_number", i + 1)
                    put("target_reps", set.targetReps); if (set.percentage == null) putNull("percentage") else put("percentage", set.percentage)
                    put("planned_weight", set.plannedWeight); put("actual_weight", set.actualWeight)
                    if (set.actualReps == null) putNull("actual_reps") else put("actual_reps", set.actualReps)
                    put("completed", if (set.completed) 1 else 0)
                })
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    fun removeWorkoutExercise(id: Long) = writableDatabase.execSQL("UPDATE workout_exercises SET removed=1 WHERE id=?", arrayOf(id))
    fun undoRemoveWorkoutExercise(id: Long) = writableDatabase.execSQL("UPDATE workout_exercises SET removed=0 WHERE id=?", arrayOf(id))

    fun moveWorkoutExercise(workoutId: Long, id: Long, direction: Int) {
        val ordered = getWorkoutExercises(workoutId).toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in ordered.indices) return
        java.util.Collections.swap(ordered, index, target)
        val database = writableDatabase
        database.beginTransaction()
        try {
            ordered.forEachIndexed { i, item -> database.execSQL("UPDATE workout_exercises SET sort_order=? WHERE id=?", arrayOf(i, item.id)) }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    /** Save this session's structure and manual per-set defaults without changing other templates. */
    fun updateTemplateFromWorkout(workoutId: Long): Long {
        val workout = requireNotNull(getWorkout(workoutId))
        val records = getWorkoutExercises(workoutId)
        require(records.isNotEmpty()) { "An empty workout cannot become a template" }
        val database = writableDatabase
        database.beginTransaction()
        try {
            val origin = workoutTemplateId(workoutId)
            val templateId = getTemplates().firstOrNull { it.id == origin }?.id ?: createTemplate(workout.templateName)
            renameTemplate(templateId, workout.templateName)
            database.delete("template_exercises", "template_id=?", arrayOf(templateId.toString()))
            database.delete("template_sets", "template_id=?", arrayOf(templateId.toString()))
            records.forEach { record ->
                val existing = record.exerciseId?.let { getExercise(it) }
                val old = existing ?: getExercises(true).firstOrNull { it.id == record.exerciseId }
                val snapshotSets = getWorkoutSets(record.id)
                val inferredMax = snapshotSets.firstOrNull { (it.percentage ?: 0.0) > 0.0 }
                    ?.let { it.plannedWeight / requireNotNull(it.percentage) } ?: 0.0
                val exerciseId = existing?.takeIf { it.mode == record.mode }?.id ?: saveExercise(
                    (old ?: Exercise(name = record.exerciseName, mode = record.mode)).copy(
                        id = 0, name = record.exerciseName, mode = record.mode,
                        trainingMax = old?.takeIf { it.mode == record.mode }?.trainingMax ?: inferredMax
                    )
                )
                addExerciseToTemplate(templateId, exerciseId)
                snapshotSets.forEach { set ->
                    database.insertOrThrow("template_sets", null, ContentValues().apply {
                        put("template_id", templateId); put("exercise_id", exerciseId); put("set_number", set.setNumber)
                        put("target_reps", set.targetReps); put("planned_weight", set.actualWeight)
                        if (set.actualReps == null) putNull("actual_reps") else put("actual_reps", set.actualReps)
                    })
                }
            }
            database.execSQL("UPDATE workouts SET template_id=? WHERE id=?", arrayOf(templateId, workoutId))
            database.setTransactionSuccessful()
            return templateId
        } finally { database.endTransaction() }
    }

    fun getExerciseProgress(exerciseId: Long?, name: String): List<ExerciseProgressPoint> {
        val identity = if (exerciseId == null) "we.exercise_name = ? COLLATE NOCASE" else "we.exercise_id = ?"
        val result = mutableListOf<ExerciseProgressPoint>()
        val sql = """
            SELECT w.id, w.completed_at, MAX(s.actual_weight), MAX(s.actual_reps),
                MAX(CASE WHEN s.actual_reps=1 THEN s.actual_weight ELSE s.actual_weight * (1.0 + s.actual_reps/30.0) END)
            FROM workouts w JOIN workout_exercises we ON we.workout_id=w.id
            JOIN workout_sets s ON s.workout_exercise_id=we.id
            WHERE w.status=? AND we.removed=0 AND s.completed=1 AND s.actual_reps>0 AND $identity
            GROUP BY w.id ORDER BY w.completed_at DESC, w.id DESC LIMIT 100
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(WorkoutSummary.COMPLETED, exerciseId?.toString() ?: name)).use { c ->
            while (c.moveToNext()) result += ExerciseProgressPoint(c.getLong(0), c.getLong(1), c.getDouble(2), c.getInt(3), c.getDouble(4))
        }
        return result.reversed()
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
