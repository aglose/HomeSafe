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
| Telling you | Three minutes into each boot the relay pushes "Server restarted" with what came back, or a list of what didn't: cameras with no video, recordings not on the 4 TB drive, Frigate not answering, the vision model missing. | `relay/relay.py`, "boot report" |
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
