#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if command -v pebble >/dev/null; then
  (cd watch && pebble build)
else
  echo 'SKIP Pebble: install Pebble SDK 4.9.169+ and pebble CLI to create a .pbw'
fi
if command -v gradle >/dev/null; then
  (cd android && gradle --no-daemon :app:assembleDebug)
elif [[ -x android/gradlew ]]; then
  (cd android && ./gradlew --no-daemon :app:assembleDebug)
else
  echo 'SKIP Android: use Android Studio or install Gradle 8.13 + Android SDK 36'
fi
