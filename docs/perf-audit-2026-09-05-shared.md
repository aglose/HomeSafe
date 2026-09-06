# Compose Performance Audit — 2026-09-05 — `:shared` (Android)

Method: the skydoves *compose-performance-skills* Measure → Diagnose → Fix → Verify loop, plus the
Android R8 analyzer and CLI skills. Everything below was measured on the **benchmarkRelease**
variant (release bytecode, R8 full mode, debug signing) with Macrobenchmark; see the
`:baselineprofile` module for the harness.

## Environment
- Compose Multiplatform 1.12.0 (Compose UI 1.10-line), Material3 1.12.0-alpha03
- Compose Compiler = Kotlin 2.4.10 plugin (strong skipping, intrinsic remember, pausable composition all on)
- AGP 9.4.0, Gradle 9.7.1, R8 full mode (AGP default)
- Device: **HomeSafeSmoke AVD** (`sdk_gphone64_arm64`, API 36, booted with `-memory 4096 -cores 4`, Apple M4 Pro host). An emulator, so
  the absolute numbers are not what a phone sees; only the *deltas* between runs on the same AVD
  are meaningful. The user's Pixel 10 Pro XL is deliberately not driven by automation.
- Benchmarks: `StartupTimingMetric`, COLD, 10 iterations; `FrameTimingMetric`, WARM, 5 iterations,
  Home feed fling down/down/up after an unattended sign-in in `setupBlock`.

## Baseline (Phase 1) — HEAD `4e927a5`
- Release build type: `isMinifyEnabled = false` (no R8 at all), no Baseline Profile, no compiler
  reports, no stability gate, no `ReportDrawn`.
- Numbers: see run **A** in the table below.

## Diagnosis (Phase 2)
From `shared/build/compose_compiler/HomeSafe:shared-*.txt` (release Android compilation):

| Metric | Value |
| --- | --- |
| Composable functions, restartable | 76 |
| …of which skippable | 76 (100%) |
| Restart groups incl. lambdas / skippable | 282 / 161 |
| Known-unstable arguments (module-wide) | 3 |
| Unstable classes | 32 — ViewModels, repositories, DAOs, DI graph, `LivePlayerHolder`, `TopLevelBackStack`, `RecordingPlaylist` (DoubleArray field) |
| Phase-misplaced (composition-phase) hot reads found by review | 2 |

Stability is not the problem in this codebase: strong skipping already makes every restartable
composable skippable, UI state classes are `@Immutable`, every list is keyed, and flows are
collected with `collectAsStateWithLifecycle`. The compiler-visible unstable classes are held
by reference and compared by instance, so they do not defeat skipping. The real costs were
runtime, ranked by frequency × cost:

1. **`PulsingDot` recomposed every animation frame.** `transition.animateFloat(...)` was read
   with `by` in the composable body, so each dot (one per camera card on Home, plus the header,
   LIVE pills and in-progress Moments) re-ran composition at 60 Hz. Draw-phase work masquerading
   as composition-phase work.
2. **`CameraDetailScreen` recomposed as a whole four times a second** during recording playback
   (the 250 ms position poll updates `playback`), and on every pixel of a timeline drag — the
   root collected the `playback` flow and passed it down, so the header, quick actions,
   detection-zones card and recent-activity cards all re-entered composition.
3. **`RecordingTimeline` re-laid-out its tick labels on every draw** (`textMeasurer.measure` per
   tick, inside the Canvas lambda) although the ticks only change once a second, and rebuilt the
   tick list / max-motion scan on every recomposition.
4. **No R8** on release: interpreted-style Compose runtime, no lambda grouping, no
   `sourceInformation` stripping, no `ComposerImpl` devirtualisation; 11.1 MB APK.
5. **No Baseline Profile**: Compose ships unbundled, so cold start and first scroll ran JIT-cold.
6. Minor: date headers in the Moments feed and the mixed list on the classifier screen had no
   `contentType`; the zone editor re-measured its labels on every drag sample.

## Fixes applied (Phase 3)

| Skill | Change | Files |
| --- | --- | --- |
| build/configuring-r8-for-compose, android r8-analyzer | R8 on for release: `isMinifyEnabled`/`isShrinkResources` true, `proguard-android-optimize.txt`; no custom keeps needed (AGP's R8 config analyzer shows only library consumer rules; Compose's own are `allowshrinking,allowobfuscation`) | `androidApp/build.gradle.kts` |
| measurement/testing-compose-in-release-mode | Compiler reports behind `-PcomposeCompilerReports=true`; `benchmarkRelease` variant (from the baselineprofile plugin) carries the local test credentials so journeys can sign in; autofill gate keyed on the value, not `BuildConfig.DEBUG` | `androidApp/build.gradle.kts`, `shared/build.gradle.kts`, `MainActivity.kt` |
| recomposition/deferring-state-reads | `PulsingDot`: keep the animation as `State<Float>`, read it only inside `graphicsLayer { }` | `ui/components/PulsingDot.kt` |
| recomposition/deferring-state-reads (scoping the read) | `CameraDetailScreen`: root no longer collects `playback`/`alerts`; `PlayerSurface`, new `QuickActionsRow`, and `TimelineSection` collect what they need | `ui/screens/CameraDetailScreen.kt` |
| recomposition (composition-phase work), draw caching | `RecordingTimeline`: `remember` the tick list, the measured tick labels, and `maxMotion`; draw uses the cached layouts | `ui/components/RecordingTimeline.kt` |
| lists/optimizing-lazy-layouts | `contentType` for Moments date headers and the classifier screen's mixed items; stable keys on the one-off items | `ui/screens/MomentsScreen.kt`, `ui/screens/ClassifierLabelingScreen.kt` |
| draw caching | `MaskPolygonEditor`: zone-label text layouts remembered across drag redraws | `ui/components/MaskPolygonEditor.kt` |
| stability/stabilizing-compose-types | `compose_stability_config.conf` declares `kotlinx.datetime.*`, `kotlin.time.Instant/Duration` stable for future parameters | `compose_stability_config.conf`, `shared/build.gradle.kts` |
| measurement/generating-baseline-profiles | `:baselineprofile` module: generator (cold start → sign-in → Home scroll), startup + scroll benchmarks in None vs Require modes; `ReportFullyDrawnWhen` (expect/actual) on the sign-in form and Home; `testTag("home_feed")` + `testTagsAsResourceId` | `baselineprofile/**`, `ReportFullyDrawn*.kt`, `HomeLiveViewScreen.kt`, `SecureConnectionScreen.kt` |
| stability/enforcing-stability-in-ci | skydoves `compose-stability-analyzer` 0.13.0 on `:shared`; baseline committed at `shared/stability/shared.stability`; `./gradlew :shared:stabilityCheck` fails on regression (`-PcomposeStabilityStrict=false` to demote locally) | `shared/build.gradle.kts`, `shared/stability/` |

Not done, on purpose: no `@Stable` on ViewModels or `ImmutableList` rewrites (nothing hot is
non-skippable, and skippability is a diagnostic, not the goal); no `derivedStateOf` (no
high-frequency input feeding a low-frequency output); no `Modifier.composed` to migrate; no
`BoxWithConstraints`/`SubcomposeLayout` in the tree; lazy prefetch left at the Foundation 1.10
defaults (three cards per screen).

## Measurements

Runs on the HomeSafeSmoke AVD, `benchmarkRelease`, medians of 10 (startup) / P50-P90-P99 of the
frames in 5 iterations (scroll). Δ is against the previous row.

| Run | Build | Startup TTID median (ms) | Scroll frameDurationCpuMs P50 / P90 / P99 | Scroll frameOverrunMs P50 / P90 / P99 |
| --- | --- | --- | --- | --- |
| A | HEAD code, R8 **off**, `CompilationMode.None` | 242 | 18.9 / 33.2 / 34.2 | 3.0 / 19.4 / 20.5 |
| B | HEAD code, R8 **on**, None | 188 (−22%) | 3.4 / 32.1 / 33.6 | −12.2 / 19.3 / 20.4 |
| C | fixed code, R8 on, None | 180 | 18.3 / 19.0 / 21.8 | 2.4 / 3.1 / 5.9 |
| D | fixed code, R8 on, **Baseline Profile required** (degraded window; control startup None = 1238) | 1191 | 37.7 / 69.3 / 180.1 | 36.4 / 84.4 / 251.5 |
| D′ | same, fresh settled emulator: startup None → Require, then scroll None | 181 → 176 (−3%) | 39.4 / 77.3 / 184.8 (None; decoder-heavy window) | 40.4 / 97.2 / 260.5 |

Run D above was measured ~10 minutes after C, by which time the emulator had degraded (guest load
average 11 on 4 vCPUs with idle userland, i.e. hypervisor/IO overhead): a repeat of C's scroll test
at that moment gave 39.5 / 70.6 / 163.1 and a repeat of the *no-profile* startup gave **1238 ms** —
so against its contemporaneous control the profiled build was 4% faster on startup and equal on
scroll, not 6× slower. D is therefore re-measured on a freshly booted, settled emulator in the
table's D′ row. Emulator numbers drift with wall-clock time; only same-window pairs are comparable.

**The scroll benchmark is video-dominated, so its emulator deltas are not attributable to code.**
Perfetto traces from the runs show why C and C3 (identical code) differ: in C the H.264 decoder
service used 1.3 s of CPU over the 15 s iteration and the app 3.9 s; in C3 the decoder used 7.2 s
and the app 18.9 s. The Home cards play three live streams, and how much they decode depends on
go2rtc/stream state at that minute, not on Compose. The A→B→C scroll rows are therefore reported
but *not claimed* as code-attributable; the recomposition-count measurement below is what verifies
the UI fixes, and `FrameTimingMetric` for this surface needs a physical device and a controlled
stream (or a fixture camera).

Discarded: a first A/B pair was run while the Pixel_9a emulator was also up with HomeSafe in the
foreground decoding three live streams (host load average ~15). B came out 7× slower than A across
every metric, which is host contention, not R8 — the pair was thrown away and the whole sequence
re-run with only the Smoke AVD alive. Lesson recorded: one emulator at a time when benchmarking.

## Recomposition counts (the code-attributable measurement)

`@TraceRecomposition` (skydoves compose-stability-analyzer runtime, `threshold = 1`) on the four
composables, debug build on the same AVD, same journey, counts over fixed 15-second windows:

| Window | Composable | HEAD `4e927a5` | With fixes |
| --- | --- | --- | --- |
| Home, live cards, no input | `PulsingDot` (4 on screen) | 3,640 | 0 |
| Home, live cards, no input | `CameraCard` / `HomeTabContent` | 0 / 0 | 0 / 0 |
| Camera detail, live | `PulsingDot` (LIVE pill) | 894 | 0 |
| Camera detail, live | `CameraDetailScreen` root | 7 | 0 |
| Camera detail, recording playback | `CameraDetailScreen` root | 61 (≈4 Hz position poll) | 0 |

3,640 in 15 s is four dots × 60 frames × 15 s: every animation frame re-ran composition for each
dot. 61 in 15 s on the detail root is the 250 ms position poll recomposing the entire screen. Both
are gone with the fixes; the animation and the playhead still update (draw-phase and scoped reads).

## Verification (Phase 4)
- Side note: a second agent, at the user's request, gave the debug variant the `.debug` application id and label and added a real release signing config (keystore gitignored, see its own report); the release APK is now installable next to debug. Independent of the perf work.
- Baseline Profile generated on the AVD from the cold-start → sign-in → Home-scroll journey:
  `androidApp/src/release/generated/baselineProfiles/baseline-prof.txt` (34,477 rules; 1,950 for
  app classes, 15,784 for `androidx.compose`) plus `startup-prof.txt` for dex layout. Verified in the R8 release APK: `assets/dexopt/baseline.prof` (14 KB, post-R8 mapped) and `baseline.profm`.
- CI stability gate: `:shared:stabilityCheck` passes against the baseline in `shared/stability/shared.stability`.
- Nothing in this audit is committed yet: the working tree holds the build changes, the six UI fixes, the `:baselineprofile` module, the generated profile and the stability baseline. Suggested split: (1) R8 + build config, (2) UI fixes, (3) benchmark module + profile + gate.
- R8 release build installs and runs end to end on the emulator (sign-in over Tailscale, Home with
  three live cameras, no crashes) — the only release-behaviour risk from turning R8 on.

## How to re-run
```bash
./gradlew :androidApp:assembleRelease -PcomposeCompilerReports=true   # reports → shared/build/compose_compiler/
ANDROID_SERIAL=<device> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
ANDROID_SERIAL=<device> ./gradlew :androidApp:generateBaselineProfile     # then commit the .txt
./gradlew :shared:stabilityCheck                                          # CI gate
./gradlew :shared:stabilityDump                                           # refresh baseline deliberately
```

## Open items / follow-ups
- Repeat runs A–D on the Pixel (or any physical phone) before quoting numbers externally; the
  emulator has no thermal envelope and its GPU path is translated.
- `HomeScrollBenchmarks` signs in on every iteration; a signed-in Room/credential state would
  shorten it and let `StartupBenchmarks` measure cold start *to Home* instead of to the form.
- The `stabilityCheck` gate is wired but not yet in a CI workflow (the repo has none); add
  `./gradlew :shared:stabilityCheck` to whichever job compiles.
- `CameraDetailScreen` still recomposes `PlayerSurface` and `TimelineSection` at 4 Hz during
  playback by design (the playhead is real state there). If that ever shows up in traces,
  the next step is a `() -> Double` playhead provider into a draw-phase-only timeline.
