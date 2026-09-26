# Testing

HomeSafe's tests come in layers. Each layer runs where it's cheapest and is only as broad as it needs to be. They follow
[Test your Compose layout](https://developer.android.com/develop/ui/compose/testing): semantics finders, assertions and
actions, with `testTag` only where text or content descriptions can't pick a node out.

| Layer | Where | What it drives | Runs on |
|---|---|---|---|
| Unit | `shared/src/commonTest` | View models, repositories, parsers, pure UI logic | JVM, Android host, iOS simulator, emulator |
| Screen UI | `shared/src/uiTest` | One stateless composable from fixtures (`HomeFeed`, `MomentsFeed`, `AlertsSection`, …) | JVM (skiko), iOS simulator, emulator |
| Integration journeys | `shared/src/integrationTest` | The whole app — `App()` on a real `AppGraph` — signed in over HTTP to a fake Frigate | JVM, emulator |
| End to end | `androidApp/src/androidTest` | The installed app's real `MainActivity`: intents, system back, relaunch | Emulator |
| Android CLI journeys | `androidApp/src/journeysTest` | Natural-language journeys an agent runs on a device through the Android CLI | Emulator (opt-in) |

Alongside these, previews and screenshots show what a change looks like rather than asserting on it:
`:shared:renderPreviews`, the Layoutlib screenshot tests in `:androidApp`, journey screenshots
(`-PjourneyScreens`), and the before/after gallery in each pull request. See [ui-previews.md](ui-previews.md).

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

On the JVM, the journeys keep the desktop app's database in `shared/build/tmp/jvmTest/homesafe-data`, not
`~/.homesafe`. The directory is set with the `homesafe.dataDir` system property.

## Running the suites

| Suite | Command |
|---|---|
| Fake Frigate's own tests | `./gradlew :fake-frigate:test` |
| Unit, screen UI and integration journeys on the JVM | `./gradlew :shared:jvmTest` |
| Only the integration journeys | `./gradlew :shared:jvmTest --tests 'com.meticulouscreations.homesafe.integration.*'` |
| Android host unit tests | `./gradlew :shared:testAndroidHostTest` |
| Everything in `shared` on a device (unit, screen UI, integration) | `./gradlew :shared:connectedAndroidDeviceTest` |
| End-to-end on the real `MainActivity` | `./gradlew :androidApp:connectedDebugAndroidTest` |
| iOS simulator (unit and screen UI) | `./gradlew :shared:iosSimulatorArm64Test` |
| Android CLI journeys | `scripts/run-android-cli-journeys.sh` (see below) |

The end-to-end tests use the AndroidX Test Orchestrator with `clearPackageData`. Each test gets its own
instrumentation and a wiped app, because sign-in history lives in Room and there's one app graph per process.

The two device suites can't share an emulator at the same time, because each covers the other's Activities. The build
orders the end-to-end suite after the shared module's when you run both.

On CI, `scripts/run-instrumented-tests.sh` runs them with a time limit. If they're still running when it expires, the
script dumps every thread of the app and test processes and the journey watchdog's output from logcat. Either way it
prints the last tests the runner started, so a hang shows up in the job log.

## Android CLI journeys

`androidApp/src/journeysTest/*.journey.xml` are journeys in the format documented for
[Android CLI Journeys](https://developer.android.com/tools/agents/android-cli/journeys):

- A `<journey>` holds a `<description>` and a list of `<action>`s.
- An action that starts with "Verify" is checked against the screen rather than performed.
- The files also open in Android Studio's Journeys (Studio Labs).

The Android CLI has no journey runner of its own. An agent reads each journey and drives the device with
`android layout`, `android screen` and `adb shell input`, as the android-cli skill describes.
`scripts/run-android-cli-journeys.sh` does that with Claude Code in headless mode. Each journey starts from a
cleared app, and the script fails unless every action of every journey gets a ✅.

To run them locally:

1. Install the [Android CLI](https://developer.android.com/tools/agents/android-cli), then run `android init`.
2. Install Claude Code and set `ANTHROPIC_API_KEY`.
3. Start the fake Frigate with `--advertise 10.0.2.2` as above.
4. Install the debug APK on an emulator.
5. Run `scripts/run-android-cli-journeys.sh` to run them all, or pass journey files to run a subset.

Results go to `build/journeys/`.

On CI, the **Android CLI journeys** workflow runs them on demand and weekly. It needs the `ANTHROPIC_API_KEY` secret
and is not part of `ci-green`: an agent judging screenshots costs tokens and isn't deterministic. The deterministic
layers above are what gate a merge.

The sign-in fields, the Connect button and the bottom nav carry test tags, and `MainActivity` exposes test tags as
resource ids (`testTagsAsResourceId`). So `android layout`, UiAutomator and the Macrobenchmark journeys all see
handles like `sign_in_server_url` and `bottom_nav_moments`.
