package com.example.fivethreeonelifter

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.app.NotificationManager
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
    private var restEndAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = WorkoutDb(this)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_SURFACE
        NotificationScheduler.createChannel(this)
        NotificationScheduler.scheduleAll(this)
        showDashboard()
    }

    override fun onDestroy() {
        stopUiTimers()
        db.close()
        super.onDestroy()
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
            addView(text("Any exercise using 5/3/1 uses this week and its own Training Max. Manual exercises use their saved set/rep/weight defaults.", 14f))
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
                }, matchWithMargin(56, 4))
            }
        }

        root.addView(sectionTitle("Training heatmap"))
        root.addView(card().apply {
            addView(text("Last 12 weeks", 17f, true))
            addView(text("Darker squares mean more completed workouts that day.", 13f, false, COLOR_MUTED))
            addView(HeatmapView(this@MainActivity).apply { counts = db.workoutCountsByDate() })
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
                    }, matchWithMargin(46, 2))
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
                showTemplates()
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

        root.addView(space(10))
        val restCard = card()
        val restText = text("Rest timer: ready", 18f, true)
        restCard.addView(restText)
        val restButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(60, 90, 120).forEach { seconds ->
            restButtons.addView(compactButton("${seconds}s") { startRestTimer(seconds, restText) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        restButtons.addView(compactButton("Stop") {
            stopRestTimer()
            restText.text = "Rest timer: ready"
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        restCard.addView(space(8))
        restCard.addView(restButtons)
        root.addView(restCard)

        root.addView(sectionTitle("Sets"))
        db.getWorkoutExercises(workoutId).forEach { exerciseRecord ->
            val exerciseCard = card()
            exerciseCard.addView(text(exerciseRecord.exerciseName, 19f, true))
            exerciseCard.addView(text(if (exerciseRecord.mode == Exercise.MODE_531) "5/3/1 calculated weights · edit any weight if needed" else "Manual prescription · edit weight or actual reps as you train", 13f, false, COLOR_MUTED))
            exerciseCard.addView(space(6))

            db.getWorkoutSets(exerciseRecord.id).forEach { set ->
                exerciseCard.addView(activeSetRow(set))
            }
            root.addView(exerciseCard, matchWrapWithMargin(5))
        }

        root.addView(space(14))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(dangerButton("Discard") {
            confirm("Discard this workout?", "The active workout and its set entries will be deleted and will not appear in history.") {
                db.discardWorkout(workoutId)
                stopUiTimers()
                showDashboard()
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(4) })
        actions.addView(button("Complete workout") {
            val latest = db.getWorkout(workoutId) ?: return@button
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

    private fun activeSetRow(set: WorkoutSetRecord): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val pct = set.percentage?.let { " · ${(it * 100).toInt()}%" } ?: ""
        wrap.addView(text("Set ${set.setNumber} · target ${set.targetReps}$pct · planned ${formatKg(set.plannedWeight)}", 14f, true))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val done = CheckBox(this).apply {
            isChecked = set.completed
            text = "Done"
            setOnCheckedChangeListener { _, checked -> db.updateSetCompleted(set.id, checked) }
        }
        val weight = numberInput("kg", formatRaw(set.actualWeight))
        val reps = integerInput("reps", set.actualReps?.toString() ?: "")
        weight.addTextChangedListener(simpleWatcher {
            weight.text.toString().toDoubleOrNull()?.let { db.updateSetWeight(set.id, it) }
        })
        reps.addTextChangedListener(simpleWatcher {
            db.updateSetReps(set.id, reps.text.toString().toIntOrNull())
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
        stopUiTimers()
        toast("Workout saved to history.")
        showHistoryDetail(workoutId)
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
                }, matchWrapWithMargin(4))
            }
        }

        root.addView(sectionTitle("Workout history"))
        if (workouts.isEmpty()) {
            root.addView(infoCard("No completed workouts yet."))
            return
        }
        workouts.forEach { addHistorySummary(it) }
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

    private fun startRestTimer(seconds: Int, target: TextView) {
        restRunnable?.let { handler.removeCallbacks(it) }
        restEndAt = System.currentTimeMillis() + seconds * 1000L
        val runnable = object : Runnable {
            override fun run() {
                val remaining = ((restEndAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                target.text = if (remaining > 0) "Rest: ${formatDuration(remaining)}" else "Rest complete"
                if (remaining > 0) handler.postDelayed(this, 250L) else toast("Rest timer finished.")
            }
        }
        restRunnable = runnable
        handler.post(runnable)
    }

    private fun stopRestTimer() {
        restRunnable?.let { handler.removeCallbacks(it) }
        restRunnable = null
        restEndAt = 0L
    }

    private fun stopUiTimers() {
        elapsedRunnable?.let { handler.removeCallbacks(it) }
        restRunnable?.let { handler.removeCallbacks(it) }
        elapsedRunnable = null
        restRunnable = null
    }

    private fun setScreen(title: String, showNav: Boolean, backAction: (() -> Unit)? = null) {
        stopUiTimers()
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
            header.addView(ghostButton("‹ Back") { backAction() }, LinearLayout.LayoutParams(dp(92), dp(44)).apply { rightMargin = dp(8) })
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
        setPadding(dp(15), dp(15), dp(15), dp(15))
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
        setPadding(0, dp(9), 0, 0)
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = COLOR_TEXT): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.12f)
        }

    private fun button(label: String, click: () -> Unit): Button = styledButton(label, COLOR_PRIMARY, Color.WHITE, click)

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
        setOnClickListener { click() }
    }

    private fun compactButton(label: String, click: () -> Unit): Button = softButton(label, click).apply {
        setPadding(dp(3), 0, dp(3), 0)
        textSize = 12f
    }

    private fun navButton(label: String, selected: Boolean, click: () -> Unit): Button = styledButton(
        label,
        if (selected) COLOR_PRIMARY_SOFT else Color.TRANSPARENT,
        if (selected) COLOR_PRIMARY else COLOR_MUTED,
        click
    ).apply {
        textSize = 11f
        setPadding(dp(2), 0, dp(2), 0)
        gravity = Gravity.CENTER
    }

    private fun statRow(vararg stats: Pair<String, String>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
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
        input.setPadding(dp(11), 0, dp(11), 0)
        input.setTextColor(COLOR_TEXT)
        input.setHintTextColor(COLOR_MUTED)
        input.background = roundedBackground(Color.WHITE, COLOR_BORDER, 11)
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
        private val COLOR_BACKGROUND = Color.rgb(246, 247, 251)
        private val COLOR_SURFACE = Color.WHITE
        private val COLOR_TEXT = Color.rgb(28, 31, 42)
        private val COLOR_MUTED = Color.rgb(105, 111, 128)
        private val COLOR_BORDER = Color.rgb(229, 231, 239)
        private val COLOR_PRIMARY = Color.rgb(93, 79, 219)
        private val COLOR_PRIMARY_SOFT = Color.rgb(238, 235, 255)
        private val COLOR_TEAL_SOFT = Color.rgb(229, 249, 245)
        private val COLOR_DANGER = Color.rgb(196, 67, 80)
        private val COLOR_DANGER_SOFT = Color.rgb(255, 235, 237)
        private val COLOR_PR = Color.rgb(174, 104, 0)
        private val COLOR_PR_SOFT = Color.rgb(255, 243, 208)
    }

}
