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

# The device's clock when the recording starts: only an in-process file written after it belongs to
# this recording (an older one may be from another build, or from before a tracing build was
# replaced). Nothing is deleted, since a running process may hold its file open (idle.py reuses one).
started=$(adb shell date +%s | tr -d '\r')

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
newest=$(adb shell "stat -c '%Y %n' $dir/* 2> /dev/null" | tr -d '\r' | sort -n | tail -1)
if [ -n "$newest" ] && [ "${newest%% *}" -ge "$started" ]; then
  adb pull "${newest#* }" "$OUT.inproc.pftrace" > /dev/null && echo "in-process: $OUT.inproc.pftrace"
else
  rm -f "$OUT.inproc.pftrace"
  echo "no in-process trace written during this recording (a build without -Phomesafe.tracing=true?)"
fi
echo "system: $OUT.pftrace"
