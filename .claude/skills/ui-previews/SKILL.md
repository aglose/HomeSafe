---
name: ui-previews
description: See what a Compose UI change actually looks like. Use when changing or reviewing any composable in HomeSafe (shared/src/commonMain/.../ui, features/*/src/commonMain/.../ui), when asked for screenshots of a screen, or when a pull request touches UI. Renders @Preview functions to PNGs through Layoutlib, or fetches the images CI drew when this machine can't build.
---

# Looking at UI changes

Don't sign off on a UI change you haven't looked at. Render it, open the PNGs with the Read tool, and compare them
with what was asked for. The full reference is `docs/ui-previews.md`.

## 1. Make sure there's a preview of the state you changed

Previews live next to the composable, in `shared/src/commonMain` or a feature module's
(`features/<name>/src/commonMain`; see `ui/screens/HomePreviews.kt`). They're
no-argument `@Preview` composables that draw the stateless composable from fixtures.

- Screens get `@Preview(name = "...", widthDp = 412, heightDp = 915)`.
- Components get no size, and are wrapped in `FrigateTheme`, not `FrigatePreview`.
- Keep previews private, as compose-rules requires, and top-level. There's nothing to register: every
  `@Preview` in `:shared`, the features and `:app` is drawn. Its `@Preview` arguments must be literals or a
  `const val` of the same file (the wrappers are generated from source).

## 2. Render and look

The app is Android and iOS only, so previews are drawn by Layoutlib (Compose Preview Screenshot Testing),
as Android draws them. Iterate with the preview server, which keeps Layoutlib loaded: it takes comma-separated
words matched against each preview's module, function and name, and prints the PNGs it drew.

```bash
scripts/preview --list
scripts/preview BudgetOverview,FinanceBankSync     # PNGs in build/ui-previews/live/
scripts/preview --all                              # everything, before you finish
```

- **It works** (needs the Android SDK). Read the PNGs it prints, fix, re-run: about a second plus Gradle compiling
  what you changed. The first call in a checkout starts the server (~10–20 s). Before you finish, run `--all`, to
  catch a shared component you changed elsewhere.
- **A preview that throws** prints its exception with your file and line; the script exits non-zero.
- **If `scripts/preview` itself breaks** (it leans on screenshot-engine internals, so an AGP or engine upgrade can
  do that), fall back to `scripts/render-ui-previews.sh build/ui-previews/local --only <words>`, ~15 s a round,
  and say so. It's also what CI runs.
- **Layoutlib draws one frame.** Animations show their first frame, and nothing is clicked or scrolled.
- **Whole-app flows** have no screenshots any more (they came from the JVM journeys); the journeys run on CI's
  emulator, and a failing one prints the screen's semantics tree.
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
