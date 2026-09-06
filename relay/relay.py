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
    conn.execute("CREATE TABLE IF NOT EXISTS devices (token TEXT PRIMARY KEY, platform TEXT, name TEXT, created REAL, last_seen REAL)")
    conn.execute("CREATE TABLE IF NOT EXISTS sent (review_id TEXT PRIMARY KEY, sent_at REAL, body TEXT)")
    conn.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT)")
    # Away mode (see docs/away-mode.md in the app repo): each phone says whether its owner is home.
    # Guarded ALTERs so a relay.db from before the feature upgrades itself on boot.
    columns = {row[1] for row in conn.execute("PRAGMA table_info(devices)")}
    if "away" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN away INTEGER NOT NULL DEFAULT 0")
    if "away_updated" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN away_updated REAL")
    # Familiar vs. stranger (docs/familiar-faces.md): a phone that set "only strangers" doesn't
    # want to hear about people Frigate recognised (a face arrives as the review item's sub_label).
    if "quiet_familiar" not in columns:
        conn.execute("ALTER TABLE devices ADD COLUMN quiet_familiar INTEGER NOT NULL DEFAULT 0")
    conn.commit()
    return conn


DB_LOCK = threading.Lock()
CONN = None


def with_db(fn):
    with DB_LOCK:
        return fn(CONN)


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
    rows = with_db(lambda c: c.execute("SELECT token, quiet_familiar FROM devices").fetchall())
    tokens = [token for token, quiet in rows if not (familiar and quiet)]
    ok = dropped = failed = 0
    skipped = len(rows) - len(tokens)
    for token in tokens:
        sent, err = send_push(token, title, body, data, away=away)
        if sent:
            ok += 1
        elif "UNREGISTERED" in err or "404" in err:
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

def presence_snapshot(this_token: str | None = None) -> dict[str, Any]:
    """Who says they're home. `everyone_away` needs at least one device, and all of them away."""
    rows = with_db(lambda c: c.execute("SELECT token, name, platform, away, away_updated FROM devices ORDER BY created").fetchall())
    devices = [
        {"name": n, "platform": p, "away": bool(a), "away_updated": u, "this_device": t == this_token}
        for t, n, p, a, u in rows
    ]
    return {"devices": devices, "everyone_away": bool(devices) and all(d["away"] for d in devices)}


def away_since() -> float | None:
    """When the last person left, or None while somebody is home (or nobody has registered)."""
    row = with_db(lambda c: c.execute("SELECT COUNT(*), SUM(away), MAX(away_updated) FROM devices").fetchone())
    total, away, updated = row
    if not total or (away or 0) < total:
        return None
    return float(updated or 0.0)


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
    token: str
    platform: str = "android"
    name: str = ""
    # "Only strangers": skip pushes for people Frigate recognised. Sent on every registration.
    quiet_familiar: bool = False


class Presence(BaseModel):
    away: bool


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
    user = require_frigate_session(request)
    now = time.time()
    with_db(lambda c: (
        c.execute(
            # Deliberately leaves `away`/`away_updated` alone: re-registering (every connect, every
            # LAN/Tailscale flip) must not quietly mark a phone as back home.
            "INSERT INTO devices (token, platform, name, created, last_seen, quiet_familiar) VALUES (?,?,?,?,?,?) "
            "ON CONFLICT(token) DO UPDATE SET platform=excluded.platform, name=excluded.name, last_seen=excluded.last_seen, "
            "quiet_familiar=excluded.quiet_familiar",
            (device.token, device.platform, device.name, now, now, int(device.quiet_familiar)),
        ),
        c.commit(),
    ))
    log.info("device registered by %s: %s (%s, strangers only=%s)", user, device.name or "unnamed", device.platform, device.quiet_familiar)
    return {"ok": True}


@app.delete("/devices/{token}")
def unregister(token: str, request: Request) -> dict[str, Any]:
    require_frigate_session(request)
    with_db(lambda c: (c.execute("DELETE FROM devices WHERE token=?", (token,)), c.commit()))
    return {"ok": True}


@app.put("/devices/{token}/presence")
def set_presence(token: str, presence: Presence, request: Request) -> dict[str, Any]:
    """This phone's owner has left (or come back). Upserts so a presence change can't race registration."""
    user = require_frigate_session(request)
    now = time.time()
    with_db(lambda c: (
        c.execute(
            "INSERT INTO devices (token, platform, name, created, last_seen, away, away_updated) VALUES (?,?,?,?,?,?,?) "
            "ON CONFLICT(token) DO UPDATE SET last_seen=excluded.last_seen, away=excluded.away, away_updated=excluded.away_updated",
            (token, "unknown", "", now, now, int(presence.away), now),
        ),
        c.commit(),
    ))
    snapshot = presence_snapshot(token)
    log.info("presence by %s: away=%s -> everyone_away=%s", user, presence.away, snapshot["everyone_away"])
    return snapshot


@app.get("/presence")
def get_presence(request: Request, token: str | None = None) -> dict[str, Any]:
    """Who's home. Pass this phone's token so its own entry comes back flagged `this_device`."""
    require_frigate_session(request)
    return presence_snapshot(token)


@app.get("/devices")
def list_devices(request: Request) -> list[dict[str, Any]]:
    require_frigate_session(request)
    rows = with_db(lambda c: c.execute("SELECT platform, name, created, last_seen, away FROM devices").fetchall())
    return [{"platform": p, "name": n, "created": cr, "last_seen": ls, "away": bool(a)} for p, n, cr, ls, a in rows]


@app.post("/test")
def test_push(request: Request) -> dict[str, Any]:
    """Sends a sample alert to every registered phone, so the whole path can be checked from the app."""
    user = require_frigate_session(request)
    result = broadcast("Front Yard", "Test: Sarah's Tesla in the driveway", {"review_id": f"test-{int(time.time())}", "camera": "hikvision_1", "test": "1"})
    log.info("test push by %s: %s", user, result)
    return {"ok": True, **result}
