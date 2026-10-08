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
| A person on the Front Yard | Sounds at once, then updates quietly until the yard has been empty 10 min | Sounds on the loud channel, one per visit |
| A person with a household car coming or going | Folded into the car's notification | Sounds on the loud channel, one per visit |
| Front Door, Backyard, unnamed cars, animals | In the next summary | Sounds on the loud channel, one per visit |

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
- **Summaries.** At `DIGEST_HOURS` (default 9, 12, 15, 18, 21 on the household's clock, taken from
  the phones' time zones) whatever went to the summary since the last one is told as one quiet
  notification, replacing the last: "Since 12:00 PM: 5 visits" / "Front Door: Sarah, 1 unknown
  person · Backyard: 1 unknown person, dog · Front Yard: Sarah's Car, 1 unknown car". Alerts on one
  camera within 5 minutes of each other are one visit. Nothing kept, no summary. A slot the relay
  slept through is covered by the next. The push is `summary=1`, `silent=1`, `notif_id=summary`
  with no moment to open; Android posts it on its own low-importance "Summaries" channel.
- **Away, grouped.** Every alert and every person detection goes through the same visits the old
  rules used for ordinary alerts: the first of a camera's run sounds on the loud channel ("Away ·
  Front Door"), more of the same updates it quietly, something new in it sounds again. Replayed
  over 2026-09-26 to 29 as if nobody had been home, 970 pushes become 220 that sound — though most
  of those days' traffic was the household itself, which isn't there when everyone's away.
- Quiet hours and "only strangers" still apply per phone to everything but away pushes.

## Rolling out: `NOTIFY_POLICY`

| Value | Pushes | Logs to `notify_log` |
| --- | --- | --- |
| `legacy` | the old rules | nothing |
| `shadow` | the old rules | what the new rules would do at home, the summaries they'd send, and what the old rules did (`<review>:legacy`) |
| `v2` | the new rules, home and away, and the summaries | what it did |

Replaying 2026-09-26 to 29 through the new rules (car presence without the live spot check, which
can't be asked about the past): the 806 alerts pushed become 54 sounding Front Yard pushes, 149
quiet updates, 67 folded into a car, and 536 kept for the summary; plus 27 car arrivals and
departures.

To compare, on the box:

```sql
SELECT route, COUNT(*) FROM notify_log WHERE at > strftime('%s','now','-1 day') GROUP BY route;
```

## Not the cameras: budget alerts

The relay also tells the phones when the month's card spending crosses a line the household asked
to hear about (see [budget.md](budget.md), "Alerts"). They are no part of the policy above: they
come from `budget_check`, not from Frigate's alerts, they go only to phones last signed in to by
someone who may see the finances, and on Android they have a **Budget** channel of their own
(`budget=1` in the push's data, `notif_id=budget-<month>-<line>`). Quiet hours hold them back;
"only when everyone's away" and "only strangers" don't apply to them.
