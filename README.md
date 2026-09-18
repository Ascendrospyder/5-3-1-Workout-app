# LiftLog — flexible 5/3/1 workout tracker

A small native Android/Kotlin lifting app with reusable workout templates, a custom exercise library, live workout logging, timers, history, and a 12-week training heatmap.

The app does **not** hardcode OHP, bench, squat, rows, or accessories. You create the exercises you want and decide whether each exercise uses 5/3/1 calculations or a manual prescription.

## What this version does

### Exercise library

Create any exercise you want. Each exercise has one of two modes:

- **5/3/1** — stores a Training Max, rounding increment, and per-cycle TM increase. When a workout starts, the app automatically generates that week's 3 main sets and their weights.
- **Manual** — stores a default weight, number of sets, and rep range. Good for rows, curls, triceps, lateral raises, RDLs, etc.

For a 5/3/1 exercise you can enter a 1RM and Training Max percentage and press **Use 1RM × Training Max %** to calculate the starting Training Max. You can also directly edit the current Training Max.

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

### Workout history

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

### Dashboard

The dashboard contains:

- active-workout resume card
- current 5/3/1 cycle and week
- buttons to start each workout template
- a small **12-week heatmap** based on completed workout dates
- recent workout summaries

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
- `WorkoutDb.kt` — SQLite persistence for exercises, templates, workouts and sets
- `Models.kt` — data models
- `ProgramMath.kt` — 5/3/1 and weight-rounding calculations
- `HeatmapView.kt` — custom 12-week dashboard heatmap
- `ProgramMathTest.kt` — calculation unit tests

The project deliberately uses Android's built-in UI widgets and SQLite APIs, so there are very few runtime dependencies.

## Toolchain

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- compileSdk / targetSdk 37
- minSdk 26
- Java 17 bytecode target

## Open the project in Android Studio

1. Install Android Studio.
2. Unzip the downloaded project.
3. Open the folder containing `settings.gradle.kts` in Android Studio.
4. Let Gradle Sync run.
5. If Android Studio asks to install Android SDK Platform 37 or build tools, accept it.

The supplied wrapper bootstrap script downloads the Gradle wrapper JAR the first time it is used. Your computer therefore needs internet access for the initial Gradle/Android dependency setup.

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
7. Return to **Home** and start the workout from its template.

## Build-validation note

The pure Kotlin calculation code was syntax-checked in the generation environment. A full Android Gradle build could not be run there because the environment has no Android SDK and cannot download the missing Gradle wrapper JAR. Android Studio on a normal connected development machine performs that final Android build/sync step.
