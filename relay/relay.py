"""
HomeSafe push relay.

Frigate has no push service for phones, so this small service sits next to it on the same box:
it polls Frigate's review API for new *alerts* (the ones already gated by zone in Frigate's own
config), turns each into a sentence like "Sarah's Tesla in the driveway", and sends it through
Firebase Cloud Messaging to every phone that registered. Phones register by POSTing their FCM
token; the request is authenticated by forwarding the caller's Frigate session cookie to
Frigate's own authenticated port, so the relay holds no secrets of its own beyond the FCM key.
"""

import json
import logging
import os
import secrets
import sqlite3
import threading
import time
from typing import Any

import requests
from fastapi import FastAPI, HTTPException, Request
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account
from pydantic import BaseModel

FRIGATE = os.environ.get("FRIGATE_INTERNAL", "http://127.0.0.1:5000").rstrip("/")
FRIGATE_AUTH = os.environ.get("FRIGATE_AUTH", "http://127.0.0.1:8971").rstrip("/")
PROJECT = os.environ["FCM_PROJECT"]
KEY_FILE = os.environ.get("FCM_KEY", "/secrets/fcm.json")
DB_PATH = os.environ.get("RELAY_DB", "/data/relay.db")
POLL_SECONDS = float(os.environ.get("POLL_SECONDS", "5"))
CONFIG_REFRESH_SECONDS = 300

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("relay")

# Mirrors the app's naming (CameraDisplayName.kt / DetectionNames.kt) so a push reads exactly
# like the same event in the Moments feed.
CAMERA_NAMES = {"amcrest_1": "Front Door", "hikvision_1": "Front Yard", "hikvision_2": "Backyard"}
SUB_LABEL_NAMES = {"sarahs_tesla": "Sarah's Tesla", "ron_judys_mercedes": "Ron and Judy's Mercedes"}
ENCLOSED = ["driveway", "garage", "carport", "yard", "porch", "garden", "pool", "patio", "alley", "hallway", "kitchen", "room"]


def humanize(key: str) -> str:
    return " ".join(w[:1].upper() + w[1:] for w in key.replace("-", "_").split("_") if w)


def camera_name(key: str) -> str:
    return CAMERA_NAMES.get(key, humanize(key))


def subject_for(objects: list[str], sub_labels: list[str]) -> str:
    if sub_labels:
        key = sub_labels[0]
        return SUB_LABEL_NAMES.get(key.lower(), humanize(key))
    nouns = [o.capitalize() for o in objects] or ["Something"]
    return nouns[0] if len(nouns) == 1 else ", ".join(nouns[:-1]) + " and " + nouns[-1].lower()


def zone_phrase(zone: str) -> str:
    name = " ".join(w for w in zone.replace("-", "_").split("_") if w).lower()
    prep = "in" if any(e in name for e in ENCLOSED) else "on"
    return f"{prep} the {name}"


def sentence(item: dict[str, Any], required_zones: list[str]) -> tuple[str, str]:
    data = item.get("data") or {}
    objects = data.get("objects") or []
    sub_labels = data.get("sub_labels") or []
    zones = data.get("zones") or []
    # The zone that made this an alert wins; otherwise the last one the object reached.
    zone = next((z for z in required_zones if z in zones), zones[-1] if zones else None)
    subject = subject_for(objects, sub_labels)
    body = f"{subject} {zone_phrase(zone)}" if zone else f"{subject} detected"
    return camera_name(item.get("camera", "")), body


# ---------------------------------------------------------------- storage

def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    # The current shape, for a fresh database. A device is known by `device_id` — a UUID the app
    # makes once per install — and `token` is merely where to push (NULL for a phone that has no
    # push, i.e. iOS until APNs lands). `secret` lets that install change its own presence from
    # a background wake that has no Frigate session (see `authenticate`).
    conn.execute(
        "CREATE TABLE IF NOT EXISTS devices ("
        " device_id TEXT PRIMARY KEY, token TEXT UNIQUE, platform TEXT, name TEXT, created REAL, last_seen REAL,"
        " away INTEGER NOT NULL DEFAULT 0, away_updated REAL, quiet_familiar INTEGER NOT NULL DEFAULT 0,"
        " build TEXT NOT NULL DEFAULT 'unknown', secret TEXT, away_pending_since REAL, away_pending_dwell REAL)"
    )
    conn.execute("CREATE TABLE IF NOT EXISTS sent (review_id TEXT PRIMARY KEY, sent_at REAL, body TEXT)")
    conn.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT)")
    columns = {row[1] for row in conn.execute("PRAGMA table_info(devices)")}
    if "device_id" not in columns:
        # A relay.db from before devices had an identity of their own: the token *was* the key.
        # Bring the old table up to the last pre-identity shape (these are the guarded ALTERs
        # earlier versions ran on boot), then rebuild it keyed by device_id = token, so every
        # existing row keeps its presence and its history, and the phones re-register into it.
        if "away" not in columns:
            conn.execute("ALTER TABLE devices ADD COLUMN away INTEGER NOT NULL DEFAULT 0")
        if "away_updated" not in columns:
            conn.execute("ALTER TABLE devices ADD COLUMN away_updated REAL")
        if "quiet_familiar" not in columns:
            conn.execute("ALTER TABLE devices ADD COLUMN quiet_familiar INTEGER NOT NULL DEFAULT 0")
        if "build" not in columns:
            conn.execute("ALTER TABLE devices ADD COLUMN build TEXT NOT NULL DEFAULT 'unknown'")
        conn.execute(
            "CREATE TABLE devices_v2 ("
            " device_id TEXT PRIMARY KEY, token TEXT UNIQUE, platform TEXT, name TEXT, created REAL, last_seen REAL,"
            " away INTEGER NOT NULL DEFAULT 0, away_updated REAL, quiet_familiar INTEGER NOT NULL DEFAULT 0,"
            " build TEXT NOT NULL DEFAULT 'unknown', secret TEXT, away_pending_since REAL, away_pending_dwell REAL)"
        )
        conn.execute(
            "INSERT INTO devices_v2 (device_id, token, platform, name, created, last_seen, away, away_updated, quiet_familiar, build)"
            " SELECT token, token, platform, name, created, last_seen, away, away_updated, quiet_familiar, build FROM devices"
        )
        conn.execute("DROP TABLE devices")
        conn.execute("ALTER TABLE devices_v2 RENAME TO devices")
        log.info("migrated devices to device_id identity")
    conn.commit()
    return conn


DB_LOCK = threading.Lock()
CONN = None


def with_db(fn):
    with DB_LOCK:
        return fn(CONN)


def state_get(key: str) -> Any | None:
    row = with_db(lambda c: c.execute("SELECT value FROM state WHERE key=?", (key,)).fetchone())
    return json.loads(row[0]) if row else None


def state_set(key: str, value: Any | None) -> None:
    if value is None:
        with_db(lambda c: (c.execute("DELETE FROM state WHERE key=?", (key,)), c.commit()))
    else:
        with_db(lambda c: (c.execute("INSERT OR REPLACE INTO state VALUES (?,?)", (key, json.dumps(value))), c.commit()))


# ---------------------------------------------------------------- FCM

SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]
# Must match AlertNotifier.android.kt / HomeSafeMessagingService.kt in the app.
AWAY_CHANNEL_ID = "away_alerts"
_session: AuthorizedSession | None = None


def fcm_session() -> AuthorizedSession:
    global _session
    if _session is None:
        creds = service_account.Credentials.from_service_account_file(KEY_FILE, scopes=SCOPES)
        _session = AuthorizedSession(creds)
    return _session


def send_push(token: str, title: str, body: str, data: dict[str, str], away: bool = False) -> tuple[bool, str]:
    """One FCM message. `away` escalates it: the app's loud "away_alerts" channel on Android, time-sensitive on iOS."""
    aps: dict[str, Any] = {"sound": "default", "thread-id": data.get("camera", "")}
    if away:
        aps["interruption-level"] = "time-sensitive"
    message = {
        "message": {
            "token": token,
            "notification": {"title": title, "body": body},
            "data": data,
            "android": {
                "priority": "high",
                "notification": {"channel_id": AWAY_CHANNEL_ID if away else "detections", "tag": data.get("review_id", "")},
            },
            "apns": {"headers": {"apns-priority": "10"}, "payload": {"aps": aps}},
        }
    }
    r = fcm_session().post(f"https://fcm.googleapis.com/v1/projects/{PROJECT}/messages:send", json=message, timeout=15)
    if r.ok:
        return True, ""
    try:
        err = r.json().get("error", {})
        code = err.get("status", "") + " " + " ".join(d.get("errorCode", "") for d in err.get("details", []) if isinstance(d, dict))
    except Exception:
        code = r.text[:200]
    return False, f"{r.status_code} {code}".strip()


def broadcast(title: str, body: str, data: dict[str, str], away: bool = False, familiar: bool = False) -> dict[str, int]:
    """Pushes to every phone — except, for a [familiar] person, the phones that asked for strangers only."""
    rows = with_db(lambda c: c.execute("SELECT token, quiet_familiar FROM devices WHERE token IS NOT NULL").fetchall())
    tokens = [token for token, quiet in rows if not (familiar and quiet)]
    ok = dropped = failed = 0
    skipped = len(rows) - len(tokens)
    for token in tokens:
        sent, err = send_push(token, title, body, data, away=away)
        if sent:
            ok += 1
        elif "UNREGISTERED" in err or "404" in err:
            # The install behind this token is gone (uninstalled, or its token rotated and the new
            # one has since re-registered its device_id); its presence row goes with it.
            with_db(lambda c: (c.execute("DELETE FROM devices WHERE token=?", (token,)), c.commit()))
            dropped += 1
            log.info("dropped stale device token (%s)", err)
        else:
            failed += 1
            log.warning("push failed: %s", err)
    return {"sent": ok, "dropped": dropped, "failed": failed, "skipped_familiar": skipped}


# ---------------------------------------------------------------- Frigate

_required_zones: dict[str, list[str]] = {}
_config_loaded_at = 0.0


def required_zones() -> dict[str, list[str]]:
    global _required_zones, _config_loaded_at
    if time.time() - _config_loaded_at > CONFIG_REFRESH_SECONDS:
        try:
            cfg = requests.get(f"{FRIGATE}/api/config", timeout=10).json()
            _required_zones = {name: (cam.get("review", {}).get("alerts", {}).get("required_zones") or []) for name, cam in cfg.get("cameras", {}).items()}
            _config_loaded_at = time.time()
        except Exception as e:  # keep the last known map
            log.warning("config refresh failed: %s", e)
            _config_loaded_at = time.time() - CONFIG_REFRESH_SECONDS + 30
    return _required_zones


def recent_review(severity: str) -> list[dict[str, Any]]:
    r = requests.get(f"{FRIGATE}/api/review", params={"severity": severity, "limit": 20}, timeout=10)
    r.raise_for_status()
    return r.json()


def recent_alerts() -> list[dict[str, Any]]:
    return recent_review("alert")


# ---------------------------------------------------------------- away mode

# Only the household's real phones decide whether the house is empty. A debug build — an
# emulator, a test install next to the release app, a phone left on a dev branch — registers for
# push like any other device, but it must never hold away mode open (or, worse, be the single
# "away" device that opens it). Anything that didn't say which build it is stays out too.
#
# The exception is iOS: there is no iOS release channel yet (no App Store / TestFlight build), so
# the household's iPhone is a debug IPA and would otherwise never count. Drop "ios" from this set
# the day an iOS release ships.
AWAY_BUILDS = {"release"}
AWAY_DEBUG_PLATFORMS = {"ios"}

# The household's home, as `{"lat", "lng", "radius_m", "updated", "by"}`, or absent. Set once from
# whichever phone is standing in it; both phones draw their geofence around it.
HOME_KEY = "home"


def counts_for_away(platform: str, build: str) -> bool:
    """Whether this device's away switch is part of `everyone_away`."""
    return (build or "").lower() in AWAY_BUILDS or (platform or "").lower() in AWAY_DEBUG_PLATFORMS


def presence_snapshot(this_device: str | None = None) -> dict[str, Any]:
    """
    Who says they're home. Every registered device is listed (so a debug install can see itself),
    but `everyone_away` is decided by the counting ones alone: at least one, and all of them away.
    A device that has *left* but is still inside its dwell (see `promote_pending`) shows as
    `pending_away` and is not away yet. `this_device` matches a device_id or, for old apps, a token.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, token, name, platform, away, away_updated, build, away_pending_since FROM devices ORDER BY created"
    ).fetchall())
    devices = [
        {
            "name": n,
            "platform": p,
            "away": bool(a),
            "away_updated": u,
            "this_device": this_device is not None and this_device in (d, t),
            "build": b,
            "counts": counts_for_away(p, b),
            "pending_away": ps is not None,
        }
        for d, t, n, p, a, u, b, ps in rows
    ]
    counting = [d for d in devices if d["counts"]]
    return {
        "devices": devices,
        "everyone_away": bool(counting) and all(d["away"] for d in counting),
        "home": state_get(HOME_KEY),
    }


def away_since() -> float | None:
    """When the last person left, or None while somebody is home (or no counting phone has registered)."""
    rows = with_db(lambda c: c.execute("SELECT platform, away, away_updated, build FROM devices").fetchall())
    counting = [(a, u) for p, a, u, b in rows if counts_for_away(p, b)]
    if not counting or not all(a for a, _ in counting):
        return None
    return float(max((u or 0.0) for _, u in counting))


def promote_pending() -> None:
    """
    A geofence exit doesn't mean "away" on its own — a walk to the mailbox crosses it too — so a
    phone that left asks to be marked away *after* a dwell, and anything that sees it back home
    in the meantime (re-entering the fence, reaching Frigate over the LAN, the manual switch)
    cancels the request. This is the other half: once the dwell has run out, the phone is away.
    Runs every poll, so the promotion lands within POLL_SECONDS of the deadline.
    """
    now = time.time()
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, name FROM devices WHERE away=0 AND away_pending_since IS NOT NULL"
        " AND away_pending_since + COALESCE(away_pending_dwell, 0) <= ?",
        (now,),
    ).fetchall())
    for device_id, name in rows:
        with_db(lambda c: (
            c.execute(
                "UPDATE devices SET away=1, away_updated=?, away_pending_since=NULL, away_pending_dwell=NULL WHERE device_id=?",
                (now, device_id),
            ),
            c.commit(),
        ))
        log.info("presence: %s left for good (dwell over) -> everyone_away=%s", name or device_id, presence_snapshot()["everyone_away"])


def away_items(since: float) -> list[dict[str, Any]]:
    """Every review item — alerts *and* detections — with a person in it that started after everyone left, oldest first."""
    items = {item["id"]: item for severity in ("alert", "detection") for item in recent_review(severity)}
    fresh = [
        item for item in items.values()
        if float(item.get("start_time") or 0) >= since and "person" in ((item.get("data") or {}).get("objects") or [])
    ]
    return sorted(fresh, key=lambda item: float(item.get("start_time") or 0))


def push_away_review(item: dict[str, Any], zones: dict[str, list[str]]) -> None:
    """Escalated push for one review item while nobody is home; records it in `sent` like a normal alert."""
    rid = item["id"]
    camera, what = sentence(item, zones.get(item.get("camera", ""), []))
    title = f"Away: {what}"
    body = f"{camera} · nobody home"
    data = {
        "review_id": rid,
        "camera": item.get("camera", ""),
        "event_id": (item.get("data", {}).get("detections") or [""])[0],
        "zones": ",".join(item.get("data", {}).get("zones") or []),
        "start_time": str(item.get("start_time", "")),
        "away": "1",
    }
    result = broadcast(title, body, data, away=True)
    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), title)), c.commit()))
    log.info("away alert %s -> %s: %s | %s", rid, title, body, result)


# Frigate names a face a few seconds into a visit. A person who is still here and still
# anonymous is re-checked for this long before being pushed as a stranger, so a phone that only
# wants strangers isn't woken for family walking up the drive. Mirrors
# DetectionAlertService.RECOGNITION_GRACE_SECONDS in the app.
RECOGNITION_GRACE_SECONDS = 20.0


def has_person(item: dict[str, Any]) -> bool:
    return "person" in ((item.get("data") or {}).get("objects") or [])


def is_recognised_person(item: dict[str, Any]) -> bool:
    """A person Frigate put a name to — the review item carries the face as a sub_label."""
    sub_labels = [s for s in ((item.get("data") or {}).get("sub_labels") or []) if s and s.lower() != "unknown"]
    return has_person(item) and bool(sub_labels)


def awaiting_recognition(item: dict[str, Any]) -> bool:
    """Still in progress, a person in it, no name yet, and young enough that a name may still come."""
    if not has_person(item) or is_recognised_person(item) or item.get("end_time") is not None:
        return False
    if not with_db(lambda c: c.execute("SELECT 1 FROM devices WHERE quiet_familiar=1").fetchone()):
        return False  # nobody cares about the distinction: don't delay anyone's push
    return time.time() - float(item.get("start_time") or 0) < RECOGNITION_GRACE_SECONDS


def poll_forever() -> None:
    # Everything that already exists at boot is history, not news.
    try:
        for item in recent_alerts():
            with_db(lambda c: (c.execute("INSERT OR IGNORE INTO sent VALUES (?,?,?)", (item["id"], time.time(), "(pre-existing)")), c.commit()))
    except Exception as e:
        log.warning("initial review fetch failed: %s", e)
    while True:
        try:
            zones = required_zones()
            # ---- Away mode: while nobody is home, any person on any camera is news (alerts and
            # detections alike, zone rules ignored). Runs first so a person alert goes out escalated
            # and the normal pass below then finds it already in `sent`. ----
            promote_pending()
            since = away_since()
            if since is not None:
                for item in away_items(since):
                    if with_db(lambda c: c.execute("SELECT 1 FROM sent WHERE review_id=?", (item["id"],)).fetchone()):
                        continue
                    push_away_review(item, zones)
            # ---- end away mode ----
            for item in reversed(recent_alerts()):  # oldest first, so pushes arrive in order
                rid = item["id"]
                if with_db(lambda c: c.execute("SELECT 1 FROM sent WHERE review_id=?", (rid,)).fetchone()):
                    continue
                if awaiting_recognition(item):
                    continue  # not marked sent: judged again next poll, once Frigate has had time to name the face
                title, body = sentence(item, zones.get(item.get("camera", ""), []))
                data = {
                    "review_id": rid,
                    "camera": item.get("camera", ""),
                    "event_id": (item.get("data", {}).get("detections") or [""])[0],
                    "zones": ",".join(item.get("data", {}).get("zones") or []),
                    "start_time": str(item.get("start_time", "")),
                }
                result = broadcast(title, body, data, familiar=is_recognised_person(item))
                with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), body)), c.commit()))
                log.info("alert %s -> %s: %s | %s", rid, title, body, result)
        except Exception as e:
            log.warning("poll error: %s", e)
        time.sleep(POLL_SECONDS)


# ---------------------------------------------------------------- HTTP API

app = FastAPI(title="HomeSafe relay")


class Device(BaseModel):
    # A UUID the app makes once per install and keeps: the identity the relay knows a phone by.
    # Old apps send only `token`; the relay then uses the token as the id, as it always did.
    device_id: str | None = None
    # Where to push. Absent for a phone that can't receive push (iOS, until APNs lands).
    token: str | None = None
    platform: str = "android"
    name: str = ""
    # "Only strangers": skip pushes for people Frigate recognised. Sent on every registration.
    quiet_familiar: bool = False
    # "release" or "debug" — decides whether this phone counts towards away mode (`counts_for_away`).
    build: str = "unknown"


class Presence(BaseModel):
    away: bool
    # What flipped it — "manual", "geofence", "lan" — for the log only.
    source: str = "manual"
    # For away=true: wait this long, and only then mark the phone away unless something has said
    # "home" in the meantime (see `promote_pending`). 0 means right now, which is what the switch does.
    dwell_seconds: float = 0


class Home(BaseModel):
    lat: float
    lng: float
    radius_m: float = 150


def require_frigate_session(request: Request) -> str:
    """The caller proves they're a signed-in HomeSafe user by carrying a valid Frigate session cookie."""
    cookie = request.headers.get("cookie")
    if not cookie:
        raise HTTPException(status_code=401, detail="Frigate session cookie required")
    try:
        r = requests.get(f"{FRIGATE_AUTH}/api/profile", headers={"Cookie": cookie}, timeout=5)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Frigate unreachable: {e}")
    if r.status_code != 200:
        raise HTTPException(status_code=401, detail="Frigate rejected the session")
    try:
        return r.json().get("username", "?")
    except Exception:
        return "?"


def find_device(ident: str) -> tuple | None:
    """A device row by device_id, or — for the old apps and old rows — by push token."""
    return with_db(lambda c: c.execute(
        "SELECT device_id, token, name, secret FROM devices WHERE device_id=? OR token=?", (ident, ident)
    ).fetchone())


def authenticate(request: Request, device: str | None = None) -> str:
    """
    Two ways in. A signed-in user carries the Frigate session cookie and may do anything. An
    *install* carries `Authorization: Bearer <secret>` — the secret it was handed when it
    registered — and may act only on its own row, named by `device`. That second door exists
    for the background wakes (a geofence crossing at 3 AM, a rotated push token) that have no
    Frigate session and no user to ask for one.
    """
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer ") and device is not None:
        row = find_device(device)
        if row is not None and row[3] and secrets.compare_digest(row[3], auth[7:].strip()):
            return f"device:{row[2] or row[0]}"
        # A secret the relay no longer knows (its database was reset, say) must not lock out a
        # signed-in user: the cookie, when there is one, still decides — and re-registration
        # then hands the app a fresh secret.
        if not request.headers.get("cookie"):
            raise HTTPException(status_code=401, detail="Unknown device or wrong secret")
    return require_frigate_session(request)


@app.on_event("startup")
def startup() -> None:
    global CONN
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    CONN = db()
    threading.Thread(target=poll_forever, name="poller", daemon=True).start()
    log.info("relay up: frigate=%s project=%s poll=%ss", FRIGATE, PROJECT, POLL_SECONDS)


@app.get("/health")
def health() -> dict[str, Any]:
    devices = with_db(lambda c: c.execute("SELECT COUNT(*) FROM devices").fetchone()[0])
    sent = with_db(lambda c: c.execute("SELECT COUNT(*) FROM sent").fetchone()[0])
    return {"ok": True, "devices": devices, "alerts_seen": sent, "project": PROJECT}


@app.post("/devices")
def register(device: Device, request: Request) -> dict[str, Any]:
    """
    Registers (or refreshes) an install. Answers with its `device_id` and its `secret`; the app
    keeps both. The secret is minted once and returned on every registration, so an app that
    lost it (a reinstall keeps neither) simply gets it again by signing in.
    """
    if not device.device_id and not device.token:
        raise HTTPException(status_code=400, detail="device_id or token required")
    device_id = device.device_id or device.token
    user = authenticate(request, device_id)
    now = time.time()

    def upsert(c: sqlite3.Connection) -> str:
        if device.token:
            # An app that only just learned it has an identity: its old row is keyed by its token.
            # Move that row over so its presence and history survive the upgrade...
            c.execute("UPDATE devices SET device_id=? WHERE device_id=? AND device_id!=?", (device_id, device.token, device_id))
            # ...and a token belongs to exactly one install, so take it off any other row.
            c.execute("UPDATE devices SET token=NULL WHERE token=? AND device_id!=?", (device.token, device_id))
        row = c.execute("SELECT secret FROM devices WHERE device_id=?", (device_id,)).fetchone()
        secret = (row[0] if row else None) or secrets.token_urlsafe(32)
        c.execute(
            # Deliberately leaves `away`/`away_updated`/pending alone: re-registering (every connect,
            # every LAN/Tailscale flip) must not quietly mark a phone as back home.
            "INSERT INTO devices (device_id, token, platform, name, created, last_seen, quiet_familiar, build, secret)"
            " VALUES (?,?,?,?,?,?,?,?,?)"
            " ON CONFLICT(device_id) DO UPDATE SET token=excluded.token, platform=excluded.platform, name=excluded.name,"
            " last_seen=excluded.last_seen, quiet_familiar=excluded.quiet_familiar, build=excluded.build, secret=excluded.secret",
            (device_id, device.token, device.platform, device.name, now, now, int(device.quiet_familiar), device.build, secret),
        )
        c.commit()
        return secret

    secret = with_db(upsert)
    log.info(
        "device registered by %s: %s (%s %s, push=%s, strangers only=%s, counts for away=%s)",
        user, device.name or "unnamed", device.platform, device.build, device.token is not None, device.quiet_familiar,
        counts_for_away(device.platform, device.build),
    )
    return {"ok": True, "device_id": device_id, "secret": secret}


@app.delete("/devices/{ident}")
def unregister(ident: str, request: Request) -> dict[str, Any]:
    authenticate(request, ident)
    with_db(lambda c: (c.execute("DELETE FROM devices WHERE device_id=? OR token=?", (ident, ident)), c.commit()))
    return {"ok": True}


@app.put("/devices/{ident}/presence")
def set_presence(ident: str, presence: Presence, request: Request) -> dict[str, Any]:
    """
    This phone's owner has left (or come back). `ident` is the device_id (or, for old apps, the
    token); the row is created if it doesn't exist yet so a presence change can't race
    registration. Away with a dwell only *arms* the change — see `promote_pending`. Home is
    always immediate and cancels any armed departure.
    """
    user = authenticate(request, ident)
    now = time.time()
    row = find_device(ident)
    device_id = row[0] if row else ident

    def apply(c: sqlite3.Connection) -> str:
        if row is None:
            c.execute("INSERT INTO devices (device_id, platform, name, created, last_seen) VALUES (?,?,?,?,?)", (device_id, "unknown", "", now, now))
        if not presence.away:
            c.execute(
                "UPDATE devices SET last_seen=?, away=0, away_updated=?, away_pending_since=NULL, away_pending_dwell=NULL WHERE device_id=?",
                (now, now, device_id),
            )
            outcome = "home"
        elif presence.dwell_seconds > 0:
            already = c.execute("SELECT away, away_pending_since FROM devices WHERE device_id=?", (device_id,)).fetchone()
            if already and (already[0] or already[1] is not None):
                c.execute("UPDATE devices SET last_seen=? WHERE device_id=?", (now, device_id))
                outcome = "already away" if already[0] else "already leaving"
            else:
                c.execute(
                    "UPDATE devices SET last_seen=?, away_pending_since=?, away_pending_dwell=? WHERE device_id=?",
                    (now, now, float(presence.dwell_seconds), device_id),
                )
                outcome = f"leaving, away in {int(presence.dwell_seconds)}s"
        else:
            c.execute(
                "UPDATE devices SET last_seen=?, away=1, away_updated=?, away_pending_since=NULL, away_pending_dwell=NULL WHERE device_id=?",
                (now, now, device_id),
            )
            outcome = "away"
        c.commit()
        return outcome

    outcome = with_db(apply)
    snapshot = presence_snapshot(device_id)
    log.info("presence by %s (%s): %s -> everyone_away=%s", user, presence.source, outcome, snapshot["everyone_away"])
    return snapshot


@app.get("/presence")
def get_presence(request: Request, device: str | None = None, token: str | None = None) -> dict[str, Any]:
    """Who's home. Pass this phone's `device` (or, old apps, `token`) so its own entry comes back flagged `this_device`."""
    ident = device or token
    authenticate(request, ident)
    return presence_snapshot(ident)


@app.put("/home")
def set_home(home: Home, request: Request, device: str | None = None) -> dict[str, Any]:
    """
    Where home is, for every phone's geofence. Whoever is standing in it sets it; a user action,
    so the session cookie. `device` only lets the answer flag the caller's own row.
    """
    user = require_frigate_session(request)
    value = {"lat": home.lat, "lng": home.lng, "radius_m": max(50.0, home.radius_m), "updated": time.time(), "by": user}
    state_set(HOME_KEY, value)
    log.info("home set by %s: %.5f, %.5f r=%dm", user, home.lat, home.lng, value["radius_m"])
    return presence_snapshot(device)


@app.delete("/home")
def clear_home(request: Request, device: str | None = None) -> dict[str, Any]:
    user = require_frigate_session(request)
    state_set(HOME_KEY, None)
    log.info("home cleared by %s", user)
    return presence_snapshot(device)


@app.get("/devices")
def list_devices(request: Request) -> list[dict[str, Any]]:
    require_frigate_session(request)
    rows = with_db(lambda c: c.execute(
        "SELECT platform, name, created, last_seen, away, build, token IS NOT NULL, away_pending_since IS NOT NULL FROM devices"
    ).fetchall())
    return [
        {"platform": p, "name": n, "created": cr, "last_seen": ls, "away": bool(a), "build": b, "counts_for_away": counts_for_away(p, b), "push": bool(push), "pending_away": bool(pend)}
        for p, n, cr, ls, a, b, push, pend in rows
    ]


@app.post("/test")
def test_push(request: Request) -> dict[str, Any]:
    """Sends a sample alert to every registered phone, so the whole path can be checked from the app."""
    user = require_frigate_session(request)
    result = broadcast("Front Yard", "Test: Sarah's Tesla in the driveway", {"review_id": f"test-{int(time.time())}", "camera": "hikvision_1", "test": "1"})
    log.info("test push by %s: %s", user, result)
    return {"ok": True, **result}
