# Compose Performance Audit — 2026-10-08 — the weather app (`:shared`, Android)

Method: the skydoves *compose-performance-skills* Measure → Diagnose → Fix → Verify loop
(auditing-compose-performance, testing-compose-in-release-mode, generating-baseline-profiles,
optimizing-lazy-layouts, configuring-lazy-prefetch, deferring-state-reads). The complaint was
stutter while scrolling the weather app in the release build.

## Environment
- Compose Multiplatform 1.12.0 (androidx Compose Foundation 1.12.0), Kotlin 2.4.20, AGP 9.5.0-alpha07
- Build: `benchmarkRelease` (release bytecode, R8 full mode, profileable, debug-signed), installed
  beside the Play app as `com.meticulouscreations.homesafe.benchmark` (`-PbenchmarkAppIdSuffix=.benchmark`)
- Device: **Pixel 10 Pro XL**, Android 17 (API 37), 1080×2404 at 120 Hz. The Play build on it is
  `speed-profile` from `install-dm`, which is what `CompilationMode.Partial(Require)` measures.
- Benchmark: `WeatherScrollBenchmarks`, 5 iterations, a **new process each iteration**; sign-in and
  opening the weather app are in `setupBlock`. The measured block is about 13 s: one slow drag,
  three flings down and three up on Today, the Forecast tab, a fling down and up, back to Today.
- Each run is a fresh install, so iteration 0 is the **first session after an install or update**
  (no GPU shader cache) and iterations 1–4 are **later sessions**. They are reported apart.
- What the sky draws is the place's weather at that minute. Runs were made for four places:
  home (sunny day), Seattle (overcast day), Belém (drizzle), Mumbai (clear night).

Beyond Macrobenchmark's own metrics, each trace was read with Perfetto's trace processor for the
frame timeline's jank types, frames over 16.6 ms (two refreshes at 120 Hz), and UI-thread
(`Choreographer#doFrame`) and RenderThread (`DrawFrames`) times.

## Baseline (Phase 1)
Later sessions (iterations 1–4), per 13-second journey:

| Sky | frameDurationCpuMs P50 / P90 / P99 | frameOverrunMs P95 / P99 | Deadlines missed | Frames > 16.6 ms | UI frames > 16.6 ms (longest) | Frames a refresh late (buffer stuffing) |
| --- | --- | --- | --- | --- | --- | --- |
| Home, sunny (3 runs) | 5.2 / 6.7–6.8 / 10.5–10.9 | −5.7…−5.4 / 0.4…1.5 | 3.8–4.8 | 3.2–5.5 | 2.0–2.5 (41–47 ms) | 11–50% |
| Seattle, overcast | 5.1 / 6.7 / 10.2 | −5.5 / 0.7 | 4.8 | 3.8 | 2.0 (50 ms) | 15% |
| Belém, drizzle | 5.1 / 6.8 / 10.8 | −1.2 / **5.3** | 5.8 | **17.0** | 2.8 (51 ms) | 38% |
| Mumbai, clear night | 5.0 / 6.4 / 10.3 | −4.1 / 1.5 | 5.0 | 4.8 | 2.0 (54 ms) | 18% |

First session after an install (iteration 0), home: 7–12 deadlines missed, 19–23 frames over
16.6 ms, and 20–23 shader pipeline compilations of more than 2 ms on the RenderThread, about
200 ms in all, the largest 23 ms each.

Compilation mode, home sky, same journey: no compilation 8.2 deadlines missed and 8.2 frames
over 16.6 ms; the shipped profile 3.8–4.8 and 3.2–5.5; everything compiled 3.8 and 3.2.

## Diagnosis (Phase 2)
- **Stability is clean.** From `shared/stability/shared.stability`: every weather screen
  composable is skippable with stable parameters; the only non-skippable one that draws is
  `ScaleBar` (`List<Color>`), which is trivial. No hot state is read in composition: the sky's
  frame counter, the scroll dim and the radar clock are all read in draw or layer blocks.
- **The Baseline Profile had no weather rules** (generated 2026-09-06, before the weather app),
  but that is not the stutter: full compilation is no smoother than the shipped profile.
- Ranked by frequency × cost, what the traces show:
  1. **Changing tab composes the other tab from nothing**: one UI-thread frame of 34–54 ms at
     every Today ↔ Forecast switch (`Crossfade` dropped the tab it left).
  2. **A wet sky cannot be shaded at the display's rate.** The sky was redrawn on every frame,
     116 frames a second whether or not anything moved, and a scroll frame ran every sky shader
     again. With rain that is 17 frames over 16.6 ms per journey and 38% of frames a refresh late.
  3. **A large lazy item composed under the finger.** The details are nine tiles in one item and
     the hourly strip twenty-six columns; when prefetch has not finished one before it scrolls in,
     the rest is one frame of 50–60 ms (seen on the Mumbai page, whose first drag brings the
     details in).
  4. **First session after an install or update**: ~22 pipeline compilations mid-scroll, as each
     kind of draw (the moon tile's shader, the radar card's two, gradients) is first seen. The
     cache lives in `code_cache`, which Android clears on every app update.
- The RenderThread spends about 4.3 ms a frame whatever the sky (it is CPU time building ~250
  draw operations and two or three render passes); the sky's own cost is on the GPU.

## Fixes applied (Phase 3)

| Skill | Change | Files | Measured |
| --- | --- | --- | --- |
| deferring-state-reads (draw-phase work, at the rate it needs) | The sky steps at 30 frames a second, 60 while rain or snow falls or it is easing to a new scene, not on every display frame. A sky that is costly to shade (rain, snow, stars) is kept in an offscreen layer on Android, so a scroll frame copies it. | `weather/ui/sky/WeatherSky.kt`, `weather/ui/shader/WeatherShader*.kt` | Belém drizzle (four runs, each with the tab fix also in): frames > 16.6 ms 17.0 → 3.0–4.5, frames a refresh late 38% → 6–12%, frameOverrunMs P99 5.3 → 0.8–1.8 ms. Alone, on the home and Seattle skies, it changed no deadline count; frames drawn during the journey went 116/s → 92/s. |
| recomposition (composition cost at a tap) | Both tabs of a place stay composed; a tab change fades between two layers. The tab behind is first composed 700 ms after the page appears and follows data changes only while the lists are at rest. | `weather/ui/WeatherApp.kt` | UI frames > 16.6 ms per journey 2.0–2.8 → 0–0.5; longest UI frame 41–54 ms → 12–25 ms. `AndroidOwner:measureAndLayout` 131 → 78 ms per journey. |
| configuring-lazy-prefetch | `LazyLayoutCacheWindow(2 viewports ahead and behind)` on a place's two lists: the page is a dozen cards, all kept composed and built ahead of the scroll. | `weather/ui/WeatherApp.kt` | Mumbai page, same hour: longest UI frame 60.9 → 14.4–14.8 ms, UI frames > 16.6 ms 1.2 → 0, recomposition during the journey 88 → 34–38 ms. No change on the home page, where no such frame occurred. |
| generating-baseline-profiles | The generator's journey opens and scrolls the weather app; profile regenerated on the Pixel. | `baselineprofile/**`, `androidApp/src/release/generated/baselineProfiles/` | 34,477 → 47,533 rules, 2,200 of them for `weather`. Expected gain is small (see compilation modes above). |

Tried and reverted, for showing nothing measurable: setting the last place tab in the tap handler
instead of an effect, and reading the radar card's `animated` flag through a lambda (UI frames
over 8.3 ms 6.0 → 6.0). An unconditional sky layer was also tried first: on a plain day's sky it
only added a render pass, so the layer is now for costly skies alone.

## Verification (Phase 4)
Final code, later sessions, against the baseline above:

| Sky | frameDurationCpuMs P50 / P90 / P99 | frameOverrunMs P95 / P99 | Deadlines missed | Frames > 16.6 ms | UI frames > 16.6 ms (longest) | Frames a refresh late |
| --- | --- | --- | --- | --- | --- | --- |
| Home, sunny (2 runs) | 5.2–5.3 / 7.4 / 11.4–11.5 | −4.6…−4.5 / −0.3…0.9 | 2.0–3.2 | 0.8–1.8 | 0–0.2 (12–18 ms) | 7–9% |
| Seattle, overcast | 5.2 / 7.4 / 11.5 | −4.7 / 0.4 | 2.8 | 1.5 | 0 (13 ms) | 5% |
| Belém, drizzle | 5.4 / 7.1 / 10.9 | −1.9 / 1.4 | 2.5 | 4.0 | 0.5 (24 ms) | 12% |
| Mumbai, clear night | 5.3 / 7.4 / 11.3 | −2.6 / 0.0 | 2.2 | 3.5 | 0 (14 ms) | 14% |

- **What got worse:** `frameDurationCpuMs` P90 is up about 0.6 ms and P99 about 1 ms on every sky
  (6.7 → 7.4, 10.5 → 11.4). RenderThread frames over 8.3 ms went from 2.5–6.5 to 9–15 per
  journey on the dry skies (3 on the rainy one). These frames still meet their deadline (the overrun percentiles are level or better),
  but the cause was not pinned down.
- A baseline control was re-run at the end of each batch (home: 3.8 and 4.8 deadlines missed,
  4.0 and 5.5 frames over 16.6 ms), so the differences are not drift. The phone's battery went
  from 31 °C to 38 °C over the session.
- The Belém and Seattle rows were measured before the cache window was restored; the last Belém
  run with it lost an iteration to a sign-in failure and the rain had stopped by then.
- Baseline Profile regenerated: yes, on the Pixel, and packaged (`assets/dexopt/baseline.prof`).
- Stability gate: `:shared:stabilityCheck` passes; no composable's signature changed.
- 647 weather JVM tests pass (including `WeatherAppUiTest`), ktlint passes on the touched source sets.

## How to re-run
```bash
# Scroll benchmark on a phone that has the Play app installed, for a chosen sky
ANDROID_SERIAL=<device> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -PbenchmarkAppIdSuffix=.benchmark \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meticulouscreations.homesafe.baselineprofile.WeatherScrollBenchmarks \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.weatherPlace=Seattle
# Profile
ANDROID_SERIAL=<device> ./gradlew :androidApp:generateBaselineProfile -PbenchmarkAppIdSuffix=.benchmark
```
Compare runs of the same place made within the hour. Iteration 0 of a run is the cold-shader-cache session.

## Open items / follow-ups
- **First session after an update is unfixed**: ~22 pipeline compilations, ~200 ms, during the
  first scroll. HWUI has no precompile API; a warm-up would have to draw each shader once, in the
  same clip and target as its real use, before the cards scroll in. Every merged pull request
  ships an update, so this is the session most often seen in testing.
- **The weekly Baseline Profile workflow has failed both of its runs** (2026-09-28, 2026-10-05)
  at "Check for the pull request token": `BASELINE_PROFILE_TOKEN` is not set (see
  docs/testing.md). Its journey now includes the weather app, which needs the internet and, on
  the CI emulator's software GPU, draws the sky very slowly; watch its first run.
- **Home's live players keep decoding under the weather app** for the foreground idle window
  (`LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS`, 90 s): three decoders and their threads took
  about 30% of a core in the app plus 22% in the codec service throughout the journey. Its effect
  on weather frames was not measured (a run that waits out the window would show it).
- A change of tab still costs two or three UI frames of 5–9 ms (the tab state, then an effect's
  write, then the radar's visibility reaching the view model each recompose the page).
- The once-a-minute clock tick recomposes the whole page; with both tabs kept, the one behind
  now follows a frame later while the lists are at rest.
- Android 17 asks for "nearby devices" (`ACCESS_LOCAL_NETWORK`) right after sign-in; the journeys
  grant it up front (`grantPermissions`), as the older Home benchmarks would also need on a phone.
- `HourlyStrip`'s `drawBehind` rebuilds its path and gradient on every draw; it redraws only
  when the strip itself is scrolled sideways, which this journey does not do. Not measured.
