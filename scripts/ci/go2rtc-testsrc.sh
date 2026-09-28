#!/usr/bin/env bash
# Starts a go2rtc on this machine that serves ffmpeg test patterns under the fake Frigate's
# stream names, so an emulator signed in to the fake (at 10.0.2.2) gets real live video over
# WebRTC and HLS instead of a failed join. The fake Frigate deliberately has no go2rtc of its own.
#
#   scripts/ci/go2rtc-testsrc.sh <log-dir> [stream ...]
#
# Streams default to the fake household's cameras (FakeFrigateState.household()): front_door,
# back_yard, and driveway with its separate driveway_sub grid stream. Needs ffmpeg with libx264.
# The API listens on 1984 and WebRTC on 127.0.0.1:8555, advertising 10.0.2.2 — the host as the emulator
# sees it. Exits non-zero unless /api/streams lists every stream.
set -euo pipefail

# The release baseline-profile.yml pins too; its linux_amd64 asset's checksum is verified below.
GO2RTC_VERSION=1.9.14
GO2RTC_SHA256=32d616af226bd731678ffde328b94cfb94e30339bfefc469cfb76323144615a6
LOG_DIR=${1:?log dir}
shift
STREAMS=("$@")
[ ${#STREAMS[@]} -gt 0 ] || STREAMS=(front_door back_yard driveway driveway_sub)

mkdir -p "$LOG_DIR"
# GO2RTC_BIN runs a go2rtc already on this machine instead (a Mac rehearsing the CI job, say).
BIN=${GO2RTC_BIN:-$LOG_DIR/go2rtc}
if [ ! -x "$BIN" ]; then
  curl -fsSL -o "$BIN" "https://github.com/AlexxIT/go2rtc/releases/download/v${GO2RTC_VERSION}/go2rtc_linux_amd64"
  echo "$GO2RTC_SHA256  $BIN" | sha256sum --check --quiet || { echo "::error::go2rtc download failed its checksum"; rm -f "$BIN"; exit 1; }
  chmod +x "$BIN"
fi

# A one-second keyframe interval, like the real sub streams; baseline H.264 so every decoder takes it.
SOURCE='exec:ffmpeg -hide_banner -loglevel error -re -f lavfi -i testsrc2=size=640x360:rate=15 -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p -g 15 -f rtsp {output}'
{
  echo 'api: { listen: ":1984" }'
  echo 'rtsp: { listen: ":8554" }'
  # Loopback, not ":8555": the emulator's 10.0.2.2 is this machine's loopback, and for a bare
  # port go2rtc binds UDP on the non-loopback interfaces only.
  echo 'webrtc: { listen: "127.0.0.1:8555", candidates: ["10.0.2.2:8555"], ice_servers: [] }'
  echo 'streams:'
  for s in "${STREAMS[@]}"; do echo "  $s: \"$SOURCE\""; done
} > "$LOG_DIR/go2rtc.yaml"

nohup "$BIN" -config "$LOG_DIR/go2rtc.yaml" > "$LOG_DIR/go2rtc.log" 2>&1 &
for _ in $(seq 1 30); do
  if LISTED=$(curl -sf http://127.0.0.1:1984/api/streams); then
    for s in "${STREAMS[@]}"; do
      python3 -c 'import json,sys; sys.exit(0 if sys.argv[1] in json.load(sys.stdin) else 1)' "$s" <<<"$LISTED" \
        || { echo "::error::go2rtc is up but does not list $s"; cat "$LOG_DIR/go2rtc.log"; exit 1; }
    done
    echo "go2rtc serving: ${STREAMS[*]}"
    exit 0
  fi
  sleep 1
done
echo "::error::go2rtc did not start"; cat "$LOG_DIR/go2rtc.log"; exit 1
