#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
RAW_URL="https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

if [ ! -f "$JAR" ]; then
  echo "Gradle wrapper JAR is not bundled; downloading the official Gradle 9.6.0 wrapper..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$RAW_URL" -o "$JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$JAR" "$RAW_URL"
  else
    echo "Install curl or wget, then rerun ./gradlew" >&2
    exit 1
  fi
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA=$(sha256sum "$JAR" | awk '{print $1}')
  if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    echo "Gradle wrapper checksum mismatch." >&2
    rm -f "$JAR"
    exit 1
  fi
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

exec "$JAVA" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
