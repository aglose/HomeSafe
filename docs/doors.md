# Door sensors

Zigbee contact sensors on the doors, read by the Frigate box with no cloud and no subscription.
They took over from SimpliSafe's, whose events only leave SimpliSafe's servers through an
unofficial API and, for an instant "door opened", a paid plan.

```
sensor ──Zigbee──▶ USB stick ──▶ Zigbee2MQTT ──MQTT──▶ Mosquitto ──▶ relay ──▶ app, pushes
                   (on the box)   (server/zigbee)        127.0.0.1:1883   ("doors")
```

| Piece | What it is |
| --- | --- |
| Stick | Sonoff Dongle Plus MG24 (Silicon Labs EFR32MG24, EmberZNet), on a USB extension cable away from the box's USB 3 ports |
| Sensors | ThirdReality Zigbee Contact Sensor (3RDS17BZ), two AAA cells each |
| Zigbee2MQTT, Mosquitto | `server/zigbee/docker-compose.yml`, both `restart: always`, host networking. The broker listens on 127.0.0.1 only. |
| Relay | `relay/relay.py`, "doors": keeps each door's state and 90 days of openings and closings in `relay.db`, answers `GET /doors`, and pushes |
| App | Pills at the top of Home (each open or offline door, and a count of the closed ones) opening the Doors page, also at Settings › Doors: each sensor's battery and signal, its push setting, and the last 7 days |

## What pushes

Each door has its own setting, chosen in the app (`PUT /doors/{id}` with `{"notify": …}`):

| Setting | Everyone away | Someone home |
| --- | --- | --- |
| **Only when away** (default) | Pushes, loud, to every phone | Nothing |
| **Always** | Pushes, loud, to every phone | Pushes like any ordinary alert: each phone's quiet hours and "only when away" still hold it back |
| **Never** | Nothing | Nothing |

- Only an opening pushes. The history keeps both.
- A door opened again within 2 minutes (`DOOR_QUIET_SECONDS`) of its last push updates that
  notification without a sound, so a door propped and swung doesn't ring five times.
- "Away" is the same away mode the cameras use (see [away-mode.md](away-mode.md)).
- The push carries `door`, `door_name`, `door_event=opened`, `at` and `notif_id=door-<id>`, plus
  `away=1` when it went out as an away alert; the app words it from those.

## What a push looks like

On Android a door's notification wears a door in the status bar (`NotificationGlyph.DOOR`), in
the app's orange; one that went out as an away alert wears the away shield in red instead, as
every alert on the loud away channel does, and its title says which door. A tap opens the Doors
page on Home, above the camera list (`homesafe://doors?door=<id>`, `DoorDeepLink`), whatever was
up, and Back returns to the list. Icons and channels: [notifications.md](notifications.md#icons).

On iOS the banner is the one APNs draws from the relay's `aps.alert` (its English title and body;
an away alert is time-sensitive, and a reopening soon after collapses onto the same banner by its
`notif_id`). FCM hands the push's data over as top-level `userInfo` keys, so the notification
delegate (`AlertNotifier.ios.kt`) sees the relay's `door` key on a tap and opens the same Doors
page through `DoorDeepLinks`, which the iOS 26 host (`IosShell`) drains as the Compose shell does.

## Setting up the box

The stick plugged in, on its extension cable:

```bash
ssh frigate 'ls -l /dev/serial/by-id'
```

Then copy `server/zigbee` over and give it the stick's path and a password for the pairing page:

```bash
scp -r server/zigbee andrew@100.99.163.71:surveillance/
```

```bash
ssh frigate 'cd ~/surveillance/zigbee && mkdir -p data && cp -n configuration.yaml data/ && echo "ZIGBEE_STICK=/dev/serial/by-id/<the name ls printed>" > .env && echo "ZIGBEE2MQTT_CONFIG_FRONTEND_AUTH_TOKEN=$(openssl rand -hex 16)" > zigbee.env && chmod 600 zigbee.env && docker compose up -d'
```

`cp -n` seeds Zigbee2MQTT's configuration once; from then on Zigbee2MQTT owns `data/`, including
the network key it generated. Losing `data/` means pairing every sensor again.

Then rebuild the relay so it has the MQTT client (`paho-mqtt`):

```bash
ssh frigate 'cd ~/surveillance/relay && docker compose up -d --build'
```

```bash
ssh frigate 'sudo journalctl -t homesafe-relay --since "5 min ago" | grep doors:'
```

should say `doors: listening to zigbee2mqtt on 127.0.0.1:1883`.

## Pairing a sensor

1. Open `http://100.99.163.71:8080` (or `http://192.168.68.65:8080` at home) and sign in with the
   token in `~/surveillance/zigbee/zigbee.env`.
2. **Permit join (All)**, then pull the sensor's battery tab (or hold its button for 5 seconds
   until the light blinks).
3. When it shows up, rename it to what the app should call it ("Front door"). The relay picks up
   the name at once; renaming it later keeps its history and its setting.
4. Turn **Permit join** off again.

Pair the sensors where they'll live, or at least re-pair any that drop off once mounted: a battery
sensor keeps the router (or the stick) it joined through.

## Range

The stick is the network's only router until something mains-powered joins. If a far door drops
off or its link quality (in the app, and on the pairing page) stays under ~30, pair a Zigbee smart
plug halfway; every plug relays for the sensors around it.

## When something's wrong

- **App says it can't hear the sensors:** the relay isn't connected to the broker (`docker ps`
  should list `mosquitto`), or Zigbee2MQTT isn't running (`docker logs zigbee2mqtt`; the usual cause
  is the stick's path in `.env` after moving it to another port).
- **A sensor shows offline:** Zigbee2MQTT hasn't heard from it in 25 hours. Check its battery, then
  re-pair it.
