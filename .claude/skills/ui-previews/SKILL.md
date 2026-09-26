---
name: ui-previews
description: See what a Compose UI change actually looks like. Use when changing or reviewing any composable in HomeSafe (shared/src/commonMain/.../ui), when asked for screenshots of a screen, or when a pull request touches UI. Renders @Preview functions to PNGs (JVM or Layoutlib), captures integration-journey screens, or fetches the images CI drew when this machine can't build.
---

# Looking at UI changes

Don't sign off on a UI change you haven't looked at. Render it, open the PNGs with the Read tool, and compare them
with what was asked for. The full reference is `docs/ui-previews.md`.

## 1. Make sure there's a preview of the state you changed

Previews live in `shared/src/commonMain`, next to the composable (see `ui/screens/HomePreviews.kt`). They're
no-argument `@Preview` composables that draw the stateless composable from fixtures.

- Screens get `@Preview(name = "...", widthDp = 412, heightDp = 915)`.
- Components get no size, and are wrapped in `FrigateTheme`, not `FrigatePreview`.
- Keep previews private, as compose-rules requires. The exception is a preview reviewers should see as
  Android draws it: make it public in a file that suppresses `compose:preview-public-check`, like
  `HomePreviews.kt`. Then add a one-line `@PreviewTest` wrapper to
  `androidApp/src/screenshotTest/.../SharedPreviewScreenshots.kt`.

## 2. Render and look

First check whether Gradle works here:

```bash
./gradlew :shared:renderPreviews -Ppreview=<part of the preview id> --console=plain
```

- **It works.** It prints `shared/build/previews/<id>.png` for each preview it drew. Read the images, fix, re-run.
  A preview that throws fails the task with its stack trace.
- **Android-exact renders:** run `scripts/render-ui-previews.sh build/ui-previews/local` (needs the Android
  SDK). Then read `build/ui-previews/local/android/*.png`.
- **Whole-app flows:** run `./gradlew :shared:jvmTest -PjourneyScreens --tests '*<Name>JourneyTest*'`. Then read
  `shared/build/journey-screens/<journey>/NN-*.png`, one image per tap, plus `end` or `failed`.
- **It can't resolve plugins** (for example a sandbox that blocks `dl.google.com`): commit, push the branch, then
  run the fetch script below. The **UI previews** workflow draws everything on CI.

```bash
scripts/fetch-ui-previews.sh --wait
```

It waits for the pushed commit's renders, then unpacks them into `build/ui-previews/<branch>/`. Read `README.md`
there first: it lists changed previews as before and after, then new ones, then the rest. `summary.json` has the
same as data. If the run failed, look at the "UI previews" run's "Draw the branch" step (the `actions_list` and
`get_job_logs` GitHub tools).

## 3. In the pull request

You don't need to paste screenshots. The UI previews workflow keeps a before/after table in the PR description,
between `<!-- ui-previews:start -->` and `<!-- ui-previews:end -->`, on every push. Don't edit that block by hand.
Describe what changed visually in your own words above it.
