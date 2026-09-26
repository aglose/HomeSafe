"""
HomeSafe push relay.

Frigate has no push service for phones, so this small service sits next to it on the same box:
it polls Frigate's review API for new *alerts* (the ones already gated by zone in Frigate's own
config), turns each into a sentence like "Sarah's Tesla in the driveway", and sends it through
Firebase Cloud Messaging to every phone that registered. Phones register by POSTing their FCM
token; the request is authenticated by forwarding the caller's Frigate session cookie to
Frigate's own authenticated port, so the relay holds no secrets of its own beyond the FCM key.

Not every alert is pushed. A vehicle is only news when it has actually gone somewhere, so an alert
whose only objects are vehicles that never moved is held while it's open (a car pulling in shows
travel within seconds) and dropped once it ends still — see `motion_verdict`. And not every phone
gets every push: each registers its own quiet hours and "only when everyone's away" choice, and an
ordinary alert skips a phone that is inside its quiet hours or only wants Away alerts — see
`silenced`. Away alerts reach every phone regardless.
"""

import json
import logging
import os
import re
import secrets
import sqlite3
from statistics import median
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

import requests
from fastapi import FastAPI, HTTPException, Request, Response
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account
from pydantic import BaseModel

FRIGATE = os.environ.get("FRIGATE_INTERNAL", "http://127.0.0.1:5000").rstrip("/")
FRIGATE_AUTH = os.environ.get("FRIGATE_AUTH", "http://127.0.0.1:8971").rstrip("/")
PROJECT = os.environ["FCM_PROJECT"]
KEY_FILE = os.environ.get("FCM_KEY", "/secrets/fcm.json")
DB_PATH = os.environ.get("RELAY_DB", "/data/relay.db")
POLL_SECONDS = float(os.environ.get("POLL_SECONDS", "5"))
# Frigate's clips folder, mounted in so a car tagged in the app can join a classifier's dataset:
# Frigate itself can only file the crops it queued, never a frame someone boxed by hand.
CLIPS_DIR = os.environ.get("CLIPS_DIR", "/clips")
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
        " build TEXT NOT NULL DEFAULT 'unknown', secret TEXT, away_pending_since REAL, away_pending_dwell REAL,"
        " quiet_start INTEGER, quiet_end INTEGER, only_away INTEGER NOT NULL DEFAULT 0, tz TEXT, utc_offset INTEGER)"
    )
    conn.execute("CREATE TABLE IF NOT EXISTS sent (review_id TEXT PRIMARY KEY, sent_at REAL, body TEXT)")
    conn.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT)")
    # What the car check (see `car_check_forever`) made of each event, so it looks at each once:
    # kind "street" (was it a passing car to file under `none`) or "vlm" (the second opinion).
    conn.execute("CREATE TABLE IF NOT EXISTS car_checks (event_id TEXT, kind TEXT, at REAL, verdict TEXT, detail TEXT, PRIMARY KEY (event_id, kind))")
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
    # Added after the device_id rebuild: each phone's quiet hours (minutes after its local
    # midnight, NULL while off), its "only when everyone's away" choice, and the clock to read
    # them by. Guarded like the ALTERs above so an existing relay.db picks them up on boot.
    columns = {row[1] for row in conn.execute("PRAGMA table_info(devices)")}
    for column, declaration in (
        ("quiet_start", "INTEGER"),
        ("quiet_end", "INTEGER"),
        ("only_away", "INTEGER NOT NULL DEFAULT 0"),
        ("tz", "TEXT"),
        ("utc_offset", "INTEGER"),
    ):
        if column not in columns:
            conn.execute(f"ALTER TABLE devices ADD COLUMN {column} {declaration}")
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


def push_message(token: str, title: str, body: str, data: dict[str, str], away: bool = False) -> dict[str, Any]:
    """
    The FCM v1 body for one phone. Android gets a *data-only* message: with a `notification`
    block, Android draws a backgrounded app's notification itself — no picture, no clip, and a
    tap that can't say which moment it was — so the app's messaging service must always be the
    one to post it (text at once, then its picture and clip; see HomeSafeMessagingService.kt).
    The title and body travel in the data for that. iOS draws its own banner from `aps.alert`.
    `away` escalates it: the app's loud "away_alerts" channel on Android, time-sensitive on iOS.
    """
    aps: dict[str, Any] = {"alert": {"title": title, "body": body}, "sound": "default", "thread-id": data.get("camera", "")}
    if away:
        aps["interruption-level"] = "time-sensitive"
    return {
        "message": {
            "token": token,
            "data": {**data, "title": title, "body": body},
            "android": {"priority": "high"},
            "apns": {"headers": {"apns-priority": "10"}, "payload": {"aps": aps}},
        }
    }


def send_push(token: str, title: str, body: str, data: dict[str, str], away: bool = False) -> tuple[bool, str]:
    """One FCM message; see `push_message` for its shape."""
    message = push_message(token, title, body, data, away=away)
    r = fcm_session().post(f"https://fcm.googleapis.com/v1/projects/{PROJECT}/messages:send", json=message, timeout=15)
    if r.ok:
        return True, ""
    try:
        err = r.json().get("error", {})
        code = err.get("status", "") + " " + " ".join(d.get("errorCode", "") for d in err.get("details", []) if isinstance(d, dict))
    except Exception:
        code = r.text[:200]
    return False, f"{r.status_code} {code}".strip()


def local_minute(now: float, tz: str | None, utc_offset: int | None) -> int | None:
    """
    Minutes since midnight on the phone's clock: by its IANA zone when this relay can resolve it,
    else by the UTC offset it last reported (right until the next DST change, and the phone
    re-registers on every connect), else None — nothing to read quiet hours by.
    """
    zone = None
    if tz:
        try:
            zone = ZoneInfo(tz)
        except Exception:
            zone = None
    if zone is None and utc_offset is not None:
        zone = timezone(timedelta(minutes=utc_offset))
    if zone is None:
        return None
    local = datetime.fromtimestamp(now, zone)
    return local.hour * 60 + local.minute


def in_quiet_hours(start: int | None, end: int | None, minute: int) -> bool:
    """
    Whether `minute` falls in the window from `start` up to (not including) `end`, which may wrap
    midnight. A window that starts where it ends is empty; so is one that isn't set. Same rule as
    the app's `QuietHours.contains`.
    """
    if start is None or end is None or start == end:
        return False
    if start < end:
        return start <= minute < end
    return minute >= start or minute < end


def silenced(only_away: bool, quiet_start: int | None, quiet_end: int | None, tz: str | None, utc_offset: int | None, now: float) -> bool:
    """Whether an ordinary (not Away) alert skips this phone right now: it only wants Away alerts, or it's in its quiet hours."""
    if only_away:
        return True
    minute = local_minute(now, tz, utc_offset)
    return minute is not None and in_quiet_hours(quiet_start, quiet_end, minute)


def broadcast(title: str, body: str, data: dict[str, str], away: bool = False, familiar: bool = False, test: bool = False) -> dict[str, int]:
    """
    Pushes to every phone — except, for a [familiar] person, the phones that asked for strangers
    only, and, for an ordinary alert, the phones that are `silenced`. An [away] alert and a [test]
    push go to every phone whatever it asked for.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT token, quiet_familiar, only_away, quiet_start, quiet_end, tz, utc_offset FROM devices WHERE token IS NOT NULL"
    ).fetchall())
    now = time.time()
    tokens = []
    skipped = quiet = 0
    for token, quiet_familiar, only_away, quiet_start, quiet_end, tz, utc_offset in rows:
        if familiar and quiet_familiar:
            skipped += 1
        elif not away and not test and silenced(bool(only_away), quiet_start, quiet_end, tz, utc_offset, now):
            quiet += 1
        else:
            tokens.append(token)
    ok = dropped = failed = 0
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
    return {"sent": ok, "dropped": dropped, "failed": failed, "skipped_familiar": skipped, "skipped_quiet": quiet}


# ---------------------------------------------------------------- Frigate

_required_zones: dict[str, list[str]] = {}
_car_zones: dict[str, list[str]] = {}
_car_zone_polygons: dict[str, list[list[tuple[float, float]]]] = {}
_config_loaded_at = 0.0


def zones_wanting(cam: dict[str, Any], label: str) -> list[str]:
    """The camera's zones that count `label` as inside: Frigate tags a zone only when its `objects` is empty or names the label."""
    return [name for name, zone in (cam.get("zones") or {}).items() if not (zone or {}).get("objects") or label in zone["objects"]]


def zone_polygon(zone: dict[str, Any]) -> list[tuple[float, float]]:
    """A zone's `coordinates` ("x1,y1,x2,y2,...", fractions of the frame) as points; empty when it has none."""
    raw = (zone or {}).get("coordinates") or ""
    if isinstance(raw, list):
        raw = ",".join(str(v) for v in raw)
    try:
        values = [float(v) for v in str(raw).split(",") if v.strip()]
    except ValueError:
        return []
    return list(zip(values[0::2], values[1::2]))


def refresh_config() -> None:
    global _required_zones, _car_zones, _car_zone_polygons, _config_loaded_at
    if time.time() - _config_loaded_at > CONFIG_REFRESH_SECONDS:
        try:
            cfg = requests.get(f"{FRIGATE}/api/config", timeout=10).json()
            cameras = cfg.get("cameras", {})
            _required_zones = {name: (cam.get("review", {}).get("alerts", {}).get("required_zones") or []) for name, cam in cameras.items()}
            _car_zones = {name: zones_wanting(cam, "car") for name, cam in cameras.items()}
            _car_zone_polygons = {
                name: [p for p in (zone_polygon((cam.get("zones") or {}).get(z)) for z in _car_zones[name]) if len(p) >= 3]
                for name, cam in cameras.items()
            }
            _config_loaded_at = time.time()
        except Exception as e:  # keep the last known maps
            log.warning("config refresh failed: %s", e)
            _config_loaded_at = time.time() - CONFIG_REFRESH_SECONDS + 30


def required_zones() -> dict[str, list[str]]:
    refresh_config()
    return _required_zones


def car_zones() -> dict[str, list[str]]:
    """Per camera, the zones a car counts as in — on the Front Yard, the driveway. Empty for a camera with none."""
    refresh_config()
    return _car_zones


def car_zone_polygons() -> dict[str, list[list[tuple[float, float]]]]:
    """Per camera, the outlines of the zones in `car_zones`."""
    refresh_config()
    return _car_zone_polygons


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
    `id` and `last_seen` let Settings tell a phone in use from an old install, and remove the
    latter (`DELETE /devices/{id}`).
    """
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, token, name, platform, away, away_updated, build, away_pending_since, last_seen FROM devices ORDER BY created"
    ).fetchall())
    devices = [
        {
            "id": d,
            "name": n,
            "platform": p,
            "away": bool(a),
            "away_updated": u,
            "this_device": this_device is not None and this_device in (d, t),
            "build": b,
            "counts": counts_for_away(p, b),
            "pending_away": ps is not None,
            "last_seen": ls,
        }
        for d, t, n, p, a, u, b, ps, ls in rows
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


# ---------------------------------------------------------------- motion gate

# Frigate re-detects a parked car all day: the detector's box on it flickers between the whole car
# and part of it, the tracker registers a brand-new object, and Frigate's review maintainer files
# an alert for any new object that has "moved at least once" — a fresh object's first jitter
# counts. Seen on the Front Yard camera 2026-09-15: forty "car in the driveway" alerts in three
# hours for two Teslas that never left the driveway, each a 2-point path with no travel. The
# stationary classifier can't help; these objects die within seconds, before it gets a look.
#
# So a vehicle is only news once it has gone somewhere. Mirrors the app's VehicleVisits.isStill
# and DetectionAlertService, which apply the same rule to the Moments feed and the in-app poller.
VEHICLE_LABELS = {"car", "truck", "bus", "motorcycle", "bicycle", "boat", "train", "vehicle"}
# Share of the path that must sit within the box's size of the path's median for `is_still`.
STILL_FRACTION = 0.7
# A path shorter than this is still whatever its shape. Frigate records a point when the object
# first appears, another on its next look, and then one per ~5% of the frame travelled, so three
# points is a single jump — the detector's box flipping between the whole car and part of it —
# and only from four is there a journey to judge.
MOVED_MIN_POINTS = 4
# An open alert whose vehicles haven't moved *yet* is judged again next poll, so a car pulling in
# is pushed as soon as its path shows travel. Past this age it is judged as it stands.
MOTION_WAIT_CAP_SECONDS = 600.0


def fetch_event(event_id: str) -> dict[str, Any] | None:
    """One tracked object from `/api/events/{id}`, with its box and path; None if Frigate has no such event, raises if it couldn't say."""
    r = requests.get(f"{FRIGATE}/api/events/{event_id}", timeout=5)
    if r.status_code == 404:
        return None
    r.raise_for_status()
    return r.json()


def event_detail(event_id: str) -> dict[str, Any] | None:
    """`fetch_event`, but None whenever Frigate can't say."""
    try:
        return fetch_event(event_id)
    except Exception as e:
        log.warning("event %s lookup failed: %s", event_id, e)
        return None


def path_points(event: dict[str, Any]) -> list[tuple[float, float]]:
    """The bottom-centre points of `data.path_data` (`[[[x, y], time], ...]`), in order."""
    points = []
    for sample in (event.get("data") or {}).get("path_data") or []:
        if isinstance(sample, list) and sample and isinstance(sample[0], list) and len(sample[0]) >= 2:
            points.append((float(sample[0][0]), float(sample[0][1])))
    return points


def is_still(event: dict[str, Any]) -> bool:
    """
    True when the object barely moved: at least STILL_FRACTION of its path points lie within
    r = max(box width, box height) of the path's median point on both axes. A path of fewer than
    MOVED_MIN_POINTS points is still (nothing has happened yet); an event with no box is never
    still (there is nothing to judge it by). Same rule, same numbers, as the app's
    `MomentEvent.isStill`.
    """
    box = (event.get("data") or {}).get("box")
    if not box or len(box) < 4 or box[2] <= 0 or box[3] <= 0:
        return False
    points = path_points(event)
    if len(points) < MOVED_MIN_POINTS:
        return True
    mx = median(x for x, _ in points)
    my = median(y for _, y in points)
    r = max(float(box[2]), float(box[3]))
    near = sum(1 for x, y in points if abs(x - mx) <= r and abs(y - my) <= r)
    return near / len(points) >= STILL_FRACTION


def motion_verdict(item: dict[str, Any]) -> str:
    """
    Whether this alert has something in it that moved: "push" (yes, or it can't be judged and a
    real alert is worth more than a quiet phone), "wait" (only vehicles so far and none has moved
    yet, but the alert is still open) or "skip" (it ended and nothing in it ever moved).
    """
    data = item.get("data") or {}
    # Review items suffix a classified object ("car-verified"); the label is what matters here.
    labels = {str(o).removesuffix("-verified") for o in (data.get("objects") or [])}
    detections = [d for d in (data.get("detections") or []) if d]
    if not labels or not detections or any(label not in VEHICLE_LABELS for label in labels):
        return "push"  # a person, a dog — news whatever the cars are doing
    for event_id in detections:
        event = event_detail(event_id)
        if event is None or (event.get("data") or {}).get("type") not in (None, "object"):
            return "push"
        if not is_still(event):
            return "push"
    if item.get("end_time") is None and time.time() - float(item.get("start_time") or 0) < MOTION_WAIT_CAP_SECONDS:
        return "wait"
    return "skip"


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
                verdict = motion_verdict(item)
                if verdict == "wait":
                    continue  # likewise: judged again once the car has had a chance to go somewhere
                if verdict == "skip":
                    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), "(stationary)")), c.commit()))
                    log.info("alert %s skipped: nothing in it moved (%s)", rid, ", ".join((item.get("data") or {}).get("objects") or []))
                    continue
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


# ---------------------------------------------------------------- car check

# Measured on the Front Yard 2026-09-23: Frigate's car classifier (`known_cars`) named 45-89% of
# the cars driving past as one of the household's, almost always "andrews_tesla" at ~0.98. It
# judges a 55-124 px crop of the detect frame, and its only picture of "every other car in the
# world" is the `none` class. Two jobs here chip at that, both off unless configured:
#
# - Street crops into `none`. Frigate queues a crop of every car it tries to classify
#   (`clips/<model>/train/`), keeps only the newest 200, and a passing car on the street is,
#   by construction, not one of ours. So crops of cars that entered no car zone, travelled across
#   the frame, and had no car in a car zone near them in time are filed into `none`, a few an
#   hour so the class spans day, dusk and infrared night — and the model is retrained once a day
#   when enough have been added.
# - A second opinion on cars in a car zone (the driveway), from a local vision model through
#   Ollama. It looks at the car in the 4K recording rather than the detect frame (~6x the pixels),
#   and answers a closed set — colour, make, body — which is then matched against what the
#   household's cars look like (HOUSEHOLD_CARS). It only ever vetoes or corrects the classifier:
#   a name that contradicts what the car looks like is replaced by the one household car that
#   fits, or cleared; it never names a car the classifier left unnamed, and never touches a name a
#   person gave (score 1.0, from the app's car tagging). No appearance can tell our dark blue Model Y
#   from a neighbour's; this catches the red hatchback called "Andrew's Tesla".
CAR_CLASSIFIER = os.environ.get("CAR_CLASSIFIER", "")
STREET_NONE_MAX = int(os.environ.get("STREET_NONE_MAX", "600"))
STREET_NONE_PER_HOUR = int(os.environ.get("STREET_NONE_PER_HOUR", "12"))
# Crops of one car are near-duplicates; two is variety enough.
STREET_CROPS_PER_EVENT = 2
# A car must cross this much of the frame (bottom-centre path, fractions) to count as passing.
STREET_MIN_TRAVEL = 0.2
# A household car the tracker lost on its way out of the driveway is still ours: nothing within
# this long of a car in a car zone is filed.
STREET_CLEAR_SECONDS = 180.0
# Nor anything whose path came this close to a car zone's outline (frame fractions).
STREET_ZONE_MARGIN = 0.05
# Frigate's `/api/events` filters on start time alone, so a visit that ends in that window is
# found in two looks: every visit that began up to this long before it, and, among the visits of
# at least this length, those that began up to CAR_ZONE_LONGEST_VISIT before it. A parked car is
# re-registered every so often; the longest visit in the week to 2026-09-25 was 2.8 h.
CAR_ZONE_RECENT_SECONDS = 1800.0
CAR_ZONE_LONGEST_VISIT_SECONDS = 24 * 3600.0
# Past a full page of either, the relay can't say. The driveway sees ~650 car visits a day.
CAR_ZONE_PAGE = 200
# Frigate answers 404 for a car it is still tracking (the row is written later), so a crop's event
# is only given up on once it began this long ago.
STREET_GONE_AFTER_SECONDS = 3600.0
RETRAIN_AFTER = int(os.environ.get("RETRAIN_AFTER", "60"))
RETRAIN_EVERY_SECONDS = 24 * 3600.0
# A retrain Frigate refused (or never answered) is asked for again after this long, not every round.
RETRAIN_RETRY_SECONDS = 3600.0

OLLAMA = os.environ.get("OLLAMA_URL", "").rstrip("/")
# The Instruct build: plain `qwen3-vl:4b` is the Thinking one, which spends seconds reasoning first.
VLM_MODEL = os.environ.get("VLM_MODEL", "qwen3-vl:4b-instruct")
# {"andrews_tesla": {"make": "tesla", "colour": ["blue", "black"]}, ...}: how each household car
# looks, keyed by the classifier's category. `colour` is one colour or a list of the ones a camera
# might see it as (dark blue reads as black at dusk). A car missing here is never judged.
HOUSEHOLD_CARS: dict[str, dict[str, str]] = json.loads(os.environ.get("HOUSEHOLD_CARS", "{}") or "{}")
# Below a person's 1.0 (the app's tags), above nothing the classifier needs to beat.
VLM_SCORE = 0.9
# A car still in view is judged once it has been tracked this long: the classifier's attempts
# are over within seconds, and a parked car's event can stay open for hours.
VLM_SETTLE_SECONDS = 60.0
# Recordings reach disk a segment behind; past this age an event with no frame is given up on.
VLM_GIVE_UP_SECONDS = 15 * 60.0
VLM_CROP_EDGE = 640
# A car takes ~1.5 s on the GPU; past this, something is wrong with where the model runs.
VLM_SLOW_SECONDS = 10.0
CAR_CHECK_SECONDS = 30.0

COLOURS = ["white", "black", "grey", "silver", "red", "blue", "green", "brown", "beige", "gold", "yellow", "orange", "purple", "unknown"]
MAKES = [
    "tesla", "toyota", "honda", "ford", "chevrolet", "nissan", "hyundai", "kia", "subaru", "mazda", "volkswagen",
    "bmw", "mercedes", "audi", "lexus", "jeep", "ram", "gmc", "dodge", "rivian", "volvo", "porsche", "mini", "other", "unknown",
]
BODIES = ["sedan", "suv", "hatchback", "pickup", "van", "minivan", "coupe", "wagon", "convertible", "motorcycle", "truck", "other"]
DELIVERY = ["none", "amazon", "ups", "fedex", "usps", "dhl", "other"]
# Grey and silver are one colour to a camera at dusk.
COLOUR_GROUPS = {"silver": "grey"}

VLM_SCHEMA = {
    "type": "object",
    "properties": {
        "colour": {"type": "string", "enum": COLOURS},
        "make": {"type": "string", "enum": MAKES},
        "model": {"type": "string"},
        "body": {"type": "string", "enum": BODIES},
        "delivery": {"type": "string", "enum": DELIVERY},
    },
    "required": ["colour", "make", "model", "body", "delivery"],
}
VLM_PROMPT = (
    "This is a crop from a home security camera. Describe the vehicle in the centre of the picture. "
    "If the picture is black-and-white infrared night footage, answer colour \"unknown\". "
    "Answer make \"unknown\" unless a badge or an unmistakable shape shows it. "
    "model is the model name if you can tell (\"Model Y\", \"Camry\"), else an empty string. "
    "delivery is the company if it is a marked delivery vehicle, else \"none\"."
)


def train_crop_event(file_name: str) -> str | None:
    """The event a queued crop belongs to: Frigate names them `<event id>-<frame time>-<label>-<score>.webp`."""
    parts = file_name.split("-")
    if len(parts) < 5 or not EVENT_ID.fullmatch(f"{parts[0]}-{parts[1]}"):
        return None
    return f"{parts[0]}-{parts[1]}"


def path_travel(event: dict[str, Any]) -> float:
    """How far apart the two most distant points of the object's path are, in frame fractions."""
    points = path_points(event)
    return max((((ax - bx) ** 2 + (ay - by) ** 2) ** 0.5 for ax, ay in points for bx, by in points), default=0.0)


def distance_to_polygon(point: tuple[float, float], polygon: list[tuple[float, float]]) -> float:
    """0 inside the polygon, else the distance to its nearest edge, in frame fractions."""
    x, y = point
    inside = False
    nearest = float("inf")
    for (ax, ay), (bx, by) in zip(polygon, polygon[1:] + polygon[:1]):
        if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
            inside = not inside
        dx, dy = bx - ax, by - ay
        t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
        nearest = min(nearest, ((x - ax - t * dx) ** 2 + (y - ay - t * dy) ** 2) ** 0.5)
    return 0.0 if inside else nearest


def is_passing_street_car(event: dict[str, Any], zones_for_car: list[str], polygons: list[list[tuple[float, float]]] = ()) -> bool:
    """
    A finished car event that is surely not one of ours: its camera has a zone for cars (so "in no
    zone" means "not in the driveway"), it entered none, it travelled across the frame, and no
    point of its path came within STREET_ZONE_MARGIN of a car zone's outline. That last is not the
    same as Frigate's tag: a zone only counts an object that stays in it for `inertia` frames, so a
    car pulling briskly out of the driveway (Andrew's Tesla, 2026-09-24 15:40) never gets tagged.
    """
    return (
        event.get("label") == "car"
        and event.get("end_time") is not None
        and bool(zones_for_car)
        and not any(z in zones_for_car for z in event.get("zones") or [])
        and path_travel(event) >= STREET_MIN_TRAVEL
        and not any(distance_to_polygon(p, poly) <= STREET_ZONE_MARGIN for p in path_points(event) for poly in polygons)
    )


def clear_window(event: dict[str, Any]) -> tuple[float, float]:
    """The stretch around a street car in which a car-zone car arriving or leaving makes it possibly ours."""
    return event["start_time"] - STREET_CLEAR_SECONDS, (event.get("end_time") or event["start_time"]) + STREET_CLEAR_SECONDS


def car_zone_came_or_went(event: dict[str, Any], zones_for_car: list[str], others: list[dict[str, Any]]) -> bool:
    """
    Did any of `others` arrive in or leave a car zone within the street car's `clear_window`? A car
    parked right through it did neither, so a driveway that is never empty still lets crops in.
    """
    start, end = clear_window(event)
    return any(
        e.get("id") != event.get("id")
        and set(e.get("zones") or []) & set(zones_for_car)
        and any(t is not None and start <= float(t) <= end for t in (e.get("start_time"), e.get("end_time")))
        for e in others
    )


def car_zone_car_nearby(event: dict[str, Any], zones_for_car: list[str]) -> bool:
    """Did a car arrive in or leave a car zone on the same camera within STREET_CLEAR_SECONDS of this one? True when Frigate can't say."""
    start, end = clear_window(event)
    base = {"camera": event["camera"], "label": "car", "zones": ",".join(zones_for_car), "limit": CAR_ZONE_PAGE}
    looks = [
        {"after": start - CAR_ZONE_RECENT_SECONDS, "before": end},
        {"after": start - CAR_ZONE_LONGEST_VISIT_SECONDS, "before": start - CAR_ZONE_RECENT_SECONDS, "min_length": CAR_ZONE_RECENT_SECONDS},
    ]
    others = []
    try:
        for params in looks:
            r = requests.get(f"{FRIGATE}/api/events", params={**base, **params}, timeout=10)
            r.raise_for_status()
            page = r.json()
            if len(page) >= CAR_ZONE_PAGE:
                log.warning("car-zone lookup for %s: a full page, can't say", event.get("id"))
                return True
            others += page
    except Exception as e:
        log.warning("car-zone lookup for %s failed: %s", event.get("id"), e)
        return True
    return car_zone_came_or_went(event, zones_for_car, others)


def dataset_count(model: str, category: str) -> int:
    folder = os.path.join(CLIPS_DIR, model, "dataset", category)
    return len(os.listdir(folder)) if os.path.isdir(folder) else 0


def filed_since(kind: str, since: float) -> int:
    return with_db(lambda c: c.execute("SELECT COUNT(*) FROM car_checks WHERE kind=? AND verdict='filed' AND at>=?", (kind, since)).fetchone()[0])


def record_check(event_id: str, kind: str, verdict: str, detail: str = "") -> None:
    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO car_checks VALUES (?,?,?,?,?)", (event_id, kind, time.time(), verdict, detail)), c.commit()))


def checked(event_id: str, kind: str) -> bool:
    return with_db(lambda c: c.execute("SELECT 1 FROM car_checks WHERE event_id=? AND kind=?", (event_id, kind)).fetchone()) is not None


def file_street_crops() -> None:
    """Moves queued crops of passing street cars into `none`, within the hourly and total caps."""
    model = CAR_CLASSIFIER
    room = STREET_NONE_MAX - dataset_count(model, "none")
    budget = STREET_NONE_PER_HOUR - filed_since("street", time.time() - 3600)
    if room <= 0 or budget <= 0:
        return
    train = os.path.join(CLIPS_DIR, model, "train")
    by_event: dict[str, list[str]] = {}
    for name in sorted(os.listdir(train)) if os.path.isdir(train) else []:
        event_id = train_crop_event(name)
        if event_id and name.endswith(".webp"):
            by_event.setdefault(event_id, []).append(name)
    zones = car_zones()
    unreachable = 0
    for event_id, files in by_event.items():
        if budget <= 0 or room <= 0:
            break
        if checked(event_id, "street"):
            continue
        try:
            event = fetch_event(event_id)
        except Exception:
            unreachable += 1  # look again next round
            continue
        if event is None:
            if time.time() - float(event_id.split("-")[0]) >= STREET_GONE_AFTER_SECONDS:
                record_check(event_id, "street", "gone")  # Frigate never kept it
            continue
        if event.get("end_time") is None:
            continue  # still going: look again later
        zones_for_car = zones.get(event.get("camera", ""), [])
        if not is_passing_street_car(event, zones_for_car, car_zone_polygons().get(event.get("camera", ""), [])):
            record_check(event_id, "street", "not-street")
            continue
        if car_zone_car_nearby(event, zones_for_car):
            record_check(event_id, "street", "near-car-zone")
            continue
        moved = 0
        for name in files[:min(STREET_CROPS_PER_EVENT, room)]:
            r = requests.post(f"{FRIGATE}/api/classification/{model}/dataset/categorize",
                              json={"category": "none", "training_file": name}, timeout=10)
            moved += r.ok
        record_check(event_id, "street", "filed" if moved else "failed", str(moved))
        budget -= 1
        room -= moved
        log.info("street car %s: %d crop(s) filed as %s/none (named %s)", event_id, moved, model, event.get("sub_label"))
    if unreachable:
        log.warning("street crops: %d event(s) couldn't be looked up, trying again next round", unreachable)


def maybe_retrain() -> None:
    """Retrains the classifier once a day, when at least RETRAIN_AFTER street crops went in since the last one."""
    now = time.time()
    last = state_get("car_retrain_at") or 0.0
    if now - last < RETRAIN_EVERY_SECONDS or filed_since("street", last) < RETRAIN_AFTER:
        return
    if now - (state_get("car_retrain_tried_at") or 0.0) < RETRAIN_RETRY_SECONDS:
        return
    state_set("car_retrain_tried_at", now)
    r = requests.post(f"{FRIGATE}/api/classification/{CAR_CLASSIFIER}/train", timeout=30)
    if not r.ok:
        log.warning("retrain of %s refused, trying again in %.0f min: %s %s", CAR_CLASSIFIER, RETRAIN_RETRY_SECONDS / 60, r.status_code, r.text[:200])
        return
    state_set("car_retrain_at", now)
    log.info("retrain of %s requested after %d new street crops: %s %s", CAR_CLASSIFIER, filed_since("street", last), r.status_code, r.text[:200])


def sub_label_of(event: dict[str, Any]) -> tuple[str | None, float | None]:
    """The event's name and how sure its giver was; Frigate has sent `sub_label` both as a string and as `[name, score]`."""
    sub = event.get("sub_label")
    score = (event.get("data") or {}).get("sub_label_score")
    if isinstance(sub, list):
        sub, score = (sub + [None, None])[:2]
    return (sub or None), (float(score) if score is not None else None)


def second_opinion_due(event: dict[str, Any], zones_for_car: list[str], now: float) -> bool:
    """A car in a car zone, done or settled, whose name no person gave."""
    _, score = sub_label_of(event)
    return (
        event.get("label") == "car"
        and any(z in zones_for_car for z in event.get("zones") or [])
        and (event.get("end_time") is not None or now - float(event.get("start_time") or now) >= VLM_SETTLE_SECONDS)
        and (score is None or score < 1.0)
    )


def last_sighting(event: dict[str, Any]) -> tuple[float, tuple[float, float, float, float]] | None:
    """
    When and where to look at the car: the time of its last path point, and its box moved so its
    bottom centre sits on that point (the path is where it ended up; the box, from its best frame,
    is how big it is). None without a box.
    """
    box = (event.get("data") or {}).get("box")
    if not box or len(box) < 4 or box[2] <= 0 or box[3] <= 0:
        return None
    w, h = float(box[2]), float(box[3])
    samples = [s for s in (event.get("data") or {}).get("path_data") or [] if isinstance(s, list) and len(s) >= 2]
    if samples:
        (fx, fy), t = samples[-1][0], float(samples[-1][1])
    else:
        fx, fy, t = float(box[0]) + w / 2, float(box[1]) + h, float(event.get("start_time") or 0)
    return t, (fx - w / 2, fy - h, w, h)


def vlm_crop_box(box: tuple[float, float, float, float], margin: float = 0.25) -> tuple[float, float, float, float]:
    """The box grown by `margin` of its size on every side and kept inside the frame: room for a box from another frame."""
    x, y, w, h = box
    left, top = max(0.0, x - w * margin), max(0.0, y - h * margin)
    right, bottom = min(1.0, x + w * (1 + margin)), min(1.0, y + h * (1 + margin))
    return left, top, right - left, bottom - top


def household_matches(description: dict[str, str], cars: dict[str, dict[str, str]]) -> list[str]:
    """The household cars the description fits. Unknown colour or make (infrared, no badge) rules nothing out."""
    def group(colour: str) -> str:
        return COLOUR_GROUPS.get(colour, colour)
    colour, make = description.get("colour", "unknown"), description.get("make", "unknown")
    fits = []
    for name, looks in cars.items():
        if looks.get("make") and make not in ("unknown", "other") and make != looks["make"]:
            continue
        wanted = looks.get("colour") or []
        wanted = [wanted] if isinstance(wanted, str) else wanted
        if wanted and colour != "unknown" and group(colour) not in {group(c) for c in wanted}:
            continue
        fits.append(name)
    return fits


def second_opinion_verdict(name: str | None, matches: list[str], cars: dict[str, dict[str, str]], make: str = "unknown") -> tuple[str, str | None]:
    """
    What to do with the classifier's name given the cars the picture fits: ("keep", name),
    ("relabel", other name) or ("clear", None). An unnamed car, or a name with no description to
    check it against, is kept as it is — this only vetoes and corrects. A wrong name is replaced
    only by the one household car that fits *and* whose make the model read off the picture;
    fitting on colour alone ("some white car") is not enough to call it anyone's.
    """
    if not name or name.lower() in ("none", "unknown") or name not in cars:
        return "keep", name
    if name in matches:
        return "keep", name
    if len(matches) == 1 and cars[matches[0]].get("make") == make:
        return "relabel", matches[0]
    return "clear", None


def describe_car(jpeg: bytes) -> dict[str, str]:
    import base64

    r = requests.post(f"{OLLAMA}/api/chat", json={
        "model": VLM_MODEL,
        "messages": [{"role": "user", "content": VLM_PROMPT, "images": [base64.b64encode(jpeg).decode()]}],
        "format": VLM_SCHEMA,
        "stream": False,
        "keep_alive": "24h",
        # num_gpu 99: every layer on the GPU. On 2026-09-25 Ollama's first load put the whole model
        # on the CPU with 6.9 GB of VRAM free (37 s a car instead of 1.5 s); this makes that a
        # loud failure rather than a quiet slowdown.
        "options": {"temperature": 0, "num_ctx": 4096, "num_gpu": 99},
    }, timeout=120)
    r.raise_for_status()
    return json.loads(r.json()["message"]["content"])


def car_picture(event: dict[str, Any]) -> bytes | None:
    """The car cut out of the camera's full-resolution recording at its last sighting, long edge VLM_CROP_EDGE, as JPEG."""
    from io import BytesIO

    from PIL import Image

    sighting = last_sighting(event)
    if sighting is None:
        return None
    t, box = sighting
    r = requests.get(f"{FRIGATE}/api/{event['camera']}/recordings/{t:.1f}/snapshot.jpg", timeout=30)
    if not r.ok:
        return None
    frame = Image.open(BytesIO(r.content)).convert("RGB")
    x, y, w, h = vlm_crop_box(box)
    crop = frame.crop((round(x * frame.width), round(y * frame.height), round((x + w) * frame.width), round((y + h) * frame.height)))
    scale = VLM_CROP_EDGE / max(crop.width, crop.height)
    crop = crop.resize((max(1, round(crop.width * scale)), max(1, round(crop.height * scale))), Image.LANCZOS)
    out = BytesIO()
    crop.save(out, format="JPEG", quality=92)
    return out.getvalue()


_vlm_pulling = threading.Event()


def pull_vlm_model() -> None:
    """Downloads VLM_MODEL into Ollama. Hours over the box's Wi-Fi, so on a thread of its own; Ollama resumes a broken pull."""
    try:
        log.info("pulling %s into Ollama", VLM_MODEL)
        r = requests.post(f"{OLLAMA}/api/pull", json={"model": VLM_MODEL, "stream": False}, timeout=None)
        log.info("pull of %s: %s %s", VLM_MODEL, r.status_code, r.text[:200])
    except Exception as e:
        log.warning("pull of %s failed: %s", VLM_MODEL, e)
    finally:
        _vlm_pulling.clear()


def ensure_vlm_model() -> bool:
    """True once Ollama has VLM_MODEL; until then starts one background pull at a time. False while Ollama can't be reached."""
    try:
        tags = requests.get(f"{OLLAMA}/api/tags", timeout=10).json().get("models") or []
    except Exception as e:
        log.warning("Ollama at %s unavailable: %s", OLLAMA, e)
        return False
    if any(m.get("name") == VLM_MODEL or m.get("model") == VLM_MODEL for m in tags):
        return True
    if not _vlm_pulling.is_set():
        _vlm_pulling.set()
        threading.Thread(target=pull_vlm_model, name="vlm-pull", daemon=True).start()
    return False


def second_opinions() -> None:
    """Looks again at each settled car in a car zone from the last hour, once."""
    now = time.time()
    zones = car_zones()
    for camera, zones_for_car in zones.items():
        if not zones_for_car:
            continue
        r = requests.get(f"{FRIGATE}/api/events", params={
            "camera": camera, "label": "car", "zones": ",".join(zones_for_car), "after": now - 3600, "limit": 50,
        }, timeout=10)
        r.raise_for_status()
        for summary in r.json():
            event_id = summary.get("id", "")
            if checked(event_id, "vlm"):
                continue
            event = event_detail(event_id) or summary
            if not second_opinion_due(event, zones_for_car, now):
                continue
            picture = car_picture(event)
            if picture is None:
                if now - float(event.get("start_time") or now) > VLM_GIVE_UP_SECONDS:
                    record_check(event_id, "vlm", "no-frame")
                continue  # the recording isn't on disk yet; next round
            started = time.time()
            description = describe_car(picture)
            # The model can take a minute or two: judge the name the event has now, so a person's tag
            # given meanwhile is left alone. Frigate has no conditional update, so milliseconds remain.
            try:
                event = fetch_event(event_id)
            except Exception as e:
                log.warning("car %s: lookup after the model failed, trying again next round: %s", event_id, e)
                continue
            if event is None:
                continue  # deleted meanwhile
            name, score = sub_label_of(event)
            if score is not None and score >= 1.0:
                record_check(event_id, "vlm", "person", json.dumps({"was": name, "score": score, "saw": description}))
                continue
            matches = household_matches(description, HOUSEHOLD_CARS)
            action, new_name = second_opinion_verdict(name, matches, HOUSEHOLD_CARS, description.get("make", "unknown"))
            summary_text = " ".join(v for v in (description.get("colour"), description.get("make"), description.get("model"), description.get("body")) if v and v not in ("unknown", "other"))
            if description.get("delivery") not in (None, "none"):
                summary_text += f" ({description['delivery']})"
            try:
                if action != "keep":
                    requests.post(f"{FRIGATE}/api/events/{event_id}/sub_label",
                                  json={"subLabel": new_name or "", "subLabelScore": VLM_SCORE if new_name else None}, timeout=10).raise_for_status()
                if summary_text.strip():
                    requests.post(f"{FRIGATE}/api/events/{event_id}/description", json={"description": summary_text.strip()}, timeout=10).raise_for_status()
            except Exception as e:
                log.warning("car %s: Frigate didn't take the verdict (%s %s), trying again next round: %s", event_id, action, new_name or "", e)
                continue
            record_check(event_id, "vlm", action, json.dumps({"was": name, "score": score, "now": new_name, "saw": description}))
            took = time.time() - started
            log.info("car %s on %s: classifier %s (%s), model saw %s in %.1fs -> %s %s",
                     event_id, camera, name, score, summary_text, took, action, new_name or "")
            if took > VLM_SLOW_SECONDS:
                log.warning("vision model took %.0fs for one car: is Ollama running on the CPU? (docker logs ollama | grep load_tensors)", took)


# While Ollama is still downloading (hours on the box's Wi-Fi), ask again this often rather than every round.
VLM_RETRY_SECONDS = 600.0


def car_check_forever() -> None:
    vlm_ready = False
    vlm_tried_at = 0.0
    while True:
        try:
            file_street_crops()
            maybe_retrain()
        except Exception as e:
            log.warning("street crops: %s", e)
        if OLLAMA and HOUSEHOLD_CARS:
            try:
                if not vlm_ready and time.time() - vlm_tried_at >= VLM_RETRY_SECONDS:
                    vlm_tried_at = time.time()
                    vlm_ready = ensure_vlm_model()
                if vlm_ready:
                    second_opinions()
            except Exception as e:
                log.warning("second opinion: %s", e)
        time.sleep(CAR_CHECK_SECONDS)


# ---------------------------------------------------------------- boot report

# The box restarts now and then (a power cut, a kernel update, someone at the console), and until
# 2026-09-25 the only way to learn how it came back was to notice the app had gone quiet. So once
# per boot the relay looks around a few minutes in, when everything that is going to start has,
# and pushes one line: all back, or what isn't. `/proc/uptime` in a container is the host's, so a
# relay restart on its own (a deploy) is told apart from a reboot, and the boot is remembered in
# `state` so a relay restarted later in the same boot doesn't report it twice.
BOOT_REPORT_AFTER_SECONDS = 180.0
# A relay that comes up later than this into a boot was restarted by itself, not by the boot.
BOOT_REPORT_WINDOW_SECONDS = 900.0
# The recording drive is 3.7 TB; the boot disk Frigate would fall back to writing on is 441 GB.
RECORDING_DRIVE_MIN_MB = 1_000_000
# go2rtc's WebRTC port. After the 2026-09-25 13:23 reboot its WebRTC module never started (one
# address it tried to bind wasn't ready, and that aborts the lot), so the app fell back to HLS for
# three hours while every other check was green. The relay shares the host's network, so the
# host's UDP sockets are in its /proc/net. Empty turns the check off.
WEBRTC_UDP_PORT = os.environ.get("WEBRTC_UDP_PORT", "8555")


def udp_port_listening(proc_net_udp: str, port: int) -> bool:
    """Whether a /proc/net/udp (or udp6) table has a socket bound to [port]: local address "ADDR:PORT", port in hex."""
    wanted = f"{port:04X}"
    for line in proc_net_udp.splitlines()[1:]:
        fields = line.split()
        if len(fields) > 1 and fields[1].rsplit(":", 1)[-1].upper() == wanted:
            return True
    return False


def household_zone() -> ZoneInfo | None:
    """The time zone most registered phones say they are in; None when none says."""
    zones = with_db(lambda c: [r[0] for r in c.execute("SELECT tz FROM devices WHERE tz IS NOT NULL AND tz != ''")])
    for name in sorted(set(zones), key=zones.count, reverse=True):
        try:
            return ZoneInfo(name)
        except Exception:
            continue
    return None


def clock_text(epoch: float, zone: ZoneInfo | None = None) -> str:
    """ "5:33 PM" in [zone] (UTC when unknown, and then says so). """
    moment = datetime.fromtimestamp(epoch, zone or timezone.utc)
    text = moment.strftime("%I:%M %p").lstrip("0")
    return text if zone else text + " UTC"


def host_uptime() -> float:
    with open("/proc/uptime") as f:
        return float(f.read().split()[0])


def boot_health() -> dict[str, Any]:
    """What came back: Frigate's cameras and detector, where recordings are going, and the vision model."""
    health: dict[str, Any] = {"frigate": False, "cameras": {}, "recording_mb": None, "vlm": None, "webrtc": None}
    try:
        stats = requests.get(f"{FRIGATE}/api/stats", timeout=10).json()
        health["frigate"] = True
        health["cameras"] = {name: float(cam.get("camera_fps") or 0) for name, cam in (stats.get("cameras") or {}).items()}
        storage = (stats.get("service") or {}).get("storage") or {}
        recordings = storage.get("/media/frigate/recordings") or {}
        health["recording_mb"] = recordings.get("total")
    except Exception as e:
        log.warning("boot report: Frigate stats unavailable: %s", e)
    if WEBRTC_UDP_PORT:
        tables = ""
        for path in ("/proc/net/udp", "/proc/net/udp6"):
            try:
                with open(path) as f:
                    tables += f.read()
            except OSError:
                pass
        health["webrtc"] = udp_port_listening(tables, int(WEBRTC_UDP_PORT))
    if OLLAMA:
        try:
            tags = requests.get(f"{OLLAMA}/api/tags", timeout=10).json().get("models") or []
            health["vlm"] = any(m.get("name") == VLM_MODEL or m.get("model") == VLM_MODEL for m in tags)
        except Exception:
            health["vlm"] = False
    return health


def boot_report_text(health: dict[str, Any], booted_at: str) -> tuple[str, str]:
    """("Server restarted", "Back since 5:33 PM · all 3 cameras · recording drive OK") — or the problems, first."""
    problems = []
    if not health.get("frigate"):
        problems.append("Frigate isn't answering")
    cameras = health.get("cameras") or {}
    down = sorted(camera_name(name) for name, fps in cameras.items() if fps <= 0)
    if down:
        problems.append(f"no video from {', '.join(down)}")
    total = health.get("recording_mb")
    if health.get("frigate") and (total is None or total < RECORDING_DRIVE_MIN_MB):
        problems.append("recordings aren't on the 4 TB drive")
    if health.get("frigate") and health.get("webrtc") is False:
        problems.append("live video is on the slower HLS fallback (WebRTC didn't start)")
    if health.get("vlm") is False:
        problems.append("vision model not loaded")
    if problems:
        return "Server restarted with problems", f"Back since {booted_at}: " + "; ".join(problems)
    parts = [f"Back since {booted_at}", f"all {len(cameras)} cameras" if cameras else "no cameras configured", "recording drive OK"]
    return "Server restarted", " · ".join(parts)


def boot_report() -> None:
    """Waits until BOOT_REPORT_AFTER_SECONDS into the boot, then pushes the report once per boot."""
    try:
        uptime = host_uptime()
    except Exception as e:
        log.warning("boot report: no uptime: %s", e)
        return
    booted = time.time() - uptime
    if uptime > BOOT_REPORT_WINDOW_SECONDS:
        return  # the relay restarted on its own; the box has been up a while
    last = state_get("boot_reported")
    if last is not None and abs(float(last) - booted) < 120:
        return
    time.sleep(max(0.0, BOOT_REPORT_AFTER_SECONDS - uptime))
    title, body = boot_report_text(boot_health(), clock_text(booted, household_zone()))
    result = broadcast(title, body, {"review_id": f"boot-{int(booted)}", "system": "boot"})
    state_set("boot_reported", booted)
    log.info("boot report: %s | %s | %s", title, body, result)


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
    # Quiet hours, minutes after the phone's local midnight; both absent while they're off.
    quiet_start: int | None = None
    quiet_end: int | None = None
    # "Only when everyone's away": ordinary alerts skip this phone; Away alerts still reach it.
    only_away: bool = False
    # The phone's clock, for reading quiet hours: its IANA zone, and its UTC offset in minutes as a fallback.
    tz: str | None = None
    utc_offset: int | None = None


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
    if CAR_CLASSIFIER:
        threading.Thread(target=car_check_forever, name="car-check", daemon=True).start()
    threading.Thread(target=boot_report, name="boot-report", daemon=True).start()
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
            "INSERT INTO devices (device_id, token, platform, name, created, last_seen, quiet_familiar, build, secret,"
            " quiet_start, quiet_end, only_away, tz, utc_offset)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            " ON CONFLICT(device_id) DO UPDATE SET token=excluded.token, platform=excluded.platform, name=excluded.name,"
            " last_seen=excluded.last_seen, quiet_familiar=excluded.quiet_familiar, build=excluded.build, secret=excluded.secret,"
            " quiet_start=excluded.quiet_start, quiet_end=excluded.quiet_end, only_away=excluded.only_away,"
            " tz=excluded.tz, utc_offset=excluded.utc_offset",
            (
                device_id, device.token, device.platform, device.name, now, now, int(device.quiet_familiar), device.build, secret,
                device.quiet_start, device.quiet_end, int(device.only_away), device.tz, device.utc_offset,
            ),
        )
        c.commit()
        return secret

    secret = with_db(upsert)
    log.info(
        "device registered by %s: %s (%s %s, push=%s, strangers only=%s, quiet=%s-%s %s, only away=%s, counts for away=%s)",
        user, device.name or "unnamed", device.platform, device.build, device.token is not None, device.quiet_familiar,
        device.quiet_start, device.quiet_end, device.tz, device.only_away, counts_for_away(device.platform, device.build),
    )
    return {"ok": True, "device_id": device_id, "secret": secret}


@app.delete("/devices/{ident}")
def unregister(ident: str, request: Request) -> dict[str, Any]:
    """
    Forgets an install. Its own secret may remove itself; a signed-in user's cookie may remove any
    device — how Settings clears out old installs. One that is still installed comes back the next
    time it connects and re-registers.
    """
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
    if ident:
        # A phone asking about itself is a phone in use: the app polls this about once a minute
        # while it's open, so `last_seen` separates the household's phones from old installs.
        with_db(lambda c: (c.execute("UPDATE devices SET last_seen=? WHERE device_id=? OR token=?", (time.time(), ident, ident)), c.commit()))
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


# What a phone may fetch about an event through the relay: the pictures a pushed notification
# shows. Only these, only by a well-formed Frigate event id, so the route can't be walked to the
# rest of Frigate's unauthenticated internal API.
EVENT_MEDIA = {"thumbnail.jpg": "image/jpeg", "preview.gif": "image/gif"}
EVENT_ID = re.compile(r"[0-9]+\.[0-9]+-[a-z0-9]+")


def event_media_url(event_id: str, name: str) -> str | None:
    """Frigate's internal URL for one of an event's `EVENT_MEDIA`, or None when either part isn't one."""
    if name not in EVENT_MEDIA or not EVENT_ID.fullmatch(event_id):
        return None
    return f"{FRIGATE}/api/events/{event_id}/{name}"


@app.get("/events/{event_id}/{name}")
def event_media(event_id: str, name: str, request: Request, device: str | None = None) -> Response:
    """
    An event's thumbnail or animated preview, for the notification a push became. The phone
    that got the push may have no Frigate session — it was woken from the background — so it
    proves itself with its device secret, like presence does; a session cookie works too.
    """
    url = event_media_url(event_id, name)
    if url is None:
        raise HTTPException(status_code=404, detail="No such media")
    authenticate(request, device)
    try:
        r = requests.get(url, timeout=20)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Frigate unreachable: {e}")
    if r.status_code != 200:
        # Frigate 404s a preview until the event has frames for it; the app asks again.
        raise HTTPException(status_code=404, detail=f"Frigate answered {r.status_code}")
    return Response(content=r.content, media_type=EVENT_MEDIA[name], headers={"Cache-Control": "private, max-age=3600"})


# A classifier or category name as Frigate keeps it on disk: one path segment, never `.` or `..`.
# Hyphens are allowed because the dataset already has a category with one (`in-laws_mercedes`).
DATASET_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
# A full-resolution detect frame is well under a megabyte; this only stops a runaway upload.
MAX_EXAMPLE_BYTES = 8 * 1024 * 1024


def classification_crop(frame_w: int, frame_h: int, x: float, y: float, w: float, h: float) -> tuple[int, int, int, int] | None:
    """
    The part of a frame Frigate's object classifier would have looked at for a box at `x, y, w, h`
    (fractions of the frame), as pixel `left, top, right, bottom`. It is Frigate's own
    `calculate_region(..., model_size=longest edge, multiplier=1.0)`: a square as big as the box's
    longer side, centred on the box and pushed back inside the frame, then cut off by the frame's
    edge when the square is taller or wider than the frame. A hand-drawn example framed any other
    way would teach the model a picture it never gets shown. None for a box with no area.
    """
    # Whole pixels, as Frigate's own boxes are: 180/720 of a frame must be 180 px, not 179.99.
    left, top = round(x * frame_w), round(y * frame_h)
    right, bottom = round((x + w) * frame_w), round((y + h) * frame_h)
    if right - left < 1 or bottom - top < 1:
        return None
    size = int(max(right - left, bottom - top) // 4 * 4)
    size = max(size, 4)
    x_offset = int((right - left) / 2.0 + left - size / 2.0)
    x_offset = 0 if x_offset < 0 else min(x_offset, max(0, frame_w - size))
    y_offset = int((bottom - top) / 2.0 + top - size / 2.0)
    y_offset = 0 if y_offset < 0 else min(y_offset, max(0, frame_h - size))
    return x_offset, y_offset, min(frame_w, x_offset + size), min(frame_h, y_offset + size)


def dataset_file_name(category: str, now: float) -> str:
    """Named the way Frigate names a crop it files (`categorize`), so the two are indistinguishable."""
    random_id = "".join(secrets.choice("abcdefghijklmnopqrstuvwxyz0123456789") for _ in range(6))
    return f"{category}-{now}-{random_id}.png"


def save_classification_example(model: str, category: str, jpeg: bytes, box: tuple[float, float, float, float]) -> str:
    """Cuts [box] out of [jpeg] as Frigate would and writes it into the model's dataset; answers the file name."""
    from io import BytesIO

    from PIL import Image

    model_dir = os.path.join(CLIPS_DIR, model)
    if not os.path.isdir(model_dir):
        raise HTTPException(status_code=404, detail=f"No classifier folder for {model}")
    try:
        frame = Image.open(BytesIO(jpeg))
        frame.load()
    except Exception:
        raise HTTPException(status_code=400, detail="Body is not an image")
    region = classification_crop(frame.width, frame.height, *box)
    if region is None:
        raise HTTPException(status_code=400, detail="Box has no area")
    folder = os.path.join(model_dir, "dataset", category)
    os.makedirs(folder, exist_ok=True)
    name = dataset_file_name(category, time.time())
    frame.convert("RGB").crop(region).save(os.path.join(folder, name), format="PNG")
    return name


@app.post("/classification/{model}/dataset/{category}")
async def add_classification_example(
    model: str, category: str, request: Request, x: float, y: float, w: float, h: float,
) -> dict[str, Any]:
    """
    Adds one example to a classifier's dataset from a frame the app shows: the body is the JPEG
    as the app received it from Frigate, and `x, y, w, h` the car's box on it as fractions — so the
    crop is of exactly the frame the person boxed the car on, not whatever the camera sees by the
    time the request lands. A user action, so the session cookie. Training is the app's call.
    """
    from fastapi.concurrency import run_in_threadpool

    if not DATASET_NAME.fullmatch(model) or not DATASET_NAME.fullmatch(category):
        raise HTTPException(status_code=400, detail="Bad model or category name")
    if not (0 <= x <= 1 and 0 <= y <= 1 and 0 < w <= 1 and 0 < h <= 1):
        raise HTTPException(status_code=400, detail="Box must be fractions of the frame")
    jpeg = await request.body()
    if not jpeg or len(jpeg) > MAX_EXAMPLE_BYTES:
        raise HTTPException(status_code=400, detail="Missing or oversized image")
    user = await run_in_threadpool(require_frigate_session, request)
    name = await run_in_threadpool(save_classification_example, model, category, jpeg, (x, y, w, h))
    log.info("classifier example by %s: %s/%s <- %s (box %.3f,%.3f %.3fx%.3f)", user, model, category, name, x, y, w, h)
    return {"ok": True, "file": name}



@app.post("/test")
def test_push(request: Request) -> dict[str, Any]:
    """Sends a sample alert to every registered phone, so the whole path can be checked from the app."""
    user = require_frigate_session(request)
    result = broadcast("Front Yard", "Test: Sarah's Tesla in the driveway", {"review_id": f"test-{int(time.time())}", "camera": "hikvision_1", "test": "1"}, test=True)
    log.info("test push by %s: %s", user, result)
    return {"ok": True, **result}
