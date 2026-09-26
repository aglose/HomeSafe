#!/usr/bin/env bash
# Draws the UI previews both ways and gathers the PNGs in one place:
#
#   OUT_DIR/android/   Layoutlib, through Compose Preview Screenshot Testing: :androidApp's
#                      @PreviewTest functions (src/screenshotTest), as Android draws them.
#   OUT_DIR/desktop/   the JVM, through :shared:renderPreviews: every @Preview in :shared,
#                      drawn by Skia on the desktop runtime.
#   OUT_DIR/journeys/  with UI_PREVIEWS_JOURNEYS=1: the integration journeys' screenshots.
#
# Run from the root of a checkout (the "UI previews" workflow also runs it inside a worktree of
# the merge base, to have something to compare with). Exits non-zero if either renderer failed,
# after gathering whatever the other one drew. See docs/ui-previews.md.
#
# Usage: scripts/render-ui-previews.sh OUT_DIR [extra gradle flags]
set -uo pipefail

out=${1:?usage: scripts/render-ui-previews.sh OUT_DIR [gradle flags]}
shift
# This run's images only: a preview or journey removed since the last run into OUT_DIR must not linger.
rm -rf "$out/android" "$out/desktop" "$out/journeys"
mkdir -p "$out/android" "$out/desktop"

# The update task leaves an image alone when the new one is close enough, and never deletes one
# whose preview is gone; start clean so the folders are exactly what this code draws. Where they
# are depends on the tool's setup (src/screenshotTest<Target><Variant>/reference for a test suite).
rm -rf androidApp/src/screenshotTest*/reference
since="${TMPDIR:-/tmp}/render-ui-previews.$$"
touch "$since"

./gradlew :shared:renderPreviews "$@"
status=$?
cp shared/build/previews/*.png shared/build/previews/previews.json "$out/desktop/" 2>/dev/null

# The screenshot tool's update task: its name is derived from the suite, target and variant
# (update<Suite><Target><Variant>TestSuite), or updateDebugScreenshotTest with the older standalone
# plugin, which a merge base may still use. Ask Gradle rather than hard-code either.
tasks_out="$since.tasks"
if ! ./gradlew -q :androidApp:tasks --all --no-configuration-cache > "$tasks_out" 2>&1; then
  echo "Couldn't list :androidApp's tasks:"
  tail -n 30 "$tasks_out"
fi
echo "Screenshot and test-suite tasks in :androidApp:"
grep -iE '^[a-z][A-Za-z0-9]*(screenshot|suite)[A-Za-z0-9]*( |$)' "$tasks_out" | sed 's/^/  /'
android_task=$(grep -oE '^update[A-Za-z0-9]*' "$tasks_out" | grep -i screenshot | grep -i debug | head -n1)
rm -f "$tasks_out"
if [ -n "$android_task" ]; then
  echo "Layoutlib: :androidApp:$android_task"
  ./gradlew ":androidApp:$android_task" "$@" || status=$?
else
  echo "Layoutlib: :androidApp has no screenshot update task"
  status=1
fi

# The tool nests its images by package: <reference>/com/…/screenshots/SharedPreviewScreenshotsKt/.
# It writes the same ones under build/outputs/…/rendered/ too; that is the fallback, taking only
# images from this run. File names hold spaces and commas (the preview's name).
find androidApp/src -path '*/screenshotTest*/reference/*' -name '*.png' 2>/dev/null | sort > "$since.list"
[ -s "$since.list" ] || find androidApp/build/outputs -path '*rendered*' -name '*.png' -newer "$since" 2>/dev/null | sort > "$since.list"
if [ -s "$since.list" ]; then
  echo "Layoutlib images from: $(dirname "$(head -n1 "$since.list")")"
  while IFS= read -r png; do cp "$png" "$out/android/"; done < "$since.list"
else
  echo "Layoutlib drew nothing. Test results:"
  find androidApp/build -ipath '*test-results*' -name '*.xml' -newer "$since" -exec grep -h -m1 '<testsuite' {} + 2>/dev/null | head -5
fi
rm -f "$since" "$since.list"

# UI_PREVIEWS_JOURNEYS=1: also run the JVM integration journeys with -PjourneyScreens, and gather
# their flipbooks (what the whole app showed after each tap) in OUT_DIR/journeys, one file per
# screen as <journey>__<step>.png. A journey that fails leaves its "failed" screen and is
# reported, but doesn't fail this script: that is CI's job, not the gallery's.
if [ "${UI_PREVIEWS_JOURNEYS:-}" = 1 ]; then
  mkdir -p "$out/journeys"
  ./gradlew :shared:jvmTest --tests 'com.meticulouscreations.homesafe.integration.*' -PjourneyScreens "$@" \
    || echo "Some journeys failed; their last screen is saved as <journey>__NN-failed.png"
  for dir in shared/build/journey-screens/*/; do
    [ -d "$dir" ] || continue
    for png in "$dir"*.png; do
      [ -f "$png" ] && cp "$png" "$out/journeys/$(basename "$dir")__$(basename "$png")"
    done
  done
fi

echo "Gathered $(find "$out/android" -name '*.png' | wc -l) Android and $(find "$out/desktop" -name '*.png' | wc -l) desktop previews" \
  "and $(find "$out/journeys" -name '*.png' 2>/dev/null | wc -l) journey screens in $out"
exit "$status"
