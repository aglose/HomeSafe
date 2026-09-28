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
set -euo pipefail

BASE_APK=${1:?base apk}
HEAD_APK=${2:?head apk}
RUNS=${3:-4}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
PACKAGE=com.meticulouscreations.homesafe

bench() {
  python3 scripts/bench-first-live-pixel.py --serial "$SERIAL" --package "$PACKAGE" \
    --cameras 1 --timeout 30 --settle 1 --pause 3 "$@"
}

# The milestones arrived with the change that introduced this gate; a base from before it can
# only have its cold launch timed.
BASE_MODE=()
if ! unzip -p "$BASE_APK" 'classes*.dex' | grep -aq HomeSafeTTFP; then
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
    bench --runs 1 --label "warmup-$variant" "${mode[@]}" | grep -E '^run' || true
    bench --runs "$RUNS" --label "$variant" "${mode[@]}" | grep -E '^run'
  done
}

abba
# A regression that shows up once may be the runner; one that survives twice the samples is not.
if ! python3 scripts/bench-first-live-pixel.py --gate base head; then
  echo "== over the limit; measuring another round before deciding"
  abba
  python3 scripts/bench-first-live-pixel.py --gate base head
fi
