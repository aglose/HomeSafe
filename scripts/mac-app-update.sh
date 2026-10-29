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

install_agent() {
  local hours=${1:-6}
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

command -v gh >/dev/null || die "GitHub's CLI isn't installed: brew install gh && gh auth login"
gh auth status >/dev/null 2>&1 || die "GitHub's CLI isn't signed in: gh auth login"

# One at a time: a check at login can meet one started by hand.
mkdir -p "$home_dir"
lock="$home_dir/lock"
# A lock older than an hour was left by a run that was killed.
find "$lock" -maxdepth 0 -mmin +60 -exec rmdir {} \; 2>/dev/null || true
mkdir "$lock" 2>/dev/null || { say "Another update is running."; exit 0; }
work=$(mktemp -d)
mount=
cleanup() {
  [[ -n "$mount" ]] && hdiutil detach -quiet "$mount" 2>/dev/null || true
  rm -rf "$work"
  rmdir "$lock" 2>/dev/null || true
}
trap cleanup EXIT

# main's newest .dmg, from a push (not a pull request's run), still kept.
run_id=$(gh api "repos/$repo/actions/artifacts?name=$artifact&per_page=50" --jq \
  '[.artifacts[] | select(.expired | not) | select(.workflow_run.head_branch == "main")] | sort_by(.created_at) | last | .workflow_run.id // empty')
[[ -n "$run_id" ]] || die "No $artifact build of main is kept on GitHub right now (they last 30 days)."
installed=$(cat "$state" 2>/dev/null || true)
if [[ "$run_id" == "$installed" && -d "$app" && "$force" == false ]]; then
  say "Up to date (run $run_id)."
  exit 0
fi

# Someone using it now keeps it; the next check tries again.
front=$(lsappinfo info -only bundleid "$(lsappinfo front)" 2>/dev/null || true)
if [[ "$front" == *"$bundle_id"* ]]; then
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
if pgrep -qf "$app/Contents/MacOS/HomeSafe"; then
  running=true
  say "Quitting HomeSafe to update it…"
  osascript -e "quit app id \"$bundle_id\"" >/dev/null 2>&1 || true
  for _ in $(seq 30); do pgrep -qf "$app/Contents/MacOS/HomeSafe" || break; sleep 1; done
  if pgrep -qf "$app/Contents/MacOS/HomeSafe"; then
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
