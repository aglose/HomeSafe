#!/usr/bin/env bash
# Pulls down the UI previews the "UI previews" workflow drew for a branch, for a machine that
# can't draw them itself — a sandboxed agent with no Android toolchain, say. Push the branch,
# then:
#
#   scripts/fetch-ui-previews.sh --wait     # this branch; waits until its pushed head is drawn
#   scripts/fetch-ui-previews.sh other-branch
#
# The images land in build/ui-previews/<branch>/: android/ (Layoutlib), desktop/ (the JVM),
# journeys/ (the whole app after each tap of each integration journey, <journey>__<step>.png),
# base/ (the merge base's copy of anything that changed), README.md (the gallery, what changed
# first) and summary.json. See docs/ui-previews.md.
set -euo pipefail

wait=false
branch=
for arg in "$@"; do
  case "$arg" in
    --wait) wait=true ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) branch=$arg ;;
  esac
done
branch=${branch:-$(git branch --show-current)}
key=$(printf '%s' "$branch" | tr -c 'A-Za-z0-9._-' '-')
ref="refs/previews/$key"
dest="build/ui-previews/$key"

# The commit the gallery should be of: the branch as pushed.
git fetch --quiet origin "$branch"
want=$(git rev-parse FETCH_HEAD)

deadline=$(( $(date +%s) + ${UI_PREVIEWS_TIMEOUT:-1800} ))
while true; do
  if git fetch --quiet origin "+$ref:$ref" 2>/dev/null; then
    drawn=$(git log -1 --format=%s "$ref" | sed -n 's/^UI previews of .* at \([0-9a-f]*\)$/\1/p')
    [ "$drawn" = "$want" ] && break
    note="drawn at ${drawn:0:7}, waiting for ${want:0:7}"
  else
    note="nothing drawn yet for $branch"
  fi
  if ! $wait; then
    echo "Note: $note." >&2
    git rev-parse --quiet --verify "$ref" >/dev/null || exit 1
    break
  fi
  [ "$(date +%s)" -lt "$deadline" ] || { echo "Timed out: $note." >&2; exit 1; }
  echo "$note; checking again in 30s" >&2
  sleep 30
done

rm -rf "$dest"
mkdir -p "$dest"
git archive "$ref" | tar -x -C "$dest"
git log -1 --format='%s%n%b' "$ref"
echo "Gallery in $dest (README.md lists what changed first):"
find "$dest" -name '*.png' | sort
