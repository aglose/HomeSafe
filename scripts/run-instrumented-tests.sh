#!/usr/bin/env bash
# Runs the instrumented suites on the connected emulator and makes a hang diagnosable from the
# CI log alone. The connected test tasks print nothing per test while they run, and a test that
# blocks the main thread can't be interrupted, so a hung suite would otherwise sit silent until
# the job's timeout with no clue where it stopped.
#
# - Before Gradle starts, the emulator is given time to finish what it began at boot (settle), and
#   for as long as the tests run a system dialog that takes the screen is answered (the watch
#   started by watch_for_system_dialogs).
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

# What has the screen, as dumpsys names it: "mCurrentFocus=Window{1a2b3c u0 <title>}". Read into a
# variable and matched there, never piped into `grep -q` or `head`: those stop reading at the first
# line, adb dies of SIGPIPE, and under pipefail a match then reads as "not found".
current_focus() {
  adb shell dumpsys window 2>/dev/null | grep 'mCurrentFocus=' || true
}

# A system "isn't responding" or "keeps stopping" dialog keeps window focus until somebody answers
# it, and on a runner nobody does. The emulator's launcher has been declared not responding half a
# minute after boot, while Gradle had the runner's processors, and every end-to-end test of that
# run then failed ten minutes later with "Waited for the root of the view hierarchy to have window
# focus". So the screen is looked at every few seconds, and such a dialog is closed: asked to
# first, and if it is still there its process is stopped, which takes its dialogs with it (the
# launcher comes back by itself). A dialog about the app or its tests is left where it is. That
# one is a finding, and the tests it covers should fail on it.
watch_for_system_dialogs() {
  while sleep 5; do
    focus=$(current_focus)
    [[ "$focus" =~ Application\ (Not\ Responding|Error):\ ([^}\ ]+) ]] || continue
    package=${BASH_REMATCH[2]}
    case "$package" in
      com.meticulouscreations.*) continue ;;
    esac
    echo "::warning::A system dialog about $package had the emulator's screen; closing it."
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    sleep 2
    focus=$(current_focus)
    if [[ "$focus" == *"Application "*": $package"* ]]; then
      echo "It stayed, so $package is stopped."
      adb shell am force-stop "$package" || true
    fi
  done
}

# The emulator says it has booted well before it has stopped starting things. Resumed from its
# snapshot, it spends the next minute or two on Google's own start-up work (queued broadcasts,
# Play services' configuration, downloads), and a suite that starts in the middle of that has had
# its first test's activity recreated under it. Gradle starting at the same moment takes the
# processors that work needs. So first: up to a minute for the broadcast queue to drain, then up
# to another for the launcher to be in front and the screen to hold still long enough for
# uiautomator to read it. Carries on regardless; the tests' own failures say more than this could.
settle() {
  started=$SECONDS
  adb shell 'timeout 60 am wait-for-broadcast-idle' >/dev/null 2>&1 || true
  for _ in $(seq 30); do
    focus=$(current_focus)
    if [[ "$focus" == *[Ll]auncher* && "$focus" != *"Application "* ]] \
      && [ "$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -c '<hierarchy' || true)" -gt 0 ]; then
      echo "The emulator settled in $((SECONDS - started)) seconds."
      return 0
    fi
    sleep 2
  done
  echo "::warning::The emulator had not settled after $((SECONDS - started)) seconds; running the tests anyway."
}

watch_for_system_dialogs &
dialog_watch=$!
settle

timeout --signal=TERM --kill-after=60 "${limit_minutes}m" ./gradlew "$@"
status=$?

kill "$dialog_watch" 2>/dev/null
wait "$dialog_watch" 2>/dev/null

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

if [ "$status" -ne 0 ]; then
  # A run where every end-to-end test fails with "Waited for the root of the view hierarchy to
  # have window focus" means something else held the screen: usually a system "isn't responding"
  # or "keeps stopping" dialog on the emulator. Show what took focus and any ANRs, so a failure
  # like that names its cause instead of looking like the app broke.
  echo "::group::System dialogs, ANRs and window focus changes"
  adb logcat -d -b main,system,crash -v time \
    | grep -E "ANR in|not responding|isn't responding|keeps stopping|AppErrorDialog|FATAL EXCEPTION|Changing focus from|mCurrentFocus|focus-request" \
    | tail -n 120
  adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" || true
  echo "::endgroup::"
fi

echo "::group::Last tests the runner started and finished"
adb logcat -d -s TestRunner:I | grep -E "started:|finished:|failed:" | tail -n 40
echo "::endgroup::"

exit "$status"
