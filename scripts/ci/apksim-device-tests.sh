#!/bin/bash
# Runs every module's device tests, and the end-to-end tests, on apksim
# (https://github.com/aglose/apksim) instead of an emulator: Android's framework on the JVM, in
# apksim's Linux image. The same APKs the android-ui job installs, built beforehand with
#   ./gradlew assembleAndroidDeviceTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
#
#   scripts/ci/apksim-device-tests.sh APKSIM_CHECKOUT IMAGE OUT
#
# Writes JUnit XML and a log per suite to OUT, and a table of tests and times to stdout (and to the
# job summary on GitHub). Exits 1 if any test failed. Each test APK of a module instruments
# itself, so it is passed as both APKs; the whole-app journeys and the end-to-end tests run one
# process per test, four at a time, as the Orchestrator does.
set -uo pipefail
APKSIM="$(cd "$1" && pwd)" IMAGE="$2"
mkdir -p "$3"
OUT="$(cd "$3" && pwd)"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JOURNEYS=com.meticulouscreations.homesafe.integration
# apksim's runner starts the image once per process, with the repository as its working directory.
LAUNCH="docker run --rm -v $ROOT:/work $IMAGE"
cd "$ROOT"

failed=0
table="| Suite | Result | Wall time |
|---|---|---|"
run() { # name app test [instrument.py arguments...]
  local name=$1 app=$2 test=$3; shift 3
  local start=$SECONDS
  python3 "$APKSIM/scripts/instrument.py" --apksim "$LAUNCH" "$app" "$test" --xml "$OUT/$name.xml" "$@" > "$OUT/$name.log" 2>&1 || failed=1
  local result
  result="$(tail -1 "$OUT/$name.log")"
  echo "$name: $result [$((SECONDS - start)) s]"
  table+="
| $name | $result | $((SECONDS - start)) s |"
}

total=$SECONDS
apk=shared/build/outputs/apk/androidTest/shared-androidTest.apk
run shared $apk $apk
for module in features/*/; do
  module=$(basename "$module")
  apk=features/$module/build/outputs/apk/androidTest/$module-androidTest.apk
  [ -f "$apk" ] && run "$module" $apk $apk
done
apk=app/build/outputs/apk/androidTest/app-androidTest.apk
run app $apk $apk -e notPackage $JOURNEYS
run journeys $apk $apk -e package $JOURNEYS --isolate -j 4
run e2e androidApp/build/outputs/apk/debug/androidApp-debug.apk androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk --isolate -j 4
echo "all suites: $((SECONDS - total)) s"
table+="
| all | | $((SECONDS - total)) s |"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '### Device tests on apksim\n\n%s\n' "$table" >> "$GITHUB_STEP_SUMMARY"
fi
# Each failure, with its message and stack, in the log.
for xml in "$OUT"/*.xml; do
  python3 - "$xml" <<'EOF'
import html, re, sys
text = open(sys.argv[1], encoding="utf-8").read()
for cls, name, body in re.findall(r'<testcase classname="([^"]*)" name="([^"]*)"[^>]*><failure[^>]*>(.*?)</failure>', text, re.S):
    print(f"\nFAILED {cls}#{name}\n{html.unescape(body)[:4000]}")
EOF
done
exit $failed
