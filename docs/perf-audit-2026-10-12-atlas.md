# Compose Performance Audit — 2026-10-12 — the atlas app (`:shared`, Android)

Method: Measure → Diagnose → Fix → Verify, as in `perf-audit-2026-10-08-weather.md`. The
complaint was that the atlas app is not smooth, the map most of all.

## Environment
- Compose Multiplatform 1.12.0, Kotlin 2.4.20, AGP 9.5.0-alpha07.
- Build: `benchmarkRelease` (release bytecode, R8, profileable), installed as
  `com.meticulouscreations.homesafe.benchmark` (`-PbenchmarkAppIdSuffix=.benchmark`), compiled
  with the shipped Baseline Profile (`CompilationMode.Partial(Require)`).
- History: a real one, fetched from the relay at sign-in: over a decade of it, some tens of
  thousands of stays and paths, counted into a few hundred squares at the coarsest level and
  about a million and a half at the finest. (The fixture in the repository is a hundredth of
  that, and shows none of what follows.)
- Benchmark: `AtlasBenchmarks`, two journeys, each a new process per iteration with sign-in and
  opening the atlas in `setupBlock`:
  - `map`: the map with everything on it, mostly zoomed: pinched out and in at a town's scale, at
    the whole world's (every flight and place in view) and right in (where the paths themselves
    are drawn), with drags and flicks, the glides home ↔ everywhere, the zoom buttons, a double
    tap, and the years played.
  - `browse`: Days, Places, Trips and Patterns each flung down and back, a place opened from the
    list, a day opened from the calendar and played.
- **Device: mostly the `CiApi34` emulator (API 34, 1080×2400, 60 Hz) on this Mac, not the Pixel.**
  The Pixel 10 Pro XL was used for the first baseline run only and was then needed back. An
  emulator's graphics go through the host's GPU by a translation layer, so its frame times are
  not a phone's: a draw call costs far more there than on a phone and a shader pixel far less.
  What carries over is what was drawn and on which thread: operation counts, UI-thread time, and
  differences of orders of magnitude. The Mac was also under load from other builds for much of
  the session; runs made then are marked.

## Baseline (Phase 1)

**Pixel 10 Pro XL**, `map` (an earlier, shorter version of the journey, about fifteen gestures):
one pass had not finished after 3½ minutes, when the run was stopped to give the phone back. Its
trace was 830 MB and could not be opened on this Mac. No frame numbers, then, but a journey that
is about 25 seconds of input took more than eight times that.

**Emulator**, the same journey: one pass was 322 seconds in when it failed (a stale node, since
fixed in the journey). In those 322 seconds:

| | |
| --- | --- |
| Frames drawn | 221 (0.7 a second) |
| Frame duration P50 / P90 / max | 611 ms / 5.3 s / 10.8 s |
| UI thread per frame, P50 | 2.0 s (almost all of it waiting for the RenderThread) |
| RenderThread per frame, P50 / max | 532 ms / 47.8 s |
| Draw operations per frame | about 10,300 circles and 2,970 round-ended lines, none batched |
| Texture copies made for blending | 14,646 |

The map at rest, on the same build: 7 frames in half a second, 150 ms each.

**Emulator**, `browse`: 58 s, 3,286 frames, frame duration P50 18.8 / P90 22.3 / P99 76.9 ms.
The UI thread's longest frames: 1,627 ms opening the page of the place most time was spent at
(its small map, close in), 413 ms after it, 267 ms going back to the Map tab.

## Diagnosis (Phase 2)

1. **The glow was thousands of separate draws, every frame.** Far out, each counted square was a
   round point drawn with `BlendMode.Lighten`. That blend has to read what is already there, so
   no two overlapping dots can be batched: about ten thousand operations a frame, each its own
   draw, and on a GPU without the advanced blend equations a copy of the target for each
   (`glGenTextures` 14,646 times). Close in, every path whose box touched the view had every one
   of its stretches drawn, on or off screen: near home that is almost every path there is,
   gathered into a buffer on the UI thread (the 1.6 s frame) and drawn as thousands of rounded
   rectangles.
2. **Three shaders over the whole screen on every display frame, moving or not.** The night under
   the map, the tiles' tone and the glow's bloom all redrew whenever the shader clock moved,
   which was every frame; the bloom looked thirteen times round every pixel.
3. **The backdrop of every other tab** (contour lines, nine noise lookups a pixel) was shaded
   again on every frame, including every frame of a list scrolling over it.
4. **The overlay allocated as it drew**: a `Path` and two strokes for each of up to 400 flights,
   a new gradient for each of 60 places, a sort of the places and a text measure for each name,
   and a mercator projection (a logarithm and a tangent) of each, per frame.
5. Smaller things: the zoom buttons jumped instead of travelling, a drag stopped dead when let
   go, a glide asked for the base map's tiles at every level it passed through, the map forgot
   where it was whenever another tab was opened, and the paths were not read until the analysis
   had finished though neither needs the other.

Not the problem: composition. `Recomposer:recompose` was 112 ms over the whole 58-second `browse`
journey, and every atlas screen composable is skippable with stable parameters.

## Fixes (Phase 3)

| Change | Files |
| --- | --- |
| **The glow is a picture, made off the main thread and kept in squares** as the base map is kept in tiles: 256 pixels a side, at half the screen's resolution on a dense screen, four scales to each doubling of the map. Far out a square is rasterised from the counted squares, close in from the paths, clipped to it. A frame puts down the two dozen in view. A drag asks for those coming in at the edge (and a ring beyond is made ahead); a pinch stretches what it has until the next scale's arrive; a glide makes the squares for where it lands while it travels. | `atlas/domain/HeatRaster.kt`, `atlas/ui/AtlasHeat.kt`, `atlas/ui/AtlasMap.kt` |
| **The bloom is worked into the picture** as it is made (the same two rings, six looks each), so `ATLAS_HEAT_SHADER` looks once at each pixel instead of thirteen times. | `HeatRaster.kt`, `atlas/ui/shader/AtlasShaders.kt` |
| **The flights are drawn into the same pictures**, in a channel of their own, and coloured by the same shader. As one stroked path they were a mask the size of the screen, drawn on the CPU and handed to the GPU twice a frame (`Texture upload 1080x2400`). | `HeatRaster.kt`, `AtlasHeat.kt`, `AtlasShaders.kt` |
| **The map's own light steps 30 times a second**, not on every frame: the night's drift, the glow's shimmer, and the contour backdrop. At rest the map is drawn 30 times a second instead of 60 or 120. | `weather/ui/shader/ShaderClock.kt`, `AtlasMap.kt`, `AtlasWidgets.kt` |
| **The contour backdrop is a kept layer** on Android: shaded when it moves, copied for the frames between, so a list scrolling over it does not shade it again. The year and week grids likewise. | `AtlasWidgets.kt`, `DaysScreen.kt`, `PlacesScreen.kt` |
| **The overlay draws from what was worked out once**: places' positions and name layouts, flights' ends; one path for all the arcs; one gradient per kind of place; halos, then discs, so each kind is one batch. | `AtlasMap.kt` |
| **The map moves like a map**: a flick runs on and slows, the zoom buttons and a double tap travel, a finger put down stops whatever was moving. A glide asks for tiles only where it lands. The map is found where it was left when its tab is come back to. | `AtlasMap.kt`, `MapScreen.kt`, `AtlasApp.kt` |
| **Sooner to the glow**: the paths are read beside the analysis rather than after it, and the eleven levels are counted at once on threads of their own. | `AtlasViewModel.kt`, `atlas/domain/AtlasDensity.kt` |

## Verification (Phase 4)

All on the emulator, the Mac quiet, the same history. "Players idle" means the run waited out
Home's live players first (`-e atlasQuietHome true`, see Open items); "warm" that the journey had
been gone through once in the process already (`-e atlasWarm true`).

**`map`** (the longer, zoom-heavy journey described above; the baseline could not get through a
shorter one):

| | Baseline | After, players idle, new process | After, players idle, warm |
| --- | --- | --- | --- |
| One pass of the journey | not finished in 322 s | 48 s | 45 s |
| frameDurationCpuMs P50 / P90 / P99 | (611 / 5,300 / 8,000 frame duration) | 4.7 / 39.4 / 102.1 | 4.6 / 24.0 / 38.9 |
| frameOverrunMs P50 / P90 / P99 | — | −9.5 / 32.2 / 128.4 | −10.2 / 9.4 / 29.1 |
| UI thread per frame P50 / P90 | 2,000 ms / 2,900 ms | 1.0 / 23 ms | 0.7 / 6.8 ms |
| Frames over 33 ms | 151 of 221 | 253 of 1,877 | 3 of 1,917 |
| Drawn per frame for the glow | 10,300 circles and 2,970 lines, each a draw of its own | two dozen pictures | the same |
| Textures made per frame | 81 (a copy of the target for each blend that needed one) | under one (a square or a tile arriving) | the same |

The map at rest draws 30 frames a second, each about 2 ms of the render thread. Dragged by hand
(`adb shell input swipe`, players idle, a process a few minutes old), every frame of a drag at a
town's scale, the world's and a street's met its deadline: UI thread 0.4 ms (P90 0.9), drawing
1.2 ms (P90 1.6).

**`browse`**, baseline and after run back to back:

| | Baseline | After |
| --- | --- | --- |
| Players idle (3 iterations): frameDurationCpuMs P50 / P90 / P99 | 32.7 / 33.7 / 50.4 | 3.1 / 17.6 / 19.0 |
| Players idle: frameOverrunMs P50 / P90 / P99 | +18.4 / +19.6 / +54.0 | −11.7 / +1.9 / +3.2 |
| Straight from Home (5 iterations): frameDurationCpuMs P50 / P90 / P99 | 20.8 / 81.2 / 345.2 | 3.7 / 51.5 / 121.7 |
| Straight from Home: frameOverrunMs P50 / P90 / P99 | +5.1 / +99.2 / +512.5 | −11.2 / +53.8 / +157.8 |

- Time to the glow, from the tabs appearing, on a new process: reading the paths 90 ms, counting
  them 670 ms before the levels were counted side by side (not re-timed after), the analysis
  145 ms beside it rather than before it.
- A square of the glow takes a millisecond or two of one background thread once the code is
  compiled; the years played through (every square in view drawn again each 1.1 s) cost 0.1 s of
  background CPU in two seconds, warm.
- 83 atlas tests pass on the JVM, eleven of them new (`HeatRasterTest`) and thirteen the UI test;
  ktlint passes on the touched source sets; the Atlas previews render as before
  (`:shared:renderPreviews -Ppreview=Atlas`); `:shared:stabilityCheck` passes with the two
  signatures that changed (`MapScreen`, `rememberWeatherShaderClock`) brought up to date.
- **What got worse:** memory. The squares are up to 144 pictures of a quarter megabyte, so as
  much as 37 MB while a map is on screen, against none before. A flight's arc and the glow are
  drawn at half the screen's resolution on a dense screen and are a little softer for it. And the
  glow, the contours and the night under the map now move 30 times a second where they moved 60
  or 120.
- **Not measured:** anything on the Pixel after the fix. The numbers above say what is drawn and
  where; what a frame costs on the phone, and whether three shaders over the screen fit in 8.3 ms
  there while the map moves, has to be read off the phone (the command is below).

## How to re-run
```bash
ANDROID_SERIAL=<device> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -PbenchmarkAppIdSuffix=.benchmark \
  -Pandroid.testInstrumentationRunnerArguments.class=com.meticulouscreations.homesafe.baselineprofile.AtlasBenchmarks \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.atlasQuietHome=true
```
Add `atlasWarm=true` for a session already under way, `atlasIterations=3` for a shorter run, and
`#map` or `#browse` to the class for one journey. The account signed in (the build's test credentials) has to be one the relay gives the timeline
to. The first iteration after an install waits for the whole history to arrive.

## Open items
- **Home's live players go on decoding under the atlas for 90 seconds**
  (`LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS`), as the weather audit noted of the weather app.
  On the emulator that is video decoded on the CPU, and it is the largest thing left: with it, the
  `browse` journey's P90 is 51.5 ms; with the players idle, 17.6 ms. On a phone the decoding is
  the hardware's and the cost is surely smaller, but it has not been measured there, and it falls
  on every app opened from the drawer in its first minute and a half. The window is there so that
  going back to Home is instant; whether an app opened over Home should shorten it is a choice
  about Home, not about the atlas, and was left alone.
- **None of the atlas is in the Baseline Profile**, which was generated before there was one. A
  new process interprets it and then compiles it as it goes: in a new process the `map` journey's
  P90 is 39 ms, the second time through it 24 ms. The generator's journey should open the atlas;
  the weekly workflow runs against the fake Frigate, which has no relay and so no history, so it
  needs a history to open on first (the fixture would do).
- While the map moves at a town's scale the emulator's render thread is two to three times as
  busy as at the world's or a street's scale, for the same operations. Not found: nothing differs
  in what is drawn but the places' sixty halos being in view. It shows as 10–17 ms frames there,
  on an emulator.
- A day's or a trip's map still draws its route and its flights as paths: a few, and only while
  that map is moved or the day is played.
- The glow is not drawn across the date line (it never was); a flight that crosses it is.
- The emulator and the Mac it runs on were shared with other builds for much of the session.
  Runs made then are not quoted above; they were two to ten times slower throughout.
