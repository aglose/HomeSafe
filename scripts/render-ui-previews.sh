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

./gradlew :shared:renderPreviews :androidApp:updateDebugScreenshotTest --continue "$@"
status=$?

cp shared/build/previews/*.png shared/build/previews/previews.json "$out/desktop/" 2>/dev/null
cp "$reference"/*.png "$out/android/" 2>/dev/null
if [ -z "$(find "$out/android" -name '*.png' -print -quit)" ] && [ -d androidApp/build ]; then
  # Gradle can succeed with nothing drawn (no @PreviewTest found, say); show where the tool's output went.
  echo "No Android previews in $reference. What the screenshot tasks left behind:"
  find androidApp/build androidApp/src -ipath '*screenshot*' -not -path '*/intermediates/*' -not -path '*/tmp/*' 2>/dev/null | head -40
  find androidApp/build -ipath '*test-results*screenshot*' -name '*.xml' -exec grep -h -m1 '<testsuite' {} + 2>/dev/null | head -5
fi
echo "Gathered $(find "$out/android" -name '*.png' | wc -l) Android and $(find "$out/desktop" -name '*.png' | wc -l) desktop previews in $out"
exit "$status"
