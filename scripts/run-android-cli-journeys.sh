#!/usr/bin/env bash
# Runs every androidApp/src/journeysTest/*.journey.xml on apksim, or on a connected device or emulator.
#
# Android CLI journeys have no runner of their own: an agent reads the journey, drives the device
# with `android layout`, `android screen` and `adb shell input` as the android-cli skill describes,
# and writes a markdown result with a ✅ or ❌ per action. This script does that with Claude Code in
# headless mode, one journey at a time from a freshly installed app, and fails unless every action
# of every journey passed.
#
# Where the journeys run:
# - apksim (https://github.com/aglose/apksim), when APKSIM is set: the command that starts an
#   instance, such as `apksim` on a Mac or, on Linux, `docker run --rm --network host -v "$PWD:/work"
#   ghcr.io/aglose/apksim:0.5.0`. Each journey gets an instance of its own, started with no app data
#   and stopped after it, so APK must be the debug APK's path as that command sees it.
# - Otherwise the device adb already has (one emulator or phone), with the debug APK installed.
#
# Needs: adb, the Android CLI (`android`), Claude Code (`claude`) signed in (or
# CLAUDE_CODE_OAUTH_TOKEN set, as on CI), and the fake Frigate at FRIGATE_URL as the app sees it:
# http://localhost:8971 on apksim (bound to 127.0.0.1), http://10.0.2.2:8971 on an emulator. See
# docs/testing.md.
set -uo pipefail

APP_ID="${APP_ID:-com.meticulouscreations.homesafe.debug}"
OUT="${OUT:-build/journeys}"
APKSIM="${APKSIM:-}"
APK="${APK:-androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
APKSIM_ADB_PORT="${APKSIM_ADB_PORT:-15037}"
if [ -n "$APKSIM" ]; then
  FRIGATE_URL="${FRIGATE_URL:-http://localhost:8971}"
else
  FRIGATE_URL="${FRIGATE_URL:-http://10.0.2.2:8971}"
fi
JOURNEYS=("${@:-androidApp/src/journeysTest/*.journey.xml}")
mkdir -p "$OUT"

# The skill holds the rules for judging a journey. It goes under the working directory because a
# headless agent can't read outside it, which is where `android init` puts the skill on a machine
# with no agent folders yet. An agent without the rules guesses or refuses, so stop here instead.
SKILL_ROOT="$OUT/android-cli"
SKILL="$SKILL_ROOT/.claude/skills/android-cli/SKILL.md"
mkdir -p "$SKILL_ROOT"
android skills add --agent=claude-code --project="$SKILL_ROOT" android-cli > /dev/null
if [ ! -f "$SKILL" ]; then
  echo "FAIL  couldn't install the android-cli skill to $SKILL"
  exit 1
fi

# The journeys type the emulator's address for the fake Frigate. Anywhere else the agent reads a
# copy that types FRIGATE_URL instead.
journey_for() {
  local journey="$1" copy
  if [ "$FRIGATE_URL" = "http://10.0.2.2:8971" ]; then
    echo "$journey"
    return
  fi
  copy="$OUT/journeys/$(basename "$journey")"
  mkdir -p "$OUT/journeys"
  sed "s#http://10\.0\.2\.2:8971#$FRIGATE_URL#g" "$journey" > "$copy"
  echo "$copy"
}

# apksim: an instance of its own for one journey. It starts with no app data (`--fresh`), since
# apksim has no `pm clear`, and draws a soft keyboard, so the Back that hides the keyboard closes
# the keyboard: without one, Back on the app's last screen quits the instance. On a Mac it reaches
# nothing but localhost (`--no-network`, macOS-only; on CI the container shares the runner's
# network, which has no route to the household's server).
start_apksim() {
  local name="$1" args=(--headless --fresh --soft-keyboard --adb "$APKSIM_ADB_PORT")
  [ "$(uname)" = Darwin ] && args+=(--no-network)
  # shellcheck disable=SC2086 # APKSIM is a command line
  $APKSIM "${args[@]}" "$APK" > "$OUT/$name.apksim.log" 2>&1 &
  APKSIM_PID=$!
  export ANDROID_SERIAL="localhost:$APKSIM_ADB_PORT"
  for _ in $(seq 1 120); do
    if ! kill -0 "$APKSIM_PID" 2> /dev/null; then
      echo "$name: apksim exited before its first frame; see $OUT/$name.apksim.log"
      return 1
    fi
    adb connect "$ANDROID_SERIAL" > /dev/null 2>&1
    [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ] && return 0
    sleep 1
  done
  echo "$name: apksim's adb device never came up; see $OUT/$name.apksim.log"
  return 1
}

stop_apksim() {
  adb shell apksim quit > /dev/null 2>&1
  for _ in $(seq 1 20); do
    kill -0 "$APKSIM_PID" 2> /dev/null || break
    sleep 0.5
  done
  kill "$APKSIM_PID" 2> /dev/null
  wait "$APKSIM_PID" 2> /dev/null
  adb disconnect "$ANDROID_SERIAL" > /dev/null 2>&1
}

# On a device: every journey starts signed out, on the app's first screen.
reset_device_app() {
  local name="$1"
  adb shell pm clear "$APP_ID" > /dev/null
  adb logcat -c
  # `pm clear` tears the old task down in the background, and that can kill the process of a launch
  # that comes too soon, which leaves the splash icon up with no app behind it. A launch after that
  # stays up. An app that never does is the app's failure, for the agent to report.
  for attempt in 1 2 3 4 5; do
    adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 > /dev/null
    sleep 5
    adb shell pidof "$APP_ID" > /dev/null && break
    echo "$name: no app process after launch $attempt"
  done
}

[ -z "$APKSIM" ] && adb logcat -G 16M
rm -f "$OUT/summary.tsv"
failed=0
for journey in ${JOURNEYS[@]}; do
  name="$(basename "$journey" .journey.xml)"
  result="$OUT/$name.md"
  rm -f "$result"
  run="$(journey_for "$journey")"

  # The app's logcat goes beside the result, because an agent is told to report an app that dies,
  # not to find out why.
  if [ -n "$APKSIM" ]; then
    if ! start_apksim "$name"; then
      echo "FAIL  $name (apksim didn't start)"
      printf '%s\tfail\t0\t%s\t0\n' "$name" "$(grep -c '<action>' "$run")" >> "$OUT/summary.tsv"
      failed=1
      stop_apksim
      continue
    fi
  else
    reset_device_app "$name"
  fi

  started=$SECONDS
  claude -p "Run the Android CLI journey in $run on the connected device. The app $APP_ID is already open in the foreground: do not reinstall, clear or relaunch it. Read $SKILL first, and evaluate the journey exactly as its section on running journey tests describes, driving the device only with the android CLI (android layout, android screen capture and resolve) and adb shell input. The page moves when the keyboard opens, so read android layout again before each tap. It also lists what the keyboard covers, so if the keyboard is over something an action needs, hide it first with adb shell input keyevent KEYCODE_BACK; that is not a failure. Write the result to $result in the skill's markdown format: one '### Action:' heading per <action> in order, holding that action's text word for word followed by ✅ if it succeeded or ❌ if it failed, and stop at the first failure. An action that lists several things is still one heading: report its steps in the bullets under it." \
    --allowedTools "Bash(android *)" "Bash(adb *)" "Read" "Write" \
    > "$OUT/$name.log" 2>&1
  agent=$?
  took=$((SECONDS - started))
  adb logcat -d > "$OUT/$name.logcat"
  [ -n "$APKSIM" ] && stop_apksim

  expected="$(grep -c '<action>' "$run")"
  passed="$( { grep -E '^### Action' "$result" 2> /dev/null || true; } | grep -c '✅')"
  # The passed headings must be the journey's actions, in order. A count isn't enough: a split
  # action adds headings, which could make up for one that never ran.
  actions="$(sed -n 's:.*<action>\(.*\)</action>.*:\1:p' "$run")"
  reported="$( { grep -E '^### Action:.*✅' "$result" 2> /dev/null || true; } | sed -E 's/^### Action: *//; s/ *✅ *//')"
  if [ "$agent" -eq 0 ] && [ -f "$result" ] && ! grep -q '❌' "$result" && [ "$reported" = "$actions" ]; then
    echo "PASS  $name ($passed/$expected actions, ${took}s)"
    outcome=pass
  else
    echo "FAIL  $name ($passed/$expected actions, ${took}s) — see $result and $OUT/$name.log"
    outcome=fail
    failed=1
  fi
  # One line per journey, for comparing one run with another (scripts/ci/compare-journeys.py).
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$outcome" "$passed" "$expected" "$took" >> "$OUT/summary.tsv"
done

exit "$failed"
