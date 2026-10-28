# Notifications

What sounds depends on whether anyone is home, and the house is empty once both tracked phones —
the Android phone and the iPhone — have left (see [away-mode.md](away-mode.md), "The two tracked
phones"). The relay (`relay/relay.py`,
"notification policy" and "car presence") makes the call; the phone just shows what it's sent.

"Sounds" below means *alerts*: a heads-up banner (and a buzz on the Away channel). No HomeSafe
notification plays a sound, on either phone. Android fixes a channel's sound when it is first
created, so the channels that used to sound (`detections`, `away_alerts`, which rang the phone's
alarm, `weather`, `weather_severe`, `budget`) are deleted and replaced by silent `…_v2` ones
(`SilentChannels.kt`); the relay's APNs pushes carry no `sound`.

## Settings → Notifications

Every app's notifications are switched on one page, Settings → **Notifications** (it used to be
called Alerts). At the top, **Mute all notifications** silences this phone completely
(`NotificationMute`, kept in the platform's preferences so a push can read it with no app
running): the phone re-registers with the relay with no token, so the relay pushes it nothing of
any kind (cameras, doors, Away alerts, budget), the in-app camera poller and the weather check
post nothing, and on Android `HomeSafeMessagingService` drops anything already on its way. Each
app's own choices are kept for when it is turned off. Below it, one card for each app that sends any:

- **Cameras**: this phone's switch, then what to hear about and when (the rules below, quiet hours,
  "only when everyone's away", "only strangers") and the test notification.
- **Doors**: a row to the Doors page, since each door chooses for itself (see below).
- **Weather**: the weather app's switch and its four kinds, the same ones its own settings show
  (`WeatherNoticePreferences`, which also starts or stops the background check).
- **Finance**: the budget's three alerts, the same ones as Budget → Limits and cards → Alerts.
  They are the household's, so they are read from the relay each time the page opens
  (`BudgetAlertPreferences`), and the card is left out where there is no budget, or the account
  may not see it.

All of them share the phone's one notification permission, which the Cameras card asks for. The
main Settings page's row sums up the cameras' choices and adds "weather" while those are on too.

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

## Not the cameras: parking

When a household Tesla is parked at a curb whose signs say it must move (street sweeping, a time
limit), the relay's "parking" section tells its drivers: the evening before a morning deadline,
half an hour before, and again if it is still there when the time comes (see
[parking.md](parking.md)). The push is `parking=1`, `notif_id=parking-<car>`, worded on the phone;
quiet hours hold it back until they end, "only when everyone's away" and "only strangers" don't.

## Not the cameras: door sensors

A door opening is pushed by the relay's "doors" section, not by this policy (see
[doors.md](doors.md)). Each door chooses for itself: **only when away** (the default) pushes as an
away alert, loud and to every phone; **always** also pushes while someone is home, as an ordinary
alert that quiet hours and "only when everyone's away" hold back; **never** doesn't push. The push
is `door=<id>`, `notif_id=door-<id>`, worded on the phone; a door opened again within 2 minutes
updates its notification quietly. On Android it wears the door (the shield when it went out as an
away alert) and a tap opens the Doors page (`homesafe://doors?door=<id>`, see
[doors.md](doors.md#what-a-push-looks-like)).

On Android a door that opened while someone was home has a **Doors** channel (`doors`, heads-up,
silent like the rest) and a group of its own in the shade, so the doors can be turned down without
the cameras; one that opened while everyone was away goes on the Away channel with the cameras'
away alerts.

| Android channel | Id | What goes on it |
| --- | --- | --- |
| Detections | `detections_v2` | The cameras' alerts while someone is home, and "Not a person" confirmations |
| Away alerts | `away_alerts_v2` | Someone seen, or a door opened, while everyone is away (buzzes) |
| Doors | `doors` | A door opening while someone is home |
| Summaries | `summaries` | The relay's summaries (low importance) |
| Weather, Severe weather | `weather_v2`, `weather_severe_v2` | The weather app's (see [weather.md](weather.md)) |
| Budget | `budget_v2` | The month's card spending (see [budget.md](budget.md)) |
| Parking | `parking` | A household car parked on the street that has to move (see [parking.md](parking.md)) |

## Icons

On Android each notification says what it is from the status bar, before the shade is opened:

| Notification | Icon | Accent |
| --- | --- | --- |
| A person, where the notification knows that is what was seen | A figure | The app's orange |
| A car (an unnamed one, a household car arriving or leaving, or one to move off the street) | A car | The app's orange |
| An animal (in-app alerts) | A paw | The app's orange |
| Anything else from a camera | The camera | The app's orange |
| A door that opened | A door, swung open | The app's orange |
| Someone seen, or a door opened, while everyone is away | A shield | Red |
| A summary | A list | The app's orange |
| "Marked not a person" | A tick | The app's orange |
| A budget alert | A card | Amber close to a limit, red past one |
| The weather | One per kind: see [weather.md](weather.md#what-one-looks-like) | The kind's own |

A push says what it is about only as far as the relay already lets on: `car_unnamed`,
`person_unnamed`, and a household car's `notif_id=car-<name>`. Everything else from the relay
wears the camera. The in-app alerts know the detection's label and always wear the right one.

Every icon is drawn once, in `NotificationGlyph` (`shared/.../ui/notification/NotificationGlyphs.kt`),
as paths on a 24-unit grid. The drawables under `shared/src/androidMain/res/drawable/ic_notification_*.xml`
are written from it, and `NotificationGlyphsTest` fails when one has drifted. To change or add
one, edit the glyph and run:

```sh
UPDATE_NOTIFICATION_ICONS=1 ./gradlew :shared:testAndroidHostTest --tests '*NotificationGlyphsTest*' --rerun
```

`scripts/render-ui-previews.sh` (Layoutlib; see docs/ui-previews.md) draws the whole set, large and at status-bar size.

On iOS there is no icon to choose, but each camera's alerts stack in a thread of their own,
someone seen while everyone is away is time-sensitive, and a summary or more of a visit already
announced arrives passively.

## iPhone

The relay pushes to an iPhone straight through Apple's push service (APNs), not through Firebase:
the app registers Apple's own device token (64 hex digits, `AppDelegate.swift` →
`onApnsToken` in `IosApp.kt`), and the relay signs in to APNs with the household's key
(`relay.py`, "APNs"). Every push the relay makes reaches an iPhone this way, the same banner
Android's carry the text of (iOS draws the relay's English `aps.alert`), with the push's data
beside it, so a tap opens the moment, the door or the page it is about.

Until the key is on the box the relay tells an iPhone it can't push to it (`push: false` in the
answer to its registration), and the app keeps its own in-app alerts while it runs, as before.

Apple keeps two services, and a token belongs to one: **sandbox** for a build Xcode installs on
the phone, **production** for TestFlight, ad hoc and App Store builds. The relay tries the one that
took the token last (production the first time), and the other when Apple says `BadDeviceToken`.

### Setup (once)

1. **A key.** At [developer.apple.com](https://developer.apple.com/account/resources/authkeys/list),
   Certificates, Identifiers & Profiles → **Keys** → **+**. Name it "HomeSafe relay", tick
   **Apple Push Notifications service (APNs)**, Configure → Environment **Sandbox & Production**,
   then Continue and Register. **Download** the `.p8` (Apple offers it once) and note the **Key ID**.
2. **On the box**, copy the key to `~/surveillance/relay/data/apns-key.p8` and write
   `~/surveillance/relay/apns.env` (gitignored):
   ```
   APNS_KEY_ID=ABC123DEFG
   ```
   The team (`APNS_TEAM_ID`, default KSK3H9YSXD) and the bundle id (`APNS_TOPIC`, default
   `com.meticulouscreations.homesafe.HomeSafe`) only need setting if they ever change.
3. **Restart the relay** so it reads `apns.env`: `docker compose up -d homesafe-relay`.
4. **Build the app onto the iPhone from Xcode.** The project now has the Push Notifications
   entitlement (`iosApp.entitlements`); with automatic signing Xcode turns the capability on for
   the app's identifier itself. At launch the app gets its token and registers it.

The relay log then says `device registered by …: Apple iPhone (ios …, push=True, …)`, and a test
push from Settings → Notifications reaches the iPhone.

## Weather

The weather app's notifications (rain on the way, the day ahead, government warnings) are a
separate thing from everything above: they don't come from the cameras or the relay. The phone
checks the forecast itself and posts them on channels of their own, **Weather** and **Severe
weather**, each with its kind's icon and colour and a picture of the forecast behind it. See
[weather.md](weather.md#notifications).
