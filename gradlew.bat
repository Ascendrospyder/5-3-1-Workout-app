@echo off
setlocal
set "APP_HOME=%~dp0"
set "JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "RAW_URL=https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar"
set "EXPECTED_SHA=497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

if not exist "%JAR%" (
  echo Gradle wrapper JAR is not bundled; downloading the official Gradle 9.6.0 wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%RAW_URL%' -OutFile '%JAR%'"
  if errorlevel 1 exit /b 1
)

for /f "tokens=*" %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%JAR%').Hash.ToLower()"') do set "ACTUAL_SHA=%%H"
if /I not "%ACTUAL_SHA%"=="%EXPECTED_SHA%" (
  echo Gradle wrapper checksum mismatch.
  del /q "%JAR%" 2>nul
  exit /b 1
)

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
