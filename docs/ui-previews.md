# UI previews and screenshots

You can't judge a UI change from a diff. HomeSafe turns a change into pictures through Layoutlib, the renderer
behind Android Studio's preview pane, on your own machine or on CI (for an agent's sandbox with no Android
toolchain). The app is Android and iOS only; the JVM renderer (`:shared:renderPreviews`) and the journey
screenshots went with the JVM target.

| What | Command | Draws | Output |
|---|---|---|---|
| **Screenshot tests** (Layoutlib) | `./gradlew :androidApp:updateDebugScreenshotTestDefaultTestSuite` | every `@Preview` in `:shared`, the features and `:app`, as Android draws them | `androidApp/src/screenshotTestDefaultDebug/reference/**/*.png` |

`scripts/render-ui-previews.sh OUT_DIR` runs it and collects the PNGs in `OUT_DIR/android`. Add
`--only finance,HomeFeed` to draw only the previews whose module, function or name contains one of those words,
which is much quicker than drawing them all. `scripts/render-ui-previews.sh --list` prints every preview.

## Previews are the catalogue

A preview is a no-argument `@Preview` composable in `shared/src/commonMain` or a feature module's
(`features/<name>/src/commonMain`), written next to the code it shows. It draws a stateless composable from
fixtures, as the screen UI tests do. See `ui/screens/HomePreviews.kt`.

- Android Studio draws it in the editor.
- Layoutlib draws it too, with nothing to register. `:androidApp:generatePreviewTests` reads every `@Preview` in
  the `commonMain` and `androidMain` sources of `:shared`, `features/*` and `:app`, and writes a `@PreviewTest`
  wrapper for each into `androidApp/build/generated/previewTests/`, with the preview's own `@Preview` arguments.
  The wrapper calls the preview by reflection, so keep previews private, as compose-rules wants.
- The generator reads source, so a preview must be a top-level function, and its `@Preview` arguments literals or
  a `const val` of the same file. Anything else fails the build and names the file.

Give screen previews a phone's size, `@Preview(widthDp = 412, heightDp = 915)`. Without a size, a preview is drawn
as big as its content. Wrap component previews in
`FrigateTheme` rather than `FrigatePreview`, because the latter's `Surface` fills the whole canvas.

## The renderer

**Compose Preview Screenshot Testing**
([docs](https://developer.android.com/studio/preview/compose-screenshot-testing)) is Google's host-side tool. It
draws through Layoutlib, the renderer behind Android Studio's preview pane, so it's Android-exact.

- It only looks at `@PreviewTest` functions in an Android module's `screenshotTest` source set. It doesn't
  support Kotlin Multiplatform common code, hence the generated wrappers in `:androidApp`.
- It draws one frame. Animations show their first frame, and nothing is clicked or scrolled.
- It needs the Android SDK.
- It runs as an [AGP test suite](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites):
  `testOptions.screenshotTests` in `androidApp/build.gradle.kts`, with engine `0.0.1-alpha16`. That needs AGP 9.5
  (still a preview release) and the experimental `android.experimental.testSuiteSupport` flag. It replaces the
  deprecated standalone `com.android.compose.screenshot` plugin.
- `testDebugScreenshotTestDefaultTestSuite` compares against the reference images and writes an HTML diff report
  to `androidApp/build/reports/tests/`.
- The reference images aren't committed yet (they're gitignored). To gate merges on them, have CI generate them on
  Linux, commit those, and add `testDebugScreenshotTestDefaultTestSuite` to `ci.yml`. References made on a Mac
  won't match Linux exactly.

## In pull requests

The **UI previews** workflow (`.github/workflows/ui-previews.yml`) runs on every push to a branch other than `main`.
It isn't part of `ci-green`.

1. It draws the branch with `scripts/render-ui-previews.sh`, then the merge base with the same script. The merge
   base's pictures are kept in the Actions cache under its commit, so a later push to the branch draws only the branch.
2. `scripts/ui_previews.py` compares the two runs file by file and lays out a gallery.
3. The gallery goes to `refs/previews/<branch>-<digest of its name>` as one parentless commit, force-pushed each time. It's outside
   `refs/heads`, so a normal clone never downloads it. Its `README.md` is the gallery on GitHub, and the run's
   summary links it.
4. If the branch has an open pull request, a before/after table goes into the description between
   `<!-- ui-previews:start -->` and `<!-- ui-previews:end -->`. The table shows only what changed, what's new and what was removed.

The job fails only if a preview can't be drawn at all. A preview that merely looks different is what the gallery is
for.

## For agents

**With a working Gradle** (your machine, or a sandbox that can reach Google Maven):

1. Change the composable. Add or adjust a preview for the state you changed.
2. Run `scripts/render-ui-previews.sh build/ui-previews/local --only <words>`, naming the previews you're working
   on (`--list` shows them all). The PNGs land in `build/ui-previews/local/android/`.
3. Open the PNGs and look at them, then iterate. Draw everything once at the end, without `--only`.

**In a sandbox that can't build** (for example a Claude Code cloud environment whose network policy blocks
`dl.google.com`): push the branch, then run `scripts/fetch-ui-previews.sh --wait`. It waits until the workflow has
drawn the pushed commit, then unpacks the gallery into `build/ui-previews/<branch>/`. Start from its `README.md`,
which lists what changed first. It takes one CI run, a few minutes per round.

### Letting a Claude Code cloud environment build

Everything here goes through Gradle, and Gradle can't configure this project without Google Maven: AGP and the
androidx artifacts live only there. In the environment's settings, allow `dl.google.com` and `maven.google.com`,
or choose a broader network access level.

The Layoutlib renders also need the Android SDK. A setup script can install it. Take the current command-line tools
link from [the Android Studio downloads page](https://developer.android.com/studio#command-tools):

```bash
export ANDROID_HOME=$HOME/android-sdk
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -sSLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37"  # AGP fetches build-tools itself
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

