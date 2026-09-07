# Away mode

The same detection means something different depending on whether anyone is home. A person in
the driveway at 4 PM is a delivery when someone's in; when both phones say their owners are out,
it's the one thing worth a loud alert. Household presence is shared between the two phones, so
the push relay on the Frigate box (`relay/relay.py`) is the source of truth.

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
| `POST /devices` | cookie, or bearer | `{"device_id","token"?, "platform","name","quiet_familiar","build"}` | `{"ok", "device_id", "secret"}` |
| `DELETE /devices/{id}` | cookie, or bearer | — | `{"ok"}` |
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
{"devices":[{"name","platform","away","away_updated","this_device","build","counts","pending_away"}],
 "everyone_away": bool,
 "home": {"lat","lng","radius_m","updated","by"} | null}
```

- `this_device` is true on the entry whose `device_id` (or, for old apps, token) matches the
  `?device=` query or the `PUT` path.
- `everyone_away` = at least one **counting** device (below) **and** all of those away.
  `pending_away` devices are not away.
- `POST /devices` (re-registration on every connect and LAN/Tailscale flip) never touches
  `away`/`away_updated`/pending. `token` belongs to exactly one install: registering it moves it
  off any other row. An app upgrading from token-only identity brings its old row along — the row
  keyed by its token is re-keyed to the new `device_id`, presence intact.
- Old apps that send only `token` get `device_id = token`, as before. `?token=` still works.
- `PUT .../presence` on an unknown id creates the row (so a presence change can't race
  registration); such a row has `platform="unknown"`, `build="unknown"` and no name — so it
  doesn't count — until `POST /devices` fills them in.
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

### Which phones count

Only the household's real phones may decide the house is empty. `POST /devices` carries
`build` — `"release"` or `"debug"` — and `counts_for_away(platform, build)` is the one place that
rule lives:

```python
AWAY_BUILDS = {"release"}
AWAY_DEBUG_PLATFORMS = {"ios"}      # no iOS release channel yet; drop "ios" the day one ships
```

- A debug install — an emulator, the `.debug` app sitting beside the real one, a phone on a dev
  branch — still registers, still receives every push, and still has its own switch. It just
  isn't part of `everyone_away` and can't hold away mode open by claiming to be home.
- iOS is exempt while the only iOS build is a debug IPA: the household iPhone counts on any
  build. (It has no relay row at all until iOS push registration lands — see **Not done**.)
- Rows written before the `build` column existed stay `"unknown"` and don't count. The real
  phones re-register on their next connect and become `"release"` again on their own, so this
  self-heals; a leftover emulator row never does, which is the point.
- Each entry in the presence snapshot carries its `build` and a `counts` boolean, so the Settings
  list can show a debug install greyed out as "· debug, not counted". `GET /devices` reports the
  same as `counts_for_away`.

## Automatic presence

Two signals, one per direction, chosen for how they fail:

- **Home** — reaching Frigate over the LAN (`ConnectionRoute.LOCAL_NETWORK`, which the connection
  repository already probes on every network change; you can't do that from the road), or
  re-entering the home geofence. Immediate. A false "home" merely keeps the ordinary alert rules,
  so this is the safe direction to be wrong in — and the LAN half needs no permission at all.
- **Away** — leaving the geofence. Armed with a **10-minute dwell** (`PresenceAutomationImpl.DEFAULT_DWELL_SECONDS`)
  so the mailbox, the bins and a chat at the kerb aren't departures; any "home" in between
  cancels it. A false "away" would turn every delivery into an alarm, hence the caution.

The manual switch keeps working throughout as the override. Off by default
(`AlertSettings.automaticPresence`, Room schema 9); inert without location "Always" and a home.

**Home** is a 150 m circle (`DEFAULT_RADIUS_METERS`; Android won't fire reliably much under 100 m)
set once from a phone standing in it — Settings → Away mode → "Set home here" — and shared through
the relay, so the second phone only has to switch automation on. Each phone also caches it
(`DeviceIdentityEntity.home*`) so the fence can be re-armed with no relay in reach.

**Ports.** `GeofenceMonitor` (`access: StateFlow<LocationAccess>`, `requestAccess()`,
`currentLocation()`, `watch(home?)`) and `DeviceInfo` (`platform`, `name`, `build`), both with
`createX(platformContext)` actuals; desktop and web are unavailable no-ops.

- **Android** — Play Services `GeofencingClient` (`play-services-location`), one fence, ENTER|EXIT,
  never expires, 60 s responsiveness, no initial trigger. Delivered to `GeofenceBroadcastReceiver`
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
- **Common** — `PresenceAutomationImpl` runs three collectors while the app runs: LAN → home
  (asks the relay first, reports only if away or pending); the fence follows *automation on ∧
  access ALWAYS ∧ home*, starting from the cached home and then following the relay — "the relay
  hasn't answered yet" is never read as "no home", or a background launch would tear the fence
  down; and the home cache follows the relay. `DeviceRegistrar` (common, replaces most of the
  Android `PushRegistrar`) registers on every server change / preference change / token rotation
  and stores the returned secret; Android's `onNewToken` reaches it through `BackgroundGraph`, so
  a rotation with the app closed still lands (bearer auth).
- **Settings** — Away mode gained an "Automatic" switch (turning it on also asks for location),
  one button per missing piece ("Allow location always" / "Allow location in system settings",
  "Set home here" / "Move home here" + "Clear"), and a caption saying what's watching. Device
  rows show `· leaving…` while pending.

### Escalation

Each poll, before the normal alert pass, `poll_forever` asks `away_since()`. While everyone is
away it fetches `GET /api/review` for **both** `severity=alert` and `severity=detection`, and
pushes every item whose `data.objects` contains `"person"` and whose `start_time` is at or after
the moment the last person left (so flipping the switch never replays the afternoon). The push is:

- title `Away: Person in the driveway` (via `sentence()`), body `Front Yard · nobody home`;
- data `away="1"` plus the usual `review_id`, `camera`, `event_id`, `zones`, `start_time`;
- Android `channel_id: "away_alerts"`, `priority: high`; APNs `sound: default`,
  `interruption-level: time-sensitive`.

Items go into the same `sent` table as ordinary alerts, so a person alert while away is sent once,
escalated, and the normal pass then skips it. Non-person alerts while away, and everything while
somebody is home, behave exactly as before.

## App

- `domain/model/HouseholdPresence.kt` — `PresenceDevice`, `HouseholdPresence` (+ `EMPTY`, `thisDevice`).
- `domain/platform/PushTokenProvider` — `isSupported`, `suspend fun token()`: where to push,
  nothing more. Android answers with `FirebaseMessaging.getInstance().token`; iOS, desktop and web
  actuals return null, and registration proceeds without one.
- `data/DeviceIdentityStore` — this install's `device_id` (minted on first use), its relay secret,
  and the cached home. `data/DeviceRegistrar` — see Automatic presence.
- `domain/repository/PresenceRepository` / `data/PresenceRepositoryImpl` — `presence: StateFlow`,
  `refresh()`, `setThisDeviceAway(away, source, dwellSeconds)`, `setHome()`. Polls the relay every
  60 s **only while collected** (`subscriptionCount`), restarts on server change, clears on
  sign-out. Writes name the device by id and bear its secret; with no live connection (a
  background wake) the last signed-in server is tried, Tailscale address first, then LAN.
  `setThisDeviceAway` adopts the `PUT` response, so the switch shows what the relay recorded.
- Use cases: `ObserveHouseholdPresenceUseCase`, `RefreshHouseholdPresenceUseCase`, `SetAwayUseCase`,
  `ObserveLocationAccessUseCase`, `RequestLocationAccessUseCase`, `SetHomeHereUseCase`, `ClearHomeUseCase`.
- `PushRelayApi.registerDevice(url, DeviceRegistration, secret?)` → `DeviceCredentials`;
  `setPresence(url, deviceId, secret?, away, source, dwellSeconds)`; `getPresence(url, deviceId,
  secret?)`; `setHome(url, home?)` — internal `@Serializable` DTOs, `Authorization: Bearer` when a
  secret is known. `DeviceInfo.build` is read off the installed app (`FLAG_DEBUGGABLE` on Android,
  `Platform.isDebugBinary` on iOS); `PresenceDevice.countsForAway` / `pendingAway` carry the
  relay's verdict back (defaulting to true / false, so an older relay behaves as it did).
- `DetectionAlertService` (the in-app 15 s poller) collects presence while polling; when
  `everyoneAway` and the event is `PEOPLE`, it notifies regardless of zone rules with
  `AlertNotification.urgent = true` and title prefix `Away: `. `AlertNotifier.android.kt` posts
  urgent ones on the new `away_alerts` channel (IMPORTANCE_HIGH, alarm sound, `CATEGORY_ALARM`);
  `HomeSafeMessagingService` does the same for pushes carrying `away=1`.
- Settings tab: an "Away mode" section after Alerts — "I'm away" switch for this phone, one line
  per device (`Google Pixel 10 Pro XL · away since 4:12 PM` / `· home`, and
  `· debug, not counted` greyed out for a device the relay doesn't count), a caption explaining
  the escalation. Disabled with a caption when push isn't supported on the platform or the relay
  couldn't be reached. `SettingsViewModel` carries `presence`, `awayBusy`, `awayError`,
  `locationAccess`, `geofenceSupported`, `homeBusy`, `homeError`; presence is refreshed in the
  tab's `LifecycleResumeEffect`.
- Home tab: a slim "Away mode · nobody home · alerts escalated" banner with an "I'm back" button
  (`SetAwayUseCase(false)`) while `everyoneAway`; the status line reads "Away Mode" instead of
  "System Secure".

## Not done

- **iOS push.** The iPhone now has an identity, a presence row, a vote and a geofence, but no
  push token: escalated *pushes* don't reach it. It still gets the in-app
  (`DetectionAlertService`) escalation while the app runs. APNs key → Firebase, and an FCM token
  from the Swift side, are the remaining pieces; the relay already builds an `apns` payload.
- **Dwell and radius are constants** (10 min, 150 m). Per-household tuning would be a relay
  `state` entry like `home`.
- **Relay-side snooze / "I'm back" from the notification** — not built.
- Geofencing and token-rotation ghosts, previously listed here, are done: see Automatic presence
  and Identity.
