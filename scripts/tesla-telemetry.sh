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
# The key is $TESLA_PRIVATE_KEY (default ~/.config/homesafe/tesla-private-key.pem). It is signed by
# scripts/tesla-sign, a few lines over Tesla's vehicle-command signing package (its own tesla-jws
# can't be built for a Mac without cgo). The first run builds that on the box in Go's image, for
# this Mac, so nothing is installed here.
set -euo pipefail

BOX=${HOMESAFE_BOX:-andrew@100.99.163.71}
VEHICLE_COMMAND=v0.4.1
KEY=${TESLA_PRIVATE_KEY:-$HOME/.config/homesafe/tesla-private-key.pem}
SIGN=${TESLA_SIGN:-$HOME/.config/homesafe/bin/tesla-sign-$VEHICLE_COMMAND}
box() { ssh -o ConnectTimeout=10 -o BatchMode=yes "$BOX" "$@"; }

if [ "${1:-}" = status ]; then
  box "docker exec homesafe-relay python relay.py tesla-telemetry-status"
  exit
fi
[ $# -eq 0 ] || { echo "usage: scripts/tesla-telemetry.sh [status]" >&2; exit 2; }
[ -r "$KEY" ] || { echo "no private key at $KEY (set TESLA_PRIVATE_KEY)" >&2; exit 1; }

if [ ! -x "$SIGN" ]; then
  arch=$(uname -m)
  [ "$arch" = x86_64 ] && arch=amd64
  mkdir -p "$(dirname "$SIGN")"
  echo "building tesla-sign on vehicle-command $VEHICLE_COMMAND for darwin/$arch on the box" >&2
  box "docker run --rm -i -e GOOS=darwin -e GOARCH=$arch -e CGO_ENABLED=0 golang:1.24 sh -c \
    'mkdir /src && cd /src && cat > main.go && go mod init tesla-sign >&2 \
     && go get github.com/teslamotors/vehicle-command@$VEHICLE_COMMAND >&2 && go mod tidy >&2 \
     && go build -o /tmp/tesla-sign . >&2 && cat /tmp/tesla-sign'" \
    < "$(dirname "$0")/tesla-sign/main.go" > "$SIGN.part"
  chmod +x "$SIGN.part"
  mv "$SIGN.part" "$SIGN"
fi

config=$(box "docker exec homesafe-relay python relay.py tesla-telemetry-config")
token=$(printf '%s' "$config" | "$SIGN" "$KEY")
printf '%s' "$token" | box "docker exec -i homesafe-relay python relay.py tesla-telemetry-send"
