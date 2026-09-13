This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop (JVM).

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Local network vs Tailscale

The server's private LAN address is hardcoded as `LOCAL_SERVER_URL` in
[`LocalNetworkConfig.kt`](shared/src/commonMain/kotlin/com/meticulouscreations/homesafe/domain/model/LocalNetworkConfig.kt)
— there's only ever one household server, so it isn't a connect-screen field. When the device is
on the server's Wi-Fi the app uses that address for everything — API, snapshots, and live/recorded
video — with no VPN hop; otherwise it uses the Tailscale URL entered on the connect screen. The
choice is made by probing rather than by reading the SSID, so a router that splits one LAN into
several SSIDs needs no special handling, and the app never needs location permission. The active
route shows as a badge in the top bar and under Settings → Server Information.

**The LAN address has to prove it is the server before it is trusted with anything.** The
Tailscale address is the server's identity — reaching it means WireGuard to a node the tailnet
vouches for — so that is the only address the password is ever sent to. The LAN address is just
an IP on whatever Wi-Fi the phone happens to be on; a coffee shop on the same `192.168.68.0/22`
would answer at it too. So on sign-in (`ConnectionRepositoryImpl`):

1. The login goes to the Tailscale URL, and the LAN address is probed at the same time (a short
   `/api/version` with a 1.5 s cap), so being away from home costs nothing extra.
2. If the LAN answered, the session token the Tailscale login produced is filed under the LAN
   host in the cookie jar and the LAN host is asked whose session it is (`/api/profile`).
   Frigate's token is a JWT signed with the server's own secret and bound to no host, so only
   the household server can accept it. If it does, the LAN route is used; if it doesn't — a
   different server, or something merely answering on that port — the LAN host gets no
   password and no traffic, and the Tailscale route is used.
3. Only when the Tailscale login fails at the transport level (Tailscale switched off at home)
   and the LAN answers does the password go over the LAN, as the last resort.

Route flips work the same way: whenever the OS reports a network change, the LAN address is
re-probed and the session is *moved* — copied to the other address and confirmed there — not
re-established. The password is sent once per sign-in, ever; if the server has forgotten the
session (a restart, or a night's expiry), it is renewed on the Tailscale address and carried over.
Coming back to the foreground after more than 15 s in the background runs the same check, so a
phone that left the house while the app was suspended (iOS delivers no path events to a
suspended app) is on the right route, with a working session, before the first card asks for a
picture.

Sign-in itself is short on the critical path: with the server's cameras already cached from a
previous session the app is connected the moment the session is confirmed, and `/api/config` is
refreshed behind the grid (in place, so the list never blinks empty). A first sign-in with no cache
still waits for the list.

For the LAN route to work the server has to accept Frigate's ports (8971 and 1984) from the
LAN, not only from its Tailscale interface, and `LOCAL_SERVER_URL` has to match its actual LAN
address (a DHCP reservation on the router keeps that from drifting). What the LAN route does *not*
protect is go2rtc's own port (1984), which has no auth of its own: anyone on the household Wi-Fi
who can reach it can ask it for a stream. Scope the firewall rule to the LAN subnet, and to the
devices that need it if the router allows.

### Live video pipeline

The home grid is built to put a picture on screen the instant a card appears and real video as
soon as the network allows, never an old photo in between:

- **One player per camera, shared and long-lived.** `CameraStreamPlayer` takes a `playerKey`;
  the grid card and the camera detail screen both pass the camera's name, so tapping a card hands
  the already-decoding player to the detail screen (live video is up before the shared-element
  transition ends) and coming back hands it back. Players live in a per-platform `LivePlayerPool`
  and pause the moment nothing is watching them (a card scrolled out, a tab switch, the app going
  to the background) and resume at the live edge on return. How long an unwatched player keeps its
  connection depends on why nobody is watching (`LivePlaybackPolicy.awaitIdleWindow`): 90 s while
  the app is on screen — the user is on another tab, or inside one camera while the rest of the
  grid waits — and 30 s once the app itself has gone to the background, counted from that moment.
  Anything inside the window is a warm resume; the picture moves again on the next frame.
- **Two live sources per camera, both kept.** A viewer steps between the grid stream and the
  detail screen's full-quality stream every time they open a card and come back. Rather than
  join afresh each way (a signaling round trip, then a wait for the next keyframe, with the
  picture frozen meanwhile) the holder keeps the WebRTC peer it stopped showing on *standby* —
  connected and decoding, drawn nowhere, silent — and promotes it on the way back
  (`LivePlaybackPolicy.canServe` / `standbyTtlMs`). The cheap grid peer is kept for as long as
  the holder lives; the full-quality one, which costs real bandwidth, for 20 s. For a camera with
  a single stream the peer the detail screen joined with sound also plays the silent grid card,
  and nothing rejoins at all.
- **Pause keeps the picture.** libwebrtc keeps decoding a remote track whether or not it is
  "enabled", and a disabled remote track hands its renderers black frames; pausing therefore
  detaches the renderers instead, so the last frame stays up and the very next decoded frame is
  what shows on resume.
- **Start-up costs paid behind the sign-in screen.** libwebrtc's native initialisation and the
  shared EGL context are built on a background thread at launch (`warmUpLivePlayback`), not
  inside the first camera's join.
- **A poster that is never stale.** While a surface has no frame of its own — first open, a
  reconnect, a return from a long background — `LivePosterLayer` shows the last snapshot this
  device saw (from disk, same frame the card appears), replaces it with a freshly fetched
  `latest.jpg` within about 100 ms on the LAN, and keeps refreshing it once a second until video
  renders. Coil 3 ignores Frigate's `no-store` header by default, which is why the old grid could
  show the same picture for days; the fresh fetch bypasses cache reads and overwrites the cached
  copy instead.
- **Recordings and clips get the same treatment.** Any `VideoSource` can carry a poster. History
  playback on the camera detail screen and event clips on Moments use Frigate's recording snapshot
  for the moment about to play (`/api/<camera>/recordings/<time>/snapshot.jpg`, ~150 ms server-side),
  so a real frame is on screen before the playlist even resolves. Dragging the timeline shows the
  frame under the finger, YouTube-style, and that same frame stays up after release until playback
  reports it has arrived. Clip players are keyed by event, so reopening a card resumes where it was.
- **Fast recovery.** go2rtc's HLS sessions expire while a player is paused; the first retry now
  fires after 250 ms rather than a second, and retries never give up while someone is watching (one
  request every 30 s at most), so video comes back on its own after a server restart.
- **WebRTC first, HLS as the fallback (Android and iOS).** A live source carries go2rtc's WebRTC
  signaling URL alongside its HLS URL. The player joins over a peer connection — one
  signaling round trip, then frames as the camera sends them, a fraction of a second behind live
  — and starts HLS for the same source if the join doesn't connect within 3 s or show a frame
  within 5 s, without disturbing the poster. A stream that fails twice stays on HLS for ten
  minutes. See [docs/webrtc-live.md](docs/webrtc-live.md) for the flow and the server setup.

The grid plays each camera's *grid* stream and the detail screen its *live* stream; today both
resolve to the same 4K main stream because no `live.streams` sub stream is configured in Frigate.
Adding one (see `FrigateApiClient.toFrigateCamera`) is the single biggest remaining win for
connect time and battery: the grid would decode a 720p stream per camera instead of 4K.

Android plays HLS with ExoPlayer and iOS with AVFoundation; both play live over WebRTC with libwebrtc. Desktop has neither, so it decodes
the same HLS itself, with FFmpeg through JavaCV (`FfmpegPlaybackSession.jvm.kt`): a decode thread
per open stream, frames converted straight to BGRA and handed to Skia, and a presentation loop
that plays them out on the stream's own timestamps rather than on the rate they were decoded at.
The differences worth knowing about:

- **Where Android and iOS pause a live player, desktop closes it and reopens on the way back.**
  FFmpeg has no pause for a network stream, and go2rtc expires an HLS session behind a paused
  player anyway. It is still a warm return — the last frame stays up and no poster reappears.
- **Seeking a recording is verified, and reopens the source when it fails.** FFmpeg's plain seek
  leaves an HLS playlist at end-of-stream about as often as it moves it; the frame-checked seek is
  tried first and, if what comes back is not within two seconds of the moment asked for, the
  source is reopened positioned there — which always lands, at the cost of a reconnect.
- **Frames are decoded no wider than 1920.** A 4K BGRA frame is 33 MB, and nothing on screen shows
  a camera at that size; FFmpeg scales during the colour conversion it has to do regardless.
- **Audio plays through `javax.sound.sampled`,** and both codecs go2rtc will publish (AAC and
  Opus) decode, unlike iOS. Muting writes silence rather than closing the line, because the line's
  drain rate is part of what paces a stream that has audio.

FFmpeg's native libraries are ~20 MB per platform and come from JavaCPP's presets, which unpack
them into `~/.javacpp/cache` the first time anything touches them — several seconds on a cold
machine, which `warmUpVideoDecoder()` in `main.kt` starts at launch so it happens behind the
connect screen instead of in front of the first camera. A normal build and `:shared:jvmTest` take
only the host's natives; a distributable meant for other machines has to ask for theirs:

```
./gradlew :desktopApp:packageDistributionForCurrentOS \
    -PjavacppPlatforms=macosx-arm64,macosx-x86_64,windows-x86_64,linux-x86_64
```

The web target still shows the "Live view not yet available on this platform" placeholder.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- Web tests:
  - Wasm target: `./gradlew :shared:wasmJsTest`
  - JS target: `./gradlew :shared:jsTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

### Releasing

Every push to `main` with a green CI publishes a signed bundle to Play internal testing. The
one-time Play Console and secret setup that makes that work is in
[docs/release-to-play.md](docs/release-to-play.md).

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).