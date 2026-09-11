# Live video over WebRTC

HLS puts a couple of seconds of segments between the camera and the screen, and a fresh join has
to fetch a playlist and a segment before anything moves. WebRTC is a peer connection straight to
go2rtc: after one signaling round trip the server pushes frames as the camera produces them, so a
card shows moving video within a keyframe interval of connecting and stays a fraction of a second
behind live. This is how Android plays every live stream now; HLS remains the fallback there and
the only path on iOS and desktop (see "Platforms" below).

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

Joins are make-before-break. The previous engine — an HLS session or an older peer — keeps
drawing until the new peer has a frame, so a quality upgrade on the detail screen and a
LAN↔Tailscale route flip no longer pass through black. Every binder (grid card, detail screen)
draws through its own renderer attached as a sink on the same video track, so there is no
surface hand-over to bridge either.

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
- **iOS** — still HLS through AVFoundation. The common flow is ready for a peer implemented on
  the Swift side over `WebRTC.xcframework` (no cinterop needed), which is the next step.
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
         - 192.168.68.64:8555     # LAN (wlo1)
         - 100.99.163.71:8555     # Tailscale (tailscale0)
       ice_servers: []            # host candidates only, no outbound STUN
   ```

   Then `docker compose restart frigate`. Check the merged config at
   `http://100.99.163.71:1984/api/config`.

2. **Firewall.** ufw allowed 8555/tcp on Tailscale only. ICE prefers UDP and only falls back to
   TCP, so without the UDP rule a join "works" but with head-of-line blocking:

   ```bash
   sudo ufw allow in on tailscale0 proto udp to any port 8555 comment 'go2rtc WebRTC ICE/UDP via Tailscale'
   sudo ufw allow in on wlo1 from 192.168.68.0/22 proto udp to any port 8555 comment 'go2rtc WebRTC ICE/UDP from LAN'
   sudo ufw allow in on wlo1 from 192.168.68.0/22 proto tcp to any port 8555 comment 'go2rtc WebRTC ICE/TCP from LAN'
   ```

Smoke test before blaming the app: open Frigate's own web UI on the phone, switch a camera's live
view to WebRTC, and confirm it plays over both the LAN and Tailscale.

The floor on first-frame time after ICE connects is the camera's keyframe interval: go2rtc starts
a new consumer at the next keyframe, and these cameras send one every 2 s. Setting the I-frame
interval equal to the frame rate on the sub streams takes up to a second off every card.
