package com.example.fivethreeonelifter

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var db: WorkoutDb
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedRunnable: Runnable? = null
    private var restRunnable: Runnable? = null
    private var exportBackup: File? = null
    private var dataDialog: AlertDialog? = null
    private var currentBackAction: (() -> Unit)? = null
    private var restDisplay: TextView? = null
    private var restWorkoutId: Long = 0
    private var elapsedDisplay: TextView? = null
    private var elapsedStartedAt: Long = 0
    private var palette = AppPalette.LIGHT

    private val COLOR_BACKGROUND get() = palette.background
    private val COLOR_SURFACE get() = palette.surface
    private val COLOR_TEXT get() = palette.text
    private val COLOR_MUTED get() = palette.muted
    private val COLOR_BORDER get() = palette.border
    private val COLOR_PRIMARY get() = palette.primary
    private val COLOR_PRIMARY_SOFT get() = palette.primarySoft
    private val COLOR_TEAL_SOFT get() = palette.tealSoft
    private val COLOR_DANGER get() = palette.danger
    private val COLOR_DANGER_SOFT get() = palette.dangerSoft
    private val COLOR_PR get() = palette.pr
    private val COLOR_PR_SOFT get() = palette.prSoft

    override fun onCreate(savedInstanceState: Bundle?) {
        db = WorkoutDb(this)
        val appearance = db.getSettingString("appearance", "system")
        val dark = when (appearance) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        palette = if (dark) AppPalette.DARK else AppPalette.LIGHT
        // Apply the native theme before creating widgets so dialogs, spinners and checkboxes match.
        setTheme(resources.getIdentifier(if (dark) "Theme.FiveThreeOne.Dark" else "Theme.FiveThreeOne.Light", "style", packageName))
        super.onCreate(savedInstanceState)
        exportBackup = savedInstanceState?.getString("export_backup")?.let { File(it) }
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_SURFACE
        if (Build.VERSION.SDK_INT >= 30) {
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // Accessing the decor view creates it; Window.getInsetsController() can
            // crash on Android 11–14 when called before the first setContentView.
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)
        } else {
            @Suppress("DEPRECATION")
            val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (window.decorView.systemUiVisibility and lightBars.inv()) or if (dark) 0 else lightBars
        }
        NotificationScheduler.createChannel(this)
        NotificationScheduler.scheduleAll(this)
        val requestedWorkout = savedInstanceState?.getLong("visible_workout_id") ?: intent.getLongExtra("workout_id", 0)
        if (requestedWorkout > 0 && db.getWorkout(requestedWorkout)?.status == WorkoutSummary.ACTIVE) showActiveWorkout(requestedWorkout)
        else showDashboard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exportBackup?.let { outState.putString("export_backup", it.absolutePath) }
        outState.putLong("visible_workout_id", if (elapsedDisplay != null) restWorkoutId else 0)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        RestTimer.reschedule(this)
        elapsedDisplay?.let { startElapsedTimer(elapsedStartedAt, it) }
        restDisplay?.let { bindRestTimer(it, restWorkoutId) }
    }

    override fun onStop() {
        stopUiTimers()
        super.onStop()
    }

    @Deprecated("Uses the shared screen back action")
    override fun onBackPressed() {
        currentBackAction?.invoke() ?: super.onBackPressed()
    }

    override fun onDestroy() {
        stopUiTimers()
        dataDialog?.dismiss()
        db.close()
        super.onDestroy()
    }

    private fun chooseExportDestination(backup: File? = null) {
        exportBackup = backup
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "liftlog-export-$timestamp.json")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DATA)
        } catch (_: ActivityNotFoundException) {
            toast("No file picker is available to save the export.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RESTORE_DATA && resultCode == RESULT_OK) {
            val source = data?.data ?: return
            confirm("Replace your current data?", "This restores the selected export, replacing all current workouts, exercises, templates and settings. A local copy of the replaced data will be kept so you can undo the restore.") {
                val resolver = applicationContext.contentResolver
                restoreData { resolver.openInputStream(source)?.bufferedReader(Charsets.UTF_8) ?: error("Cannot open the selected file") }
            }
            return
        }
        if (requestCode != REQUEST_EXPORT_DATA || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        val appContext = applicationContext
        val savedBackup = exportBackup
        exportBackup = null
        toast("Exporting your data…")
        Thread({
            var temporaryFile: File? = null
            val message = try {
                val snapshot = File.createTempFile("liftlog-export-", ".json", appContext.cacheDir)
                temporaryFile = snapshot
                if (savedBackup != null) savedBackup.copyTo(snapshot, overwrite = true)
                else {
                    val exportDb = WorkoutDb(appContext)
                    try {
                        snapshot.bufferedWriter(Charsets.UTF_8).use { exportDb.writeExport(it) }
                    } finally { exportDb.close() }
                }
                // Finish the database snapshot before copying to a potentially slow cloud provider.
                val output = appContext.contentResolver.openOutputStream(destination, "wt")
                    ?: throw java.io.IOException("Could not open the export destination")
                output.use { stream -> snapshot.inputStream().use { it.copyTo(stream) } }
                "All data exported successfully."
            } catch (_: Exception) {
                "Export failed. The selected file may be incomplete; please try again."
            } finally {
                temporaryFile?.delete()
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }, "LiftLog-export").start()
    }

    private fun chooseRestoreSource() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            }, REQUEST_RESTORE_DATA)
        } catch (_: ActivityNotFoundException) { toast("No file picker is available.") }
    }

    private fun lastRestoreBackup(): File? = File(filesDir, "restore-backups").listFiles()
        ?.filter { it.extension == "json" }?.maxByOrNull { it.lastModified() }

    private fun restoreData(open: () -> Reader) {
        val appContext = applicationContext
        val previousOrientation = requestedOrientation
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        dataDialog = AlertDialog.Builder(this).setTitle("Restoring data…")
            .setMessage("Validating the export and saving your current data.").setCancelable(false).show()
        Thread({
            val failure = try {
                val folder = File(appContext.filesDir, "restore-backups").apply { mkdirs() }
                val backup = File(folder, "before-restore-${System.currentTimeMillis()}-${System.nanoTime()}.json")
                val restoreDb = WorkoutDb(appContext)
                try { open().use { restoreDb.restoreExport(it, backup) } } finally { restoreDb.close() }
                // The restore has committed. Auxiliary notification failures must not report a rollback.
                runCatching { RestTimer.stop(appContext) }
                runCatching { NotificationScheduler.scheduleAll(appContext) }
                null
            } catch (error: Exception) { error.message ?: "The file could not be restored" }
            Handler(Looper.getMainLooper()).post {
                if (!isDestroyed) {
                    dataDialog?.dismiss()
                    dataDialog = null
                    requestedOrientation = previousOrientation
                    if (failure == null) {
                        toast("Data restored. You can undo this from Home → Your data.")
                        showDashboard()
                        recreate() // Apply the appearance preference from the restored settings.
                    } else AlertDialog.Builder(this).setTitle("Restore failed")
                        .setMessage("$failure. Your existing data has not been replaced.").setPositiveButton("OK", null).show()
                }
            }
        }, "LiftLog-restore").start()
    }

    private fun showDashboard() {
        setScreen("LiftLog", showNav = true)

        val active = db.getActiveWorkout()
        if (active != null) {
            root.addView(card().apply {
                addView(text("Workout in progress", 18f, true))
                addView(text(active.templateName, 16f))
                addView(text("${active.completedSets}/${active.totalSets} sets ticked · started ${formatTime(active.startedAt)}", 14f, false, COLOR_MUTED))
                addView(space(10))
                addView(button("Resume workout") { showActiveWorkout(active.id) })
            })
            root.addView(space(12))
        }

        val week = db.getSettingInt("week", 1)
        val cycle = db.getSettingInt("cycle", 1)
        root.addView(card().apply {
            addView(text("5/3/1 cycle $cycle · week $week", 18f, true))
            addView(text(weekName(week), 14f, false, COLOR_MUTED))
            addView(text("5/3/1 follows this week and each exercise's Training Max. Manual exercises remember your last completed session.", 14f))
            addView(space(8))
            addView(button(if (week == 4) "Finish deload + progress Training Maxes" else "Advance to week ${week + 1}") {
                val (newWeek, newCycle) = db.advanceFiveThreeOneWeek()
                toast(if (newWeek == 1) "Cycle $newCycle started. 5/3/1 Training Max increments applied." else "Moved to week $newWeek.")
                showDashboard()
            })
        })

        root.addView(sectionTitle("Workout schedule"))
        val schedule = db.getWorkoutSchedule()
        val todayDay = LocalDate.now().dayOfWeek.value
        val todayPlan = schedule.firstOrNull { it.dayOfWeek == todayDay && it.templateId != null }
        root.addView(tintedCard(if (todayPlan != null) COLOR_TEAL_SOFT else COLOR_SURFACE).apply {
            if (todayPlan != null) {
                addView(text("Today · ${todayPlan.templateName}", 19f, true))
                addView(text("Your scheduled workout is ready. Reminders stop after you complete it.", 13f, false, COLOR_MUTED))
                addView(space(8))
            } else {
                addView(text("Wednesday · Friday · Saturday · Sunday", 17f, true))
            }
            val summary = schedule.joinToString("  •  ") { item ->
                "${shortDay(item.dayOfWeek)}: ${item.templateName ?: "—"}"
            }
            addView(text(summary, 13f, false, COLOR_MUTED))
            addView(space(8))
            addView(softButton("Manage schedule & reminders") { showSchedule() })
        })

        root.addView(sectionTitle("Start from a template"))
        val templates = db.getTemplates()
        if (templates.isEmpty()) {
            root.addView(infoCard("No templates yet. Add exercises first, then create a template."))
            root.addView(space(8))
            root.addView(button("Create exercises") { showExercises() })
            root.addView(space(6))
            root.addView(button("Create templates") { showTemplates() })
        } else {
            templates.forEach { template ->
                val count = db.getTemplateExercises(template.id).size
                root.addView(button("${template.name} · $count exercises") {
                    if (db.getActiveWorkout() != null) {
                        toast("Finish or discard the active workout first.")
                    } else if (count == 0) {
                        toast("Add at least one exercise to this template.")
                    } else {
                        val workoutId = db.startWorkout(template, week)
                        showActiveWorkout(workoutId)
                    }
                }, matchWrapWithMargin(4))
            }
        }

        root.addView(sectionTitle("Training heatmap"))
        root.addView(card().apply {
            addView(text("Last 12 weeks", 17f, true))
            addView(text("Darker squares mean more completed workouts that day.", 13f, false, COLOR_MUTED))
            addView(HeatmapView(this@MainActivity, palette).apply { counts = db.workoutCountsByDate() })
        })

        root.addView(sectionTitle("Recent workouts"))
        val recent = db.getCompletedWorkouts(3)
        if (recent.isEmpty()) {
            root.addView(infoCard("Completed workouts will appear here with their date, duration, and set history."))
        } else {
            recent.forEach { summary -> addHistorySummary(summary) }
            root.addView(space(6))
            root.addView(button("View all history") { showHistory() })
        }

        root.addView(sectionTitle("Appearance"))
        val appearance = db.getSettingString("appearance", "system")
        val currentAppearance = when (appearance) { "dark" -> "Dark"; "light" -> "Light"; else -> "Follow phone setting" }
        root.addView(softButton("Theme: $currentAppearance") {
            val choices = listOf("system", "light", "dark")
            AlertDialog.Builder(this).setTitle("Appearance")
                .setSingleChoiceItems(arrayOf("Follow phone setting", "Light", "Dark"), choices.indexOf(appearance).coerceAtLeast(0)) { dialog, index ->
                    if (choices[index] != appearance) {
                        db.setSettingString("appearance", choices[index])
                        dialog.dismiss()
                        recreate()
                    } else dialog.dismiss()
                }.setNegativeButton("Cancel", null).show()
        })

        root.addView(sectionTitle("Your data"))
        root.addView(card().apply {
            addView(text("Export all data", 18f, true))
            addView(text("Save all exercises, templates, workouts (including one in progress), sets, settings and reminders as a JSON file. Choose your phone storage or an available cloud drive.", 14f))
            addView(space(8))
            addView(softButton("Export all data") { chooseExportDestination() })
            addView(space(6))
            addView(softButton("Restore from export") { chooseRestoreSource() })
            addView(space(6))
            addView(softButton("Recently deleted") { showTrash() })
            lastRestoreBackup()?.let { backup ->
                addView(space(6))
                addView(softButton("Undo last restore") {
                    confirm("Restore the previous data?", "Your current data will be replaced by the saved pre-restore copy. A copy of the current data will also be kept.") {
                        restoreData { backup.bufferedReader(Charsets.UTF_8) }
                    }
                })
                addView(softButton("Export pre-restore copy") { chooseExportDestination(backup) })
            }
        })
    }

    private fun showExercises() {
        setScreen("Exercises", showNav = true)
        root.addView(button("+ Create custom exercise") { showExerciseEditor(null) })
        root.addView(infoCard("Popular Chest, Back, Legs, Arms and Shoulders exercises are available as quick-add presets while you build a workout template. Selecting one automatically adds it to this library too."))
        root.addView(sectionTitle("Your exercise library"))

        val exercises = db.getExercises()
        if (exercises.isEmpty()) {
            root.addView(infoCard("Add any lift or accessory you want. Each exercise can use either 5/3/1 percentage calculations or a manual set/rep/weight prescription."))
            return
        }

        exercises.forEach { exercise ->
            val subtitle = if (exercise.isFiveThreeOne) {
                "5/3/1 · TM ${formatKg(exercise.trainingMax)} · +${formatKg(exercise.increment)} per cycle"
            } else {
                "Manual · ${exercise.defaultSets} × ${repRange(exercise)} @ ${formatKg(exercise.defaultWeight)}"
            }
            root.addView(card().apply {
                addView(text(exercise.name, 18f, true))
                addView(text(subtitle, 14f, false, COLOR_MUTED))
                addView(space(8))
                addView(button("Edit") { showExerciseEditor(exercise) })
                addView(softButton("View progress") { showProgress(exercise.id, exercise.name) { showExercises() } })
            }, matchWrapWithMargin(5))
        }
    }

    private fun showExerciseEditor(
        existing: Exercise?,
        backAction: (() -> Unit)? = null,
        onSaved: ((Long) -> Unit)? = null
    ) {
        setScreen(
            if (existing == null) "Add exercise" else "Edit exercise",
            showNav = false,
            backAction = backAction ?: { showExercises() }
        )

        val c = card()
        val name = textInput("Exercise name", existing?.name ?: "")
        c.addView(label("Name"))
        c.addView(name, matchWithMargin(52, 4))

        c.addView(label("Calculation mode"))
        val modeSpinner = Spinner(this)
        val modeLabels = listOf("Manual sets / reps / weight", "5/3/1 percentages")
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeLabels)
        modeSpinner.setSelection(if (existing?.isFiveThreeOne == true) 1 else 0)
        c.addView(modeSpinner, matchWithMargin(54, 4))

        c.addView(text("For 5/3/1 exercises", 16f, true).apply { setPadding(0, dp(12), 0, 0) })
        val oneRm = numberInput("1RM (kg)", existing?.let { formatRaw(it.oneRepMax) } ?: "")
        val tmPct = numberInput("Training Max %", existing?.let { formatRaw(it.tmPercent * 100) } ?: "90")
        val trainingMax = numberInput("Current Training Max (kg)", existing?.let { formatRaw(it.trainingMax) } ?: "")
        val rounding = numberInput("Round working weights to (kg)", existing?.let { formatRaw(it.roundTo) } ?: "2.5")
        val increment = numberInput("TM increase after each cycle (kg)", existing?.let { formatRaw(it.increment) } ?: "2.5")
        listOf(oneRm, tmPct, trainingMax, rounding, increment).forEach { c.addView(it, matchWithMargin(52, 3)) }
        c.addView(button("Use 1RM × Training Max %") {
            val rm = oneRm.text.toString().toDoubleOrNull()
            val pct = tmPct.text.toString().toDoubleOrNull()
            if (rm == null || pct == null || pct <= 0) toast("Enter a valid 1RM and Training Max %.")
            else trainingMax.setText(formatRaw(ProgramMath.trainingMax(rm, pct / 100.0)))
        })

        c.addView(text("For manual exercises", 16f, true).apply { setPadding(0, dp(12), 0, 0) })
        val manualWeight = numberInput("Default weight (kg)", existing?.let { formatRaw(it.defaultWeight) } ?: "0")
        val manualSets = integerInput("Number of sets", existing?.defaultSets?.toString() ?: "3")
        val repMin = integerInput("Minimum / target reps", existing?.repMin?.toString() ?: "8")
        val repMax = integerInput("Maximum reps", existing?.repMax?.toString() ?: "12")
        listOf(manualWeight, manualSets, repMin, repMax).forEach { c.addView(it, matchWithMargin(52, 3)) }

        c.addView(space(10))
        c.addView(button("Save exercise") {
            val exerciseName = name.text.toString().trim()
            val mode = if (modeSpinner.selectedItemPosition == 1) Exercise.MODE_531 else Exercise.MODE_MANUAL
            val pctValue = (tmPct.text.toString().toDoubleOrNull() ?: 90.0) / 100.0
            val tmValue = trainingMax.text.toString().toDoubleOrNull() ?: 0.0
            val roundValue = rounding.text.toString().toDoubleOrNull() ?: 2.5
            val incValue = increment.text.toString().toDoubleOrNull() ?: 2.5
            val weightValue = manualWeight.text.toString().toDoubleOrNull() ?: 0.0
            val setsValue = manualSets.text.toString().toIntOrNull() ?: 0
            val minValue = repMin.text.toString().toIntOrNull() ?: 0
            val maxValue = repMax.text.toString().toIntOrNull() ?: 0

            when {
                exerciseName.isBlank() -> toast("Give the exercise a name.")
                mode == Exercise.MODE_531 && (tmValue <= 0 || roundValue <= 0 || incValue <= 0) -> toast("5/3/1 needs a Training Max, rounding value, and cycle increment above zero.")
                mode == Exercise.MODE_MANUAL && (setsValue <= 0 || minValue <= 0 || maxValue < minValue || weightValue < 0) -> toast("Check the manual sets, reps, and weight.")
                else -> {
                    val savedId = db.saveExercise(
                        Exercise(
                            id = existing?.id ?: 0,
                            name = exerciseName,
                            mode = mode,
                            oneRepMax = oneRm.text.toString().toDoubleOrNull() ?: 0.0,
                            tmPercent = pctValue,
                            trainingMax = tmValue,
                            roundTo = roundValue,
                            increment = incValue,
                            defaultWeight = weightValue,
                            defaultSets = setsValue.coerceAtLeast(1),
                            repMin = minValue.coerceAtLeast(1),
                            repMax = maxValue.coerceAtLeast(minValue.coerceAtLeast(1))
                        )
                    )
                    toast("Exercise saved.")
                    if (onSaved != null) onSaved(savedId) else showExercises()
                }
            }
        })
        root.addView(c)

        if (existing != null) {
            root.addView(space(12))
            root.addView(dangerButton("Delete exercise") {
                confirm("Delete ${existing.name}?", "It will also be removed from templates. Completed workout history keeps its saved exercise name and sets.") {
                    db.deleteExercise(existing.id)
                    showExercises()
                    offerUndo("Exercise moved to Recently deleted") { db.restoreExercise(existing.id); showExercises() }
                }
            })
        }
    }

    private fun showTemplates() {
        setScreen("Templates", showNav = true)

        val creator = card()
        creator.addView(text("New workout template", 18f, true))
        val name = textInput("e.g. Upper A", "")
        creator.addView(name, matchWithMargin(52, 6))
        creator.addView(button("Create template") {
            val value = name.text.toString().trim()
            if (value.isBlank()) toast("Give the template a name.")
            else {
                val id = db.createTemplate(value)
                showTemplateEditor(WorkoutTemplate(id, value))
            }
        })
        root.addView(creator)

        root.addView(sectionTitle("Your templates"))
        val templates = db.getTemplates()
        if (templates.isEmpty()) {
            root.addView(infoCard("Templates are reusable workouts. Add exercises to the library, then build each template from those exercises."))
        } else {
            templates.forEach { template ->
                val count = db.getTemplateExercises(template.id).size
                root.addView(card().apply {
                    addView(text(template.name, 18f, true))
                    addView(text("$count exercises", 14f, false, COLOR_MUTED))
                    addView(space(8))
                    addView(button("Edit template") { showTemplateEditor(template) })
                }, matchWrapWithMargin(5))
            }
        }
    }

    private fun showTemplateEditor(template: WorkoutTemplate) {
        setScreen("Edit template", showNav = false, backAction = { showTemplates() })

        val nameCard = card()
        val name = textInput("Template name", template.name)
        nameCard.addView(name, matchWithMargin(52, 4))
        nameCard.addView(button("Rename") {
            val value = name.text.toString().trim()
            if (value.isBlank()) toast("Template name cannot be blank.")
            else {
                db.renameTemplate(template.id, value)
                toast("Template renamed.")
                showTemplateEditor(WorkoutTemplate(template.id, value))
            }
        })
        root.addView(nameCard)

        root.addView(sectionTitle("Exercises in this template"))
        val selected = db.getTemplateExercises(template.id)
        if (selected.isEmpty()) root.addView(infoCard("This template is empty. Add an exercise below."))
        selected.forEachIndexed { index, exercise ->
            root.addView(card().apply {
                addView(text("${index + 1}. ${exercise.name}", 17f, true))
                addView(text(if (exercise.isFiveThreeOne) "5/3/1 formula" else "${exercise.defaultSets} × ${repRange(exercise)} @ ${formatKg(exercise.defaultWeight)}", 13f, false, COLOR_MUTED))
                addView(space(6))
                addView(softButton("Edit exercise settings") {
                    showExerciseEditor(
                        existing = exercise,
                        backAction = { showTemplateEditor(template) },
                        onSaved = { showTemplateEditor(template) }
                    )
                })
                addView(space(4))
                addView(dangerButton("Remove from template") {
                    db.removeExerciseFromTemplate(template.id, exercise.id)
                    showTemplateEditor(template)
                })
            }, matchWrapWithMargin(4))
        }

        root.addView(sectionTitle("Quick add popular exercises"))
        root.addView(infoCard("Pick a preset below and it will be added to your exercise library and this template automatically. If you already customised an exercise with the same name, your existing settings are kept."))

        val selectedNames = selected.map { it.name.trim().lowercase(Locale.US) }.toSet()
        ExercisePresets.categories.forEach { category ->
            val available = ExercisePresets.inCategory(category)
                .filter { it.name.trim().lowercase(Locale.US) !in selectedNames }
            if (available.isNotEmpty()) {
                val categoryCard = card()
                categoryCard.addView(text(category, 17f, true))
                categoryCard.addView(text("Tap an exercise to add it", 12f, false, COLOR_MUTED))
                categoryCard.addView(space(6))
                available.forEach { preset ->
                    categoryCard.addView(softButton("+ ${preset.name}") {
                        val exerciseId = db.ensureExercise(preset.toExercise())
                        db.addExerciseToTemplate(template.id, exerciseId)
                        toast("${preset.name} added.")
                        showTemplateEditor(template)
                    }, matchWrapWithMargin(3))
                }
                root.addView(categoryCard, matchWrapWithMargin(5))
            }
        }

        root.addView(space(6))
        root.addView(button("+ Create custom exercise for this template") {
            showExerciseEditor(
                existing = null,
                backAction = { showTemplateEditor(template) },
                onSaved = { exerciseId ->
                    db.addExerciseToTemplate(template.id, exerciseId)
                    showTemplateEditor(template)
                }
            )
        })

        root.addView(sectionTitle("Add from your exercise library"))
        val allExercises = db.getExercises()
        if (allExercises.isEmpty()) {
            root.addView(infoCard("Your personal library is empty. Use a popular preset above or create a custom exercise."))
        } else {
            val addCard = card()
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allExercises.map { it.name })
            addCard.addView(spinner, matchWithMargin(54, 4))
            addCard.addView(button("Add to template") {
                val exercise = allExercises[spinner.selectedItemPosition]
                db.addExerciseToTemplate(template.id, exercise.id)
                showTemplateEditor(template)
            })
            root.addView(addCard)
        }

        root.addView(space(14))
        root.addView(dangerButton("Delete template") {
            confirm("Delete ${template.name}?", "Completed workout history will remain.") {
                db.deleteTemplate(template.id)
                NotificationScheduler.scheduleAll(this)
                showTemplates()
                offerUndo("Template moved to Recently deleted") {
                    db.restoreTemplate(template.id); NotificationScheduler.scheduleAll(this); showTemplates()
                }
            }
        })
    }

    private fun showSchedule() {
        setScreen("Schedule & reminders", showNav = false, backAction = { showDashboard() })

        root.addView(infoCard("Assign one of your workout templates to each training day. LiftLog will remind you at the times below until that day's assigned workout is completed."))

        val templates = db.getTemplates()
        if (templates.isEmpty()) {
            root.addView(space(8))
            root.addView(infoCard("Create at least one workout template before assigning your training days."))
            root.addView(space(8))
            root.addView(button("Create a template") { showTemplates() })
            return
        }

        root.addView(sectionTitle("Training days"))
        val current = db.getWorkoutSchedule().associateBy { it.dayOfWeek }
        val daySpinners = linkedMapOf<Int, Spinner>()
        listOf(3, 5, 6, 7).forEach { day ->
            val c = card()
            c.addView(text(fullDay(day), 17f, true))
            val spinner = Spinner(this)
            val labels = listOf("No workout") + templates.map { it.name }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val selectedTemplateId = current[day]?.templateId
            val selectedIndex = templates.indexOfFirst { it.id == selectedTemplateId }
            spinner.setSelection(if (selectedIndex >= 0) selectedIndex + 1 else 0)
            c.addView(spinner, matchWithMargin(54, 5))
            root.addView(c, matchWrapWithMargin(4))
            daySpinners[day] = spinner
        }

        root.addView(sectionTitle("Reminder times"))
        root.addView(infoCard("Four reminders are scheduled on each assigned training day. Android may deliver them a little later during battery-saving modes; exact alarm permission is not required."))
        val timeCard = card()
        val labels = listOf("Morning", "Midday", "After work", "Evening")
        val savedTimes = NotificationScheduler.reminderTimes(db)
        val timeInputs = mutableListOf<EditText>()
        labels.forEachIndexed { index, labelText ->
            timeCard.addView(label("$labelText reminder · 24-hour HH:mm"))
            val input = textInput("HH:mm", savedTimes[index])
            input.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            timeCard.addView(input, matchWithMargin(52, 3))
            timeInputs += input
        }
        root.addView(timeCard)

        val notificationsEnabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        root.addView(space(8))
        root.addView(infoCard(if (notificationsEnabled) "Notifications are enabled for LiftLog." else "Notifications are currently disabled. Saving will ask Android for notification permission where required."))
        root.addView(space(10))
        root.addView(button("Save workout schedule") {
            val times = timeInputs.map { it.text.toString().trim() }
            if (times.any { !NotificationScheduler.isValidTime(it) }) {
                toast("Use 24-hour times like 08:30 or 17:30.")
                return@button
            }
            daySpinners.forEach { (day, spinner) ->
                val position = spinner.selectedItemPosition
                val templateId = if (position <= 0) null else templates[position - 1].id
                db.setScheduledTemplate(day, templateId)
            }
            NotificationScheduler.saveReminderTimes(db, times)
            requestNotificationPermissionIfNeeded()
            NotificationScheduler.scheduleAll(this)
            toast("Schedule and reminders saved.")
            showDashboard()
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4310)
        }
    }

    private fun shortDay(day: Int): String = when (day) {
        3 -> "Wed"
        5 -> "Fri"
        6 -> "Sat"
        7 -> "Sun"
        else -> "Day"
    }

    private fun fullDay(day: Int): String = when (day) {
        3 -> "Wednesday"
        5 -> "Friday"
        6 -> "Saturday"
        7 -> "Sunday"
        else -> "Day"
    }

    private fun offerUndo(message: String, undo: () -> Unit) {
        AlertDialog.Builder(this).setTitle(message).setMessage("You can also recover this item from Home → Recently deleted.")
            .setPositiveButton("Undo") { _, _ -> undo() }.setNegativeButton("Done", null).show()
    }

    private fun showTrash() {
        setScreen("Recently deleted", false, backAction = { showDashboard() })
        val exercises = db.getExercises(true)
        val templates = db.getTemplates(true)
        val workouts = db.getDiscardedWorkouts()
        root.addView(infoCard("Deleted exercises and templates keep their original IDs and membership. Restoring an item reconnects it to its templates. Discarded workouts can be resumed when no other workout is active."))
        if (exercises.isEmpty() && templates.isEmpty() && workouts.isEmpty()) root.addView(infoCard("Nothing to restore."))
        exercises.forEach { item -> root.addView(softButton("Restore exercise: ${item.name}") { db.restoreExercise(item.id); showTrash() }) }
        templates.forEach { item -> root.addView(softButton("Restore template: ${item.name}") {
            db.restoreTemplate(item.id); NotificationScheduler.scheduleAll(this); showTrash()
        }) }
        workouts.forEach { item -> root.addView(softButton("Resume: ${item.templateName} · ${formatDate(item.startedAt)}") {
            if (db.getActiveWorkout() != null) toast("Finish the current workout first.")
            else { db.undoDiscardWorkout(item.id); showActiveWorkout(item.id) }
        }) }
    }

    private fun showProgress(exerciseId: Long?, name: String, back: () -> Unit) {
        setScreen("Exercise progress", false, backAction = back)
        root.addView(text(name, 22f, true))
        val points = db.getExerciseProgress(exerciseId, name)
        if (points.isEmpty()) { root.addView(infoCard("Complete some sets with reps logged to see progress.")); return }
        root.addView(infoCard("Each point shows a session's highest weight, highest reps or best estimated 1RM. Tap a point to inspect it. Estimates are calculated from logged weight and reps."))
        val selected = text("Tap a point for its values", 14f)
        val chart = ProgressChartView(this, palette).apply {
            this.points = points
            contentDescription = "${name} progress over ${points.size} sessions. The session values are listed below."
            onSelected = { point -> selected.text = "${formatDate(point.at)} · ${formatKg(point.weight)} · ${point.reps} reps · e1RM ${formatKg(point.estimatedOneRepMax)}" }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Weight (kg)", "Reps", "e1RM (kg)").forEachIndexed { i, title ->
            controls.addView(compactButton(title) { chart.metric = i }, weightedActionParams())
        }
        root.addView(controls)
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        root.addView(selected)
        root.addView(sectionTitle("Sessions · newest first"))
        points.reversed().forEach { point -> root.addView(softButton("${formatDate(point.at)}\n${formatKg(point.weight)} · ${point.reps} reps · e1RM ${formatKg(point.estimatedOneRepMax)}") { showHistoryDetail(point.workoutId) }) }
    }

    private fun openWorkout(workoutId: Long) {
        if (db.getWorkout(workoutId)?.status == WorkoutSummary.ACTIVE) showActiveWorkout(workoutId)
        else showHistoryDetail(workoutId)
    }

    private fun saveWorkoutTemplate(workoutId: Long) {
        try {
            db.updateTemplateFromWorkout(workoutId)
            NotificationScheduler.scheduleAll(this)
            toast("Template updated. Other templates are unchanged.")
            showHistoryDetail(workoutId)
        } catch (error: IllegalArgumentException) { toast(error.message ?: "Unable to update the template.") }
    }

    private fun showWorkoutEditor(workoutId: Long) {
        val workout = db.getWorkout(workoutId) ?: return showDashboard()
        setScreen("Edit workout", false, backAction = { openWorkout(workoutId) })
        root.addView(infoCard(if (workout.status == WorkoutSummary.ACTIVE)
            "Changes here affect only this workout. At completion, you can choose whether to update its template."
            else "Correct this saved workout. Statistics, personal records and progress charts update from the saved values."))
        val name = textInput("Workout name", workout.templateName)
        root.addView(name, matchWithMargin(52, 4))
        root.addView(softButton("Save workout name") {
            if (name.text.toString().isBlank()) toast("Enter a workout name.")
            else { db.renameWorkout(workoutId, name.text.toString()); toast("Workout name saved.") }
        })
        val records = db.getWorkoutExercises(workoutId)
        records.forEach { record ->
            root.addView(card().apply {
                addView(text(record.exerciseName, 18f, true))
                addView(text("${db.getWorkoutSets(record.id).size} sets", 13f, false, COLOR_MUTED))
                addView(softButton("Edit weights, reps and sets") { showWorkoutExerciseEditor(workoutId, record) })
                val order = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                order.addView(compactButton("Move up") { db.moveWorkoutExercise(workoutId, record.id, -1); showWorkoutEditor(workoutId) }, weightedActionParams())
                order.addView(compactButton("Move down") { db.moveWorkoutExercise(workoutId, record.id, 1); showWorkoutEditor(workoutId) }, weightedActionParams())
                addView(order)
                addView(dangerButton("Remove from this workout") {
                    confirm("Remove ${record.exerciseName}?", "Its sets will be removed from this workout. The exercise library and template stay unchanged.") {
                        db.removeWorkoutExercise(record.id); showWorkoutEditor(workoutId)
                        AlertDialog.Builder(this@MainActivity).setTitle("Exercise removed")
                            .setPositiveButton("Undo") { _, _ -> db.undoRemoveWorkoutExercise(record.id); showWorkoutEditor(workoutId) }
                            .setNegativeButton("Done", null).show()
                    }
                })
            }, matchWrapWithMargin(5))
        }
        val selected = records.mapNotNull { it.exerciseId }.toSet()
        val available = db.getExercises().filter { it.id !in selected }
        fun add(exerciseId: Long) {
            try { db.addWorkoutExercise(workoutId, exerciseId); showWorkoutEditor(workoutId) }
            catch (error: IllegalArgumentException) { toast(error.message ?: "Could not add the exercise.") }
        }
        root.addView(sectionTitle("Add exercises"))
        if (available.isNotEmpty()) {
            val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, available.map { it.name }) }
            root.addView(spinner, matchWithMargin(54, 4))
            root.addView(button("Add from library") { add(available[spinner.selectedItemPosition].id) })
        }
        root.addView(softButton("Create a custom exercise") {
            showExerciseEditor(null, backAction = { showWorkoutEditor(workoutId) }, onSaved = { add(it) })
        })
        ExercisePresets.categories.forEach { category ->
            root.addView(sectionTitle(category))
            ExercisePresets.inCategory(category).forEach { preset ->
                if (records.none { it.exerciseName.equals(preset.name, true) }) root.addView(softButton("+ ${preset.name}") { add(db.ensureExercise(preset.toExercise())) }, matchWrapWithMargin(3))
            }
        }
        root.addView(space(12))
        root.addView(button(if (workout.status == WorkoutSummary.ACTIVE) "Return to workout" else "View updated summary") { openWorkout(workoutId) })
    }

    private fun showWorkoutExerciseEditor(workoutId: Long, exercise: WorkoutExerciseRecord) {
        fun leave() = confirm("Leave without saving?", "Your set edits on this page will be discarded.") { openWorkout(workoutId) }
        setScreen("Edit sets", false, backAction = { leave() })
        root.addView(text(exercise.exerciseName, 22f, true))
        root.addView(infoCard("Edit this session's targets, weights, reps and completion marks. Save when finished. Adding or removing sets here does not change its template yet."))
        data class Draft(val original: WorkoutSetRecord, val card: View, val target: EditText, val weight: EditText, val reps: EditText, val done: CheckBox)
        val drafts = mutableListOf<Draft>()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        fun append(set: WorkoutSetRecord) {
            val container = card()
            val target = textInput("Target reps", set.targetReps)
            val weight = numberInput("Weight (kg)", formatRaw(set.actualWeight))
            val reps = integerInput("Actual reps", set.actualReps?.toString() ?: "")
            val done = CheckBox(this).apply { text = "Done"; isChecked = set.completed }
            container.addView(label("Target reps")); container.addView(target, matchWithMargin(48, 2))
            container.addView(label("Weight (kg)")); container.addView(weight, matchWithMargin(48, 2))
            container.addView(label("Actual reps (blank if not logged)")); container.addView(reps, matchWithMargin(48, 2))
            container.addView(done)
            val draft = Draft(set, container, target, weight, reps, done)
            drafts += draft
            container.addView(dangerButton("Remove set") {
                if (drafts.size <= 1) toast("Keep at least one set, or remove the exercise instead.")
                else { drafts.remove(draft); rows.removeView(container) }
            })
            rows.addView(container, matchWrapWithMargin(5))
        }
        db.getWorkoutSets(exercise.id).forEach { append(it) }
        root.addView(softButton("+ Add set") {
            if (drafts.size >= 100) toast("Maximum 100 sets per exercise.")
            else {
                val last = drafts.lastOrNull()
                val weight = last?.weight?.text?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
                append(WorkoutSetRecord(0, exercise.id, drafts.size + 1, last?.target?.text?.toString() ?: "8-12", null, weight, weight, null, false))
            }
        })
        root.addView(button("Save sets") {
            try {
                val values = drafts.map { draft ->
                    val weight = requireNotNull(draft.weight.text.toString().toDoubleOrNull()) { "Enter a valid weight for every set" }
                    val repsText = draft.reps.text.toString().trim()
                    val reps = if (repsText.isBlank()) null else requireNotNull(repsText.toIntOrNull()) { "Enter whole-number reps" }
                    require(weight.isFinite() && weight >= 0 && (reps == null || reps >= 0)) { "Weights and reps cannot be negative" }
                    val target = draft.target.text.toString().trim()
                    require(target.isNotEmpty()) { "Enter target reps for every set" }
                    draft.original.copy(actualWeight = weight, actualReps = reps, targetReps = target, completed = draft.done.isChecked)
                }
                db.replaceWorkoutSets(exercise.id, values)
                toast("Set changes saved.")
                openWorkout(workoutId)
            } catch (error: IllegalArgumentException) { toast(error.message ?: "Check the set values.") }
        })
    }

    private fun showActiveWorkout(workoutId: Long) {
        val workout = db.getWorkout(workoutId) ?: run {
            showDashboard()
            return
        }
        if (workout.status != WorkoutSummary.ACTIVE) {
            showHistoryDetail(workoutId)
            return
        }

        setScreen(workout.templateName, showNav = false, backAction = { showDashboard() })

        val timerCard = card()
        val elapsed = text("00:00", 28f, true)
        timerCard.addView(text("Workout timer", 14f, false, COLOR_MUTED))
        timerCard.addView(elapsed)
        timerCard.addView(text("Started ${formatDateTime(workout.startedAt)}", 13f, false, COLOR_MUTED))
        root.addView(timerCard)
        startElapsedTimer(workout.startedAt, elapsed)
        root.addView(space(8))
        root.addView(softButton("Edit workout / add exercises") { showWorkoutEditor(workoutId) })

        root.addView(space(10))
        val restCard = card()
        val restText = text("Rest timer: ready", 18f, true)
        restCard.addView(restText)
        val restButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(60, 90, 120).forEach { seconds ->
            restButtons.addView(compactButton("${seconds}s") { startRestTimer(seconds, restText, workoutId) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        restButtons.addView(compactButton("Stop") {
            stopRestTimer()
            restText.text = "Rest timer: ready"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        restCard.addView(space(8))
        restCard.addView(restButtons)
        val adjustments = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-30, 30).forEach { delta ->
            adjustments.addView(compactButton(if (delta > 0) "+30s" else "−30s") {
                if (RestTimer.endAt(this) == 0L) toast("Start a rest timer first.")
                else RestTimer.adjust(this, delta)
            }, weightedActionParams())
        }
        val duration = integerInput("Seconds", db.getSettingInt("rest_seconds", 90).toString())
        adjustments.addView(duration, LinearLayout.LayoutParams(0, dp(48), 1f))
        adjustments.addView(compactButton("Start") {
            val seconds = duration.text.toString().toIntOrNull()
            if (seconds == null || seconds !in 1..3600) toast("Enter 1–3600 seconds.")
            else { db.setSettingInt("rest_seconds", seconds); startRestTimer(seconds, restText, workoutId) }
        }, weightedActionParams())
        restCard.addView(adjustments)
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Vibrate" to "rest_vibrate", "Sound" to "rest_sound").forEach { (title, key) ->
            options.addView(CheckBox(this).apply {
                text = title; isChecked = db.getSettingInt(key, 1) == 1
                setOnCheckedChangeListener { _, checked -> db.setSettingInt(key, if (checked) 1 else 0) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        restCard.addView(options)
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            restCard.addView(softButton("Allow rest notifications") {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestNotificationPermissionIfNeeded()
                else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            })
        }
        if (!RestTimer.hasPreciseAlarms(this)) {
            restCard.addView(text("Background rest alerts may be delayed until precise alarms are enabled.", 12f, false, COLOR_MUTED))
            restCard.addView(softButton("Enable precise rest alerts") {
                if (Build.VERSION.SDK_INT >= 31) try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: ActivityNotFoundException) { toast("Open Android settings → Alarms & reminders for LiftLog.") }
            })
        }
        root.addView(restCard)
        bindRestTimer(restText, workoutId)

        root.addView(sectionTitle("Sets"))
        db.getWorkoutExercises(workoutId).forEach { exerciseRecord ->
            val exerciseCard = card()
            exerciseCard.addView(text(exerciseRecord.exerciseName, 19f, true))
            exerciseCard.addView(text(if (exerciseRecord.mode == Exercise.MODE_531) "5/3/1 calculated weights · edit any weight if needed" else "Manual prescription · edit weight or actual reps as you train", 13f, false, COLOR_MUTED))
            exerciseCard.addView(space(6))

            val previous = db.previousSessionSets(exerciseRecord.exerciseId, exerciseRecord.exerciseName, workoutId).associateBy { it.setNumber }
            db.getWorkoutSets(exerciseRecord.id).forEach { set ->
                exerciseCard.addView(activeSetRow(set, previous[set.setNumber]))
            }
            exerciseCard.addView(softButton("Edit sets") { showWorkoutExerciseEditor(workoutId, exerciseRecord) })
            root.addView(exerciseCard, matchWrapWithMargin(5))
        }

        root.addView(space(14))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(dangerButton("Discard") {
            confirm("Discard this workout?", "The active workout and its set entries will be deleted and will not appear in history.") {
                db.discardWorkout(workoutId)
                stopRestTimer()
                stopUiTimers()
                showDashboard()
                offerUndo("Workout discarded") {
                    if (db.getActiveWorkout() != null) toast("Finish the current workout before restoring this one.")
                    else { db.undoDiscardWorkout(workoutId); showActiveWorkout(workoutId) }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(4) })
        actions.addView(button("Complete workout") {
            val latest = db.getWorkout(workoutId) ?: return@button
            if (latest.totalSets == 0) { toast("Add an exercise and at least one set before completing."); return@button }
            val remaining = latest.totalSets - latest.completedSets
            if (remaining > 0) {
                confirm("Complete with $remaining unticked sets?", "You can still save the workout, but those sets will remain marked incomplete in history.") {
                    finishWorkout(workoutId)
                }
            } else {
                finishWorkout(workoutId)
            }
        }, LinearLayout.LayoutParams(0, dp(54), 2f).apply { leftMargin = dp(4) })
        root.addView(actions)
        root.addView(space(20))
    }

    private fun activeSetRow(set: WorkoutSetRecord, previous: WorkoutSetRecord? = null): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val pct = set.percentage?.let { " · ${(it * 100).toInt()}%" } ?: ""
        wrap.addView(text("Set ${set.setNumber} · target ${set.targetReps}$pct · planned ${formatKg(set.plannedWeight)}", 14f, true))
        wrap.addView(text(if (previous?.completed == true) "Last: ${formatKg(previous.actualWeight)} × ${previous.actualReps?.toString() ?: "—"} reps"
            else "Last: no completed set recorded", 12f, false, COLOR_MUTED))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val done = CheckBox(this).apply {
            isChecked = set.completed
            text = "Done"
        }
        val weight = numberInput("kg", formatRaw(set.actualWeight))
        val reps = integerInput("reps", set.actualReps?.toString() ?: "")
        done.setOnCheckedChangeListener { _, checked ->
            val enteredWeight = weight.text.toString().toDoubleOrNull()
            val enteredReps = reps.text.toString().toIntOrNull()
            if (checked && (enteredWeight == null || !enteredWeight.isFinite() || enteredWeight < 0 || (reps.text.isNotBlank() && (enteredReps == null || enteredReps < 0)))) {
                toast("Enter a valid weight and whole-number reps before marking Done.")
                done.isChecked = false
            } else db.updateSetCompleted(set.id, checked)
        }
        weight.addTextChangedListener(simpleWatcher {
            val value = weight.text.toString().toDoubleOrNull()
            weight.error = if (value == null || !value.isFinite() || value < 0) "Enter a non-negative weight" else null
            weight.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { db.updateSetWeight(set.id, it) }
        })
        reps.addTextChangedListener(simpleWatcher {
            val value = reps.text.toString()
            reps.error = if (value.isNotBlank() && (value.toIntOrNull() == null || (value.toIntOrNull() ?: -1) < 0)) "Enter non-negative whole-number reps" else null
            if (value.isBlank()) db.updateSetReps(set.id, null)
            else value.toIntOrNull()?.takeIf { it >= 0 }?.let { db.updateSetReps(set.id, it) }
        })

        row.addView(done, LinearLayout.LayoutParams(0, dp(50), 1.2f))
        row.addView(weight, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        row.addView(reps, LinearLayout.LayoutParams(0, dp(50), 0.8f))
        wrap.addView(row)
        return wrap
    }

    private fun finishWorkout(workoutId: Long) {
        db.completeWorkout(workoutId)
        NotificationScheduler.dismissDay(this, LocalDate.now().dayOfWeek.value)
        stopRestTimer()
        stopUiTimers()
        toast("Workout saved to history.")
        showHistoryDetail(workoutId)
        AlertDialog.Builder(this).setTitle("Update the workout template?")
            .setMessage("Save this workout's exercise list, order and manual sets/weights/reps for future sessions? 5/3/1 keeps its progression rules. Other templates will not be changed.")
            .setPositiveButton("Update template") { _, _ -> saveWorkoutTemplate(workoutId) }
            .setNegativeButton("Keep template", null).show()
    }

    private fun showHistory() {
        setScreen("History", showNav = true)
        val workouts = db.getCompletedWorkouts()
        val overview = db.getHistoryOverview()

        root.addView(text("Your training", 24f, true))
        root.addView(text("Volume, consistency and personal records from completed sessions.", 14f, false, COLOR_MUTED))
        root.addView(space(10))
        root.addView(tintedCard(COLOR_PRIMARY_SOFT).apply {
            addView(statRow(
                "Workouts" to overview.totalWorkouts.toString(),
                "Last 30d" to overview.workoutsLast30Days.toString(),
                "Sets" to overview.totalCompletedSets.toString()
            ))
            addView(space(10))
            addView(statRow(
                "Volume" to formatVolume(overview.totalVolume),
                "Reps" to overview.totalReps.toString(),
                "Avg time" to formatCompactDuration(overview.averageDurationSeconds)
            ))
        })

        root.addView(sectionTitle("Workout history"))
        if (workouts.isEmpty()) {
            root.addView(infoCard("No completed workouts yet."))
        } else {
            workouts.forEach { addHistorySummary(it) }
        }

        val records = db.getPersonalRecords()
        root.addView(sectionTitle("Personal records"))
        if (records.isEmpty()) {
            root.addView(infoCard("Log reps on completed sets and your PRs will appear here automatically."))
        } else {
            records.forEach { record ->
                root.addView(card().apply {
                    addView(horizontalTitle(record.exerciseName, "PR"))
                    addView(space(7))
                    addView(statRow(
                        "Heaviest" to formatKg(record.heaviestWeight),
                        "At weight" to "${record.heaviestWeightReps} reps",
                        "Est. 1RM" to formatKg(record.estimatedOneRepMax)
                    ))
                    addView(space(7))
                    addView(text(
                        "e1RM best: ${formatKg(record.e1rmWeight)} × ${record.e1rmReps} · ${formatDate(record.e1rmAt)}",
                        13f, false, COLOR_MUTED
                    ))
                    addView(softButton("View progress") { showProgress(record.exerciseId, record.exerciseName) { showHistory() } })
                }, matchWrapWithMargin(4))
            }
        }

    }

    private fun addHistorySummary(summary: WorkoutSummary) {
        val stats = db.getWorkoutStats(summary.id)
        root.addView(card().apply {
            addView(text(summary.templateName, 19f, true))
            addView(text("${formatDate(summary.completedAt ?: summary.startedAt)} · ${formatCompactDuration(stats.durationSeconds)}", 13f, false, COLOR_MUTED))
            addView(space(9))
            addView(statRow(
                "Volume" to formatVolume(stats.totalVolume),
                "Reps" to stats.totalReps.toString(),
                "Sets" to "${stats.completedSets}/${stats.totalSets}"
            ))
            if (stats.prCount > 0) {
                addView(space(8))
                addView(prPill("★ ${stats.prCount} PR${if (stats.prCount == 1) "" else "s"}"))
            }
            db.getWorkoutExercises(summary.id).forEach { exercise ->
                val sets = db.getWorkoutSets(exercise.id)
                val completed = sets.filter { it.completed }
                addView(space(12))
                addView(text(exercise.exerciseName, 15f, true))
                addView(text("${completed.size}/${sets.size} sets completed", 13f, false, COLOR_MUTED))
                completed.forEach { set ->
                    val reps = set.actualReps?.let { "$it reps" } ?: "reps not logged"
                    addView(text("Set ${set.setNumber} · ${formatKg(set.actualWeight)} · $reps", 13f, false, COLOR_MUTED))
                }
            }
            addView(space(9))
            addView(softButton("View workout") { showHistoryDetail(summary.id) })
        }, matchWrapWithMargin(5))
    }

    private fun showHistoryDetail(workoutId: Long) {
        val workout = db.getWorkout(workoutId) ?: run {
            showHistory()
            return
        }
        setScreen("Workout summary", showNav = false, backAction = { showHistory() })
        val stats = db.getWorkoutStats(workoutId)
        val exerciseStats = db.getExerciseWorkoutStats(workoutId).associateBy { it.workoutExerciseId }
        val prSets = db.getWorkoutPrSetIds(workoutId)

        root.addView(tintedCard(COLOR_PRIMARY_SOFT).apply {
            addView(text(workout.templateName, 24f, true))
            addView(text(formatDateTime(workout.completedAt ?: workout.startedAt), 14f, false, COLOR_MUTED))
            addView(space(12))
            addView(statRow(
                "Volume" to formatVolume(stats.totalVolume),
                "Reps" to stats.totalReps.toString(),
                "Duration" to formatCompactDuration(stats.durationSeconds)
            ))
            addView(space(10))
            addView(statRow(
                "Exercises" to stats.exerciseCount.toString(),
                "Sets" to "${stats.completedSets}/${stats.totalSets}",
                "Top load" to formatKg(stats.topWeight)
            ))
            if (stats.prCount > 0) {
                addView(space(10))
                addView(prPill("★ ${stats.prCount} personal record${if (stats.prCount == 1) "" else "s"} in this workout"))
            }
        })

        root.addView(space(8))
        root.addView(softButton("Edit this workout") { showWorkoutEditor(workoutId) })
        root.addView(softButton("Update template from this workout") {
            confirm("Update the template?", "Replace the template's exercises, order and manual set defaults with this workout. 5/3/1 progression is retained.") { saveWorkoutTemplate(workoutId) }
        })
        root.addView(sectionTitle("Exercise breakdown"))
        db.getWorkoutExercises(workoutId).forEach { exercise ->
            val exStats = exerciseStats[exercise.id]
            val c = card()
            c.addView(text(exercise.exerciseName, 19f, true))
            if (exStats != null) {
                c.addView(text(
                    "${exStats.completedSets}/${exStats.totalSets} sets · ${exStats.totalReps} reps · ${formatVolume(exStats.volume)} volume",
                    13f, false, COLOR_MUTED
                ))
                if (exStats.topWeight > 0) {
                    c.addView(text("Top load ${formatKg(exStats.topWeight)} · est. 1RM ${formatKg(exStats.estimatedOneRepMax)}", 13f, false, COLOR_MUTED))
                }
            }
            c.addView(space(8))
            db.getWorkoutSets(exercise.id).forEach { set ->
                val pct = set.percentage?.let { " · ${(it * 100).toInt()}%" } ?: ""
                val actualReps = set.actualReps?.toString() ?: "—"
                val tick = if (set.completed) "✓" else "○"
                val setLine = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(text("$tick Set ${set.setNumber}  ${formatKg(set.actualWeight)} × $actualReps", 14f, true))
                    addView(text("Target ${set.targetReps}$pct · planned ${formatKg(set.plannedWeight)}", 12f, false, COLOR_MUTED))
                    prSets[set.id]?.forEach { badge ->
                        addView(prPill(if (badge == "WEIGHT PR") "★ Weight PR" else "★ e1RM PR"))
                    }
                }
                c.addView(setLine)
            }
            root.addView(c, matchWrapWithMargin(5))
        }
        root.addView(space(12))
        root.addView(softButton("Back to history") { showHistory() })
    }

    private fun startElapsedTimer(startedAt: Long, target: TextView) {
        elapsedDisplay = target
        elapsedStartedAt = startedAt
        elapsedRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val seconds = max(0L, (System.currentTimeMillis() - startedAt) / 1000L)
                target.text = formatDuration(seconds)
                handler.postDelayed(this, 1000L)
            }
        }
        elapsedRunnable = runnable
        handler.post(runnable)
    }

    private fun startRestTimer(seconds: Int, target: TextView, workoutId: Long) {
        requestNotificationPermissionIfNeeded()
        RestTimer.start(this, workoutId, seconds)
        bindRestTimer(target, workoutId)
    }

    private fun bindRestTimer(target: TextView, workoutId: Long) {
        restDisplay = target
        restWorkoutId = workoutId
        restRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val end = RestTimer.endAt(this@MainActivity)
                val remaining = RestTimer.remainingSeconds(this@MainActivity)
                if (RestTimer.workoutId(this@MainActivity) == workoutId) {
                    target.text = if (remaining > 0) "Rest: ${formatDuration(remaining)}" else if (end > 0 || RestTimer.isComplete(this@MainActivity)) "Rest complete" else "Rest timer: ready"
                    if (end > 0 && remaining == 0L && RestTimer.deliver(this@MainActivity, end)) toast("Rest timer finished.")
                } else target.text = "Rest timer: ready"
                handler.postDelayed(this, 250L)
            }
        }
        restRunnable = runnable
        handler.post(runnable)
    }

    private fun stopRestTimer() {
        restRunnable?.let { handler.removeCallbacks(it) }
        restRunnable = null
        RestTimer.stop(this)
    }

    private fun stopUiTimers() {
        elapsedRunnable?.let { handler.removeCallbacks(it) }
        restRunnable?.let { handler.removeCallbacks(it) }
        elapsedRunnable = null
        restRunnable = null
    }

    private fun setScreen(title: String, showNav: Boolean, backAction: (() -> Unit)? = null) {
        stopUiTimers()
        restDisplay = null
        elapsedDisplay = null
        currentBackAction = backAction
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        screen.setOnApplyWindowInsetsListener { _, insets ->
            val statusBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            root.setPadding(dp(16), statusBarHeight + dp(16), dp(16), dp(28))
            insets
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        screen.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (backAction != null) {
            header.addView(ghostButton("‹ Back") { backAction() }, LinearLayout.LayoutParams(dp(92), dp(48)).apply { rightMargin = dp(8) })
        }
        header.addView(text(title, 28f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        root.addView(space(14))

        if (showNav) {
            screen.addView(bottomNav(title), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        }
        setContentView(screen)
    }

    private fun bottomNav(title: String): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply { setColor(COLOR_SURFACE) }
        }
        val items: List<Triple<String, Boolean, () -> Unit>> = listOf(
            Triple("⌂\nHome", title == "LiftLog", { showDashboard() }),
            Triple("＋\nExercises", title == "Exercises", { showExercises() }),
            Triple("▦\nTemplates", title == "Templates", { showTemplates() }),
            Triple("◷\nHistory", title == "History", { showHistory() })
        )
        items.forEach { (label, selected, action) ->
            nav.addView(navButton(label, selected, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        return nav
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = matchWrapWithMargin(6)
        elevation = dp(1).toFloat()
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER, 16)
    }

    private fun tintedCard(color: Int): LinearLayout = card().apply {
        background = roundedBackground(color, color, 18)
    }

    private fun infoCard(message: String): View = tintedCard(COLOR_TEAL_SOFT).apply {
        addView(text(message, 14f, false, COLOR_MUTED))
    }

    private fun sectionTitle(value: String): TextView = text(value, 18f, true).apply {
        setPadding(0, dp(20), 0, dp(7))
    }

    private fun label(value: String): TextView = text(value, 14f, true).apply {
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = COLOR_TEXT): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, 0, 0, dp(4))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
        }

    private fun button(label: String, click: () -> Unit): Button = styledButton(label, COLOR_PRIMARY, palette.onPrimary, click)

    private fun softButton(label: String, click: () -> Unit): Button = styledButton(label, COLOR_PRIMARY_SOFT, COLOR_PRIMARY, click)

    private fun ghostButton(label: String, click: () -> Unit): Button = styledButton(label, Color.TRANSPARENT, COLOR_PRIMARY, click, stroke = COLOR_BORDER)

    private fun dangerButton(label: String, click: () -> Unit): Button = styledButton(label, COLOR_DANGER_SOFT, COLOR_DANGER, click)

    private fun styledButton(label: String, bg: Int, fg: Int, click: () -> Unit, stroke: Int? = null): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(12).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
        // A custom drawable removes native button insets. Supply explicit
        // content padding and spacing for every action, including View progress.
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.CENTER
        layoutParams = matchWrapWithMargin(4)
        setOnClickListener { click() }
    }

    private fun compactButton(label: String, click: () -> Unit): Button = softButton(label, click).apply {
        setPadding(dp(6), dp(6), dp(6), dp(6))
        textSize = 12f
    }

    private fun weightedActionParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        }

    private fun navButton(label: String, selected: Boolean, click: () -> Unit): Button = styledButton(
        label,
        if (selected) COLOR_PRIMARY_SOFT else Color.TRANSPARENT,
        if (selected) COLOR_PRIMARY else COLOR_MUTED,
        click
    ).apply {
        textSize = 11f
        setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER
    }

    private fun statRow(vararg stats: Pair<String, String>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        stats.forEachIndexed { index, (label, value) ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(value, 17f, true).apply { gravity = Gravity.CENTER })
                addView(text(label, 11f, false, COLOR_MUTED).apply { gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(4)
            })
        }
    }

    private fun horizontalTitle(title: String, badge: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(title, 18f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(prPill(badge))
    }

    private fun prPill(label: String): TextView = text(label, 11f, true, COLOR_PR).apply {
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = roundedBackground(COLOR_PR_SOFT, COLOR_PR_SOFT, 20)
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != fill) setStroke(dp(1), stroke)
    }

    private fun textInput(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(true)
        inputBackground(this)
    }

    private fun numberInput(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        inputBackground(this)
    }

    private fun integerInput(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        inputBackground(this)
    }

    private fun inputBackground(input: EditText) {
        input.setPadding(dp(12), dp(8), dp(12), dp(8))
        input.setTextColor(COLOR_TEXT)
        input.setHintTextColor(COLOR_MUTED)
        input.background = roundedBackground(palette.input, COLOR_BORDER, 11)
    }

    private fun simpleWatcher(after: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, afterCount: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after()
    }

    private fun confirm(title: String, message: String, yes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> yes() }
            .show()
    }

    private fun weekName(week: Int): String = when (week) {
        1 -> "5s week · 65 / 75 / 85%"
        2 -> "3s week · 70 / 80 / 90%"
        3 -> "5/3/1 week · 75 / 85 / 95%"
        else -> "Deload · 40 / 50 / 60%"
    }

    private fun repRange(exercise: Exercise): String =
        if (exercise.repMin == exercise.repMax) exercise.repMin.toString() else "${exercise.repMin}-${exercise.repMax}"

    private fun formatKg(value: Double): String = "${formatRaw(value)} kg"

    private fun formatRaw(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private fun formatDate(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))

    private fun formatDateTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a"))

    private fun formatTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

    private fun durationText(startedAt: Long, completedAt: Long?): String {
        val end = completedAt ?: System.currentTimeMillis()
        return formatDuration(max(0L, (end - startedAt) / 1000L))
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun formatVolume(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fm kg", value / 1_000_000.0)
        value >= 10_000 -> String.format(Locale.US, "%.1fk kg", value / 1_000.0)
        else -> "${formatRaw(value)} kg"
    }

    private fun formatCompactDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun matchWithMargin(heightDp: Int, marginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
            topMargin = dp(marginDp)
            bottomMargin = dp(marginDp)
        }

    private fun matchWrapWithMargin(marginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(marginDp)
            bottomMargin = dp(marginDp)
        }

    private fun space(valueDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(valueDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_EXPORT_DATA = 1001
        private const val REQUEST_RESTORE_DATA = 1002
    }

}
