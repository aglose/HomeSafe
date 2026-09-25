#!/usr/bin/env bash
# Runs the instrumented suites on the connected emulator and makes a hang diagnosable from the
# CI log alone. The connected test tasks print nothing per test while they run, and a test that
# blocks the main thread can't be interrupted, so a hung suite would otherwise sit silent until
# the job's timeout with no clue where it stopped.
#
# - Gradle runs under a time limit shorter than the job's, so there is time left to look.
# - On a timeout, every Java thread of each app and test process is dumped (debuggerd -j), after
#   the journey watchdog's own dump (it writes to System.err, which Android sends to logcat).
# - Either way, the test runner's start/finish lines show which test was running last.
#
# Usage: scripts/run-instrumented-tests.sh [minutes] -- <gradle tasks and flags>
set -uo pipefail

limit_minutes=${1:-45}
shift
[ "${1:-}" = "--" ] && shift

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then
  adb() { "$sdk/platform-tools/adb" "$@"; }
fi

adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -c || true

timeout --signal=TERM --kill-after=60 "${limit_minutes}m" ./gradlew "$@"
status=$?

if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
  echo "::error::Instrumented tests were still running after ${limit_minutes} minutes."
  echo "::group::Java threads of the app and test processes"
  adb root >/dev/null 2>&1 && adb wait-for-device
  for pid in $(adb shell "ps -A -o PID,NAME" | awk '/meticulouscreations/ {print $1}'); do
    echo "=== pid $pid: $(adb shell cat /proc/"$pid"/cmdline | tr '\0' ' ')"
    adb shell debuggerd -j "$pid" || echo "(debuggerd -j $pid failed)"
  done
  echo "::endgroup::"
  echo "::group::Journey watchdog and other System.err output"
  adb logcat -d -s System.err:W | tail -n 400
  echo "::endgroup::"
fi

echo "::group::Last tests the runner started and finished"
adb logcat -d -s TestRunner:I | grep -E "started:|finished:|failed:" | tail -n 40
echo "::endgroup::"

exit "$status"
