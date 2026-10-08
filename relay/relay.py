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

Nor does every push make a sound. Frigate cuts one person or car in view into a new alert every
minute or so, so alerts on one camera close together are one *visit* with one notification, and
only its first alert, or one that brings something new to it, sounds; the rest update it quietly.
A household car doing its rounds, and a backlog found late, are quiet too — see `Visits`.

Cars are told by what they did. The relay remembers where each named car is parked (relay.db), so
a car Frigate re-detected without a name where Andrew's Tesla is parked is Andrew's Tesla, one
that stayed put is dropped like any still car, and one that went is "Andrew's Tesla left the
driveway" — see "vehicle memory". The local vision model checks those names against a picture
of each car kept on the box.
"""

import calendar
import hashlib
import html
import http.client
import json
import logging
import os
import re
import secrets
import socket
import sqlite3
from statistics import median
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable
from urllib.parse import parse_qs, urlencode
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


# Sub-labels that are not a name: the classifier's reserved "none" class (and the key "Not ours"
# would slug to) and Frigate's unknown-face marker. Mirrors the app's MomentVisits.NOT_A_NAME.
NOT_A_NAME = {"none", "not_ours", "unknown"}


def unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))


def review_labels(item: dict[str, Any]) -> list[str]:
    """The review's labels in order, once each; a classified object's "car-verified" counts as "car"."""
    return unique([str(o).removesuffix("-verified") for o in ((item.get("data") or {}).get("objects") or []) if o])


def review_names(item: dict[str, Any]) -> list[str]:
    """The names Frigate put to things in the review (faces, classified cars), in order, placeholders dropped."""
    return unique([s for s in ((item.get("data") or {}).get("sub_labels") or []) if s and s.lower() not in NOT_A_NAME])


def car_names(labels: list[str], names: list[str]) -> list[str]:
    """
    Which of a review's names are cars. A review's sub-labels don't say which object each belongs
    to, so: with no person in it every name is a car's; with a person, only the household's
    configured cars (HOUSEHOLD_CARS) are, and the rest are faces.
    """
    if "car" not in labels:  # the car classifier only names cars
        return []
    if "person" not in labels:
        return names
    return [n for n in names if n.lower() in HOUSEHOLD_CARS]


# The app's DetectionNames.kt rules for putting back the apostrophe a category key lost:
# `andrews_tesla` is "Andrew's Tesla", as the Moments feed says, not "Andrews Tesla".
VEHICLE_WORDS = {
    "car", "truck", "van", "minivan", "suv", "jeep", "pickup", "sedan", "wagon", "coupe", "hatchback", "convertible",
    "bike", "motorcycle", "scooter", "rv", "camper", "trailer", "boat", "model",
    "tesla", "mercedes", "benz", "bmw", "audi", "honda", "toyota", "ford", "chevy", "chevrolet", "subaru", "lexus",
    "acura", "nissan", "mazda", "hyundai", "kia", "volvo", "volkswagen", "vw", "porsche", "rivian", "polestar", "lucid",
    "prius", "civic", "camry", "corolla", "accord", "outback", "highlander", "tacoma", "cybertruck", "mini", "dodge",
    "ram", "gmc", "buick", "cadillac", "lincoln", "infiniti", "genesis", "jaguar", "range", "fiat", "mitsubishi",
}
UPPERCASE_WORDS = {"bmw", "suv", "vw", "gmc", "rv"}
NAMES_ENDING_IN_S = {
    "agnes", "alexis", "amos", "andreas", "carlos", "charles", "chris", "curtis", "cyrus", "dennis", "doris", "douglas",
    "elias", "ellis", "frances", "francis", "giles", "gladys", "gus", "hans", "iris", "james", "janis", "jess", "jesus",
    "jonas", "jules", "julius", "klaus", "les", "lewis", "lois", "louis", "lucas", "marcus", "markus", "mathias",
    "matthias", "miles", "morris", "moses", "myles", "niklas", "nicholas", "nicolas", "otis", "phyllis", "rhys", "ross",
    "russ", "silas", "thomas", "tobias", "travis", "wes", "willis",
}
PLURAL_OWNERS = {"parents", "grandparents", "kids", "neighbors", "neighbours", "inlaws"}


def possessive(owner: str) -> str | None:
    """"andrews" -> "Andrew's", "james" -> "James's", "parents" -> "Parents'"; None when it can't tell."""
    lower = owner.lower()
    if len(lower) < 3 or not lower.endswith("s"):
        return None
    capitalised = owner[:1].upper() + owner[1:]
    if lower in PLURAL_OWNERS:
        return f"{capitalised}'"
    if lower in NAMES_ENDING_IN_S:
        return f"{capitalised}'s"
    if lower[:-1] in NAMES_ENDING_IN_S:
        return f"{capitalised[:-1]}'s"
    if lower.endswith("ss"):
        return None
    return f"{capitalised[:-1]}'s"


def display_name(key: str) -> str:
    """"andrews_tesla" -> "Andrew's Tesla", "in-laws_mercedes" -> "In-Laws' Mercedes": the app's `subLabelDisplayName`."""
    known = SUB_LABEL_NAMES.get(key.lower())
    if known:
        return known
    words = [w for w in re.split(r"[_-]", key) if w]
    vehicle_at = next((i for i, w in enumerate(words) if w.lower() in VEHICLE_WORDS), -1)
    if vehicle_at >= 1:
        owner_at = vehicle_at - 1
        if words[owner_at].lower() == "laws" and owner_at >= 1 and words[owner_at - 1].lower() == "in":
            del words[owner_at]
            words[owner_at - 1] = "In-Laws'"
        else:
            words[owner_at] = possessive(words[owner_at]) or words[owner_at]
    return " ".join(w.upper() if w.lower() in UPPERCASE_WORDS else w[:1].upper() + w[1:] for w in words)


def subject_for(objects: list[str], sub_labels: list[str]) -> str:
    """
    "Person and Andrews Tesla", "Car and person", "Sarah": people first, each by name where
    Frigate gave one, then everything else, a named car in place of its bare label. People lead so
    a stranger walking past a parked household car is never announced as just the car.
    """
    labels = unique([str(o).removesuffix("-verified") for o in objects if o])
    names = unique([s for s in sub_labels if s and s.lower() not in NOT_A_NAME])
    cars = car_names(labels, names)
    faces = [n for n in names if n not in cars]
    people: list[tuple[str, bool]] = []
    things: list[tuple[str, bool]] = []
    for label in labels:
        if label == "person":
            people += [(display_name(n), True) for n in faces] or [("person", False)]
        elif label == "car" and cars:
            things += [(display_name(n), True) for n in cars]
        else:
            things.append((label, False))
    parts = list(dict.fromkeys(people + things)) or [("something", False)]
    words = [text if named or i else text.capitalize() for i, (text, named) in enumerate(parts)]
    return words[0] if len(words) == 1 else ", ".join(words[:-1]) + " and " + words[-1]


def zone_words(zone: str) -> str:
    return " ".join(w for w in zone.replace("-", "_").split("_") if w).lower()


def zone_phrase(zone: str) -> str:
    name = zone_words(zone)
    prep = "in" if any(e in name for e in ENCLOSED) else "on"
    return f"{prep} the {name}"


def told(subject: str, zone: str | None, movement: str | None = None) -> str:
    """
    "Andrew's Tesla left the driveway", "Car arrived in the driveway", "Person in the driveway":
    what a car did (see "vehicle memory"), or where the subject is when that isn't known.
    """
    where = zone_phrase(zone) if zone else ""
    if movement == "arrived":
        return f"{subject} arrived {where}".strip()
    if movement == "left":
        return f"{subject} left the {zone_words(zone)}" if zone else f"{subject} left"
    if movement == "moved":
        return f"{subject} moved {where}".strip()
    return f"{subject} {where}" if zone else f"{subject} detected"


def alert_zone(zones: list[str], required_zones: list[str]) -> str | None:
    """The zone that made this an alert wins; otherwise the last one the object reached."""
    return next((z for z in required_zones if z in zones), zones[-1] if zones else None)


def sentence(item: dict[str, Any], required_zones: list[str]) -> tuple[str, str]:
    data = item.get("data") or {}
    zone = alert_zone(data.get("zones") or [], required_zones)
    subject = subject_for(data.get("objects") or [], data.get("sub_labels") or [])
    return camera_name(item.get("camera", "")), told(subject, zone)


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
    # The vehicle memory (see "vehicle memory"). `vehicles`: each named car per camera, whether it
    # is parked there now (`here`), where (`spot`: its box's bottom centre and size) and since
    # when, and what it looks like to the vision model (`looks`). `vehicle_sightings`: each car
    # event in a car zone, the name it was given and how, and what it did.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS vehicles (camera TEXT, name TEXT, here INTEGER NOT NULL DEFAULT 0, spot TEXT,"
        " since REAL, last_seen REAL, event_id TEXT, looks TEXT, PRIMARY KEY (camera, name))"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS vehicle_sightings (event_id TEXT PRIMARY KEY, camera TEXT, name TEXT, how TEXT,"
        " movement TEXT, zone TEXT, start REAL, end REAL, final INTEGER NOT NULL DEFAULT 0, saw TEXT, noted TEXT, at REAL)"
    )
    # Google Home's account link and stream tokens (see "Google Home"), by hash: kind is "code",
    # "access", "refresh" or "stream"; subject the Frigate user, or for "stream" the camera.
    conn.execute("CREATE TABLE IF NOT EXISTS google_tokens (hash TEXT PRIMARY KEY, kind TEXT, subject TEXT, expires REAL)")
    # The names people gave events through the relay (see "person tags"), and since when it keeps them.
    conn.execute("CREATE TABLE IF NOT EXISTS person_tags (event_id TEXT PRIMARY KEY, name TEXT, by TEXT, at REAL)")
    conn.execute("INSERT OR IGNORE INTO state VALUES ('person_tags_since', ?)", (json.dumps(time.time()),))
    # Each household car's make, model, colour and plate (see "car profiles"); `by` who saved it, or "env".
    conn.execute("CREATE TABLE IF NOT EXISTS car_profiles (name TEXT PRIMARY KEY, make TEXT, model TEXT, colour TEXT, plate TEXT, updated REAL, by TEXT)")
    # Detections someone said are not a person (see "phantom people"): the event, and where on its
    # camera the thing that fooled the detector sits (`box`, JSON [x, y, w, h] in frame fractions).
    conn.execute("CREATE TABLE IF NOT EXISTS phantoms (event_id TEXT PRIMARY KEY, camera TEXT, box TEXT, by TEXT, at REAL)")
    # Each linked Tesla account's tokens (the refresh token is single use: every refresh hands back
    # the next), and which account each VIN is reached through (see "Tesla").
    conn.execute("CREATE TABLE IF NOT EXISTS tesla_accounts (account TEXT PRIMARY KEY, refresh TEXT, access TEXT, expires REAL, updated REAL)")
    conn.execute("CREATE TABLE IF NOT EXISTS tesla_vehicles (vin TEXT PRIMARY KEY, account TEXT, name TEXT, updated REAL)")
    # The institutions linked through Plaid (see "bank sync"): each one's read-only access token,
    # what Plaid can tell about it (`products`, a JSON list) and how its last sync went (`error`
    # is Plaid's error code). Then each of its accounts as last read, and what each investment
    # account holds.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_items (item_id TEXT PRIMARY KEY, access TEXT, institution_id TEXT, institution TEXT,"
        " products TEXT, linked_by TEXT, linked_at REAL, synced_at REAL, error TEXT, error_message TEXT, consent_expires TEXT)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_accounts (account_id TEXT PRIMARY KEY, item_id TEXT, name TEXT, official_name TEXT, mask TEXT,"
        " type TEXT, subtype TEXT, current REAL, available REAL, credit_limit REAL, currency TEXT, apr REAL, min_payment REAL, due TEXT, updated REAL)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_holdings (account_id TEXT, security_id TEXT, item_id TEXT, ticker TEXT, name TEXT, kind TEXT,"
        " quantity REAL, price REAL, price_as_of TEXT, value REAL, cost_basis REAL, currency TEXT, PRIMARY KEY (account_id, security_id))"
    )
    # The name each account's row goes by in the feed sheet, given once and kept while the account
    # is there: the budget sheet's formulas look accounts up by it.
    conn.execute("CREATE TABLE IF NOT EXISTS plaid_feed_keys (account_id TEXT PRIMARY KEY, key TEXT)")
    # What was bought on the credit cards (see "budget"): one row per transaction as Plaid last
    # told it. `date` is the day it was spent (`posted` the day it settled), `amount` positive for
    # a purchase, `kind` "spend", "refund" or "payment" (paying the card off, which is not
    # spending), `owner` whose card the bank says it was (rarely said), and `tag` the bucket
    # someone put it in by hand ("person:<name>" or "family"). A transaction Plaid took back keeps
    # its row for a while with `removed_at` set, so the posted one that replaces a pending one
    # can still take over its tag. Then the merchants someone said always belong to one bucket.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_transactions (txn_id TEXT PRIMARY KEY, item_id TEXT NOT NULL, account_id TEXT NOT NULL, date TEXT NOT NULL,"
        " posted TEXT, amount REAL NOT NULL, currency TEXT, name TEXT, merchant TEXT, merchant_key TEXT, category TEXT, category_detail TEXT,"
        " pending INTEGER NOT NULL DEFAULT 0, pending_id TEXT, owner TEXT, kind TEXT NOT NULL, tag TEXT, tag_by TEXT, removed_at REAL, updated REAL)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS plaid_transactions_date ON plaid_transactions (date)")
    conn.execute("CREATE INDEX IF NOT EXISTS plaid_transactions_pending ON plaid_transactions (pending_id)")
    conn.execute("CREATE TABLE IF NOT EXISTS budget_rules (merchant_key TEXT PRIMARY KEY, bucket TEXT NOT NULL, by TEXT, at REAL)")
    # Where each institution's transactions were read up to (Plaid's cursor), when they were last
    # asked for and last changed, and whether Plaid has finished fetching their history.
    columns = {row[1] for row in conn.execute("PRAGMA table_info(plaid_items)")}
    for column, declaration in (("txn_cursor", "TEXT"), ("txn_synced_at", "REAL"), ("txn_changed_at", "REAL"), ("txn_status", "TEXT")):
        if column not in columns:
            conn.execute(f"ALTER TABLE plaid_items ADD COLUMN {column} {declaration}")
    # What the notification policy made of each alert and each household car coming or going (see
    # "notification policy"), whether it was acted on or only logged: `key` is the review id, or
    # "car:<name>:<time>"; `route` "instant", "update", "fold", "digest", "car" or, for what the
    # old rules did, "legacy-sound", "legacy-silent", "legacy-away".
    conn.execute(
        "CREATE TABLE IF NOT EXISTS notify_log (key TEXT PRIMARY KEY, at REAL, policy TEXT, mode TEXT, route TEXT,"
        " camera TEXT, title TEXT, body TEXT, start REAL, event_id TEXT, labels TEXT, names TEXT)"
    )
    # `labels`/`names` (JSON lists: the review's labels, and the names put to its faces and cars)
    # came with the summary; a notify_log from before it gains them here.
    if not {"labels", "names"} <= {row[1] for row in conn.execute("PRAGMA table_info(notify_log)")}:
        conn.execute("ALTER TABLE notify_log ADD COLUMN labels TEXT")
        conn.execute("ALTER TABLE notify_log ADD COLUMN names TEXT")
    # One row a minute of what the relay could reach (see "uptime"): `at` the minute, `checks` a
    # JSON object of check -> 1/0 (a check that couldn't be made is left out), `devices` each
    # tailnet device -> 1/0 for whether Tailscale had it online.
    conn.execute("CREATE TABLE IF NOT EXISTS uptime (at INTEGER PRIMARY KEY, checks TEXT, devices TEXT)")
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
    # Then who last signed in on it (`user`, and their Frigate `role`): a push about the
    # household's money goes only to the phones of people who may see it (see `budget_push`).
    columns = {row[1] for row in conn.execute("PRAGMA table_info(devices)")}
    for column, declaration in (
        ("quiet_start", "INTEGER"),
        ("quiet_end", "INTEGER"),
        ("only_away", "INTEGER NOT NULL DEFAULT 0"),
        ("tz", "TEXT"),
        ("utc_offset", "INTEGER"),
        ("user", "TEXT"),
        ("role", "TEXT"),
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

    A push that is one more alert in a visit already on the phone (see `Visits`) carries the
    visit's `notif_id` and `silent: "1"`: Android replaces that visit's notification without a
    sound, and iOS collapses it onto the same banner (`apns-collapse-id`), quietly.
    """
    silent = data.get("silent") == "1"
    aps: dict[str, Any] = {"alert": {"title": title, "body": body}, "thread-id": data.get("camera", "")}
    if not silent:
        aps["sound"] = "default"
    if away:
        aps["interruption-level"] = "time-sensitive"
    elif silent:
        aps["interruption-level"] = "passive"
    headers = {"apns-priority": "10"}
    collapse_id = data.get("notif_id") or data.get("review_id")
    if collapse_id:
        headers["apns-collapse-id"] = collapse_id[:64]
    return {
        "message": {
            "token": token,
            "data": {**data, "title": title, "body": body},
            "android": {"priority": "high"},
            "apns": {"headers": headers, "payload": {"aps": aps}},
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
    results = [deliver(token, title, body, data, away=away) for token in tokens]
    return {"sent": results.count("sent"), "dropped": results.count("dropped"), "failed": results.count("failed"), "skipped_familiar": skipped, "skipped_quiet": quiet}


def deliver(token: str, title: str, body: str, data: dict[str, str], away: bool = False) -> str:
    """One push to one phone: "sent", "failed", or "dropped" when the token turned out to be dead (its row goes)."""
    sent, err = send_push(token, title, body, data, away=away)
    if sent:
        return "sent"
    if "UNREGISTERED" in err or "404" in err:
        # The install behind this token is gone (uninstalled, or its token rotated and the new
        # one has since re-registered its device_id); its presence row goes with it.
        with_db(lambda c: (c.execute("DELETE FROM devices WHERE token=?", (token,)), c.commit()))
        log.info("dropped stale device token (%s)", err)
        return "dropped"
    log.warning("push failed: %s", err)
    return "failed"


# ---------------------------------------------------------------- Frigate

_required_zones: dict[str, list[str]] = {}
_car_zones: dict[str, list[str]] = {}
_car_zone_polygons: dict[str, list[list[tuple[float, float]]]] = {}
_car_zone_outlines: dict[str, dict[str, list[tuple[float, float]]]] = {}
_live_streams: dict[str, list[str]] = {}
_detect_sizes: dict[str, tuple[int, int]] = {}
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
    global _required_zones, _car_zones, _car_zone_polygons, _car_zone_outlines, _live_streams, _detect_sizes, _config_loaded_at
    if time.time() - _config_loaded_at > CONFIG_REFRESH_SECONDS:
        try:
            cfg = requests.get(f"{FRIGATE}/api/config", timeout=10).json()
            cameras = cfg.get("cameras", {})
            _required_zones = {name: (cam.get("review", {}).get("alerts", {}).get("required_zones") or []) for name, cam in cameras.items()}
            _car_zones = {name: zones_wanting(cam, "car") for name, cam in cameras.items()}
            _car_zone_outlines = {
                name: {z: p for z in _car_zones[name] if len(p := zone_polygon((cam.get("zones") or {}).get(z))) >= 3}
                for name, cam in cameras.items()
            }
            _car_zone_polygons = {name: list(outlines.values()) for name, outlines in _car_zone_outlines.items()}
            _live_streams = {name: list(((cam.get("live") or {}).get("streams") or {}).values()) for name, cam in cameras.items()}
            _detect_sizes = {
                name: (int(d["width"]), int(d["height"])) for name, cam in cameras.items()
                if (d := cam.get("detect") or {}).get("width") and d.get("height")
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


def car_zone_outlines() -> dict[str, dict[str, list[tuple[float, float]]]]:
    """Per camera, each zone in `car_zones` by name with its outline."""
    refresh_config()
    return _car_zone_outlines


def detect_sizes() -> dict[str, tuple[int, int]]:
    """Per camera, the detect frame's width and height in pixels: what the car classifier crops from."""
    refresh_config()
    return _detect_sizes


def live_streams() -> dict[str, list[str]]:
    """Per camera, the go2rtc streams its live view may use (Frigate's `live.streams`), main first."""
    refresh_config()
    return _live_streams


REVIEW_PAGE = 50
# How far back `recent_review` pages for a backlog: 500 alerts is days of this household's worth.
REVIEW_MAX_PAGES = 10


def recent_review(severity: str, known: Callable[[str], bool] | None = None) -> list[dict[str, Any]]:
    """
    Frigate's newest review items, newest first. With `known` (has this id been handled?), keeps
    paging back until a page reaches one it knows, so a backlog longer than a page — Frigate or
    the network back after an outage — is read in full rather than falling off the end; a page
    held only 20 once, and a longer burst was never pushed at all.
    """
    items: list[dict[str, Any]] = []
    ids: set[str] = set()
    before = None
    for _ in range(REVIEW_MAX_PAGES):
        params: dict[str, Any] = {"severity": severity, "limit": REVIEW_PAGE}
        if before is not None:
            params["before"] = before
        r = requests.get(f"{FRIGATE}/api/review", params=params, timeout=10)
        r.raise_for_status()
        page = r.json()
        fresh = [item for item in page if item["id"] not in ids]
        items += fresh
        ids |= {item["id"] for item in fresh}
        if known is None or len(page) < REVIEW_PAGE or not fresh or any(known(item["id"]) for item in page):
            return items
        # `before` is strict on start_time: nudge it so an item sharing the oldest start isn't skipped.
        before = float(page[-1].get("start_time") or 0) + 0.001
    log.warning("review backlog deeper than %d pages; the oldest of it is not pushed", REVIEW_MAX_PAGES)
    return items


def was_sent(review_id: str) -> bool:
    return bool(with_db(lambda c: c.execute("SELECT 1 FROM sent WHERE review_id=?", (review_id,)).fetchone()))


def recent_alerts() -> list[dict[str, Any]]:
    return recent_review("alert", known=was_sent)


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

# The presence authority: one install whose away switch alone decides whether the house is empty.
# "Every release phone" let stale rows vote — three old iPhone debug installs and a release build on
# an emulator, none of which will ever say away — so `everyone_away` could not come true
# (2026-09-29, and away mode had been silent since). The phone that carries its owner in and out
# (Andrew's release Pixel) is chosen from the app (`PUT /presence/authority`), or by
# PRESENCE_DEVICE until it has been; the build rule above applies only while neither is set.
PRESENCE_KEY = "presence_device"
PRESENCE_DEVICE = os.environ.get("PRESENCE_DEVICE", "").strip()


def presence_authority() -> str | None:
    """The device_id that decides away mode on its own, or None while every counting phone votes."""
    return state_get(PRESENCE_KEY) or PRESENCE_DEVICE or None


def counts_for_away(platform: str, build: str) -> bool:
    """Whether this device's away switch is part of `everyone_away` when no presence authority is set."""
    return (build or "").lower() in AWAY_BUILDS or (platform or "").lower() in AWAY_DEBUG_PLATFORMS


def counts(device_id: str, platform: str, build: str, authority: str | None) -> bool:
    """Whether this device's away switch decides away mode: the authority alone when there is one."""
    return device_id == authority if authority else counts_for_away(platform, build)


def presence_snapshot(this_device: str | None = None) -> dict[str, Any]:
    """
    Who says they're home. Every registered device is listed (so a debug install can see itself),
    but `everyone_away` is decided by the counting ones alone: at least one, and all of them away —
    which with a presence `authority` is that one phone.
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
            "pending_away": ps is not None,
            "last_seen": ls,
        }
        for d, t, n, p, a, u, b, ps, ls in rows
    ]
    authority = presence_authority()
    for device in devices:
        device["counts"] = counts(device["id"], device["platform"], device["build"], authority)
    counting = [d for d in devices if d["counts"]]
    return {
        "devices": devices,
        "everyone_away": bool(counting) and all(d["away"] for d in counting),
        "home": state_get(HOME_KEY),
        "authority": authority,
    }


def away_since() -> float | None:
    """When the last person left, or None while somebody is home (or no counting phone has registered)."""
    rows = with_db(lambda c: c.execute("SELECT device_id, platform, away, away_updated, build FROM devices").fetchall())
    authority = presence_authority()
    counting = [(a, u) for d, p, a, u, b in rows if counts(d, p, b, authority)]
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
        **offers(item),
    }
    result = broadcast(title, body, data, away=True)
    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), title)), c.commit()))
    if NOTIFY_POLICY != "legacy":
        log_decision(f"{rid}:legacy", "away", "legacy-away", item.get("camera", ""), title, body, float(item.get("start_time") or 0), data["event_id"])
    log.info("away alert %s -> %s: %s | %s", rid, title, body, result)


# Frigate names a face a few seconds into a visit. A person who is still here and still
# anonymous is re-checked for this long before being pushed as a stranger, so a phone that only
# wants strangers isn't woken for family walking up the drive. Mirrors
# DetectionAlertService.RECOGNITION_GRACE_SECONDS in the app.
RECOGNITION_GRACE_SECONDS = 20.0


def has_person(item: dict[str, Any]) -> bool:
    return "person" in ((item.get("data") or {}).get("objects") or [])


def is_recognised_person(item: dict[str, Any]) -> bool:
    """A person Frigate put a name to — the review item carries the face as a sub_label (a household car's name doesn't count)."""
    labels = review_labels(item)
    names = review_names(item)
    return has_person(item) and any(n not in car_names(labels, names) for n in names)


def is_unnamed_car(item: dict[str, Any]) -> bool:
    """
    Nothing but cars in it, and none of them named: its `event_id` (the first detection) is a car
    the classifier didn't know, so the app offers a "Tag car" button on the notification. A review
    with a person in it isn't, since its first detection may be the person.
    """
    return review_labels(item) == ["car"] and not review_names(item)


def is_unnamed_person(item: dict[str, Any]) -> bool:
    """
    Nothing but people in it, and nobody named: its `event_id` (the first detection) is a person
    who may not be one at all, so the app offers a "Not a person" button on the notification (see
    "phantom people"). Not with a car in it, since its first detection may be the car.
    """
    return review_labels(item) == ["person"] and not review_names(item)


# The push data keys that offer a button on the notification, by what the review holds.
OFFER_KEYS = ("car_unnamed", "person_unnamed")


def offers(item: dict[str, Any]) -> dict[str, str]:
    """The push data that offers [item]'s buttons: "Tag car" for an unnamed car, "Not a person" for an unnamed person."""
    if is_unnamed_car(item):
        return {"car_unnamed": "1"}
    if is_unnamed_person(item):
        return {"person_unnamed": "1"}
    return {}


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
# Share of a path that must sit within the box's size of the path's median for it to be `gathered`.
STILL_FRACTION = 0.7
# The most "the box's size" may be, as a share of the frame. The box is the best frame's, and for a
# car that drives in and parks by the camera that is the close-up: Andrew's Tesla crossed three
# quarters of the Front Yard in 32 seconds on 2026-10-05 with a box 0.36 of the frame tall, which
# held 22 of the arrival's 25 points, so the arrival was "still" and never pushed. A parked car's
# jitter doesn't grow with its box like that: of 6,000 vehicle events over those two days, no path
# that stayed in one place needed more than 0.17 to hold STILL_FRACTION of its points.
STILL_RADIUS_CAP = 0.2
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


def gathered(points: list[tuple[float, float]], r: float) -> bool:
    """At least STILL_FRACTION of `points` within `r` of their median on both axes."""
    mx = median(x for x, _ in points)
    my = median(y for _, y in points)
    near = sum(1 for x, y in points if abs(x - mx) <= r and abs(y - my) <= r)
    return near / len(points) >= STILL_FRACTION


def path_span(points: list[tuple[float, float]]) -> float:
    """How far `points` reach across the frame: the larger of their extents along the two axes."""
    xs, ys = [x for x, _ in points], [y for _, y in points]
    return max(max(xs) - min(xs), max(ys) - min(ys))


def is_still(event: dict[str, Any]) -> bool:
    """
    True when the object never went anywhere: its whole path is `gathered` within
    r = max(box width, box height), and no more than STILL_RADIUS_CAP, and it had not travelled
    before, meaning no earlier stretch of the path, from its first point on, both reached further
    than 2r across and was not gathered. Sitting doesn't undo a drive: Frigate can keep one event
    on a car from the street until hours after it parked, and every flicker of its box adds a
    point at the resting place until the whole path is gathered there, arrival and all. The 2r is
    the width gathered points fit in; without it a short path decides on a single point.

    A path of fewer than MOVED_MIN_POINTS points is still (nothing has happened yet); an event
    with no box is never still (there is nothing to judge it by). Same rule, same numbers, as the
    app's `MomentEvent.isStill`.
    """
    box = (event.get("data") or {}).get("box")
    if not box or len(box) < 4 or box[2] <= 0 or box[3] <= 0:
        return False
    points = path_points(event)
    if len(points) < MOVED_MIN_POINTS:
        return True
    r = min(max(float(box[2]), float(box[3])), STILL_RADIUS_CAP)
    if not gathered(points, r):
        return False
    stretches = (points[:count] for count in range(MOVED_MIN_POINTS, len(points)))
    return not any(path_span(stretch) > 2 * r and not gathered(stretch, r) for stretch in stretches)


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


# ---------------------------------------------------------------- phantom people

# The detector sees people in clutter. On the Front Door camera, 2026-09-29, 4:23-5:14 PM, it made
# thirty "Person detected" events out of what sat by the door (a helmet on a bag, from above), one
# every time its score on it crept over the threshold and fell back: most a second or two long, a
# few a minute. Nothing was ever there.
#
# So a person can be marked "not a person" from the app (POST /events/{id}/not_a_person), on a
# Moments card, on the detection a notification opened, or with the notification's own button. The
# relay keeps where that event's box was as a *phantom spot* on its camera, and from then on a person
# who stayed put (`stayed_put`) with a box overlapping a phantom spot by PHANTOM_IOU is the same
# phantom again, and not news: its alert is skipped like a parked car's, and the app hides it from
# the feed (GET /phantoms, the same rule in `MomentEvent.isPhantom`). A real person at that spot
# still gets through: they walked there, so they didn't stay put, and a face Frigate names is never
# a phantom. The mark is also a training example (see "person check"), and once that classifier has
# learnt them, a person who stayed put *anywhere* and that it calls a phantom is one too.
#
# Staying put is stricter than a car's `is_still`, which lets 30% of the path stray and the rest
# wander a whole box, up to a fifth of the frame: up close, as at a front door, a person's box is
# half the frame, and most of a climb up the steps to the door fits within that fifth. A phantom's
# box only jitters.
PHANTOM_IOU = 0.3
# Every path point within this share of the box's longer side of the path's median.
PHANTOM_DRIFT = 0.25


def stayed_put(event: dict[str, Any]) -> bool:
    """True when every point of the object's path lies within PHANTOM_DRIFT of its box's longer side of the path's median; False with no box."""
    box = (event.get("data") or {}).get("box")
    if not box or len(box) < 4 or box[2] <= 0 or box[3] <= 0:
        return False
    points = path_points(event)
    if not points:
        return True
    mx = median(x for x, _ in points)
    my = median(y for _, y in points)
    r = PHANTOM_DRIFT * max(float(box[2]), float(box[3]))
    return all(abs(x - mx) <= r and abs(y - my) <= r for x, y in points)


def box_iou(a: list[float] | tuple[float, ...], b: list[float] | tuple[float, ...]) -> float:
    """Intersection over union of two [x, y, w, h] boxes."""
    ix = max(0.0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = a[2] * a[3] + b[2] * b[3] - inter
    return inter / union if union > 0 else 0.0


def phantom_spots(camera: str | None = None) -> list[dict[str, Any]]:
    """Every phantom spot (or one camera's), oldest first."""
    rows = with_db(lambda c: c.execute(
        "SELECT event_id, camera, box, at FROM phantoms WHERE ? IS NULL OR camera=? ORDER BY at", (camera, camera)
    ).fetchall())
    return [{"event_id": e, "camera": cam, "box": json.loads(box), "at": at} for e, cam, box, at in rows]


def face_name(event: dict[str, Any]) -> str | None:
    """The name Frigate put to the event, unless it is one of the NOT_A_NAME placeholders (in any case)."""
    name, _ = sub_label_of(event)
    return name if name and name.lower() not in NOT_A_NAME else None


def classifier_says_phantom(event: dict[str, Any]) -> bool:
    """
    The person classifier (see "person check") called [event] a phantom, at PERSON_PHANTOM_MIN_SCORE
    or better. Frigate keeps an `attribute` model's verdict in the event's data under the model's
    name, and its score beside it, from the moment its attempts agree, open events included.
    """
    if not PERSON_CLASSIFIER:
        return False
    data = event.get("data") or {}
    return data.get(PERSON_CLASSIFIER) == PERSON_PHANTOM_CLASS and float(data.get(f"{PERSON_CLASSIFIER}_score") or 0) >= PERSON_PHANTOM_MIN_SCORE


def is_phantom(event: dict[str, Any], spots: list[dict[str, Any]], classifier: bool = True) -> bool:
    """
    A person event that is a phantom: one marked as such itself, or an unnamed person who stayed put
    and either has a box that overlaps one of [spots] (its camera's) by PHANTOM_IOU or, unless
    [classifier] is false, is one the person classifier calls a phantom.
    """
    if event.get("label") != "person":
        return False
    if any(s["event_id"] == event.get("id") for s in spots):
        return True
    if face_name(event):
        return False  # a face Frigate knows
    box = (event.get("data") or {}).get("box")
    if not box or len(box) < 4 or not stayed_put(event):
        return False
    if classifier and classifier_says_phantom(event):
        return True
    return any(s["camera"] == event.get("camera") and box_iou(box, s["box"]) >= PHANTOM_IOU for s in spots)


def phantom_verdict(item: dict[str, Any]) -> str:
    """
    Whether a review item is nothing but phantom people: "push" (no, or it can't be told), "wait"
    (so far, but it's open and young: a person just arrived hasn't had time to show they move) or
    "skip" (it is). Same shape as `motion_verdict`.
    """
    data = item.get("data") or {}
    labels = {str(o).removesuffix("-verified") for o in (data.get("objects") or [])}
    detections = [d for d in (data.get("detections") or []) if d]
    if labels != {"person"} or not detections:
        return "push"
    spots = phantom_spots(item.get("camera"))
    if not spots and not person_classifier_ready():
        return "push"
    for event_id in detections:
        event = event_detail(event_id)
        if event is None or (event.get("data") or {}).get("type") not in (None, "object") or not is_phantom(event, spots):
            return "push"
    if item.get("end_time") is None and time.time() - float(item.get("start_time") or 0) < MOTION_WAIT_CAP_SECONDS:
        return "wait"
    return "skip"


def mark_phantom(event: dict[str, Any], by: str) -> dict[str, Any]:
    """Keeps [event]'s box as a phantom spot on its camera; answers the spot."""
    box = [float(v) for v in ((event.get("data") or {}).get("box") or [])[:4]]
    if len(box) < 4 or box[2] <= 0 or box[3] <= 0:
        raise HTTPException(status_code=400, detail="Frigate kept no box for this detection")
    spot = {"event_id": event["id"], "camera": event.get("camera", ""), "box": box, "at": time.time()}
    with_db(lambda c: (
        c.execute("INSERT OR REPLACE INTO phantoms VALUES (?,?,?,?,?)", (spot["event_id"], spot["camera"], json.dumps(box), by, spot["at"])),
        c.commit(),
    ))
    return spot


def unmark_phantom(event_id: str) -> bool:
    """Forgets the phantom spot [event_id] made; whether there was one."""
    return with_db(lambda c: (c.execute("DELETE FROM phantoms WHERE event_id=?", (event_id,)).rowcount, c.commit())[0]) > 0


# ---------------------------------------------------------------- visits

# Frigate ends a review item whenever it loses its objects for a moment and opens a new one when
# they reappear, so one person pottering in the yard or one car being washed in the driveway comes
# out as a review every 40-60 s. Pushed one by one, the week to 2026-09-25 was 454 sounding
# notifications, about 65 a day, most of them the same thing happening again: 114 of Front Yard's
# 251 came within five minutes of the one before. The Moments feed already folds these into
# visits (MomentVisits.kt); a notification now does the same.
#
# Every alert is still pushed, so nothing is lost; what changes is how. Alerts on one camera that
# follow each other within VISIT_GAP_SECONDS are a *visit* and share one notification (`notif_id`,
# the visit's first review id), which each alert replaces with the visit so far. Only the visit's
# first alert, and one that brings something new to it (a person where there were only cars), makes
# a sound; the rest update it silently. Two more things are always quiet:
# - a household car's comings and goings: an alert with nothing in it but the family's named cars,
#   each seen within ROUTINE_GAP_SECONDS (the feed's "routine" stretch), on any camera;
# - a backlog: an alert that began BACKLOG_SECONDS or more ago, found late because Frigate or the
#   network was down, is history by now, however loud it would have been.
# Away alerts are never folded: nobody home and a person seen always sounds, on its own.
VISIT_GAP_SECONDS = 300.0
ROUTINE_GAP_SECONDS = 3600.0
BACKLOG_SECONDS = 600.0


def review_kinds(item: dict[str, Any]) -> set[str]:
    """
    What a review brings, for telling a visit's news from more of the same: a recognised person
    as "person:<name>" and anyone else as "person" (so a stranger after the family still sounds),
    each named car as "car:<name>", an unnamed car as "car", and any other label (dog, bicycle)
    as itself.
    """
    labels = review_labels(item)
    names = review_names(item)
    cars = car_names(labels, names)
    kinds = {label for label in labels if label not in ("car", "person")}
    if "person" in labels:
        kinds |= {f"person:{n.lower()}" for n in names if n not in cars} or {"person"}
    if "car" in labels:
        kinds |= {f"car:{n.lower()}" for n in cars} or {"car"}
    return kinds


class Visit:
    """One camera's run of alerts close together, and what they've shown so far."""

    def __init__(self, item: dict[str, Any]):
        self.id: str = item["id"]
        self.camera: str = item.get("camera", "")
        self.first_start = float(item.get("start_time") or 0)
        self.last_seen = self.first_start
        # Each alert as last told (with what the vehicle memory made of its cars), in order.
        self.items: dict[str, dict[str, Any]] = {}
        self.kinds: set[str] = set()

    @property
    def count(self) -> int:
        return len(self.items)

    @property
    def objects(self) -> list[str]:
        return unique([o for item in self.items.values() for o in ((item.get("data") or {}).get("objects") or [])])

    @property
    def sub_labels(self) -> list[str]:
        return unique([s for item in self.items.values() for s in ((item.get("data") or {}).get("sub_labels") or [])])

    @property
    def zones(self) -> list[str]:
        """Where it is now, not where it started."""
        return next((list(z) for item in reversed(self.items.values()) if (z := (item.get("data") or {}).get("zones"))), [])

    def add(self, item: dict[str, Any], now: float) -> None:
        self.items[item["id"]] = item
        self.kinds |= review_kinds(item)
        self.see(item, now)

    def retell(self, item: dict[str, Any]) -> None:
        """One of its alerts again, now that more is known about its cars; what it brought to the visit stays as judged."""
        if item["id"] in self.items:
            self.items[item["id"]] = item

    def see(self, item: dict[str, Any], now: float) -> None:
        """An alert of this visit is still going (seen now) or has ended; the visit lasts until then."""
        end = item.get("end_time")
        self.last_seen = max(self.last_seen, float(end) if end is not None else now)

    def movement(self) -> str | None:
        """
        What the visit's car did, when it is only ever the one car (by name, or never named): its
        latest arrival, departure or move. Anything else in view, or two cars, and it isn't told.
        """
        labels = unique([str(o).removesuffix("-verified") for o in self.objects])
        stories = [s for item in self.items.values() for s in ((item.get("data") or {}).get("vehicles") or [])]
        if labels != ["car"] or not stories or len({s.get("name") for s in stories}) != 1:
            return None
        moves = [s["movement"] for s in stories if s.get("movement") in ("arrived", "left", "moved")]
        return moves[-1] if moves else None

    def sentence(self, required_zones: list[str]) -> tuple[str, str]:
        zone = alert_zone(self.zones, required_zones)
        body = told(subject_for(self.objects, self.sub_labels), zone, self.movement())
        if self.count > 1:
            body += f" · {self.count} alerts"
        return camera_name(self.camera), body


class Visits:
    """Each camera's current visit, and when each household car was last seen, for `judge`."""

    def __init__(self) -> None:
        self.by_camera: dict[str, Visit] = {}
        self.car_seen: dict[str, float] = {}

    def observe(self, items: list[dict[str, Any]], now: float) -> None:
        """
        Stretches each visit to its alerts' latest ends, so a long alert keeps its visit open while
        it lasts, and likewise when its household cars were last seen: a car sat in view for most of
        an hour was seen just now, not when its alert was first pushed.
        """
        for item in items:
            visit = self.by_camera.get(item.get("camera", ""))
            if visit is not None and item["id"] in visit.items:
                visit.see(item, now)
                self.see_cars(item, now)

    def see_cars(self, item: dict[str, Any], now: float) -> None:
        end = item.get("end_time")
        seen = float(end) if end is not None else now
        for car in (k for k in review_kinds(item) if k.startswith("car:")):
            self.car_seen[car] = max(self.car_seen.get(car, float("-inf")), seen)

    def judge(self, item: dict[str, Any], now: float) -> tuple[Visit, bool]:
        """Files this alert under its camera's visit (a new one if the last has gone quiet) and says whether it should sound."""
        camera = item.get("camera", "")
        start = float(item.get("start_time") or 0)
        kinds = review_kinds(item)
        visit = self.by_camera.get(camera)
        if visit is None or start - visit.last_seen > VISIT_GAP_SECONDS:
            visit = self.by_camera[camera] = Visit(item)
        # A household car seen within the hour is no news, whether it's the whole alert or has just
        # been named in a visit that so far only had "a car".
        routine = {k for k in kinds if k.startswith("car:") and start - self.car_seen.get(k, float("-inf")) <= ROUTINE_GAP_SECONDS}
        news = (not visit.kinds and not kinds) or bool(kinds - visit.kinds - routine)
        visit.add(item, now)
        self.see_cars(item, now)
        backlog = now - start >= BACKLOG_SECONDS
        return visit, news and not backlog


# A car's name can come after its alert has been pushed (the vision model looks a minute in), and
# whether it left or only moved is only plain once it is out of the driveway. So a visit with a car
# in it is told again for FOLLOWUP_SECONDS after its last push, quietly and only when the words
# change: "Car in the driveway" becomes "Andrew's Tesla left the driveway".
FOLLOWUP_SECONDS = 20 * 60.0
FOLLOWUP_EVERY_SECONDS = 30.0
FOLLOWUP_MAX_UPDATES = 4


class Followups:
    """The visits with cars pushed lately, what each one's notification says, and when to look at it again."""

    def __init__(self) -> None:
        self.by_visit: dict[str, dict[str, Any]] = {}

    def track(self, visit: Visit, body: str, data: dict[str, str], now: float) -> None:
        if "car" not in {str(o).removesuffix("-verified") for o in visit.objects}:
            self.by_visit.pop(visit.id, None)
            return
        self.by_visit[visit.id] = {"visit": visit, "body": body, "data": data, "until": now + FOLLOWUP_SECONDS,
                                   "next": now + FOLLOWUP_EVERY_SECONDS, "updates": 0}

    def run(self, alerts: list[dict[str, Any]], zones: dict[str, list[str]], now: float,
            push: Callable[..., dict[str, int]] | None = None) -> None:
        """Tells each due visit again from what is known now, and pushes it quietly when that reads differently."""
        push = push or broadcast
        fresh = {item["id"]: item for item in alerts}
        for visit_id, f in list(self.by_visit.items()):
            if now > f["until"] or f["updates"] >= FOLLOWUP_MAX_UPDATES:
                del self.by_visit[visit_id]
                continue
            if now < f["next"]:
                continue
            f["next"] = now + FOLLOWUP_EVERY_SECONDS
            visit: Visit = f["visit"]
            for rid, item in list(visit.items.items()):
                visit.retell(with_vehicle_memory(fresh.get(rid, item)))
            title, body = visit.sentence(zones.get(visit.camera, []))
            if body == f["body"]:
                continue
            last = list(visit.items.values())[-1]
            data = {k: v for k, v in f["data"].items() if k not in OFFER_KEYS}
            data["silent"] = "1"
            data.update(offers(last))
            result = push(title, body, data, familiar=is_recognised_person(last))
            f["body"], f["data"] = body, data
            f["updates"] += 1
            log.info("visit %s told again -> %s: %s | %s", visit.id, title, body, result)


# ---------------------------------------------------------------- notification policy

# What deserves a sound depends on whether anyone is home (see "presence authority"). Measured
# 2026-09-26..29: 802 pushes in three days, two thirds of them "... in the driveway" on the Front
# Yard, a quarter of them people Frigate had put a name to, and among them no telling a household
# car coming or going from its re-detections. So while the house is occupied:
# - a household car arriving or leaving sounds, once each way (see "car presence");
# - a person on a camera in INSTANT_PERSON_CAMERAS (the Front Yard) sounds at once, and then that
#   camera stays quiet until it has been empty of people for YARD_QUIET_SECONDS: anyone else in
#   the meantime only updates the same notification (`YardWatch`). Replayed, the Front Yard's 271
#   person alerts of those three days sound 54 times;
# - a person in the same review as a household car coming, going or moving, or who turns up within
#   FOLD_SECONDS of one arriving, is that car's driver or passenger: folded into the car's
#   notification rather than sounding on their own (`folded_into_car`);
# - everything else — the Front Door, the Backyard, unnamed cars, animals — is kept for a summary
#   (`notify_log`, route "digest").
# While everyone is away: under "v2" every alert and person detection, grouped into visits and
# loud (see `poll_forever`); otherwise the old rules — every alert, and every person escalated.
#
# NOTIFY_POLICY: "legacy" pushes by the old rules only; "shadow" pushes by the old rules and logs
# what this policy would have done, to compare the two; "v2" pushes by this policy.
NOTIFY_POLICY = os.environ.get("NOTIFY_POLICY", "legacy").strip().lower()
INSTANT_PERSON_CAMERAS = {c.strip() for c in os.environ.get("INSTANT_PERSON_CAMERAS", "hikvision_1").split(",") if c.strip()}
YARD_QUIET_SECONDS = 600.0
FOLD_SECONDS = 180.0
# A person beside a car that is coming or going but has no name yet waits this long for the
# classifier (it names a car within seconds when it can) before sounding as a stranger's visit.
FOLD_NAME_WAIT_SECONDS = 20.0


def log_decision(key: str, mode: str, route: str, camera: str, title: str = "", body: str = "",
                 start: float | None = None, event_id: str | None = None, item: dict[str, Any] | None = None) -> None:
    """One decision into `notify_log`; with the review `item`, its labels and names too, for the summary."""
    labels = json.dumps(review_labels(item)) if item else None
    names = json.dumps(review_names(item)) if item else None
    with_db(lambda c: (c.execute(
        "INSERT OR REPLACE INTO notify_log (key, at, policy, mode, route, camera, title, body, start, event_id, labels, names)"
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        (key, time.time(), NOTIFY_POLICY, mode, route, camera, title, body, start, event_id, labels, names),
    ), c.commit()))


def seen_until(item: dict[str, Any], now: float) -> float:
    end = item.get("end_time")
    return float(end) if end is not None else now


def folded_into_car(item: dict[str, Any]) -> bool:
    """
    Whether the people in this review came with a household car: one in the review that arrived,
    left or moved, or one that arrived on the camera within FOLD_SECONDS before it (they got out).
    """
    if any(s.get("name") and s.get("movement") in ("arrived", "left", "moved") for s in (item.get("data") or {}).get("vehicles") or []):
        return True
    start = float(item.get("start_time") or 0)
    return bool(with_db(lambda c: c.execute(
        "SELECT 1 FROM vehicle_sightings WHERE camera=? AND name IS NOT NULL AND how NOT IN ('not', 'late')"
        " AND movement='arrived' AND start BETWEEN ? AND ?",
        (item.get("camera", ""), start - FOLD_SECONDS, start + POLL_SECONDS),
    ).fetchone()))


class YardWatch:
    """Each instant-person camera's current run of people: the first sounds, the rest update it, until the camera has been empty a while."""

    def __init__(self) -> None:
        self.by_camera: dict[str, dict[str, Any]] = {}

    def observe(self, items: list[dict[str, Any]], now: float) -> None:
        """Keeps a run going while any of its reviews is still in progress, as `Visits.observe` does."""
        for item in items:
            run = self.by_camera.get(item.get("camera", ""))
            if run is not None and item["id"] in run["items"]:
                run["last_seen"] = max(run["last_seen"], seen_until(item, now))

    def see(self, item: dict[str, Any], now: float, folded: bool) -> tuple[dict[str, Any], bool]:
        """Files a person review in its camera's run (a new one once the camera has been empty YARD_QUIET_SECONDS) and says whether it sounds."""
        camera = item.get("camera", "")
        start = float(item.get("start_time") or 0)
        run = self.by_camera.get(camera)
        fresh = run is None or start - run["last_seen"] > YARD_QUIET_SECONDS
        if fresh:
            run = self.by_camera[camera] = {"id": item["id"], "first_start": start, "last_seen": start, "items": set(), "sightings": 0}
        run["items"].add(item["id"])
        run["last_seen"] = max(run["last_seen"], seen_until(item, now))
        if not folded:
            run["sightings"] += 1
        return run, fresh and not folded and now - start < BACKLOG_SECONDS


def yard_sentence(run: dict[str, Any], item: dict[str, Any], required_zones: list[str]) -> tuple[str, str]:
    """ "Front Yard", "Person on the front lawn · 3 sightings": who, by face when Frigate knows it, never the cars beside them."""
    data = item.get("data") or {}
    names = review_names(item)
    faces = [n for n in names if n not in car_names(review_labels(item), names)]
    body = told(subject_for(["person"], faces), alert_zone(data.get("zones") or [], required_zones))
    if run["sightings"] > 1:
        body += f" · {run['sightings']} sightings"
    return camera_name(item.get("camera", "")), body


def awaiting_car_name(item: dict[str, Any], now: float) -> bool:
    """A car in the review arriving, leaving or moving with no name yet, while the review is young enough for one to come."""
    moving = [s for s in (item.get("data") or {}).get("vehicles") or [] if s.get("movement") in ("arrived", "left", "moved")]
    return any(not s.get("name") for s in moving) and now - float(item.get("start_time") or 0) < FOLD_NAME_WAIT_SECONDS


def home_route(item: dict[str, Any], yard: YardWatch, now: float) -> tuple[str, dict[str, Any] | None]:
    """
    What an alert is while someone is home: "instant", "update" or "fold" (with its yard run),
    "digest", or "wait" — judged again next poll, its car may yet be named.
    """
    if item.get("camera") in INSTANT_PERSON_CAMERAS and has_person(item):
        folded = folded_into_car(item)
        if not folded and awaiting_car_name(item, now):
            return "wait", None
        run, sound = yard.see(item, now, folded)
        return ("fold" if folded else "instant" if sound else "update"), run
    return "digest", None


# ---------------------------------------------------------------- car presence

# Whether each household car is home, from the vehicle memory's sightings, so that "Sarah's car
# arrived" and "Andrew's Tesla left" are each said once. The sightings alone won't do: Frigate's
# tracker loses a parked car and re-finds it, and a jump of its box reads as "left" — Andrew's
# Tesla "left the driveway" twenty times between 12:39 and 13:37 on 2026-09-28 without moving.
# - A car that is home has *left* once a departure has ended and, for CAR_LEFT_CONFIRM_SECONDS
#   after it, nothing has seen the car again, and no car still stands in its spot. A car found in
#   its spot turns that departure down for good.
# - A car that is away has *arrived* at its first sighting since — arriving, or found parked (the
#   tracker often misses the arrival and only picks the car up standing). A name the classifier
#   gave is held until the vision model has looked (see "car check"), for at most
#   CAR_NAME_WAIT_SECONDS, since the classifier calls passing cars "Andrew's Tesla".
# - Where a car is one of TESLA_CARS and Tesla can say where it is (see "Tesla"), that decides
#   instead: near home confirms an arrival at once and turns a departure down; well away confirms a
#   departure without the spot check, and turns an arrival down (another car given its name). A
#   car Tesla has asleep turns a departure down too (it would be awake had it just driven off);
#   for an arrival, asleep says nothing and the camera decides.
# - And every TESLA_CHECK_SECONDS Tesla is asked about each of its cars whatever the camera saw:
#   a car parked at the curb arrives and leaves outside the camera's car zones (Sarah's, 2026-10-01).
# The state is kept in `state` under CAR_PRESENCE_KEY; a car first heard of starts where the
# vehicle memory has it, silently, so a deploy never replays the day.
CAR_PRESENCE_KEY = "car_presence"
CAR_LEFT_CONFIRM_SECONDS = 600.0
CAR_NAME_WAIT_SECONDS = 180.0
CAR_PRESENCE_EVERY_SECONDS = 15.0
# A sighting ending this long after a departure ended is the car still here, not the departure itself.
CAR_SEEN_AFTER_SECONDS = 30.0
CAR_ARRIVAL_MOVES = ("arrived", "parked", "moved")


def car_sightings(name: str, after: float) -> list[dict[str, Any]]:
    """The car's sightings that began after `after`, oldest first, less those the vision model turned down or that were another car's."""
    rows = with_db(lambda c: c.execute(
        f"SELECT {', '.join(_SIGHTING_COLUMNS)} FROM vehicle_sightings WHERE name=? AND start>?"
        " AND COALESCE(how, '') NOT IN ('not', 'late') ORDER BY start",
        (name, after),
    ).fetchall())
    return [dict(zip(_SIGHTING_COLUMNS, r)) for r in rows]


def name_settled(row: dict[str, Any], now: float) -> bool:
    """Whether a sighting's name can be told: a person's, the vision model's or the memory's, one the model has checked, or waited on long enough."""
    if row.get("how") in ("tagged", "looked", "parked") or not (OLLAMA and HOUSEHOLD_CARS):
        return True
    return checked(row["event_id"], "vlm") or now - float(row["start"] or now) >= CAR_NAME_WAIT_SECONDS


def spot_still_taken(camera: str, name: str) -> bool:
    """
    Whether a car still stands where `name` was parked: an event in progress on the camera whose
    path ends at its spot, and that isn't another household car or a car that has since pulled in.
    """
    v = vehicle(camera, name)
    if not v or not v.get("spot"):
        return False
    for summary in events_since({"camera": camera, "label": "car", "in_progress": 1}, 0.0):
        event = event_detail(summary["id"]) or summary
        points = event_points(event)
        if not points or near_spot(points[-1], v["spot"]) is None:
            continue
        story = sighting(summary["id"]) or {}
        if story.get("name") in (None, name) and story.get("movement") != "arrived":
            return True
    return False


def starting_state(name: str, now: float) -> tuple[bool, float]:
    """
    Where a car starts, as (here, since), by its sightings of the last day: here when there are
    some and none is a departure, or it was seen again after the last one (the departure rule of
    `CarPresence`), or that departure is still inside its CAR_LEFT_CONFIRM_SECONDS — then `since`
    falls just before it, so `CarPresence.departure` judges it like any other. Away otherwise.
    """
    rows = car_sightings(name, now - VEHICLE_STALE_SECONDS)
    lefts = [r for r in rows if r["movement"] == "left"]
    if not lefts:
        return bool(rows), now
    gone = lefts[-1]
    gone_at = float(gone["end"] if gone["end"] is not None else gone["start"])
    if any(
        r["movement"] != "left" and (float(r["start"]) > gone_at or (r["end"] is not None and float(r["end"]) > gone_at + CAR_SEEN_AFTER_SECONDS))
        for r in rows
    ):
        return True, now
    if now < gone_at + CAR_LEFT_CONFIRM_SECONDS:
        return True, float(gone["start"]) - 1
    return False, now


def span_text(seconds: float) -> str:
    """ "3h 10m", "25m", "2d 4h"."""
    minutes = max(1, int(seconds // 60))
    days, hours, minutes = minutes // 1440, minutes // 60 % 24, minutes % 60
    if days:
        return f"{days}d {hours}h" if hours else f"{days}d"
    if hours:
        return f"{hours}h {minutes}m" if minutes else f"{hours}h"
    return f"{minutes}m"


class CarPresence:
    """Each household car's home or away, and what changed (see "car presence")."""

    def __init__(self) -> None:
        self.cars: dict[str, dict[str, Any]] | None = None
        self.checked_at = float("-inf")

    def discover(self, now: float) -> None:
        """
        Loads the saved state once, and starts each car the vehicle memory knows and it doesn't by
        its sightings of the last day (`starting_state`), not the memory's `here`: a false
        "left" clears that, and on 2026-09-29 both cars started away with the Tesla in the driveway.
        """
        if self.cars is None:
            self.cars = state_get(CAR_PRESENCE_KEY) or {}
        rows = with_db(lambda c: c.execute("SELECT name, camera FROM vehicles").fetchall())
        found: dict[str, dict[str, Any]] = {}
        for name, camera in rows:
            if name in self.cars or name in found:
                continue
            here, since = starting_state(name, now)
            found[name] = {"here": here, "since": since, "camera": camera, "skip": None, "known": False}
        for name, car in found.items():
            self.cars[name] = car
            log.info("car presence: %s starts %s", name, "home" if car["here"] else "away")
        if found:
            state_set(CAR_PRESENCE_KEY, self.cars)

    def tick(self, now: float | None = None) -> list[dict[str, Any]]:
        """Every CAR_PRESENCE_EVERY_SECONDS: each car that arrived or left since, as `{name, movement, camera, event_id, at, was}`."""
        now = time.time() if now is None else now
        if now - self.checked_at < CAR_PRESENCE_EVERY_SECONDS:
            return []
        self.checked_at = now
        self.discover(now)
        changes = []
        for name, car in list(self.cars.items()):
            rows = car_sightings(name, float(car["since"]))
            change = self.departure(name, car, rows, now) if car["here"] else self.arrival(name, car, rows, now)
            change = change or self.tesla_check(name, car, now)
            if change:
                changes.append(change)
        if changes or any(car.get("dirty") for car in self.cars.values()):
            for car in self.cars.values():
                car.pop("dirty", None)
            state_set(CAR_PRESENCE_KEY, self.cars)
        return changes

    def arrival(self, name: str, car: dict[str, Any], rows: list[dict[str, Any]], now: float) -> dict[str, Any] | None:
        turned_down = car.get("not_arrivals") or []
        row = next((r for r in rows if r["movement"] in CAR_ARRIVAL_MOVES and r["event_id"] not in turned_down), None)
        if row is None:
            return None
        # Tesla, when it can say, settles it at once: near home is the car; well away, it was another.
        verdict = tesla_verdict(name, now)
        if verdict == "away":
            log.info("car presence: %s's arrival %s turned down, Tesla has the car away", name, row["event_id"])
            car["not_arrivals"], car["dirty"] = (turned_down + [row["event_id"]])[-20:], True
            return None
        if verdict != "home" and not name_settled(row, now):
            return None
        return self.change(name, car, float(row["start"]), row)

    def departure(self, name: str, car: dict[str, Any], rows: list[dict[str, Any]], now: float) -> dict[str, Any] | None:
        lefts = [r for r in rows if r["movement"] == "left" and r["final"] and r["event_id"] != car.get("skip")]
        if not lefts:
            return None
        gone = lefts[-1]
        gone_at = float(gone["end"] if gone["end"] is not None else gone["start"])
        confirmed_at = gone_at + CAR_LEFT_CONFIRM_SECONDS
        if any(
            r["movement"] != "left" and float(r["start"]) <= confirmed_at
            and (float(r["start"]) > gone_at or (r["end"] is not None and float(r["end"]) > gone_at + CAR_SEEN_AFTER_SECONDS))
            for r in rows
        ):
            return None  # seen again within the window: that was the tracker, not the car
        if now < confirmed_at:
            return None
        # A car that drove off CAR_LEFT_CONFIRM_SECONDS ago is awake — still driving, or only just
        # parked, and a Tesla takes longer than that to sleep. Asleep, it never went: on 2026-09-30
        # Tesla had Andrew's car asleep at each of the five departures the camera made up that day.
        verdict = tesla_verdict(name, now)
        if verdict in ("home", "asleep") or (verdict is None and spot_still_taken(gone["camera"], name)):
            reason = {"home": "Tesla has the car at home", "asleep": "Tesla has the car asleep, so it never drove off"}
            log.info("car presence: %s's departure %s turned down, %s", name, gone["event_id"],
                     reason.get(verdict, "a car still stands in its spot"))
            car["skip"], car["dirty"] = gone["event_id"], True
            return None
        return self.change(name, car, gone_at, gone)

    def tesla_check(self, name: str, car: dict[str, Any], now: float) -> dict[str, Any] | None:
        """
        Every TESLA_CHECK_SECONDS, asks Tesla where a car the camera hasn't settled is: one parked at
        the curb comes and goes out of the camera's car zones, so only Tesla sees it. Recorded home
        and Tesla has it well away, it left; recorded away and Tesla has it near home, it is home.
        Asleep or unsure, nothing changes.
        """
        if name not in TESLA_CARS or now - float(car.get("checked") or 0) < TESLA_CHECK_SECONDS:
            return None
        car["checked"], car["dirty"] = now, True
        verdict = tesla_verdict(name, now)
        if verdict == ("away" if car["here"] else "home"):
            log.info("car presence: %s %s, by Tesla's check", name, "left" if car["here"] else "is home")
            return self.change(name, car, now, {"camera": car.get("camera") or "", "event_id": "", "movement": "parked"}, via="tesla")
        return None

    def change(self, name: str, car: dict[str, Any], at: float, row: dict[str, Any], via: str = "camera") -> dict[str, Any]:
        here = not car["here"]
        was = at - float(car["since"]) if car.get("known") else None
        self.cars[name] = {"here": here, "since": at, "camera": row["camera"], "skip": None, "known": True, "checked": car.get("checked")}
        movement = "left" if not here else "arrived" if row["movement"] == "arrived" else "is home"
        return {"name": name, "movement": movement, "camera": row["camera"], "event_id": row["event_id"], "at": at, "was": was, "via": via}


def car_sentence(change: dict[str, Any]) -> tuple[str, str]:
    """ "Sarah's Car arrived home", "Front Yard · away 3h 10m"; "Andrew's Tesla left", "Front Yard · home 5h"."""
    subject = display_name(change["name"])
    title = {"arrived": f"{subject} arrived home", "is home": f"{subject} is home"}.get(change["movement"], f"{subject} left")
    # One Tesla's check found, not a camera: the curb, out of the camera's car zones.
    body = "Seen by Tesla" if change.get("via") == "tesla" else camera_name(change["camera"])
    if change.get("was") is not None:
        body += f" · {'home' if change['movement'] == 'left' else 'away'} {span_text(change['was'])}"
    return title, body


def tell_car(change: dict[str, Any], mode: str, push: Callable[..., dict[str, int]] | None = None) -> None:
    """One household car arriving or leaving: pushed under "v2", only logged otherwise."""
    title, body = car_sentence(change)
    act = NOTIFY_POLICY == "v2"
    if act:
        data = {
            "notif_id": f"car-{change['name']}",
            "camera": change["camera"],
            "event_id": change["event_id"] or "",
            "event_start": str(change["at"]),
            "start_time": str(change["at"]),
        }
        result = (push or broadcast)(title, body, data, away=mode == "away")
    else:
        result = "not pushed"
    log_decision(f"car:{change['name']}:{int(change['at'])}", mode, "car", change["camera"], title, body, change["at"], change["event_id"])
    log.info("car presence (%s): %s | %s -> %s", NOTIFY_POLICY, title, body, result)


def skip_phantom(item: dict[str, Any]) -> bool:
    """
    Whether to pass over [item] this poll because it is phantom people (see "phantom people"): not
    yet (judged again next poll), or for good (marked sent, so it is never pushed).
    """
    verdict = phantom_verdict(item)
    if verdict == "skip":
        with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (item["id"], time.time(), "(phantom)")), c.commit()))
        log.info("alert %s skipped: a phantom person on %s", item["id"], item.get("camera", ""))
    return verdict != "push"


def tell_home(item: dict[str, Any], route: str, run: dict[str, Any] | None, zones: dict[str, list[str]],
              push: Callable[..., dict[str, int]] | None = None) -> None:
    """
    One alert while someone is home, by its `home_route`: under "v2" an instant one sounds, an
    update lands silently on its run's notification, and the rest are marked sent unpushed (kept
    in `notify_log` for the summary); under "shadow" it is only logged.
    """
    rid, camera = item["id"], item.get("camera", "")
    required = zones.get(camera, [])
    title, body = yard_sentence(run, item, required) if run is not None and route in ("instant", "update") else sentence(item, required)
    event_id = ((item.get("data") or {}).get("detections") or [""])[0]
    result: Any = "not pushed"
    if NOTIFY_POLICY == "v2":
        if route in ("instant", "update"):
            data = {
                "review_id": rid,
                "notif_id": run["id"],
                "camera": camera,
                "event_id": event_id,
                "event_start": str(item.get("start_time", "")),
                "zones": ",".join((item.get("data") or {}).get("zones") or []),
                "start_time": str(run["first_start"]),
            }
            if route == "update":
                data["silent"] = "1"
            data.update(offers(item))
            result = (push or broadcast)(title, body, data, familiar=is_recognised_person(item))
        sent_body = body if route in ("instant", "update") else f"({route})"
        with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), sent_body)), c.commit()))
    log_decision(rid, "home", route, camera, title, body, float(item.get("start_time") or 0), event_id, item)
    log.info("alert %s at home (%s): %s -> %s: %s | %s", rid, NOTIFY_POLICY, route, title, body, result)


# ---------------------------------------------------------------- summaries

# What stayed quiet while someone was home (route "digest" in `notify_log`) is told a few times a
# day, at DIGEST_HOURS on the household's clock, as one quiet notification that replaces the last:
# "Since 12:00 PM: 5 visits" / "Front Door: Sarah, 2 unknown people · Backyard: dog · Front Yard:
# 1 unknown car". Alerts on one camera no more than DIGEST_VISIT_GAP_SECONDS apart are one visit.
# Nothing to tell, no summary. Under "shadow" it is only logged (route "summary").
DIGEST_HOURS = sorted({int(h) for h in os.environ.get("DIGEST_HOURS", "9,12,15,18,21").split(",") if h.strip().isdigit() and 0 <= int(h) < 24})
DIGEST_KEY = "digest_until"
DIGEST_VISIT_GAP_SECONDS = 300.0
# A slot this long gone (the relay was down through it) is folded into the next one rather than sent late.
DIGEST_LATE_SECONDS = 1800.0
ANIMAL_LABELS = {"dog", "cat", "bird", "horse", "sheep", "cow", "bear", "deer", "raccoon", "squirrel", "rabbit", "fox", "skunk"}


def digest_slot(now: float, zone: ZoneInfo | None) -> float | None:
    """The latest DIGEST_HOURS slot at or before `now`, on the household's clock (UTC when unknown)."""
    local = datetime.fromtimestamp(now, zone or timezone.utc)
    midnight = local.replace(hour=0, minute=0, second=0, microsecond=0)
    slots = [(midnight - timedelta(days=d)).replace(hour=h).timestamp() for d in (0, 1) for h in DIGEST_HOURS]
    past = [t for t in slots if t <= now]
    return max(past) if past else None


def digest_rows(after: float, until: float) -> list[dict[str, Any]]:
    """The alerts kept for the summary that were decided in (after, until], oldest first."""
    rows = with_db(lambda c: c.execute(
        "SELECT camera, start, labels, names FROM notify_log WHERE route='digest' AND at>? AND at<=? ORDER BY start",
        (after, until),
    ).fetchall())
    return [{"camera": c, "start": float(st or 0), "labels": json.loads(lb or "[]"), "names": json.loads(nm or "[]")} for c, st, lb, nm in rows]


def digest_visits(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The rows as visits: per camera, alerts no more than DIGEST_VISIT_GAP_SECONDS apart, with everything they saw."""
    visits: list[dict[str, Any]] = []
    last: dict[str, dict[str, Any]] = {}
    for row in rows:
        visit = last.get(row["camera"])
        if visit is None or row["start"] - visit["end"] > DIGEST_VISIT_GAP_SECONDS:
            visit = last[row["camera"]] = {"camera": row["camera"], "start": row["start"], "end": row["start"], "labels": [], "names": []}
            visits.append(visit)
        visit["end"] = row["start"]
        visit["labels"] = unique(visit["labels"] + row["labels"])
        visit["names"] = unique(visit["names"] + row["names"])
    return visits


def plural(count: int, one: str, many: str) -> str:
    return f"{count} {one if count == 1 else many}"


def digest_text(visits: list[dict[str, Any]], since: float, zone: ZoneInfo | None) -> tuple[str, str]:
    """
    The summary's title and body: each camera in the order it was first busy, and on it who (by
    name, else how many visits had someone unknown), which cars (the same), and any animals.
    """
    by_camera: dict[str, list[dict[str, Any]]] = {}
    for visit in visits:
        by_camera.setdefault(visit["camera"], []).append(visit)
    parts = []
    for camera, seen in by_camera.items():
        people: list[str] = []
        cars: list[str] = []
        strangers = unknown_cars = 0
        others: list[str] = []
        for visit in seen:
            labels, names = visit["labels"], visit["names"]
            car_named = car_names(labels, names)
            faces = [n for n in names if n not in car_named]
            if "person" in labels:
                people += faces
                strangers += not faces
            if "car" in labels:
                cars += car_named
                unknown_cars += not car_named
            others += [label for label in labels if label not in ("person", "car")]
        words = [display_name(n) for n in unique(people)]
        if strangers:
            words.append(plural(strangers, "unknown person", "unknown people"))
        words += [display_name(n) for n in unique(cars)]
        if unknown_cars:
            words.append(plural(unknown_cars, "unknown car", "unknown cars"))
        words += [f"{label} ×{others.count(label)}" if others.count(label) > 1 else label for label in unique(others)]
        parts.append(f"{camera_name(camera)}: {', '.join(words) or plural(len(seen), 'visit', 'visits')}")
    return f"Since {clock_text(since, zone)}: {plural(len(visits), 'visit', 'visits')}", " · ".join(parts)


def only_familiar(visits: list[dict[str, Any]]) -> bool:
    """
    Whether a summary is nothing but people Frigate put a name to — every visit people alone, and
    each one named — so a phone that asked for strangers only isn't sent it. Anything else in it
    (a stranger, a car, an animal) is news to that phone too.
    """
    def named_people_only(visit: dict[str, Any]) -> bool:
        faces = [n for n in visit["names"] if n not in car_names(visit["labels"], visit["names"])]
        return visit["labels"] == ["person"] and bool(faces)

    return bool(visits) and all(named_people_only(v) for v in visits)


def summarise(now: float | None = None, push: Callable[..., dict[str, int]] | None = None) -> None:
    """At each DIGEST_HOURS slot: tells what was kept for the summary since the last one."""
    if NOTIFY_POLICY == "legacy" or not DIGEST_HOURS:
        return
    now = time.time() if now is None else now
    zone = household_zone()
    slot = digest_slot(now, zone)
    until = state_get(DIGEST_KEY)
    if slot is None or (until is not None and slot <= float(until)):
        return
    if until is None:
        state_set(DIGEST_KEY, slot)
        return  # the first slot this relay has seen: the summaries start from here
    if now - slot > DIGEST_LATE_SECONDS:
        return  # slept through it: the next slot covers this one's time too
    state_set(DIGEST_KEY, slot)
    visits = digest_visits(digest_rows(float(until), slot))
    if not visits:
        return
    title, body = digest_text(visits, float(until), zone)
    result: Any = "not pushed"
    if NOTIFY_POLICY == "v2":
        result = (push or broadcast)(title, body, {"notif_id": "summary", "summary": "1", "silent": "1"}, familiar=only_familiar(visits))
    log_decision(f"summary:{int(slot)}", "home", "summary", "", title, body, float(until))
    log.info("summary (%s): %s | %s -> %s", NOTIFY_POLICY, title, body, result)


def poll_forever() -> None:
    visits = Visits()
    followups = Followups()
    yard = YardWatch()
    cars = CarPresence()
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
            # Under "v2" the away items go through the visits below instead, grouped and loud.
            if since is not None and NOTIFY_POLICY != "v2":
                for item in away_items(since):
                    if was_sent(item["id"]):
                        continue
                    if skip_phantom(item):
                        continue
                    push_away_review(item, zones)
            # ---- end away mode ----
            mode = "home" if since is None else "away"
            policy = NOTIFY_POLICY in ("shadow", "v2") and mode == "home"
            # Under "v2", away is every alert and every person detection, each camera's run of them
            # one visit: the first sounds on the loud channel, more of the same updates it quietly,
            # and something new in it (a stranger after the family, a second car) sounds again.
            grouped_away = NOTIFY_POLICY == "v2" and mode == "away"
            alerts = recent_alerts()
            if grouped_away:
                known = {item["id"] for item in alerts}
                people = [item for item in away_items(since) if item["id"] not in known]
                alerts = sorted(alerts + people, key=lambda item: float(item.get("start_time") or 0), reverse=True)
            visits.observe(alerts, time.time())
            yard.observe(alerts, time.time())
            for item in reversed(alerts):  # oldest first, so pushes arrive in order
                rid = item["id"]
                if was_sent(rid):
                    continue
                if awaiting_recognition(item):
                    continue  # not marked sent: judged again next poll, once Frigate has had time to name the face
                if skip_phantom(item):
                    continue
                verdict = motion_verdict(item)
                if verdict == "wait":
                    continue  # likewise: judged again once the car has had a chance to go somewhere
                if verdict == "skip":
                    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), "(stationary)")), c.commit()))
                    log.info("alert %s skipped: nothing in it moved (%s)", rid, ", ".join((item.get("data") or {}).get("objects") or []))
                    continue
                # Names the cars the vehicle memory knows, and says what they did.
                item = with_vehicle_memory(item)
                parked = parked_verdict(item)
                if parked == "wait":
                    continue
                if parked == "skip":
                    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), "(parked)")), c.commit()))
                    log.info("alert %s skipped: %s stayed parked", rid, ", ".join(s["name"] for s in item["data"]["vehicles"]))
                    continue
                if policy:
                    route, run = home_route(item, yard, time.time())
                    if route == "wait":
                        continue
                    tell_home(item, route, run, zones)
                    if NOTIFY_POLICY == "v2":
                        continue
                visit, sound = visits.judge(item, time.time())
                title, body = visit.sentence(zones.get(item.get("camera", ""), []))
                data = {
                    "review_id": rid,
                    "notif_id": visit.id,
                    "camera": item.get("camera", ""),
                    # This alert's own object and start, so the picture and clip show what just
                    # happened (the clip is asked for once its preview window from `event_start` is over) ...
                    "event_id": (item.get("data", {}).get("detections") or [""])[0],
                    "event_start": str(item.get("start_time", "")),
                    "zones": ",".join(visit.zones),
                    # ... and the visit's start, so a tap opens it from the beginning.
                    "start_time": str(visit.first_start),
                }
                if not sound:
                    data["silent"] = "1"
                data.update(offers(item))
                if grouped_away:
                    title, data["away"] = f"Away · {title}", "1"
                result = broadcast(title, body, data, away=grouped_away, familiar=is_recognised_person(item) and not grouped_away)
                with_db(lambda c: (c.execute("INSERT OR REPLACE INTO sent VALUES (?,?,?)", (rid, time.time(), body)), c.commit()))
                log.info("alert %s (visit %s, %s) -> %s: %s | %s", rid, visit.id, "sound" if sound else "silent", title, body, result)
                if NOTIFY_POLICY == "shadow":
                    log_decision(f"{rid}:legacy", mode, "legacy-sound" if sound else "legacy-silent", item.get("camera", ""), title, body,
                                 float(item.get("start_time") or 0), data["event_id"])
                if not grouped_away:
                    # A follow-up retells a visit as an ordinary push; an away visit's cars are told by car presence instead.
                    followups.track(visit, body, data, time.time())
            followups.run(alerts, zones, time.time())
            if NOTIFY_POLICY in ("shadow", "v2"):
                for change in cars.tick():
                    tell_car(change, mode)
                summarise()
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
# - Far cars lose their name (see "far cars").
# - A second opinion on cars in a car zone (the driveway), from a local vision model through
#   Ollama. It looks at the car in the 4K recording rather than the detect frame (~6x the pixels),
#   and answers a closed set — colour, make, model, body, and the plate when it can read one —
#   which is then checked against the household's car profiles (see "car profiles"). It only ever
#   vetoes or corrects the classifier: a name that contradicts what the car looks like is replaced
#   by the one household car that fits, or cleared; it never names a car the classifier left
#   unnamed on looks alone (a plate of ours does), and never touches a name a person gave (score
#   1.0, from the app's car tagging). No appearance can tell our dark blue Model Y from a
#   neighbour's, which is what the plate is for; looks catch the red Model Y called "Andrew's Tesla".
# - Verified crops into their car (see "verified crops"): a name the check confirmed by plate or by
#   looks, on crops the classifier was 100% sure of, is filed as a training example of that car.
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
# {"andrews_tesla": {"make": "tesla", "model": "Model Y", "colour": "blue", "plate": "8ABC123"}, ...}:
# how each household car looks, keyed by the classifier's category (see "car profiles"). The
# HOUSEHOLD_CARS environment variable only seeds the profiles the app then edits; this dict is the
# live copy of the `car_profiles` table. A car missing here is never judged.
HOUSEHOLD_CARS: dict[str, dict[str, Any]] = json.loads(os.environ.get("HOUSEHOLD_CARS", "{}") or "{}")
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
        "plate": {"type": "string"},
    },
    "required": ["colour", "make", "model", "body", "delivery", "plate"],
}
VLM_PROMPT = (
    "This is a crop from a home security camera. Describe the vehicle in the centre of the picture. "
    "If the picture is black-and-white infrared night footage, answer colour \"unknown\". "
    "Answer make \"unknown\" unless a badge or an unmistakable shape shows it. "
    "model is the model name if you can tell (\"Model Y\", \"Camry\"), else an empty string. "
    "delivery is the company if it is a marked delivery vehicle, else \"none\". "
    "plate is the vehicle's licence plate, letters and digits only, if the plate is in the picture and every "
    "character is sharp enough to read; otherwise an empty string. Never guess a character."
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


def check_of(event_id: str, kind: str) -> tuple[str, str] | None:
    """The verdict and detail recorded for an event, if any."""
    return with_db(lambda c: c.execute("SELECT verdict, detail FROM car_checks WHERE event_id=? AND kind=?", (event_id, kind)).fetchone())


def checked(event_id: str, kind: str) -> bool:
    return with_db(lambda c: c.execute("SELECT 1 FROM car_checks WHERE event_id=? AND kind=?", (event_id, kind)).fetchone()) is not None


def queued_crops(model: str) -> dict[str, list[str]]:
    """The crops Frigate queued for [model] to be labelled (`clips/<model>/train/`), by event, oldest first."""
    train = os.path.join(CLIPS_DIR, model, "train")
    by_event: dict[str, list[str]] = {}
    for name in sorted(os.listdir(train)) if os.path.isdir(train) else []:
        event_id = train_crop_event(name)
        if event_id and name.endswith(".webp"):
            by_event.setdefault(event_id, []).append(name)
    return by_event


def file_street_crops() -> None:
    """Moves queued crops of passing street cars into `none`, within the hourly and total caps."""
    model = CAR_CLASSIFIER
    room = STREET_NONE_MAX - dataset_count(model, "none")
    budget = STREET_NONE_PER_HOUR - filed_since("street", time.time() - 3600)
    if room <= 0 or budget <= 0:
        return
    by_event = queued_crops(model)
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
    if now - last < RETRAIN_EVERY_SECONDS or filed_since("street", last) + filed_since("verified", last) < RETRAIN_AFTER:
        return
    if now - (state_get("car_retrain_tried_at") or 0.0) < RETRAIN_RETRY_SECONDS:
        return
    state_set("car_retrain_tried_at", now)
    r = requests.post(f"{FRIGATE}/api/classification/{CAR_CLASSIFIER}/train", timeout=30)
    if not r.ok:
        log.warning("retrain of %s refused, trying again in %.0f min: %s %s", CAR_CLASSIFIER, RETRAIN_RETRY_SECONDS / 60, r.status_code, r.text[:200])
        return
    state_set("car_retrain_at", now)
    log.info("retrain of %s requested after %d new street and %d verified crops: %s %s",
             CAR_CLASSIFIER, filed_since("street", last), filed_since("verified", last), r.status_code, r.text[:200])


# ---------------------------------------------------------------- verified crops
#
# The user's rule (2026-09-28): a detection the model is 100% sure of is simply taken, and one it
# isn't sure of is left for a person to correct. But the classifier's 100% alone isn't enough: on
# 2026-09-27 it named Sarah's red Model Y `andrews_tesla` at 1.0, and filing that would have taught
# it the mistake. So a queued crop the classifier scored 1.0 for a household car is filed into that
# car's dataset only once the car check verified the event as that very car, by its plate or its
# looks (see "car profiles"). The rest stay in the queue, where the app shows every 1.0 crop the
# check didn't verify alongside the uncertain ones.
#
# A parked car is re-registered all day in the same spot, and a class trained from one spot learns
# the spot (see the quarantine of 2026-09-27). So few crops per event, and few an hour per car,
# spread over the day's light rather than piled up from one afternoon.
CONFIDENT_SCORE = 1.0
VERIFIED_CROPS_PER_EVENT = 2
VERIFIED_EVENTS_PER_CAR_PER_DAY = 6
VERIFIED_SPACING_SECONDS = 3600.0


def train_crop_guess(file_name: str) -> tuple[str, float] | None:
    """The classifier's guess on a queued crop, `<event id>-<frame time>-<category>-<score>.webp`, as (category, score)."""
    if train_crop_event(file_name) is None:
        return None
    parts = file_name.rsplit(".", 1)[0].split("-")
    try:
        return "-".join(parts[3:-1]), float(parts[-1])
    except ValueError:
        return None


def verified_as(event_id: str) -> tuple[str | None, str | None, str | None]:
    """
    What the car check made of an event: (the name it ends with, what verified it, the check's
    verdict). All None until the check has looked; the first two None when nothing verified it.
    """
    row = check_of(event_id, "vlm")
    if row is None:
        return None, None, None
    verdict, detail = row
    try:
        d = json.loads(detail or "{}")
    except ValueError:
        d = {}
    how = d.get("verified")
    name = d.get("now") if verdict in ("relabel", "name", "clear") else d.get("was")
    return (name if how else None), how, verdict


def verified_filings(name: str, since: float) -> list[float]:
    """When crops of `name` were filed as verified since `since`."""
    rows = with_db(lambda c: c.execute("SELECT at, detail FROM car_checks WHERE kind='verified' AND verdict='filed' AND at>=?", (since,)).fetchall())
    return [at for at, detail in rows if (json.loads(detail or "{}") or {}).get("name") == name]


def file_verified_crops() -> None:
    """Files the queued 1.0 crops of cars the check verified into those cars (see "verified crops")."""
    model = CAR_CLASSIFIER
    train = os.path.join(CLIPS_DIR, model, "train")
    by_event: dict[str, list[tuple[str, str]]] = {}
    for file_name in sorted(os.listdir(train)) if os.path.isdir(train) else []:
        event_id, guess = train_crop_event(file_name), train_crop_guess(file_name)
        if event_id and guess and file_name.endswith(".webp") and guess[1] >= CONFIDENT_SCORE and guess[0] in HOUSEHOLD_CARS:
            by_event.setdefault(event_id, []).append((file_name, guess[0]))
    now = time.time()
    for event_id, crops in by_event.items():
        if checked(event_id, "verified"):
            continue
        name, how, verdict = verified_as(event_id)
        if verdict is None:
            continue  # not looked at (yet): the check only looks at cars in a car zone
        if how is None:
            record_check(event_id, "verified", "unverified", verdict)
            continue
        files = [f for f, category in crops if category == name][:VERIFIED_CROPS_PER_EVENT]
        if not files:
            record_check(event_id, "verified", "other-name", json.dumps({"name": name}))
            continue
        filed = verified_filings(name, now - 24 * 3600)
        if len(filed) >= VERIFIED_EVENTS_PER_CAR_PER_DAY or (filed and now - max(filed) < VERIFIED_SPACING_SECONDS):
            record_check(event_id, "verified", "enough", json.dumps({"name": name}))
            continue
        moved = []
        for file_name in files:
            r = requests.post(f"{FRIGATE}/api/classification/{model}/dataset/categorize",
                              json={"category": name, "training_file": file_name}, timeout=10)
            if r.ok:
                moved.append(file_name)
        record_check(event_id, "verified", "filed" if moved else "failed", json.dumps({"name": name, "how": how, "files": moved}))
        log.info("verified car %s: %d crop(s) the classifier was sure of filed as %s/%s (by %s)", event_id, len(moved), model, name, how)


# ---------------------------------------------------------------- far cars
#
# The classifier names every car Frigate tracks, however small, and a car across the street is a
# few dozen pixels of the detect frame blown up to the classifier's 224. Dark, it is "Andrew's
# Tesla": a neighbour's SUV parked across the road scored 0.7-0.99 at night on 2026-09-27, after
# 14 crops of it had been filed into `none`. In the three days to then, all 29 cars named outside
# the car zones from a crop under 110 px were the wrong car: cars across the street, cars driving
# by, one cut off by the frame's edge. Just above that is a household car: Sarah's, parked at the
# curb half behind the tree, is a 115 px crop, and her training pictures are of that very view.
# Inside the driveway small crops are ours too: the porch beam cuts the car in half.
#
# So a car that kept clear of every car zone, and whose crop was under FAR_CAR_MAX_PX, has the
# classifier's name taken off once it is done or settled. A person's tag stays.
FAR_CAR_MAX_PX = 110
FAR_CAR_PAGE = 200


def classifier_crop_px(event: dict[str, Any], frame: tuple[int, int] | None) -> float | None:
    """
    The side of the square the classifier judged the car from: the box's longer side in pixels of
    the detect frame (Frigate crops a square of that side around the box). None without a box.
    """
    box = (event.get("data") or {}).get("box")
    if not frame or not box or len(box) < 4:
        return None
    return max(float(box[2]) * frame[0], float(box[3]) * frame[1])


def is_far_car(event: dict[str, Any], zones_for_car: list[str], polygons: list[list[tuple[float, float]]],
               frame: tuple[int, int] | None, now: float) -> bool:
    """
    A car too far off for the classifier to name (see "far cars"): done or settled, tagged with no
    car zone, every point of its path clearly out of them, and a crop under FAR_CAR_MAX_PX. Never
    on a camera without car zone outlines, where "far" can't be told.
    """
    px = classifier_crop_px(event, frame)
    points = event_points(event)
    return (
        event.get("label") == "car"
        and bool(zones_for_car) and bool(polygons)
        and (event.get("end_time") is not None or now - float(event.get("start_time") or now) >= VLM_SETTLE_SECONDS)
        and not any(z in zones_for_car for z in event.get("zones") or [])
        and bool(points) and all(zone_side(p, polygons) == "out" for p in points)
        and px is not None and px < FAR_CAR_MAX_PX
    )


def classifier_names() -> list[str]:
    """The names the car classifier gives: its dataset's categories but `none`, and the household's cars."""
    folder = os.path.join(CLIPS_DIR, CAR_CLASSIFIER, "dataset")
    found = os.listdir(folder) if CAR_CLASSIFIER and os.path.isdir(folder) else []
    return sorted({n for n in found + list(HOUSEHOLD_CARS) if n.lower() not in NOT_A_NAME})


def clear_far_car_names() -> None:
    """
    Takes the classifier's name off each far car of the last hour or still in view (Frigate's
    `after` is by start time, and a parked car's event can stay open for hours), and off it again
    should the classifier name it anew. A person's tag is looked at again each round, since it may
    be taken away.
    """
    names = classifier_names()
    if not names:
        return
    now = time.time()
    frames, polygons = detect_sizes(), car_zone_polygons()
    for camera, zones_for_car in car_zones().items():
        if not zones_for_car or not polygons.get(camera) or not frames.get(camera):
            continue
        named = {"camera": camera, "label": "car", "sub_labels": ",".join(names), "limit": FAR_CAR_PAGE}
        found: dict[str, dict[str, Any]] = {}
        for params in ({**named, "after": now - 3600}, {**named, "in_progress": 1}):
            r = requests.get(f"{FRIGATE}/api/events", params=params, timeout=10)
            r.raise_for_status()
            found.update({e["id"]: e for e in r.json() if e.get("id")})
        for event in found.values():
            event_id = event.get("id", "")
            name, score = frigate_name(event)
            before = check_of(event_id, "far")
            if not name or (before and before[0] != "cleared") or by_a_person(event):
                continue
            if not is_far_car(event, zones_for_car, polygons[camera], frames[camera], now):
                if event.get("end_time") is not None:
                    record_check(event_id, "far", "near", name)
                continue  # still in view: it may yet come closer
            requests.post(f"{FRIGATE}/api/events/{event_id}/sub_label", json={"subLabel": "", "subLabelScore": None}, timeout=10).raise_for_status()
            px = classifier_crop_px(event, frames[camera]) or 0.0
            record_check(event_id, "far", "cleared", json.dumps({"was": name, "score": score, "px": round(px)}))
            log.info("far car %s on %s: %s (%s) taken off, a %.0f px crop outside the car zones", event_id, camera, name, score, px)


def sub_label_of(event: dict[str, Any]) -> tuple[str | None, float | None]:
    """The event's name and how sure its giver was; Frigate has sent `sub_label` both as a string and as `[name, score]`."""
    sub = event.get("sub_label")
    score = (event.get("data") or {}).get("sub_label_score")
    if isinstance(sub, list):
        sub, score = (sub + [None, None])[:2]
    return (sub or None), (float(score) if score is not None else None)


# ---------------------------------------------------------------- person tags
#
# Frigate keeps one name per event, a sub_label with a score, whoever gave it. The app tags a car at
# 1.0, and the relay took a 1.0 to mean a person had: it leaves such a name alone and may keep a
# picture of the car as its reference. Since the retrain of 2026-09-27 the known_cars classifier
# scores some of its own guesses 1.0 too, among them the parked Tesla's event that carried on as a
# car on the street, filed as the Tesla leaving.
#
# So the app tags through the relay (POST /events/{id}/sub_label), which passes the tag on to
# Frigate as that person (their session cookie, so Frigate's own permission check applies) and
# keeps a row per tag in `person_tags`. A name is a person's when that row has it. Events that
# began before the table existed keep the old rule, so tags given before are still trusted.
def person_tags_since() -> float:
    return float(state_get("person_tags_since") or 0.0)


def by_a_person(event: dict[str, Any]) -> bool:
    """Whether the event's name is one a person gave (see "person tags")."""
    name, score = sub_label_of(event)
    if not name:
        return False
    row = with_db(lambda c: c.execute("SELECT name FROM person_tags WHERE event_id=?", (event.get("id"),)).fetchone())
    if row:
        return row[0] == name
    return score is not None and score >= 1.0 and float(event.get("start_time") or 0.0) < person_tags_since()


def record_person_tag(event_id: str, name: str | None, by: str) -> None:
    """
    Keeps (or, for a name taken away, forgets) the name a person gave an event, and drops what was
    decided about the event while its name was the classifier's: the same name, now a person's,
    may give the car its reference picture and is filed again as `tagged`.
    """
    def write(c: sqlite3.Connection) -> None:
        if name:
            c.execute("INSERT OR REPLACE INTO person_tags VALUES (?,?,?,?)", (event_id, name, by, time.time()))
        else:
            c.execute("DELETE FROM person_tags WHERE event_id=?", (event_id,))
        c.execute("DELETE FROM car_checks WHERE event_id=? AND kind='learn'", (event_id,))
        c.execute("UPDATE vehicle_sightings SET final=0 WHERE event_id=?", (event_id,))
        c.commit()

    with_db(write)


def second_opinion_due(event: dict[str, Any], zones_for_car: list[str], now: float) -> bool:
    """A car in a car zone, done or settled, whose name no person gave."""
    return (
        event.get("label") == "car"
        and any(z in zones_for_car for z in event.get("zones") or [])
        and (event.get("end_time") is not None or now - float(event.get("start_time") or now) >= VLM_SETTLE_SECONDS)
        and not by_a_person(event)
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


# ---------------------------------------------------------------- car profiles
#
# Each household car's make, model, colour and plate, keyed by the classifier's category, set from
# the app (PUT /cars/profiles/{name}) and kept in `car_profiles`. Colour is one colour, and it is
# strict: the user chose (2026-09-28) that a blue Tesla never passes as a black one, although dark
# blue reads as black at dusk. A car whose colour can't be told is left for the plate, or for a
# person to confirm in the app's labelling queue, rather than waved through.
#
# A plate is the strongest word there is: two Model Ys in the household differ only in colour, and
# a neighbour's in neither. The Front Yard seldom shows one (the porch beam and the angle hide the
# parked cars' plates), so it counts only when the model read every character: within
# PLATE_MATCH_DISTANCE of a household plate it names the car, whatever the classifier said; at
# least PLATE_DIFFERENT_DISTANCE from the named car's plate it takes that name away.
PLATE_MATCH_DISTANCE = 1
PLATE_DIFFERENT_DISTANCE = 3
PLATE_MIN_LENGTH = 4
PLATE_MAX_LENGTH = 10
PROFILE_MODEL_MAX = 40
_PROFILE_KEYS = ("make", "model", "colour", "plate")


def normal_plate(plate: Any) -> str:
    """A plate as letters and digits only, upper case: "8abc 123" and "8ABC-123" are one plate."""
    return re.sub(r"[^A-Z0-9]", "", str(plate or "").upper())[:PLATE_MAX_LENGTH]


def edit_distance(a: str, b: str) -> int:
    row = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, cb in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (ca != cb))
    return row[-1]


def normal_model(model: Any, make: str = "") -> str:
    """ "Tesla Model Y" and "model-y" as "modely": letters and digits, lower case, the make dropped from the front."""
    text = re.sub(r"[^a-z0-9]", "", str(model or "").lower())
    make = re.sub(r"[^a-z0-9]", "", (make or "").lower())
    return text[len(make):] if make and make not in ("unknown", "other") and text.startswith(make) and len(text) > len(make) else text


def profile_row(row: tuple) -> tuple[str, dict[str, Any]]:
    name, make, model, colour, plate = row
    return name, {k: v for k, v in zip(_PROFILE_KEYS, (make, model, colour, plate)) if v}


def load_car_profiles() -> None:
    """
    Seeds `car_profiles` from the HOUSEHOLD_CARS environment variable, once (a list of colours
    becomes its first, the strict rule; a car the app removes stays removed), and makes the table
    the live copy.
    """
    global HOUSEHOLD_CARS

    def seed(c: sqlite3.Connection) -> None:
        for name, looks in HOUSEHOLD_CARS.items():
            colour = looks.get("colour")
            colour = (colour[0] if colour else None) if isinstance(colour, list) else colour
            c.execute("INSERT OR IGNORE INTO car_profiles VALUES (?,?,?,?,?,?,?)",
                      (name, looks.get("make"), looks.get("model"), colour, normal_plate(looks.get("plate")) or None, time.time(), "env"))
        c.commit()

    if not state_get("car_profiles_seeded"):
        with_db(seed)
        state_set("car_profiles_seeded", time.time())
        log.info("car profiles seeded from HOUSEHOLD_CARS: %s", sorted(HOUSEHOLD_CARS))
    HOUSEHOLD_CARS = car_profiles()


def car_profiles() -> dict[str, dict[str, Any]]:
    rows = with_db(lambda c: c.execute("SELECT name, make, model, colour, plate FROM car_profiles ORDER BY name").fetchall())
    return dict(profile_row(r) for r in rows)


def save_car_profile(name: str, profile: dict[str, Any] | None, by: str) -> None:
    """Writes (or, for None, removes) a car's profile and reloads the live copy."""
    global HOUSEHOLD_CARS

    def write(c: sqlite3.Connection) -> None:
        if profile is None:
            c.execute("DELETE FROM car_profiles WHERE name=?", (name,))
        else:
            c.execute("INSERT OR REPLACE INTO car_profiles VALUES (?,?,?,?,?,?,?)",
                      (name, *(profile.get(k) or None for k in _PROFILE_KEYS), time.time(), by))
        c.commit()

    with_db(write)
    HOUSEHOLD_CARS = car_profiles()


def looks_verdict(description: dict[str, str], profile: dict[str, Any]) -> str:
    """
    Whether a car the vision model described is the car of `profile`: "mismatch" when a make,
    model or colour it read contradicts the profile, "match" when it read the car's colour and
    nothing it read contradicts it, else "unsure". What the model couldn't tell (infrared, no badge,
    no model name) rules nothing out, but nor does it confirm: colour is what tells the household's
    two Model Ys apart. A profile may still carry a list of colours (the old configuration).
    """
    def group(colour: str) -> str:
        return COLOUR_GROUPS.get(colour, colour)

    make = description.get("make") or "unknown"
    if profile.get("make") and make not in ("unknown", "other") and make != profile["make"]:
        return "mismatch"
    wanted_model, seen_model = normal_model(profile.get("model"), profile.get("make", "")), normal_model(description.get("model"), make)
    if wanted_model and seen_model and wanted_model not in seen_model and seen_model not in wanted_model:
        return "mismatch"
    wanted = profile.get("colour") or []
    wanted = [wanted] if isinstance(wanted, str) else wanted
    colour = description.get("colour") or "unknown"
    if wanted and colour != "unknown":
        return "match" if group(colour) in {group(c) for c in wanted} else "mismatch"
    return "unsure"


def household_matches(description: dict[str, str], cars: dict[str, dict[str, Any]]) -> list[str]:
    """The household cars the description doesn't contradict."""
    return [name for name, profile in cars.items() if looks_verdict(description, profile) != "mismatch"]


def plate_read(event: dict[str, Any], description: dict[str, str]) -> str:
    """The plate read off the car: Frigate's own reader if it is on, else the vision model's. Empty when neither could."""
    data = event.get("data") or {}
    for plate in (data.get("recognized_license_plate"), event.get("recognized_license_plate"), description.get("plate")):
        plate = normal_plate(plate[0] if isinstance(plate, list) and plate else plate)
        if len(plate) >= PLATE_MIN_LENGTH:
            return plate
    return ""


def plate_owner(plate: str, cars: dict[str, dict[str, Any]]) -> str | None:
    """The household car whose plate this is, within PLATE_MATCH_DISTANCE; None when it is nobody's, or two cars' (a typo in a profile)."""
    owners = [name for name, p in cars.items() if p.get("plate") and edit_distance(plate, normal_plate(p["plate"])) <= PLATE_MATCH_DISTANCE]
    return owners[0] if plate and len(owners) == 1 else None


def plate_rules_out(plate: str, name: str, cars: dict[str, dict[str, Any]]) -> bool:
    """
    Whether a plate read clearly isn't the named car's: a full read, far from the plate on its profile.
    A read is full when it is about as long as that plate, so a scrap of a longer plate is only far
    from it by length and rules nothing out, while a short plate on file can still be ruled out.
    """
    own = normal_plate((cars.get(name) or {}).get("plate"))
    full = len(plate) >= max(PLATE_MIN_LENGTH, len(own) - PLATE_MATCH_DISTANCE)
    return bool(own) and full and edit_distance(plate, own) >= PLATE_DIFFERENT_DISTANCE


def second_opinion_verdict(name: str | None, description: dict[str, str], cars: dict[str, dict[str, Any]], plate: str = "") -> tuple[str, str | None, str | None]:
    """
    What to do with the classifier's name given what the vision model saw: ("keep", name, how),
    ("relabel", other name, how) or ("clear", None, None), where `how` says what verified the name
    it ends with ("plate" or "looks"), None when nothing did.

    A plate of ours names the car outright. Otherwise an unnamed car, or a name with no profile to
    check it against, is kept as it is. A name whose car doesn't look like its profile (or whose
    plate isn't its own) is replaced only by the one other household car that looks right *and*
    whose make the model read off the picture — colour alone ("some white car") doesn't make it
    anyone's — or else cleared. A name that fits but whose colour couldn't be told is kept, unverified.
    """
    owner = plate_owner(plate, cars)
    if owner:
        return ("keep" if owner == name else "relabel"), owner, "plate"
    if not name or name.lower() in ("none", "unknown") or name not in cars:
        return "keep", name, None
    verdict = "mismatch" if plate_rules_out(plate, name, cars) else looks_verdict(description, cars[name])
    if verdict == "match":
        return "keep", name, "looks"
    if verdict == "unsure":
        return "keep", name, None
    make = description.get("make") or "unknown"
    others = [n for n, p in cars.items() if n != name and looks_verdict(description, p) == "match" and p.get("make") == make
              and not plate_rules_out(plate, n, cars)]
    if len(others) == 1:
        return "relabel", others[0], "looks"
    return "clear", None, None


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


def worth_learning(event: dict[str, Any], zones_for_car: list[str], now: float) -> bool:
    """A car a person tagged, in a car zone and settled, whose camera has no reference picture of it yet (see "vehicle memory")."""
    name, _ = frigate_name(event)
    return (
        name is not None and by_a_person(event)
        and event.get("label") == "car"
        and any(z in zones_for_car for z in event.get("zones") or [])
        and (event.get("end_time") is not None or now - float(event.get("start_time") or now) >= VLM_SETTLE_SECONDS)
        and reference_picture(event.get("camera", ""), name) is None
    )


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
            # Looked at once already, unless a person has tagged it since: then once more, for its picture.
            # A name that turned out not to be a person's is passed over until it changes.
            judged = checked(event_id, "vlm")
            if judged:
                name_now = frigate_name(summary)[0]
                learnt = check_of(event_id, "learn")
                if (name_now is None or reference_picture(camera, name_now) is not None
                        or (learnt and (learnt[0] != "untagged" or learnt[1] == name_now))):
                    continue
            event = event_detail(event_id) or summary
            # A late arrival's tag is the car the event began as, so its picture isn't that car.
            learning = worth_learning(event, zones_for_car, now) and not checked(event_id, "learn") and (sighting(event_id) or {}).get("how") != "late"
            if judged and not learning:
                record_check(event_id, "learn", "untagged", frigate_name(event)[0] or "")
                continue
            if not ((not judged and second_opinion_due(event, zones_for_car, now)) or learning):
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
            summary_text = " ".join(v for v in (description.get("colour"), description.get("make"), description.get("model"), description.get("body")) if v and v not in ("unknown", "other"))
            if description.get("delivery") not in (None, "none"):
                summary_text += f" ({description['delivery']})"
            summary_text = summary_text.strip()
            if by_a_person(event):
                # A person's name: nothing to judge, but the best picture there is of that car.
                learned = learn_vehicle(camera, name, description, picture, tagged=True)
                record_check(event_id, "learn", "kept" if learned else "night", json.dumps({"name": name, "saw": description}))
                if not judged:
                    record_check(event_id, "vlm", "person", json.dumps({"was": name, "score": score, "saw": description}))
                continue
            plate = plate_read(event, description)
            action, new_name, verified = second_opinion_verdict(name, description, HOUSEHOLD_CARS, plate)
            story = observe_car(event) or {}
            recognised, remembered = None, ""
            if action == "keep" and frigate_name(event)[0] is None:
                if story.get("how") == "looked" and story.get("name"):
                    recognised, remembered = story["name"], "confirm"  # confirmed before, Frigate didn't take it: again
                else:
                    recognised, remembered = recognise_from_memory(camera, story, description, picture)
                if remembered == "confirm":
                    action, new_name = "name", recognised
            try:
                if action != "keep":
                    requests.post(f"{FRIGATE}/api/events/{event_id}/sub_label",
                                  json={"subLabel": new_name or "", "subLabelScore": VLM_SCORE if new_name else None}, timeout=10).raise_for_status()
                if story:
                    if remembered == "confirm":
                        story.update(name=recognised, how="looked")
                    elif remembered == "reject":
                        # Not the car remembered there: nor, then, is that car there any more.
                        with_db(lambda c: (c.execute("UPDATE vehicles SET here=0 WHERE camera=? AND name=? AND here=1", (camera, story["name"])), c.commit()))
                        log.info("vehicle memory: car %s is not %s, so %s is forgotten from that spot", event_id, story["name"], story["name"])
                        story.update(name=None, how="not")
                    story["saw"] = summary_text or story.get("saw")
                    save_sighting(story)
                    if remembered in ("confirm", "reject"):
                        story = observe_car(event) or story  # tells the memory the car is (or isn't) here
                    note_event(story)
                elif summary_text:
                    requests.post(f"{FRIGATE}/api/events/{event_id}/description", json={"description": summary_text}, timeout=10).raise_for_status()
            except Exception as e:
                log.warning("car %s: Frigate didn't take the verdict (%s %s), trying again next round: %s", event_id, action, new_name or "", e)
                continue
            if verified == "plate" or (action == "keep" and verified == "looks"):
                learn_vehicle(camera, new_name, description, picture, tagged=False)
            if action == "name":
                verified = None  # the memory's name, not the classifier's: no crops of it to file
            record_check(event_id, "vlm", action, json.dumps({"was": name, "score": score, "now": new_name, "saw": description,
                                                             "memory": remembered or None, "verified": verified, "plate": plate or None}))
            took = time.time() - started
            log.info("car %s on %s: classifier %s (%s), model saw %s%s in %.1fs -> %s %s%s%s",
                     event_id, camera, name, score, summary_text, f" plate {plate}" if plate else "", took, action, new_name or "",
                     f" ({verified})" if verified else "", f" (memory: {remembered})" if remembered else "")
            if took > VLM_SLOW_SECONDS:
                log.warning("vision model took %.0fs for one car: is Ollama running on the CPU? (docker logs ollama | grep load_tensors)", took)


# While Ollama is still downloading (hours on the box's Wi-Fi), ask again this often rather than every round.
VLM_RETRY_SECONDS = 600.0


# ---------------------------------------------------------------- person check
#
# A phantom spot (see "phantom people") only catches the phantom it was shown, where it was shown.
# What carries over to the next bag left on the step is a model that has learnt the household's own
# phantoms. Frigate's object classification can run a second model on every person it tracks, as
# `known_cars` runs on cars: PERSON_CLASSIFIER names one with two classes, `person` and
# PERSON_PHANTOM_CLASS, and the relay grows its dataset from what the household already knows, with
# no labelling to sit through:
# - `phantom`: each detection someone marks "not a person" (Frigate's own queued crop of it when it
#   still has one, else cut from the recording as Frigate would), and from then on each re-detection
#   of a phantom: the detector's confident mistakes, which teach the most;
# - `person`: the people Frigate put a face's name to, which are people whatever else is true, and
#   people who walked across the frame (PERSON_WALKED), so the class isn't only the household's
#   faces at the door: clutter doesn't cross a yard.
# Not `none`, which is what `known_cars` calls "not ours": Frigate drops a `none` verdict before it
# reaches the event, so the relay could never see the classifier say "phantom". With a class of
# its own it lands in the event's data (`classifier_says_phantom`), and a person who stayed put
# and is called a phantom with PERSON_PHANTOM_MIN_SCORE is one, wherever on the camera they are.
# Queued crops are filed a few an hour, each class capped at PERSON_CLASS_MAX, and the model is
# retrained once a day when PERSON_RETRAIN_AFTER examples went in. All of it is off without
# PERSON_CLASSIFIER, and waits while Frigate has no such model (no `clips/<model>` folder).
PERSON_CLASSIFIER = os.environ.get("PERSON_CLASSIFIER", "")
PERSON_PHANTOM_CLASS = "phantom"
PERSON_PHANTOM_MIN_SCORE = float(os.environ.get("PERSON_PHANTOM_MIN_SCORE", "0.9"))
PERSON_CLASS_MAX = int(os.environ.get("PERSON_CLASS_MAX", "400"))
PERSON_FILED_PER_HOUR = int(os.environ.get("PERSON_FILED_PER_HOUR", "12"))
PERSON_RETRAIN_AFTER = int(os.environ.get("PERSON_RETRAIN_AFTER", "20"))
# How long an ended event's queued crops wait for Frigate's face name before the event is judged anyway.
PERSON_SETTLE_SECONDS = 60.0
# How far, in frame fractions, a person's path must reach to count as someone who walked, and so a
# `person` example. A phantom's box only jitters; a fifth of the frame is several strides.
PERSON_WALKED = 0.2
# Where a "not a person" example goes when the mark is taken back: out of the dataset, kept for a look.
PERSON_UNDONE_DIR = "undone"


def person_classifier_ready() -> bool:
    return bool(PERSON_CLASSIFIER) and os.path.isdir(os.path.join(CLIPS_DIR, PERSON_CLASSIFIER))


def person_examples_since(since: float) -> int:
    """Examples filed into the person classifier since [since], by the relay and by marks alike."""
    return filed_since("person-check", since) + filed_since("not-a-person", since)


def person_crop_category(event: dict[str, Any], spots: list[dict[str, Any]]) -> str | None:
    """
    What a queued crop of [event] teaches: PERSON_PHANTOM_CLASS for a phantom, `person` for a named
    face or someone who walked across PERSON_WALKED of the frame, else nothing. A phantom only the
    classifier called one teaches nothing: filing its own verdicts back in would only harden them.
    """
    if event.get("label") != "person":
        return None
    if is_phantom(event, spots, classifier=False):
        return PERSON_PHANTOM_CLASS
    if face_name(event) or path_travel(event) >= PERSON_WALKED:
        return "person"
    return None


def file_person_crops() -> None:
    """Files queued crops of phantoms and of people (`person_crop_category`), within the hourly and per-class caps."""
    model = PERSON_CLASSIFIER
    now = time.time()
    budget = PERSON_FILED_PER_HOUR - filed_since("person-check", now - 3600)
    if budget <= 0:
        return
    room = {category: PERSON_CLASS_MAX - dataset_count(model, category) for category in ("person", PERSON_PHANTOM_CLASS)}
    spots = phantom_spots()
    for event_id, files in queued_crops(model).items():
        if budget <= 0:
            break
        if checked(event_id, "person-check") or checked(event_id, "not-a-person"):
            continue
        try:
            event = fetch_event(event_id)
        except Exception:
            continue  # look again next round
        if event is None:
            if now - float(event_id.split("-")[0]) >= STREET_GONE_AFTER_SECONDS:
                record_check(event_id, "person-check", "gone")
            continue
        end = event.get("end_time")
        if end is None or now - float(end) < PERSON_SETTLE_SECONDS:
            continue  # still going, or its face may yet be named
        category = person_crop_category(event, spots)
        if category is None:
            record_check(event_id, "person-check", "unsure")
            continue
        if room[category] <= 0:
            record_check(event_id, "person-check", "full", category)
            continue
        r = requests.post(f"{FRIGATE}/api/classification/{model}/dataset/categorize",
                          json={"category": category, "training_file": files[-1]}, timeout=10)
        record_check(event_id, "person-check", "filed" if r.ok else "failed", category)
        if r.ok:
            budget -= 1
            room[category] -= 1
            log.info("person check %s: a crop filed as %s/%s", event_id, model, category)


def file_queued_crop(model: str, category: str, training_file: str) -> str:
    """
    Moves one of the crops Frigate queued for [model] into [category], as Frigate's `categorize`
    does (a PNG, since a webp can't be trained on, under the name Frigate would give it), and
    answers the new file's name, which `categorize` doesn't: a mark taken back needs to find it.
    """
    from PIL import Image

    source = os.path.join(CLIPS_DIR, model, "train", training_file)
    folder = os.path.join(CLIPS_DIR, model, "dataset", category)
    os.makedirs(folder, exist_ok=True)
    name = dataset_file_name(category, time.time())
    with Image.open(source) as crop:
        crop.convert("RGB").save(os.path.join(folder, name), format="PNG")
    os.unlink(source)
    return name


# One lock per event being filed: two phones marking the same alert at once must not both file it,
# since only the last file's name is kept and Undo could then take back just one of them.
_filing_guard = threading.Lock()
_filing: dict[str, threading.Lock] = {}


def file_not_a_person(event: dict[str, Any]) -> str | None:
    """
    Files a detection someone marked "not a person" into the person classifier's PERSON_PHANTOM_CLASS:
    its newest queued crop, or else the person cut out of the recording at its last sighting, framed
    as Frigate frames a crop. Answers how ("queued", "recording"), or None when it couldn't. The
    file's name is kept with the check, for `unfile_not_a_person`. Marks of the same event take
    turns; the second finds the first's example and files nothing.
    """
    if not person_classifier_ready():
        return None
    with _filing_guard:
        lock = _filing.setdefault(event["id"], threading.Lock())  # kept: marks are taps, a lock each is nothing
    with lock:
        return _file_not_a_person(event)


def _file_not_a_person(event: dict[str, Any]) -> str | None:
    filed = check_of(event["id"], "not-a-person")
    if filed and filed[0] == "filed":
        return filed_how(filed[1])  # marked twice (the notification's button, then the app): one example is enough
    model = PERSON_CLASSIFIER
    try:
        crops = queued_crops(model).get(event["id"])
        if crops:
            name = file_queued_crop(model, PERSON_PHANTOM_CLASS, crops[-1])
            record_check(event["id"], "not-a-person", "filed", json.dumps({"how": "queued", "file": name}))
            return "queued"
        sighting = last_sighting(event)
        if sighting is None:
            return None
        t, box = sighting
        height = detect_sizes().get(event.get("camera", ""), (0, 0))[1]
        r = requests.get(f"{FRIGATE}/api/{event['camera']}/recordings/{t:.1f}/snapshot.jpg",
                         params={"height": height} if height else None, timeout=30)
        if not r.ok:
            return None
        name = save_classification_example(model, PERSON_PHANTOM_CLASS, r.content, box)
        record_check(event["id"], "not-a-person", "filed", json.dumps({"how": "recording", "file": name}))
        return "recording"
    except Exception as e:
        log.warning("not-a-person example for %s failed: %s", event.get("id"), e)
        return None


def filed_how(detail: str) -> str:
    """How a "not a person" example was filed, from its check's detail: JSON now, the bare word from older relays."""
    try:
        return str(json.loads(detail).get("how"))
    except (ValueError, AttributeError):
        return detail


def unfile_not_a_person(event_id: str) -> bool:
    """
    Takes back the example a "not a person" filed, now the mark is: out of the dataset into the
    model's PERSON_UNDONE_DIR (kept, never deleted, in case the mark was right after all), and its
    check marked "undone". Whether there was one to take. A retrain since has already learnt it;
    the next one forgets it.
    """
    check = check_of(event_id, "not-a-person")
    if not check or check[0] != "filed" or not PERSON_CLASSIFIER:
        return False
    try:
        name = os.path.basename(str(json.loads(check[1]).get("file") or ""))
    except (ValueError, AttributeError):
        name = None  # filed by an older relay, which didn't keep the name
    source = os.path.join(CLIPS_DIR, PERSON_CLASSIFIER, "dataset", PERSON_PHANTOM_CLASS, name) if name else None
    if not source or not os.path.isfile(source):
        return False
    folder = os.path.join(CLIPS_DIR, PERSON_CLASSIFIER, PERSON_UNDONE_DIR)
    os.makedirs(folder, exist_ok=True)
    os.replace(source, os.path.join(folder, name))
    record_check(event_id, "not-a-person", "undone", check[1])
    return True


def maybe_retrain_person() -> None:
    """Retrains the person classifier once a day, when at least PERSON_RETRAIN_AFTER examples went in since the last one."""
    now = time.time()
    last = state_get("person_retrain_at") or 0.0
    if now - last < RETRAIN_EVERY_SECONDS or person_examples_since(last) < PERSON_RETRAIN_AFTER:
        return
    if now - (state_get("person_retrain_tried_at") or 0.0) < RETRAIN_RETRY_SECONDS:
        return
    state_set("person_retrain_tried_at", now)
    r = requests.post(f"{FRIGATE}/api/classification/{PERSON_CLASSIFIER}/train", timeout=30)
    if not r.ok:
        log.warning("retrain of %s refused, trying again in %.0f min: %s %s", PERSON_CLASSIFIER, RETRAIN_RETRY_SECONDS / 60, r.status_code, r.text[:200])
        return
    state_set("person_retrain_at", now)
    log.info("retrain of %s requested after %d new examples", PERSON_CLASSIFIER, person_examples_since(last))


def car_check_forever() -> None:
    vlm_ready = False
    vlm_tried_at = 0.0
    while True:
        if CAR_CLASSIFIER:
            try:
                file_street_crops()
                maybe_retrain()
            except Exception as e:
                log.warning("street crops: %s", e)
            try:
                clear_far_car_names()
            except Exception as e:
                log.warning("far cars: %s", e)
            try:
                file_verified_crops()
            except Exception as e:
                log.warning("verified crops: %s", e)
        if person_classifier_ready():
            try:
                file_person_crops()
                maybe_retrain_person()
            except Exception as e:
                log.warning("person check: %s", e)
        try:
            vehicle_memory_round()
        except Exception as e:
            log.warning("vehicle memory: %s", e)
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


# ---------------------------------------------------------------- vehicle memory

# Tagging a car names one Frigate event, and the classifier names some of the rest, but Frigate
# re-registers a parked car as a brand-new object all day (see "motion gate"), each one unnamed
# unless the classifier gets a look in the seconds it lives, which it mostly doesn't. So Andrew's
# Tesla, tagged in the driveway, still came through as "Car in the driveway" (2026-09-27).
#
# So the relay remembers, in relay.db, where each named car is parked on each camera, and tells
# what each car in a car zone did:
# - *arrived*: its path began outside the zone and ends inside it (or on its edge);
# - *left*: began inside (or on the edge), ends outside;
# - *moved*: stayed inside but went further than its own size;
# - *parked*: stayed where it was (the flicker re-detections).
# A car whose path begins where a remembered car is parked, and that didn't arrive, is that car:
# nothing else can be standing in its spot. A car that leaves is forgotten from its spot, and so is
# one whose spot an unnamed car pulled into. Every car event in a car zone gets a row in
# `vehicle_sightings` with the name it was given, how ("tagged" by a person, "classifier",
# "parked" where a remembered car stands, "looked" at by the vision model, or "not" when the model
# said it isn't the car the memory thought), and what it did, and the story goes into the event's
# description in Frigate ("Andrew's Tesla left the driveway · blue tesla Model Y suv").
#
# The vision model (see "car check") keeps the memory honest, when it runs:
# - The first daylight look it has at a car a person tagged becomes that car's reference picture,
#   kept on the box next to relay.db (`vehicles/<camera>--<name>.jpg`), and what it saw (make,
#   colours) the car's `looks`.
# - A car named only by where it is parked is compared with that car's reference picture. The same
#   car, and the name goes to Frigate at VLM_SCORE; a different one, or a make or colour the car
#   never has, and the name is withdrawn and the car forgotten from that spot.
# - A car that arrived unnamed is compared with each remembered car that is away and has a
#   reference picture, and named when exactly one is the same car and its looks fit.
#
# Frigate's tracker hands a box from one car to another, and the memory reads around it (2026-09-27):
# - A path that jumps across a car zone's edge in one step is two cars: the parked Tesla's event
#   carried on as a car going by on the street (11:58), and was not the Tesla leaving. The memory
#   keeps the path up to the jump.
# - A path that begins on a car zone's edge and ends out of it is a departure, though no point is
#   inside: the tracker only finds a car backing out briskly once it is past the edge (09:50).
# - An arrival long after its event began is a car the tracker switched to as it went by (Sarah's
#   car at the curb all night, then the Tesla coming home at 10:58). It is filed "late": at the
#   time it came in, and unnamed, since the event's name is the other car's.
SPOT_MATCH = 0.5  # a path starting within this share of the remembered box's longer side of its spot
# A car driving moves 0.05-0.13 of the frame a path step (0.24 at the 99th percentile, 2026-09-26/27);
# the box going from the driveway to the street or the curb in one step is 0.3-0.5.
TRACK_SWITCH_JUMP = 0.25
LATE_ARRIVAL_SECONDS = 600.0
VEHICLE_STALE_SECONDS = 24 * 3600.0  # a spot nothing has been seen at for this long is not trusted
VEHICLE_LOOKBACK_SECONDS = 3600.0
LOOKS_COLOURS_MAX = 4
MOVEMENTS = ("arrived", "left", "moved", "parked")
SAME_CAR_SCHEMA = {"type": "object", "properties": {"same": {"type": "string", "enum": ["yes", "no", "unsure"]}}, "required": ["same"]}

_SIGHTING_COLUMNS = ("event_id", "camera", "name", "how", "movement", "zone", "start", "end", "final", "saw", "noted", "at")
_VEHICLE_COLUMNS = ("camera", "name", "here", "spot", "since", "last_seen", "event_id", "looks")


def sighting(event_id: str) -> dict[str, Any] | None:
    row = with_db(lambda c: c.execute(f"SELECT {', '.join(_SIGHTING_COLUMNS)} FROM vehicle_sightings WHERE event_id=?", (event_id,)).fetchone())
    return dict(zip(_SIGHTING_COLUMNS, row)) if row else None


def save_sighting(row: dict[str, Any]) -> None:
    values = tuple(row.get(k) for k in _SIGHTING_COLUMNS)
    with_db(lambda c: (c.execute(f"INSERT OR REPLACE INTO vehicle_sightings VALUES ({', '.join('?' * len(values))})", values), c.commit()))


def vehicle(camera: str, name: str) -> dict[str, Any] | None:
    row = with_db(lambda c: c.execute(f"SELECT {', '.join(_VEHICLE_COLUMNS)} FROM vehicles WHERE camera=? AND name=?", (camera, name)).fetchone())
    if not row:
        return None
    found = dict(zip(_VEHICLE_COLUMNS, row))
    found["spot"] = json.loads(found["spot"]) if found["spot"] else None
    found["looks"] = json.loads(found["looks"]) if found["looks"] else {}
    return found


def vehicles_on(camera: str) -> list[dict[str, Any]]:
    names = with_db(lambda c: [r[0] for r in c.execute("SELECT name FROM vehicles WHERE camera=? ORDER BY name", (camera,))])
    return [v for v in (vehicle(camera, n) for n in names) if v]


def save_vehicle(row: dict[str, Any]) -> None:
    values = tuple(json.dumps(row.get(k)) if k in ("spot", "looks") and row.get(k) is not None else row.get(k) for k in _VEHICLE_COLUMNS)
    with_db(lambda c: (c.execute(f"INSERT OR REPLACE INTO vehicles VALUES ({', '.join('?' * len(values))})", values), c.commit()))


def event_points(event: dict[str, Any]) -> list[tuple[float, float]]:
    """The path's bottom-centre points, or the box's bottom centre for an event with no path yet."""
    points = path_points(event)
    box = (event.get("data") or {}).get("box")
    if not points and box and len(box) >= 4:
        points = [(float(box[0]) + float(box[2]) / 2, float(box[1]) + float(box[3]))]
    return points


def zone_side(point: tuple[float, float], polygons: list[list[tuple[float, float]]]) -> str:
    """"in" a car zone, clearly "out" of all of them (past STREET_ZONE_MARGIN), or on the "edge"."""
    distances = [distance_to_polygon(point, poly) for poly in polygons]
    if any(d == 0.0 for d in distances):
        return "in"
    return "out" if all(d > STREET_ZONE_MARGIN for d in distances) else "edge"


def movement_of(event: dict[str, Any], polygons: list[list[tuple[float, float]]]) -> str | None:
    """
    What a car did in the car zones (see MOVEMENTS) by where its path began and ends; None when it
    can't be told (no box, or no zone outlines and it moved) or it only went by outside.
    """
    box = (event.get("data") or {}).get("box")
    points = event_points(event)
    if not box or len(box) < 4 or box[2] <= 0 or box[3] <= 0 or not points:
        return None
    still = len(points) < 2 or is_still(event)
    if not polygons:
        return "parked" if still else None
    first, last = zone_side(points[0], polygons), zone_side(points[-1], polygons)
    if first == "out" and last != "out":
        return "arrived"
    if first != "out" and last == "out":
        return "left"
    if first == "out" and last == "out":
        return None
    if still:
        return "parked"
    (ax, ay), (bx, by) = points[0], points[-1]
    return "moved" if ((ax - bx) ** 2 + (ay - by) ** 2) ** 0.5 >= max(float(box[2]), float(box[3])) else "parked"


def spot_of(event: dict[str, Any]) -> dict[str, float] | None:
    """Where the car ended up: its last bottom-centre point, and its box's size."""
    box = (event.get("data") or {}).get("box")
    points = event_points(event)
    if not box or len(box) < 4 or not points:
        return None
    return {"x": points[-1][0], "y": points[-1][1], "w": float(box[2]), "h": float(box[3])}


def near_spot(point: tuple[float, float], spot: dict[str, float] | None) -> float | None:
    """How far `point` is from `spot`, in the spot's box sizes; None past SPOT_MATCH."""
    if not spot:
        return None
    size = max(spot.get("w") or 0.0, spot.get("h") or 0.0)
    if size <= 0:
        return None
    d = ((point[0] - spot["x"]) ** 2 + (point[1] - spot["y"]) ** 2) ** 0.5 / size
    return d if d <= SPOT_MATCH else None


def parked_at(camera: str, point: tuple[float, float], at: float) -> str | None:
    """The remembered car parked at `point` on this camera at time `at`, the nearest if two are close."""
    fits = [
        (d, v["name"]) for v in vehicles_on(camera)
        if v["here"] and (v["last_seen"] or 0) >= at - VEHICLE_STALE_SECONDS and (d := near_spot(point, v["spot"])) is not None
    ]
    return min(fits)[1] if fits else None


def vacate(camera: str, spot: dict[str, float] | None, start: float, event_id: str | None, arriving: str | None) -> None:
    """
    A car pulled into `spot`: any other remembered car parked there that hasn't been seen since
    the arrival began isn't there any more.
    """
    if not spot:
        return
    for v in vehicles_on(camera):
        if v["name"] != arriving and v["here"] and (v["last_seen"] or 0) < start and near_spot((spot["x"], spot["y"]), v["spot"]) is not None:
            save_vehicle(dict(v, here=0))
            log.info("vehicle memory: %s's spot on %s taken by %s (car %s)", v["name"], camera, arriving or "an unnamed car", event_id)


def remember(camera: str, name: str | None, movement: str | None, event: dict[str, Any], start: float, seen: float) -> None:
    """What this event says about where the camera's cars are. Events are seen out of order, so an old one never undoes a newer one."""
    event_id = event.get("id")
    spot = spot_of(event)
    if name is None:
        if movement == "arrived":
            vacate(camera, spot, start, event_id, None)
        return
    if movement not in MOVEMENTS:
        return
    row = vehicle(camera, name)
    if row is not None and row["event_id"] != event_id:
        if movement == "left" and row["here"] and (row["since"] or 0) > seen:
            return  # it has come back since
        if movement == "arrived" and not row["here"] and (row["last_seen"] or 0) > seen:
            return  # it has left again since
        if movement in ("parked", "moved") and not row["here"] and (row["last_seen"] or 0) > start:
            return  # it left after this began
    if movement == "arrived":
        vacate(camera, spot, start, event_id, name)
    row = row or {"camera": camera, "name": name, "here": 0, "since": None, "last_seen": None, "looks": {}}
    if movement == "left":
        save_vehicle(dict(row, here=0, last_seen=max(row["last_seen"] or 0, seen), event_id=event_id))
        return
    since = start if movement == "arrived" or not row["here"] or row["since"] is None else row["since"]
    save_vehicle(dict(row, here=1, spot=spot or row.get("spot"), since=since, last_seen=max(row["last_seen"] or 0, seen), event_id=event_id))


def zone_of(event: dict[str, Any], zones_for_car: list[str], outlines: dict[str, list[tuple[float, float]]]) -> str:
    """
    The car zone the event was in: one Frigate tagged it with, else the one its path touched
    (Frigate misses a car that leaves briskly), else the camera's first.
    """
    tagged = [z for z in event.get("zones") or [] if z in zones_for_car]
    if tagged:
        return tagged[-1]
    points = event_points(event)
    touched = [z for z, outline in outlines.items() if z in zones_for_car and any(zone_side(p, [outline]) == "in" for p in points)]
    return touched[0] if touched else zones_for_car[0]


def in_car_zone(event: dict[str, Any], zones_for_car: list[str], polygons: list[list[tuple[float, float]]]) -> bool:
    """Tagged with a car zone, or a path that touched one, or that began on its edge and left (see "vehicle memory")."""
    if any(z in zones_for_car for z in event.get("zones") or []):
        return True
    if not polygons:
        return False
    sides = [zone_side(p, polygons) for p in event_points(event)]
    return "in" in sides or (len(sides) >= 2 and sides[0] == "edge" and sides[-1] == "out")


def path_samples(event: dict[str, Any]) -> list[list[Any]]:
    """The well-formed `[[x, y], time]` samples of `data.path_data`."""
    return [s for s in (event.get("data") or {}).get("path_data") or []
            if isinstance(s, list) and len(s) >= 2 and isinstance(s[0], list) and len(s[0]) >= 2]


def before_switch(event: dict[str, Any], polygons: list[list[tuple[float, float]]]) -> dict[str, Any]:
    """
    The event with its path cut where it jumps across a car zone's edge (the tracker moving to
    another car), and without Frigate's zone tags, which may have come from the part after the
    jump; the event itself otherwise. Each zone's edge counts, so a jump from one car zone into
    another is a switch too.
    """
    samples = path_samples(event)
    for i in range(1, len(samples)):
        (ax, ay), (bx, by) = samples[i - 1][0][:2], samples[i][0][:2]
        crossed = any((zone_side((ax, ay), [poly]) == "out") != (zone_side((bx, by), [poly]) == "out") for poly in polygons)
        if crossed and ((bx - ax) ** 2 + (by - ay) ** 2) ** 0.5 > TRACK_SWITCH_JUMP:
            return dict(event, zones=[], data=dict(event["data"], path_data=samples[:i]))
    return event


def came_in_at(event: dict[str, Any], polygons: list[list[tuple[float, float]]]) -> float | None:
    """When a car that arrived came in: the time of its path's last point clearly outside every car zone."""
    outside = [float(s[1]) for s in path_samples(event) if zone_side((float(s[0][0]), float(s[0][1])), polygons) == "out"]
    return outside[-1] if outside else None


def frigate_name(event: dict[str, Any]) -> tuple[str | None, float | None]:
    name, score = sub_label_of(event)
    return (None, None) if not name or name.lower() in NOT_A_NAME else (name, score)


def observe_car(event: dict[str, Any], now: float | None = None) -> dict[str, Any] | None:
    """
    Files one car event in the vehicle memory: its name (Frigate's, else the remembered car parked
    where it began), how it got it, and what it did. Answers its sighting; None for anything that
    isn't a car in a car zone.
    """
    now = time.time() if now is None else now
    camera = event.get("camera", "")
    zones_for_car = car_zones().get(camera, [])
    polygons = car_zone_polygons().get(camera, [])
    if event.get("label") != "car" or not event.get("id") or not zones_for_car:
        return None
    event = before_switch(event, polygons) if polygons else event
    if not in_car_zone(event, zones_for_car, polygons):
        return None
    event_id = event["id"]
    prev = sighting(event_id) or {}
    name, _ = frigate_name(event)
    movement = movement_of(event, polygons)
    start = float(event.get("start_time") or now)
    came = came_in_at(event, polygons) if movement == "arrived" else None
    late = came is not None and came - start > LATE_ARRIVAL_SECONDS
    if late:
        name, start = None, came  # the event's name is the car the tracker followed before this one
    how = ("tagged" if by_a_person(event) else "classifier") if name else None
    if name and prev.get("how") == "looked" and prev.get("name") == name:
        how = "looked"  # the name the vision model gave it, back from Frigate
    end = event.get("end_time")
    seen = float(end) if end is not None else now
    if late:
        how = "late"
    elif name is None:
        if prev.get("how") in ("parked", "looked", "not"):
            name, how = prev.get("name"), prev["how"]  # decided already: kept, confirmed, or turned down
        elif prev.get("how") in ("tagged", "classifier"):
            how = "not"  # Frigate's name was taken away (the second opinion cleared it): not that car, nor to be guessed at
        elif movement in ("parked", "moved", "left"):
            guess = parked_at(camera, event_points(event)[0], start)
            if guess:
                name, how = guess, "parked"
    if prev.get("name") and prev["name"] != name:
        # Its name was taken back (the second opinion cleared or swapped it): so is what it told the memory.
        with_db(lambda c: (c.execute("UPDATE vehicles SET here=0 WHERE camera=? AND name=? AND event_id=?", (camera, prev["name"], event_id)), c.commit()))
    remember(camera, name, movement, event, start, seen)
    story = dict(prev, event_id=event_id, camera=camera, name=name, how=how, movement=movement,
                 zone=zone_of(event, zones_for_car, car_zone_outlines().get(camera, {})), start=start, end=end, final=int(end is not None), at=now)
    save_sighting(story)
    return story


def vehicle_story(event_id: str) -> dict[str, Any] | None:
    """What the memory makes of one detection: its sighting, brought up to date while the car is still in view."""
    row = sighting(event_id)
    if row and row["final"]:
        return row
    event = event_detail(event_id)
    return observe_car(event) if event else row


def with_vehicle_memory(item: dict[str, Any]) -> dict[str, Any]:
    """
    The review item with its cars as the memory knows them: the names it put to them added to its
    sub_labels (kept apart from Frigate's own in `frigate_sub_labels`, so it can be told again),
    and `vehicles`, each car's name, how it got it and what it did. Never stops an alert: anything
    that goes wrong leaves the item as Frigate sent it.
    """
    data = item.get("data") or {}
    if "car" not in review_labels(item):
        return item
    try:
        own = list(data.get("frigate_sub_labels", data.get("sub_labels")) or [])
        stories = []
        for event_id in data.get("detections") or []:
            story = vehicle_story(event_id) if event_id else None
            if story:
                stories.append({"event_id": event_id, "name": story.get("name"), "how": story.get("how"), "movement": story.get("movement")})
        names = [s["name"] for s in stories if s["name"]]
        return {**item, "data": {**data, "frigate_sub_labels": own, "sub_labels": unique(own + names), "vehicles": stories}}
    except Exception as e:
        log.warning("vehicle memory for %s: %s", item.get("id"), e)
        return item


def parked_verdict(item: dict[str, Any]) -> str:
    """
    The motion gate again, now the cars have names: an alert with nothing in it but cars the memory
    knows, each of which stayed where it was parked, is Andrew's Tesla being re-detected, not news.
    "wait" while it is open (it may yet drive off), "skip" once it ends, else "push".
    """
    data = item.get("data") or {}
    stories = data.get("vehicles") or []
    detections = [d for d in data.get("detections") or [] if d]
    if (
        review_labels(item) != ["car"] or not stories or len(stories) != len(detections)
        or any(not s.get("name") or s.get("movement") != "parked" for s in stories)
    ):
        return "push"
    if item.get("end_time") is None and time.time() - float(item.get("start_time") or 0) < MOTION_WAIT_CAP_SECONDS:
        return "wait"
    return "skip"


def event_note(story: dict[str, Any]) -> str:
    """The event's description in Frigate: its story and what the vision model saw, e.g. "Andrew's Tesla left the driveway · blue tesla Model Y suv"."""
    parts = []
    if story.get("movement") in ("arrived", "left", "moved"):
        parts.append(told(display_name(story["name"]) if story.get("name") else "Car", story.get("zone"), story["movement"]))
    elif story.get("name") and story.get("how") in ("parked", "looked"):
        parts.append(told(display_name(story["name"]), story.get("zone"), "parked"))
    if story.get("saw"):
        parts.append(story["saw"])
    return " · ".join(parts)


def note_event(story: dict[str, Any]) -> None:
    """Writes the event's story into its Frigate description, once per change. Raises if Frigate won't take it."""
    note = event_note(story)
    if not note or note == story.get("noted"):
        return
    requests.post(f"{FRIGATE}/api/events/{story['event_id']}/description", json={"description": note}, timeout=10).raise_for_status()
    with_db(lambda c: (c.execute("UPDATE vehicle_sightings SET noted=? WHERE event_id=?", (note, story["event_id"])), c.commit()))
    story["noted"] = note


def renamed(summary: dict[str, Any], story: dict[str, Any]) -> bool:
    """Has Frigate's name for a finished event changed since it was filed (a person tagged it late, say)?"""
    if story.get("how") == "late":
        return False  # its name was never this car's
    name, _ = frigate_name(summary)
    if name is None:
        return story.get("how") in ("tagged", "classifier", "looked")
    return name != story.get("name")


# Once at start and then hourly, the round reads a whole day back (VEHICLE_STALE_SECONDS) rather
# than an hour, so a relay that was down or just deployed learns both the cars named in that time
# and the departures Frigate didn't tag, before it trusts any spot. At most this many pages.
VEHICLE_HISTORY_EVERY_SECONDS = 3600.0
VEHICLE_HISTORY_PAGES = 20
_vehicle_history_read: dict[str, float] = {}


def events_since(params: dict[str, Any], after: float, pages: int = 1) -> list[dict[str, Any]]:
    """Frigate's car events from `after` on, newest first, paging back up to `pages` pages of CAR_ZONE_PAGE."""
    found: dict[str, dict[str, Any]] = {}
    before = None
    for _ in range(pages):
        query = {**params, "after": after, "limit": CAR_ZONE_PAGE}
        if before is not None:
            query["before"] = before
        r = requests.get(f"{FRIGATE}/api/events", params=query, timeout=10)
        r.raise_for_status()
        page = r.json()
        fresh = [e for e in page if e.get("id") and e["id"] not in found]
        found.update({e["id"]: e for e in fresh})
        if len(page) < CAR_ZONE_PAGE or not fresh:
            return list(found.values())
        # `before` is strict on start_time: nudge it so an event sharing the oldest start isn't skipped.
        before = min(float(e.get("start_time") or 0) for e in page) + 0.001
    if pages > 1:
        log.warning("vehicle memory: more than %d pages of car events since %s; the oldest are not read", pages, clock_text(after))
    return list(found.values())


def vehicle_memory_round(now: float | None = None) -> None:
    """
    Files each camera's recent car events in the vehicle memory, oldest first: those in its car
    zones and any still in view; the cars Frigate didn't tag with a car zone, whose path may still
    show one leaving (Frigate misses a brisk departure); and named cars in its car zones, so the
    memory knows a car tagged before it started. The last hour each round, the last day hourly.
    """
    now = time.time() if now is None else now
    for camera, zones_for_car in car_zones().items():
        if not zones_for_car:
            continue
        history = now - _vehicle_history_read.get(camera, float("-inf")) >= VEHICLE_HISTORY_EVERY_SECONDS
        since, pages = (now - VEHICLE_STALE_SECONDS, VEHICLE_HISTORY_PAGES) if history else (now - VEHICLE_LOOKBACK_SECONDS, 1)
        in_zone = {"camera": camera, "label": "car", "zones": ",".join(zones_for_car)}
        summaries: dict[str, dict[str, Any]] = {}
        for found in (
            events_since(in_zone, since, pages),
            events_since({**in_zone, "in_progress": 1}, 0.0),
            events_since({"camera": camera, "label": "car"}, since, pages),
        ):
            summaries.update({s["id"]: s for s in found})
        if history:
            _vehicle_history_read[camera] = now
        for summary in sorted(summaries.values(), key=lambda s: float(s.get("start_time") or 0)):
            story = sighting(summary["id"])
            if not (story and story["final"] and not renamed(summary, story)):
                if checked(summary["id"], "memory"):
                    continue  # finished outside every car zone
                event = event_detail(summary["id"])
                story = observe_car(event, now) if event else None
                if story is None and event and event.get("end_time") is not None:
                    record_check(summary["id"], "memory", "outside")
            # The vision model rewrites it with what it saw, when it looks.
            if story and story["final"]:
                try:
                    note_event(story)
                except Exception as e:
                    log.warning("car %s: Frigate didn't take its description: %s", story["event_id"], e)


def learn_looks(looks: dict[str, Any], description: dict[str, str]) -> dict[str, Any]:
    """A car's looks with one more daylight description in: every colour it has been seen as (the latest few), its make, model and body."""
    looks = dict(looks or {})
    colour = description.get("colour")
    if colour and colour != "unknown":
        colours = [c for c in looks.get("colour") or [] if c != colour]
        looks["colour"] = (colours + [colour])[-LOOKS_COLOURS_MAX:]
    for key in ("make", "model", "body"):
        value = description.get(key)
        if value and value not in ("unknown", "other"):
            looks[key] = value
    return looks


def expected_looks(name: str, learned: dict[str, Any]) -> dict[str, Any]:
    """
    What the car must look like: its HOUSEHOLD_CARS entry, else the make the model has read off it.
    Learned colours rule nothing out: one car is several colours to a camera over a day.
    """
    if name in HOUSEHOLD_CARS:
        return HOUSEHOLD_CARS[name]
    return {"make": learned["make"]} if (learned or {}).get("make") else {}


def vehicle_picture_path(camera: str, name: str) -> str | None:
    """Where a car's reference picture is kept, next to relay.db; None for a name that can't be a file name."""
    if not DATASET_NAME.fullmatch(camera) or not DATASET_NAME.fullmatch(name):
        return None
    return os.path.join(os.path.dirname(DB_PATH), "vehicles", f"{camera}--{name}.jpg")


def reference_picture(camera: str, name: str) -> bytes | None:
    path = vehicle_picture_path(camera, name)
    if not path or not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        return f.read()


def learn_vehicle(camera: str, name: str, description: dict[str, str], picture: bytes, tagged: bool) -> bool:
    """
    Keeps what the vision model saw of a car we know is `name`. Daylight only: an infrared picture
    shows no colour and compares badly. A person's tag always replaces the reference picture; any
    other known sighting only supplies the first.
    """
    if description.get("colour") in (None, "unknown"):
        return False
    row = vehicle(camera, name) or {"camera": camera, "name": name, "here": 0, "spot": None, "since": None, "last_seen": None, "event_id": None, "looks": {}}
    save_vehicle(dict(row, looks=learn_looks(row["looks"], description)))
    path = vehicle_picture_path(camera, name)
    if path and (tagged or not os.path.exists(path)):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(picture)
        log.info("vehicle memory: reference picture of %s on %s kept (%s)", name, camera, "tagged" if tagged else "first")
    return True


def same_car_prompt(name: str, looks: dict[str, Any]) -> str:
    """The comparison's question, describing the car by its profile where it has one, else by what the model has seen of it."""
    looks = dict(looks or {}, **{k: v for k, v in (HOUSEHOLD_CARS.get(name) or {}).items() if k != "plate"})
    colours = looks.get("colour") or []
    colours = [colours] if isinstance(colours, str) else colours
    seen = " ".join(str(v) for v in colours[-1:] + [looks.get(k) for k in ("make", "model", "body")] if v)
    return (
        f"Both pictures are crops from the same home security camera. The first is {display_name(name)}"
        + (f" ({seen})" if seen else "")
        + ", a car that parks here. Is the vehicle in the centre of the second picture the same car? "
        "Compare body shape, colour, roof, wheels, lights and any stickers or damage, not the lighting. "
        "Answer \"unsure\" if either picture is too dark, blurred or cut off to tell."
    )


def same_car(reference: bytes, picture: bytes, prompt: str) -> str:
    """The vision model's "yes", "no" or "unsure": is the car in `picture` the one in `reference`?"""
    import base64

    r = requests.post(f"{OLLAMA}/api/chat", json={
        "model": VLM_MODEL,
        "messages": [{"role": "user", "content": prompt, "images": [base64.b64encode(reference).decode(), base64.b64encode(picture).decode()]}],
        "format": SAME_CAR_SCHEMA,
        "stream": False,
        "keep_alive": "24h",
        "options": {"temperature": 0, "num_ctx": 8192, "num_gpu": 99},
    }, timeout=120)
    r.raise_for_status()
    return json.loads(r.json()["message"]["content"]).get("same", "unsure")


def compare_cars(camera: str, name: str, looks: dict[str, Any], picture: bytes) -> str | None:
    """`same_car` against the car's reference picture: None when there is no picture, "unsure" when the model can't answer."""
    reference = reference_picture(camera, name)
    if reference is None:
        return None
    try:
        return same_car(reference, picture, same_car_prompt(name, looks))
    except Exception as e:
        log.warning("vehicle memory: comparing with %s's picture failed: %s", name, e)
        return "unsure"


def memory_verdict(description: dict[str, str], expected: dict[str, Any], same: str | None) -> str:
    """
    Whether the car the memory put a name to is that car: "confirm", "reject" or "unsure". Looks
    that can't be the car (a make or colour it never is) or a "no" from the comparison reject it; a
    "yes" confirms it, and so, with no reference picture to compare, does the model reading the
    car's own make off it.
    """
    if expected and not household_matches(description, {"car": expected}):
        return "reject"
    if same == "no":
        return "reject"
    if same == "yes":
        return "confirm"
    if same is None and expected.get("make") and description.get("make") == expected["make"]:
        return "confirm"
    return "unsure"


def recognise_from_memory(camera: str, story: dict[str, Any], description: dict[str, str], picture: bytes) -> tuple[str | None, str]:
    """
    For an event Frigate left unnamed: the name the memory and the vision model agree on, and what
    was decided ("confirm", "reject", "unsure", or "" when there was nothing to decide). A car the
    memory named by its spot is checked against that car; one that arrived, against each remembered
    car that is away and has a reference picture.
    """
    if story.get("how") == "parked" and story.get("name"):
        name = story["name"]
        row = vehicle(camera, name) or {"looks": {}}
        verdict = memory_verdict(description, expected_looks(name, row["looks"]), compare_cars(camera, name, row["looks"], picture))
        return (name if verdict != "reject" else None), verdict
    if story.get("movement") == "arrived" and not story.get("name") and story.get("how") != "not":
        yes = []
        for row in vehicles_on(camera):
            same = None if row["here"] else compare_cars(camera, row["name"], row["looks"], picture)
            if same == "yes" and memory_verdict(description, expected_looks(row["name"], row["looks"]), same) == "confirm":
                yes.append(row["name"])
        return (yes[0], "confirm") if len(yes) == 1 else (None, "")
    return None, ""


def vehicle_memory_snapshot(now: float | None = None) -> dict[str, Any]:
    """Every remembered car and the last day's comings and goings, for GET /vehicles."""
    now = time.time() if now is None else now
    cameras = with_db(lambda c: [r[0] for r in c.execute("SELECT DISTINCT camera FROM vehicles ORDER BY camera")])
    cars = [
        {"camera": v["camera"], "name": v["name"], "display_name": display_name(v["name"]), "here": bool(v["here"]),
         "since": v["since"], "last_seen": v["last_seen"], "looks": v["looks"],
         "has_picture": reference_picture(v["camera"], v["name"]) is not None}
        for camera in cameras for v in vehicles_on(camera)
    ]
    rows = with_db(lambda c: c.execute(
        f"SELECT {', '.join(_SIGHTING_COLUMNS)} FROM vehicle_sightings WHERE start>=? AND movement IN ('arrived','left','moved')"
        " ORDER BY start DESC LIMIT 200", (now - 24 * 3600,)).fetchall())
    events = [dict(zip(_SIGHTING_COLUMNS, r)) for r in rows]
    return {
        "vehicles": cars,
        "recent": [
            {"event_id": e["event_id"], "camera": e["camera"], "name": e["name"], "display_name": display_name(e["name"]) if e["name"] else None,
             "how": e["how"], "movement": e["movement"], "start": e["start"], "end": e["end"],
             "text": told(display_name(e["name"]) if e["name"] else "Car", e["zone"], e["movement"])}
            for e in events
        ],
    }


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


# ---------------------------------------------------------------- uptime

# "I couldn't connect" has half a dozen possible culprits between a phone and a camera, and on
# 2026-10-04 it took an afternoon in the journal to learn the box had been fine and the phone's
# Tailscale was off. So once a minute the relay tries each link of that chain from the box's side
# and keeps the answers in relay.db (`uptime`), and `/status` draws them: which link was down,
# when, for how long, and which of the household's devices Tailscale could see. A minute with no
# row is a minute the relay wasn't running, which is how a reboot or a dead box shows up.
UPTIME_EVERY_SECONDS = 60
UPTIME_KEEP_DAYS = 90
# Two samples further apart than this have a hole between them: the relay was down.
UPTIME_GAP_SECONDS = UPTIME_EVERY_SECONDS * 2.5
# tailscaled's LocalAPI socket, mounted into the container (docker-compose.yml). Without it the
# Tailscale check and the device list are left out rather than shown as down.
TAILSCALE_SOCKET = os.environ.get("TAILSCALE_SOCKET", "/var/run/tailscale/tailscaled.sock")
# Anything that answers on 443 by address, so this check doesn't lean on DNS.
UPTIME_INTERNET_HOSTS = (("1.1.1.1", 443), ("8.8.8.8", 443))
# A name the relay itself needs resolved to push. Every heartbeat failure up to 2026-10-04 was
# "Could not resolve host", while the router and the internet were both fine.
UPTIME_DNS_NAME = os.environ.get("UPTIME_DNS_NAME", "fcm.googleapis.com")
UPTIME_DNS_SECONDS = 8.0
# The order the app and the page show them in: the box's own links outward, then what it serves.
# "server" is not sampled: it is whether there was a sample at all.
UPTIME_CHECKS = ("server", "router", "internet", "dns", "tailscale", "frigate", "cameras", "live")
UPTIME_MAX_OUTAGES = 50
# A link that drops, comes back for a minute or two and drops again is one outage, not ten.
UPTIME_MERGE_SECONDS = 300
# What each check can't work without: while the router is unreachable there is no internet, and
# without the internet no name resolves. An outage wholly inside its cause's is left off the
# list (the bars still show it), so one router failure reads as one line.
UPTIME_NEEDS = {"internet": "router", "dns": "internet", "cameras": "frigate"}


def default_gateway(proc_net_route: str) -> str | None:
    """The default route's gateway from a /proc/net/route table (the lowest metric wins), dotted; None without one."""
    best: tuple[int, str] | None = None
    for line in proc_net_route.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 8 or fields[1] != "00000000" or fields[7] != "00000000":
            continue
        try:
            raw = bytes.fromhex(fields[2])
            metric = int(fields[6])
        except ValueError:
            continue
        if len(raw) != 4 or raw == b"\x00\x00\x00\x00":
            continue
        address = ".".join(str(b) for b in reversed(raw))
        if best is None or metric < best[0]:
            best = (metric, address)
    return best[1] if best else None


def tcp_reachable(host: str, port: int, timeout: float = 3.0) -> bool:
    """Whether [host] answers on [port] at all: a refusal is an answer, silence is not."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except ConnectionRefusedError:
        return True
    except OSError:
        return False


# The lookup `resolves` last started, kept so a resolver that hangs holds one thread, not one more a minute.
_dns_lookup: dict[str, threading.Thread | None] = {"thread": None}


def resolves(name: str, timeout: float = UPTIME_DNS_SECONDS) -> bool:
    """
    Whether [name] resolves within [timeout]. getaddrinfo has no timeout of its own, so it runs on
    a thread that is left behind when slow. One at a time: while an earlier lookup is still stuck
    in the resolver the name isn't resolving, and starting another behind it would add a thread
    every minute for as long as the resolver hangs.
    """
    earlier = _dns_lookup["thread"]
    if earlier is not None and earlier.is_alive():
        return False
    answer: list[bool] = []

    def look() -> None:
        try:
            socket.getaddrinfo(name, 443)
            answer.append(True)
        except OSError:
            answer.append(False)

    thread = threading.Thread(target=look, name="uptime-dns", daemon=True)
    _dns_lookup["thread"] = thread
    thread.start()
    thread.join(timeout)
    return bool(answer and answer[0])


class _UnixHTTPConnection(http.client.HTTPConnection):
    """HTTP over a unix socket: tailscaled's LocalAPI is one."""

    def __init__(self, path: str, timeout: float):
        super().__init__("local-tailscaled.sock", timeout=timeout)
        self._path = path

    def connect(self) -> None:
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self._path)


def tailscale_status(path: str | None = None, timeout: float = 5.0) -> dict[str, Any] | None:
    """tailscaled's own status (what `tailscale status --json` prints); None when it can't be asked."""
    connection = _UnixHTTPConnection(path or TAILSCALE_SOCKET, timeout)
    try:
        connection.request("GET", "/localapi/v0/status")
        response = connection.getresponse()
        if response.status != 200:
            return None
        return json.loads(response.read())
    except (OSError, ValueError, http.client.HTTPException):
        return None
    finally:
        connection.close()


def epoch_of(stamp: str | None) -> float | None:
    """An RFC 3339 time from tailscaled ("2026-10-04T05:27:17.1Z") as epoch seconds; None for its zero time or anything unreadable."""
    if not stamp or stamp.startswith("0001-"):
        return None
    try:
        return datetime.fromisoformat(re.sub(r"\.\d+", "", stamp).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def tailnet_devices(status: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """
    The household's devices on the tailnet by their MagicDNS name ("pixel-10-pro-xl"), each with
    whether Tailscale has it online, when it was last seen and its OS. Funnel's ingress nodes are
    Tailscale's own, not anyone's phone, and are left out.
    """
    devices: dict[str, dict[str, Any]] = {}
    for peer in (status.get("Peer") or {}).values():
        host = peer.get("HostName") or ""
        if host == "funnel-ingress-node":
            continue
        name = (peer.get("DNSName") or "").split(".")[0] or host
        if not name:
            continue
        devices[name] = {"online": bool(peer.get("Online")), "last_seen": epoch_of(peer.get("LastSeen")), "os": peer.get("OS") or ""}
    return devices


def uptime_probe() -> tuple[dict[str, bool], dict[str, dict[str, Any]]]:
    """One look at every link: the checks that could be made (True up, False down), and the tailnet's devices."""
    checks: dict[str, bool] = {}
    try:
        with open("/proc/net/route") as f:
            gateway = default_gateway(f.read())
    except OSError:
        gateway = None
    checks["router"] = bool(gateway) and (tcp_reachable(gateway, 53) or tcp_reachable(gateway, 80))
    checks["internet"] = any(tcp_reachable(host, port) for host, port in UPTIME_INTERNET_HOSTS)
    checks["dns"] = resolves(UPTIME_DNS_NAME)
    devices: dict[str, dict[str, Any]] = {}
    if os.path.exists(TAILSCALE_SOCKET):
        status = tailscale_status()
        checks["tailscale"] = bool(status and status.get("BackendState") == "Running" and (status.get("Self") or {}).get("Online"))
        if status:
            devices = tailnet_devices(status)
    try:
        cameras = (requests.get(f"{FRIGATE}/api/stats", timeout=5).json().get("cameras") or {})
        checks["frigate"] = True
        if cameras:
            checks["cameras"] = all(float(cam.get("camera_fps") or 0) > 0 for cam in cameras.values())
    except Exception:
        checks["frigate"] = False
    try:
        checks["live"] = requests.get(f"{GO2RTC}/api", timeout=5).status_code == 200
    except Exception:
        checks["live"] = False
    return checks, devices


def record_uptime(at: int, checks: dict[str, bool], devices: dict[str, dict[str, Any]]) -> None:
    """Keeps the minute's sample, and what Tailscale says of each device now (its OS and when it was last seen)."""
    with_db(lambda c: (
        c.execute("INSERT OR REPLACE INTO uptime VALUES (?,?,?)", (
            at, json.dumps({k: int(v) for k, v in checks.items()}), json.dumps({k: int(v["online"]) for k, v in devices.items()}))),
        c.commit(),
    ))
    if devices:
        known = state_get("uptime_devices") or {}
        for name, device in devices.items():
            seen = at if device["online"] else (device["last_seen"] or (known.get(name) or {}).get("last_seen"))
            known[name] = {"os": device["os"], "last_seen": seen}
        state_set("uptime_devices", known)
    if at % 3600 < UPTIME_EVERY_SECONDS:
        with_db(lambda c: (c.execute("DELETE FROM uptime WHERE at<?", (at - UPTIME_KEEP_DAYS * 86400,)), c.commit()))


def uptime_forever() -> None:
    """Samples on the minute, for as long as the relay runs."""
    while True:
        now = time.time()
        time.sleep(UPTIME_EVERY_SECONDS - now % UPTIME_EVERY_SECONDS)
        at = int(time.time()) // UPTIME_EVERY_SECONDS * UPTIME_EVERY_SECONDS
        try:
            checks, devices = uptime_probe()
            record_uptime(at, checks, devices)
            down = sorted(k for k, v in checks.items() if not v)
            if down:
                log.warning("uptime: down: %s", ", ".join(down))
        except Exception:
            log.exception("uptime sample failed")


def bucket_state(up: int, down: int) -> str:
    """One bucket's letter: "u" up throughout, "x" down throughout, "d" some of each, "n" nothing measured."""
    if up and down:
        return "d"
    if down:
        return "x"
    return "u" if up else "n"


def uptime_runs(samples: list[tuple[int, bool]], until: float) -> list[dict[str, Any]]:
    """
    The stretches a check was down, from its (time, up) samples in order. A stretch starts at the
    first down sample and ends at the next up one; a hole in the samples ends it a sample after
    its last down one (what happened in the hole belongs to "server"); one still down at the last
    sample, with that sample fresh as of [until], has no end yet.
    """
    runs: list[dict[str, Any]] = []
    start: int | None = None
    last: int | None = None
    for at, up in samples:
        if start is not None and last is not None and at - last > UPTIME_GAP_SECONDS:
            runs.append({"start": start, "end": last + UPTIME_EVERY_SECONDS, "cut": True})
            start = None
        if not up and start is None:
            start = at
        elif up and start is not None:
            runs.append({"start": start, "end": at})
            start = None
        last = at
    if start is not None and last is not None:
        runs.append({"start": start, "end": None if until - last <= UPTIME_GAP_SECONDS else last + UPTIME_EVERY_SECONDS})
    # Flapping joins up; a stretch cut short by a hole doesn't join the next, the hole being "server"'s.
    merged: list[dict[str, Any]] = []
    for run in runs:
        previous = merged[-1] if merged else None
        if previous and not previous.get("cut") and previous["end"] is not None and run["start"] - previous["end"] <= UPTIME_MERGE_SECONDS:
            previous["end"] = run["end"]
            previous["cut"] = run.get("cut", False)
        else:
            merged.append(dict(run))
    return [{"start": run["start"], "end": run["end"]} for run in merged]


def covered(outage: dict[str, Any], by: list[dict[str, Any]], until: float) -> bool:
    """Whether [outage] lies inside one of [by], give or take a sample at each end."""
    end = outage["end"] if outage["end"] is not None else until
    return any(
        other["start"] - UPTIME_EVERY_SECONDS <= outage["start"] and end <= (other["end"] if other["end"] is not None else until) + UPTIME_EVERY_SECONDS
        for other in by
    )


def uptime_summary(rows: list[tuple[int, dict[str, int], dict[str, int]]], since: float, until: float, buckets: int,
                   recording_since: float | None, known_devices: dict[str, dict[str, Any]] | None = None,
                   previous_at: int | None = None) -> dict[str, Any]:
    """
    What `/status/data` answers: [rows] (time, checks, devices; in order, inside [since, until])
    cut into [buckets] equal spans, each check and each device a string of `bucket_state` letters,
    how much of the time each check was up, and the stretches something was down, newest first.
    "server" is the samples themselves: a span with fewer than there should have been was a span
    the relay wasn't running for. Nothing before [recording_since] (the first sample ever kept)
    counts against anything: it wasn't being measured. [previous_at] is the last sample before
    [since], so a hole the range opens in the middle of is still listed, from where it began.
    """
    width = (until - since) / buckets
    index = lambda at: min(buckets - 1, max(0, int((at - since) / width)))
    names = [k for k in UPTIME_CHECKS if k != "server"] + sorted({k for _, checks, _ in rows for k in checks} - set(UPTIME_CHECKS))
    tally = {k: [[0, 0] for _ in range(buckets)] for k in names}
    series: dict[str, list[tuple[int, bool]]] = {k: [] for k in names}
    device_tally: dict[str, list[list[int]]] = {}
    device_last: dict[str, int] = {}
    counts = [0] * buckets
    for at, checks, devices in rows:
        b = index(at)
        counts[b] += 1
        for key, value in checks.items():
            tally[key][b][0 if value else 1] += 1
            series[key].append((at, bool(value)))
        for name, online in devices.items():
            device_tally.setdefault(name, [[0, 0] for _ in range(buckets)])[b][0 if online else 1] += 1
            if online:
                device_last[name] = at

    # "server": how many of the samples each span should hold are there.
    server = []
    recorded = expected_total = 0
    for b in range(buckets):
        start, end = since + b * width, min(since + (b + 1) * width, until)
        measured_from = max(start, recording_since) if recording_since is not None else end
        expected = int((end - measured_from) // UPTIME_EVERY_SECONDS) if end > measured_from else 0
        if expected <= 0:
            server.append("n" if not counts[b] else "u")
            continue
        got = min(counts[b], expected)
        recorded += got
        expected_total += expected
        # A sample or two short is the edge of the span or a slow probe, not an outage.
        server.append("u" if expected - got <= 1 else ("d" if got else "x"))

    outages = []
    previous = previous_at
    for at, _, _ in rows:
        if previous is not None and at - previous > UPTIME_GAP_SECONDS:
            outages.append({"check": "server", "start": previous + UPTIME_EVERY_SECONDS, "end": at})
        previous = at
    # No sample since the last one, right up to the end of the range. It ends there rather than
    # being left open: whoever is asking is being answered, so the relay is running again.
    if previous is not None and until - previous > UPTIME_GAP_SECONDS:
        outages.append({"check": "server", "start": previous + UPTIME_EVERY_SECONDS, "end": until})
    runs = {key: uptime_runs(series[key], until) for key in names}
    for key in names:
        causes, cause = [], UPTIME_NEEDS.get(key)
        while cause:
            causes += runs.get(cause, [])
            cause = UPTIME_NEEDS.get(cause)
        outages += [{"check": key, **run} for run in runs[key] if not covered(run, causes, until)]
    for outage in outages:
        outage["seconds"] = int((outage["end"] if outage["end"] is not None else until) - outage["start"])
    outages.sort(key=lambda o: o["start"], reverse=True)

    fresh = bool(rows) and until - rows[-1][0] <= UPTIME_GAP_SECONDS
    latest = rows[-1][1] if fresh else {}
    # Whether a device is online now is the newest sample's word alone. One it doesn't name (the
    # tailnet couldn't be read that minute) is unknown, not whatever an older sample said.
    latest_devices = rows[-1][2] if fresh else {}
    result_checks = [{
        "key": "server", "states": "".join(server),
        "up_fraction": (recorded / expected_total) if expected_total else None,
        "down_seconds": (expected_total - recorded) * UPTIME_EVERY_SECONDS, "up": True if fresh else None,
    }]
    for key in names:
        up = sum(t[0] for t in tally[key])
        down = sum(t[1] for t in tally[key])
        if not up and not down:
            continue
        result_checks.append({
            "key": key, "states": "".join(bucket_state(*t) for t in tally[key]),
            "up_fraction": up / (up + down), "down_seconds": down * UPTIME_EVERY_SECONDS,
            "up": bool(latest[key]) if key in latest else None,
        })
    known_devices = known_devices or {}
    result_devices = [{
        "name": name, "os": (known_devices.get(name) or {}).get("os", ""),
        "online": bool(latest_devices[name]) if name in latest_devices else None,
        "last_seen": device_last.get(name) or (known_devices.get(name) or {}).get("last_seen"),
        # A phone dozes on and off the tailnet all day: seen at all in a span is on the tailnet for it.
        "states": "".join("u" if t[0] else bucket_state(*t) for t in device_tally[name]),
    } for name in sorted(device_tally)]
    return {
        "since": since, "until": until, "bucket_seconds": width, "sample_seconds": UPTIME_EVERY_SECONDS,
        "recording_since": recording_since, "checks": result_checks, "devices": result_devices,
        "outages": outages[:UPTIME_MAX_OUTAGES],
    }


def uptime_report(hours: float, buckets: int, now: float | None = None) -> dict[str, Any]:
    """The last [hours] of samples from relay.db as an `uptime_summary`."""
    until = now if now is not None else time.time()
    since = until - hours * 3600
    rows = with_db(lambda c: c.execute("SELECT at, checks, devices FROM uptime WHERE at>=? AND at<=? ORDER BY at", (since, until)).fetchall())
    first = with_db(lambda c: c.execute("SELECT MIN(at) FROM uptime").fetchone()[0])
    before = with_db(lambda c: c.execute("SELECT MAX(at) FROM uptime WHERE at<?", (since,)).fetchone()[0])
    parsed = [(at, json.loads(checks or "{}"), json.loads(devices or "{}")) for at, checks, devices in rows]
    return uptime_summary(parsed, since, until, buckets, first, state_get("uptime_devices"), before)


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


class Authority(BaseModel):
    # The install whose away switch alone decides away mode (see "presence authority").
    device_id: str


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


def frigate_profile(request: Request) -> dict[str, Any] | None:
    """Frigate's `/api/profile` for the session the request carries, or None when it carries none Frigate will vouch for. Never raises."""
    cookie = request.headers.get("cookie")
    if not cookie:
        return None
    try:
        r = requests.get(f"{FRIGATE_AUTH}/api/profile", headers={"Cookie": cookie}, timeout=5)
        profile = r.json() if r.status_code == 200 else None
    except Exception:
        return None
    return profile if isinstance(profile, dict) else None


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
    load_car_profiles()
    threading.Thread(target=poll_forever, name="poller", daemon=True).start()
    threading.Thread(target=car_check_forever, name="car-check", daemon=True).start()
    threading.Thread(target=boot_report, name="boot-report", daemon=True).start()
    threading.Thread(target=uptime_forever, name="uptime", daemon=True).start()
    threading.Thread(target=bank_forever, name="bank-sync", daemon=True).start()
    log.info("relay up: frigate=%s project=%s poll=%ss", FRIGATE, PROJECT, POLL_SECONDS)


@app.get("/health")
def health() -> dict[str, Any]:
    devices = with_db(lambda c: c.execute("SELECT COUNT(*) FROM devices").fetchone()[0])
    sent = with_db(lambda c: c.execute("SELECT COUNT(*) FROM sent").fetchone()[0])
    return {"ok": True, "devices": devices, "alerts_seen": sent, "project": PROJECT}


UPTIME_CHECK_NAMES = {
    "server": "Server running", "router": "Home router", "internet": "Internet", "dns": "Name lookups (DNS)",
    "tailscale": "Tailscale on the server", "frigate": "Frigate", "cameras": "Cameras sending video", "live": "Live video (go2rtc)",
}


@app.get("/status/data")
def status_data(request: Request, hours: float = 24, buckets: int = 96) -> dict[str, Any]:
    """What was up and when over the last [hours] (see "uptime"), for the app's status screen and `/status`."""
    require_frigate_session(request)
    hours = min(max(hours, 1.0), UPTIME_KEEP_DAYS * 24.0)
    body = uptime_report(hours, min(max(buckets, 12), 288))
    for check in body["checks"]:
        check["name"] = UPTIME_CHECK_NAMES.get(check["key"], humanize(check["key"]))
    return body


# The same picture for a browser, at http://<box>:8787/status on the home network or the tailnet.
# The page itself holds nothing; its data rides on the Frigate session cookie, which a browser
# signed in to Frigate on :8971 sends to :8787 too (cookies are per host, not per port).
STATUS_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>HomeSafe status</title>
<style>
:root{--bg:#fcfcfb;--ink:#1a1a19;--muted:#6b6b68;--line:#e4e4e0;--card:#fff;--up:#0ca30c;--part:#fab219;--down:#d03b3b;--none:#e4e4e0}
@media (prefers-color-scheme: dark){:root{--bg:#1a1a19;--ink:#f1f1ee;--muted:#a3a39f;--line:#33332f;--card:#232321;--none:#33332f}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,sans-serif}
main{max-width:760px;margin:0 auto;padding:20px 16px 48px}h1{font-size:20px;margin:0 0 4px;font-weight:600}h2{font-size:15px;margin:28px 0 10px;font-weight:600}
.sub{color:var(--muted);font-size:13px}.ranges{display:flex;gap:8px;margin:16px 0}
button{font:inherit;padding:6px 14px;border-radius:8px;border:1px solid var(--line);background:transparent;color:var(--ink);cursor:pointer}
button[aria-pressed=true]{background:var(--card);border-color:var(--muted);font-weight:600}
.row{padding:10px 0;border-top:1px solid var(--line)}.head{display:flex;justify-content:space-between;gap:12px;align-items:baseline}
.num{font-variant-numeric:tabular-nums;color:var(--muted);font-size:13px;white-space:nowrap}
.bar{display:flex;gap:1px;height:22px;margin-top:6px}.bar i{flex:1;border-radius:2px;background:var(--none)}
.bar i.u{background:var(--up);opacity:.55}.bar i.d{background:var(--part)}.bar i.x{background:var(--down)}
.bar:focus-visible{outline:2px solid var(--ink);outline-offset:3px}.bar i.sel{box-shadow:0 0 0 2px var(--ink);opacity:1}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:8px;background:var(--none)}.dot.u{background:var(--up)}.dot.x{background:var(--down)}
.axis{display:flex;justify-content:space-between;color:var(--muted);font-size:12px;margin-top:4px}
.legend{display:flex;gap:14px;flex-wrap:wrap;color:var(--muted);font-size:12px;margin:6px 0 2px}.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px;vertical-align:-1px}
#tip{min-height:20px;color:var(--muted);font-size:13px;margin-top:8px}table{width:100%;border-collapse:collapse;font-size:14px}td{padding:7px 0;border-top:1px solid var(--line)}td:last-child{text-align:right;color:var(--muted);white-space:nowrap}
</style></head><body><main>
<h1>HomeSafe status</h1><div class="sub" id="sub">Loading…</div>
<div class="ranges" id="ranges"></div>
<div class="legend"><span><i style="background:var(--up);opacity:.55"></i>Up</span><span><i style="background:var(--part)"></i>Partly down</span><span><i style="background:var(--down)"></i>Down</span><span><i style="background:var(--none)"></i>Not measured</span></div>
<div id="tip" aria-live="polite">Hover or tap a bar for its time, or focus one and use the arrow keys</div>
<div id="checks"></div><h2>Devices on Tailscale</h2><div id="devices"></div><h2>Outages</h2><div id="outages"></div>
</main><script>
const RANGES=[[24,"24 hours"],[168,"7 days"],[720,"30 days"]];let hours=24;
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const when=t=>new Date(t*1000).toLocaleString([], {month:"short",day:"numeric",hour:"numeric",minute:"2-digit"});
const span=s=>s<60?Math.round(s)+" s":s<5400?Math.round(s/60)+" min":s<172800?(s/3600).toFixed(1)+" h":Math.round(s/86400)+" d";
const WORD={u:"up",d:"partly down",x:"down",n:"not measured"};
function bar(states,d,label){const text=i=>label+" · "+when(d.since+i*d.bucket_seconds)+" · "+WORD[states[i]],last=states.length-1;
return '<div class="bar" tabindex="0" role="slider" aria-label="'+esc(label)+' timeline" aria-valuemin="0" aria-valuemax="'+last+'" aria-valuenow="'+last+'" aria-valuetext="'+esc(text(last))+'">'+[...states].map((c,i)=>'<i class="'+c+'" data-t="'+esc(text(i))+'"></i>').join("")+"</div>"}
function pick(bar,i){const spans=bar.children;if(!spans.length)return;i=Math.max(0,Math.min(spans.length-1,i));bar.dataset.i=i;const t=spans[i].dataset.t;
bar.setAttribute("aria-valuenow",i);bar.setAttribute("aria-valuetext",t);document.querySelectorAll(".bar i.sel").forEach(s=>s.classList.remove("sel"));spans[i].classList.add("sel");document.getElementById("tip").textContent=t}
const at=bar=>bar.dataset.i===undefined?bar.children.length-1:+bar.dataset.i;
function draw(d){
document.getElementById("sub").textContent="Measured from the server, once a minute"+(d.recording_since?" since "+when(d.recording_since):"")+". Updated "+when(d.until)+".";
document.getElementById("checks").innerHTML=d.checks.map(c=>'<div class="row"><div class="head"><span><span class="dot '+(c.up===true?"u":c.up===false?"x":"")+'"></span>'+esc(c.name)+'</span><span class="num">'+(c.up_fraction==null?"not measured":(c.up_fraction*100).toFixed(c.up_fraction>0.999&&c.up_fraction<1?3:2)+"% up"+(c.down_seconds?" · "+span(c.down_seconds)+" down":""))+"</span></div>"+bar(c.states,d,c.name)+"</div>").join("")+'<div class="axis"><span>'+when(d.since)+"</span><span>"+when(d.until)+"</span></div>";
document.getElementById("devices").innerHTML=d.devices.length?d.devices.map(v=>'<div class="row"><div class="head"><span><span class="dot '+(v.online===true?"u":v.online===false?"x":"")+'"></span>'+esc(v.name)+'</span><span class="num">'+(v.online?"online":v.last_seen?"last seen "+when(v.last_seen):"not seen")+"</span></div>"+bar(v.states,d,v.name)+"</div>").join(""):'<div class="sub">The server is not reading Tailscale.</div>';
const names=Object.fromEntries(d.checks.map(c=>[c.key,c.name]));
document.getElementById("outages").innerHTML=d.outages.length?"<table>"+d.outages.map(o=>"<tr><td>"+esc(names[o.check]||o.check)+"</td><td>"+when(o.start)+(o.end?"":" · ongoing")+" · "+span(o.seconds)+"</td></tr>").join("")+"</table>":'<div class="sub">Nothing was down in this range.</div>';
}
function ranges(){document.getElementById("ranges").innerHTML=RANGES.map(r=>'<button aria-pressed="'+(r[0]===hours)+'" data-h="'+r[0]+'">'+r[1]+"</button>").join("")}
async function load(){ranges();try{const r=await fetch("status/data?hours="+hours,{credentials:"same-origin"});
if(r.status===401){document.getElementById("sub").innerHTML='Sign in to Frigate in this browser first: <a href="http://'+location.hostname+':8971/">open Frigate</a>, then reload this page.';return}
if(!r.ok)throw new Error("HTTP "+r.status);draw(await r.json())}catch(e){document.getElementById("sub").textContent="Couldn't load the status: "+e.message}}
document.getElementById("ranges").onclick=e=>{const h=e.target.dataset.h;if(h){hours=+h;load()}};
const hit=e=>e.target.dataset&&e.target.dataset.t&&e.target.parentElement.classList.contains("bar")?e.target:null;
document.body.addEventListener("pointerover",e=>{const s=hit(e);if(s)pick(s.parentElement,[...s.parentElement.children].indexOf(s))});
document.body.addEventListener("click",e=>{const s=hit(e);if(s)pick(s.parentElement,[...s.parentElement.children].indexOf(s))});
document.body.addEventListener("focusin",e=>{if(e.target.classList&&e.target.classList.contains("bar"))pick(e.target,at(e.target))});
document.body.addEventListener("keydown",e=>{const bar=e.target.classList&&e.target.classList.contains("bar")?e.target:null;if(!bar)return;const i=at(bar);
const to={ArrowLeft:i-1,ArrowDown:i-1,ArrowRight:i+1,ArrowUp:i+1,Home:0,End:bar.children.length-1}[e.key];if(to===undefined)return;e.preventDefault();pick(bar,to)});
const reading=()=>document.activeElement&&document.activeElement.classList.contains("bar");
load();setInterval(()=>{if(!reading())load()},60000);
</script></body></html>
"""


@app.get("/status")
def status_page() -> Response:
    return Response(content=STATUS_PAGE, media_type="text/html", headers={"Cache-Control": "no-store"})


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
    # A session, when the request carries a good one, both lets it in and says whose phone this
    # is. Only without one is the install's own secret (or a refusal) left to `authenticate`.
    profile = frigate_profile(request)
    user = str(profile.get("username") or "?") if profile else authenticate(request, device_id)
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
    # Whose phone it is, when the registration says: one made with the install's own secret and
    # no session (a push token rotating in the background) leaves who it was as it stood.
    if profile and profile.get("username"):
        with_db(lambda c: (c.execute("UPDATE devices SET user=?, role=? WHERE device_id=?", (str(profile["username"]), profile.get("role"), device_id)), c.commit()))
    log.info(
        "device registered by %s: %s (%s %s, push=%s, strangers only=%s, quiet=%s-%s %s, only away=%s, counts for away=%s)",
        user, device.name or "unnamed", device.platform, device.build, device.token is not None, device.quiet_familiar,
        device.quiet_start, device.quiet_end, device.tz, device.only_away, counts(device_id, device.platform, device.build, presence_authority()),
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


@app.put("/presence/authority")
def set_presence_authority(authority: Authority, request: Request) -> dict[str, Any]:
    """
    Makes one install the presence authority: from then on its away switch alone says whether the
    house is empty. An install may choose itself (its bearer secret); a signed-in user may choose any.
    """
    user = authenticate(request, authority.device_id)
    row = find_device(authority.device_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Unknown device")
    state_set(PRESENCE_KEY, row[0])
    snapshot = presence_snapshot(row[0])
    log.info("presence authority set by %s: %s -> everyone_away=%s", user, row[2] or row[0], snapshot["everyone_away"])
    return snapshot


@app.delete("/presence/authority")
def clear_presence_authority(request: Request, device: str | None = None) -> dict[str, Any]:
    """
    Hands the decision back to PRESENCE_DEVICE, or failing that to every counting phone. The
    authority itself may step down (its bearer secret, `device` naming it); a signed-in user may always.
    """
    current = state_get(PRESENCE_KEY)
    user = authenticate(request, device if device and device == current else None)
    state_set(PRESENCE_KEY, None)
    log.info("presence authority cleared by %s (was %s)", user, current)
    return presence_snapshot(device)


@app.get("/devices")
def list_devices(request: Request) -> list[dict[str, Any]]:
    require_frigate_session(request)
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, platform, name, created, last_seen, away, build, token IS NOT NULL, away_pending_since IS NOT NULL FROM devices"
    ).fetchall())
    authority = presence_authority()
    return [
        {"platform": p, "name": n, "created": cr, "last_seen": ls, "away": bool(a), "build": b, "counts_for_away": counts(d, p, b, authority), "push": bool(push), "pending_away": bool(pend)}
        for d, p, n, cr, ls, a, b, push, pend in rows
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



@app.post("/events/{event_id}/sub_label")
def tag_event(event_id: str, body: dict[str, Any], request: Request) -> Response:
    """
    A person naming an event (see "person tags"): the body is Frigate's own sub_label body, passed
    on to Frigate with the caller's session cookie so Frigate decides whether they may. What Frigate
    took is kept as that person's; an empty name takes the tag away. Answers Frigate's answer.
    """
    cookie = request.headers.get("cookie")
    if not cookie:
        raise HTTPException(status_code=401, detail="Frigate session cookie required")
    name = body.get("subLabel") or None
    if name is not None and not isinstance(name, str):
        raise HTTPException(status_code=400, detail="subLabel must be a string")
    try:
        r = requests.post(f"{FRIGATE_AUTH}/api/events/{event_id}/sub_label", json=body, headers={"Cookie": cookie}, timeout=10)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Frigate unreachable: {e}")
    if r.ok:
        user = require_frigate_session(request)
        record_person_tag(event_id, name, user)
        log.info("person tag by %s: %s <- %s", user, event_id, name or "(cleared)")
    return Response(content=r.content, status_code=r.status_code, media_type=r.headers.get("content-type", "application/json"))


@app.post("/events/{event_id}/not_a_person")
def not_a_person(event_id: str, request: Request, device: str | None = None) -> dict[str, Any]:
    """
    Someone saying a person detection was not a person (see "phantom people"): its box becomes a
    phantom spot on its camera, and the detection a phantom example for the person classifier (see
    "person check"). A user action: the session cookie, or, for a notification's "Not a person"
    button, which may wake the app with no session at all, the install's own secret and [device].
    Answers the spot, and how the example was filed (null when it wasn't: no classifier yet, or no
    picture of it left).
    """
    if not EVENT_ID.fullmatch(event_id):
        raise HTTPException(status_code=404, detail="No such event")
    user = authenticate(request, device)
    try:
        event = fetch_event(event_id)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Frigate unreachable: {e}")
    if event is None:
        raise HTTPException(status_code=404, detail="Frigate no longer has this detection")
    if event.get("label") != "person":
        raise HTTPException(status_code=400, detail="Only a person detection can be marked not a person")
    if face_name(event):
        raise HTTPException(status_code=400, detail="Frigate knows this face; it is someone")
    spot = mark_phantom(event, user)
    example = file_not_a_person(event)
    log.info("not a person, by %s: %s on %s at %s (example: %s)", user, event_id, spot["camera"], spot["box"], example)
    return {"ok": True, "spot": spot, "example": example}


@app.delete("/events/{event_id}/not_a_person")
def undo_not_a_person(event_id: str, request: Request, device: str | None = None) -> dict[str, Any]:
    """
    Takes a "not a person" back, as it was given (cookie, or an install's secret): the phantom spot
    goes, and so does the example it filed, out of the dataset before the next retrain.
    """
    user = authenticate(request, device)
    removed = unmark_phantom(event_id)
    unfiled = unfile_not_a_person(event_id)
    log.info("not a person taken back, by %s: %s (%s%s)", user, event_id, "removed" if removed else "wasn't marked",
             ", example unfiled" if unfiled else "")
    return {"ok": True, "removed": removed, "unfiled": unfiled}


@app.get("/phantoms")
def phantoms(request: Request, device: str | None = None) -> dict[str, Any]:
    """Every phantom spot, for the app to hide the same phantoms from its feed."""
    authenticate(request, device)
    return {"spots": phantom_spots(), "iou": PHANTOM_IOU}


@app.get("/vehicles")
def vehicles(request: Request, device: str | None = None) -> dict[str, Any]:
    """
    What the vehicle memory knows: each named car per camera, whether it is parked there now and
    since when, and the last day's arrivals, departures and moves. A signed-in user or a device.
    """
    authenticate(request, device)
    return vehicle_memory_snapshot()


def profile_json(name: str, profile: dict[str, Any]) -> dict[str, Any]:
    colour = profile.get("colour")
    return {"name": name, "display_name": display_name(name), "make": profile.get("make"), "model": profile.get("model"),
            "colour": colour[0] if isinstance(colour, list) and colour else colour, "plate": profile.get("plate")}


@app.get("/cars/profiles")
def get_car_profiles(request: Request) -> dict[str, Any]:
    """The household cars' profiles (see "car profiles"), and the makes and colours a profile may name. A signed-in user."""
    require_frigate_session(request)
    return {
        "profiles": [profile_json(name, profile) for name, profile in sorted(HOUSEHOLD_CARS.items())],
        "makes": [m for m in MAKES if m not in ("other", "unknown")],
        "colours": [c for c in COLOURS if c != "unknown"],
    }


@app.put("/cars/profiles/{name}")
def put_car_profile(name: str, body: dict[str, Any], request: Request) -> dict[str, Any]:
    """
    Sets a car's make, model, colour and plate. `name` is the classifier's category for it. Each
    field is optional (blank or missing leaves it unchecked), but what is given must be one of
    the vision model's makes and colours, since those are all it can answer.
    """
    if not DATASET_NAME.fullmatch(name) or name.lower() in NOT_A_NAME:
        raise HTTPException(status_code=400, detail="Bad car name")
    make = str(body.get("make") or "").strip().lower()
    colour = str(body.get("colour") or "").strip().lower()
    model = str(body.get("model") or "").strip()
    plate = normal_plate(body.get("plate"))
    if make and make not in MAKES:
        raise HTTPException(status_code=400, detail=f"Unknown make {make}")
    if colour and (colour not in COLOURS or colour == "unknown"):
        raise HTTPException(status_code=400, detail=f"Unknown colour {colour}")
    if len(model) > PROFILE_MODEL_MAX:
        raise HTTPException(status_code=400, detail="Model name too long")
    if body.get("plate") and len(plate) < PLATE_MIN_LENGTH:
        raise HTTPException(status_code=400, detail="Plate too short")
    user = require_frigate_session(request)
    profile = {"make": make, "model": model, "colour": colour, "plate": plate}
    save_car_profile(name, profile, user)
    log.info("car profile by %s: %s <- %s", user, name, {k: v for k, v in profile.items() if k != "plate"} | {"plate": bool(plate)})
    return profile_json(name, HOUSEHOLD_CARS.get(name) or {})


@app.delete("/cars/profiles/{name}")
def delete_car_profile(name: str, request: Request) -> dict[str, Any]:
    user = require_frigate_session(request)
    save_car_profile(name, None, user)
    log.info("car profile by %s: %s removed", user, name)
    return {"ok": True}


CAR_CHECKS_MAX = 200


def car_check_json(event_id: str) -> dict[str, Any] | None:
    """What the car check made of one event, for the app's labelling queue; None before it has looked."""
    row = check_of(event_id, "vlm")
    if row is None:
        return None
    verdict, detail = row
    try:
        d = json.loads(detail or "{}")
    except ValueError:
        d = {}
    saw = d.get("saw") or {}
    name, how, _ = verified_as(event_id)
    filed = check_of(event_id, "verified")
    return {
        "verdict": verdict,
        "classifier": d.get("was"),
        "name": name or (d.get("now") if verdict in ("relabel", "name", "clear") else d.get("was")),
        "verified": how,
        "saw": {k: saw.get(k) for k in ("colour", "make", "model", "body")},
        "plate_read": bool(d.get("plate")),
        "filed": filed[0] if filed else None,
    }


@app.get("/cars/checks")
def car_checks(events: str, request: Request) -> dict[str, Any]:
    """The car check's verdicts on the given events (comma-separated ids), keyed by id; events it hasn't looked at are left out."""
    require_frigate_session(request)
    ids = [e for e in events.split(",") if EVENT_ID.fullmatch(e)][:CAR_CHECKS_MAX]
    return {"checks": {e: c for e in ids if (c := car_check_json(e)) is not None}}


@app.post("/test")
def test_push(request: Request) -> dict[str, Any]:
    """Sends a sample alert to every registered phone, so the whole path can be checked from the app."""
    user = require_frigate_session(request)
    result = broadcast("Front Yard", "Test: Sarah's Tesla in the driveway", {"review_id": f"test-{int(time.time())}", "camera": "hikvision_1", "test": "1"}, test=True)
    log.info("test push by %s: %s", user, result)
    return {"ok": True, **result}


# ---------------------------------------------------------------- Google Home

# The cameras on a Nest Hub or a Chromecast with Google TV ("Hey Google, show the Front Door"),
# through a cloud-to-cloud smart-home integration of our own: a project in the Google Home
# Developer Console, linked in the Google Home app and never certified. Google's cloud reaches
# the relay over Tailscale Funnel, which publishes /google/* and nothing else. Linking the
# account signs in with a Frigate account, so there's no new password. When a display asks for a
# camera it posts a WebRTC offer to /google/signal, which hands it to Frigate's go2rtc and returns
# go2rtc's answer; the video then goes straight from go2rtc to the display over the LAN, since
# go2rtc advertises its LAN address. Off unless GOOGLE_CLIENT_ID is set (google-home.env on the box).
GOOGLE_CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID", "")
GOOGLE_CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET", "")
# The Developer Console project: names the one address Google may send an account link back to.
GOOGLE_PROJECT_ID = os.environ.get("GOOGLE_PROJECT_ID", "")
# Where Funnel publishes the relay, for the signaling address handed to a display.
GOOGLE_PUBLIC_URL = os.environ.get("GOOGLE_PUBLIC_URL", "").rstrip("/")
GO2RTC = os.environ.get("GO2RTC_URL", "http://127.0.0.1:1984").rstrip("/")
# Per camera, the go2rtc stream a display gets. Only the cameras listed are offered to Google: it
# wants 480p to 1080p, and here the main streams are 4K and the Hikvision subs 360p, so a camera
# needs a stream picked (or made) for it. Set in docker-compose.yml.
GOOGLE_STREAMS: dict[str, str] = json.loads(os.environ.get("GOOGLE_STREAMS") or "{}")
GOOGLE_AGENT_USER = "homesafe"  # one household, one Google link
GOOGLE_CODE_SECONDS = 600
GOOGLE_ACCESS_SECONDS = 3600
# How long a display has, after asking for a camera, to post its offer.
GOOGLE_STREAM_SECONDS = 120
# The page on a display that plays the stream is served from here, and posts the offer from it.
GOOGLE_SIGNAL_ORIGIN = "https://www.gstatic.com"
# "1" logs each display's WebRTC offer and go2rtc's answer in full, for a stream that negotiates but never plays.
GOOGLE_LOG_SDP = os.environ.get("GOOGLE_LOG_SDP") == "1"
GOOGLE_ICE_SERVERS = json.dumps([{"urls": "stun:stun.l.google.com:19302"}])
GET_CAMERA_STREAM = "action.devices.commands.GetCameraStream"
# The link page is on the internet: after this many wrong passwords in the window, it refuses
# every sign-in until the window passes. Frigate itself doesn't limit them.
GOOGLE_LOGIN_LIMIT = 5
GOOGLE_LOGIN_WINDOW_SECONDS = 900
_google_login_attempts: list[float] = []


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def google_issue(kind: str, subject: str, ttl: float | None, now: float | None = None) -> str:
    """A new token of `kind` for `subject`, good for `ttl` seconds (None: until unlinked). Only its hash is kept."""
    token = secrets.token_urlsafe(32)
    expires = None if ttl is None else (now or time.time()) + ttl
    with_db(lambda c: (c.execute("INSERT INTO google_tokens VALUES (?,?,?,?)", (token_hash(token), kind, subject, expires)), c.commit()))
    return token


def google_check(token: str, kind: str, consume: bool = False, now: float | None = None) -> str | None:
    """The subject a live token of `kind` was issued for, or None. `consume` spends it (a code is good once)."""
    now = now or time.time()
    digest = token_hash(token)

    def look(c: sqlite3.Connection):
        row = c.execute("SELECT subject, expires FROM google_tokens WHERE hash=? AND kind=?", (digest, kind)).fetchone()
        if row is not None and consume:
            c.execute("DELETE FROM google_tokens WHERE hash=?", (digest,))
        c.execute("DELETE FROM google_tokens WHERE expires IS NOT NULL AND expires < ?", (now,))
        c.commit()
        return row

    row = with_db(look) if token else None
    if row is None or (row[1] is not None and row[1] < now):
        return None
    return row[0]


def google_unlink() -> None:
    with_db(lambda c: (c.execute("DELETE FROM google_tokens"), c.commit()))


def google_redirect_ok(uri: str, project: str) -> bool:
    """Only Google's account-linking redirect for our project may receive a code."""
    hosts = ("oauth-redirect.googleusercontent.com", "oauth-redirect-sandbox.googleusercontent.com")
    return bool(project) and uri in {f"https://{host}/r/{project}" for host in hosts}


def reserve_login(attempts: list[float], now: float) -> bool:
    """
    Counts a sign-in against the limit *before* its password is checked, so guesses sent at once
    can't all slip under it; False once the limit is reached. A correct password hands its
    reservation back (`attempts.remove(now)`), so only wrong ones use up the window.
    """
    attempts[:] = [t for t in attempts if now - t < GOOGLE_LOGIN_WINDOW_SECONDS]
    if len(attempts) >= GOOGLE_LOGIN_LIMIT:
        return False
    attempts.append(now)
    return True


def google_offered(streams: dict[str, str], known: dict[str, list[str]]) -> dict[str, str]:
    """The cameras Google may show, with their streams: those given a stream that Frigate still has."""
    return {camera: stream for camera, stream in streams.items() if camera in known}


def google_cameras() -> dict[str, str]:
    return google_offered(GOOGLE_STREAMS, live_streams())


def google_sync(request_id: str, cameras: dict[str, str]) -> dict[str, Any]:
    return {"requestId": request_id, "payload": {"agentUserId": GOOGLE_AGENT_USER, "devices": [
        {
            "id": camera,
            "type": "action.devices.types.CAMERA",
            "traits": ["action.devices.traits.CameraStream"],
            "name": {"name": camera_name(camera)},
            "willReportState": False,
            "attributes": {"cameraStreamSupportedProtocols": ["webrtc"], "cameraStreamNeedAuthToken": True},
            "deviceInfo": {"manufacturer": "HomeSafe", "model": "Frigate camera"},
        }
        for camera in cameras
    ]}}


def google_query(request_id: str, payload: dict[str, Any], cameras: dict[str, str]) -> dict[str, Any]:
    ids = [d.get("id") for d in payload.get("devices") or []]
    return {"requestId": request_id, "payload": {"devices": {
        i: {"online": True, "status": "SUCCESS"} if i in cameras else {"online": False, "status": "ERROR", "errorCode": "deviceNotFound"}
        for i in ids
    }}}


def google_execute(request_id: str, payload: dict[str, Any], cameras: dict[str, str], issue: Callable[[str], str]) -> dict[str, Any]:
    """
    Answers "show the Front Door": for each camera asked for, a signaling address and a short-lived
    token to post the offer with (`issue(camera)` makes it). The token rides in the address too,
    as in Google's sample, in case a display leaves the header off.
    """
    results = []
    for command in payload.get("commands") or []:
        for device in command.get("devices") or []:
            camera = device.get("id")
            for execution in command.get("execution") or []:
                protocols = (execution.get("params") or {}).get("SupportedStreamProtocols") or ["webrtc"]
                if execution.get("command") != GET_CAMERA_STREAM:
                    results.append({"ids": [camera], "status": "ERROR", "errorCode": "functionNotSupported"})
                elif camera not in cameras:
                    results.append({"ids": [camera], "status": "ERROR", "errorCode": "deviceNotFound"})
                elif "webrtc" not in protocols:
                    results.append({"ids": [camera], "status": "ERROR", "errorCode": "notSupported"})
                else:
                    token = issue(camera)
                    results.append({"ids": [camera], "status": "SUCCESS", "states": {
                        "cameraStreamProtocol": "webrtc",
                        "cameraStreamSignalingUrl": f"{GOOGLE_PUBLIC_URL}/google/signal/{camera}?{urlencode({'token': token})}",
                        "cameraStreamAuthToken": token,
                        "cameraStreamIceServers": GOOGLE_ICE_SERVERS,
                    }})
    return {"requestId": request_id, "payload": {"commands": results}}


def go2rtc_answer(stream: str, offer_sdp: str) -> str:
    """go2rtc's WebRTC answer to a display's offer for `stream`."""
    r = requests.post(f"{GO2RTC}/api/webrtc", params={"src": stream}, json={"type": "offer", "sdp": offer_sdp}, timeout=15)
    r.raise_for_status()
    return r.json()["sdp"]


def display_answer(sdp: str) -> str:
    """
    go2rtc's answer as a Google display takes it. The display offers two-way audio, and go2rtc
    answers `sendrecv` (the camera's audio out, the display's microphone in), or `recvonly` for a
    stream with no audio. Audio is made one-way instead: `sendonly`, or `inactive` with nothing to
    send, so the display is never asked for its microphone. Scrypted's Google Home plugin, which
    plays on Nest Hubs, answers the same way. Video is left as it is.
    """
    newline = "\r\n" if "\r\n" in sdp else "\n"
    lines, section = [], None
    for line in sdp.split(newline):
        if line.startswith("m="):
            section = line[2:].split(" ", 1)[0]
        if section == "audio":
            line = {"a=sendrecv": "a=sendonly", "a=recvonly": "a=inactive"}.get(line, line)
        lines.append(line)
    return newline.join(lines)


def bearer(request: Request) -> str:
    auth = request.headers.get("authorization", "")
    return auth[7:].strip() if auth.lower().startswith("bearer ") else ""


def form_fields(raw: bytes) -> dict[str, str]:
    return {k: v[0] for k, v in parse_qs(raw.decode(errors="replace")).items()}


def json_response(body: dict[str, Any], status: int = 200, headers: dict[str, str] | None = None) -> Response:
    return Response(content=json.dumps(body), status_code=status, media_type="application/json", headers=headers)


def basic_credentials(header: str) -> tuple[str, str]:
    """Client id and secret from an `Authorization: Basic` header; both empty when it's malformed."""
    import base64

    try:
        decoded = base64.b64decode(header[6:].strip(), validate=True).decode()
    except ValueError:  # bad base64 (binascii.Error) or bytes that aren't UTF-8
        return "", ""
    client_id, _, secret = decoded.partition(":")
    return client_id, secret


def require_google() -> None:
    if not (GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET and GOOGLE_PROJECT_ID and GOOGLE_PUBLIC_URL):
        raise HTTPException(status_code=404, detail="Google Home is not set up")


LINK_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Link HomeSafe</title><style>body{{font-family:system-ui,sans-serif;max-width:22rem;margin:3rem auto;padding:0 1rem}}
input,button{{display:block;width:100%;box-sizing:border-box;margin:.5rem 0;padding:.7rem;font-size:1rem}}.e{{color:#b3261e}}</style></head>
<body><h2>Link HomeSafe to Google Home</h2><p>Sign in with your Frigate account.</p>{error}
<form method="post"><input type="hidden" name="client_id" value="{client_id}"><input type="hidden" name="redirect_uri" value="{redirect_uri}">
<input type="hidden" name="state" value="{state}"><input name="user" placeholder="Username" autocomplete="username" required>
<input name="password" type="password" placeholder="Password" autocomplete="current-password" required><button>Link</button></form></body></html>"""


def link_page(fields: dict[str, str], error: str = "") -> Response:
    values = {k: html.escape(fields.get(k, "")) for k in ("client_id", "redirect_uri", "state")}
    return Response(content=LINK_PAGE.format(error=f'<p class="e">{html.escape(error)}</p>' if error else "", **values), media_type="text/html")


def check_link_request(fields: dict[str, str]) -> None:
    if fields.get("client_id") != GOOGLE_CLIENT_ID or not google_redirect_ok(fields.get("redirect_uri", ""), GOOGLE_PROJECT_ID):
        raise HTTPException(status_code=400, detail="Unknown client or redirect")


@app.get("/google/oauth/authorize")
def google_authorize_page(request: Request) -> Response:
    """Where the Google Home app sends someone linking HomeSafe: a sign-in form."""
    require_google()
    fields = dict(request.query_params)
    check_link_request(fields)
    return link_page(fields)


@app.post("/google/oauth/authorize")
async def google_authorize(request: Request) -> Response:
    """Checks the Frigate account, then sends the browser back to Google with a one-time code."""
    from fastapi.concurrency import run_in_threadpool

    require_google()
    fields = form_fields(await request.body())
    check_link_request(fields)
    # Reserved here, with no await since the check, so sign-ins in flight together share the limit.
    attempt = time.time()
    if not reserve_login(_google_login_attempts, attempt):
        log.warning("google link: sign-in refused, too many failures")
        return link_page(fields, "Too many attempts. Try again in a few minutes.")
    user = fields.get("user", "")
    try:
        r = await run_in_threadpool(lambda: requests.post(
            f"{FRIGATE_AUTH}/api/login", json={"user": user, "password": fields.get("password", "")}, timeout=10
        ))
        ok = r.status_code == 200
    except Exception as e:
        _google_login_attempts.remove(attempt)  # not a guess
        log.warning("google link: frigate unreachable: %s", e)
        return link_page(fields, "Frigate is unreachable.")
    if not ok:
        log.warning("google link: wrong password for %r", user)
        return link_page(fields, "Wrong username or password.")
    _google_login_attempts.remove(attempt)
    code = await run_in_threadpool(google_issue, "code", user, GOOGLE_CODE_SECONDS)
    log.info("google link: %s signed in", user)
    return Response(status_code=302, headers={"Location": f"{fields['redirect_uri']}?{urlencode({'code': code, 'state': fields.get('state', '')})}"})


@app.post("/google/oauth/token")
async def google_token(request: Request) -> Response:
    """Google trades the code for tokens here, and later its refresh token for fresh access tokens."""
    from fastapi.concurrency import run_in_threadpool

    require_google()
    fields = form_fields(await request.body())
    client_id, client_secret = fields.get("client_id", ""), fields.get("client_secret", "")
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("basic "):
        client_id, client_secret = basic_credentials(auth)
    # As bytes: compare_digest refuses a str with anything but ASCII in it.
    if client_id != GOOGLE_CLIENT_ID or not secrets.compare_digest(client_secret.encode(), GOOGLE_CLIENT_SECRET.encode()):
        return json_response({"error": "invalid_client"}, 401)
    grant = fields.get("grant_type")
    if grant == "authorization_code":
        user = await run_in_threadpool(google_check, fields.get("code", ""), "code", True)
        if user is None:
            return json_response({"error": "invalid_grant"}, 400)
        access = await run_in_threadpool(google_issue, "access", user, GOOGLE_ACCESS_SECONDS)
        refresh = await run_in_threadpool(google_issue, "refresh", user, None)
        log.info("google link: tokens issued for %s", user)
        return json_response({"token_type": "Bearer", "access_token": access, "refresh_token": refresh, "expires_in": GOOGLE_ACCESS_SECONDS})
    if grant == "refresh_token":
        user = await run_in_threadpool(google_check, fields.get("refresh_token", ""), "refresh")
        if user is None:
            return json_response({"error": "invalid_grant"}, 400)
        access = await run_in_threadpool(google_issue, "access", user, GOOGLE_ACCESS_SECONDS)
        return json_response({"token_type": "Bearer", "access_token": access, "expires_in": GOOGLE_ACCESS_SECONDS})
    return json_response({"error": "unsupported_grant_type"}, 400)


@app.post("/google/fulfillment")
async def google_fulfillment(request: Request) -> Response:
    """Google's smart-home intents: SYNC lists the cameras, QUERY says they're up, EXECUTE starts a stream."""
    from fastapi.concurrency import run_in_threadpool

    require_google()
    user = await run_in_threadpool(google_check, bearer(request), "access")
    if user is None:
        return json_response({"error": "invalid_token"}, 401)
    body = json.loads(await request.body() or b"{}")
    request_id = body.get("requestId", "")
    for item in body.get("inputs") or []:
        intent, payload = item.get("intent"), item.get("payload") or {}
        if intent == "action.devices.SYNC":
            cameras = await run_in_threadpool(google_cameras)
            log.info("google sync by %s: %s", user, cameras)
            return json_response(google_sync(request_id, cameras))
        if intent == "action.devices.QUERY":
            cameras = await run_in_threadpool(google_cameras)
            return json_response(google_query(request_id, payload, cameras))
        if intent == "action.devices.EXECUTE":
            cameras = await run_in_threadpool(google_cameras)
            answer = await run_in_threadpool(
                google_execute, request_id, payload, cameras, lambda camera: google_issue("stream", camera, GOOGLE_STREAM_SECONDS)
            )
            log.info("google execute by %s: %s", user, [c.get("ids") for c in answer["payload"]["commands"]])
            return json_response(answer)
        if intent == "action.devices.DISCONNECT":
            await run_in_threadpool(google_unlink)
            log.info("google link: unlinked by %s", user)
            return json_response({})
    return json_response({"requestId": request_id, "payload": {"errorCode": "notSupported"}})


def signal_cors(request: Request) -> dict[str, str]:
    if request.headers.get("origin") != GOOGLE_SIGNAL_ORIGIN:
        return {}
    return {
        "Access-Control-Allow-Origin": GOOGLE_SIGNAL_ORIGIN,
        "Access-Control-Allow-Credentials": "true",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Authorization, Content-Type",
        "Vary": "Origin",
    }


@app.options("/google/signal/{camera}")
def google_signal_preflight(camera: str, request: Request) -> Response:
    return Response(status_code=204, headers=signal_cors(request))


@app.post("/google/signal/{camera}")
async def google_signal(camera: str, request: Request) -> Response:
    """
    The display's side of the WebRTC handshake: `{"action": "offer", "sdp": ...}` gets go2rtc's
    `{"action": "answer", "sdp": ...}`; `{"action": "end"}` needs nothing (go2rtc drops the peer itself).
    """
    from fastapi.concurrency import run_in_threadpool

    require_google()
    cors = signal_cors(request)
    body = json.loads(await request.body() or b"{}")
    action = body.get("action")
    if action == "end":
        return json_response({}, headers=cors)
    token = bearer(request) or request.query_params.get("token", "")
    if await run_in_threadpool(google_check, token, "stream") != camera:
        return json_response({"error": "invalid_token"}, 401, cors)
    if action != "offer" or not body.get("sdp"):
        return json_response({"error": f"unsupported action {action!r}"}, 400, cors)
    stream = (await run_in_threadpool(google_cameras)).get(camera)
    if stream is None:
        return json_response({"error": "unknown camera"}, 404, cors)
    try:
        sdp = display_answer(await run_in_threadpool(go2rtc_answer, stream, body["sdp"]))
    except Exception as e:
        log.warning("google signal: go2rtc refused %s: %s", stream, e)
        return json_response({"error": "stream unavailable"}, 502, cors)
    log.info("google signal: %s streaming %s", camera, stream)
    if GOOGLE_LOG_SDP:
        log.info("google signal: %s offer:\n%s\nanswer:\n%s", camera, body["sdp"], sdp)
    return json_response({"action": "answer", "sdp": sdp}, headers=cors)


# ---------------------------------------------------------------- finance
#
# The app's finance section shows the household's budget sheet, a Google Sheet private to the
# household's own Google accounts. The phone never holds a Google credential and the sheet is
# never made public: the relay reads it with a Google service account (by default the same key
# that sends pushes) which the sheet is shared with as a viewer, and hands it to a signed-in app.
# The sheet is returned as it is — every grid tab's raw values and merged ranges — and the app
# makes sense of it (PersonalFinanceParser.kt), so reorganising the sheet never needs a relay
# change.
#
# It's the household's money, not the cameras: a Frigate *viewer* account (a sitter, a guest) gets
# no further than a 403. Frigate admins may read it, and so may any username FINANCE_USERS lists
# (a household member whose Frigate login is a viewer).
#
# The sheet's own charts come along too, as Google describes them (what kind, stacked or not,
# which ranges they plot) plus each plotted range's number format, which says whether an axis is
# dates or money. The app draws them from the values it already has (SheetChartReader.kt), so a
# chart added to the sheet shows up in the app with no change to either.
#
# Setup, once: switch on the Google Sheets API for the key's Cloud project, share the sheet with
# the key's `client_email` (read-only), and set FINANCE_SHEET_ID (in finance.env on the box; the
# repo is public). Until then the route says which step is missing (`error`) and who to share
# with (`service_account`), and the app shows that.

FINANCE_SHEET_ID = os.environ.get("FINANCE_SHEET_ID", "").strip()
FINANCE_SHEET_KEY = os.environ.get("FINANCE_SHEET_KEY", KEY_FILE)
FINANCE_USERS = {u.strip() for u in os.environ.get("FINANCE_USERS", "").split(",") if u.strip()}
SHEETS_API = "https://sheets.googleapis.com/v4/spreadsheets"
SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
# The sheet changes by hand a few times a month; a minute's cache keeps tab-hopping in the app
# from hitting Google every time, and a pull to refresh (?refresh=true) skips it.
FINANCE_CACHE_SECONDS = 60
# ...but not more than once every few seconds however hard someone pulls, and a failure is
# answered from memory for as long, so an outage or a missing setup step isn't asked of Google
# by every request.
FINANCE_MIN_REFRESH_SECONDS = 5
# How long a request waits behind another one already asking Google before it gives up on it.
FINANCE_LOCK_SECONDS = 10

_finance_lock = threading.Lock()
_finance_cache: dict[str, Any] = {"at": 0.0, "body": None, "failed_at": 0.0, "failure": None}
_finance_google: dict[str, Any] = {"session": None, "account": None}


def finance_may_read(profile: dict[str, Any]) -> bool:
    """Whether Frigate's `/api/profile` answer is someone allowed the household's finances: an admin, or a listed user."""
    return profile.get("role") == "admin" or str(profile.get("username", "")) in FINANCE_USERS


def require_finance_user(request: Request) -> str:
    """A signed-in Frigate user who may read the finances (see "finance"); their username."""
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
        profile = r.json()
    except ValueError:
        profile = {}
    if not isinstance(profile, dict) or not finance_may_read(profile):
        raise HTTPException(403, {"error": "not_allowed", "message": "This account can't see the household's finances"})
    return str(profile.get("username", "?"))


def sheet_range(title: str) -> str:
    """A whole tab as an A1 range: the title quoted, any quote in it doubled."""
    return "'" + title.replace("'", "''") + "'"


def sheet_problem(status: int, body: Any, account: str | None) -> tuple[int, dict[str, Any]]:
    """
    Google's refusal as the app's setup problem: the Sheets API switched off for the key's
    project, the sheet not shared with the key, or no such sheet. The status is what the relay
    answers (503 for a setup step, 502 for anything else Google said).
    """
    error = body.get("error") if isinstance(body, dict) else None
    if not isinstance(error, dict):
        error = {"message": str(error)} if error else {}
    message = str(error.get("message", "")) or f"Google answered {status}"
    details = [d for d in error.get("details", []) if isinstance(d, dict)]
    reasons = {str(d.get("reason", "")) for d in details}
    reasons |= {str(e.get("reason", "")) for e in error.get("errors", []) if isinstance(e, dict)}
    reasons.discard("")
    detail: dict[str, Any] = {"message": message, "service_account": account}
    if status == 403 and (reasons & {"SERVICE_DISABLED", "accessNotConfigured"} or "has not been used" in message or "is disabled" in message):
        detail["error"] = "api_disabled"
        for d in details:
            url = d.get("metadata", {}).get("activationUrl") if isinstance(d.get("metadata"), dict) else None
            if url:
                detail["activation_url"] = url
        if "activation_url" not in detail:
            found = re.search(r"https://console\.developers\.google\.com/\S+", message)
            if found:
                detail["activation_url"] = found.group(0).rstrip(".")
        return 503, detail
    # A plain permission refusal is the sheet not shared with the key. A 403 with a reason of its
    # own (a suspended project, a scope or an org policy) isn't, and sharing wouldn't help.
    if status == 403 and not (reasons - {"PERMISSION_DENIED", "forbidden"}):
        detail["error"] = "not_shared"
        return 503, detail
    if status == 404:
        detail["error"] = "not_found"
        return 503, detail
    detail["error"] = "google_error"
    return 502, detail


def grid_titles(meta: dict[str, Any]) -> list[str]:
    """The tabs that hold cells. A chart on a tab of its own has none, and asking for its values fails the whole read."""
    return [
        s.get("properties", {}).get("title", "")
        for s in meta.get("sheets", [])
        if s.get("properties", {}).get("sheetType", "GRID") == "GRID"
    ]


def column_letters(index: int) -> str:
    """A 0-based column as A1 letters: 0 → A, 25 → Z, 26 → AA."""
    letters = ""
    index += 1
    while index > 0:
        index, rem = divmod(index - 1, 26)
        letters = chr(ord("A") + rem) + letters
    return letters


def chart_range(grid: dict[str, Any], titles: dict[int, str]) -> dict[str, Any] | None:
    """
    A chart's GridRange with its tab by title rather than id, 0-based and end-exclusive. A
    missing end (a whole-column range like A2:A) stays None: the data runs to the tab's end.
    """
    title = titles.get(int(grid.get("sheetId", 0)))
    if title is None:
        return None
    return {
        "sheet": title,
        "start_row": grid.get("startRowIndex", 0),
        "end_row": grid.get("endRowIndex"),
        "start_column": grid.get("startColumnIndex", 0),
        "end_column": grid.get("endColumnIndex"),
    }


def chart_ranges(data: Any, titles: dict[int, str]) -> list[dict[str, Any]]:
    """A ChartData's ranges, in order (Google lets one series run across several)."""
    if not isinstance(data, dict):
        return []
    sources = data.get("sourceRange", {}).get("sources", [])
    return [r for r in (chart_range(s, titles) for s in sources if isinstance(s, dict)) if r is not None]


# The spec keys of the chart kinds that aren't a BasicChartSpec, as the app names them.
_OTHER_CHART_KINDS = {
    "bubbleChart": "BUBBLE",
    "candlestickChart": "CANDLESTICK",
    "orgChart": "ORG",
    "histogramChart": "HISTOGRAM",
    "waterfallChart": "WATERFALL",
    "treemapChart": "TREEMAP",
}


def chart_payload(chart: dict[str, Any], tab: str, titles: dict[int, str], tab_gids: dict[str, int]) -> dict[str, Any]:
    """
    One embedded chart as the app draws it: its kind, the ranges for its domain (the x axis, or a
    pie's labels) and each series, and where it sits. The values stay in the grid the payload
    already carries; the app reads them from there.
    """
    spec = chart.get("spec", {}) if isinstance(chart.get("spec"), dict) else {}
    anchor = chart.get("position", {}).get("overlayPosition", {}).get("anchorCell", {})
    body: dict[str, Any] = {
        "id": chart.get("chartId"),
        "sheet": tab,
        "gid": tab_gids.get(tab),
        "title": spec.get("title", ""),
        "subtitle": spec.get("subtitle", ""),
        "anchor": {"row": anchor.get("rowIndex", 0), "column": anchor.get("columnIndex", 0)},
        "stacked": "NOT_STACKED",
        "header_count": None,
        "domain": [],
        "series": [],
    }
    if isinstance(spec.get("basicChart"), dict):
        basic = spec["basicChart"]
        body["kind"] = basic.get("chartType", "LINE")
        body["stacked"] = basic.get("stackedType", "NOT_STACKED")
        body["header_count"] = basic.get("headerCount")
        domains = [d for d in basic.get("domains", []) if isinstance(d, dict)]
        if domains:
            body["domain"] = chart_ranges(domains[0].get("domain"), titles)
            body["reversed"] = bool(domains[0].get("reversed", False))
        body["series"] = [
            {"ranges": chart_ranges(s.get("series"), titles), "type": s.get("type") or body["kind"], "axis": s.get("targetAxis", "LEFT_AXIS")}
            for s in basic.get("series", [])
            if isinstance(s, dict)
        ]
    elif isinstance(spec.get("pieChart"), dict):
        pie = spec["pieChart"]
        body["kind"] = "PIE"
        body["domain"] = chart_ranges(pie.get("domain"), titles)
        body["series"] = [{"ranges": chart_ranges(pie.get("series"), titles), "type": "PIE", "axis": "LEFT_AXIS"}]
        body["pie_hole"] = pie.get("pieHole", 0)
    elif isinstance(spec.get("scorecardChart"), dict):
        body["kind"] = "SCORECARD"
        body["series"] = [{"ranges": chart_ranges(spec["scorecardChart"].get("keyValueData"), titles), "type": "SCORECARD", "axis": "LEFT_AXIS"}]
    else:
        body["kind"] = next((name for key, name in _OTHER_CHART_KINDS.items() if key in spec), "OTHER")
    body["series"] = [s for s in body["series"] if s["ranges"]]
    return body


def sheet_charts(meta: dict[str, Any]) -> list[dict[str, Any]]:
    """Every embedded chart in the workbook, tab by tab and top to bottom, left to right within one."""
    sheets = [s for s in meta.get("sheets", []) if isinstance(s, dict)]
    titles = {int(s.get("properties", {}).get("sheetId", 0)): s.get("properties", {}).get("title", "") for s in sheets}
    tab_gids = {title: gid for gid, title in titles.items()}
    charts = []
    for sheet in sheets:
        tab = sheet.get("properties", {}).get("title", "")
        placed = [chart_payload(c, tab, titles, tab_gids) for c in sheet.get("charts", []) if isinstance(c, dict)]
        charts += sorted(placed, key=lambda c: (c["anchor"]["row"], c["anchor"]["column"]))
    return charts


# Each plotted range's number format is read from one cell: its second, so a header row (which
# isn't counted until the app decides whether there is one) doesn't answer for the data.
_MAX_FORMAT_PROBES = 60


def format_probe(r: dict[str, Any]) -> tuple[str, int, int]:
    """The cell of range `r` whose number format stands for the range: its second cell down a column, or along a row."""
    rows = None if r["end_row"] is None else r["end_row"] - r["start_row"]
    columns = None if r["end_column"] is None else r["end_column"] - r["start_column"]
    if rows == 1 and columns != 1:
        return r["sheet"], r["start_row"], r["start_column"] + 1
    return r["sheet"], r["start_row"] + (0 if rows == 1 else 1), r["start_column"]


def format_probes(charts: list[dict[str, Any]]) -> list[tuple[str, int, int]]:
    """The distinct cells whose formats the charts need: each domain's and each series' first range."""
    cells: list[tuple[str, int, int]] = []
    for chart in charts:
        for ranges in [chart["domain"]] + [s["ranges"] for s in chart["series"]]:
            if ranges:
                cell = format_probe(ranges[0])
                if cell not in cells:
                    cells.append(cell)
    return cells[:_MAX_FORMAT_PROBES]


def cell_a1(cell: tuple[str, int, int]) -> str:
    sheet, row, column = cell
    return f"{sheet_range(sheet)}!{column_letters(column)}{row + 1}"


def cell_formats(grid: dict[str, Any]) -> dict[tuple[str, int, int], dict[str, str]]:
    """The number formats in a `spreadsheets.get` answer for the probe cells, by (tab, row, column)."""
    found: dict[tuple[str, int, int], dict[str, str]] = {}
    for sheet in grid.get("sheets", []):
        title = sheet.get("properties", {}).get("title", "")
        for data in sheet.get("data", []):
            row0, col0 = data.get("startRow", 0), data.get("startColumn", 0)
            for i, row in enumerate(data.get("rowData", [])):
                for j, value in enumerate(row.get("values", [])):
                    fmt = value.get("effectiveFormat", {}).get("numberFormat") if isinstance(value, dict) else None
                    if isinstance(fmt, dict) and fmt.get("type"):
                        found[(title, row0 + i, col0 + j)] = {"type": fmt["type"], "pattern": fmt.get("pattern", "")}
    return found


def with_formats(charts: list[dict[str, Any]], formats: dict[tuple[str, int, int], dict[str, str]]) -> list[dict[str, Any]]:
    """The charts with each domain's and series' number format (None when the cell has none, or wasn't read)."""
    for chart in charts:
        chart["domain_format"] = formats.get(format_probe(chart["domain"][0])) if chart["domain"] else None
        for series in chart["series"]:
            series["format"] = formats.get(format_probe(series["ranges"][0]))
    return charts


def sheet_payload(meta: dict[str, Any], values: dict[str, Any], sheet_id: str, fetched_at: float, formats: dict[tuple[str, int, int], dict[str, str]] | None = None) -> dict[str, Any]:
    """
    The workbook as the app reads it: each grid tab's rows of raw values and its merged ranges
    (0-based, end-exclusive), and the workbook's charts. `values` holds one range per grid tab,
    in order.
    """
    ranges = values.get("valueRanges", [])
    sheets = []
    grids = [s for s in meta.get("sheets", []) if s.get("properties", {}).get("sheetType", "GRID") == "GRID"]
    for i, sheet in enumerate(grids):
        rows = ranges[i].get("values", []) if i < len(ranges) else []
        merges = [
            {
                "start_row": m.get("startRowIndex", 0),
                "end_row": m.get("endRowIndex", 0),
                "start_column": m.get("startColumnIndex", 0),
                "end_column": m.get("endColumnIndex", 0),
            }
            for m in sheet.get("merges", [])
        ]
        sheets.append({"title": sheet.get("properties", {}).get("title", ""), "values": rows, "merges": merges})
    return {
        "title": meta.get("properties", {}).get("title", ""),
        "fetched_at": fetched_at,
        "url": f"https://docs.google.com/spreadsheets/d/{sheet_id}/edit",
        "sheets": sheets,
        "charts": with_formats(sheet_charts(meta), formats or {}),
    }


def finance_google() -> Any:
    """The read-only Sheets session, made on first use from FINANCE_SHEET_KEY."""
    if _finance_google["session"] is None:
        if not os.path.exists(FINANCE_SHEET_KEY):
            raise HTTPException(503, {"error": "no_key", "message": "The relay has no service-account key to read the sheet with"})
        try:
            creds = service_account.Credentials.from_service_account_file(FINANCE_SHEET_KEY, scopes=[SHEETS_SCOPE])
        except (ValueError, KeyError) as e:
            log.warning("finance: %s isn't a service-account key: %s", FINANCE_SHEET_KEY, e)
            raise HTTPException(503, {"error": "no_key", "message": "The relay's key isn't a service-account key"}) from e
        _finance_google["account"] = creds.service_account_email
        _finance_google["session"] = AuthorizedSession(creds)
    return _finance_google["session"]


def _google_json(response: Any) -> Any:
    try:
        return response.json()
    except ValueError:
        return {}


def chart_formats(session: Any, meta: dict[str, Any]) -> dict[tuple[str, int, int], dict[str, str]]:
    """
    The number formats of the cells the charts plot (see `format_probe`), in one more call.
    Without them the app still finds date axes from the values (date serials), but writes every
    value as a plain number rather than money or a percentage. So a failure here is logged and
    the sheet is served without them.
    """
    cells = format_probes(sheet_charts(meta))
    if not cells:
        return {}
    try:
        params = [("ranges", cell_a1(c)) for c in cells]
        params.append(("fields", "sheets(properties(title),data(startRow,startColumn,rowData(values(effectiveFormat(numberFormat)))))"))
        answer = session.get(f"{SHEETS_API}/{FINANCE_SHEET_ID}", params=params, timeout=15)
        if answer.status_code != 200:
            log.warning("finance: chart formats unavailable (%s)", answer.status_code)
            return {}
        return cell_formats(answer.json())
    except Exception as e:
        log.warning("finance: chart formats unavailable: %s", e)
        return {}


def read_finance_sheet() -> dict[str, Any]:
    """Asks Google for the whole workbook. Raises HTTPException with the app's problem on failure."""
    session = finance_google()
    account = _finance_google["account"]
    try:
        meta = session.get(
            f"{SHEETS_API}/{FINANCE_SHEET_ID}",
            params={"fields": "properties.title,sheets(properties(sheetId,title,sheetType),merges,charts(chartId,spec,position))"},
            timeout=15,
        )
        if meta.status_code != 200:
            raise HTTPException(*sheet_problem(meta.status_code, _google_json(meta), account))
        titles = grid_titles(meta.json())
        params = [("ranges", sheet_range(t)) for t in titles]
        params += [("valueRenderOption", "UNFORMATTED_VALUE"), ("dateTimeRenderOption", "SERIAL_NUMBER"), ("majorDimension", "ROWS")]
        values = session.get(f"{SHEETS_API}/{FINANCE_SHEET_ID}/values:batchGet", params=params, timeout=20)
        if values.status_code != 200:
            raise HTTPException(*sheet_problem(values.status_code, _google_json(values), account))
        return sheet_payload(meta.json(), values.json(), FINANCE_SHEET_ID, time.time(), chart_formats(session, meta.json()))
    except HTTPException:
        raise
    except Exception as e:
        # Unreachable, or the key refused when the session swapped it for a token (revoked, or
        # the clock is off): either way Google never answered. The detail stays in the log.
        log.warning("finance: couldn't read the sheet: %s", e)
        raise HTTPException(502, {"error": "google_error", "message": "Couldn't reach Google", "service_account": account}) from e


# The workbook is the household's books and rides on a cookie, which doesn't stop an HTTP cache
# keeping a copy the way an Authorization header would: every answer says not to.
FINANCE_CACHE_CONTROL = "private, no-store"


@app.get("/finance/sheet")
def get_finance_sheet(request: Request, response: Response, refresh: bool = False) -> dict[str, Any]:
    """The household budget workbook (see "finance"), every grid tab. A Frigate admin, or a user in FINANCE_USERS."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    if not FINANCE_SHEET_ID:
        raise HTTPException(503, {"error": "not_configured", "message": "FINANCE_SHEET_ID isn't set on the relay"})
    if not _finance_lock.acquire(timeout=FINANCE_LOCK_SECONDS):
        # Someone else's read is still waiting on Google: answer what there is rather than queue.
        if _finance_cache["body"] is not None:
            return _finance_cache["body"]
        raise HTTPException(503, {"error": "google_error", "message": "Google is slow to answer; try again shortly"})
    try:
        now = time.time()
        cached = _finance_cache["body"]
        age = now - _finance_cache["at"]
        if cached is not None and (age < FINANCE_MIN_REFRESH_SECONDS or (not refresh and age < FINANCE_CACHE_SECONDS)):
            return cached
        failure = _finance_cache["failure"]
        if failure is not None and now - _finance_cache["failed_at"] < FINANCE_MIN_REFRESH_SECONDS:
            raise HTTPException(*failure)
        try:
            body = read_finance_sheet()
        except HTTPException as e:
            log.warning("finance: sheet unavailable (%s): %s", e.status_code, (e.detail or {}).get("error") if isinstance(e.detail, dict) else e.detail)
            _finance_cache.update(failed_at=now, failure=(e.status_code, e.detail))
            raise
        _finance_cache.update(at=now, body=body, failed_at=0.0, failure=None)
        return body
    finally:
        _finance_lock.release()


# ---------------------------------------------------------------- bank sync
#
# The budget sheet's balances used to be typed in by hand. Plaid reads them instead: each bank,
# brokerage or lender is linked once, on Plaid's own page (the institution's own sign-in where it
# has one), and from then on the relay asks Plaid once a day what every account holds and writes
# that into two feed tabs of a Google Sheet, which the budget sheet's cells look up. The app goes
# on reading the budget sheet as it always has (see "finance").
#
# What is kept on the box is one access token per institution, in relay.db. The relay reads
# balances, holdings and loan terms with it, and what was bought on the credit cards (see
# "budget"); no product that can move money is ever asked for. The phone never sees a token, only
# what the relay read with it, and only when signed in as someone who may see the finances.
#
# Linking is Plaid's Hosted Link: the relay asks Plaid for a link token with a `hosted_link_url`,
# the app opens that in the browser, and the relay asks Plaid how the session ended when the app
# asks (`/link/token/get`). No webhook, so nothing more of the relay is published to the internet.
#
# Setup (docs/bank-sync.md): a Plaid account's client id and secret in plaid.env on the box, and
# FINANCE_FEED_SHEET_ID, a sheet shared with the service account as an Editor. Off without the
# first two. Without the third the balances are still read and shown in the app, only not written.

PLAID_CLIENT_ID = os.environ.get("PLAID_CLIENT_ID", "").strip()
PLAID_SECRET = os.environ.get("PLAID_SECRET", "").strip()
# "production" (a Trial plan's keys are production keys) or "sandbox".
PLAID_ENV = os.environ.get("PLAID_ENV", "").strip().lower() or "production"
PLAID_API = os.environ.get("PLAID_API", f"https://{PLAID_ENV}.plaid.com").rstrip("/")
PLAID_COUNTRIES = [c.strip().upper() for c in os.environ.get("PLAID_COUNTRIES", "US").split(",") if c.strip()]
# Only for a bank whose sign-in hands over to its own phone app; it must be registered in Plaid's dashboard.
PLAID_REDIRECT_URI = os.environ.get("PLAID_REDIRECT_URI", "").strip()
# What a link asks an institution for, by what the person said they were linking. The product
# named here is the one the institution must have (Link only lists those that do); investments and
# liabilities also come along wherever the institution has them, so one sign-in to a bank that
# holds the mortgage and a brokerage account too brings all three. Transactions are what a bank or
# a card is linked by, and what the budget reads a card's purchases with (see "budget").
PLAID_KINDS = {"bank": "transactions", "investments": "investments", "loans": "liabilities"}
PLAID_EXTRAS = ("investments", "liabilities")
# How long a link stays good (Plaid's own lifetime for a hosted link it doesn't deliver itself).
PLAID_LINK_SECONDS = 1800
PLAID_LINKS_KEY = "plaid_links"
PLAID_SYNCED_KEY = "plaid_synced"
PLAID_FEED_KEY = "plaid_feed"
PLAID_FEED_ROWS_KEY = "plaid_feed_rows"
# Plaid's ways of saying an institution has nothing of a kind: not a failed sync.
PLAID_NOTHING_THERE = {"NO_INVESTMENT_ACCOUNTS", "NO_LIABILITY_ACCOUNTS", "PRODUCTS_NOT_SUPPORTED"}
# ...of saying the person has to sign in at the institution again (Link's update mode)...
PLAID_RELINK = {"ITEM_LOGIN_REQUIRED", "PENDING_EXPIRATION", "PENDING_DISCONNECT"}
# ...and of saying it no longer knows the link at all.
PLAID_GONE = {"ITEM_NOT_FOUND", "INVALID_ACCESS_TOKEN"}

# The hour, on the household's clock, at which every institution is read each day. Plaid itself
# refreshes an institution about once a day, holdings after the markets close, so asking more
# often would mostly read the same numbers again.
BANK_SYNC_HOUR = min(23, max(0, int(os.environ.get("BANK_SYNC_HOUR", "6"))))
BANK_CHECK_SECONDS = 300
# "Sync now" in the app, however often it is tapped.
BANK_MIN_SYNC_SECONDS = 60
# How long an unlinking waits for a sync under way to finish before it says to try again.
BANK_UNLINK_WAIT_SECONDS = 25

# Where the balances are written: a sheet of its own that the budget sheet looks up with
# IMPORTRANGE (so the relay can still only read the budget sheet), or the budget sheet's own id to
# have the two tabs added to it. Either way the relay writes these two tabs and nothing else.
FINANCE_FEED_SHEET_ID = os.environ.get("FINANCE_FEED_SHEET_ID", "").strip()
SHEETS_WRITE_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
FEED_ACCOUNTS_TAB = "Bank feed"
FEED_HOLDINGS_TAB = "Holdings feed"
# The key comes first and the balance second, so a cell's formula is VLOOKUP(key, A:B, 2, FALSE).
FEED_ACCOUNT_COLUMNS = ["Key", "Balance", "Available", "Limit", "Institution", "Account", "Mask", "Type", "Subtype", "APR %", "Minimum payment", "Payment due", "Currency", "Updated"]
FEED_HOLDING_COLUMNS = ["Account key", "Ticker", "Name", "Quantity", "Price", "Value", "Cost basis", "Kind", "Institution", "Currency", "Price as of"]

# Held by whatever is changing what relay.db keeps of the institutions and writing the feed from
# it: a sync, or an unlinking. One at a time, so a sync can't put back what an unlinking removed.
_bank_lock = threading.Lock()
# Held around every change to the links under way (PLAID_LINKS_KEY), and for the whole of finishing
# one: Plaid's public token can be swapped for an access token only once.
_link_lock = threading.Lock()
# "Sync now" is let in by one request at a time, and when the last one was.
_sync_ask_lock = threading.Lock()
_sync_asked = {"at": 0.0}
_feed_google: dict[str, Any] = {"session": None, "account": None}


class PlaidError(Exception):
    """Plaid refused: its `error_code`, and its own words for it."""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(f"{code}: {message}" if message else code)
        self.code, self.message = code, message


class BankLink(BaseModel):
    # What is being linked: a key of PLAID_KINDS.
    kind: str = "bank"
    # An institution already linked, to sign in to again (Link's update mode); `kind` is then ignored.
    institution: str | None = None


def plaid_on() -> bool:
    return bool(PLAID_CLIENT_ID and PLAID_SECRET)


def plaid_post(path: str, body: dict[str, Any]) -> dict[str, Any]:
    """One call to Plaid. The request carries the secret and often an access token, so it is never logged."""
    r = requests.post(f"{PLAID_API}{path}", json={"client_id": PLAID_CLIENT_ID, "secret": PLAID_SECRET, **body}, timeout=30)
    try:
        answer = r.json()
    except ValueError:
        answer = {}
    if not isinstance(answer, dict):
        answer = {}
    if r.status_code != 200:
        raise PlaidError(str(answer.get("error_code") or f"HTTP_{r.status_code}"), str(answer.get("display_message") or answer.get("error_message") or ""))
    return answer


def plaid_link_request(user: str, kind: str, access: str | None = None) -> dict[str, Any]:
    """
    The `/link/token/create` body for a hosted link: a new institution of `kind`, or with `access`
    a fresh sign-in to one already linked. Plaid is told who is linking only as a hash.
    """
    body: dict[str, Any] = {
        "client_name": "HomeSafe",
        "language": "en",
        "country_codes": PLAID_COUNTRIES,
        "user": {"client_user_id": hashlib.sha256(f"homesafe:{user}".encode()).hexdigest()[:32]},
        "hosted_link": {},
    }
    if PLAID_REDIRECT_URI:
        body["redirect_uri"] = PLAID_REDIRECT_URI
    if access:
        body["access_token"] = access
    else:
        product = PLAID_KINDS[kind]
        body["products"] = [product]
        body["required_if_supported_products"] = [p for p in PLAID_EXTRAS if p != product]
    return body


def plaid_link_start(user: str, kind: str = "bank", item_id: str | None = None, now: float | None = None) -> dict[str, Any]:
    """A page to open in the browser that links an institution (or signs in again to `item_id`), and the token to ask about it by."""
    now = time.time() if now is None else now
    access = None
    if item_id:
        row = with_db(lambda c: c.execute("SELECT access FROM plaid_items WHERE item_id=?", (item_id,)).fetchone())
        if not row:
            raise HTTPException(404, {"error": "not_found", "message": "That institution isn't linked"})
        access = row[0]
    elif kind not in PLAID_KINDS:
        raise HTTPException(400, {"error": "bad_kind", "message": f"kind is one of {', '.join(PLAID_KINDS)}"})
    answer = plaid_post("/link/token/create", plaid_link_request(user, kind, access))
    token, url = answer.get("link_token"), answer.get("hosted_link_url")
    if not token or not url:
        raise PlaidError("NO_HOSTED_LINK", "Plaid made a link without a page to open. Hosted Link may not be switched on for this Plaid account.")
    with _link_lock:
        links = {t: link for t, link in (state_get(PLAID_LINKS_KEY) or {}).items() if link.get("expires", 0) > now}
        links[token] = {"expires": now + PLAID_LINK_SECONDS, "by": user, "item": item_id}
        state_set(PLAID_LINKS_KEY, links)
    return {"token": token, "url": url, "expires_at": now + PLAID_LINK_SECONDS}


def plaid_save_item(item_id: str, access: str, institution: dict[str, Any], user: str, now: float) -> None:
    with_db(lambda c: (c.execute(
        "INSERT OR REPLACE INTO plaid_items (item_id, access, institution_id, institution, products, linked_by, linked_at) VALUES (?,?,?,?,?,?,?)",
        (item_id, access, institution.get("institution_id"), institution.get("name") or "", "[]", user, now),
    ), c.commit()))


def plaid_link_finish(token: str, now: float | None = None) -> dict[str, Any]:
    """
    How a link from `plaid_link_start` is getting on: "pending" while the person is still on
    Plaid's page, "linked" once they finished (each new institution's token is kept and a sync
    started), "exited" when they left without finishing, "expired" for a link too old or unknown.
    """
    with _link_lock:
        return _plaid_link_finish(token, time.time() if now is None else now)


def _plaid_link_finish(token: str, now: float) -> dict[str, Any]:
    links = state_get(PLAID_LINKS_KEY) or {}
    link = links.get(token)
    if link is None:
        return {"status": "expired"}
    if link.get("done") is not None:
        return {"status": "linked", "institutions": link["done"]}
    answer = plaid_post("/link/token/get", {"link_token": token})
    sessions = [s for s in answer.get("link_sessions") or [] if isinstance(s, dict)]
    added = [r for s in sessions for r in ((s.get("results") or {}).get("item_add_results") or []) if isinstance(r, dict) and r.get("public_token")]
    relinked = link.get("item")
    if relinked and (added or any(s.get("finished_at") and not s.get("exit") for s in sessions)):
        # Signed in again: the token kept is still the one; only its error goes.
        with_db(lambda c: (c.execute("UPDATE plaid_items SET error=NULL, error_message=NULL WHERE item_id=?", (relinked,)), c.commit()))
        row = with_db(lambda c: c.execute("SELECT institution FROM plaid_items WHERE item_id=?", (relinked,)).fetchone())
        names = [row[0]] if row else []
    elif added and not relinked:
        names = []
        for result in added:
            exchanged = plaid_post("/item/public_token/exchange", {"public_token": result["public_token"]})
            institution = result.get("institution") if isinstance(result.get("institution"), dict) else {}
            plaid_save_item(exchanged["item_id"], exchanged["access_token"], institution, str(link.get("by") or "?"), now)
            names.append(institution.get("name") or "")
            log.info("bank: %s linked %s", link.get("by"), institution.get("name") or exchanged["item_id"])
    elif link.get("expires", 0) <= now:
        return {"status": "expired"}
    elif any(s.get("exit") or s.get("finished_at") for s in sessions):
        return {"status": "exited"}
    else:
        return {"status": "pending"}
    links[token] = {**link, "done": names}
    state_set(PLAID_LINKS_KEY, links)
    bank_sync_in_background()
    return {"status": "linked", "institutions": names}


def plaid_liability_terms(liabilities: Any) -> dict[str, dict[str, Any]]:
    """Each loan's and card's rate, next payment and when it's due, by account, from a `/liabilities/get` answer."""
    terms: dict[str, dict[str, Any]] = {}
    if not isinstance(liabilities, dict):
        return terms
    for card in liabilities.get("credit") or []:
        aprs = [a for a in card.get("aprs") or [] if isinstance(a, dict)]
        # The rate purchases are charged, not a cash advance's or a transfer offer's.
        purchase = next((a for a in aprs if a.get("apr_type") == "purchase_apr"), aprs[0] if aprs else {})
        terms[card.get("account_id")] = {"apr": purchase.get("apr_percentage"), "min_payment": card.get("minimum_payment_amount"), "due": card.get("next_payment_due_date")}
    for loan in liabilities.get("student") or []:
        terms[loan.get("account_id")] = {"apr": loan.get("interest_rate_percentage"), "min_payment": loan.get("minimum_payment_amount"), "due": loan.get("next_payment_due_date")}
    for loan in liabilities.get("mortgage") or []:
        rate = loan.get("interest_rate") if isinstance(loan.get("interest_rate"), dict) else {}
        terms[loan.get("account_id")] = {"apr": rate.get("percentage"), "min_payment": loan.get("next_monthly_payment"), "due": loan.get("next_payment_due_date")}
    return terms


def plaid_store(item_id: str, accounts: list[dict[str, Any]], terms: dict[str, dict[str, Any]] | None, holdings: list[dict[str, Any]] | None,
                securities: dict[str, dict[str, Any]], now: float) -> None:
    """
    Replaces what is kept of an institution with what Plaid just said: its accounts, their loan
    terms, and what they hold. `terms` or `holdings` None leaves those as they were (Plaid hadn't
    them ready), though an account that is gone still takes its holdings with it. Each account
    new to the relay is given its key in the feed (see `plaid_assign_keys`).
    """
    def write(c: sqlite3.Connection) -> None:
        kept = terms
        if kept is None:
            kept = {row[0]: {"apr": row[1], "min_payment": row[2], "due": row[3]}
                    for row in c.execute("SELECT account_id, apr, min_payment, due FROM plaid_accounts WHERE item_id=?", (item_id,))}
        c.execute("DELETE FROM plaid_accounts WHERE item_id=?", (item_id,))
        for a in accounts:
            balances = a.get("balances") if isinstance(a.get("balances"), dict) else {}
            term = kept.get(a.get("account_id"), {})
            c.execute(
                "INSERT OR REPLACE INTO plaid_accounts VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (a.get("account_id"), item_id, a.get("name") or "", a.get("official_name"), a.get("mask"), a.get("type"), a.get("subtype"),
                 balances.get("current"), balances.get("available"), balances.get("limit"),
                 balances.get("iso_currency_code") or balances.get("unofficial_currency_code"),
                 term.get("apr"), term.get("min_payment"), term.get("due"), now),
            )
        if holdings is None:
            c.execute("DELETE FROM plaid_holdings WHERE item_id=? AND account_id NOT IN (SELECT account_id FROM plaid_accounts WHERE item_id=?)", (item_id, item_id))
        else:
            c.execute("DELETE FROM plaid_holdings WHERE item_id=?", (item_id,))
            for h in holdings:
                security = securities.get(h.get("security_id"), {})
                c.execute(
                    "INSERT OR REPLACE INTO plaid_holdings VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    (h.get("account_id"), h.get("security_id"), item_id, security.get("ticker_symbol"), security.get("name") or "", security.get("type"),
                     h.get("quantity"), h.get("institution_price"), h.get("institution_price_as_of"), h.get("institution_value"), h.get("cost_basis"),
                     h.get("iso_currency_code") or h.get("unofficial_currency_code")),
                )
        plaid_assign_keys(c)
        c.commit()

    with_db(write)


def plaid_sync_item(item_id: str, access: str, now: float) -> str | None:
    """
    Reads one institution from Plaid into relay.db: its accounts' balances, what its investment
    accounts hold, and its loans' terms. Answers Plaid's error code when it refused, having kept
    what was read before (an old balance beats none) and noted the refusal on the institution.
    """
    try:
        item = plaid_post("/item/get", {"access_token": access}).get("item") or {}
        products = sorted({str(p) for p in item.get("products") or []})
        accounts = [a for a in plaid_post("/accounts/get", {"access_token": access}).get("accounts") or [] if isinstance(a, dict)]
        holdings: list[dict[str, Any]] | None = []
        securities: dict[str, dict[str, Any]] = {}
        if "investments" in products:
            try:
                answer = plaid_post("/investments/holdings/get", {"access_token": access})
                holdings = [h for h in answer.get("holdings") or [] if isinstance(h, dict)]
                securities = {s.get("security_id"): s for s in answer.get("securities") or [] if isinstance(s, dict)}
            except PlaidError as e:
                if e.code == "PRODUCT_NOT_READY":
                    holdings = None
                elif e.code not in PLAID_NOTHING_THERE:
                    raise
        terms: dict[str, dict[str, Any]] | None = {}
        if "liabilities" in products:
            try:
                terms = plaid_liability_terms(plaid_post("/liabilities/get", {"access_token": access}).get("liabilities"))
            except PlaidError as e:
                if e.code == "PRODUCT_NOT_READY":
                    terms = None
                elif e.code not in PLAID_NOTHING_THERE:
                    raise
        plaid_store(item_id, accounts, terms, holdings, securities, now)
        # The link itself can be on its way out while the data still comes (a consent about to lapse).
        warning = item.get("error") if isinstance(item.get("error"), dict) else {}
        with_db(lambda c: (c.execute(
            "UPDATE plaid_items SET products=?, synced_at=?, error=?, error_message=?, consent_expires=?, institution_id=COALESCE(?, institution_id) WHERE item_id=?",
            (json.dumps(products), now, warning.get("error_code"), warning.get("error_message"), item.get("consent_expiration_time"), item.get("institution_id"), item_id),
        ), c.commit()))
        return None
    except PlaidError as e:
        log.warning("bank: %s didn't sync: %s", item_id[:8], e.code)
        with_db(lambda c: (c.execute("UPDATE plaid_items SET error=?, error_message=? WHERE item_id=?", (e.code, e.message, item_id)), c.commit()))
        return e.code
    except Exception as e:
        # Plaid unreachable, or an answer in a shape this doesn't know. Not the detail: it may quote the request.
        log.warning("bank: %s didn't sync: %s", item_id[:8], type(e).__name__)
        with_db(lambda c: (c.execute("UPDATE plaid_items SET error=?, error_message=? WHERE item_id=?", ("UNREACHABLE", "Couldn't reach Plaid", item_id)), c.commit()))
        return "UNREACHABLE"


def feed_key(label: str, taken: set[str]) -> str:
    """A key no other account has: `label` ("Institution Account 1234") itself, or with the first of " (2)", " (3)"… that is free."""
    label = label or "Account"
    if label not in taken:
        return label
    n = 2
    while f"{label} ({n})" in taken:
        n += 1
    return f"{label} ({n})"


def plaid_assign_keys(c: sqlite3.Connection) -> None:
    """
    Gives each account that hasn't one its key in the feed, and frees the keys of accounts that
    are gone. A key once given stays that account's whatever happens around it: the budget
    sheet's formulas look accounts up by key, so closing or unlinking one of two accounts of the
    same name must not hand its key to the other, nor a bank renaming an account change its key.
    A freed key goes to the next new account of that name, which is what makes an institution
    unlinked and linked again come back under the keys it had. The caller commits.
    """
    c.execute("DELETE FROM plaid_feed_keys WHERE account_id NOT IN (SELECT account_id FROM plaid_accounts)")
    taken = {row[0] for row in c.execute("SELECT key FROM plaid_feed_keys")}
    new = c.execute(
        "SELECT a.account_id, i.institution, a.name, a.mask FROM plaid_accounts a JOIN plaid_items i ON i.item_id = a.item_id"
        " WHERE a.account_id NOT IN (SELECT account_id FROM plaid_feed_keys) ORDER BY i.linked_at, i.item_id, a.type, a.name, a.account_id"
    ).fetchall()
    for account_id, institution, name, mask in new:
        key = feed_key(" ".join(part for part in (institution, name, mask) if part), taken)
        taken.add(key)
        c.execute("INSERT INTO plaid_feed_keys VALUES (?,?)", (account_id, key))


def bank_institutions() -> list[dict[str, Any]]:
    """Every linked institution with its accounts as last read, the oldest link first. Never a token."""
    items = with_db(lambda c: c.execute(
        "SELECT item_id, institution, linked_at, synced_at, error, error_message, consent_expires FROM plaid_items ORDER BY linked_at, item_id"
    ).fetchall())
    accounts = with_db(lambda c: c.execute(
        "SELECT account_id, item_id, name, official_name, mask, type, subtype, current, available, credit_limit, currency, apr, min_payment, due, updated"
        " FROM plaid_accounts ORDER BY type, name, account_id"
    ).fetchall())
    held = dict(with_db(lambda c: c.execute("SELECT account_id, COUNT(*) FROM plaid_holdings GROUP BY account_id").fetchall()))
    keys = dict(with_db(lambda c: c.execute("SELECT account_id, key FROM plaid_feed_keys").fetchall()))
    institutions = []
    for item_id, name, linked_at, synced_at, error, error_message, consent_expires in items:
        institutions.append({
            "id": item_id,
            "name": name or "",
            "linked_at": linked_at,
            "synced_at": synced_at,
            "error": error,
            "error_message": error_message,
            "needs_relink": error in PLAID_RELINK,
            "consent_expires": consent_expires,
            "accounts": [
                {"id": a[0], "key": keys.get(a[0]) or a[2], "name": a[2], "official_name": a[3], "mask": a[4], "type": a[5], "subtype": a[6], "balance": a[7],
                 "available": a[8], "limit": a[9], "currency": a[10], "apr": a[11], "min_payment": a[12], "due": a[13], "updated": a[14],
                 "holdings": held.get(a[0], 0)}
                for a in accounts if a[1] == item_id
            ],
        })
    return institutions


def feed_tables(institutions: list[dict[str, Any]], holdings: list[tuple], zone: ZoneInfo | None) -> dict[str, list[list[Any]]]:
    """
    The two feed tabs' cells, headers first: every account (see FEED_ACCOUNT_COLUMNS) and every
    holding (`holdings` as (account id, ticker, name, quantity, price, value, cost basis, kind,
    currency, price as of)). An empty cell is "", which clears it; None would leave what was there.
    """
    def cells(row: list[Any]) -> list[Any]:
        return ["" if v is None else v for v in row]

    accounts = [FEED_ACCOUNT_COLUMNS]
    keyed: dict[str, tuple[str, str]] = {}
    for institution in institutions:
        for a in institution["accounts"]:
            keyed[a["id"]] = (a["key"], institution["name"])
            updated = datetime.fromtimestamp(a["updated"], zone or timezone.utc).strftime("%Y-%m-%d %H:%M") if a["updated"] else None
            accounts.append(cells([a["key"], a["balance"], a["available"], a["limit"], institution["name"], a["name"], a["mask"], a["type"], a["subtype"],
                                   a["apr"], a["min_payment"], a["due"], a["currency"], updated]))
    held = [FEED_HOLDING_COLUMNS]
    for account_id, ticker, name, quantity, price, value, cost_basis, kind, currency, price_as_of in holdings:
        if account_id in keyed:
            key, institution_name = keyed[account_id]
            held.append(cells([key, ticker, name, quantity, price, value, cost_basis, kind, institution_name, currency, price_as_of]))
    return {FEED_ACCOUNTS_TAB: accounts, FEED_HOLDINGS_TAB: held}


def feed_google() -> Any:
    """The Sheets session that may write, used for the feed sheet alone; the budget sheet's own (`finance_google`) stays read-only."""
    if _feed_google["session"] is None:
        if not os.path.exists(FINANCE_SHEET_KEY):
            raise HTTPException(503, {"error": "no_key", "message": "The relay has no service-account key to write the feed with"})
        try:
            creds = service_account.Credentials.from_service_account_file(FINANCE_SHEET_KEY, scopes=[SHEETS_WRITE_SCOPE])
        except (ValueError, KeyError) as e:
            raise HTTPException(503, {"error": "no_key", "message": "The relay's key isn't a service-account key"}) from e
        _feed_google["account"] = creds.service_account_email
        _feed_google["session"] = AuthorizedSession(creds)
    return _feed_google["session"]


def feed_write(tables: dict[str, list[list[Any]]]) -> None:
    """
    Writes each tab of `tables` from its A1, adding a tab the sheet hasn't got. Nothing is cleared
    first: rows the last write had and this one doesn't are overwritten blank in the same call, so
    a formula looking the feed up never finds it empty. Raises HTTPException with the setup
    problem when Google refuses (see `sheet_problem`; "not_shared" here means not as an Editor).
    """
    session = feed_google()
    account = _feed_google["account"]
    base = f"{SHEETS_API}/{FINANCE_FEED_SHEET_ID}"

    def answered(response: Any) -> Any:
        if response.status_code != 200:
            raise HTTPException(*sheet_problem(response.status_code, _google_json(response), account))
        return _google_json(response)

    meta = answered(session.get(base, params={"fields": "sheets(properties(title,sheetType))"}, timeout=15))
    missing = [tab for tab in tables if tab not in grid_titles(meta)]
    if missing:
        answered(session.post(f"{base}:batchUpdate", json={"requests": [{"addSheet": {"properties": {"title": tab}}} for tab in missing]}, timeout=15))
    before = state_get(PLAID_FEED_ROWS_KEY) or {}
    data = []
    for tab, rows in tables.items():
        blanks = [[""] * len(rows[0])] * max(0, int(before.get(tab, 0)) - len(rows))
        data.append({"range": f"{sheet_range(tab)}!A1", "majorDimension": "ROWS", "values": rows + blanks})
    # RAW: an account's "0123" stays those four characters rather than becoming the number 123.
    answered(session.post(f"{base}/values:batchUpdate", json={"valueInputOption": "RAW", "data": data}, timeout=20))
    state_set(PLAID_FEED_ROWS_KEY, {tab: len(rows) for tab, rows in tables.items()})


def write_bank_feed(now: float) -> None:
    """Writes what relay.db holds to the feed sheet, if there is one, and keeps how that went for the app to show."""
    if not FINANCE_FEED_SHEET_ID:
        return
    holdings = with_db(lambda c: c.execute(
        "SELECT account_id, ticker, name, quantity, price, value, cost_basis, kind, currency, price_as_of FROM plaid_holdings ORDER BY account_id, value DESC, security_id"
    ).fetchall())
    try:
        feed_write(feed_tables(bank_institutions(), holdings, household_zone()))
        state_set(PLAID_FEED_KEY, {"at": now, "error": None, "message": None})
        # The budget sheet's cells follow the feed: the next read of it shouldn't be a minute-old one.
        _finance_cache["at"] = 0.0
    except HTTPException as e:
        detail = e.detail if isinstance(e.detail, dict) else {}
        log.warning("bank: the feed sheet wasn't written: %s", detail.get("error") or e.status_code)
        state_set(PLAID_FEED_KEY, {
            "at": now, "error": detail.get("error") or "google_error", "message": detail.get("message"),
            "activation_url": detail.get("activation_url"), "service_account": detail.get("service_account"),
        })
    except Exception as e:
        log.warning("bank: the feed sheet wasn't written: %s", e)
        state_set(PLAID_FEED_KEY, {"at": now, "error": "google_error", "message": "Couldn't reach Google"})


def plaid_sync_all(now: float | None = None, wait: bool = False) -> bool:
    """
    Every linked institution from Plaid, then the feed sheet. One at a time: False when a sync is
    already under way, unless `wait` says to queue behind it (a link just made must not be missed
    by a sync that began before it).
    """
    if not _bank_lock.acquire(blocking=wait):
        return False
    try:
        now = time.time() if now is None else now
        items = with_db(lambda c: c.execute("SELECT item_id, access FROM plaid_items ORDER BY linked_at, item_id").fetchall())
        failed = [item_id for item_id, access in items if plaid_sync_item(item_id, access, now)]
        state_set(PLAID_SYNCED_KEY, {"at": now, "institutions": len(items), "failed": len(failed)})
        log.info("bank: synced %d institutions, %d failed", len(items), len(failed))
        plaid_transactions_sync(now)
        write_bank_feed(now)
    finally:
        _bank_lock.release()
    budget_check_quietly()
    return True


def bank_sync_in_background() -> None:
    threading.Thread(target=lambda: plaid_sync_all(wait=True), name="bank-sync-now", daemon=True).start()


def bank_sync_slot(now: float, zone: ZoneInfo | None) -> datetime:
    """The latest BANK_SYNC_HOUR at or before `now`, on the household's clock (UTC when unknown)."""
    local = datetime.fromtimestamp(now, zone or timezone.utc)
    slot = local.replace(hour=BANK_SYNC_HOUR, minute=0, second=0, microsecond=0)
    return slot if slot <= local else slot - timedelta(days=1)


def bank_sync_due(now: float, last: float, zone: ZoneInfo | None) -> bool:
    """Whether the day's sync is still owed: nothing has synced since the latest BANK_SYNC_HOUR."""
    return last < bank_sync_slot(now, zone).timestamp()


def bank_forever() -> None:
    """
    Once a day at BANK_SYNC_HOUR, and on a start that slept through it: every institution, then
    the feed. Every hour in between: what was bought on the cards (see "budget").
    """
    while True:
        time.sleep(BANK_CHECK_SECONDS)
        try:
            if not plaid_on():
                continue
            last = float((state_get(PLAID_SYNCED_KEY) or {}).get("at") or 0)
            linked = with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_items").fetchone()[0])
            if linked and bank_sync_due(time.time(), last, household_zone()):
                plaid_sync_all()
            elif linked and transactions_due(time.time(), float((state_get(PLAID_TXN_SYNCED_KEY) or {}).get("at") or 0)):
                plaid_transactions_sync_all()
        except Exception:
            log.exception("bank sync failed")


def bank_status(now: float | None = None) -> dict[str, Any]:
    """What the app's bank sync page shows: the institutions and their accounts, when they were last read and are next, and how the feed sheet's last write went."""
    now = time.time() if now is None else now
    synced = state_get(PLAID_SYNCED_KEY) or {}
    feed = state_get(PLAID_FEED_KEY) or {}
    return {
        "configured": plaid_on(),
        "environment": PLAID_ENV,
        "institutions": bank_institutions(),
        "synced_at": synced.get("at"),
        "syncing": _bank_lock.locked(),
        "next_sync_at": (bank_sync_slot(now, household_zone()) + timedelta(days=1)).timestamp(),
        "feed": {
            "configured": bool(FINANCE_FEED_SHEET_ID),
            "url": f"https://docs.google.com/spreadsheets/d/{FINANCE_FEED_SHEET_ID}/edit" if FINANCE_FEED_SHEET_ID else None,
            "written_at": feed.get("at") if not feed.get("error") else None,
            "error": feed.get("error"),
            "message": feed.get("message"),
            "activation_url": feed.get("activation_url"),
            "service_account": _feed_google["account"] or feed.get("service_account") or _finance_google["account"],
        },
    }


def require_bank(request: Request, response: Response) -> str:
    """A signed-in user who may see the finances, on a relay with Plaid set up; their username."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    if not plaid_on():
        raise HTTPException(503, {"error": "not_configured", "message": "Plaid isn't set up on the relay"})
    return user


def plaid_problem(e: PlaidError) -> HTTPException:
    return HTTPException(502, {"error": "plaid_error", "code": e.code, "message": e.message or f"Plaid answered {e.code}"})


@app.get("/finance/bank")
def get_bank(request: Request, response: Response) -> dict[str, Any]:
    """The linked institutions and how their sync is going (see "bank sync"). Answers without Plaid set up too, to say so."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    return bank_status()


@app.post("/finance/bank/link")
def post_bank_link(body: BankLink, request: Request, response: Response) -> dict[str, Any]:
    """Starts linking an institution: the page to open, and the token `GET /finance/bank/link/{token}` follows it by."""
    user = require_bank(request, response)
    try:
        return plaid_link_start(user, body.kind, body.institution)
    except PlaidError as e:
        log.warning("bank: no link for %s: %s", user, e.code)
        raise plaid_problem(e) from e


@app.get("/finance/bank/link/{token}")
def get_bank_link(token: str, request: Request, response: Response) -> dict[str, Any]:
    """How a link is getting on (see `plaid_link_finish`), with the institutions as they stand once it is made."""
    require_bank(request, response)
    try:
        result = plaid_link_finish(token)
    except PlaidError as e:
        log.warning("bank: linking failed: %s", e.code)
        raise plaid_problem(e) from e
    if result["status"] == "linked":
        # The sync just started may not hold the lock yet: say it is on its way regardless.
        result["bank"] = {**bank_status(), "syncing": True}
    return result


@app.post("/finance/bank/sync")
def post_bank_sync(request: Request, response: Response) -> dict[str, Any]:
    """Reads every institution now rather than at the next BANK_SYNC_HOUR. Answers at once; `syncing` says it is under way."""
    require_bank(request, response)
    with _sync_ask_lock:
        # The last sync asked for counts as well as the last one done: a second tap lands before
        # the first one's worker holds the lock, and would otherwise queue a second sync behind it.
        last = max(float((state_get(PLAID_SYNCED_KEY) or {}).get("at") or 0), _sync_asked["at"])
        if _bank_lock.locked() or time.time() - last < BANK_MIN_SYNC_SECONDS:
            return bank_status()
        _sync_asked["at"] = time.time()
        bank_sync_in_background()
    return {**bank_status(), "syncing": True}


@app.delete("/finance/bank/institutions/{item_id}")
def delete_bank_institution(item_id: str, request: Request, response: Response) -> dict[str, Any]:
    """Unlinks an institution: Plaid is told to forget it, and its token, accounts and feed rows go."""
    user = require_bank(request, response)
    # Behind any sync under way, and ahead of the next: a sync that read this institution before
    # it was forgotten would otherwise store its accounts again, and write them back to the feed.
    if not _bank_lock.acquire(timeout=BANK_UNLINK_WAIT_SECONDS):
        raise HTTPException(503, {"error": "busy", "message": "The accounts are being read just now. Try again in a minute."})
    try:
        row = with_db(lambda c: c.execute("SELECT access, institution FROM plaid_items WHERE item_id=?", (item_id,)).fetchone())
        if not row:
            raise HTTPException(404, {"error": "not_found", "message": "That institution isn't linked"})
        try:
            plaid_post("/item/remove", {"access_token": row[0]})
        except PlaidError as e:
            # Plaid already not knowing it is the result wanted; anything else and the token is kept to try again.
            if e.code not in PLAID_GONE:
                raise plaid_problem(e) from e

        def forget(c: sqlite3.Connection) -> None:
            for table in ("plaid_transactions", "plaid_holdings", "plaid_accounts", "plaid_items"):
                c.execute(f"DELETE FROM {table} WHERE item_id=?", (item_id,))
            plaid_assign_keys(c)
            c.commit()

        with_db(forget)
        log.info("bank: %s unlinked %s", user, row[1] or item_id[:8])
        write_bank_feed(time.time())
    finally:
        _bank_lock.release()
    return bank_status()


# ---------------------------------------------------------------- budget
#
# The budget sheet says what a month is meant to cost. The credit cards say what it is costing:
# every hour the relay asks Plaid what has been bought on each linked card (`/transactions/sync`,
# which hands over only what changed since the last ask) and keeps the purchases in relay.db. The
# app's Budget page reads the month from here: what has been spent, by whom, against which limit.
#
# Each card is given a role, by its key in the feed (which outlives a card being linked again):
# "split" for a card two people carry, "family" for one whose spending is everyone's, "person:<name>"
# for one person's own card, "ignore" for one that is no part of the budget. A purchase on a split
# card belongs to whoever the bank says made it, which it rarely says, or to whoever its merchant
# was said to always be, or to nobody yet: someone then tags it in the app, and a tag made by
# hand outranks everything else.
#
# The limits, the roles and the two numbers the relay can't work out for itself (the month's
# take-home and the bills that aren't paid by card, both of which only the app reads out of the
# budget sheet) are one JSON value in `state`. Past the limits, and past what take-home leaves
# after those bills, the phones of the people who may see the finances are told (`budget_check`).
#
# Only credit cards' transactions are kept, and never in the log: a purchase is nobody's business
# but the household's.

# How often the cards are asked about. Plaid itself hears from a bank a few times a day, so most
# asks find nothing new; the page says when something last did.
TXN_SYNC_SECONDS = 3600
# ...except a card linked in the last day whose first transactions Plaid is still fetching: its
# month isn't on the page until they arrive, so it is asked at every check until they have.
TXN_NEW_SECONDS = 86400
TXN_PAGE = 500
# More pages than any real card has in one ask: a cursor that never ends is given up on.
TXN_MAX_PAGES = 100
# Plaid starts a paged answer over when the transactions change under it.
TXN_RESTARTS = 3
# How long a transaction Plaid took back is remembered, for its tag to pass to what replaced it.
TXN_TOMBSTONE_SECONDS = 30 * 86400
TXN_READY = {"INITIAL_UPDATE_COMPLETE", "HISTORICAL_UPDATE_COMPLETE"}
PLAID_TXN_SYNCED_KEY = "plaid_txn_synced"
BUDGET_KEY = "budget"
BUDGET_ALERTS_KEY = "budget_alerts"
BUDGET_FAMILY = "family"
BUDGET_PERSON = "person:"
BUDGET_SPLIT = "split"
BUDGET_IGNORE = "ignore"
BUDGET_MAX_PEOPLE = 6
BUDGET_ALERT_NAMES = ("total", "savings", "buckets")
# The share of the month's limit at which the phones are first told, and then told it is passed.
BUDGET_TOTAL_PERCENTS = (80, 100)
BUDGET_HISTORY_MONTHS = 6
BUDGET_TOP_MERCHANTS = 8

# "Sync now" on the Budget page, when it was last asked for (see `_sync_asked`).
_txn_asked = {"at": 0.0}
_budget_alert_lock = threading.Lock()
# How a card being paid off reads when its category doesn't say so.
_PAYMENT_WORDS = re.compile(r"\b(payment|autopay|thank you)\b", re.IGNORECASE)
_MONTH = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


class BudgetPatch(BaseModel):
    # Each is left as it stands when absent. Inside `limits` and `roles`, a null takes one away.
    people: list[str] | None = None
    limits: dict[str, Any] | None = None
    roles: dict[str, Any] | None = None
    card_paid_lines: list[str] | None = None
    alerts: dict[str, bool] | None = None
    sheet: dict[str, Any] | None = None


class BudgetTag(BaseModel):
    # "person:<name>", "family", or null to take the tag off.
    bucket: str | None = None
    # Whether every purchase from this merchant goes the same way from now on.
    remember: bool = False


def transactions_due(now: float, last: float) -> bool:
    """Whether the cards are to be asked now: an hour after the last time, or sooner while a new card's first transactions are awaited."""
    return now - last >= TXN_SYNC_SECONDS or (now - last >= BANK_CHECK_SECONDS and transactions_awaited(now))


def transactions_awaited(now: float) -> bool:
    """Whether an institution linked in the last day for its transactions hasn't had its first ones from Plaid yet."""
    rows = with_db(lambda c: c.execute("SELECT products, txn_status FROM plaid_items WHERE linked_at > ?", (now - TXN_NEW_SECONDS,)).fetchall())
    return any("transactions" in (products or "") and status not in TXN_READY for products, status in rows)


def merchant_key(merchant: str | None, name: str | None) -> str:
    """
    What a merchant is remembered by: the first few words of its name, letters only (any
    alphabet's), so that "COSTCO WHSE #0123" and "Costco Whse #0456" are the same shop. A name
    with no letters at all is remembered as it is written.
    """
    text = (merchant or name or "").lower().replace("'", "")
    return " ".join(re.findall(r"[^\W\d_]+", text)[:3]) or text.strip()


def txn_kind(amount: float, category: str | None, name: str | None, detail: str | None = None) -> str:
    """
    What a card's transaction is: "spend" for a purchase (Plaid's amounts are positive for money
    going out), "payment" for the card being paid off, which is no part of what was spent, and
    "refund" for any other money coming back, which is taken off the month it lands in. What
    Plaid itself calls a credit card payment is one whichever way its amount points.
    """
    if detail == "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT":
        return "payment"
    if amount >= 0:
        return "spend"
    if category in ("LOAN_PAYMENTS", "TRANSFER_IN", "TRANSFER_OUT") or _PAYMENT_WORDS.search(name or ""):
        return "payment"
    return "refund"


def txn_row(txn: dict[str, Any], item_id: str, now: float) -> tuple | None:
    """One of Plaid's transactions as a `plaid_transactions` row (without its tag), or None for one that says too little to keep."""
    txn_id, amount = txn.get("transaction_id"), txn.get("amount")
    # The day the card was used when the bank says, else the day it settled.
    date = txn.get("authorized_date") or txn.get("date")
    if not txn_id or not txn.get("account_id") or not date or isinstance(amount, bool) or not isinstance(amount, (int, float)):
        return None
    category = txn.get("personal_finance_category") if isinstance(txn.get("personal_finance_category"), dict) else {}
    name, merchant = txn.get("name") or "", txn.get("merchant_name")
    return (
        txn_id, item_id, txn["account_id"], str(date)[:10], txn.get("date"), float(amount), txn.get("iso_currency_code") or txn.get("unofficial_currency_code"),
        name, merchant, merchant_key(merchant, name), category.get("primary"), category.get("detailed"), int(bool(txn.get("pending"))),
        txn.get("pending_transaction_id"), txn.get("account_owner"), txn_kind(float(amount), category.get("primary"), name, category.get("detailed")), now,
    )


def plaid_transactions_pull(access: str, cursor: str | None) -> dict[str, Any]:
    """
    Everything that changed on an institution's accounts since `cursor` (None: from the start):
    the transactions `added` and `modified`, the ids `removed`, the `cursor` to ask from next
    time and Plaid's `status` for how much of the history it has fetched. All of the answer's
    pages or none: Plaid says to start over when the transactions change between two pages.
    """
    for _ in range(TXN_RESTARTS):
        added: list[dict[str, Any]] = []
        modified: list[dict[str, Any]] = []
        removed: list[str] = []
        at = cursor
        try:
            for _page in range(TXN_MAX_PAGES):
                body: dict[str, Any] = {"access_token": access, "count": TXN_PAGE}
                if at:
                    body["cursor"] = at
                answer = plaid_post("/transactions/sync", body)
                added += [t for t in answer.get("added") or [] if isinstance(t, dict)]
                modified += [t for t in answer.get("modified") or [] if isinstance(t, dict)]
                removed += [t["transaction_id"] for t in answer.get("removed") or [] if isinstance(t, dict) and t.get("transaction_id")]
                at = answer.get("next_cursor") or at
                if not answer.get("has_more"):
                    return {"added": added, "modified": modified, "removed": removed, "cursor": at, "status": answer.get("transactions_update_status")}
            raise PlaidError("TOO_MANY_PAGES", "Plaid's answer didn't end")
        except PlaidError as e:
            if e.code != "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION":
                raise
    raise PlaidError("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION", "The transactions kept changing while they were read")


def plaid_transactions_store(item_id: str, pulled: dict[str, Any], now: float) -> int:
    """
    Puts what `plaid_transactions_pull` read into relay.db and moves the institution's cursor on,
    as one change: a relay stopped halfway asks for the same again. Only the credit cards'
    transactions are kept. A tag made by hand stays on a transaction Plaid changed, and passes
    from a pending purchase to the posted one that replaces it. Answers how many rows changed.
    """
    def write(c: sqlite3.Connection) -> int:
        cards = {row[0] for row in c.execute("SELECT account_id FROM plaid_accounts WHERE item_id=? AND type='credit'", (item_id,))}
        changed = 0
        for txn in pulled["added"] + pulled["modified"]:
            row = txn_row(txn, item_id, now)
            if row is None or row[2] not in cards:
                continue
            c.execute(
                "INSERT INTO plaid_transactions (txn_id, item_id, account_id, date, posted, amount, currency, name, merchant, merchant_key, category,"
                " category_detail, pending, pending_id, owner, kind, updated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                " ON CONFLICT(txn_id) DO UPDATE SET item_id=excluded.item_id, account_id=excluded.account_id, date=excluded.date, posted=excluded.posted,"
                " amount=excluded.amount, currency=excluded.currency, name=excluded.name, merchant=excluded.merchant, merchant_key=excluded.merchant_key,"
                " category=excluded.category, category_detail=excluded.category_detail, pending=excluded.pending, pending_id=excluded.pending_id,"
                " owner=excluded.owner, kind=excluded.kind, updated=excluded.updated, removed_at=NULL",
                row,
            )
            changed += 1
            if row[13]:
                was = c.execute("SELECT tag, tag_by FROM plaid_transactions WHERE txn_id=? AND tag IS NOT NULL", (row[13],)).fetchone()
                if was:
                    c.execute("UPDATE plaid_transactions SET tag=?, tag_by=? WHERE txn_id=? AND tag IS NULL", (was[0], was[1], row[0]))
        for txn_id in pulled["removed"]:
            changed += c.execute("UPDATE plaid_transactions SET removed_at=? WHERE txn_id=? AND item_id=? AND removed_at IS NULL", (now, txn_id, item_id)).rowcount
        c.execute("DELETE FROM plaid_transactions WHERE removed_at IS NOT NULL AND removed_at < ?", (now - TXN_TOMBSTONE_SECONDS,))
        c.execute(
            "UPDATE plaid_items SET txn_cursor=?, txn_synced_at=?, txn_status=?, txn_changed_at=CASE WHEN ? THEN ? ELSE txn_changed_at END WHERE item_id=?",
            (pulled["cursor"], now, pulled["status"], int(changed > 0), now, item_id),
        )
        c.commit()
        return changed

    return with_db(write)


def plaid_transactions_sync_item(item_id: str, access: str, cursor: str | None, now: float) -> str | None:
    """
    One institution's new transactions into relay.db. Answers Plaid's error code when it refused,
    having kept what was read before. A refusal that needs the person (a sign-in that lapsed) is
    noted on the institution at once rather than at the next morning's read of the balances.
    """
    try:
        plaid_transactions_store(item_id, plaid_transactions_pull(access, cursor), now)
        return None
    except PlaidError as e:
        log.warning("budget: %s's transactions weren't read: %s", item_id[:8], e.code)
        if e.code in PLAID_RELINK | PLAID_GONE:
            with_db(lambda c: (c.execute("UPDATE plaid_items SET error=?, error_message=? WHERE item_id=?", (e.code, e.message, item_id)), c.commit()))
        return e.code
    except Exception as e:
        # Plaid unreachable, or an answer in a shape this doesn't know. Not the detail: it may quote the request.
        log.warning("budget: %s's transactions weren't read: %s", item_id[:8], type(e).__name__)
        return "UNREACHABLE"


def plaid_transactions_sync(now: float) -> None:
    """
    The new transactions of every institution that was linked for them. The caller holds
    `_bank_lock`. An institution linked for something else is never asked: asking would add the
    product to it. Nor is one whose accounts aren't known yet, since which of them are cards
    decides what is kept.
    """
    items = with_db(lambda c: c.execute(
        "SELECT item_id, access, txn_cursor, products, (SELECT COUNT(*) FROM plaid_accounts a WHERE a.item_id = plaid_items.item_id)"
        " FROM plaid_items ORDER BY linked_at, item_id"
    ).fetchall())
    read = failed = 0
    for item_id, access, cursor, products, accounts in items:
        try:
            has = "transactions" in json.loads(products or "[]")
        except ValueError:
            has = False
        if not has or not accounts:
            continue
        read += 1
        if plaid_transactions_sync_item(item_id, access, cursor, now):
            failed += 1
    state_set(PLAID_TXN_SYNCED_KEY, {"at": now, "institutions": read, "failed": failed})
    log.info("budget: read %d institutions' transactions, %d failed", read, failed)


def plaid_transactions_sync_all(now: float | None = None, wait: bool = False) -> bool:
    """The hourly read (see `plaid_transactions_sync`), then the alerts. False when a sync is already under way, unless `wait`."""
    if not _bank_lock.acquire(blocking=wait):
        return False
    try:
        plaid_transactions_sync(time.time() if now is None else now)
    finally:
        _bank_lock.release()
    budget_check_quietly()
    return True


def budget_sync_in_background() -> None:
    threading.Thread(target=lambda: plaid_transactions_sync_all(wait=True), name="budget-sync-now", daemon=True).start()


def budget_config() -> dict[str, Any]:
    """The budget's settings as kept, every part present: see `BudgetPatch` and `budget_config_save`."""
    kept = state_get(BUDGET_KEY) or {}
    people = [p for p in kept.get("people") or [] if isinstance(p, str)]
    limits = kept.get("limits") or {}
    sheet = kept.get("sheet") or {}
    return {
        "people": people,
        "limits": {"total": limits.get("total"), "people": {p: (limits.get("people") or {}).get(p) for p in people}, "family": limits.get("family")},
        "roles": dict(kept.get("roles") or {}),
        "card_paid_lines": list(kept.get("card_paid_lines") or []),
        "alerts": {name: bool((kept.get("alerts") or {}).get(name)) for name in BUDGET_ALERT_NAMES},
        "sheet": {"take_home": sheet.get("take_home"), "bills_off_card": sheet.get("bills_off_card"), "at": sheet.get("at")},
    }


def _budget_refusal(message: str) -> HTTPException:
    return HTTPException(400, {"error": "bad_budget", "message": message})


def _budget_amount(value: Any, what: str) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1e9:
        raise _budget_refusal(f"{what} is an amount of money, or nothing")
    return round(float(value), 2)


def budget_buckets(people: list[str]) -> set[str]:
    """Every bucket a purchase can be put in: each person's, and the family's."""
    return {BUDGET_FAMILY} | {BUDGET_PERSON + p for p in people}


def budget_config_save(patch: dict[str, Any], now: float) -> dict[str, Any]:
    """
    Changes the parts of the settings that `patch` names and answers them all. Refuses, changing
    nothing, anything that isn't what it should be: a limit that isn't an amount, a role for a
    person the budget doesn't know.
    """
    config = budget_config()
    if patch.get("people") is not None:
        people = [str(p).strip() for p in patch["people"]]
        if len(people) > BUDGET_MAX_PEOPLE or any(not p or len(p) > 40 or ":" in p or p.lower() == BUDGET_FAMILY for p in people):
            raise _budget_refusal(f"people are up to {BUDGET_MAX_PEOPLE} names")
        if len({p.lower() for p in people}) != len(people):
            raise _budget_refusal("two people have the same name")
        config["limits"]["people"] = {p: config["limits"]["people"].get(p) for p in people}
        config["people"] = people
    if patch.get("limits") is not None:
        limits = patch["limits"]
        for name in ("total", "family"):
            if name in limits:
                config["limits"][name] = _budget_amount(limits[name], f"the {name} limit")
        for person, value in (limits.get("people") or {}).items():
            if person not in config["people"]:
                raise _budget_refusal(f"{person} isn't one of the budget's people")
            config["limits"]["people"][person] = _budget_amount(value, f"{person}'s limit")
    if patch.get("roles") is not None:
        for key, role in patch["roles"].items():
            if role is None:
                config["roles"].pop(str(key), None)
            elif role in (BUDGET_SPLIT, BUDGET_FAMILY, BUDGET_IGNORE) or (isinstance(role, str) and role in budget_buckets(config["people"])):
                config["roles"][str(key)[:200]] = role
            else:
                raise _budget_refusal(f"a card is {BUDGET_SPLIT}, {BUDGET_FAMILY}, {BUDGET_IGNORE} or {BUDGET_PERSON}<one of the people>")
    if patch.get("card_paid_lines") is not None:
        config["card_paid_lines"] = sorted({str(line)[:200] for line in patch["card_paid_lines"]})
    if patch.get("alerts") is not None:
        for name, on in patch["alerts"].items():
            if name not in BUDGET_ALERT_NAMES:
                raise _budget_refusal(f"alerts are {', '.join(BUDGET_ALERT_NAMES)}")
            config["alerts"][name] = bool(on)
    if patch.get("sheet") is not None:
        sheet = patch["sheet"]
        config["sheet"] = {"take_home": _budget_amount(sheet.get("take_home"), "take-home"), "bills_off_card": _budget_amount(sheet.get("bills_off_card"), "the bills"), "at": now}
    # A role naming someone who has since left the budget goes back to unset.
    config["roles"] = {key: role for key, role in config["roles"].items() if not role.startswith(BUDGET_PERSON) or role in budget_buckets(config["people"])}
    state_set(BUDGET_KEY, config)
    return config


def budget_rules() -> dict[str, str]:
    """Each remembered merchant (by `merchant_key`) and the bucket its purchases go in."""
    return dict(with_db(lambda c: c.execute("SELECT merchant_key, bucket FROM budget_rules ORDER BY merchant_key").fetchall()))


def budget_bucket(tag: str | None, role: str, owner: str | None, key: str | None, people: list[str], rules: dict[str, str]) -> tuple[str | None, str]:
    """
    Whose a purchase is, and how that is known: a tag made by hand ("manual"); else the card it
    was on being one person's own ("account"); else, on a card two people carry, the one person
    the bank names ("bank"); else what its merchant was said to always be ("rule"); else the
    family's when the card is the family's ("account"); else nobody's yet (None, "none").
    """
    buckets = budget_buckets(people)
    if tag in buckets:
        return tag, "manual"
    if role.startswith(BUDGET_PERSON) and role in buckets:
        return role, "account"
    if role == BUDGET_SPLIT and owner:
        words = set(re.findall(r"[a-z]+", owner.lower()))
        named = [p for p in people if p.lower() in words]
        if len(named) == 1:
            return BUDGET_PERSON + named[0], "bank"
    if rules.get(key or "") in buckets:
        return rules[key], "rule"
    if role == BUDGET_FAMILY:
        return BUDGET_FAMILY, "account"
    return None, "none"


def month_days(month: str) -> int:
    return calendar.monthrange(int(month[:4]), int(month[5:]))[1]


def month_before(month: str, months: int = 1) -> str:
    index = int(month[:4]) * 12 + int(month[5:]) - 1 - months
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def budget_cards(roles: dict[str, str]) -> list[dict[str, Any]]:
    """Every linked credit card: what it is, its role in the budget if it has one, and whether its purchases can be read."""
    rows = with_db(lambda c: c.execute(
        "SELECT k.key, i.item_id, i.institution, a.name, a.mask, a.current, a.credit_limit, i.error, i.products"
        " FROM plaid_accounts a JOIN plaid_items i ON i.item_id = a.item_id LEFT JOIN plaid_feed_keys k ON k.account_id = a.account_id"
        " WHERE a.type = 'credit' ORDER BY i.linked_at, i.item_id, a.name, a.account_id"
    ).fetchall())
    return [
        {"key": key or name, "institution_id": item_id, "institution": institution or "", "name": name, "mask": mask, "role": roles.get(key or name),
         "balance": current, "limit": limit, "error": error, "needs_relink": error in PLAID_RELINK, "transactions": "transactions" in (products or "")}
        for key, item_id, institution, name, mask, current, limit, error, products in rows
    ]


def budget_purchases(first: str, last: str, config: dict[str, Any]) -> list[dict[str, Any]]:
    """
    What counts toward the budget between two days (inclusive), each put in its bucket: every
    purchase and refund on a card that has a role, pending ones too. A card being paid off
    doesn't count, nor a transaction Plaid took back, nor a pending one whose posted one is here.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT t.txn_id, t.date, t.amount, t.name, t.merchant, t.merchant_key, t.category, t.pending, t.owner, t.kind, t.tag, k.key"
        " FROM plaid_transactions t JOIN plaid_feed_keys k ON k.account_id = t.account_id"
        " WHERE t.removed_at IS NULL AND t.kind != 'payment' AND t.date >= ? AND t.date <= ?"
        " AND t.txn_id NOT IN (SELECT pending_id FROM plaid_transactions WHERE pending_id IS NOT NULL AND removed_at IS NULL)"
        " ORDER BY t.date DESC, t.amount DESC, t.txn_id",
        (first, last),
    ).fetchall())
    rules = budget_rules()
    purchases = []
    for txn_id, date, amount, name, merchant, key, category, pending, owner, kind, tag, card in rows:
        role = config["roles"].get(card) or ""
        if role in ("", BUDGET_IGNORE):
            continue
        bucket, source = budget_bucket(tag, role, owner, key, config["people"], rules)
        purchases.append({
            "id": txn_id, "date": date, "amount": round(amount, 2), "name": merchant or name or "", "merchant": key or None, "category": category,
            "pending": bool(pending), "card": card, "bucket": bucket, "source": source, "kind": kind, "remembered": bool(key) and key in rules,
        })
    return purchases


def budget_month(month: str | None = None, now: float | None = None) -> dict[str, Any]:
    """
    What the app's Budget page shows for a month (this one when not said): the cards, the
    settings, what was spent in each bucket and on each day, on what and where, every purchase,
    the months before it, and how fresh all of that is. Days are the household's.
    """
    now = time.time() if now is None else now
    today = datetime.fromtimestamp(now, household_zone() or timezone.utc).date()
    this_month = today.strftime("%Y-%m")
    month = month or this_month
    if not _MONTH.match(month):
        raise HTTPException(400, {"error": "bad_month", "message": "month is YYYY-MM"})
    days = month_days(month)
    day = today.day if month == this_month else days if month < this_month else 0
    config = budget_config()
    rules = budget_rules()
    earliest = month_before(month, BUDGET_HISTORY_MONTHS - 1)
    counted = budget_purchases(f"{earliest}-01", f"{month}-{days:02d}", config)
    purchases = [p for p in counted if p["date"][:7] == month]
    spent_by_month: dict[str, float] = {}
    for p in counted:
        spent_by_month[p["date"][:7]] = spent_by_month.get(p["date"][:7], 0.0) + p["amount"]

    def bucket(bucket_id: str | None, kind: str, name: str | None, limit: float | None) -> dict[str, Any]:
        mine = [p for p in purchases if p["bucket"] == bucket_id]
        return {"id": bucket_id or "unassigned", "kind": kind, "name": name, "spent": round(sum(p["amount"] for p in mine), 2), "limit": limit, "count": len(mine)}

    buckets = [bucket(BUDGET_PERSON + p, "person", p, config["limits"]["people"].get(p)) for p in config["people"]]
    buckets.append(bucket(BUDGET_FAMILY, "family", None, config["limits"]["family"]))
    buckets.append(bucket(None, "unassigned", None, None))
    by_day: dict[str, float] = {}
    categories: dict[str, list[float]] = {}
    merchants: dict[str, list[Any]] = {}
    for p in purchases:
        # A purchase the bank dates past the household's today (its clock is ahead) is put on
        # today, so the days still add up to what the month has spent.
        on = min(p["date"], f"{month}-{day:02d}") if day else p["date"]
        by_day[on] = by_day.get(on, 0.0) + p["amount"]
        tally = categories.setdefault(p["category"] or "OTHER", [0.0, 0])
        tally[0], tally[1] = tally[0] + p["amount"], tally[1] + 1
        shop = merchants.setdefault(p["merchant"] or p["name"].lower(), [p["name"], 0.0, 0])
        shop[1], shop[2] = shop[1] + p["amount"], shop[2] + 1
    synced = state_get(PLAID_TXN_SYNCED_KEY) or {}
    items = with_db(lambda c: c.execute("SELECT products, txn_status, txn_changed_at FROM plaid_items").fetchall())
    reading = [(status, changed) for products, status, changed in items if "transactions" in (products or "")]
    take_home, bills = config["sheet"]["take_home"], config["sheet"]["bills_off_card"]
    alerts = state_get(BUDGET_ALERTS_KEY) or {}
    return {
        "configured": plaid_on(),
        "environment": PLAID_ENV,
        "month": month,
        "today": today.isoformat(),
        "day": day,
        "days_in_month": days,
        "synced_at": synced.get("at"),
        "changed_at": max((changed for _, changed in reading if changed), default=None),
        "syncing": _bank_lock.locked(),
        "next_sync_at": float(synced.get("at") or now) + TXN_SYNC_SECONDS,
        # False while Plaid is still fetching a card's first transactions: the month isn't all there yet.
        "ready": all(status in TXN_READY for status, _ in reading),
        "cards": budget_cards(config["roles"]),
        "config": {**config, "rules": [{"merchant": key, "bucket": to} for key, to in rules.items()]},
        "savings_line": round(take_home - bills, 2) if take_home is not None and bills is not None else None,
        "spent": round(sum(p["amount"] for p in purchases), 2),
        "pending": round(sum(p["amount"] for p in purchases if p["pending"]), 2),
        "buckets": buckets,
        "daily": [{"date": f"{month}-{d:02d}", "spent": round(by_day.get(f"{month}-{d:02d}", 0.0), 2)} for d in range(1, day + 1)],
        "categories": sorted(({"id": name, "spent": round(total, 2), "count": count} for name, (total, count) in categories.items()), key=lambda c: -c["spent"]),
        "merchants": sorted(({"name": name, "spent": round(total, 2), "count": count} for name, total, count in merchants.values()), key=lambda m: -m["spent"])[:BUDGET_TOP_MERCHANTS],
        "transactions": purchases,
        "history": [{"month": m, "spent": round(spent_by_month.get(m, 0.0), 2), "days": month_days(m)}
                    for m in (month_before(month, back) for back in range(BUDGET_HISTORY_MONTHS - 1, 0, -1))],
        "alerts_sent": sorted(alerts.get("sent") or {}) if alerts.get("month") == month else [],
    }


def budget_alerts(budget: dict[str, Any]) -> list[dict[str, Any]]:
    """
    Every line the month has crossed that the settings say to tell the phones about, the mildest
    of each `scope` first: the share of the month's limit, what take-home leaves after the bills
    that aren't on a card, and each person's and the family's own limit.
    """
    config, spent = budget["config"], budget["spent"]
    crossed = []
    total = config["limits"]["total"]
    if config["alerts"]["total"] and total:
        crossed += [{"key": f"total_{percent}", "scope": "total", "kind": f"total_{percent}", "spent": spent, "limit": total, "person": None}
                    for percent in BUDGET_TOTAL_PERCENTS if spent >= total * percent / 100]
    line = budget["savings_line"]
    if config["alerts"]["savings"] and line and line > 0 and spent >= line:
        crossed.append({"key": "savings", "scope": "savings", "kind": "savings", "spent": spent, "limit": line, "person": None})
    if config["alerts"]["buckets"]:
        crossed += [{"key": f"{b['id']}_100", "scope": b["id"], "kind": f"{b['kind']}_100", "spent": b["spent"], "limit": b["limit"], "person": b["name"]}
                    for b in budget["buckets"] if b["limit"] and b["spent"] >= b["limit"]]
    return crossed


def budget_alert_text(alert: dict[str, Any], budget: dict[str, Any]) -> tuple[str, str]:
    """A push's title and body in English, for a phone whose app doesn't word them itself from the push's data."""
    spent, limit = f"${alert['spent']:,.0f}", f"${alert['limit']:,.0f}"
    left = budget["days_in_month"] - budget["day"]
    days = "the last day of the month" if left <= 0 else "1 day left this month" if left == 1 else f"{left} days left this month"
    if alert["kind"] == "savings":
        return "Dipping into savings", f"The cards are at {spent} this month, past the {limit} that take-home leaves after the bills."
    if alert["kind"] == "person_100":
        return f"{alert['person']} is over budget", f"{spent} spent of {limit}, with {days}."
    if alert["kind"] == "family_100":
        return "The family card is over budget", f"{spent} spent of {limit}, with {days}."
    if alert["kind"] == "total_100":
        return "Over budget", f"{spent} spent of this month's {limit}, with {days}."
    return "Close to the budget", f"{spent} spent of this month's {limit}, with {days}."


def budget_recipients(now: float) -> list[tuple[str, str]]:
    """
    The phones to tell about the money right now, as (device id, token): those last signed in to
    by someone who may see the finances, and not in their quiet hours. A phone that wants only
    Away alerts still gets these: that choice is about the cameras.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, token, user, role, quiet_start, quiet_end, tz, utc_offset FROM devices WHERE token IS NOT NULL AND user IS NOT NULL"
    ).fetchall())
    phones = []
    for device_id, token, user, role, quiet_start, quiet_end, tz, utc_offset in rows:
        minute = local_minute(now, tz, utc_offset)
        if finance_may_read({"username": user, "role": role}) and not (minute is not None and in_quiet_hours(quiet_start, quiet_end, minute)):
            phones.append((device_id, token))
    return phones


def budget_check(now: float | None = None, push: Callable[[str, str, str, dict[str, str]], str] | None = None) -> list[str]:
    """
    Tells each phone once a month about each line the month has crossed (see `budget_alerts`), and
    answers which were told to anyone just now. Of the lines of one scope only the furthest is
    sent, and the nearer ones count as told with it: a month already over its limit when the
    alerts were switched on says so once, not twice. A phone in its quiet hours isn't counted as
    told, so it hears at the first check after them. `push` stands in for `deliver` in tests.
    """
    if not any(budget_config()["alerts"].values()):
        return []
    # One check at a time: the hourly one and one after a change to the settings would otherwise
    # both find a phone untold and both tell it.
    with _budget_alert_lock:
        return _budget_check(time.time() if now is None else now, push or deliver)


def _budget_check(now: float, push: Callable[[str, str, str, dict[str, str]], str]) -> list[str]:
    budget = budget_month(None, now)
    crossed = budget_alerts(budget)
    if not crossed:
        return []
    kept = state_get(BUDGET_ALERTS_KEY) or {}
    sent: dict[str, list[str]] = kept.get("sent") or {} if kept.get("month") == budget["month"] else {}
    phones = budget_recipients(now)
    told = []
    for scope in dict.fromkeys(alert["scope"] for alert in crossed):
        steps = [alert for alert in crossed if alert["scope"] == scope]
        top = steps[-1]
        title, body = budget_alert_text(top, budget)
        data = {
            "budget": "1", "budget_kind": top["kind"], "month": budget["month"], "spent": f"{top['spent']:.2f}", "limit": f"{top['limit']:.2f}",
            "person": top["person"] or "", "days_left": str(budget["days_in_month"] - budget["day"]), "notif_id": f"budget-{budget['month']}-{top['key']}",
        }
        for device_id, token in phones:
            if device_id in sent.get(top["key"], []):
                continue
            if push(token, title, body, data) != "sent":
                continue
            for step in steps:
                if device_id not in sent.setdefault(step["key"], []):
                    sent[step["key"]].append(device_id)
            if top["key"] not in told:
                told.append(top["key"])
    state_set(BUDGET_ALERTS_KEY, {"month": budget["month"], "sent": sent})
    if told:
        log.info("budget: told the phones about %s", ", ".join(told))
    return told


def budget_check_quietly() -> None:
    try:
        budget_check()
    except Exception:
        log.exception("budget check failed")


def budget_check_in_background() -> None:
    threading.Thread(target=budget_check_quietly, name="budget-check", daemon=True).start()


@app.get("/finance/budget")
def get_budget(request: Request, response: Response, month: str | None = None) -> dict[str, Any]:
    """The month's spending on the cards against the budget (see `budget_month`). Answers without Plaid set up too, to say so."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    return budget_month(month)


@app.put("/finance/budget/config")
def put_budget_config(body: BudgetPatch, request: Request, response: Response) -> dict[str, Any]:
    """Changes the budget's settings: the people, the limits, each card's role, the alerts, and what the app read from the sheet."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    patch = {name: getattr(body, name, None) for name in ("people", "limits", "roles", "card_paid_lines", "alerts", "sheet")}
    budget_config_save(patch, time.time())
    if any(patch[name] is not None for name in ("people", "limits", "roles", "alerts")):
        log.info("budget: %s changed the settings", user)
    # A limit just lowered under what is already spent is a line crossed.
    budget_check_in_background()
    return budget_month()


@app.put("/finance/budget/transactions/{txn_id}")
def put_budget_transaction(txn_id: str, body: BudgetTag, request: Request, response: Response) -> dict[str, Any]:
    """Says whose a purchase is (or, with a null, takes that back), and with `remember` that its merchant's always are."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    if body.bucket is not None and body.bucket not in budget_buckets(budget_config()["people"]):
        raise HTTPException(400, {"error": "bad_bucket", "message": "A purchase is a person's or the family's"})
    now = time.time()

    def tag(c: sqlite3.Connection) -> tuple | None:
        row = c.execute("SELECT date, merchant_key FROM plaid_transactions WHERE txn_id=? AND removed_at IS NULL", (txn_id,)).fetchone()
        if row is None:
            return None
        c.execute("UPDATE plaid_transactions SET tag=?, tag_by=? WHERE txn_id=?", (body.bucket, user if body.bucket else None, txn_id))
        if body.remember and row[1]:
            if body.bucket:
                c.execute("INSERT OR REPLACE INTO budget_rules VALUES (?,?,?,?)", (row[1], body.bucket, user, now))
            else:
                c.execute("DELETE FROM budget_rules WHERE merchant_key=?", (row[1],))
        c.commit()
        return row

    row = with_db(tag)
    if row is None:
        raise HTTPException(404, {"error": "not_found", "message": "That purchase isn't there any more"})
    return budget_month(row[0][:7])


@app.delete("/finance/budget/rules")
def delete_budget_rule(merchant: str, request: Request, response: Response) -> dict[str, Any]:
    """
    Forgets that a merchant's purchases always go one way. The ones tagged by hand stay as they
    are. The merchant is named in the query (`?merchant=`), not the path: its key is whatever
    its name was, and a name can hold a slash.
    """
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    with_db(lambda c: (c.execute("DELETE FROM budget_rules WHERE merchant_key=?", (merchant,)), c.commit()))
    return budget_month()


@app.post("/finance/budget/sync")
def post_budget_sync(request: Request, response: Response) -> dict[str, Any]:
    """Asks the cards what was bought now rather than at the next hour. Answers at once; `syncing` says it is under way."""
    require_bank(request, response)
    with _sync_ask_lock:
        last = max(float((state_get(PLAID_TXN_SYNCED_KEY) or {}).get("at") or 0), _txn_asked["at"])
        if _bank_lock.locked() or time.time() - last < BANK_MIN_SYNC_SECONDS:
            return budget_month()
        _txn_asked["at"] = time.time()
        budget_sync_in_background()
    return {**budget_month(), "syncing": True}


# ---------------------------------------------------------------- Tesla

# Both household cars are Teslas, and Tesla's Fleet API knows where each one is — which settles
# what the camera can only guess at (see "car presence"). The relay asks only at the moments the
# camera thinks a car arrived or left: never on a timer, and never waking a sleeping car. A car that
# is near home confirms an arrival and turns a departure down; one that is well away confirms the
# departure and turns down the arrival (the classifier naming someone else's car); a car asleep,
# unlinked or out of calls leaves the camera to decide as before. A few dozen calls a day at
# $0.002 each, inside the $10 a month Tesla credits a personal account.
#
# Setup (docs/tesla.md): a Tesla developer application whose allowed origin is TESLA_PUBLIC_URL,
# its key pair's public half served at /.well-known/appspecific/com.tesla.3p.public-key.pem (Funnel
# publishes that and /tesla/*), `python relay.py tesla-register` once, then `python relay.py
# tesla-link` for each Tesla account that owns a household car, opened in a browser. Off unless
# TESLA_CLIENT_ID is set (tesla.env on the box).
TESLA_CLIENT_ID = os.environ.get("TESLA_CLIENT_ID", "")
TESLA_CLIENT_SECRET = os.environ.get("TESLA_CLIENT_SECRET", "")
TESLA_PUBLIC_URL = os.environ.get("TESLA_PUBLIC_URL", os.environ.get("GOOGLE_PUBLIC_URL", "")).rstrip("/")
TESLA_API = os.environ.get("TESLA_API", "https://fleet-api.prd.na.vn.cloud.tesla.com").rstrip("/")
TESLA_AUTHORIZE = "https://auth.tesla.com/oauth2/v3/authorize"
TESLA_TOKEN = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"
TESLA_SCOPES = "openid offline_access vehicle_device_data vehicle_location"
# The public half of the application's key pair; the private half stays off the box (the relay only reads).
TESLA_PUBLIC_KEY_FILE = os.environ.get("TESLA_PUBLIC_KEY_FILE", "/data/tesla-public-key.pem")
# Each household car's VIN, by its classifier name: {"andrews_tesla": "5YJ...", "sarahs_car": "7SA..."}.
TESLA_CARS: dict[str, str] = json.loads(os.environ.get("TESLA_CARS") or "{}")
TESLA_CALLS_PER_DAY = int(os.environ.get("TESLA_CALLS_PER_DAY", "200"))
# Within the home radius and this many metres more is home; beyond it and TESLA_AWAY_METRES more is away.
TESLA_HOME_MARGIN_METRES = 50.0
TESLA_AWAY_METRES = 250.0
# One answer per car is reused this long; a location older than TESLA_STALE_SECONDS says nothing.
TESLA_CACHE_SECONDS = 60.0
# How often car presence asks Tesla about each car whatever the camera saw (see `CarPresence.tesla_check`):
# two cars every half hour is ~100 calls a day, ~$6 a month, inside the credit.
TESLA_CHECK_SECONDS = float(os.environ.get("TESLA_CHECK_SECONDS", "1800"))
TESLA_STALE_SECONDS = 600.0
TESLA_LINK_SECONDS = 900
TESLA_CALLS_KEY = "tesla_calls"
_tesla_answers: dict[str, tuple[float, str | None]] = {}


def tesla_on() -> bool:
    return bool(TESLA_CLIENT_ID and TESLA_CLIENT_SECRET and TESLA_PUBLIC_URL)


def tesla_redirect_uri() -> str:
    return f"{TESLA_PUBLIC_URL}/tesla/callback"


def jwt_subject(token: str) -> str:
    """The `sub` of a JWT, unverified — only to tell one linked account from another."""
    import base64

    try:
        part = token.split(".")[1]
        return str(json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))).get("sub") or "")
    except Exception:
        return ""


def tesla_token_request(form: dict[str, str]) -> dict[str, Any]:
    r = requests.post(TESLA_TOKEN, data=form, timeout=15)
    r.raise_for_status()
    return r.json()


def tesla_save_tokens(tokens: dict[str, Any], account: str | None = None) -> str:
    """Keeps a token answer: the account it belongs to (by the access token's subject), its access token and the next refresh token."""
    account = account or jwt_subject(tokens.get("access_token", "")) or hashlib.sha256(tokens.get("refresh_token", "").encode()).hexdigest()[:16]
    expires = time.time() + float(tokens.get("expires_in") or 3600)

    with_db(lambda c: (c.execute("INSERT OR REPLACE INTO tesla_accounts VALUES (?,?,?,?,?)",
                                 (account, tokens.get("refresh_token"), tokens.get("access_token"), expires, time.time())), c.commit()))
    return account


def tesla_access(account: str) -> str | None:
    """A live access token for the account, refreshing it (and keeping the next refresh token) when it is about to lapse."""
    row = with_db(lambda c: c.execute("SELECT refresh, access, expires FROM tesla_accounts WHERE account=?", (account,)).fetchone())
    if not row:
        return None
    refresh, access, expires = row
    if access and float(expires or 0) > time.time() + 60:
        return access
    tokens = tesla_token_request({"grant_type": "refresh_token", "client_id": TESLA_CLIENT_ID, "refresh_token": refresh})
    tesla_save_tokens(tokens, account)
    return tokens.get("access_token")


def tesla_spend() -> bool:
    """Counts one Fleet API call against today's TESLA_CALLS_PER_DAY; False once the day's are spent."""
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    spent = state_get(TESLA_CALLS_KEY) or {}
    count = int(spent.get(today, 0)) if isinstance(spent, dict) else 0
    if count >= TESLA_CALLS_PER_DAY:
        return False
    state_set(TESLA_CALLS_KEY, {today: count + 1})
    return True


def tesla_learn_vehicles(account: str) -> list[str]:
    """Files each vehicle the account can see under it, by VIN; answers the VINs."""
    access = tesla_access(account)
    if not access or not tesla_spend():
        return []
    r = requests.get(f"{TESLA_API}/api/1/vehicles", headers={"Authorization": f"Bearer {access}"}, timeout=15)
    r.raise_for_status()
    vins = []
    for v in r.json().get("response") or []:
        vin = v.get("vin")
        if vin:
            vins.append(vin)
            with_db(lambda c: (c.execute("INSERT OR REPLACE INTO tesla_vehicles VALUES (?,?,?,?)",
                                         (vin, account, v.get("display_name") or "", time.time())), c.commit()))
    return vins


def haversine_metres(a: tuple[float, float], b: tuple[float, float]) -> float:
    import math

    (lat1, lng1), (lat2, lng2) = (tuple(map(math.radians, p)) for p in (a, b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def tesla_location(vin: str) -> dict[str, Any] | None:
    """
    Where the car is, as `{"lat", "lng", "at"}`, or `{"asleep": True}`; None when it can't be asked
    (not linked, out of calls, an error). Never wakes the car: a sleeping one answers 408.
    """
    row = with_db(lambda c: c.execute("SELECT account FROM tesla_vehicles WHERE vin=?", (vin,)).fetchone())
    if not row:
        return None
    access = tesla_access(row[0])
    if not access or not tesla_spend():
        return None
    r = requests.get(f"{TESLA_API}/api/1/vehicles/{vin}/vehicle_data", params={"endpoints": "location_data"},
                     headers={"Authorization": f"Bearer {access}"}, timeout=15)
    if r.status_code == 408:
        return {"asleep": True}
    r.raise_for_status()
    drive = (r.json().get("response") or {}).get("drive_state") or {}
    if drive.get("latitude") is None or drive.get("longitude") is None:
        return None
    stamp = drive.get("gps_as_of") or (float(drive["timestamp"]) / 1000 if drive.get("timestamp") else time.time())
    return {"lat": float(drive["latitude"]), "lng": float(drive["longitude"]), "at": float(stamp)}


def tesla_verdict(name: str, now: float | None = None) -> str | None:
    """
    "home" or "away" by where Tesla says the car is against the household's home (see HOME_KEY);
    "asleep" when the car is (it won't say where, and isn't woken to); None when Tesla can't say
    (not one of TESLA_CARS, not linked, a stale fix, no home set, or somewhere in between). One
    answer per car per TESLA_CACHE_SECONDS.
    """
    vin = TESLA_CARS.get(name)
    home = state_get(HOME_KEY)
    if not (tesla_on() and vin and home):
        return None
    now = time.time() if now is None else now
    cached = _tesla_answers.get(name)
    if cached and now - cached[0] < TESLA_CACHE_SECONDS:
        return cached[1]
    verdict = None
    try:
        where = tesla_location(vin)
        if where and not where.get("asleep") and now - float(where["at"]) <= TESLA_STALE_SECONDS:
            d = haversine_metres((where["lat"], where["lng"]), (float(home["lat"]), float(home["lng"])))
            radius = float(home.get("radius_m") or 150)
            verdict = "home" if d <= radius + TESLA_HOME_MARGIN_METRES else "away" if d > radius + TESLA_AWAY_METRES else None
            log.info("tesla: %s is %.0f m from home -> %s", name, d, verdict)
        elif where and where.get("asleep"):
            verdict = "asleep"
            log.info("tesla: %s is asleep", name)
    except Exception as e:
        log.warning("tesla: asking where %s is failed: %s", name, e)
    _tesla_answers[name] = (now, verdict)
    return verdict


def tesla_link_url(now: float | None = None) -> str:
    """A one-time link that signs a Tesla account in and links it to the relay, good for TESLA_LINK_SECONDS."""
    state = secrets.token_urlsafe(24)
    now = time.time() if now is None else now
    links = {s: t for s, t in (state_get("tesla_links") or {}).items() if t > now}
    links[state] = now + TESLA_LINK_SECONDS
    state_set("tesla_links", links)
    return TESLA_AUTHORIZE + "?" + urlencode({
        "response_type": "code", "client_id": TESLA_CLIENT_ID, "redirect_uri": tesla_redirect_uri(),
        "scope": TESLA_SCOPES, "state": state, "prompt_missing_scopes": "true",
    })


def tesla_page(text: str, status: int = 200) -> Response:
    body = f"<!doctype html><meta name=viewport content='width=device-width'><title>HomeSafe · Tesla</title><p style='font:16px system-ui;margin:2em'>{html.escape(text)}</p>"
    return Response(content=body, status_code=status, media_type="text/html")


@app.get("/tesla/callback")
def tesla_callback(code: str = "", state: str = "", error: str = "") -> Response:
    """Where Tesla sends the browser back: a link minted by `tesla_link_url`, used once, becomes a linked account."""
    if not tesla_on():
        return tesla_page("Tesla isn't set up on this relay.", 404)
    links = state_get("tesla_links") or {}
    expires = links.pop(state, None) if state else None
    state_set("tesla_links", links)
    if error or not code or expires is None or expires < time.time():
        return tesla_page("That link has expired or was already used. Make a new one with `python relay.py tesla-link`.", 400)
    try:
        tokens = tesla_token_request({
            "grant_type": "authorization_code", "client_id": TESLA_CLIENT_ID, "client_secret": TESLA_CLIENT_SECRET,
            "code": code, "audience": TESLA_API, "redirect_uri": tesla_redirect_uri(),
        })
        account = tesla_save_tokens(tokens)
        vins = tesla_learn_vehicles(account)
    except Exception as e:
        log.warning("tesla: linking failed: %s", e)
        return tesla_page("Tesla didn't accept the sign-in. Try a new link.", 502)
    known = [name for name, vin in TESLA_CARS.items() if vin in vins]
    log.info("tesla: account %s linked, %d vehicles (%s)", account[:8], len(vins), ", ".join(known) or "none of TESLA_CARS")
    return tesla_page(f"Linked. HomeSafe can now see {', '.join(display_name(n) for n in known) or 'this account’s cars (none of them is in TESLA_CARS yet)'}.")


@app.get("/.well-known/appspecific/com.tesla.3p.public-key.pem")
def tesla_public_key() -> Response:
    """The application's public key, where Tesla looks for it on the registered domain."""
    try:
        with open(TESLA_PUBLIC_KEY_FILE, encoding="utf-8") as f:
            return Response(content=f.read(), media_type="application/x-pem-file")
    except OSError:
        raise HTTPException(status_code=404)


def tesla_register() -> dict[str, Any]:
    """Registers the application's domain with the Fleet API (once per region), by a partner token."""
    from urllib.parse import urlparse

    partner = tesla_token_request({
        "grant_type": "client_credentials", "client_id": TESLA_CLIENT_ID, "client_secret": TESLA_CLIENT_SECRET,
        "scope": TESLA_SCOPES.replace("offline_access ", ""), "audience": TESLA_API,
    })
    r = requests.post(f"{TESLA_API}/api/1/partner_accounts", json={"domain": urlparse(TESLA_PUBLIC_URL).hostname},
                      headers={"Authorization": f"Bearer {partner['access_token']}"}, timeout=15)
    r.raise_for_status()
    return r.json()


if __name__ == "__main__":
    # `python relay.py tesla-register` / `tesla-link`, run in the relay's container.
    import sys

    CONN = db()
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if not tesla_on():
        sys.exit("Set TESLA_CLIENT_ID, TESLA_CLIENT_SECRET and TESLA_PUBLIC_URL first (tesla.env).")
    if command == "tesla-register":
        print(json.dumps(tesla_register(), indent=2))
    elif command == "tesla-link":
        print(f"Open within {TESLA_LINK_SECONDS // 60} minutes, signed in as the Tesla account that owns the car:\n\n{tesla_link_url()}")
    else:
        sys.exit("usage: python relay.py tesla-register | tesla-link")
