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
- **As Frigate cameras** (see "Frigate cameras" under the box setup): a Frigate camera reading a
  Nest stream takes the Nest camera's place in the lists (`camerasWithNest`), with Frigate's
  live snapshot for a poster and Frigate's camera page; Frigate keeps its stream running, so the
  card never waits on Google to start the camera.
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
    living_room_sub: "ffmpeg:living_room#video=h264#width=640#hardware=cuda"
```

go2rtc re-encodes the camera on the GPU at 640 wide (the height follows the picture's shape), and
the box's `go2rtc.ffmpeg.h264/cuda` template already gives it a keyframe every 25 frames. That is
the point: Google sends a Nest camera's keyframes seconds apart (7–10 s measured), and a viewer
joining a running stream sees nothing until the next one, while a viewer joining `_sub` waits about
a second. The cards also decode a quarter of the pixels and use far less data over Tailscale. The
first viewer of a stream nobody is watching still waits for Google to start the camera.
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

### 7. Frigate cameras (recommended): always ready

A Nest stream exists only while someone watches it, so the first viewer after a quiet spell waits
for Google to start the camera (1–11 s). Making each Nest camera a Frigate camera keeps its
`_sub` stream running all the time, the way Frigate keeps the outdoor cameras' sub streams
running, so every card joins a running stream that has a keyframe about every second. It also
gives the cards Frigate's live snapshot as their poster, and the camera Frigate's page in the app
(timeline, settings), with detection and recording off until you want them.

The cost: every camera streams to Google and back all day, roughly 1–2 Mbps each over the home
internet, and a camera with a "live" light keeps it on. Only for wired cameras; a battery one
would be flat in days.

Under `cameras:` in `config/config.yml`, one per Nest camera, reading its `_sub` stream from
go2rtc (the name must be the Nest stream's, so the app knows which Nest camera it is):

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
