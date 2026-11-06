# Testing

HomeSafe's tests come in layers. Each layer runs where it's cheapest and is only as broad as it needs to be. They follow
[Test your Compose layout](https://developer.android.com/develop/ui/compose/testing): semantics finders, assertions and
actions, with `testTag` only where text or content descriptions can't pick a node out.

| Layer | Where | What it drives | Runs on |
|---|---|---|---|
| Unit | each module's `src/commonTest` (`shared`, `features/*`) | View models, repositories, parsers, pure UI logic | Android host, iOS simulator, emulator, JVM (desktop) |
| Screen UI | `shared/src/uiTest`; `app/src/uiTest` for the drawer's apps | One stateless composable from fixtures (`HomeFeed`, `MomentsFeed`, `AlertsSection`, …) | iOS simulator, emulator; `app/src/uiTest` on the JVM too |
| Integration journeys | `app/src/integrationTest` | The whole app — `App()` on a real `AppGraph` — signed in over HTTP to a fake Frigate | Emulator, JVM (desktop) |
| End to end | `androidApp/src/androidTest` | The installed app's real `MainActivity`: intents, system back, relaunch | Emulator |
| Android CLI journeys | `androidApp/src/journeysTest` | Natural-language journeys an agent runs on a device through the Android CLI | apksim (opt-in), emulator nightly |

On the JVM (`./gradlew desktopTest`, CI's **Desktop app** workflow) the journeys walk a phone's layout
(`JourneyWindow.PHONE`) unless they ask for a laptop's (`JourneyWindow.DESKTOP`), and leave a picture of every step
under `app/build/journeys/`; see [desktop.md](desktop.md).

Alongside these, previews and screenshots show what a change looks like rather than asserting on it:
the Layoutlib screenshot tests in `:androidApp` and the before/after gallery in each pull request. See [ui-previews.md](ui-previews.md).

## The fake Frigate (`:fake-frigate`)

The integration journeys, the end-to-end tests and the Android CLI journeys all sign in to
`FakeFrigateServer`. It's a Ktor server that implements the part of Frigate's API the app uses:

- Session cookies, config and `config/set`. Writes go back into its state, so a toggle survives a reload.
- Events, recordings, stats and profile.
- The face library and the custom classifiers.

It deliberately has no live video (go2rtc) and no push relay, so the app runs its "unavailable" paths for those.
Every snapshot and thumbnail it serves is the same 1×1 image.

Each test sets up its scene through `FakeFrigateState`:

- `FakeFrigateState.household()` is three cameras and a few hours of activity. `quiet()` has cameras but no history.
- Mutate the state to change what the server holds, e.g. `state.edit { failures["/api/stats"] = 500 }` or add
  events while the app runs.
- `server.requests` and `server.awaitRequest { … }` assert on what the app sent.
- `server.unhandled` lists anything the app asked for that the fake doesn't model.

Accounts are `admin` / `correct-horse` and the read-only `viewer` / `just-looking`.

Run it on its own, e.g. to try the app by hand from an emulator:

    ./gradlew :fake-frigate:run --args="--port 8971 --host 0.0.0.0 --advertise 10.0.2.2"

Then sign in to `http://10.0.2.2:8971`. Add `--quiet` for the empty scenario.

## Writing a journey

```kotlin
@Test
fun turningDetectionOffIsSavedOnTheServer() = runAppJourney {
    signIn.signInAs()
    shell.openTab(TopLevelRoute.Settings)
    tap(hasText("Back Yard") and hasClickAction())
    server.awaitRequest { it.method == "PUT" && it.path == "/api/config/set" }
}
```

- The Compose clock is frozen for the whole journey (`autoAdvance = false`). The app has frame loops that never go
  idle, such as loading shimmers and pulsing dots, so an auto-advancing clock would hang every idle wait.
- Wait for anything that follows an action with `awaitText`, `awaitNode`, `awaitGone` or `awaitUntil`, and tap with
  `tap(...)`. These helpers advance the clock a frame at a time while real network responses land. Don't assert
  straight after an action.
- Bring an off-screen node into view with `scrollIntoView(...)`, or `revealInList(...)` for an item of a lazy list.
  Don't use `performScrollTo()` or `performScrollToNode()`. Semantic scrolls are animated, a frozen clock never
  plays the animation, and `performScrollTo()` keeps trying until the node is in view, so it never returns.
- A wait that times out fails with the semantics tree and the fake server's request log. On CI that failure message
  is usually all you need.
- `runAppJourney` takes the app off screen and plays out a few frames before it returns. On Android the test
  environment runs any frame still pending at teardown on the test thread, where its layout pass races the main
  thread's drawing and crashes the process.
- Per-area steps live in robots (`SignInRobot`, `ShellRobot`, and the Home, Moments and Settings ones).
- `turnPhone(onItsSide = true)` lays the running app out for a phone on its side (the nav down the side, a
  camera full screen) and `turnPhone(onItsSide = false)` upright again, without changing the test window's size:
  it provides `LocalCompactLandscape`, which the layouts read in place of the window's own shape. A screen-level
  test does the same with `CompositionLocalProvider(LocalCompactLandscape provides true)`. A real device rotating
  under the real Activity — which must not recreate it — is `AppE2eTest`'s.

Fakes that the tests of more than one module use (a fake web, a fake server connection, a clock moved by
hand) live in `:testing`, which only `commonTest` source sets depend on.

## Running the suites

| Suite | Command |
|---|---|
| Fake Frigate's own tests | `./gradlew :fake-frigate:test` |
| Unit tests, every module, on the Android host | `./gradlew testAndroidHostTest` |
| Every module on a device (unit, screen UI, integration) | `./gradlew connectedAndroidDeviceTest` |
| Only the integration journeys, on a device | `./gradlew :app:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.package=com.meticulouscreations.homesafe.integration` |
| End-to-end on the real `MainActivity` | `./gradlew :androidApp:connectedDebugAndroidTest` |
| iOS simulator (unit and screen UI), every module | `./gradlew iosSimulatorArm64Test` |
| Android CLI journeys | `scripts/run-android-cli-journeys.sh` (see below) |

The end-to-end tests use the AndroidX Test Orchestrator with `clearPackageData`. Each test gets its own
instrumentation and a wiped app, because sign-in history lives in Room and there's one app graph per process.

The two device suites can't share an emulator at the same time, because each covers the other's Activities. The build
orders the end-to-end suite after the shared module's when you run both.

On pull requests and main, CI runs them on [apksim](https://github.com/aglose/apksim) rather than an emulator
([`.github/workflows/apksim.yml`](../.github/workflows/apksim.yml), CI's `android-ui` job): Android's framework on the
JVM, API 36, in apksim's published Linux image (`ghcr.io/aglose/apksim`, pinned to a release in the workflow). The same APKs, built with `assembleAndroidDeviceTest :androidApp:assembleDebug
:androidApp:assembleDebugAndroidTest`, go to [`scripts/ci/apksim-device-tests.py`](../scripts/ci/apksim-device-tests.py),
which runs each module's suite, the journeys package split over two processes (`--shards 2`), the rest of `:app`
(`notPackage`), and the end-to-end tests in one process, three suites at a time, longest first (a container each; a
4-CPU runner is full with three). A test that fails is run once more on its own; if it passes then, the job passes and
names it as flaky in a warning and the job summary. A new test needs listing nowhere. On a push to main whose pull request
already passed them on the same tree, CI skips the tests but still runs the build (`device-tests-cache`, apksim.yml's
`build-only`). setup-gradle keys the Gradle cache by job and workflow, so a pull request's device tests restore only what
this job saved on main under CI; with nothing saved there, they restored another job's entry and rebuilt most of the app.

Every night the emulators run them too
([`.github/workflows/device-tests-nightly.yml`](../.github/workflows/device-tests-nightly.yml)), on two API 34
emulators at once: the integration journeys on one
(`-Pandroid.testInstrumentationRunnerArguments.package=com.meticulouscreations.homesafe.integration`), and everything
else plus the end-to-end suite on the other (`notPackage=` the same). apksim runs the same commit beside them, and
[`scripts/ci/compare-device-tests.py`](../scripts/ci/compare-device-tests.py) lists every test the two disagree on, or
that was flaky on apksim, or that ran on one side only. Anything listed turns the run red and goes in an issue titled
"Nightly device tests: the emulators and apksim disagree" (a comment while it stays open), to be fixed in the app or
in apksim. Run it by hand from the Actions tab, or run the emulators alone with "Device tests on emulators".

On the emulators, `scripts/run-instrumented-tests.sh` runs them with a time limit. If they're still running when it expires, the
script dumps every thread of the app and test processes and the journey watchdog's output from logcat. Either way it
prints the last tests the runner started, so a hang shows up in the job log.

The same script looks after the emulator, which says it has booted long before it is quiet. Before Gradle starts it
waits, up to two minutes (about 25 seconds in practice), for the broadcast queue to drain and the launcher to be in
front and readable. While the tests run it checks every few seconds for a system "isn't responding" or "keeps stopping"
dialog and closes it, since one of those keeps window focus and fails every end-to-end test after it. A dialog about the
app itself is left alone: that one should fail the tests. Both leave a line in the job log when they had something to do.

The Activity `runComposeUiTest` hosts every UI test and journey in is declared in
`shared/src/androidDeviceTest/AndroidManifest.xml` with the same `configChanges` as `MainActivity`, so a change to the
window or the keyboard lays the content out again rather than recreating the Activity. A recreation throws the test's content away ("No compose hierarchies found"), and the
emulator makes one happen by itself: a second or two after the test APK is installed the system reloads its keyboard
layouts, and the keyboard configuration changes under whichever test is running, which is the first of the run.

## When CI goes red on main

A commit on `main` passed `ci-green` on its pull request, so a red run there is usually the runner and not the code, and
it means nothing was published. [`.github/workflows/ci-retry.yml`](../.github/workflows/ci-retry.yml) reruns the failed
jobs of such a run once, if its commit is still the newest on `main`. A second failure stays red. Pull request runs are
never rerun for you. The first attempt's logs stay on the run (the attempt menu at the top of its page), so a flake that
was rerun can still be read and fixed.

The iOS job runs on the Mac, and its disk is shared with everything else done there. The job checks the free space
first: under 15 GB it clears the runner's Xcode derived data and simulator caches, and under 8 GB it stops with an error
that says the disk is full, which is what `ld: write() failed, errno=28` from a link step meant.

When the Mac is asleep or its runner has stopped, the iOS job goes to a GitHub-hosted macOS runner instead of waiting
there for good (the `ios-runner` job in `ci.yml` asks first). That check reads the repository's runner list, which takes
a repository secret `RUNNER_STATUS_TOKEN`: a fine-grained token for this repository with **Administration: Read-only**
and nothing else. Without it the job goes to the Mac as before, with a warning on the run. The repository variable
`IOS_RUNNER` (`hosted` or `self-hosted`) overrides the check either way.

A Mac that is online can still fail to finish: asleep before it gets to the job, or halfway through it (the job then
ends with a step still "in progress" and no log). `ios-watch` follows the job from a hosted runner
([`scripts/ci/watch-ios-job.sh`](../scripts/ci/watch-ios-job.sh)): when it hasn't started within 15 minutes and the Mac
is offline (or `RUNNER_STATUS_TOKEN` isn't set), when it hasn't started within four hours even with the Mac online, or
when its runner went away, `ios-hosted` runs the same steps (the `ios-tests-and-build` action in `.github/actions/`) on a
GitHub-hosted Mac, and ci-green takes that run's result. A job that fails by itself (a test, the build) fails ci-green
as before, and "Re-run failed jobs" runs it and its watcher again. A job queued on an online Mac is waited for, because
the Mac takes one iOS job at a time and several pull requests pushed together line up behind each other there.

## Android CLI journeys

`androidApp/src/journeysTest/*.journey.xml` are journeys in the format documented for
[Android CLI Journeys](https://developer.android.com/tools/agents/android-cli/journeys):

- A `<journey>` holds a `<description>` and a list of `<action>`s.
- An action that starts with "Verify" is checked against the screen rather than performed.
- The files also open in Android Studio's Journeys (Studio Labs).

The Android CLI has no journey runner of its own. An agent reads each journey and drives the device with
`android layout`, `android screen` and `adb shell input`, as the android-cli skill describes.
`scripts/run-android-cli-journeys.sh` does that with Claude Code in headless mode. Each journey starts from a
freshly installed app, and the script fails unless every action of every journey gets a ✅.

They run on [apksim](https://github.com/aglose/apksim) (see the `apksim` skill), an instance of its own per journey,
started with no app data (`--fresh`, since apksim has no `pm clear`) and a drawn keyboard (`--soft-keyboard`: without
one, the Back that hides the keyboard quits the app). To run them on the Mac:

1. Install the [Android CLI](https://developer.android.com/tools/agents/android-cli). The script installs the skill it needs.
2. Install Claude Code and sign in (`claude`).
3. Build the debug APK (`./gradlew :androidApp:assembleDebug`).
4. Start the fake Frigate on `::1`: `./gradlew :fake-frigate:run --args="--port 8971 --host ::1 --advertise localhost"`.
5. Run `APKSIM=apksim scripts/run-android-cli-journeys.sh` to run them all, or pass journey files to run a subset.

The journeys type the emulator's address for the fake Frigate, `http://10.0.2.2:8971`; the script gives the agent a
copy that types `FRIGATE_URL` instead (`http://localhost:8971` on apksim). Without `APKSIM` the script runs them on the
device adb has, an emulator with the debug APK installed and the fake started with `--advertise 10.0.2.2` as above,
clearing the app before each journey. Results go to `build/journeys/`, with `summary.tsv` (one line per journey: name,
result, actions passed and in all, seconds the agent took).

On CI, the **Android CLI journeys** workflow runs them in apksim's Linux image (the release `apksim.yml` pins), a
container per journey on the runner's network, on demand and on a pull request that changes them or how they run. The
**Nightly Android CLI journeys** workflow runs them on an API 34 emulator and on apksim and
[`scripts/ci/compare-journeys.py`](../scripts/ci/compare-journeys.py) puts the two side by side, journey by journey with
times; a disagreement turns it red and goes in an issue, as the nightly device tests do. Both need the
`CLAUDE_CODE_OAUTH_TOKEN` secret (Claude Code on a Claude subscription, from `claude setup-token`) and neither is part of
`ci-green`: an agent judging screenshots costs tokens and isn't deterministic. The deterministic layers above are what
gate a merge.

The sign-in fields, the Connect button and the bottom nav carry test tags, and `MainActivity` exposes test tags as
resource ids (`testTagsAsResourceId`). So `android layout`, UiAutomator and the Macrobenchmark journeys all see
handles like `sign_in_server_url` and `bottom_nav_moments`.

## Baseline Profile regeneration

The Baseline Profile in `androidApp/src/release/generated/baselineProfiles/` is committed, and the
**Baseline Profile** workflow (`.github/workflows/baseline-profile.yml`) keeps it current. It runs every Monday at
07:00 UTC, and on demand from the Actions tab.

1. **Gate.** Counts the commits on `main` since the last commit that touched the profile directory. With none, the
   run stops there. A manual run with `force` ticked regenerates anyway.
2. **Generate.** On an API 34 `google_apis` x86_64 emulator, which shares its AVD snapshot cache with `ci.yml`:
   - The runner starts the fake Frigate (`--port 8971 --host 0.0.0.0 --advertise 10.0.2.2`) and writes
     `local.credentials.properties` with its `admin` account. The `nonMinifiedRelease` build's
     "Autofill test credentials" button then signs the journey in to `http://10.0.2.2:8971`.
   - Beside it runs a pinned go2rtc with an ffmpeg `testsrc` H.264 stream for each name the app asks the fake's cameras
     for: `front_door`, `driveway`, `driveway_sub` and `back_yard`. Its API is on `:1984` and WebRTC on `:8555`, with
     `10.0.2.2` as the candidate. The fake has no live video of its own, and without go2rtc the profile would only
     record the failed WebRTC join and the HLS fallback, not the path users actually take. If the fake's cameras or
     their `live.streams` change, update `GO2RTC_STREAMS` in the workflow. The step fails when any name is missing.
   - It then runs `./gradlew :androidApp:generateBaselineProfile`, which runs `BaselineProfileGenerator` on the
     emulator.
3. **Pull request.** Any change to the profile is opened as a pull request, or pushed to the existing one, from the
   fixed branch `automation/baseline-profile`. Review it and merge it like any other. The profiles, logcat and the
   fake Frigate and go2rtc logs are also uploaded as the `baseline-profile` artifact.

The pull request is opened with a token of its own rather than `GITHUB_TOKEN`. GitHub doesn't run workflows for a pull
request that `GITHUB_TOKEN` opened, so `ci-green` would never report and the merge would stay blocked. The repository
owner has to set this up once:

1. Create a repository secret `BASELINE_PROFILE_TOKEN` (Settings → Secrets and variables → Actions). It can be a
   fine-grained personal access token or a GitHub App token, scoped to this repository, with **Contents: read and
   write** and **Pull requests: read and write**.
2. In Settings → Actions → General → Workflow permissions, enable **Allow GitHub Actions to create and approve pull
   requests**.

Until the secret exists, the run still generates the profile and uploads the artifact, then fails at the pull request
step with a message saying the secret is missing.

To regenerate by hand instead, run `./gradlew :androidApp:generateBaselineProfile` with a device connected, and with
`local.credentials.properties` pointing at a Frigate server the device can reach, such as the fake one above.

The journey also opens the weather app and scrolls both of its tabs, so the device needs the internet too: a fresh
install has nowhere to forecast for, and the journey searches Open-Meteo for a place and waits for its forecast.

On a phone that has the real app installed, add `-PbenchmarkAppIdSuffix=.benchmark` to this and to the Macrobenchmark
runs (`:baselineprofile:connectedBenchmarkReleaseAndroidTest`). The benchmark builds are signed with the debug key and
can't replace an app from Play; with the suffix they install beside it as `com.meticulouscreations.homesafe.benchmark`.
`WeatherScrollBenchmarks` takes `-Pandroid.testInstrumentationRunnerArguments.weatherPlace=<city>`: what the sky draws
is that place's weather at that minute, and a wet or a night sky costs more than a clear one, so numbers are only
comparable for the same place within the hour. See `docs/perf-audit-2026-10-08-weather.md`.
