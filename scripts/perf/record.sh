#!/usr/bin/env bash
# Records one scripted journey: a system trace (Perfetto, journey.pftxt) and, from a build made
# with -Phomesafe.tracing=true, the app's in-process Tracing 2.0 file (Metro's binding spans, the
# app's traceSection/traceInstant). docs/perf-tracing.md explains both and how to read them.
#
#   scripts/perf/record.sh <out-prefix> [journey args…]
#
# The journey is scripts/perf/tour.py by default (JOURNEY=scripts/perf/idle.py for the at-rest
# walk); its arguments follow the prefix. Writes <out-prefix>.pftrace and, when the app wrote one,
# <out-prefix>.inproc.pftrace. Open both in ui.perfetto.dev with "Open multiple trace files", or
# give both to scripts/perf/trace_report.py. Concatenating them does not work: trace_processor
# drops the second file's events as out of order.
#
# Needs: adb with one device (ANDROID_SERIAL to pick), the app installed, root on the device to
# read the in-process file (`adb root`: any google_apis emulator image).
set -euo pipefail

OUT="$1"; shift
PKG="${PKG:-com.meticulouscreations.homesafe.benchmark}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JOURNEY="${JOURNEY:-$HERE/tour.py}"

sed "s/@PACKAGE@/$PKG/" "$HERE/journey.pftxt" | adb shell "cat > /data/local/tmp/journey.pftxt"
adb shell rm -f /data/misc/perfetto-traces/journey.pftrace
# On stdin: perfetto may not read a config file under /data/local/tmp (SELinux).
adb shell "cat /data/local/tmp/journey.pftxt | perfetto --txt -c - --background -o /data/misc/perfetto-traces/journey.pftrace" > /dev/null
sleep 2

python3 "$JOURNEY" "$PKG" "$@" || echo "journey failed; keeping the trace up to here"

# Home hides the app's UI, and HomeSafeApplication.onTrimMemory flushes its trace driver.
adb shell input keyevent HOME
sleep 4
adb shell pkill -INT perfetto || true
sleep 5
adb pull /data/misc/perfetto-traces/journey.pftrace "$OUT.pftrace" > /dev/null

dir="/data/data/$PKG/no_backup/perfetto_traces"
if newest=$(adb shell ls -t "$dir" 2> /dev/null | head -1 | tr -d '\r') && [ -n "$newest" ]; then
  adb pull "$dir/$newest" "$OUT.inproc.pftrace" > /dev/null && echo "in-process: $OUT.inproc.pftrace"
fi
echo "system: $OUT.pftrace"
