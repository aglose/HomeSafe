# UI previews and screenshots

You can't judge a UI change from a diff. HomeSafe has three ways to turn a change into pictures. Each suits a
different place: your own machine, CI, or an agent's sandbox with no Android toolchain.

| What | Command | Draws | Output |
|---|---|---|---|
| **Preview renders** (JVM) | `./gradlew :shared:renderPreviews [-Ppreview=Home]` | Every `@Preview` in `:shared`, on the desktop runtime | `shared/build/previews/*.png`, `previews.json` |
| **Screenshot tests** (Layoutlib) | `./gradlew :androidApp:updateDebugScreenshotTest` | `:androidApp`'s `@PreviewTest` wrappers, as Android draws them | `androidApp/src/screenshotTestDebug/reference/*.png` |
| **Journey screenshots** (JVM) | `./gradlew :shared:jvmTest -PjourneyScreens --tests '*HomeJourneyTest*'` | The whole app against the fake Frigate, after each tap, at the end and on failure | `shared/build/journey-screens/<journey>/*.png` |

`scripts/render-ui-previews.sh OUT_DIR` runs the first two and collects their PNGs in `OUT_DIR/desktop` and
`OUT_DIR/android`.

## Previews are the catalogue

A preview is a no-argument `@Preview` composable in `shared/src/commonMain`, written next to the code it shows. It
draws a stateless composable from fixtures, as the screen UI tests do. See `ui/screens/HomePreviews.kt`.

- Android Studio draws it in the editor.
- `:shared:renderPreviews` finds it without being told about it and draws it.
- For Layoutlib too, add a one-line `@PreviewTest` wrapper in
  `androidApp/src/screenshotTest/.../SharedPreviewScreenshots.kt`. The wrapper is in another module, so the
  preview must be public. compose-rules wants previews private, so put shared previews in a file that suppresses
  `compose:preview-public-check` and says why, like `HomePreviews.kt`. Keep every other preview private.

Give screen previews a phone's size, `@Preview(widthDp = 412, heightDp = 915)`. Without a size, a preview is drawn
as big as its content, and on the JVM it's measured on a 412×915 dp canvas. Wrap component previews in
`FrigateTheme` rather than `FrigatePreview`, because the latter's `Surface` fills the whole canvas.

## The two renderers

**`:shared:renderPreviews`** is `RenderPreviews` in `shared/src/jvmTest`, run by its own Gradle task (under plain
`jvmTest` it does nothing).

- ClassGraph finds `@Preview` in the compiled classes. It has binary retention, so reflection can't see it.
  Repeated `@Preview`s and multipreview annotations like `@PreviewFontScale` are found too.
- Each preview is called as compiled code would call it, inside an `ImageComposeScene` at 2× density, with
  `LocalInspectionMode` on.
- About half a second of frames play before capture, so fades and first-frame effects have landed.
- It needs only a JDK. It takes seconds after the first compile.
- The app bundles its fonts (Albert Sans, Fraunces), so text matches Android. What differs is what Android draws
  itself: system bars, and platform `actual`s like the video players.
- It honours `widthDp`, `heightDp`, `fontScale`, `showBackground` and `backgroundColor`. It skips previews that
  take a `@PreviewParameter`.
- A preview that throws fails the task, with its stack trace. Everything else is still drawn.

**Compose Preview Screenshot Testing**
([docs](https://developer.android.com/studio/preview/compose-screenshot-testing)) is Google's host-side tool. It
draws through Layoutlib, the renderer behind Android Studio's preview pane, so it's Android-exact.

- It only looks at `@PreviewTest` functions in an Android module's `screenshotTest` source set. It doesn't
  support Kotlin Multiplatform common code, hence the wrappers in `:androidApp`.
- It needs the Android SDK.
- This repository uses the standalone plugin (`com.android.compose.screenshot`, `0.0.1-alpha16`). Google now
  recommends AGP test suites instead, which need AGP 9.5; this project is on 9.4.
- `validateDebugScreenshotTest` compares against the reference images and writes an HTML diff report to
  `androidApp/build/reports/screenshotTest/`.
- The reference images aren't committed yet (they're gitignored). To gate merges on them, have CI generate them on
  Linux, commit those, and add `validateDebugScreenshotTest` to `ci.yml`. References made on a Mac won't match
  Linux exactly.

## In pull requests

The **UI previews** workflow (`.github/workflows/ui-previews.yml`) runs on every push to a branch other than `main`.
It isn't part of `ci-green`.

1. It draws the branch with `scripts/render-ui-previews.sh`, then the merge base with the same script.
2. `scripts/ui_previews.py` compares the two runs file by file and lays out a gallery.
3. The gallery goes to `refs/previews/<branch>` as one parentless commit, force-pushed each time. It's outside
   `refs/heads`, so a normal clone never downloads it. Its `README.md` is the gallery on GitHub, and the run's
   summary links it.
4. If the branch has an open pull request, a before/after table goes into the description between
   `<!-- ui-previews:start -->` and `<!-- ui-previews:end -->`. The Layoutlib renders come first; the JVM ones are
   folded underneath. The table shows only what changed, what's new and what was removed.

The job fails only if a preview can't be drawn at all. A preview that merely looks different is what the gallery is
for.

## For agents

**With a working Gradle** (your machine, or a sandbox that can reach Google Maven):

1. Change the composable. Add or adjust a preview for the state you changed.
2. Run `./gradlew :shared:renderPreviews -Ppreview=<part of the id>`. It prints each PNG's path.
3. Open the PNGs and look at them, then iterate.
4. Run `scripts/render-ui-previews.sh build/ui-previews/local` for the Layoutlib versions before you push.
5. For a flow rather than a screen, run the relevant journey with `-PjourneyScreens`. Read the flipbook in
   `shared/build/journey-screens/`.

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

The JVM renderer needs nothing beyond that network access and a JDK.
