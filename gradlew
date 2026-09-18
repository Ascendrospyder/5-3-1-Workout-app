#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
JAR_URL="https://services.gradle.org/distributions/gradle-8.9-wrapper.jar"
SHA_URL="https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256"

if [ ! -f "$JAR" ]; then
  echo "Gradle wrapper JAR is not bundled; downloading the official Gradle 8.9 wrapper..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$JAR_URL" -o "$JAR"
    EXPECTED_SHA=$(curl -fsL "$SHA_URL" | tr -d '\r\n ')
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$JAR" "$JAR_URL"
    EXPECTED_SHA=$(wget -qO- "$SHA_URL" | tr -d '\r\n ')
  else
    echo "Install curl or wget, then rerun ./gradlew" >&2
    exit 1
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA=$(sha256sum "$JAR" | awk '{print $1}')
    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
      echo "Gradle wrapper checksum mismatch." >&2
      rm -f "$JAR"
      exit 1
    fi
  fi
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

exec "$JAVA" -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
