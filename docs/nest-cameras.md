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
  like Frigate's cards (`frigateLiveStreamUrl`, `frigateWebRtcSignalingUrl`). Nest cards have no
  poster: Frigate has no snapshot of them, and a go2rtc still would dial Google just for a poster.

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

## Arranging the cameras

**Arrange** above the camera list (beside the Outdoor | Indoor switch) puts Home into Arrange mode:

- **Order**: hold a card and drag it (the `sh.calvin.reorderable` library on the lazy grid). Each
  list has its own order. A screen reader gets "Move earlier" and "Move later" actions on each
  card instead. Saved on every drop, by camera name, per server (`CameraLayoutEntity.position`).
- **Orientation**: each card shows a quarter turn either way and a fine tilt slider (±45°) along
  its bottom, and the live picture turns as they move. Turns save at once; the tilt saves when the
  slider is let go (`CameraLayoutEntity.quarterTurns` / `tiltDegrees`). A tilted picture is drawn
  just large enough to cover its box (`turnedPictureSize`), so no corner shows. On its side, a
  picture shows at the inverse shape, with bars. The turn applies wherever the camera's video
  plays: cards, quick look, the Nest page, and a Frigate camera's page (live, recordings and the
  scrub preview). Moments thumbnails and clips are not turned.
- On Android the players' TextureViews turn with Compose (`Modifier.turned`). On iOS the player
  turns its AVPlayer and WebRTC layers natively in `layoutSubviews`, from the same size, because
  Compose can't transform a UIKit view.

Code: `NestCameraRepositoryImpl` and `CameraLayoutRepositoryImpl` (data), `nestStreamNames` in `network/Go2rtcStreams.kt`,
`OUTDOOR_STREAM_PREFIX`, `HomeViewModel.cameras` / `indoorCameras` / `placement`,
`CameraPlacementSwitch`, `NestCameraScreen`.

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

### 5. Check

```bash
curl -s http://127.0.0.1:1984/api/streams | python3 -c 'import json,sys; print([k for k,v in json.load(sys.stdin).items() if any((p.get("url") or "").startswith("nest:") for p in v.get("producers") or [])])'
```

lists the Nest streams by name. `http://<box>:1984/stream.html?src=living_room` plays one in a
browser. In the app, pull to refresh on Home: the switch appears above the cameras.

## Notes

- **First picture takes a few seconds**: go2rtc has to ask Google for a stream and Google has to
  wake the camera. Later viewers join the running stream.
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
