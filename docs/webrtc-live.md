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
3. Offer-to-connected has a 3 s budget and connected-to-first-frame 5 s
   (`LivePlaybackPolicy`). Any failure closes the peer and the holder starts HLS for the same
   source **without a new cold-start generation**: the poster is already up, so the user sees
   poster → HLS video rather than a flash.
4. No STUN or TURN. Clients reach the server over the LAN or Tailscale, both plain routes between
   host addresses, so the offer carries host candidates only and gathers them in milliseconds.

`LiveTransportMemory` is the fallback policy: a stream gets two failed joins, then stays on HLS
for ten minutes from the last failure before WebRTC is tried again; a successful join clears its
record. It is keyed by the HLS URL, host included, so the LAN and Tailscale routes to one camera
are separate entries and moving between networks naturally gets a fresh try.

It also remembers which streams have *proven* WebRTC lately (`recentlyConnected`, ten minutes from
the last successful join). A cold start of an unproven stream — the first open after launch, or a
route the app has never joined on — starts HLS alongside the WebRTC join rather than sitting on
the poster for the join's full budget (3 s + 5 s) when ICE can't get through: the picture is up at
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

## Platforms

- **Android** — `io.getstream:stream-webrtc-android` (the upstream `org.webrtc` API).
  `WebRtcRuntime` initialises libwebrtc once per process with one shared EGL context;
  `AndroidWebRtcPeer` wraps a `PeerConnection`; `WebRtcTextureRenderer` is a `TextureView` drawn
  by libwebrtc's `EglRenderer`, stacked over the HLS `TextureView` and cleared whenever the
  holder goes back to HLS. `LivePlayerHolder.transport` says which engine is showing the picture;
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
   join falls back to HLS. That happened after the 2026-09-25 reboot: Frigate had started before
   the Docker bridge's IPv6 link-local address was ready (`listen udp
   [fe80::…%br-…]:8555: bind: cannot assign requested address` in go2rtc's log). It's a timing
   race, so it may not happen on every boot, and a reboot with the filter in place hasn't been
   tested yet. IPv4 addresses are usable as soon as they exist, and one that doesn't exist yet
   is skipped rather than fatal.

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
view to WebRTC, and confirm it plays over both the LAN and Tailscale.

To repeat the app's own join from a computer on the LAN:

1. Open `http://192.168.68.65:1984/webrtc.html` in a browser. Any page served from port 1984
   will do; this one is only there so the request below is same-origin. Don't use its built-in
   player: it configures a public STUN server, and the app sends host candidates only.
2. Paste this into the browser's developer console:

   ```js
   const pc = new RTCPeerConnection({ iceServers: [] });
   pc.addTransceiver('video', { direction: 'recvonly' });
   await pc.setLocalDescription(await pc.createOffer());
   await new Promise(r => setTimeout(r, 1500)); // let host candidates gather
   const res = await fetch('/api/webrtc?src=hikvision_1_sub', {
     method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: pc.localDescription.sdp,
   });
   await pc.setRemoteDescription({ type: 'answer', sdp: await res.text() });
   await new Promise(r => setTimeout(r, 5000));
   const stats = [...(await pc.getStats()).values()];
   const pair = stats.find(s => s.type === 'candidate-pair' && s.nominated && s.state === 'succeeded');
   const inbound = stats.find(s => s.type === 'inbound-rtp' && s.kind === 'video');
   console.log(res.status, pc.iceConnectionState, pair && stats.find(s => s.id === pair.remoteCandidateId),
     inbound && inbound.framesDecoded);
   pc.close();
   ```

   A healthy server prints `201 connected`, a remote candidate on 192.168.68.65:8555 over UDP,
   and a frame count above zero. A 404 means go2rtc's WebRTC module didn't start (see the IPv4
   filter above).

That was run once, on 2026-09-25 after the filter went in. It connected over UDP to
192.168.68.65:8555 with a 4 ms round trip and decoded 640x360 at 25 fps.

The floor on first-frame time after ICE connects is the camera's keyframe interval: go2rtc starts
a new consumer at the next keyframe, and these cameras send one every 2 s. Setting the I-frame
interval equal to the frame rate on the sub streams takes up to a second off every card.
