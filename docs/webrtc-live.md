# Live video over WebRTC

HLS puts a couple of seconds of segments between the camera and the screen, and a fresh join has
to fetch a playlist and a segment before anything moves. WebRTC is a peer connection straight to
go2rtc: after one signaling round trip the server pushes frames as the camera produces them, so a
card shows moving video within a keyframe interval of connecting and stays a fraction of a second
behind live. This is how Android and iOS play every live stream now; HLS remains the fallback there and
the only path on desktop (see "Platforms" below).

## How a join works

The common code (`ui/components/LiveTransport.kt`) knows nothing about libwebrtc; it sequences a
`WebRtcPeer` the platform provides:

1. `VideoSource.Live` carries a `WebRtcEndpoint` — go2rtc's signaling URL for the stream and
   whether to ask for audio. The HLS URL stays the source's identity, so every "same stream?"
   comparison in the app is unchanged.
2. The holder asks `LiveTransportMemory` whether this stream may try WebRTC (below), and if so
   runs `WebRtcConnectFlow.connect`: create a receive-only peer, make an offer, wait for ICE
   gathering to finish, `POST` the offer to `http://<host>:1984/api/webrtc?src=<stream>` as
   `application/sdp` (`WhepSignalingClient`), apply the answer, wait for ICE to connect, wait
   for the first decoded frame.
3. Making the offer has a 3 s budget, the signaling round trip 5 s, answer-to-connected 3 s and
   connected-to-first-frame 5 s (`LivePlaybackPolicy`). Signaling gets its own, longer bound
   because go2rtc answers a stream nobody is watching only once it has dialled the camera
   (1.3–2.4 s on these cameras); under the old single offer-to-connected budget a slow camera's
   every cold join timed out and fell back to HLS. Any failure closes the peer and the holder starts HLS for the same
   source **without a new cold-start generation**: the poster is already up, so the user sees
   poster → HLS video rather than a flash.
4. No STUN or TURN. Clients reach the server over the LAN or Tailscale, both plain routes between
   host addresses, so the offer carries host candidates only and gathers them in milliseconds.

`LiveTransportMemory` is the fallback policy: a stream gets two failed joins, then stays on HLS
for ten minutes from the last failure before WebRTC is tried again; a successful join clears its
record. It is keyed by the HLS URL, host included, so the LAN and Tailscale routes to one camera
are separate entries and moving between networks naturally gets a fresh try.

It also remembers which streams have *proven* WebRTC lately (`recentlyConnected`, a week from the
last successful join), and keeps that record across launches (`LiveTransportMemory.restore`,
SharedPreferences on Android, user defaults on iOS; failures stay per launch). A cold start of an
unproven stream — a route the app has never joined on, or not in a week — starts HLS alongside the WebRTC join rather than sitting on
the poster for the join's full budget when it can't get through — stage by stage up to 3 s for the offer, 5 s for signaling, 3 s for ICE and 5 s for a frame (16 s at worst; a prepared offer takes none of the first, and a dead route usually fails at the connection): the picture is up at
HLS speed, and the peer takes over on its first frame through the same make-before-break as the
warm fast path. A proven stream joins over WebRTC alone, since its join shows a frame within a
keyframe interval and the shadow would only cost the server an HLS session.

Joins are make-before-break. The previous engine — an HLS session or an older peer — keeps
drawing until the new peer has a frame, so a quality upgrade on the detail screen and a
LAN↔Tailscale route flip no longer pass through black. Every binder (grid card, detail screen)
draws through its own renderer attached as a sink on the same video track, so there is no
surface hand-over to bridge either.

**Standby peers.** A camera has two live sources — the grid stream and the detail screen's
full-quality one — and a viewer moves between them every time they open a card and come back.
The holder keeps the peer it stopped showing on standby rather than closing it: still connected
and decoding, drawn nowhere, silent. Stepping back promotes it and the picture moves again on the
next decoded frame, with no signaling round trip and no keyframe wait. `LivePlaybackPolicy.canServe`
says which requests a peer can serve (same signaling URL; a peer with audio also serves a silent
request, never the reverse — WHEP has no renegotiation), and `standbyTtlMs` how long one is kept:
the grid peer for the life of the holder, the full-quality peer (real bandwidth) for 20 s. For a
single-stream camera the detail screen's peer therefore also plays the grid card, muted, and
nothing rejoins.

**Pause.** libwebrtc keeps decoding a remote track whether or not it is enabled, and a *disabled*
remote track hands its sinks black frames. Both engines therefore implement "video disabled" by
detaching the renderers from the track, so a paused surface keeps its last frame and shows the
very next decoded frame on resume.

Audio: only the detail screen's full-quality source asks for it (`audio = true`). The grid streams
are video-only on the server, and the join-then-upgrade plan hands sound over with the upgrade.
libwebrtc decodes Opus natively, which is what the cameras publish, so nothing is transcoded.

## Time to the first live picture

A cold start's first live pixel on Home used to wait on everything in turn: sign-in (the server
checking the password), Home composing, and only then each card's join. What takes that off the
critical path now:

- **Joins start during sign-in — or during the biometric prompt.** go2rtc's port takes no
  credentials, so as soon as the app knows which address it will land on
  (`ConnectionRepository.expectedConnection`), `LiveStreamPrefetcher` starts the grid players for
  the cameras cached for that server, under the same keys and sources the cards will bind. For a
  typed password that is the LAN answering, or Tailscale accepting the password. For a saved
  biometric login it is earlier still: while the prompt is up, the last server's LAN and
  Tailscale addresses are probed (reachability only — no credentials go anywhere) and the one
  that answers is expected. A device with nothing cached — a first-time user — starts nothing.
  Nothing is drawn until a card binds, which only happens once the sign-in has succeeded; a
  dismissed prompt or a failed sign-in lets the players go at once. A prefetched player counts as
  watched for `LivePlaybackPolicy.PREFETCH_HOLD_MS`, then falls back to the usual idle rules.
  Android and iOS both prefetch (`LivePrefetchLeases`); desktop and web don't.
- **Offers are made ahead of time (Android).** At launch, one silent offer per cached camera is
  prepared — peer created, ICE gathered, nothing sent — so a join goes straight to signaling
  (`LivePlayerPrefetch.prepare`, dropped after `PREPARED_OFFER_TTL_MS`).
- **No HLS shadow for a stream that joined fine last time**, thanks to the persisted proven
  record above: three fewer HLS sessions against go2rtc at the busiest moment of a cold start.
- **Sign-in doesn't wait out the LAN probe away from home.** Once Tailscale has accepted the
  password, the LAN probe gets `LAN_GRACE_MS` (250 ms) more; after that the app goes on over
  Tailscale and moves to the LAN behind the sign-in if the probe answers after all. It used to
  wait up to the probe's 1.5 s timeout.
- **A cold join draws its first frame.** The renderers are attached to the peer as soon as it is
  created, not when the join has been adopted, so the first decoded frame is the first frame on
  screen instead of being spent proving the join worked.

Measure it with `scripts/bench-first-live-pixel.py` (Android, a build with the test credentials).
Each run force-stops the app, signs in with the Autofill button and waits for every camera's
first frame. The app logs each stage once per process under `HomeSafeTTFP`, and `--stages` prints
per-stage medians side by side. Pass `--go2rtc http://192.168.68.65:1984`: a force-stopped app's
consumers linger on go2rtc for several seconds and keep the camera's RTSP session up, so without
the wait a run joins warm streams and looks 2–3 s faster than opening the app after a while does.

Results on the CiApi34 emulator on the LAN (2026-09-27), medians of 8–10 cold starts each,
builds interleaved; "cold streams" means go2rtc had no consumers, as when the app is opened after
a while. The emulator inflates sign-in (≈2.4 s after sitting idle; the server itself answers in
tens of milliseconds), so absolute numbers are high — compare within a row.

| Tap Connect (password) → | before (main) | + prefetch, first frame drawn (#84) | + everything above |
|---|---|---|---|
| first live pixel | 5.25 s | 3.79 s | 3.84 s |
| all three cameras live | – | 9.83 s | **5.84 s** |

The last column's gain is the slow camera: hikvision_2's go2rtc answers a cold join in ~2.4 s, so
under the old single connect budget every one of its cold joins timed out and it came up over
HLS ~10 s in; now it joins over WebRTC.

| Fingerprint unlock → | without the prompt-time start | with it |
|---|---|---|
| first live pixel | 3.70 s | **0.91 s** |
| all three cameras live | 5.77 s | **1.71 s** |

(0.8 s between the prompt appearing and the touch; the streams had been joining for ~3.3 s by the
time the prompt was passed.)

Server side — both applied 2026-09-27:

- **Sub-stream keyframe every 1 s** (was 2 s; applied 2026-09-27: Hikvision `GovLength` 50 → 25,
  Amcrest `GOP` 40 → 20; revert notes in `~/surveillance/sub-gop-before.txt` on the box). With
  cold streams it barely matters — a camera opens a new RTSP session on a keyframe anyway (first
  pixel 3.84 → 3.77 s) — but it halves the wait for a viewer joining a stream someone else is
  already watching. `GovLength` counts frames, so a camera that drops its frame rate in low light
  (hikvision_2 runs ~12 fps at dusk) gets proportionally longer intervals.
- **go2rtc keeps a session open on every `_sub` stream.** hikvision_2's and amcrest_1's detect
  inputs now read go2rtc's restream (`rtsp://127.0.0.1:8554/<cam>_sub`, `preset-rtsp-restream`)
  instead of the camera, which costs no extra camera session. hikvision_1's detect reads the
  *main* restream at 1280x720, so its sub stream is held by a `go2rtc-keepalive` container in the
  Frigate compose file: Frigate's own image running `ffmpeg -c copy -f null` against the restream,
  `restart: always`. Measured before applying, with the same setup simulated: go2rtc +1.4 % of
  one core, camera traffic on eno2 +0.5 Mbit/s (12.9 → 13.4). go2rtc now answers a join in
  30–60 ms instead of 1.3–2.4 s. Password sign-in: first live pixel 3.77 → **2.91 s**, all three
  cameras 5.79 → **2.95 s**. Fingerprint unlock: all three cameras 1.71 → **1.12 s**; the first
  picture stays ~1 s, bound by the sign-in the streams were already hidden behind. The catch:
  detection on those two cameras now depends on go2rtc staying up, as hikvision_1's already did.
  Undo: the `config.yml.bak-20260927-214452` / `docker-compose.yml.bak-20260927-214452` backups
  beside the files, `docker restart frigate`, and `docker rm -f go2rtc-keepalive`.

## Cold-launch regression gate (CI)

The `startup-perf` job in ci.yml (part of `ci-green`) builds the base branch's benchmarkRelease
app and this branch's, signed in to the fake Frigate (`http://10.0.2.2:8971`, `admin`) with go2rtc
serving ffmpeg test patterns under the fake's stream names (`scripts/ci/go2rtc-testsrc.sh`), and
times both on one API 34 emulator in ABBA blocks, each block starting with a discarded run and
every run waiting for go2rtc to have no consumers left, so each join is a cold one
(`scripts/ci/startup-perf.sh`). It gates two medians:

- **cold launch (TTID)** — `am start -W` TotalTime: process start to the first frame;
- **sign-in → first live pixel** — the milestones above, sign-in submit to the first video frame
  on Home.

A metric fails when this branch's median is more than **15 %** slower than the base's *and* more
than a floor (40 ms for TTID, 150 ms for the pixel): hosted emulators are noisy enough that a few
percent of a small number means nothing, while a real regression — a blocking call on the main
thread at launch, a join that waits for something new — shows up well past both. A result over
the limit is measured again with twice the samples before the job fails. On a push to main the
base is the commit main was at before. A base from before the milestones existed can only have
its cold launch compared, and the job says so. The medians land in the job summary; the raw runs
are in the `startup-perf` artifact.

A hosted emulator says it has booted before it has stopped starting things, so the script first
waits for the launcher to be in front and readable, and hides the system's "isn't responding"
dialogs about its own processes. A run whose sign-in screen never appears is tried again (twice
at most, `--retries`): nothing was timed yet, so nothing is biased, and a build that really can't
show its sign-in screen still fails. A camera that never draws is never retried. When the job
fails, the error names what was on screen instead, and a screenshot, the view hierarchy and the
logcat are in the artifact as `failure-screen.png`, `failure-screen.xml` and `failure-logcat.log`.

Run the same comparison by hand with `python3 scripts/bench-first-live-pixel.py --gate A B`
against any two labels recorded on one device.

## Platforms

- **Android** — `io.getstream:stream-webrtc-android` (the upstream `org.webrtc` API).
  `WebRtcRuntime` initialises libwebrtc once per process with one shared EGL context;
  `AndroidWebRtcPeer` wraps a `PeerConnection`; `WebRtcTextureRenderer` is a `TextureView` drawn
  by libwebrtc's `EglRenderer`, stacked over the HLS `TextureView` and cleared whenever the
  holder goes back to HLS. Its EGL surface has an alpha channel (`CONFIG_RGBA`) so that the
  clear is transparent: on the default RGB-only config it is opaque black, and HLS video after a
  failed or lost join played underneath a black box. `LivePlayerHolder.transport` says which engine is showing the picture;
  everything else about the holder (binders, idle stop, cold generations, retries) is the same for
  both. Media3 still plays HLS and every recording. The audio module is built with media
  attributes so sound follows the speaker/headphones route, not the earpiece.
- **iOS** — the engine lives on the Swift side: `iosApp/iosApp/WebRtc/WebRtcPeerBridge.swift`
  implements the shared module's `IosWebRtcPeer` contract (`WebRtcPeer.ios.kt`) over the
  `WebRTC` Swift package (stasel/WebRTC, Google's `WebRTC.xcframework`), and `iOSApp.swift`
  registers its factory through `startIosApp(webRtc:)`. The Kotlin framework is a static
  library with no cinterop against libwebrtc, so nothing on the Kotlin side links it; a build
  without the bridge (the simulator unit tests) simply plays HLS. The peer's video is drawn by
  an `RTCMTLVideoView` (`scaleToFill`, like every other live surface) inside a container view
  the Kotlin player composable stacks over its `AVPlayerLayer` and hides while HLS is the
  picture. The audio session is configured playback-only before the first peer, so there is
  no microphone prompt and sound stays on the speaker route. Opus decodes natively, so the
  detail screen has sound over WebRTC where HLS on AVFoundation had none.
- **Desktop** — stays on HLS through FFmpeg. libwebrtc for the JVM would add a second large
  native bundle per platform for a small latency win.

Watch a join on a device with `adb logcat -s HomeSafeLive:D` — the holder logs the elapsed time
and, on failure, the reason before falling back.

## Server setup

go2rtc already listens on 8555 for WebRTC (TCP and UDP, every interface). Two things make the
join work from a phone:

1. **Candidates.** go2rtc's answer must name the addresses the phone can reach. In Frigate's
   `config.yml`, under the existing `go2rtc:` block:

   ```yaml
   go2rtc:
     webrtc:
       listen: ":8555"
       candidates:
         - 192.168.68.65:8555     # LAN (eno2, wired via the camera switch)
         - 100.99.163.71:8555     # Tailscale (tailscale0)
       ice_servers: []            # host candidates only, no outbound STUN
       filters:
         networks: [udp4, tcp4]   # IPv4 only; see below
   ```

   Then `docker compose restart frigate`.

   Keep the IPv4 filter. go2rtc binds UDP 8555 on every address one by one, and a single failed
   bind aborts the whole WebRTC module: no `/api/webrtc` (404) and no UDP listener, so every
   join falls back to HLS. On 2026-09-25 that happened on every boot, because Frigate starts
   before the Docker bridge's IPv6 link-local address is ready (`listen udp
   [fe80::…%br-…]:8555: bind: cannot assign requested address` in go2rtc's log). IPv4 addresses
   are usable as soon as they exist, and one that doesn't exist yet is skipped rather than fatal.

   Check that it came up: `ss -lunp | grep 8555` on the box should list a UDP socket per IPv4
   address, and `docker exec frigate grep webrtc /dev/shm/logs/go2rtc/current` should show
   `[webrtc] listen addr=:8555` and no `ERR`. Don't paste `:1984/api/config` or
   `:1984/api/streams` output anywhere: both include the cameras' RTSP passwords.

2. **Firewall.** ufw allowed 8555/tcp on Tailscale only. ICE prefers UDP and only falls back to
   TCP, so without the UDP rule a join "works" but with head-of-line blocking:

   ```bash
   sudo ufw allow in on tailscale0 proto udp to any port 8555 comment 'go2rtc WebRTC ICE/UDP via Tailscale'
   sudo ufw allow in on eno2 from 192.168.68.0/22 proto udp to any port 8555 comment 'go2rtc WebRTC ICE/UDP from LAN'
   sudo ufw allow in on eno2 from 192.168.68.0/22 proto tcp to any port 8555 comment 'go2rtc WebRTC ICE/TCP from LAN'
   ```

Smoke test before blaming the app: open Frigate's own web UI on the phone, switch a camera's live
view to WebRTC, and confirm it plays over both the LAN and Tailscale. From a computer on the
LAN, `http://192.168.68.65:1984/` serves go2rtc's own pages. On one of them, a
`RTCPeerConnection` with `iceServers: []` that POSTs its offer to
`/api/webrtc?src=hikvision_1_sub` does the same join as the app. On 2026-09-25 that connected
over UDP to 192.168.68.65:8555 with a 4 ms round trip and decoded 640x360 at 25 fps.

The floor on first-frame time after ICE connects is the camera's keyframe interval when go2rtc is
already connected to it: a new consumer starts at the next keyframe. The sub streams send one
every second since 2026-09-27 (see "Time to the first live picture"). For a stream nobody was
watching, the camera opens its new session on a keyframe, and the wait is the RTSP dial instead.
