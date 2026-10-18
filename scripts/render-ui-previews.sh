#!/usr/bin/env bash
# Draws the UI previews and gathers the PNGs in one place:
#
#   OUT_DIR/android/   Layoutlib, through Compose Preview Screenshot Testing: every @Preview in
#                      :shared, the features and :app, as Android draws them.
#
# (The JVM renderer, :shared:renderPreviews, and the journey screenshots went with the JVM target.)
#
# Run from the root of a checkout (the "UI previews" workflow also runs it inside a worktree of
# the merge base, to have something to compare with). Exits non-zero if Layoutlib failed, after
# gathering whatever it drew. See docs/ui-previews.md.
#
# Usage: scripts/render-ui-previews.sh OUT_DIR [--only WORDS] [extra gradle flags]
#        scripts/render-ui-previews.sh --list
#
#   --only WORDS  draw only the previews whose module, function or name contains one of the
#                 comma-separated WORDS (finance,HomeFeed). Much quicker than drawing them all.
#   --list        print every preview (module, function, name) and draw nothing.
set -uo pipefail

if [ "${1:-}" = "--list" ]; then
  ./gradlew -q :androidApp:generatePreviewTests || exit $?
  cat androidApp/build/generated/previewTests/previews.txt
  exit 0
fi

out=${1:?usage: scripts/render-ui-previews.sh OUT_DIR [--only WORDS] [gradle flags]}
shift
# An environment variable rather than -Ppreviews, so a new filter keeps the configuration cache.
export PREVIEWS=
if [ "${1:-}" = "--only" ]; then
  PREVIEWS=${2:?--only needs WORDS}
  shift 2
fi
# This run's images only: a preview or journey removed since the last run into OUT_DIR must not linger.
rm -rf "$out/android" "$out/desktop" "$out/journeys"
mkdir -p "$out/android"

# The update task leaves an image alone when the new one is close enough, and never deletes one
# whose preview is gone; start clean so the folders are exactly what this code draws. Where they
# are depends on the tool's setup (src/screenshotTest<Target><Variant>/reference for a test suite).
rm -rf androidApp/src/screenshotTest*/reference
since="${TMPDIR:-/tmp}/render-ui-previews.$$"
touch "$since"

status=0

# The screenshot tool's update task: its name is derived from the variant, suite and target
# (update<Variant><Suite><Target>TestSuite: updateDebugScreenshotTestDefaultTestSuite here), or
# updateDebugScreenshotTest with the older standalone plugin, which a merge base may still use.
# Ask Gradle rather than hard-code either.
# Names only: `tasks --all` would create every task, and AGP 9.5-alpha's test suite registers a
# preview task for release too, which fails when created (release has no unit-test component).
tasks_out="$since.tasks"
cat > "$since.gradle" <<'GRADLE'
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':androidApp')
    app?.tasks?.names?.findAll { it =~ /(?i)screenshot|testsuite/ }?.each { println "TASK $it" }
}
GRADLE
if ! ./gradlew -q help --init-script "$since.gradle" --no-configuration-cache > "$tasks_out" 2>&1; then
  echo "Couldn't list :androidApp's tasks:"
  tail -n 30 "$tasks_out"
fi
echo "Screenshot and test-suite tasks in :androidApp:"
sed -n 's/^TASK /  /p' "$tasks_out"
updates=$(sed -n 's/^TASK //p' "$tasks_out" | grep -E '^update' | grep -i screenshot)
android_task=$(printf '%s\n' "$updates" | grep -i debug | head -n1)
[ -n "$android_task" ] || android_task=$(printf '%s\n' "$updates" | head -n1)
rm -f "$tasks_out" "$since.gradle"
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

echo "Gathered $(find "$out/android" -name '*.png' | wc -l) Android previews in $out"
exit "$status"
