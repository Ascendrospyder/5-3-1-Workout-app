@echo off
setlocal
set "APP_HOME=%~dp0"
set "JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "JAR_URL=https://services.gradle.org/distributions/gradle-8.9-wrapper.jar"
set "SHA_URL=https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256"

if not exist "%JAR%" (
  echo Gradle wrapper JAR is not bundled; downloading the official Gradle 8.9 wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%JAR_URL%' -OutFile '%JAR%'; $expected=(Invoke-RestMethod -Uri '%SHA_URL%').Trim().ToLower(); $actual=(Get-FileHash -Algorithm SHA256 '%JAR%').Hash.ToLower(); if ($actual -ne $expected) { Remove-Item -Force '%JAR%'; throw 'Gradle wrapper checksum mismatch.' }"
  if errorlevel 1 exit /b 1
)

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
