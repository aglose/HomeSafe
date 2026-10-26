#!/bin/bash
# Sets up a Claude Code cloud session so it can do what a laptop does: build with Gradle, draw the
# UI previews, run the tests that don't need a device, and reach the home server over Tailscale.
#
# Each part checks that what it needs is there (a host the environment's network policy allows, a
# secret the environment holds) and otherwise says what's missing and skips, so a session still
# starts. What it did is printed at the end; that summary is what the session is told. The full
# log is in /tmp/homesafe-session-start.log.
#
# Hosts the environment's network policy has to allow:
#   Gradle's JDK (Azul Zulu 21, gradle/gradle-daemon-jvm.properties): api.foojay.io, cdn.azul.com
#   Google Maven and the Android SDK: dl.google.com (maven.google.com redirects there)
#   Tailscale: controlplane.tailscale.com, login.tailscale.com, *.tailscale.com (DERP relays)
#
# Secrets the environment can hold (environment variables), for `ssh frigate`:
#   TS_AUTHKEY        an ephemeral, tagged Tailscale auth key
#   FRIGATE_SSH_KEY   the private key of a restricted user on the box, base64 on one line
#   FRIGATE_SSH_USER  that user (default andrew); FRIGATE_HOST its tailnet address (default 100.99.163.71)
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
LOG=/tmp/homesafe-session-start.log
: >"$LOG"
SUMMARY=()
note() { SUMMARY+=("$1"); }
env_out() { [ -n "${CLAUDE_ENV_FILE:-}" ] && echo "$1" >>"$CLAUDE_ENV_FILE"; }
# Whether HTTPS to a host gets any answer; the proxy refuses a denied host before one.
reachable() { [ "$(curl -s -o /dev/null -m 10 -w '%{http_code}' "https://$1/" 2>/dev/null)" != "000" ]; }
blocked() {
  local missing=() host
  for host in "$@"; do reachable "$host" || missing+=("$host"); done
  [ ${#missing[@]} -gt 0 ] && echo "${missing[*]}"
}

# --- The SSH client -------------------------------------------------------------------------
if ! command -v ssh >/dev/null; then
  if { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openssh-client; } >>"$LOG" 2>&1; then
    note "ssh: installed openssh-client"
  else
    note "ssh: couldn't install openssh-client (see $LOG)"
  fi
fi

# --- The Android SDK ------------------------------------------------------------------------
# Only the command-line tools and the licenses: AGP downloads the platform and build tools that
# compileSdk asks for by itself, once the licenses are accepted.
ANDROID_SDK=/opt/android-sdk
CMDLINE_TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip
if [ ! -x "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  if missing=$(blocked dl.google.com); then
    note "Android SDK: not installed, the network policy blocks $missing"
  else
    tmp=$(mktemp -d)
    if curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" >>"$LOG" 2>&1 &&
      unzip -q "$tmp/tools.zip" -d "$tmp" >>"$LOG" 2>&1; then
      mkdir -p "$ANDROID_SDK/cmdline-tools"
      rm -rf "$ANDROID_SDK/cmdline-tools/latest"
      mv "$tmp/cmdline-tools" "$ANDROID_SDK/cmdline-tools/latest"
    fi
    rm -rf "$tmp"
  fi
fi
if [ -x "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  export ANDROID_HOME=$ANDROID_SDK
  env_out "export ANDROID_HOME=$ANDROID_SDK"
  [ -f local.properties ] || echo "sdk.dir=$ANDROID_SDK" >local.properties
  if [ ! -d "$ANDROID_SDK/platform-tools" ]; then
    yes | "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >>"$LOG" 2>&1
    "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" platform-tools >>"$LOG" 2>&1
  fi
  [ -d "$ANDROID_SDK/platform-tools" ] && note "Android SDK: $ANDROID_SDK" || note "Android SDK: sdkmanager failed (see $LOG)"
fi

# --- Gradle ---------------------------------------------------------------------------------
# Maven Central answers a cold build from the cloud's shared address with 429 Too Many Requests,
# so Google's mirror of it goes first; what the mirror doesn't have yet falls through to Central.
mkdir -p ~/.gradle/init.d
cat >~/.gradle/init.d/maven-central-mirror.gradle <<'EOF'
// Written by .claude/hooks/session-start.sh, for cloud sessions only.
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
beforeSettings { settings ->
    settings.pluginManagement.repositories { maven { name = 'MavenCentralMirror'; url = mirror } }
    settings.dependencyResolutionManagement.repositories { maven { name = 'MavenCentralMirror'; url = mirror } }
}
EOF

# Provisions the daemon's JDK and resolves the plugins, so the session's first build doesn't.
if missing=$(blocked api.foojay.io cdn.azul.com dl.google.com); then
  note "Gradle: can't build, the network policy blocks $missing. UI previews: scripts/fetch-ui-previews.sh draws them on CI."
elif [ -z "${ANDROID_HOME:-}" ]; then
  note "Gradle: can't build without the Android SDK"
elif timeout 900 ./gradlew help --quiet >>"$LOG" 2>&1; then
  note "Gradle: ready (./gradlew testAndroidHostTest, ktlintCheck, scripts/preview)"
else
  note "Gradle: './gradlew help' failed (see $LOG)"
fi

# --- Tailscale, for `ssh frigate` -----------------------------------------------------------
# Userspace networking: the container has no TUN device. SSH reaches the box through
# `tailscale nc`, so ~/.ssh/config's `frigate` is the way in (relay/deploy.sh is pointed at it).
if [ -z "${TS_AUTHKEY:-}" ]; then
  note "Tailscale: off (no TS_AUTHKEY in the environment), so the home server is unreachable"
elif missing=$(blocked controlplane.tailscale.com); then
  note "Tailscale: the network policy blocks $missing"
else
  if ! command -v tailscaled >/dev/null; then
    # pkgs.tailscale.com may be blocked where the Go module proxy isn't.
    GOTOOLCHAIN=auto GOBIN=/usr/local/bin go install tailscale.com/cmd/tailscale@latest tailscale.com/cmd/tailscaled@latest >>"$LOG" 2>&1
  fi
  if ! command -v tailscaled >/dev/null; then
    note "Tailscale: couldn't install it (see $LOG)"
  else
    if ! pgrep -x tailscaled >/dev/null; then
      TS_NO_LOGS_NO_SUPPORT=true nohup tailscaled --tun=userspace-networking --state=mem: \
        --socket=/var/run/tailscale/tailscaled.sock >>/tmp/tailscaled.log 2>&1 &
      for _ in $(seq 20); do [ -S /var/run/tailscale/tailscaled.sock ] && break; sleep 0.5; done
    fi
    # `tailscale status` fails until the node is logged in and running.
    if ! tailscale status >/dev/null 2>&1; then
      timeout 60 tailscale up --auth-key="$TS_AUTHKEY" --hostname="claude-cloud" --accept-dns=false >>"$LOG" 2>&1
    fi
    if tailscale status >/dev/null 2>&1; then
      note "Tailscale: up"
    else
      note "Tailscale: didn't come up (see $LOG and /tmp/tailscaled.log)"
    fi
  fi
fi

if [ -n "${FRIGATE_SSH_KEY:-}" ]; then
  mkdir -p ~/.ssh && chmod 700 ~/.ssh
  # The key as it is, or base64 on one line (`base64 -w0 key`), since a variable is one line.
  if [[ "$FRIGATE_SSH_KEY" == -----BEGIN* ]]; then
    printf '%s\n' "$FRIGATE_SSH_KEY" >~/.ssh/frigate
  else
    printf '%s' "$FRIGATE_SSH_KEY" | base64 -d >~/.ssh/frigate
  fi
  chmod 600 ~/.ssh/frigate
  if ! grep -q '^Host frigate$' ~/.ssh/config 2>/dev/null; then
    cat >>~/.ssh/config <<EOF
Host frigate
  HostName ${FRIGATE_HOST:-100.99.163.71}
  User ${FRIGATE_SSH_USER:-andrew}
  IdentityFile ~/.ssh/frigate
  IdentitiesOnly yes
  StrictHostKeyChecking accept-new
  ProxyCommand tailscale nc %h %p
EOF
  fi
  env_out "export HOMESAFE_BOX=frigate"
  if timeout 20 ssh -o BatchMode=yes frigate true >>"$LOG" 2>&1; then
    note "ssh frigate: works"
  else
    note "ssh frigate: configured, but the box didn't answer (see $LOG)"
  fi
elif [ -n "${TS_AUTHKEY:-}" ]; then
  note "ssh frigate: no FRIGATE_SSH_KEY in the environment"
fi

note "Not in the cloud: emulator suites (no /dev/kvm) and iOS (no macOS); CI runs both."
printf 'HomeSafe cloud setup:\n'
printf -- '- %s\n' "${SUMMARY[@]}"
exit 0
