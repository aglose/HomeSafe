#!/usr/bin/env bash
# Tells the household Teslas to stream to the relay's telemetry server (docs/tesla.md, "Telemetry").
# The box prints the configuration, this Mac signs it with the Tesla application's private key
# (which never goes to the box), and the box hands the signature to Tesla with the linked account's
# token (which never leaves it). Run it again before the configuration lapses (TESLA_TELEMETRY_DAYS,
# 350) and after changing what is streamed (TESLA_TELEMETRY_FIELDS).
#
#   scripts/tesla-telemetry.sh          # sign and send
#   scripts/tesla-telemetry.sh status   # whether each car has taken it up, and the errors it reported
#
# The key is $TESLA_PRIVATE_KEY (default ~/.config/homesafe/tesla-private-key.pem). Tesla's
# `tesla-jws` signs; the first run builds it on the box in Go's image, for this Mac, so nothing is
# installed here.
set -euo pipefail

BOX=${HOMESAFE_BOX:-andrew@100.99.163.71}
KEY=${TESLA_PRIVATE_KEY:-$HOME/.config/homesafe/tesla-private-key.pem}
JWS=${TESLA_JWS:-$HOME/.config/homesafe/bin/tesla-jws}
VEHICLE_COMMAND=v0.4.1
box() { ssh -o ConnectTimeout=10 -o BatchMode=yes "$BOX" "$@"; }

if [ "${1:-}" = status ]; then
  box "docker exec homesafe-relay python relay.py tesla-telemetry-status"
  exit
fi
[ $# -eq 0 ] || { echo "usage: scripts/tesla-telemetry.sh [status]" >&2; exit 2; }
[ -r "$KEY" ] || { echo "no private key at $KEY (set TESLA_PRIVATE_KEY)" >&2; exit 1; }

if [ ! -x "$JWS" ]; then
  arch=$(uname -m)
  [ "$arch" = x86_64 ] && arch=amd64
  mkdir -p "$(dirname "$JWS")"
  echo "building tesla-jws $VEHICLE_COMMAND for darwin/$arch on the box" >&2
  box "docker run --rm -e GOOS=darwin -e GOARCH=$arch -e CGO_ENABLED=0 golang:1.24 sh -c \
    'go install github.com/teslamotors/vehicle-command/cmd/tesla-jws@$VEHICLE_COMMAND >&2 && cat /go/bin/darwin_$arch/tesla-jws'" > "$JWS.part"
  chmod +x "$JWS.part"
  mv "$JWS.part" "$JWS"
fi

config=$(box "docker exec homesafe-relay python relay.py tesla-telemetry-config")
token=$(printf '%s' "$config" | "$JWS" -key-file "$KEY" -fleet sign TelemetryClient)
printf '%s' "$token" | box "docker exec -i homesafe-relay python relay.py tesla-telemetry-send"
