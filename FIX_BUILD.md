# Fix for `DefaultDecoratedConvention` build/import error

The original package used AGP 9.4 + Gradle 9.6. That pairing is valid in current Android tooling, but an older IDE Gradle import layer can fail before project configuration with:

```text
org.gradle.api.plugins.internal.DefaultDecoratedConvention
```

This package is repinned to a broadly compatible toolchain:

- Android Gradle Plugin 8.7.2
- Gradle 8.9
- Kotlin Android plugin 2.1.20
- compileSdk / targetSdk 35
- Java target 17

## Cleanest way to open it

1. Close the old project in Android Studio.
2. Unzip this fixed project into a **new folder**.
3. Open the folder that contains `settings.gradle.kts`.
4. In Android Studio go to **Settings > Build, Execution, Deployment > Build Tools > Gradle**.
5. Set **Gradle JDK** to Android Studio's Embedded JDK (JDK 17 or newer).
6. Run **File > Sync Project with Gradle Files**.
7. Allow Android Studio to install Android SDK Platform 35 if prompted.
8. Run the `app` configuration.

## If the same error is cached

Close Android Studio and delete the project-local `.gradle` and `.idea` directories only. Reopen the fixed project and sync. Do not delete your source files.

## Command-line diagnostic

From the fixed project root on Windows:

```powershell
.\gradlew.bat --version
.\gradlew.bat clean assembleDebug --stacktrace
```

The first command should report **Gradle 8.9**.
