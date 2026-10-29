#!/usr/bin/env bash
# Keeps HomeSafe on a Mac up to date with main: fetches the .dmg the "Desktop app" workflow built
# for main's newest push (the HomeSafe-macOS artifact) and puts it in /Applications in place of the
# one there. Needs GitHub's CLI, signed in to an account that can read the repository:
#
#   brew install gh && gh auth login
#
#   scripts/mac-app-update.sh                  # update now, if main has a newer build
#   scripts/mac-app-update.sh --install [h]    # and every h hours (6) and at login, from now on
#   scripts/mac-app-update.sh --uninstall      # stop updating by itself
#   scripts/mac-app-update.sh --force          # reinstall main's newest build even if it's in place
#
# No clone is needed: --install copies this script out of the repository first, so
#   gh api repos/aglose/HomeSafe/contents/scripts/mac-app-update.sh -H 'Accept: application/vnd.github.raw' | bash -s -- --install
# sets it all up. A HomeSafe that is running is quit, replaced and opened again behind the other
# windows; one in front, being used, is left for the next check. See docs/desktop.md.
set -euo pipefail

repo=aglose/HomeSafe
artifact=HomeSafe-macOS
app=/Applications/HomeSafe.app
bundle_id=com.meticulouscreations.homesafe.desktop
label=com.meticulouscreations.homesafe.updater
home_dir="$HOME/Library/Application Support/HomeSafe/updater"
state="$home_dir/installed-run"
agent="$HOME/Library/LaunchAgents/$label.plist"
log="$HOME/Library/Logs/HomeSafe-updater.log"

say() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { say "$*" >&2; exit 1; }

[[ "$(uname -s)" == Darwin ]] || die "This is for a Mac."

# CI builds for Apple silicon only; an Intel Mac keeps the app it built itself (docs/desktop.md).
require_apple_silicon() {
  [[ "$(sysctl -n hw.optional.arm64 2>/dev/null)" == 1 ]] || die "main's builds are for Apple silicon; this Mac builds its own (./gradlew :desktopApp:packageDmg)."
}

homesafe_running() { pgrep -f "$app/Contents/MacOS/HomeSafe" >/dev/null; }

in_front() { [[ "$(lsappinfo info -only bundleid "$(lsappinfo front)" 2>/dev/null || true)" == *"$bundle_id"* ]]; }

install_agent() {
  local hours=${1:-6}
  require_apple_silicon
  [[ "$hours" =~ ^[0-9]+$ && "$hours" -gt 0 ]] || die "Hours must be a whole number above 0, not '$hours'."
  mkdir -p "$home_dir" "$(dirname "$agent")" "$(dirname "$log")"
  # Its own copy, so the schedule doesn't depend on a clone staying where it is (or existing).
  if [[ -f "${BASH_SOURCE[0]:-}" ]]; then
    cp "${BASH_SOURCE[0]}" "$home_dir/update.sh"
  else
    command -v gh >/dev/null || die "GitHub's CLI isn't installed: brew install gh && gh auth login"
    gh api "repos/$repo/contents/scripts/mac-app-update.sh" -H 'Accept: application/vnd.github.raw' >"$home_dir/update.sh"
  fi
  chmod +x "$home_dir/update.sh"
  cat >"$agent" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$home_dir/update.sh</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
  <key>StartInterval</key><integer>$((hours * 3600))</integer>
  <key>RunAtLoad</key><true/>
  <key>ProcessType</key><string>Background</string>
  <key>StandardOutPath</key><string>$log</string>
  <key>StandardErrorPath</key><string>$log</string>
</dict>
</plist>
EOF
  launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "$agent"
  say "HomeSafe now updates itself every $hours h and at login (checking now). Log: $log"
}

uninstall_agent() {
  launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
  rm -f "$agent" "$home_dir/update.sh"
  say "HomeSafe no longer updates itself. The app stays as it is."
}

force=false
case "${1:-}" in
  --install) install_agent "${2:-6}"; exit 0 ;;
  --uninstall) uninstall_agent; exit 0 ;;
  --force) force=true ;;
  -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
  '') ;;
  *) die "Unknown option '$1' (see --help)." ;;
esac

require_apple_silicon
command -v gh >/dev/null || die "GitHub's CLI isn't installed: brew install gh && gh auth login"
gh auth status >/dev/null 2>&1 || die "GitHub's CLI isn't signed in: gh auth login"

# One at a time: a check at login can meet one started by hand.
mkdir -p "$home_dir"
# The lock names the run holding it, and is taken over only once that run has gone (killed, or
# the Mac restarted under it); one that is only slow, asleep with the Mac, keeps it.
lock="$home_dir/lock"
owns_lock=false
take_lock() {
  if mkdir "$lock" 2>/dev/null; then
    printf '%s\n' "$$" >"$lock/pid"
    owns_lock=true
    return 0
  fi
  local holder
  holder=$(cat "$lock/pid" 2>/dev/null || true)
  if [[ -n "$holder" ]]; then
    kill -0 "$holder" 2>/dev/null && return 1
  else
    # Between another run's mkdir and its pid, or left by one killed right there.
    [[ -n "$(find "$lock" -maxdepth 0 -mmin +5 2>/dev/null)" ]] || return 1
  fi
  # Taken over by one run at a time, which asks again under that: two runs that both found the
  # same dead holder would otherwise each clear the lock, the second clearing the first's new one.
  local reclaim="$lock.reclaim"
  if ! mkdir "$reclaim" 2>/dev/null; then
    # One killed while taking over leaves this; it is only ever held for a moment.
    find "$reclaim" -maxdepth 0 -mmin +1 -exec rm -rf {} + 2>/dev/null || true
    return 1
  fi
  local taken=false
  if [[ "$(cat "$lock/pid" 2>/dev/null || true)" == "$holder" ]]; then
    rm -rf "$lock"
    if mkdir "$lock" 2>/dev/null; then
      printf '%s\n' "$$" >"$lock/pid"
      owns_lock=true
      taken=true
    fi
  fi
  rmdir "$reclaim" 2>/dev/null || true
  [[ "$taken" == true ]]
}
take_lock || { say "Another update is running."; exit 0; }
work=$(mktemp -d)
mount=
cleanup() {
  [[ -n "$mount" ]] && hdiutil detach -quiet "$mount" 2>/dev/null || true
  rm -rf "$work"
  if [[ "$owns_lock" == true && "$(cat "$lock/pid" 2>/dev/null)" == "$$" ]]; then rm -rf "$lock"; fi
}
trap cleanup EXIT

# main's newest .dmg, still kept: built in this repository (never a fork's pull request, whose
# branch may be called main too) by the Desktop app workflow, on a push to main or a run started on it.
run_id=
# Every page: pull requests' builds share the name, and can push main's past the first hundred.
candidates=$(gh api --paginate "repos/$repo/actions/artifacts?name=$artifact&per_page=100" --jq \
  '.artifacts[] | select(.expired | not) | select(.workflow_run.head_branch == "main" and .workflow_run.head_repository_id == .workflow_run.repository_id) | "\(.created_at) \(.workflow_run.id)"' |
  sort -r | cut -d' ' -f2)
for candidate in $candidates; do
  origin=$(gh api "repos/$repo/actions/runs/$candidate" --jq '[.event, .head_branch, .head_repository.full_name, .path] | join(" ")')
  case "$origin" in
    "push main $repo .github/workflows/desktop-app.yml" | "workflow_dispatch main $repo .github/workflows/desktop-app.yml")
      run_id=$candidate
      break
      ;;
  esac
done
[[ -n "$run_id" ]] || die "No $artifact build of main is kept on GitHub right now (they last 30 days)."
installed=$(cat "$state" 2>/dev/null || true)
if [[ "$run_id" == "$installed" && -d "$app" && "$force" == false ]]; then
  say "Up to date (run $run_id)."
  exit 0
fi

# Someone using it now keeps it; the next check tries again.
if in_front; then
  say "HomeSafe is in front; leaving the update (run $run_id) for the next check."
  exit 0
fi

say "Downloading run $run_id's $artifact…"
gh run download "$run_id" -R "$repo" -n "$artifact" -D "$work"
dmg=$(find "$work" -name '*.dmg' -print -quit)
[[ -n "$dmg" ]] || die "The $artifact artifact of run $run_id has no .dmg in it."
mount="$work/mount"
mkdir -p "$mount"
hdiutil attach -quiet -nobrowse -readonly -mountpoint "$mount" "$dmg"
[[ -d "$mount/HomeSafe.app" ]] || die "$(basename "$dmg") has no HomeSafe.app in it."
version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$mount/HomeSafe.app/Contents/Info.plist")

# Copied beside the old one first, so a failed copy leaves the old one working.
rm -rf "$app.new"
ditto "$mount/HomeSafe.app" "$app.new"
xattr -dr com.apple.quarantine "$app.new" 2>/dev/null || true

running=false
if homesafe_running; then
  # Asked again now the download is done: it may have come to the front meanwhile.
  if in_front; then
    rm -rf "$app.new"
    say "HomeSafe is in front; leaving the update (run $run_id) for the next check."
    exit 0
  fi
  running=true
  say "Quitting HomeSafe to update it…"
  osascript -e "quit app id \"$bundle_id\"" >/dev/null 2>&1 || true
  for _ in $(seq 30); do homesafe_running || break; sleep 1; done
  if homesafe_running; then
    rm -rf "$app.new"
    die "HomeSafe didn't quit; leaving the update for the next check."
  fi
fi

rm -rf "$app.old"
[[ -d "$app" ]] && mv "$app" "$app.old"
mv "$app.new" "$app"
rm -rf "$app.old"
printf '%s\n' "$run_id" >"$state"
say "Installed HomeSafe $version (run $run_id)."

# Back as it was: in the menu bar, behind whatever is in front.
[[ "$running" == true ]] && open -g "$app"
osascript -e "display notification \"Updated to $version\" with title \"HomeSafe\"" >/dev/null 2>&1 || true
