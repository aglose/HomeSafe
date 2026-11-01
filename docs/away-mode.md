# Away mode

The same detection means something different depending on whether anyone is home. A person in
the driveway at 4 PM is a delivery when someone's in; when both phones say their owners are out,
it's the one thing worth a loud alert. Away mode follows exactly two phones — the household's
Android phone and its iPhone — and the house is empty once both have left. Presence is shared
between them, so the push relay on the Frigate box (`relay/relay.py`) is the source of truth.

## Relay contract

### Identity

A device is a **`device_id`** — a UUID the app mints once per install and keeps
(`DeviceIdentityEntity`, Room schema 9). Its push token is an attribute, nullable: an iPhone has
none until APNs lands and still gets an identity, a presence row and a vote. This replaced the
token-as-primary-key design, which had two holes: iOS couldn't exist, and a rotated FCM token
made a ghost row that blocked `everyone_away` until a push to the old token failed.

Registration answers with the id and a **device secret**:

| Route | Auth | Body | Answer |
| --- | --- | --- | --- |
| `POST /devices` | cookie, or bearer | `{"device_id","token"?, "platform","name","quiet_familiar","build","hardware_id","emulator"}` | `{"ok", "device_id", "secret"}` |
| `DELETE /devices/{id}` | cookie (any device), or bearer (itself) | — | `{"ok"}` |
| `PUT /devices/{id}/presence` | cookie, or bearer | `{"away": bool, "source": "manual"\|"geofence"\|"lan", "dwell_seconds": n}` | presence snapshot |
| `GET /presence?device={id}` | cookie, or bearer | — | presence snapshot |
| `PUT /home` / `DELETE /home` | cookie | `{"lat","lng","radius_m"}` | presence snapshot |

**Two ways in** (`authenticate`). A signed-in user's Frigate session cookie may do anything. An
install's `Authorization: Bearer <secret>` may act only on its own row — the routes that name a
device. The second door exists for background wakes: a geofence crossing at 3 AM, a token
rotation with the app closed. Neither has a Frigate session (the cookie lives only in the Ktor
client's memory, and the saved credentials are behind biometrics), so without it automatic
presence could not exist. A bearer the relay doesn't know (its database was reset) falls back to
the cookie when there is one, so a stale secret never locks a user out; re-registering hands out
a fresh one. Secrets are `secrets.token_urlsafe(32)`, compared with `compare_digest`, minted once,
returned on every registration.

**The snapshot**:

```json
{"devices":[{"id","name","platform","away","away_updated","this_device","build","counts","pending_away","last_seen"}],
 "everyone_away": bool,
 "home": {"lat","lng","radius_m","updated","by"} | null,
 "not_tracked": "debug" | "emulator" | "taken" | "old_app" | "platform" | "unregistered" | null}
```

`devices` holds the tracked phones alone (below), Android first; `counts` is always true and
only kept for older apps. `not_tracked` says why the install the request named isn't listed.

- `this_device` is true on the entry whose `device_id` (or, for old apps, token) matches the
  `?device=` query or the `PUT` path.
- `id` is the `device_id` — what `DELETE /devices/{id}` takes. `last_seen` is when the relay last
  heard from the install: `POST /devices`, `PUT .../presence`, and `GET /presence?device=` for
  the device it names (the app polls that about once a minute while open), so an install nobody
  uses any more stops moving. Both are absent from relays before 2026-09-22; the app then offers
  no remove button and folds nothing away for staleness.
- `everyone_away` = at least one **tracked** phone (below) **and** all of those away.
  `pending_away` devices are not away.
- `POST /devices` (re-registration on every connect and LAN/Tailscale flip) never touches
  `away`/`away_updated`/pending. `token` belongs to exactly one install: registering it moves it
  off any other row. An app upgrading from token-only identity brings its old row along — the row
  keyed by its token is re-keyed to the new `device_id`, presence intact.
- Old apps that send only `token` get `device_id = token`, as before. `?token=` still works.
- `PUT .../presence` from an install that isn't tracked (or isn't known) changes nothing and
  answers the snapshot, whose `not_tracked` says why.
- **Dwell.** `away: true` with `dwell_seconds > 0` only *arms*: `away_pending_since` is set and
  `promote_pending()` — every poll, so within `POLL_SECONDS` — marks the device away once the
  dwell has run out. A second arm while pending doesn't restart the clock. `away: false` is always
  immediate and clears any pending arm. `dwell_seconds: 0` (the switch) is immediate too. The
  dwell lives here rather than on the phone because iOS can't reliably run a delayed job from a
  background wake, and because "home" from *any* source must be able to cancel it.
- **Home** is household state in the `state` table (`HOME_KEY`), `radius_m` floored at 50.
  Whoever is standing in it sets it; every phone's geofence follows the snapshot.
- Schema: `devices` is rebuilt on first boot after this change — `device_id TEXT PRIMARY KEY`,
  `token TEXT UNIQUE` (nullable), plus `secret`, `away_pending_since`, `away_pending_dwell`;
  existing rows get `device_id = token`. `broadcast()` skips rows with no token.

### The two tracked phones

Away mode follows exactly two phones: one Android phone and one iPhone, each a **release build on
real hardware**. Everything else — a debug build (an emulator, the `.debug` app beside the real
one, an Xcode Debug install), a release build on an emulator or simulator, an app from before
`hardware_id` — still registers and still gets pushes, but isn't tracked: it isn't listed, its
presence writes are ignored, and it can never hold away mode open or empty the house. This
replaced the presence authority (one phone deciding alone, 2026-09-29 to 2026-10-22), which had
itself replaced "every release phone votes" after stale installs kept the house occupied: on
2026-10-22 the relay listed dozens of old Pixel installs, emulators and an iPhone debug install.

- **Identity.** `POST /devices` carries `hardware_id`, which outlives a reinstall: Android's
  `ANDROID_ID` (one per app signing key, so the release app's isn't the debug app's) and, on iOS,
  a UUID the app keeps in the Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), which
  survives deleting the app but not a restore to a new phone. Not a MAC address, which iOS
  randomises. A registration whose `hardware_id` matches other rows of its platform folds them in:
  they're deleted, and the newest hands the new row its presence and its tracked place. A
  reinstall never leaves a ghost.
- **`not_tracked_reason(platform, build, emulator, hardware_id)`** is the one place the rule lives:
  platform `android` or `ios`, `build == "release"`, not an emulator, and a `hardware_id`.
- **Places.** `assign_tracked_phones()` keeps `tracked_android` / `tracked_ios` in `state`, each a
  device_id. A phone keeps its place while it is eligible, unless it has gone quiet for
  `TRACKED_STALE_SECONDS` (a week) and another eligible phone of its platform hasn't; an empty
  place goes to the eligible install heard from most recently. Two phones in use can't take turns:
  the first keeps it, and removing it in Settings (`DELETE /devices/{id}`) hands it over at once.
  A phone that takes a place starts at home. It runs on every snapshot and every poll, so a stale
  phone is replaced without anyone registering.
- **Cleanup.** `forget_untracked()` deletes installs that aren't tracked and haven't been heard
  from in `UNTRACKED_FORGET_SECONDS` (two weeks), on startup and every registration. One still in
  use re-registers the next time it connects. The old `presence_device` state key is cleared on
  startup, and `PRESENCE_DEVICE` / `presence.env` are no longer read.
- `GET /devices` (cookie) still lists every install, with `tracked` and `not_tracked`, for looking
  into from the box.

## Automatic presence

Two signals, one per direction, chosen for how they fail:

- **Home** — reaching Frigate over the LAN (`ConnectionRoute.LOCAL_NETWORK`, which the connection
  repository already probes on every network change; you can't do that from the road), or
  re-entering the home geofence. Immediate. A false "home" merely keeps the ordinary alert rules,
  so this is the safe direction to be wrong in — and the LAN half needs no permission at all.
- **Away** — leaving the geofence. Armed with a **10-minute dwell** (`PresenceAutomationImpl.DEFAULT_DWELL_SECONDS`)
  so the mailbox, the bins and a chat at the kerb aren't departures; any "home" in between
  cancels it. A false "away" would turn every delivery into an alarm, hence the caution.
- **Where the phone is** — the fence only speaks on a crossing, so a phone already out when it
  became the tracked one (which starts it at home) stayed "home" until it came back (2026-10-22,
  the Pixel's first day as the tracked phone). Two things now say which side it's on without a
  crossing: Android's fence reports its starting side whenever it's registered
  (`INITIAL_TRIGGER_ENTER | INITIAL_TRIGGER_EXIT`: every new process, reboot and update), and
  while the app is on screen `PresenceAutomationImpl` takes a fix with each fresh presence, at
  most every 5 minutes (`LOCATION_CHECK_EVERY`), needing only "while in use". In the background
  it takes none (the fence covers that); back on screen, the latest snapshot is checked at once. More than 100 m past the circle
  (`LOCATION_MARGIN_METERS`) arms away with the dwell; inside it brings an away phone home.
  Source `"location"`. A manual "I'm away" at home is undone the next time the app looks.

The manual switch keeps working throughout as the override. **Always on, with no switch**, on a
tracked phone (`DeviceInfo.canBeTracked`: a release build that isn't an emulator); a debug build
or an emulator never watches the fence or reports, and takes down any fence it armed before.
Inert without location "Always" and a home. (Until 2026-10-22 it was an "Automatic" switch, off by
default; Room schema 29 drops `SettingsEntity.automaticPresence`.)

**Home** is a 150 m circle (`DEFAULT_RADIUS_METERS`; Android won't fire reliably much under 100 m)
set once from a phone standing in it — Settings → Away mode → "Set home here" — and shared through
the relay, so the second phone only has to allow location "Always". Each phone also caches it
(`DeviceIdentityEntity.home*`) so the fence can be re-armed with no relay in reach.

**Ports.** `GeofenceMonitor` (`access: StateFlow<LocationAccess>`, `requestAccess()`,
`currentLocation()`, `watch(home?)`) and `DeviceInfo` (`platform`, `name`, `build`, `hardwareId`, `emulator`), both with
`createX(platformContext)` actuals; desktop and web are unavailable no-ops.

- **Android** — Play Services `GeofencingClient` (`play-services-location`), one fence, ENTER|EXIT,
  never expires, 60 s responsiveness, initial trigger on enter and exit. Delivered to `GeofenceBroadcastReceiver`
  (shared `androidMain`, declared in `shared/src/androidMain/AndroidManifest.xml`), which runs with
  no Activity: it builds the process-wide `BackgroundGraph` — `AlertNotifier` and
  `BiometricCredentialStore` have headless fallbacks for a non-Activity context — and calls
  `PresenceAutomation.onGeofenceTransition`, under `goAsync()`'s ten seconds. `BootReceiver`
  re-arms the fence after `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, since Android forgets
  geofences on reboot. Permissions: `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`, then
  `ACCESS_BACKGROUND_LOCATION` (Android 11+ sends the user to its own settings screen for that
  one); `requestAccess()` asks for whichever is next and re-reads state on every resume.
  `watch()` skips an identical fence so re-syncs don't churn.
- **iOS** — CoreLocation region monitoring from Kotlin/Native (`GeofenceMonitor.ios.kt`): one
  `CLCircularRegion`, `notifyOnEntry`/`Exit`, delegate crossings run `onTransition` under a UIKit
  background task. iOS relaunches a terminated app in the background to deliver a crossing, but
  only tells a `CLLocationManager` that already exists with a delegate — which is why the iOS
  graph is built at process start (`IosApp.graph`, `startIosApp()` called from `iOSApp.init`) and
  not when the first screen appears. `watch()` leaves an identical monitored region alone so a
  crossing queued for it isn't lost. "Always" is asked in two steps too (When In Use, then
  Always); `Info.plist` carries both usage strings. No background mode is needed for region
  monitoring.
- **Common** — `PresenceAutomationImpl` runs three collectors while the app runs on a tracked
  phone, and reads the relay's presence only while the app is on screen (`AppVisibility`), so it
  never keeps the minute poll or the fixes going from a pocket: LAN → home (asks the relay first, reports only if away or pending); the fence follows
  *access ALWAYS ∧ home*, starting from the cached home and then following the relay — "the relay
  hasn't answered yet" is never read as "no home", or a background launch would tear the fence
  down; and the home cache follows the relay. `DeviceRegistrar` (common, replaces most of the
  Android `PushRegistrar`) registers on every server change / preference change / token rotation
  and stores the returned secret; Android's `onNewToken` reaches it through `BackgroundGraph`, so
  a rotation with the app closed still lands (bearer auth).
- **Settings** — Away mode says what "Automatic" still needs, with one button per missing piece
  ("Allow location always" / "Allow location in system settings", "Set home here" / "Move home
  here" + "Clear"), and a caption saying what's watching. Device rows show `· leaving…` while
  pending.

### Escalation

Each poll, before the normal alert pass, `poll_forever` asks `away_since()`. While everyone is
away it fetches `GET /api/review` for **both** `severity=alert` and `severity=detection`, and
pushes every item whose `data.objects` contains `"person"` and whose `start_time` is at or after
the moment the last person left (so flipping the switch never replays the afternoon), and, on a
camera with a `property` zone, whose `data.zones` names it (see "On the property"). The push is:

- title `Person in the driveway` (via `sentence()`, with no "Away" in front: the Away channel and
  the body already say it), body `Front Yard · nobody home`;
- data `away="1"` plus the usual `review_id`, `camera`, `event_id`, `zones`, `start_time`;
- Android `priority: high` (the app posts it on its Away channel, `away_alerts_v2`); APNs
  `interruption-level: time-sensitive`, and no `sound` (HomeSafe never makes one);
- never folded into a visit: no `notif_id`, never `silent` (ordinary alerts are, see `Visits` in
  relay.py), so each one sounds on its own notification.

Items go into the same `sent` table as ordinary alerts, so a person alert while away is sent once,
escalated, and the normal pass then skips it. Non-person alerts while away, and everything while
somebody is home, behave exactly as before.

### On the property

A camera that looks over public ground has a zone named `property`, wanting people only
(`objects: [person]`), drawn up to where the household's ground ends. There, "any person" means any
person inside it; a camera with no such zone still hears about everyone. The relay reads the zone
off the review's `data.zones`, which only names it once a person has set foot there because no
other label counts as inside it, and looks again each poll, so someone who steps off the sidewalk
is told as they do. The in-app poller applies the same rule through `inZones`. Add the zone to the
camera's `review.alerts.required_zones` too, so someone there is an alert while somebody's home as
well.

Why: from 2026-10-25 to 11-01 the Front Yard pushed 301 times, 277 of them from this bypass, and
226 had nobody but passers-by on the sidewalk. The Front Yard's outline is the sidewalk's house-side
edge, read off the feet of 701 people walking along it; 2 of them put two or more points over it.
Of the 75 pushes left, a look through them found most plainly people in the yard, and about a dozen
close to the line at the upper left, behind a parked car, that a still frame couldn't settle. A zone
is judged at the bottom centre of the box, so someone whose feet are hidden is placed by what shows.

The local vision model doesn't decide this. Shown 40 of those frames, it got all 20 sidewalk walkers
right but called 8 of 20 people on the property "sidewalk", and asked only whether a real person was
there it rejected 14 of the 75, some of them plainly people. It would hide the visitor it was meant
to catch.

### Quiet hours and "only when everyone's away"

Two per-phone choices under Settings → Notifications (the Cameras card) hold back *ordinary* alerts and never Away ones:
quiet hours (a daily window, e.g. 10 PM to 7 AM, that may wrap midnight) and "only when
everyone's away" (ordinary alerts never; the phone hears only the escalated pushes above). The
app sends them on every registration as `quiet_start` / `quiet_end` (minutes after local
midnight, absent while off), `only_away`, and the clock to read them by, `tz` (IANA) with
`utc_offset` (minutes) as a fallback. `broadcast()` skips a phone that is `silenced()` for an
ordinary alert; `away=True` pushes and the `/test` push go to every phone. The in-app poller
applies the same rule via `AlertSettings.ordinaryAlertsSilenced`.

## App

- `domain/model/HouseholdPresence.kt` — `PresenceDevice`, `HouseholdPresence` (+ `EMPTY`, `thisDevice`,
  `thisDeviceNotTracked: NotTrackedReason?`).
- `domain/platform/PushTokenProvider` — `isSupported`, `suspend fun token()`: where to push,
  nothing more. Android answers with `FirebaseMessaging.getInstance().token`; iOS, desktop and web
  actuals return null, and registration proceeds without one.
- `data/DeviceIdentityStore` — this install's `device_id` (minted on first use), its relay secret,
  and the cached home. `data/DeviceRegistrar` — see Automatic presence.
- `domain/repository/PresenceRepository` / `data/PresenceRepositoryImpl` — `presence: StateFlow`,
  `refresh()`, `setThisDeviceAway(away, source, dwellSeconds)`, `setHome()`. Polls the relay every
  60 s **only while collected** (`subscriptionCount`), restarts on server change, clears on
  sign-out. `PresenceAutomationImpl` collects it only while the app is on screen. Writes name the device by id and bear its secret; with no live connection (a
  background wake) the last signed-in server is tried, Tailscale address first, then LAN.
  `setThisDeviceAway` adopts the `PUT` response, so the switch shows what the relay recorded.
- Use cases: `ObserveHouseholdPresenceUseCase`, `RefreshHouseholdPresenceUseCase`, `SetAwayUseCase`,
  `ObserveLocationAccessUseCase`, `RequestLocationAccessUseCase`, `SetHomeHereUseCase`, `ClearHomeUseCase`.
- `PushRelayApi.registerDevice(url, DeviceRegistration, secret?)` → `DeviceCredentials`;
  `setPresence(url, deviceId, secret?, away, source, dwellSeconds)`; `getPresence(url, deviceId,
  secret?)`; `setHome(url, home?)`; `removeDevice(url, deviceId)` (cookie only) — internal
  `@Serializable` DTOs, `Authorization: Bearer` when a secret is known. `DeviceInfo.build` is read off the installed app (`FLAG_DEBUGGABLE` on Android,
  `Platform.isDebugBinary` on iOS, so the iPhone must run a Release build to be tracked);
  `DeviceInfo.hardwareId` is `ANDROID_ID` or the Keychain UUID, `DeviceInfo.emulator` the stock
  emulator fingerprints or the simulator's `SIMULATOR_UDID`.
- `DetectionAlertService` (the in-app 15 s poller; paused while the relay has this phone's push
  token, since it hears the relay's escalated pushes instead) collects presence while polling; when
  `everyoneAway` and the event is `PEOPLE`, it notifies regardless of zone rules with
  `AlertNotification.urgent = true` and the title as it would read anyway. `AlertNotifier.android.kt` posts
  urgent ones on the `away_alerts_v2` channel (IMPORTANCE_HIGH, vibration, no sound, `CATEGORY_ALARM`;
  the first `away_alerts` rang the phone's alarm and is deleted, since a channel's sound can't change);
  `HomeSafeMessagingService` does the same for pushes carrying `away=1`.
- Settings tab: an "Away mode" row after Notifications, saying in a line where this phone stands
  (`awaySummary`), which opens the Away mode page (`AwaySettingsScreen`, route `AwayRoute`) —
  "I'm away" switch for this phone (locked, with the reason, on an install that isn't tracked),
  then the two tracked phones, each with its status (`away since 4:12 PM` / `home` / `leaving…`)
  and when it was last seen, and a line for whichever platform has no tracked phone yet. Every
  row but this phone's has a remove button (with a confirmation) that calls `DELETE /devices/{id}`
  on the session cookie — how a replaced phone hands its place to the new one. Then "Automatic"
  and what it still needs, and a caption explaining the escalation. `SettingsViewModel` carries
  `presence`, `awayBusy`, `awayError`, `locationAccess`, `geofenceSupported`, `homeBusy`,
  `homeError`; presence is refreshed in the tab's and the page's `LifecycleResumeEffect`.
- Home tab: a slim "Away mode · nobody home · alerts escalated" banner with an "I'm back" button
  (`SetAwayUseCase(false)`) while `everyoneAway`; the status line reads "Away Mode" instead of
  "System Secure".

## Not done

- **iOS push** is done: the relay pushes to the iPhone straight through APNs once its key is on
  the box (see [notifications.md](notifications.md#iphone)). Until then the iPhone keeps the
  in-app (`DetectionAlertService`) escalation while the app runs.
- **Dwell and radius are constants** (10 min, 150 m). Per-household tuning would be a relay
  `state` entry like `home`.
- **Relay-side snooze / "I'm back" from the notification** — not built.
- Geofencing and token-rotation ghosts, previously listed here, are done: see Automatic presence
  and Identity.
