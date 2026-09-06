# Away mode

The same detection means something different depending on whether anyone is home. A person in
the driveway at 4 PM is a delivery when someone's in; when both phones say their owners are out,
it's the one thing worth a loud alert. Household presence is shared between the two phones, so
the push relay on the Frigate box (`relay/relay.py`) is the source of truth.

## Relay contract

All routes require the Frigate session cookie, like the existing `/devices` routes.

| Route | Body | Answer |
| --- | --- | --- |
| `PUT /devices/{token}/presence` | `{"away": bool}` | presence snapshot (below) |
| `GET /presence?token={token}` | — | `{"devices":[{"name","platform","away","away_updated","this_device"}], "everyone_away": bool}` |

- `token` is the phone's FCM token — the identity the relay already knows devices by. The
  `PUT` upserts, so a presence change can't race the device's registration; a row created that
  way has `platform="unknown"` and no name until the app's normal `POST /devices` fills them in.
- `away_updated` is epoch seconds (REAL) of the last change, `null` if never set.
- `this_device` is true on the entry whose token matches the `?token=` query (or the `PUT` path),
  so the app knows which row its own switch drives.
- `everyone_away` = at least one device **and** all of them away.
- `POST /devices` (re-registration on every connect and LAN/Tailscale flip) never touches
  `away`/`away_updated`.
- Schema: `devices` gained `away INTEGER NOT NULL DEFAULT 0` and `away_updated REAL`, added by
  guarded `ALTER TABLE`s in `db()` so an existing `relay.db` upgrades on boot.

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
- `domain/platform/PushTokenProvider` — `isSupported`, `suspend fun token()`. Android answers
  with `FirebaseMessaging.getInstance().token` (shared's `androidMain` now depends on
  `firebase-messaging` via the BOM); iOS, desktop and web actuals are unsupported no-ops.
- `domain/repository/PresenceRepository` / `data/PresenceRepositoryImpl` — `presence: StateFlow`,
  `refresh()`, `setThisDeviceAway()`. Polls the relay every 60 s **only while collected**
  (`subscriptionCount`), restarts on server change, clears on sign-out. `setThisDeviceAway` adopts
  the `PUT` response, so the switch shows what the relay recorded, not what was asked.
- Use cases: `ObserveHouseholdPresenceUseCase`, `RefreshHouseholdPresenceUseCase`, `SetAwayUseCase`
  (`isSupported` mirrors the token provider).
- `PushRelayApi.setPresence` / `getPresence` with internal `@Serializable` DTOs.
- `DetectionAlertService` (the in-app 15 s poller) collects presence while polling; when
  `everyoneAway` and the event is `PEOPLE`, it notifies regardless of zone rules with
  `AlertNotification.urgent = true` and title prefix `Away: `. `AlertNotifier.android.kt` posts
  urgent ones on the new `away_alerts` channel (IMPORTANCE_HIGH, alarm sound, `CATEGORY_ALARM`);
  `HomeSafeMessagingService` does the same for pushes carrying `away=1`.
- Settings tab: an "Away mode" section after Alerts — "I'm away" switch for this phone, one line
  per device (`Google Pixel 10 Pro XL · away since 4:12 PM` / `· home`), a caption explaining the
  escalation. Disabled with a caption when push isn't supported on the platform or the relay
  couldn't be reached. `SettingsViewModel` carries `presence`, `awaySupported`, `awayBusy`,
  `awayError`; presence is refreshed in the tab's `LifecycleResumeEffect`.
- Home tab: a slim "Away mode · nobody home · alerts escalated" banner with an "I'm back" button
  (`SetAwayUseCase(false)`) while `everyoneAway`; the status line reads "Away Mode" instead of
  "System Secure".

## Not done

- **Geofencing / automatic presence.** The switch is manual. Location-based flipping (and the
  background-location permission story) is a follow-up.
- **iOS presence.** The iOS app doesn't register an APNs/FCM token with the relay yet, so
  `PushTokenProvider` is unsupported there and the switch is disabled; the iOS phone still gets
  the in-app (`DetectionAlertService`) escalation while the app runs, once the Android phone has
  marked everyone away... which can't happen until iOS can register. Practically: away mode is
  two Android phones, or one phone, until iOS push lands.
- **Token rotation.** FCM rotating a token creates a new device row (home by default) and leaves
  the old one until a push to it fails with UNREGISTERED. Until then `everyone_away` may be
  false because of a ghost row; the Settings list shows it.
- **Relay-side snooze / "I'm back" from the notification** — not built.
