#!/usr/bin/env bash
# Runs every androidApp/src/journeysTest/*.journey.xml on the connected device or emulator.
#
# Android CLI journeys have no runner of their own: an agent reads the journey, drives the device
# with `android layout`, `android screen` and `adb shell input` as the android-cli skill's journeys
# reference describes, and writes a markdown result with a ✅ or ❌ per action. This script does
# that with Claude Code in headless mode, one journey at a time from a freshly cleared app, and
# fails unless every action of every journey passed.
#
# Needs: adb and a booted device, the Android CLI (`android`) with its skill installed
# (`android init`), Claude Code (`claude`) with ANTHROPIC_API_KEY, the debug APK installed, and the
# fake Frigate reachable from the device at http://10.0.2.2:8971 — see docs/testing.md.
set -uo pipefail

APP_ID="${APP_ID:-com.meticulouscreations.homesafe.debug}"
OUT="${OUT:-build/journeys}"
JOURNEYS=("${@:-androidApp/src/journeysTest/*.journey.xml}")
mkdir -p "$OUT"

failed=0
for journey in ${JOURNEYS[@]}; do
  name="$(basename "$journey" .journey.xml)"
  result="$OUT/$name.md"
  rm -f "$result"

  # Every journey starts signed out, on the app's first screen.
  adb shell pm clear "$APP_ID" > /dev/null
  adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 > /dev/null
  sleep 5

  claude -p "Run the Android CLI journey in $journey on the connected device. The app $APP_ID is already open in the foreground: do not reinstall, clear or relaunch it. Evaluate the journey exactly as the android-cli skill's journeys reference describes, driving the device only with the android CLI (android layout, android screen capture and resolve) and adb shell input. Write the result to $result in the skill's markdown format: one '### Action:' heading per action in order, each marked ✅ if it succeeded or ❌ if it failed, and stop at the first failure." \
    --allowedTools "Bash(android *)" "Bash(adb *)" "Read" "Write" \
    > "$OUT/$name.log" 2>&1

  expected="$(grep -c '<action>' "$journey")"
  passed="$( { grep -E '^### Action' "$result" 2> /dev/null || true; } | grep -c '✅')"
  if [ -f "$result" ] && ! grep -q '❌' "$result" && [ "$passed" -eq "$expected" ]; then
    echo "PASS  $name ($passed/$expected actions)"
  else
    echo "FAIL  $name ($passed/$expected actions) — see $result and $OUT/$name.log"
    failed=1
  fi
done

exit "$failed"
