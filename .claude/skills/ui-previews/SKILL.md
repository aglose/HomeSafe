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
as Android draws them. While iterating, draw only the previews you're working on: `--only` takes
comma-separated words matched against each preview's module, function and name, and `--list` prints them all.

```bash
scripts/render-ui-previews.sh --list
scripts/render-ui-previews.sh build/ui-previews/local --only BudgetOverview,finance_card
```

- **It works** (needs the Android SDK). Read `build/ui-previews/local/android/*.png`, fix, re-run. Before you
  finish, draw everything once without `--only`, to catch a shared component you changed elsewhere.
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
