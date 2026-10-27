# Parking: move the car before the street sweeper comes

The Teslas usually sleep in the driveway, but now and then one is left at the curb. Each side of
each street around the house has its own hours when a car can't stay: street sweeping one morning
a week or a month, two-hour parking on weekdays. The relay knows from Tesla where each car is
parked, which street and which side, and tells the car's drivers before it has to move
(`relay/relay.py`, "parking").

## How it tells the street and the side

Tesla's location (see [tesla.md](tesla.md)) puts the car to within a few metres, and says which
way the car faces. The relay matches that against each street's centreline:

- **The street**: the nearest centreline the fix lands on, within half the street's width (12 m
  curb to curb unless the file says otherwise) plus 4 m for the fix to stray.
- **The side**: traffic keeps right, and a car at the curb faces the way its side's traffic goes.
  So on a two-way street the heading settles the side even when the fix lands in the middle of
  the road. On a one-way street, or without a heading, the fix decides, if it is at least 1.5 m
  off the middle. When neither can tell, the rules of every side count, so the relay would
  rather say "move" for nothing than miss a ticket.
- **Not at the curb**: a car facing across the street (more than about 45° off it) has nosed into
  a driveway, or is parked along the cross street. A car in gear or moving isn't parked.

When a car parked is only known to fall between two of Tesla's answers (car presence asks every
half hour, see [tesla.md](tesla.md)), so a time limit is counted from the earlier one: never from
later than the car really arrived.

## When it tells you

One notification per car, each one replacing the last. On Android it goes on the **Parking**
channel, wearing the car; on iOS it is the relay's banner.

| When | Says |
| --- | --- |
| A deadline before noon: 8 PM the evening before | "Move Andrew's Tesla by Thu 8:00 AM" · "North side of Central Ave · Street sweeping" |
| Any other deadline: once it is a day away | the same |
| `PARKING_REMIND_MINUTES` (30) before | "Move Andrew's Tesla by 8:00 AM" |
| The deadline came and the car is still there | "Move Andrew's Tesla now" |
| The car moved after any of these | "Andrew's Tesla has moved", quietly |

- Before telling anyone to go out to the car, the relay asks Tesla whether it is still there (one
  call, unless it asked in the last 5 minutes). A car asleep is still there.
- A phone in its quiet hours hears the latest once they are over, if the car is still there.
  "Only when everyone's away" doesn't hold these back: that choice is about the cameras.
- `drivers` in the file names whose phones hear about each car, by the Frigate user signed in on
  the phone. Without it, or with none of their phones registered, every phone hears.
- Holidays aren't known: on a holiday with no sweeping, the relay still says to move.
- A tap opens a map of the spot over whatever the app is showing (`ParkingMapOverlay` in
  `:app`), on the map picked in Settings ([maps.md](maps.md)), with the deadline, what the sign
  says, and directions. The push carries the spot as `parking_lat`, `parking_lng` (five places,
  about a metre) and `parking_heading`, and `GET /parking` gives each car's `lat`, `lng` and
  `heading`. A push from before these were sent opens the app as before, with no map.

## The signs: `parking.json`

The signs are written down on the box in `~/surveillance/relay/data/parking.json` (`/data/parking.json`
in the container, or `PARKING_FILE`). It is not in the repository: the repository is public, and
the streets give the house away. The relay reads it again whenever it changes, with no restart.

```json
{
  "tz": "America/Los_Angeles",
  "streets": [
    {"street": "Elm St", "width_m": 16, "within_m": 150, "sides": {
      "north": [{"kind": "no_parking", "days": ["thu"], "weeks": [1, 3], "start": "08:00", "end": "11:00", "note": "Street sweeping"}],
      "south": [{"kind": "no_parking", "days": ["fri"], "weeks": [2, 4], "start": "10:00", "end": "13:00", "note": "Street sweeping"}]
    }},
    {"street": "Oak St", "only": {"side": "north", "of": "Elm St"}, "sides": {
      "home": [{"kind": "no_parking", "days": ["fri"], "start": "10:00", "end": "13:00", "note": "Street sweeping"}],
      "across": [{"kind": "limit", "minutes": 120, "days": ["mon", "tue", "wed", "thu", "fri"], "start": "08:00", "end": "18:00", "permit": "B"}]
    }}
  ],
  "permits": [],
  "drivers": {"andrews_tesla": ["andrew"], "sarahs_car": ["sarah"]}
}
```

- **`street`** is looked up by name in OpenStreetMap within 600 m of home (Settings → Home), once
  a week; "Ave", "St" and the like are spelt out for the lookup. A street OpenStreetMap gets wrong
  can be drawn instead, as `"line": [[lat, lng], [lat, lng], ...]` down its middle.
- OpenStreetMap's public server is slow and often overloaded: on 2026-10-27 it took 34–42 s a
  street from the box, then answered 502 for minutes. What it found is kept through failed
  lookups, but a street that has never been found has no line until it answers (the log says
  `parking: no line for …`, and the relay asks again each hour). The household's own file has
  each street's `line` written in, copied once from what OpenStreetMap had, so it never asks.
- **`only`** keeps the part of the street on one side of another: "Oak St north of Elm St". A
  street with no signs on one stretch simply has no entry for it.
- **`within_m`** keeps the part of the street within that many metres of the house: the blocks
  whose signs were read. Without it the rules hold for the whole street within 600 m, and the
  next block's signs may say something else.
- **`sides`**: `north`, `east`, `south`, `west` (a diagonal street answers to both of the words
  for each side), `home` (the side of the street the house is on) and `across`, or `both`.
- **A rule** holds on its `days` (every day when left out), on the `weeks` of those days in the
  month (1 to 5; -1 is the last), in its `months` (1 to 12), from `start` to `end` (the whole day
  when left out; an end before the start runs past midnight). `no_parking` (sweeping, tow-away)
  means gone by the start; `limit` means gone `minutes` after parking or after the window opened,
  whichever is later. A `permit` the household holds (listed in `permits`) lifts its rule.
  `note` is what the notification calls it; without one it says "No parking 8:00 AM–11:00 AM" or
  "2-hour parking".
- **`width_m`**: curb to curb, where a street is wider or narrower than 12 m.

## Checking it

```bash
docker exec homesafe-relay python relay.py parking-check
```

This prints the streets as the relay found them (how many points each, which side the house is
on) and, after asking Tesla once per car, where each car is by them: street, side, how the side
was told (`heading` or `position`) and the rules that apply. It pushes nothing. `GET /parking`
answers the same for a signed-in phone, without asking Tesla.

The log says `parking: parked on the north side of Central Ave since …` when a car is found at a
curb, and `parking: Move … -> 1 of 1 phones` for each push.

## Cost

Nothing more, as a rule: the relay reads the answers car presence already asks for every half
hour. It asks for itself only when nobody has asked for 35 minutes, and before a notification that
sends someone out to the car: a few calls a week.
