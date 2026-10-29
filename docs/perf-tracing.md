# Tracing the app, and the 2026-10-28 audit of its heavy screens

Two things in one file: **how** HomeSafe is traced (Metro's runtime tracing, AndroidX Tracing 2.0,
a Perfetto system trace, scripted journeys on the emulator, and the scripts that turn the traces
into numbers), written so it carries over to another Android project; and **what** that found in
the heaviest screens (Home's live cameras, the drawer's Weather, Finance, Fitness and Atlas apps)
and what was changed.

## Results

On the `CiApi34` emulator (API 34, 1080×2400 at 60 Hz, `-gpu host`), `benchmarkRelease` compiled
`speed-profile` as Play would. Same journeys and the same build flags before and after.

| Measure (same journey, same conditions) | Before | After | Change |
| --- | --- | --- | --- |
| Video CPU while the drawer apps are used (tour: 14 steps, 132 s) | 50.8 core-s | 2.6–4.0 core-s | **12.7–19.5× less** |
| Video CPU, drawer apps left at rest (idle walk: 15 screens, 91 s) | 29.7 core-s | 2.5 core-s | **11.9× less** |
| Rest of the app's threads besides main and RenderThread (tour) | 15.4 core-s | 3.2–3.3 core-s | 4.7× less |
| Whole device, drawer apps in use (tour) | 1.03 cores | 0.36–0.41 cores | 2.5–2.9× less |
| Whole device, drawer apps at rest | 0.73 cores | 0.34 cores | 2.1× less |
| Fitness Today scrolling, frame P90 / P99 | 25.2 / 34.2 ms | 5.5–5.9 / 7.8–9.1 ms | ~4× |
| Fitness Lifts, frame P90 / P99 | 20.5 / 27.0 ms | 5.3–5.9 / 6.9–7.2 ms | ~3.8× |
| Weather scrolling, frame P90 / P99 | 25.8 / 40.4 ms | 6.8–7.2 / 15.3–15.7 ms | 3.6× / 2.6× |
| Finance Wallet / Budget, frame P99 | 36.3 / 34.9 ms | 10.8–12.1 / 12.5–15.4 ms | ~3× / ~2.5× |
| Frames drawn a second at rest: Weather, Fitness Today | 60 | 30 | 2× fewer |
| Worst frame opening a drawer app, first run after an install | 683–1,043 ms | 153 ms | 4.5–6.8× (single runs) |

Before: the unchanged code (with the tracing added, for the in-process numbers); after: this
change. Warm rows compare later runs with later runs, two after against one before, on a quiet
host (load ≈ 2–3; a run made while the CI runner was building on the same Mac came out up to 3×
worse and is not counted). "Video" is the threads that exist for live video: the H.264 decoder,
libwebrtc's, `decoder-texture`, `MediaCodec_loop`, ExoPlayer's and the app's EglRenderers.

What the 10× is, and isn't: the single biggest cost in the app was Home's video decoding on while
nobody could see it, and that cost fell **12–20×**. The device as a whole does 2.1–2.9× less work
in the drawer apps, because what remains is those screens' own drawing, much of it deliberate
ambient animation (below). Frame times improved most where the decoder had been competing with the
screen for the GPU. Nothing here claims a 10× on every number.

## The findings

### 1. Home's cameras kept decoding behind every other screen (fixed)

The trace's busiest threads over a tour of the app were not the app's UI threads but video: the
H.264 decoder threads (in the media HAL on an emulator), libwebrtc's `decoder-texture` (each frame
copied into a GL texture), `MediaCodec_loop` and libwebrtc's worker threads, together about a
third of a core, and up to 1.5 cores with the software GPU, for as long as the user stayed in
Weather, Finance, Fitness or Atlas.

Why: when a drawer app covers the shell, Home's content leaves the composition, its players lose
their last viewer, and `LivePlayerHolder` *pauses* them, keeping the connection for
`IDLE_STOP_FOREGROUND_MS` (90 s) so that coming back is warm. For WebRTC, pausing meant detaching
the video sinks. libwebrtc goes on receiving and decoding a track with no sinks, so "paused" cost
the same as playing. A parked standby peer (the grid stream while a camera's detail screen plays
the full-quality one) decoded too, for as long as the holder lived.

Fix (`WebRtcPeer.android.kt`): a peer whose video has been disabled for `RECEIVE_GRACE_MS` (5 s)
also calls `VideoTrack.setShouldReceive(false)`, which stops the track's receive stream, decoder
included, with the connection left up; enabling it starts the stream again. A glance away and back
inside 5 s never pauses. A peer is never paused before its first frame, because a join waits for
that frame. `park()` now disables a standby's video, so it pauses the same way. Resuming
costs one keyframe per camera. Measured on screen with `scripts/perf/back_to_home.py` (Finance
for 12 s, then back, ten times; `trace_report.py back`), from Home starting to draw again under the
closing app to each camera's renderer drawing frames again:

| Back by | Before: every camera moving | After: first camera | After: every camera (median, range) |
| --- | --- | --- | --- |
| Back button | 37 ms | 26–836 ms | 1,346 ms (423–1,893) |
| Back swipe | 33 ms | 26–819 ms | 905 ms (429–1,254) |

Meanwhile each card shows its last picture, already reporting Live, never the poster. One camera
is regularly the last by a second or so, which fits a longer keyframe interval on its stream. So
coming back after more than 5 s away now shows still pictures for up to about a second and a half
where it used to move at once: that is the price of the CPU saved, and `RECEIVE_GRACE_MS` and the
cameras' keyframe intervals are the knobs.

Not done: iOS presumably has the same behaviour (its WebRTC is libwebrtc too); not measured.

### 2. Tearing Home down waits on the GPU, three times (smaller now, not fixed)

Opening Weather on a fresh install once took a **1,043 ms** frame. `trace_report.py drill` on it:
`Compose:applyChanges` 1,017 ms on the main thread, asleep for 992 ms of it. Removing Home's
cards ran each player's teardown, which reads back its last frame with `TextureView.getBitmap`
(`keepFrame`, for the picture shown on return), releases the libwebrtc `EglRenderer` and waits on a
latch for its EGL surface (`onSurfaceTextureDestroyed`). Each of those waits for GL work on another
thread, and the RenderThread was compiling the weather sky's shaders at that moment (`shader_compile`,
641 ms in that frame), so they queued behind it.

The app's own sections (`live.keepFrame`, `live.renderer.releaseEglSurface`, `live.renderer.release`,
`live.unbindRenderer`) now put names on those waits. With the decoder off the GPU (finding 1) the
worst frame opening any drawer app on a fresh install was 153 ms; a warm teardown's sections total
about 50 ms. What would remove the rest: take `keepFrame`'s picture without a synchronous read-back
(libwebrtc's `EglRenderer.addFrameListener` hands over a scaled bitmap on its own thread).

### 3. Shader compiles on first use (mostly the emulator)

About 150 GPU programs are compiled per process over the tour: the app's AGSL shaders (sky,
aurora, forge, fibres, glow…) and Skia's own. On this emulator that is 1.3–4 s of RenderThread
time, and the cause of nearly every frame over 150 ms, because the emulator has no program binary
cache (`code_cache` stays empty) and every process compiles them all again. A phone keeps them
after the first time, so there it costs the first session after an install or update. The weather
app already warms its shaders up after opening (`ShaderWarmUp.kt`); the others don't.

### 4. Nothing is ever idle (partly fixed, partly by design)

`scripts/perf/idle.py` leaves each screen alone for six seconds. Every one kept drawing: 30 frames
a second on most, 60 on some. The shader clocks behind them are throttled to 30 a second on
purpose ("a drift looks the same at thirty as at a hundred and twenty"), so the 60s needed
explaining. Counting each clock's ticks with `traceInstant` (`trace_report.py inproc`) did:

- **Clocks out of step.** Each clock counted its own time since it started and moved on when that
  passed 1/30 s, so two 30-a-second clocks on one screen landed on alternate frames and the screen
  drew at 60. On a 120 Hz phone four such clocks can ask for every frame. Fixed with one shared grid:
  `frameGridSlot` (`ui/components/FrameGrid.kt`) moves every clock on in the same frames. Fitness
  Today: 60 → 30 frames a second at rest, RenderThread 1,035 → 388 ms per 6 s.
- **Fibres at 60 with no finger on them.** Fitness's fibre cards step at 60 because a finger drags
  them; they now do so only while `FiberTouches.stirring`, and at the slow step otherwise.
- **The radar card, below the fold.** Weather's Today page keeps its radar card composed off screen
  (the lazy list's cache window), and the map's clock moved every frame, so the whole window drew
  at 60 with none of it in view. It now ticks at 30 like the atlas's identical map light, and only
  while `onVisibilityChanged` says the map is on screen.
- **Deliberate, and left:** Finance Markets' ticker tape (a `basicMarquee`, continuous motion),
  Fitness's movement figures (a cross-fade between rendered frames, held at each end of a rep),
  and Home's live video.

What remains at rest is about 0.1–0.2 of a core per screen for the ambient backgrounds. Stopping
them a few seconds after the last touch would remove most of it; that changes how the app looks,
so it is a design decision, not made here.

### 5. Not the problem

- **Dependency injection.** Metro's spans: making the graph takes 1.5 ms; every binding resolved
  on the main thread at startup (`startAppServices`, the drawer's view models) about 30 ms, the
  largest `DetectionAlertService` at 8–18 ms.
- **Composition.** `Compose:recompose` is a few percent of main-thread time in every step; the
  main thread is mostly waiting for the RenderThread (`postAndWait`).
- **Home's frames on the emulator** are display-bound: 12 of their 15 ms is `dequeueBuffer`,
  waiting for SurfaceFlinger to hand a buffer back; the drawing itself is about 3 ms.
- Home's first frame after sign-in is 175 ms (the lazy grid composing its cards inside measure,
  and thread creation for each camera's renderer). Once per sign-in; left for another time.

## The toolchain

### Metro runtime tracing

Metro (1.5.0+) can wrap every binding the graph creates in a trace section and mark every accessor
call, written through AndroidX Tracing 2.0. Each span carries `metro.graph`, `metro.type`,
`metro.binding_kind` and more as arguments. It is how "is DI slow?" gets a number instead of an
opinion.

Setup here (`app/build.gradle.kts`, `di/AndroidAppGraph.kt`, `HomeSafeApplication.kt`):

```kotlin
// build.gradle.kts of the module that declares the root graph
metro {
    @OptIn(dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)
    enableRuntimeTracing.set(providers.gradleProperty("homesafe.tracing").map(String::toBoolean).orElse(false))
}
```

```kotlin
@DependencyGraph(AppScope::class)
interface AndroidAppGraph : AppGraph {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext, @Provides tracer: Tracer): AndroidAppGraph
    }
}
```

Things that cost time to find out:

- **The tracer has to be a factory input**, typed `androidx.tracing.Tracer` exactly. A `@Provides`
  function returning one is not enough: a FIR checker requires the factory parameter
  (`METRO_TRACE_ERROR`).
- **In Kotlin Multiplatform the root graph can't live in `commonMain`**. The checker looks at the
  parameter's type in the session where the graph is declared, and AndroidX Tracing has no iOS (or
  other native) target, so `commonMain` can't name `Tracer`, and an `expect` class that is
  `actual typealias`ed to it doesn't pass either. Here `AppGraph` became a plain interface in
  common code (all the bindings and accessors stay there) with a `@DependencyGraph` per platform:
  `AndroidAppGraph` takes the tracer, `IosAppGraph` doesn't, and `createAppGraph` is
  `expect`/`actual`. Turning tracing on fails an iOS compile, so it is an Android-build flag.
- **The tracer is read once, when the graph is made.** Set the global tracer before making any
  graph (`Application.onCreate`), or the graph keeps the library's no-op stub.
- With tracing off the factory's tracer is unused by generated code; an accessor (`val tracer`)
  keeps Metro's unused-input warning away and gives code a way to add its own sections.

### AndroidX Tracing 2.0, in process

`androidx.tracing:tracing` is the API, `tracing-wire` the driver and the Perfetto-format file sink.
Sections are written by the app itself to a file (`noBackupFilesDir/perfetto_traces/`), not to
the system trace, which is what makes them cheap and allows coroutine-aware sections and metadata.

- **Driver**: `HomeSafeApplication` implements `AbstractTraceDriver.Factory` (how the library's
  profiler receiver finds it) and sets `Tracer.setGlobalTracer(driver.tracer)` in `onCreate` when
  `BuildConfig.IN_PROCESS_TRACING`. The manifest removes the library's
  `ConnectedProfilerTracingInitializer`, as Metro's sample does, so nothing sets a global tracer first.
  The `TraceDriver` constructor that takes an enable flag is internal, and the stub driver is
  private: off means "don't make one".
- **The sink writes whole 16 KB chunks.** Everything after the last full chunk is lost when the
  process dies (a `force-stop`, the next journey's cold start). The profiler receiver's flush
  broadcast did not write it here. `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` calls `driver.flush()`,
  and `record.sh` presses Home at the end of every journey.
- **Reading the file** from a profileable but not debuggable build: `run-as` refuses, so the
  journeys use `adb root` (any `google_apis` emulator image).
- **Helpers** (`com.meticulouscreations.homesafe.tracing`, multiplatform, no-ops on iOS):
  `traceSection(name, detail) { … }` is `inline` (`Tracer.trace` isn't, and `inline` can't call it
  with a non-local-returning block), built on `beginSection`/`close` with
  `tokenFromThreadContext()` (a `@DelicateTracingApi`) so sections nest; it checks
  `isCategoryEnabled` first, so a disabled build pays one call. `traceSuspending` wraps
  `traceCoroutine`. `traceInstant(name)` marks a moment; counted per second it says which
  animation clock keeps a screen drawing.
- **Combining with the system trace.** Both use the same clock, so in ui.perfetto.dev use "Open
  multiple trace files". Don't `cat` the files together: trace_processor drops the second file's
  events as out of order (`sorter_push_event_out_of_order` in `stats`). The scripts open each file
  on its own and join by timestamp.

### The system trace

`scripts/perf/journey.pftxt`: scheduling, CPU frequency, the framework's atrace categories (`gfx`,
`view`, `wm`, `am`, `input`, `dalvik`, `binder_driver`), the app's own atrace (Compose's and hwui's
sections), SurfaceFlinger's frame timeline, and logcat lines tagged `HomeSafeJourney` (the
journey's step marks). It streams to a file (`write_into_file`) so a several-minute journey fits;
expect 2–4 MB a second on the emulator. A cold-started process is named in the trace only if
`task/task_rename` is recorded; without it the app shows up as `zygote64`.

### Journeys

`scripts/perf/tour.py` drives a cold start, sign-in, Home, and each drawer app's tabs with scrolls,
logging `<step>:start` and `<step>:end`. `scripts/perf/idle.py` opens each screen and leaves it alone.

- **Never look anything up inside a measured step.** `uiautomator dump` waits for the UI to go
  idle and walks the whole accessibility tree, and the Android CLI's `android layout` does the same
  (22 s on Home with live video, against 1 s for a dump): a screen with video or an animated shader
  is never idle, so the lookup is what gets measured, and it loads the app while it runs. The first
  run finds each target and records its centre; `--replay` taps the recorded points with fixed waits.
- **Install like Play**: `android install --apks=…` (a delta install, a few seconds), launch once,
  `am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE`, then
  `cmd package compile -f -m speed-profile <package>`.
- Each step's numbers come from the trace, not from the script's clock.

### Reading the numbers

`scripts/perf/trace_report.py`, on the `perfetto` Python package's trace_processor:

| Command | Answers | How |
| --- | --- | --- |
| `steps <prefix> [--top N]` | frame time P50/P90/P99/max and main/RenderThread CPU per step; the costliest main-thread sections | `actual_frame_timeline_slice` within each step's marks; `sched` clipped to the window |
| `cpu <prefix> [--apps]` | core-seconds by thread group (video, main, RenderThread, rest, device) | `sched` joined to `thread`, by name |
| `drill <prefix> <step> <slice> [thread]` | why one frame or section was long | `descendant_slice` of the longest match; `thread_state` for running / runnable / asleep |
| `shaders <prefix>` | GPU program compiles per step | `shader_compile` slices on the RenderThread |
| `inproc <prefix>` | the app's sections and Metro's spans by total time; instants per second per step | the in-process file, joined to the system trace's step marks by timestamp |

A pattern worth keeping from the `drill` output: a long main-thread section whose thread was
mostly `S` (asleep) is a wait, not work. Find what it waited for (here `TextureView#getBitmap` and a
latch, behind a RenderThread busy compiling shaders) before optimising any code inside it.

### The emulator: what transfers to a phone and what doesn't

- **Use `-gpu host`.** With `swiftshader_indirect` (software GL) the app's RenderThread was
  40–60% of a core, P50 frames 100–250 ms everywhere, and video's GL copies looked twice as
  expensive. None of that is the app.
- **Shader compiles** cost far more than on a phone and happen in every process (no persistent
  cache). Count them; don't read their milliseconds as a phone's.
- **Video decoding** is software there (in the media HAL's `sh.h264.decoder` threads) and a
  hardware block on a phone, so its CPU share is larger than a phone's; the frame copies, the
  GPU contention and the battery cost of decoding for nobody are real on both.
- **What transfers**: what runs on which thread and when, counts (frames drawn at rest, compiles,
  clock ticks), main-thread waits and what they wait for, and before/after ratios of the same work.

## Re-running

```bash
ANDROID_HOME=/Users/aglose/Library/Android/sdk ./gradlew :androidApp:assembleBenchmarkRelease -PbenchmarkAppIdSuffix=.benchmark -Phomesafe.tracing=true
```

```bash
android install --apks=androidApp/build/outputs/apk/benchmarkRelease/androidApp-benchmarkRelease.apk
```

```bash
python3 scripts/perf/tour.py com.meticulouscreations.homesafe.benchmark
```

```bash
scripts/perf/record.sh build/perf/tour --replay
```

```bash
python3 scripts/perf/trace_report.py steps build/perf/tour --top 10
```

The first `tour.py` run records where everything is; `record.sh … --replay` is the measured run.
For the at-rest walk: `tour.py <package> --replay --steps none`, then
`JOURNEY=scripts/perf/idle.py scripts/perf/record.sh build/perf/idle`; for the return to Home,
`JOURNEY=scripts/perf/back_to_home.py` and `trace_report.py back`. Compare like with like: a
first run after an install (no shader cache) against another, later runs against later runs.

## Carrying this to another project

1. `benchmarkRelease` (or any profileable, minified build) with a `-P` flag that turns tracing on.
2. Metro: `enableRuntimeTracing`, a `@Provides tracer: Tracer` on the root factory, the global
   tracer set in `Application.onCreate` before the graph. In KMP, root graphs per platform.
3. Tracing 2.0: a driver owned by the `Application` (`AbstractTraceDriver.Factory`), the initializer
   removed, a flush when the UI is hidden, an inline `traceSection` and a `traceInstant`.
4. Sections around anything that blocks or tears down (surfaces, codecs, EGL, file and database
   opens), and instants on every animation clock.
5. A journey with log marks, recorded once and replayed blind; a Perfetto config with
   `task_rename`, the frame timeline and the log marks.
6. Per-step SQL: frames, CPU by thread group, and `drill` for the worst frame. Look at the whole
   device's threads before the app's: here the biggest cost was in threads the app's code never
   names.
