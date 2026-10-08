#!/usr/bin/env bash
# CI's cold-launch regression gate: times the base branch's build and this branch's on one
# emulator, interleaved, and fails if this branch's medians are slower by more than the allowed
# margin (see GATED in scripts/bench-first-live-pixel.py and docs/webrtc-live.md).
#
#   scripts/ci/startup-perf.sh <base.apk> <head.apk> [runs-per-block]
#
# Both APKs are benchmarkRelease builds carrying the test credentials for the fake Frigate that
# must already be serving at 10.0.2.2:8971, with go2rtc test patterns on :1984
# (scripts/ci/go2rtc-testsrc.sh). Runs in ABBA blocks — base, head, head, base — so drift over
# the job (a warming emulator, a busy runner) lands on both builds alike. Every block starts with
# an install and a discarded run: the first launch after an install is compiling, not starting.
#
# The emulator says it has booted well before it has stopped starting things, and on a busy
# runner the first block could find the sign-in screen missing or covered. So: wait for the
# screen to be readable before the first install (settle), try again at a run whose sign-in
# screen never appeared (--retries: nothing was timed, so nothing is biased), and when the job
# fails anyway leave what was on the screen in build/startup-perf/ (diagnose).
set -euo pipefail

BASE_APK=${1:?base apk}
HEAD_APK=${2:?head apk}
RUNS=${3:-4}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
PACKAGE=com.meticulouscreations.homesafe

# Every run starts from cold streams: the last run's consumers linger on go2rtc until their
# connections time out, so wait for it to have none (the emulator's arrive from 127.0.0.1) and for
# go2rtc to stop the test-pattern producers, rather than trusting a fixed pause.
bench() {
  python3 scripts/bench-first-live-pixel.py --serial "$SERIAL" --package "$PACKAGE" \
    --cameras 1 --timeout 30 --settle 1 --pause 0 --retries 2 \
    --go2rtc http://127.0.0.1:1984 --go2rtc-count-local --idle-extra 2 "$@"
}

# Up to two minutes for the launcher to be in front and the screen to hold still long enough for
# uiautomator to read it, which is what every run's first step needs. Carries on regardless: the
# runs have their own retries, and their own errors say more than this could.
settle() {
  # System "isn't responding" dialogs about the emulator's own processes are the runner being
  # slow, not the app; they would sit on top of the screen the runs read.
  adb -s "$SERIAL" shell settings put global hide_error_dialogs 1 || true
  for _ in $(seq 60); do
    # Counted, not `grep -q`, for the reason given at BASE_MODE below.
    if [ "$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -c 'mCurrentFocus=.*[Ll]auncher' || true)" -gt 0 ] \
      && [ "$(adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null | grep -c '<hierarchy' || true)" -gt 0 ]; then
      return 0
    fi
    sleep 2
  done
  echo "::warning::The emulator's launcher was not readable after two minutes; measuring anyway."
}

# What the screen held when the job failed, for the startup-perf artifact and the log: a timeout
# on a hosted emulator is otherwise a line of text about a screen nobody saw.
diagnose() {
  status=$?
  [ "$status" -eq 0 ] && return
  mkdir -p build/startup-perf
  echo "::group::The emulator when the comparison failed"
  adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' || true
  adb -s "$SERIAL" exec-out screencap -p > build/startup-perf/failure-screen.png 2>/dev/null || true
  # uiautomator writes "UI hierchary dumped to: /dev/tty" after the document; keep what ends at the
  # last tag (as screen() in bench-first-live-pixel.py does), so the file opens as XML.
  adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null | sed -n 's/\(.*>\).*/\1/p' > build/startup-perf/failure-screen.xml || true
  adb -s "$SERIAL" logcat -d -b main,system,crash -v time > build/startup-perf/failure-logcat.log 2>/dev/null || true
  grep -E "ANR in|isn't responding|keeps stopping|FATAL EXCEPTION|AndroidRuntime: " build/startup-perf/failure-logcat.log | tail -n 60 || true
  echo "::endgroup::"
}
trap diagnose EXIT

# The milestones arrived with the change that introduced this gate; a base from before it can
# only have its cold launch timed.
BASE_MODE=()
# Counted, not `grep -q`: that exits at the first match, unzip dies of SIGPIPE, and under pipefail
# the pipeline then reads as "not found".
if [ "$(unzip -p "$BASE_APK" 'classes*.dex' | grep -ac HomeSafeTTFP || true)" -eq 0 ]; then
  BASE_MODE=(--launch-only)
  echo "::notice::The base build has no startup milestones; only its cold launch is compared."
fi

abba() {
  for variant in base head head base; do
    if [ "$variant" = base ]; then apk=$BASE_APK; mode=("${BASE_MODE[@]}"); else apk=$HEAD_APK; mode=(); fi
    echo "== $variant"
    # -d: the two builds share a versionCode, and either may be the "older" one.
    adb -s "$SERIAL" install -r -d "$apk" > /dev/null
    adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
    # The runs' own lines, and any try that had to be made again.
    bench --runs 1 --label "warmup-$variant" "${mode[@]}" | grep -E '^run|never started' || true
    bench --runs "$RUNS" --label "$variant" "${mode[@]}" | grep -E '^run|never started'
  done
}

settle
abba
# A regression that shows up once may be the runner; one that survives twice the samples is not.
if ! python3 scripts/bench-first-live-pixel.py --gate base head; then
  echo "== over the limit; measuring another round before deciding"
  abba
  python3 scripts/bench-first-live-pixel.py --gate base head
fi
