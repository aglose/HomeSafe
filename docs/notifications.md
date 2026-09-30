# Notifications

What sounds depends on whether anyone is home, and the Pixel decides that alone (see
[away-mode.md](away-mode.md), "The presence authority"). The relay (`relay/relay.py`,
"notification policy" and "car presence") makes the call; the phone just shows what it's sent.

## Why

2026-09-26 to 29: **802 pushes in three days.** Two thirds were "… in the driveway" on the Front
Yard, a quarter were people Frigate had already named, and none of them could tell a household car
coming or going from Frigate re-detecting it where it stood. Andrew's Tesla "left the driveway"
twenty times between 12:39 and 13:37 on the 28th without moving.

## The rules

| | Someone home | Everyone away |
| --- | --- | --- |
| A household car arrives or leaves | Sounds, once each way | Sounds (loud channel) |
| A person on the Front Yard | Sounds at once, then updates quietly until the yard has been empty 10 min | Sounds, as before |
| A person with a household car coming or going | Folded into the car's notification | Sounds, as before |
| Front Door, Backyard, unnamed cars, animals | Kept for a summary (`notify_log`, route `digest`) | Sounds, as before |

- **Front Yard** is `INSTANT_PERSON_CAMERAS` (default `hikvision_1`). The first person sounds; any
  person on that camera before it has been empty of people for `YARD_QUIET_SECONDS` (600) lands on
  the same notification silently — "Person on the front lawn · 3 sightings". A review still in
  progress keeps the yard busy.
- **Folding.** A person in the same review as a household car that arrived, left or moved, or who
  turns up within `FOLD_SECONDS` (180) of one arriving, is its driver or passenger. They keep the
  yard busy but don't sound. A car coming or going with no name yet holds the person for up to
  `FOLD_NAME_WAIT_SECONDS` (20) while the classifier names it. A person walking out to a parked
  car is *not* folded — nothing yet says the car will leave — so a departure can sound twice.
- **Car presence.** Each household car is home or away, across cameras, from the vehicle memory's
  sightings:
  - *left*: a departure ended, nothing saw the car in the `CAR_LEFT_CONFIRM_SECONDS` (600) after,
    and no car still stands in its remembered spot (checked against Frigate's in-progress
    objects). A car found in its spot turns that departure down for good.
  - *arrived* / *is home*: its first sighting since it left — arriving, or found parked, since the
    tracker often misses the arrival. A classifier name waits for the vision model's check, at most
    `CAR_NAME_WAIT_SECONDS` (180).
  - Wording: "Sarah's Car arrived home · Front Yard · away 3h 10m", "Andrew's Tesla left · Front Yard · home 45m".
  - A car the memory has never seen before starts where the memory has it, silently.
- Quiet hours and "only strangers" still apply per phone, as before.

## Rolling out: `NOTIFY_POLICY`

| Value | Pushes | Logs to `notify_log` |
| --- | --- | --- |
| `legacy` | the old rules | nothing |
| `shadow` | the old rules | what the new rules would do, and what the old ones did (`<review>:legacy`) |
| `v2` | the new rules while someone is home; the old ones while everyone is away | what it did |

Replaying 2026-09-26 to 29 through the new rules (car presence without the live spot check, which
can't be asked about the past): the 806 alerts pushed become 54 sounding Front Yard pushes, 149
quiet updates, 67 folded into a car, and 536 kept for the summary; plus 27 car arrivals and
departures.

To compare, on the box:

```sql
SELECT route, COUNT(*) FROM notify_log WHERE at > strftime('%s','now','-1 day') GROUP BY route;
```
