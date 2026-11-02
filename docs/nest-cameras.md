# Nest cameras

The Home page opens on the outdoor cameras. When the server also has indoor Google Nest
cameras, an **Outdoor | Indoor** switch heads the camera list, and Indoor shows them as live
cards. A Nest camera outside (a doorbell) sits in the Outdoor list after Frigate's cameras.
Tapping a Nest camera opens a live-only page with sound. There is no timeline or recordings, and
no detections: the Nest cameras are not Frigate cameras.

## How it works

- Frigate's go2rtc (1.9.10 on the box) pulls a Nest camera through Google's Smart Device
  Management (SDM) API with a `nest:` stream source. It dials Google only while someone is
  watching, the same way the Google Home app does, and keeps the SDM session alive while they do.
- The app reads go2rtc's `GET :1984/api/streams` and treats **every stream with a `nest:` source
  as a Nest camera** (`CameraSource.Nest`), unless a Frigate camera already plays that stream.
  No app setting is needed: add the stream on the server, pull to refresh on Home, and it appears.
- **Where it goes is in the stream's name**: a name starting with `outdoor_` puts the camera in
  the Outdoor list; every other Nest camera is Indoor. The setup script names doorbells that way.
- The stream name is the camera's name in the app, less any `outdoor_`: `living_room` shows as
  "Living Room", `outdoor_doorbell` as "Doorbell".
- The cards and the live page play the stream with go2rtc's WebRTC, falling back to HLS, exactly
  like Frigate's cards (`frigateLiveStreamUrl`, `frigateWebRtcSignalingUrl`).
- **As Frigate cameras** (optional, for detection; see "Frigate cameras" under the box setup): a
  Frigate camera reading a Nest stream takes the Nest camera's place in the lists
  (`camerasWithNest`), with Frigate's live snapshot for a poster and Frigate's camera page. The box
  has none since 2026-11-01. The relay's primer is what keeps the streams running.
- **A smaller stream for the cards**, like the Frigate cameras' `_sub` streams: a go2rtc stream
  named `<camera>_sub` beside a Nest camera's is what its card (and the launch prefetch) plays;
  its page starts on that and moves up to the camera's own stream once it has a picture, the
  player keeping the smaller one up until the full one has a frame. Without one the card plays the
  camera's own stream, as before. See "Grid streams" under the box setup for why they are worth
  adding.
- **Posters are last-seen stills** (`LastSeenStill`). Frigate has no snapshot of a Nest camera,
  and go2rtc's `frame.jpeg` waits for the stream's next keyframe (7–10 s on the living room's
  running stream, measured 2026-10-17), so asking for one as the card appears would be no quicker
  than the video. Instead the card takes one from go2rtc a couple of seconds after its video is
  playing (at most every 10 minutes per stream), keeps it in Coil's disk cache, and shows it from
  there next time, on the first frame, even on a cold start. A camera never watched on this device
  shows nothing until its video comes, as before.

- **Pictures come in several shapes**: on the box, the living room and kids' room cameras send
  1920x1080 (16:9), the kitchen Hub Max 1152x864 (4:3) and the doorbell 960x1280 (portrait).
  Every player stretches its picture to its box, which suits Frigate's cameras, so a Nest
  camera's video is laid out at its own shape instead (`ShapedVideo`): whole with bars by
  default, or filling the box and cropped. The shape is read from the player's stream stats the
  first time and remembered for the session. The Nest camera page's button switches between the
  two (shown only for a camera that isn't 16:9), and the choice is saved for every Nest camera
  (`PlaybackPreferences.nestFit`, schema 25).

- **Ready at launch**: the Nest cameras the server last listed are kept on the device
  (`NestCameraEntity`, schema 26), so the Indoor switch and cards are there the moment Home opens.
  As soon as the launch knows which server it is landing on, before sign-in finishes,
  `LiveStreamPrefetcher` asks go2rtc for the list again and starts up to four Nest streams
  alongside the outdoor ones. A prefetched player is held for 15 s, then idles out, so switching
  to Indoor within about a minute of opening the app finds video already flowing. This wakes
  each camera through Google on every launch, and most Nest cameras show their light while watched.

## Arranging and framing the cameras

**Arrange** above the camera list (beside the Outdoor | Indoor switch) puts Home into Arrange mode:

- **Order**: hold a card and drag it (the `sh.calvin.reorderable` library on the lazy grid). Each
  list has its own order. A screen reader gets "Move earlier" and "Move later" actions on each
  card instead. Saved on every drop, by camera name, per server (`CameraLayoutEntity.position`).
- **Quick turns**: each card has a quarter turn either way along its bottom, saved at once.
- **Framing**: tapping a card (or its **Frame** button) opens the framing editor
  (`CameraFramingScreen`) for any camera, Frigate's or Nest's. The card's video travels into a
  frame of the card's shape, playing on the same pooled player. In it:
  - one finger drags the picture, two pinch it (1x to 5x) and twist it straight; a twist counts
    only past 4°, so a pinch doesn't knock the picture crooked;
  - zoomed in, what the frame cuts off still shows around it, dimmed, so the crop is chosen
    against the whole view; a grid of thirds sits over the frame, filling in to sixths while
    straightening;
  - the **Straighten** ruler slides under a fixed pointer, a tenth of a degree at a time (16dp of
    finger per degree), with a haptic tick each degree and a firmer one at level, settling on level
    when let go within 0.3°; the arrows either side nudge a tenth of a degree, and a tap on the
    angle levels it. ±44° within each quarter turn;
  - a zoom slider and buttons, the quarter turns, and **Reset** (whole and upright).
  Every change is saved as it is let go (`CameraLayoutEntity.quarterTurns` / `tiltDegrees`, and
  `cropZoom` / `cropCenterX` / `cropCenterY` since schema 27), so Done and Back keep it; Cancel
  puts back the framing the editor opened on.
- **Where it applies**: the turn and the crop apply wherever the camera's video plays: cards,
  quick look, the Nest page, and a Frigate camera's page (live, recordings and the scrub preview).
  Pinch zoom on those pages works on top of the crop. Moments thumbnails and clips are not framed.
- The crop is kept as a zoom and the point of the picture in the middle of the frame, as
  fractions (`CameraCrop`), so it frames any box alike. It is drawn with Compose's layer transform
  outside the turn (`Modifier.cropped`), on both platforms. The turn is drawn as before: on
  Android the players' TextureViews turn with Compose (`Modifier.turned`); on iOS the player turns
  its AVPlayer and WebRTC layers natively in `layoutSubviews`, because Compose can't rotate a
  UIKit view. A tilted picture is drawn just large enough to cover its box (`turnedPictureSize`),
  so no corner shows; on its side, a picture shows at the inverse shape, with bars.

Code: `NestCameraRepositoryImpl` and `CameraLayoutRepositoryImpl` (data), `nestStreams` in
`network/Go2rtcStreams.kt`, `OUTDOOR_STREAM_PREFIX`, `HomeViewModel.cameras` / `indoorCameras` /
`placement`, `CameraPlacementSwitch`, `NestCameraScreen`, `LastSeenStill`, `CameraFramingScreen`.

## One-time setup

### 1. Google Cloud: the OAuth client

Any Google Cloud project works (the Google Home integration's `percysafe` is fine).

1. **APIs & Services → Library**: enable **Smart Device Management API**.
2. **OAuth consent screen**: user type *External*. Add your Google account as a test user, then
   **Publish app** so it is *In production*. While an app is in *Testing*, Google expires its
   refresh tokens after 7 days and the cameras would stop working every week. A personal app
   doesn't need Google's verification; you'll click past an "unverified app" warning once.
3. **Credentials → Create credentials → OAuth client ID**: type *Web application*, authorized
   redirect URI `https://www.google.com`. Keep the client ID and secret.

### 2. Device Access

1. At the [Device Access Console](https://console.nest.google.com/device-access), accept the terms
   and pay Google's one-time $5 registration fee.
2. **Create project**: give it a name, paste the OAuth client ID from step 1, and skip events
   (Pub/Sub is not used). Keep the **Project ID** it shows.

### 3. Sign in and list the cameras

On the Mac (or anywhere with Python 3; it needs no packages):

```bash
python3 tools/nest-setup/nest_setup.py --out ~/nest-secrets.env
```

It asks for the project ID, client ID and secret, prints a Google sign-in address, and after you
allow the cameras and paste back the google.com address you land on, it:

- writes `FRIGATE_NEST_CLIENT_ID`, `_CLIENT_SECRET`, `_REFRESH_TOKEN` and `_PROJECT_ID` to the
  `--out` file, readable only by you, and never prints them;
- prints a go2rtc `streams:` entry per camera that names those variables instead of containing them.

`--reuse ~/nest-secrets.env` lists the cameras again later without signing in.

### 4. The box

All in Frigate's directory on the box (`~/surveillance/frigate`):

1. Append the four `FRIGATE_NEST_*` lines from the secrets file to `frigate.env` (the compose
   file already loads it). Frigate substitutes `{FRIGATE_*}` variables into go2rtc stream strings.
2. Back up `config/config.yml`, then paste the printed entries under `go2rtc:` → `streams:`.
3. **Fix the container's DNS.** With `network_mode: host`, Docker copies the host's
   `/etc/resolv.conf` when the container starts. On 2026-10-17 the running Frigate container still had
   Tailscale's resolver (`100.100.100.100`) from when it started on Oct 1, and that resolver
   answers "server misbehaving" for public names, so go2rtc couldn't reach `googleapis.com`
   (`curl :1984/api/nest` showed the failed lookup). The host itself, and the relay container,
   resolve fine. Give Frigate a resolver of its own so a later resolv.conf change can't break it:

   ```bash
   printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > ~/surveillance/frigate/resolv.conf
   ```

   and in `docker-compose.yml`, under the `frigate` service's `volumes:`:

   ```yaml
         - ./resolv.conf:/etc/resolv.conf:ro
   ```

4. Recreate the container so it picks up the env file, the config and the mount:
   `docker compose up -d frigate`.

### 5. Grid streams (recommended)

Each Nest camera gets a smaller stream for the cards, as the Frigate cameras have their `_sub`
streams. Under `go2rtc:` → `streams:` in Frigate's `config/config.yml`, beside each Nest stream:

```yaml
    living_room_sub: "ffmpeg:living_room#video=h264#width=640#hardware=cuda#raw=-fps_mode passthrough"
```

`-fps_mode passthrough` matters for the Hub Max (kitchen): ffmpeg's RTSP output is constant-rate by
default and filled its timestamp gaps with copies of frames, 85,000 in 6 s, which saturated the
GPU's encoder and left Frigate with 0.3 fps. On the box 2026-10-17 all four `_sub` streams have it.
A portrait doorbell needs both sides given (`#width=480#height=640`): scaled to 640 wide it would be
853 tall, and H.264 wants even sizes.

go2rtc re-encodes the camera on the GPU at 640 wide (the height follows the picture's shape), and
the box's `go2rtc.ffmpeg.h264/cuda` template already gives it a keyframe every 25 frames. That is
the point: Google sends a Nest camera's keyframes seconds apart (7–10 s measured), and a viewer
joining a running stream sees nothing until the next one, while a viewer joining `_sub` waits about
a second. The cards also decode a quarter of the pixels. The
first viewer of a stream nobody is watching still waits for Google to start the camera. It doesn't
save data, though: idle on 2026-11-01 the `_sub` streams ran 1.4–1.9 Mbit/s against 0.2–1.4 Mbit/s
for their cameras' own streams.
`curl :1984/api/streams` lists it with an `ffmpeg:` source, so the app never takes it for a camera
of its own. Pull to refresh on Home and the cards move to it.

### 6. The relay's Nest primer (needed for Frigate and for quick joins)

go2rtc (1.9.10 in Frigate 0.17, and still in 1.9.14) binds the viewer that *starts* a Nest
session to the first H.264 payload type in Google's answer, while Google sends on another: that
viewer never gets a frame, and every viewer joining after it does. Measured 2026-10-17 on an idle
`kids_room`: the first `stream.mp4` read got nothing in 15 s while the session's other receiver
filled with video; a second read got 1.7 MB. `curl :1984/api/streams?src=<name>` shows it as two
video receivers, one stuck at 0 bytes. So a Nest stream's first viewer is lost: the app's first
join (its HLS shadow got the picture instead, slowly), and every `<name>_sub` transcode and
Frigate reader, which then never start at all ("Output file does not contain any stream").

The relay works around it (`nest_primer_forever` in relay.py, on unless `NEST_PRIMER=0`): it reads
go2rtc's stream list every minute and holds one `stream.mp4` viewer on every Nest stream,
starting each session itself so the dead binding is its own. Everyone else then joins a running
session. It also keeps every Nest camera streaming through Google all day.

Leaked `frame.jpeg` requests did this by accident before: a client that gave up waiting for a
keyframe stays a go2rtc consumer, and held the session open (and, on a dead binding, held it dead).

**Self-healing.** Google ends a session now and then, and go2rtc only notices when the session's
connection closes, which a WebRTC session Google stopped feeding doesn't do; it also tears a
session down only once no viewer is left on it. So a dead session can sit there for hours with the
primer, the `_sub` transcoder, and any leaked `frame.jpeg` viewer still attached, while Frigate
reports 0 fps (2026-10-18 and -19). The primer heals it in two steps:

1. A watchdog beside each hold reads the stream's video receiver bytes every 15 s. When they've
   stood still for 60 s, it drops the hold and takes it again. When the hold was the session's
   last viewer, go2rtc opens a fresh one and the camera is back.
2. When the hold taken again still gets no video for 60 s, something else is keeping the dead
   session, so the relay restarts go2rtc (`POST :1984/api/restart`, an in-place re-exec). Every
   stream blips for a few seconds, about 2–3 minutes after the video stopped. Restarts are at
   least 30 minutes apart, doubling to 4 hours while they don't bring the camera back.

A camera Google won't stream at all never gets that far: go2rtc opens no session for it, so there
is nothing for a restart to clear. The primer just keeps asking, every 6 minutes once it has backed
off, and the camera is back within that long of Google streaming it again. Frigate's log shows
`nest: wrong status: 400 Bad Request` for it. Google's reason, "The camera is not available for
streaming", came for the doorbell on 2026-10-17 (turned off in Google Home) and from 07:00 on
2026-10-20; check the camera in the Google Home app.

**The unavailable-camera push.** Only someone with the Google Home app can fix that, so the relay
says so. When a Nest stream's holds have gone an hour without go2rtc ever having a session for
it, the relay asks Google itself: the same `GenerateWebRtcStream` go2rtc sends, with a made-up
offer and the credentials read from the stream's `nest:` source in go2rtc (`GET
:1984/api/streams?src=<name>`). It never logs them, nor anything Google says that names them.

- Google says "The camera is not available for streaming": one push, *Outdoor Doorbell isn't
  streaming. Since 7:02 AM, Google says the camera isn't available for streaming. Check it in the
  Google Home app.*
- Google refuses some other way, or can't be asked: the same push, worded *No video from Google
  since 7:02 AM.*
- Google starts a session: the relay stops it again at once and pushes nothing, since the trouble
  is between go2rtc and Google, not something the Google Home app can fix. It asks again an hour
  later if the holds still have no session.

The push is an ordinary alert: each phone's quiet hours and *only when away* apply. One no phone
got is tried again at the next hold (within about 6 minutes), without asking Google again, so a
phone in quiet hours hears of it once they end. It is never repeated while the outage lasts, even
across a relay restart (the cameras told about are kept in relay.db, `state` key
`nest_outage_alerted`). The first time the camera's video moves again, a second push, *Outdoor
Doorbell is streaming again*, replaces the first (both carry `notif_id=nest-<name>`). That one
too stays owed until a phone gets it, tried again every 5 minutes, and across a restart; a
camera out again before then is still the one outage, so it isn't pushed twice.

The push's data carries `system=nest`, `camera`, `nest_camera_name`, `nest_event`
(`unavailable` or `streaming`), `nest_reason` (`refused` or `unconfirmed`) and `nest_since`
(epoch seconds), so an app can word it in its own language; until one does, phones show the
relay's English title and body.

The relay logs `nest primer: <name> had no video for 60 s; dropping it` for the first step and a
warning `... restarting go2rtc` for the second (`docker logs homesafe-relay | grep "nest primer"`).
The unavailable-camera push logs under `nest outage:`, with Google's answer.

### 7. Frigate cameras (optional): only for detection or recording

Don't add a Nest camera to Frigate just to keep it ready. The primer (step 6) already holds each
Nest stream and its `_sub`, so every card joins a running stream that has a keyframe about every
second. That costs every camera streaming to Google and back all day, roughly 1–2 Mbps each over
the home internet, and a camera with a "live" light keeps it on. Only for wired cameras; a battery
one would be flat in days.

All four cameras were Frigate cameras until 2026-11-01, with detection and recording off, and they
were taken out because the readers did harm and no good:

- Google ends each Nest session after 5 or 10 minutes (go2rtc renews only one, once), so the
  stream under a reader keeps breaking. Kitchen's restarted 285 times a day, mostly on Frigate's
  "exceeded fps limit".
- Two readers' watchdogs hung mid-restart on 2026-10-21. `/api/stats` kept showing their last
  `camera_fps` (109) for 11 days with no reader running, and the cards kept streaming without them.
- A camera Google refuses has its reader respawned every 10 s (8,620 times a day, 39% of Frigate's
  log), and holds the relay's uptime check for the cameras at false.
- A camera without frames gets Frigate's "No frames have been received" image from `latest.jpg`,
  which the app shows as the card's poster.

To run detection on a Nest camera anyway, it needs an entry under `cameras:` in `config/config.yml`
reading its `_sub` stream from go2rtc (the name must be the Nest stream's, so the app knows which
Nest camera it is), and it gets Frigate's camera page in the app:

```yaml
  living_room:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/living_room_sub
          input_args: preset-rtsp-restream
          roles:
            - detect
    detect:
      enabled: false   # no object detection ("AI") until switched on in the app's Settings
      width: 640
      height: 360
      fps: 5
    record:
      enabled: false   # nothing saved: no recordings, no Moments
    snapshots:
      enabled: false
    audio:
      enabled: false
    live:
      streams:
        Main: living_room
        Sub: living_room_sub
```

The detect size is the `_sub` stream's: 640x360 for a 16:9 camera (living room, kids' room),
640x480 for the 4:3 Hub Max (kitchen), 480x640 for the doorbell. `live.streams` is what tells the
app which stream is the card's (`Sub`) and which the page's (`Main`). Restart Frigate.

In the app, a Frigate camera reading a Nest stream is that Nest camera (`camerasWithNest`): it
stays in the Indoor list (Outdoor for `outdoor_`), keeps the Nest picture's shape, opens
Frigate's camera page, and its Nest entry is left out.

### 8. Check

```bash
curl -s http://127.0.0.1:1984/api/streams | python3 -c 'import json,sys; print([k for k,v in json.load(sys.stdin).items() if any((p.get("url") or "").startswith("nest:") for p in v.get("producers") or [])])'
```

lists the Nest streams by name. `http://<box>:1984/stream.html?src=living_room` plays one in a
browser. In the app, pull to refresh on Home: the switch appears above the cameras.

## Notes

- **First picture takes a few seconds**: go2rtc has to ask Google for a stream and Google has to
  wake the camera. Later viewers join the running stream, at its next keyframe (see "Grid
  streams"). The card shows its last-seen still meanwhile.
- **Security**: go2rtc's API on port 1984 has no login and answers anyone on the tailnet, and its
  stream list shows each source with the variables filled in, so the Nest refresh token is
  readable there. The camera RTSP passwords already are, the same way. Revoke the app's access
  at <https://myaccount.google.com/permissions> to cut it off.
- **Older Nest cameras**: the script marks cameras that offer only RTSP (Nest Cam IQ, the first
  Nest Cam Indoor). Current Nest cameras are WebRTC, which go2rtc plays.
- **Sound**: Nest audio is Opus. Android plays it over WebRTC and HLS; iOS's HLS player plays only
  AAC, so on iOS sound relies on WebRTC (see [go2rtc HLS audio](webrtc-live.md)).
- **Token expiry**: if the cameras stop after a week, the OAuth app is still in *Testing*.
  Publish it (step 1.2), run the script again, and replace the refresh token in `frigate.env`.
- **A patched go2rtc on the box** (since 2026-11-01): stock go2rtc, through 1.9.15, shares one
  session and one extension timer between all the cameras on a set of credentials. Google's
  sessions then end after 5 or 10 minutes, and each `_sub` transcode restarts about 20 s later
  (`error=EOF`, 22 to 39 an hour). `config/go2rtc` in Frigate's directory is v1.9.10 with upstream
  [PR #2351](https://github.com/AlexxIT/go2rtc/pull/2351) (commit `0847868fb5`) applied, built with
  `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags "-s -w" -trimpath`. Frigate runs an
  executable `/config/go2rtc` in place of its own and logs a warning saying so. To undo it, delete
  the file and restart Frigate. **Delete it when upgrading Frigate too**, or it goes on overriding
  the bundled go2rtc. `pkg/nest` logs nothing, so the sign that extensions work is a Nest connection's `id` in
  `/api/streams` staying the same past 10 minutes. That alone doesn't mean the camera is fine, since
  a session Google stopped feeding can stay connected for hours, so its video receiver bytes must be
  rising too (what the primer's watchdog reads). Read both with a script that prints no URLs (see
  "Security"). Don't add a battery camera to this build as it stands: Google says it ignores
  extensions on battery power, and if that comes back as a success with the old expiry, the PR's
  loop asks again at once until Google rate-limits the project.
- **The doorbell**: on 2026-10-17 Google answered every stream request for it with "The camera is
  not available for streaming" (it had been turned off in Google Home), while the relay's primer
  backed off asking. Turned back on 2026-10-18, it streamed (480x640, portrait) and its card joined
  in 0.5–0.9 s. Google has answered the same way since 07:00 on 2026-10-20.
