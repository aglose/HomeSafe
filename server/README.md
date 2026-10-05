# The Frigate box: coming back after a restart

The box (`debian-surveillance`) restarts now and then: a power cut, a kernel update, someone at
the console. These pieces make a restart end with everything running and a push to the phones
saying so, with no one having to log in.

| Step | What does it | Where it lives on the box |
|---|---|---|
| Network | `eno2` is `auto`: static 192.168.1.250 for the cameras, plus DHCP for the home network (the camera switch is uplinked to the Deco since 2026-09-25). Wi-Fi (`wlo1`) is the spare. | `/etc/network/interfaces.d/eno2` |
| Staying online | The uplink watchdog, every minute from 3 min after boot. Wired: rebinds DHCP, then brings Wi-Fi up as a spare route. Wi-Fi: reassociates, restarts, reloads the driver. It never takes `eno2` down and never reboots. | `uplink-watchdog/` → `/usr/local/bin`, `/etc/systemd/system` |
| Recording drive | Docker starts after `/mnt/surveillance` is mounted (the drive is `nofail`). Frigate's storage bind has `create_host_path: false`, so without the drive it refuses to start rather than recording onto the 441 GB boot disk. | `docker/wait-for-recordings.conf` → `/etc/systemd/system/docker.service.d/`; `~/surveillance/frigate/docker-compose.yml` |
| Containers | Frigate, the relay and Ollama are `restart: always`. `unless-stopped` left both containers down after the 2026-09-09 reboot, because they had once been stopped by hand. | the compose files |
| Live video without the wait | go2rtc keeps every `_sub` stream open, so a viewer's join doesn't wait for it to dial the camera: hikvision_2's and amcrest_1's detect inputs read go2rtc's restream, and the `go2rtc-keepalive` container (Frigate's image running ffmpeg, `restart: always`) holds hikvision_1's. Since 2026-09-27; see "Time to the first live picture" in docs/webrtc-live.md. | `~/surveillance/frigate/config/config.yml`, `~/surveillance/frigate/docker-compose.yml` |
| Telling you | Three minutes into each boot the relay pushes "Server restarted" with what came back, or a list of what didn't: cameras with no video, recordings not on the 4 TB drive, Frigate not answering, the vision model missing. | `relay/relay.py`, "boot report" |
| Keeping the record | Once a minute the relay tries each link between a phone and a camera from the box's side (router, internet, name lookups, Tailscale, Frigate, the cameras, live video) and notes which of the household's devices Tailscale can see. It keeps 90 days in `relay.db`. See "The uptime record" below. | `relay/relay.py`, "uptime"; the `/var/run/tailscale` mount in `relay/docker-compose.yml` |
| Telling you it's down | `homesafe-heartbeat.timer` pings an off-box dead-man switch every 5 min. It does nothing until `/etc/homesafe/heartbeat-url` holds a check URL (healthchecks.io or similar). | `/usr/local/bin/homesafe-heartbeat.sh` |

One setting has to be changed at the machine itself: in the BIOS, **Restore on AC power loss = Power On**, so
a power cut ends in a reboot rather than a box that stays off.

## Installing the watchdog and the Docker drop-in

```bash
scp server/uplink-watchdog/* server/docker/wait-for-recordings.conf andrew@100.99.163.71:surveillance/uplink-watchdog/
```

```bash
ssh frigate 'cd ~/surveillance/uplink-watchdog && sudo install -m 755 homesafe-uplink-watchdog.sh /usr/local/bin/ && sudo install -m 644 homesafe-uplink-watchdog.service homesafe-uplink-watchdog.timer /etc/systemd/system/ && sudo install -d /etc/systemd/system/docker.service.d && sudo install -m 644 wait-for-recordings.conf /etc/systemd/system/docker.service.d/ && sudo systemctl daemon-reload && sudo systemctl enable --now homesafe-uplink-watchdog.timer'
```

## After a restart

```bash
ssh frigate 'sudo journalctl -t homesafe-uplink-watchdog -b; sudo journalctl -t homesafe-relay -b | grep "boot report"'
```

If the boot report says live video is on the HLS fallback, go2rtc's WebRTC module failed to
start. Usually one address it tried to listen on wasn't ready; its error line names that address:

```bash
ssh frigate 'docker exec frigate grep -i "webrtc\|8555" /dev/shm/logs/go2rtc/current | tail -5'
```

Since 2026-09-25 the go2rtc `webrtc:` block has `filters: networks: [udp4, tcp4]`, which keeps it off
the Docker bridge's IPv6 link-local address that tripped it after that day's reboot.

## The uptime record

"I couldn't connect" has several possible culprits, and only some of them are the box. On
2026-10-04 the app couldn't connect from away; the box had been healthy throughout, and the
phone's Tailscale had been off since the night before. Finding that out took an afternoon in the
journal. The relay now keeps the answer:

- **In the app:** Settings › Server › Uptime. It reads over whichever address the app is using,
  so it works on the home Wi-Fi as well as over Tailscale.
- **In a browser:** `http://192.168.68.65:8787/status` at home, `http://100.99.163.71:8787/status`
  over Tailscale. Sign in to Frigate (port 8971) in the same browser first: the page's data rides
  on that session cookie.

Each row is one check, each block a span of time: green up, amber partly down, red down, grey not
measured. "Server running" is the record itself: a gap in it is a restart, a power cut, or the
relay being rebuilt. "Your devices on Tailscale" is the row to read when the server was up and
the app still couldn't connect: a phone Tailscale can't see has no route to the box from away.

The Tailscale check and the device list come from tailscaled's LocalAPI socket, which the relay's
container mounts read-only. Without the mount both are left out rather than shown as down.

What the record can't show is the box being unreachable *from outside* while everything on it is
fine (the ISP dropping inbound traffic, say). The heartbeat above covers a box with no internet;
the device row covers a phone with no Tailscale.

```bash
ssh frigate 'sudo journalctl -t homesafe-relay --since "1 hour ago" | grep "uptime:"'
```

prints each minute something was down, as the relay logged it.
