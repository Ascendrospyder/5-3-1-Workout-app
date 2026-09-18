# LiftLog 2.4 — scheduled workout reminders

A small native Android/Kotlin lifting app with reusable workout templates, a custom exercise library, live workout logging, timers, detailed history analytics, personal-record tracking, a 12-week training heatmap, and schedule-aware workout notifications.

The app keeps exercises configurable, but now includes a built-in catalogue of popular Chest, Back, Legs, Arms and Shoulders exercises for one-tap template building. You can still create any custom exercise you want and decide whether it uses 5/3/1 calculations or a manual prescription.

## What this version does

### Exercise library

Create any exercise you want. Each exercise has one of two modes:

- **5/3/1** — stores a Training Max, rounding increment, and per-cycle TM increase. When a workout starts, the app automatically generates that week's 3 main sets and their weights.
- **Manual** — stores a default weight, number of sets, and rep range. Good for rows, curls, triceps, lateral raises, RDLs, etc.

For a 5/3/1 exercise you can enter a 1RM and Training Max percentage and press **Use 1RM × Training Max %** to calculate the starting Training Max. You can also directly edit the current Training Max.


### Popular exercise presets

While editing a workout template, LiftLog shows a built-in quick-add catalogue grouped into **Chest, Back, Legs, Arms and Shoulders**. It includes common movements such as bench press, incline presses, chest-supported rows, pulldowns, squats, leg press, RDLs, curls, triceps extensions, overhead press and lateral raises.

Tapping a preset does two things automatically:

1. adds the exercise to your personal exercise library if it is not already there, and
2. adds it to the workout template you are currently editing.

If you already have an exercise with the same name, LiftLog reuses your existing customised version rather than overwriting its weights, 5/3/1 settings, sets or reps. Presets start in manual mode with sensible set/rep ranges and a 0 kg default weight so you can enter the load that fits you. You can edit any preset later and switch it to 5/3/1 if desired.

There is also a **Create custom exercise for this template** action. Saving a custom exercise from there automatically adds it to that template.

### Reusable workout templates

Create templates such as:

- Press + triceps
- Back + biceps
- Bench + chest
- Squat + legs

or any other split you prefer.

A template is built by adding exercises from your exercise library. Starting a workout always starts from a template.

### Live workout screen

During a workout the app gives you:

- elapsed workout timer
- 60 / 90 / 120 second rest timers
- every exercise and set from the template
- a **Done** checkbox for every set
- the planned / formula-calculated weight
- an editable weight field, so you can override the calculated number
- an actual reps field
- **Complete workout** and **Discard workout** actions

The current workout is stored in SQLite, so its sets are not just temporary UI state.

### Workout history + PR tracking

The History screen now summarizes total workouts, workouts in the last 30 days, completed sets, total reps, training volume, and average session duration. Each workout also shows its own volume, reps, sets, duration, top load and exercise-level stats.

Personal records are calculated automatically from completed sets where actual reps were logged:

- **Weight PR** — the heaviest completed load recorded for that exercise at that point in your history.
- **Estimated 1RM PR** — the best estimated one-rep max from weight × reps using the Epley estimate (a logged single is treated as its actual weight).

PR badges are historical: an old workout still shows the PR it earned on that date even after a later workout beats it. The History screen also shows your current heaviest-load and estimated-1RM records for each exercise.

Completing a workout saves a dated snapshot containing:

- template name
- start time
- completion time
- duration
- exercise names
- each set's target reps
- planned percentage for 5/3/1 sets
- actual weight
- actual reps
- whether each set was ticked complete

Editing or deleting an exercise later does not rewrite old completed workout logs.

Discarding a workout deletes the active session instead of adding it to history.

### UI and navigation

The app uses a cleaner, colourful card style with indigo, teal and warm PR accents. The main navigation is a fixed **bottom dock** for Home, Exercises, Templates and History, so it no longer scrolls at the top of each page.

### Workout schedule + notifications

LiftLog now has a schedule specifically for **Wednesday, Friday, Saturday and Sunday**. Open **Home > Manage schedule & reminders** and assign one of your workout templates to each training day. The app uses the template assigned to that weekday in the notification text, so reminders say what workout is actually planned rather than only saying that it is a training day.

By default, each assigned training day has four reminder times:

- 08:30
- 13:00
- 17:30
- 20:30

All four times are editable in 24-hour `HH:mm` format. Reminders are scheduled in the phone's local timezone. If the assigned workout is completed that day, later reminders are suppressed automatically. Discarding a workout does not mark the day complete, so a later reminder can still appear. If a scheduled workout is already active, the notification changes to a resume reminder.

The reminders use Android's normal inexact alarm path, so they do not require the special exact-alarm permission and Android may shift delivery slightly during battery-saving modes. Android 13+ requires the normal notification permission; LiftLog asks for it when you save the schedule. Reminder scheduling is restored after device reboot or app replacement/update.

### Dashboard

The dashboard contains:

- active-workout resume card
- current 5/3/1 cycle and week
- buttons to start each workout template
- a small **12-week heatmap** based on completed workout dates
- recent workout summaries with volume, reps, completed-set counts and PR badges

### 5/3/1 calculations

The global 5/3/1 week determines the percentages used by every exercise configured for 5/3/1:

- Week 1: 65% × 5, 75% × 5, 85% × 5+
- Week 2: 70% × 3, 80% × 3, 90% × 3+
- Week 3: 75% × 5, 85% × 3, 95% × 1+
- Week 4: 40% × 5, 50% × 5, 60% × 5

Calculated weights are rounded to that exercise's configured increment (for example 2.5 kg).

When you finish Week 4 and press **Finish deload + progress Training Maxes**, the app returns to Week 1, starts the next cycle, and adds each 5/3/1 exercise's configured TM increment. This means you can use +2.5 kg for upper-body lifts and +5 kg for lower-body lifts without hardcoding exercise names.

## Project structure

- `MainActivity.kt` — screens and workout UI
- `WorkoutDb.kt` — SQLite persistence for exercises, templates, workout schedule, workouts and sets
- `Models.kt` — data models
- `ProgramMath.kt` — 5/3/1 and weight-rounding calculations
- `HeatmapView.kt` — custom 12-week dashboard heatmap
- `NotificationScheduler.kt` — schedules the next reminder occurrences
- `WorkoutReminderReceiver.kt` — checks the day/template state and posts reminders
- `BootReceiver.kt` — restores reminders after reboot/update
- `ProgramMathTest.kt` — calculation unit tests

The project deliberately uses Android's built-in UI widgets and SQLite APIs, so there are very few runtime dependencies.

## Toolchain

- Android Gradle Plugin 8.7.2
- Gradle 8.9
- Kotlin Android plugin 2.1.20
- compileSdk / targetSdk 35
- minSdk 26
- Java 17 bytecode target

## Open the project in Android Studio

1. Install Android Studio.
2. Unzip the downloaded project.
3. Open the folder containing `settings.gradle.kts` in Android Studio.
4. Let Gradle Sync run.
5. If Android Studio asks to install Android SDK Platform 35 or build tools, accept it.

The supplied wrapper bootstrap script downloads the official Gradle 8.9 wrapper JAR the first time it is used and verifies it against Gradle's published SHA-256 checksum. Your computer therefore needs internet access for the initial Gradle/Android dependency setup.

## Install directly on your Android phone

1. On the phone open **Settings > About phone**.
2. Tap **Build number** seven times to enable Developer options. The exact location varies by phone manufacturer.
3. Open **Developer options** and enable **USB debugging**.
4. Connect the phone to your computer with a USB data cable.
5. Accept the **Allow USB debugging?** prompt on the phone.
6. Open this project in Android Studio.
7. Select your phone in Android Studio's device dropdown.
8. Press the green **Run** button.
9. Android Studio builds, installs, and launches **LiftLog** on your phone.

## Build an APK instead

In Android Studio use **Build > Build APK(s)**. The debug APK is normally created at:

`app/build/outputs/apk/debug/app-debug.apk`

Copy that APK to the phone, open it, allow installs from that source when Android prompts you, and install it.

## Suggested first setup

1. Open **Exercises**.
2. Add Bench Press as 5/3/1. Enter your 1RM, 90%, calculate the TM, use 2.5 kg rounding, and a 2.5 kg cycle increment.
3. Add Squat as 5/3/1 with a 5 kg cycle increment.
4. Add OHP as 5/3/1 with a 2.5 kg cycle increment.
5. Add chest-supported row, curls, triceps, RDLs, etc. as manual exercises.
6. Open **Templates** and build your training days from those exercises.
7. Return to **Home**, open **Manage schedule & reminders**, assign your Wednesday/Friday/Saturday/Sunday templates, and save the reminder times.
8. Allow notifications when Android prompts you.
9. Start workouts from Home as normal.

## Build-validation note

The pure Kotlin calculation/model code was syntax-checked in the generation environment. A full Android Gradle build could not be run there because the environment has no Android SDK or external dependency access. Android Studio on a normal connected development machine performs that final Android build/sync step.

## If you previously opened the Gradle 9.6 build

Use this fixed project from a **new unzipped folder** rather than copying it over the old folder. This avoids stale `.gradle` / `.idea` model state from the earlier Gradle 9.6 project. In Android Studio, set **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** to **JDK 17 or JDK 21** (do not use JDK 25 with Gradle 8.9), then run **File > Sync Project with Gradle Files**.

If Android Studio still shows the old `DefaultDecoratedConvention` import error, close the project, delete only the project-local `.gradle` and `.idea` folders, reopen the project, and sync again.
