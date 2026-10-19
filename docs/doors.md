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
| App | Pills at the top of Home (each open or offline door, and a count of the closed ones) opening the Doors page, also at Settings › Doors: the floor plan with each door on it, each sensor's battery and signal, its push setting, and the last 7 days |

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

## The floor plan

The Doors page draws the house's floor plan with each door on it: one floor at a time, to scale,
the rooms named, and a dot per door at its spot (orange while open, ringed red while offline). A
dot opens that door's card.

The plan is a file on the box only, `relay/data/floorplan.json` (the relay reads it as
`FLOORPLAN_FILE`, default next to `relay.db`). It isn't in the repository on purpose: the
repository is public, and a plan with the door sensors on it says too much about the house. The
previews and tests use a made-up house instead. Without the file the page is the list alone.

```json
{"floors": [
  {"id": "first", "name": "First floor",
   "outline": [[0, 0], [13.5, 0], [13.5, 8], ...],
   "rooms": [{"name": "Kitchen", "x": 5.5, "y": 18.5}],
   "stairs": [[14.5, 24]],
   "walls": [[0, 8, 13.5, 8], [13.5, 8, 13.5, 20]],
   "doors": [{"name": "Back door", "from": [2.5, 0], "to": [5.5, 0], "swing": 1, "sensor": "0x…"}],
   "stairs": [{"x": 14.5, "y": 22, "direction": "up", "facing": "n", "flight": "upstairs"}]}
 ],
 "street": "Central Ave"}
```

- Every floor is drawn in **one frame**, the street side (`street`) at the bottom, each floor
  where it sits over the one below: the app scales to all of them together, so switching floors
  keeps the house where it is.
- Feet, `y` downward, from any origin; each floor's `outline` is its outside walls as one closed
  polygon, and `walls` its inside walls, each `[x1, y1, x2, y2]`. `rooms`, `stairs`, `walls` and
  `doors` are optional.
- Stairs say which way they go from that floor (`direction` up or down) and which way they run
  (`facing` n, e, s or w: where the arrow points). The two ends of one flight share a `flight` id
  on two floors, going up on one and down on the other, in the same spot. In the app, tapping
  stairs that lead somewhere opens that floor. Plans from before this listed stairs as bare
  `[x, y]` points, which still load.
- A door is a doorway along a wall, hinged at `from`, as wide as `from`→`to`, swinging to the left
  of that line (`swing` 1) or the right (-1). One with a `sensor` (the sensor's Zigbee address)
  puts that sensor's dot in the doorway's middle whenever the plan is saved; a link to a sensor
  not paired yet waits in the file until it is.
- The floors are shown in the file's order, the first first.
- The current file's outside walls were traced from a floor plan sketch, wall by wall from the
  lengths it labels; each floor's area matches the sketch's. The sketch has no inside walls or
  doors: the inside walls in the file are drawn from where the sketch puts each room's name and
  where the outside walls step in and out, and corrected by hand.

**Placing the doors:** the easiest way is the editor below: put a door in the wall where each
sensor is and pick the sensor for it. From the app instead: Doors page › Place doors, pick a door,
tap where it is (a tap near a doorway lands in its middle); the next door not on the plan yet is
picked for the next tap. The spot is kept with the door in `relay.db`
(`PUT /doors/{id}/spot`, `DELETE` to take it off), so every phone sees it and a sensor renamed
on the pairing page keeps its spot.

**Changing the plan:** open the editor the relay serves, `http://192.168.68.65:8787/floorplan/edit`
at home or `http://100.99.163.71:8787/floorplan/edit` over Tailscale, signed in to Frigate (port
8971) in the same browser, like `/status`.

- **Move** drags walls, corners, room names and stairs. A wall's end snaps to any corner or wall
  end it's dropped near, else to the half-foot grid; dragging a joint moves every wall that meets
  there, and dragging a whole wall stretches the walls attached to it (never the outside ones).
- **Add wall** draws by dragging; **Add room name** and **Add stairs** place with a click.
- **Add door** puts a 3 ft doorway in the wall nearest the click. Drag it to slide it along the
  walls, drag an end to make it wider; the side panel names it, flips its swing or its hinge, and
  links the door sensor on it.
- Double-click an outside wall to add a corner there, a corner to remove it, a room name to
  rename it. Arrow keys nudge the selection (Shift for a tenth of a foot); Delete deletes it;
  Ctrl/Cmd+Z undoes. The side panel takes exact numbers.
- **Stairs**: the side panel sets which way they go, turns them, and links them to stairs on
  another floor (or adds new ones there in the same spot, going the other way). Double-click
  linked stairs to jump to their other end.
- **Move floor** drags a whole floor, to line it up over the one below; **Show other floors**
  draws the others dashed behind it. The street name, set at the top of the panel, sits at the
  bottom of every floor.
- The doors placed in the app show as dots for reference.
- **Save** writes the plan (`PUT /floorplan`, checked and rounded) and keeps the one it replaced
  in `relay/data/floorplan-history/` (the newest 30). Pull to refresh on the Doors page to see it:
  the app reads the plan once per server and again on a refresh. No restart needed.

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
