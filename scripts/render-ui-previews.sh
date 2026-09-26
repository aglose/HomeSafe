#!/usr/bin/env bash
# Draws the UI previews both ways and gathers the PNGs in one place:
#
#   OUT_DIR/android/   Layoutlib, through Compose Preview Screenshot Testing: :androidApp's
#                      @PreviewTest functions (src/screenshotTest), as Android draws them.
#   OUT_DIR/desktop/   the JVM, through :shared:renderPreviews: every @Preview in :shared,
#                      drawn by Skia on the desktop runtime.
#
# Run from the root of a checkout (the "UI previews" workflow also runs it inside a worktree of
# the merge base, to have something to compare with). Exits non-zero if either renderer failed,
# after gathering whatever the other one drew. See docs/ui-previews.md.
#
# Usage: scripts/render-ui-previews.sh OUT_DIR [extra gradle flags]
set -uo pipefail

out=${1:?usage: scripts/render-ui-previews.sh OUT_DIR [gradle flags]}
shift
mkdir -p "$out/android" "$out/desktop"

reference=androidApp/src/screenshotTestDebug/reference
# The update task leaves an image alone when the new one is close enough, and never deletes one
# whose preview is gone; start clean so the folder is exactly what this code draws.
rm -rf "$reference"
since="${TMPDIR:-/tmp}/render-ui-previews.$$"
touch "$since"

./gradlew :shared:renderPreviews :androidApp:updateDebugScreenshotTest --continue "$@"
status=$?

cp shared/build/previews/*.png shared/build/previews/previews.json "$out/desktop/" 2>/dev/null
# Where the tool writes reference images has moved between its releases, so take every image the
# screenshot tasks wrote during this run, from wherever they went, and say where that was.
find androidApp/src androidApp/build/outputs -ipath '*screenshot*' -name '*.png' -newer "$since" \
  -not -ipath '*/diff*' -not -ipath '*/actual*' 2>/dev/null | sort > "$since.list"
if [ -s "$since.list" ]; then
  echo "Layoutlib images from: $(xargs -n1 dirname < "$since.list" | sort -u | tr '\n' ' ')"
  xargs -I{} cp {} "$out/android/" < "$since.list"
else
  echo "Layoutlib drew nothing. Test results:"
  find androidApp/build -ipath '*test-results*screenshot*' -name '*.xml' -exec grep -h -m1 '<testsuite' {} + 2>/dev/null | head -5
fi
rm -f "$since" "$since.list"

echo "Gathered $(find "$out/android" -name '*.png' | wc -l) Android and $(find "$out/desktop" -name '*.png' | wc -l) desktop previews in $out"
exit "$status"
