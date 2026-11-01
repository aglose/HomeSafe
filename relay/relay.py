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

import asyncio
import bisect
import calendar
import gzip
import hashlib
import html
import http.client
import io
import json
import logging
import math
import os
import re
import secrets
import socket
import sqlite3
import sys
from statistics import median
import threading
import time
import zipfile
import zlib
from collections import Counter
from datetime import datetime, timedelta, timezone
from typing import Any, Callable
from urllib.parse import parse_qs, quote, urlencode, urlsplit
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

# The columns of `plaid_transactions` that `txn_details` fills, in its order.
TXN_DETAILS = ("original", "channel", "website", "city", "region", "counterparty", "confidence")


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
        " quiet_start INTEGER, quiet_end INTEGER, only_away INTEGER NOT NULL DEFAULT 0, tz TEXT, utc_offset INTEGER,"
        " hardware_id TEXT, emulator INTEGER NOT NULL DEFAULT 0)"
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
    # Every charge of the household's Teslas and its energy hour by hour, and each car's odometer and
    # lifetime energy at each day's end (see "charging").
    conn.execute(
        "CREATE TABLE IF NOT EXISTS charge_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, car TEXT NOT NULL, place TEXT, source TEXT NOT NULL,"
        " started REAL NOT NULL, ended REAL, soc_start REAL, soc_end REAL, energy_start REAL, energy_end REAL, power_kwh REAL, counter_kwh REAL,"
        " kwh_battery REAL, kwh_wall REAL, how TEXT, peak_kw REAL, lat REAL, lng REAL, site TEXT, paid REAL, tesla_id TEXT UNIQUE, typed_cost REAL, note TEXT, updated REAL, idle_fee REAL)"
    )
    if "idle_fee" not in {row[1] for row in conn.execute("PRAGMA table_info(charge_sessions)")}:
        conn.execute("ALTER TABLE charge_sessions ADD COLUMN idle_fee REAL")
    conn.execute("CREATE INDEX IF NOT EXISTS charge_sessions_started ON charge_sessions (started)")
    conn.execute("CREATE TABLE IF NOT EXISTS charge_hours (session_id INTEGER NOT NULL, hour REAL NOT NULL, kwh REAL NOT NULL, PRIMARY KEY (session_id, hour))")
    conn.execute("CREATE TABLE IF NOT EXISTS car_days (car TEXT NOT NULL, day TEXT NOT NULL, odometer REAL, energy_used REAL, PRIMARY KEY (car, day))")
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
    # An account can hold one security several times over: a stock plan reports each grant of the
    # same share as a holding of its own. `lot` tells them apart (their order as Plaid lists them),
    # and `vested` is how much of the holding is the person's already. Before there were lots only
    # the last of several was kept; holdings are rewritten whole at every sync, so the rows of a
    # table from then simply move over as lot 0.
    holdings_table = (
        "CREATE TABLE IF NOT EXISTS plaid_holdings (account_id TEXT, security_id TEXT, lot INTEGER NOT NULL DEFAULT 0, item_id TEXT, ticker TEXT,"
        " name TEXT, kind TEXT, quantity REAL, vested REAL, price REAL, price_as_of TEXT, value REAL, cost_basis REAL, currency TEXT,"
        " PRIMARY KEY (account_id, security_id, lot))"
    )
    before_lots = {row[1] for row in conn.execute("PRAGMA table_info(plaid_holdings)")}
    if before_lots and "lot" not in before_lots:
        conn.execute("ALTER TABLE plaid_holdings RENAME TO plaid_holdings_before_lots")
    conn.execute(holdings_table)
    if before_lots and "lot" not in before_lots:
        kept = "account_id, security_id, item_id, ticker, name, kind, quantity, price, price_as_of, value, cost_basis, currency"
        conn.execute(f"INSERT INTO plaid_holdings ({kept}) SELECT {kept} FROM plaid_holdings_before_lots")
        conn.execute("DROP TABLE plaid_holdings_before_lots")
    # What happened in a stock plan account, as Plaid tells it (see "stock plan"): the shares that
    # vested ("transfer", a positive `quantity`) and those sold. Only the date and the number of
    # shares are kept: Plaid prices past activity at today's price, so its amounts say nothing.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_stock_events (txn_id TEXT PRIMARY KEY, item_id TEXT NOT NULL, account_id TEXT NOT NULL, date TEXT NOT NULL,"
        " kind TEXT, ticker TEXT, quantity REAL, name TEXT)"
    )
    # The name each account's row goes by in the feed sheet, given once and kept while the account
    # is there: the budget sheet's formulas look accounts up by it.
    conn.execute("CREATE TABLE IF NOT EXISTS plaid_feed_keys (account_id TEXT PRIMARY KEY, key TEXT)")
    # Every time the relay wrote balances into the budget sheet's own cells (see "sheet fill"):
    # `changes` is a JSON list of what each cell held before and after, which is what the app
    # shows and what an undo puts back. `undone_at` and `undone_by` once someone undid it.
    conn.execute("CREATE TABLE IF NOT EXISTS sheet_writes (id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL NOT NULL, changes TEXT NOT NULL, undone_at REAL, undone_by TEXT)")
    # When each of the budget sheet's balances was last known to be right (see "typed balances"):
    # someone typed it in the app, or said it still was, or the sheet fill wrote it or found it
    # already there. `cell` is the balance's line id (tab|label|right), `source` "app" or "bank",
    # and `by` who typed it.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS sheet_values (cell TEXT PRIMARY KEY, tab TEXT NOT NULL, label TEXT NOT NULL, right INTEGER NOT NULL,"
        " value REAL, at REAL NOT NULL, by TEXT, source TEXT NOT NULL)"
    )
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
    # Which of the budget sheet's lines a purchase was put in by hand (`line_tag`: the line's name,
    # or "" for none of them), and the merchants someone said always go in one line.
    columns = {row[1] for row in conn.execute("PRAGMA table_info(plaid_transactions)")}
    for column in ("line_tag", "line_tag_by"):
        if column not in columns:
            conn.execute(f"ALTER TABLE plaid_transactions ADD COLUMN {column} TEXT")
    # A note someone wrote on a purchase ("a tire for the Tesla"), and who wrote it.
    for column in ("note", "note_by"):
        if column not in columns:
            conn.execute(f"ALTER TABLE plaid_transactions ADD COLUMN {column} TEXT")
    conn.execute("CREATE TABLE IF NOT EXISTS budget_line_rules (merchant_key TEXT PRIMARY KEY, line TEXT NOT NULL, by TEXT, at REAL)")
    # Which card a purchase was on by its key in the feed (`card`), which outlives the account, and
    # when its institution was unlinked (`archived_at`; see "finance history").
    columns = {row[1] for row in conn.execute("PRAGMA table_info(plaid_transactions)")}
    for column, declaration in (("card", "TEXT"), ("archived_at", "REAL")):
        if column not in columns:
            conn.execute(f"ALTER TABLE plaid_transactions ADD COLUMN {column} {declaration}")
    conn.execute("UPDATE plaid_transactions SET card=(SELECT key FROM plaid_feed_keys k WHERE k.account_id = plaid_transactions.account_id) WHERE card IS NULL")
    conn.execute("CREATE INDEX IF NOT EXISTS plaid_transactions_card ON plaid_transactions (card, date)")
    # What else Plaid says of a transaction, for reading it back rather than for the budget's sums (see `txn_details`).
    for column in TXN_DETAILS:
        if column not in columns:
            conn.execute(f"ALTER TABLE plaid_transactions ADD COLUMN {column} TEXT")
    # What kind of account it was on (`type` of plaid_accounts), which outlives the account: a bank
    # account's transactions must stay out of the budget once its institution has been unlinked.
    if "account_type" not in columns:
        conn.execute("ALTER TABLE plaid_transactions ADD COLUMN account_type TEXT")
    conn.execute("UPDATE plaid_transactions SET account_type=(SELECT type FROM plaid_accounts a WHERE a.account_id = plaid_transactions.account_id) WHERE account_type IS NULL")
    # What the relay would otherwise overwrite (see "finance history"): each account's balances and
    # each investment's holding as they stood on each of the household's days, the budget's plan as
    # it stood in each month, and the budget sheet on each day it differed from the day before
    # (`body`: its tabs as zlib-compressed JSON).
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_balance_days (day TEXT NOT NULL, account_id TEXT NOT NULL, item_id TEXT, key TEXT, institution TEXT, name TEXT, mask TEXT,"
        " type TEXT, subtype TEXT, current REAL, available REAL, credit_limit REAL, currency TEXT, at REAL NOT NULL, PRIMARY KEY (day, account_id))"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS plaid_holding_days (day TEXT NOT NULL, account_id TEXT NOT NULL, security_id TEXT NOT NULL, lot INTEGER NOT NULL, item_id TEXT, key TEXT, ticker TEXT,"
        " name TEXT, kind TEXT, quantity REAL, vested REAL, price REAL, price_as_of TEXT, value REAL, cost_basis REAL, currency TEXT, at REAL NOT NULL,"
        " PRIMARY KEY (day, account_id, security_id, lot))"
    )
    conn.execute("CREATE TABLE IF NOT EXISTS budget_plans (month TEXT PRIMARY KEY, plan TEXT NOT NULL, at REAL NOT NULL)")
    conn.execute("CREATE TABLE IF NOT EXISTS finance_sheet_days (day TEXT PRIMARY KEY, at REAL NOT NULL, digest TEXT NOT NULL, body BLOB NOT NULL)")
    # What Amazon's emails said was ordered (see "Amazon orders"): each email read once
    # (`amazon_emails`), each order with the day it was placed, its total when an email gave one
    # and whether its own email was read (`confirmed`: until then its items are what the shipment
    # emails named), what was in it (`need` "essential" or "discretionary", `need_by` "rule", "model" or
    # "user:<name>"), and each shipment whose email said what it cost.
    conn.execute("CREATE TABLE IF NOT EXISTS amazon_emails (message_id TEXT PRIMARY KEY, mailbox TEXT, at REAL, kind TEXT)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS amazon_orders (order_id TEXT PRIMARY KEY, mailbox TEXT, ordered TEXT NOT NULL, total REAL, updated REAL,"
        " confirmed INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS amazon_items (order_id TEXT NOT NULL, pos INTEGER NOT NULL, title TEXT NOT NULL, quantity INTEGER NOT NULL DEFAULT 1,"
        " price REAL, need TEXT, need_by TEXT, PRIMARY KEY (order_id, pos))"
    )
    conn.execute("CREATE TABLE IF NOT EXISTS amazon_shipments (message_id TEXT PRIMARY KEY, order_id TEXT NOT NULL, shipped TEXT NOT NULL, total REAL NOT NULL)")
    # What the Packages app holds (see "packages"): each shipping email read once, each package with
    # everything known of it, what happened to it in order, each visit the door camera saw with what
    # the model made of it, and whose phone is whose.
    conn.execute("CREATE TABLE IF NOT EXISTS package_emails (message_id TEXT PRIMARY KEY, mailbox TEXT, at REAL, stage TEXT)")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS packages (id TEXT PRIMARY KEY, owner TEXT, source TEXT NOT NULL, carrier TEXT, tracking TEXT, order_id TEXT, shop TEXT, title TEXT, nickname TEXT,"
        " stage TEXT NOT NULL, stage_at REAL, expected_from TEXT, expected_to TEXT, window_start TEXT, window_end TEXT, signature INTEGER NOT NULL DEFAULT 0, problem TEXT, problem_at REAL,"
        " left_note TEXT, total REAL, created REAL NOT NULL, updated REAL NOT NULL, delivered_at REAL, surprise INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,"
        " archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, notes TEXT, return_by TEXT, return_state TEXT, refund_at REAL, porch TEXT, porch_at REAL, porch_by TEXT,"
        " verdict TEXT, verdict_what TEXT, verdict_confidence REAL, verdict_by TEXT, visit_event TEXT, visit_at REAL, tracker_on INTEGER NOT NULL DEFAULT 0, merged_into TEXT)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS packages_tracking ON packages (tracking)")
    conn.execute("CREATE INDEX IF NOT EXISTS packages_order ON packages (order_id)")
    conn.execute("CREATE TABLE IF NOT EXISTS package_events (id INTEGER PRIMARY KEY AUTOINCREMENT, package_id TEXT NOT NULL, at REAL NOT NULL, kind TEXT NOT NULL, detail TEXT, key TEXT NOT NULL, UNIQUE (package_id, key))")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS package_visits (event TEXT PRIMARY KEY, start REAL NOT NULL, end REAL NOT NULL, appeared INTEGER, gone INTEGER, look INTEGER, what TEXT, confidence REAL, door INTEGER, name TEXT, meant INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS package_events_key ON package_events (key)")
    conn.execute("CREATE TABLE IF NOT EXISTS package_phones (device_id TEXT PRIMARY KEY, person TEXT NOT NULL)")
    # The Fitness app's training logs (see "fitness"): a row of a log as the app wrote it, `data` NULL once it is deleted.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS fitness_rows (owner TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, rev INTEGER NOT NULL, data TEXT, updated REAL NOT NULL, PRIMARY KEY (owner, kind, id))"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS fitness_rows_by_rev ON fitness_rows (owner, rev)")
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
    # The door sensors (see "doors"), each by its Zigbee IEEE address: its name on the pairing
    # page, whether it's open (NULL until its first reading) and since when, what it last said of
    # its battery and signal, whether Zigbee2MQTT has it online, when an opening of it should push
    # (`notify`), and when one last did. A sensor taken off the network keeps its row and history
    # with `removed` set. Then every opening and closing, kept DOOR_HISTORY_DAYS.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS doors (id TEXT PRIMARY KEY, name TEXT, model TEXT, open INTEGER, changed REAL, battery INTEGER,"
        " battery_low INTEGER, link INTEGER, online INTEGER, last_seen REAL, notify TEXT NOT NULL DEFAULT 'away', last_push REAL,"
        " removed INTEGER NOT NULL DEFAULT 0)"
    )
    conn.execute("CREATE TABLE IF NOT EXISTS door_events (id INTEGER PRIMARY KEY AUTOINCREMENT, door TEXT NOT NULL, open INTEGER NOT NULL, at REAL NOT NULL, pushed TEXT)")
    # Where each door is on the floor plan (see "floor plan"): the floor's id and the spot on it,
    # in feet from the plan's origin. NULL until someone places it in the app.
    columns = {row[1] for row in conn.execute("PRAGMA table_info(doors)")}
    for column, declaration in (("spot_floor", "TEXT"), ("spot_x", "REAL"), ("spot_y", "REAL")):
        if column not in columns:
            conn.execute(f"ALTER TABLE doors ADD COLUMN {column} {declaration}")
    conn.execute("CREATE INDEX IF NOT EXISTS door_events_at ON door_events (at)")
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
    # Then which phone it runs on (`hardware_id`) and whether that is an emulator, which decide
    # whether it can be one of the tracked phones (see "away mode").
    columns = {row[1] for row in conn.execute("PRAGMA table_info(devices)")}
    for column, declaration in (
        ("quiet_start", "INTEGER"),
        ("quiet_end", "INTEGER"),
        ("only_away", "INTEGER NOT NULL DEFAULT 0"),
        ("tz", "TEXT"),
        ("utc_offset", "INTEGER"),
        ("user", "TEXT"),
        ("role", "TEXT"),
        ("hardware_id", "TEXT"),
        ("emulator", "INTEGER NOT NULL DEFAULT 0"),
    ):
        if column not in columns:
            conn.execute(f"ALTER TABLE devices ADD COLUMN {column} {declaration}")
    conn.commit()
    return conn


DB_LOCK = threading.Lock()
CONN = None


# A call holding DB_LOCK longer than this is logged with its caller: every other thread (the door
# sensors' among them) waits on it meanwhile.
DB_SLOW_SECONDS = 1.0


def with_db(fn):
    with DB_LOCK:
        started = time.monotonic()
        try:
            return fn(CONN)
        finally:
            held = time.monotonic() - started
            if held > DB_SLOW_SECONDS:
                caller = sys._getframe(1).f_code
                log.warning("db: held %.1f s by %s (relay.py:%d)", held, caller.co_name, caller.co_firstlineno)


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
    `away` escalates it: the app's Away channel on Android, time-sensitive on iOS. Neither ever
    makes a sound (no `aps.sound`): HomeSafe lights the screen but keeps the phone quiet.

    A push that is one more alert in a visit already on the phone (see `Visits`) carries the
    visit's `notif_id` and `silent: "1"`: Android replaces that visit's notification without
    alerting again, and iOS collapses it onto the same banner (`apns-collapse-id`), passively.
    """
    headers, aps = apns_parts(title, body, data, away)
    return {
        "message": {
            "token": token,
            "data": {**data, "title": title, "body": body},
            "android": {"priority": "high"},
            "apns": {"headers": headers, "payload": {"aps": aps}},
        }
    }


def apns_parts(title: str, body: str, data: dict[str, str], away: bool) -> tuple[dict[str, str], dict[str, Any]]:
    """What an iPhone is sent, by FCM or straight through APNs (see "APNs"): the APNs headers and the `aps` dictionary."""
    silent = data.get("silent") == "1"
    aps: dict[str, Any] = {"alert": {"title": title, "body": body}, "thread-id": data.get("camera", "")}
    if away:
        aps["interruption-level"] = "time-sensitive"
    elif silent:
        aps["interruption-level"] = "passive"
    headers = {"apns-priority": "10"}
    collapse_id = data.get("notif_id") or data.get("review_id")
    if collapse_id:
        headers["apns-collapse-id"] = collapse_id[:64]
    return headers, aps


def send_push(token: str, title: str, body: str, data: dict[str, str], away: bool = False) -> tuple[bool, str]:
    """One FCM message (see `push_message` for its shape), or for an iPhone's own APNs token, one APNs push (see "APNs")."""
    if apns_token(token):
        return apns_send(token, title, body, data, away=away)
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


# ---------------------------------------------------------------- APNs

# The iPhone app registers Apple's own device token (64 hex digits) rather than an FCM one, and the
# relay pushes to it straight through Apple's push service, signed in with the household's APNs
# key: the iOS app needs no Firebase. Setup (docs/notifications.md, "iPhone"): an APNs key from
# the Apple developer account, its .p8 file on the box at APNS_KEY_FILE and its key id as
# APNS_KEY_ID (apns.env). Off without them: an iPhone still registers its token, but is told the
# relay can't push to it (see `register`), so it keeps its own in-app alerts.
APNS_KEY_FILE = os.environ.get("APNS_KEY_FILE", "/data/apns-key.p8")
APNS_KEY_ID = os.environ.get("APNS_KEY_ID", "")
APNS_TEAM_ID = os.environ.get("APNS_TEAM_ID", "KSK3H9YSXD")
# The app's bundle id (Config.xcconfig's PRODUCT_BUNDLE_IDENTIFIER with no TEAM_ID suffix).
APNS_TOPIC = os.environ.get("APNS_TOPIC", "com.meticulouscreations.homesafe.HomeSafe")
APNS_HOSTS = {"production": "https://api.push.apple.com", "sandbox": "https://api.sandbox.push.apple.com"}
# Apple wants a provider token under an hour old, and made anew no more than every 20 minutes.
APNS_TOKEN_SECONDS = 45 * 60
# Which of APNS_HOSTS took each token last, by its first 16 digits (see `apns_send`).
APNS_ENV_KEY = "apns_env"
_apns: dict[str, Any] = {"jwt": None, "at": 0.0, "client": None}
_apns_lock = threading.Lock()


def apns_token(token: str | None) -> bool:
    """Whether a push token is an iPhone's own APNs token (FCM's are longer, and not all hex)."""
    return bool(token) and re.fullmatch(r"[0-9a-f]{64}", token) is not None


def apns_on() -> bool:
    return bool(APNS_KEY_ID) and os.path.exists(APNS_KEY_FILE)


def can_push(token: str | None) -> bool:
    """Whether the relay can push to a token a phone registered: FCM's always, an iPhone's once APNs is set up."""
    return bool(token) and (apns_on() if apns_token(token) else True)


def b64url(raw: bytes) -> str:
    import base64

    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def apns_sign(now: float) -> str:
    """A provider token: the ES256 JWT APNs takes, signed with the APNs key."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, utils

    with open(APNS_KEY_FILE, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    signing_input = (b64url(json.dumps({"alg": "ES256", "kid": APNS_KEY_ID}).encode()) + "."
                     + b64url(json.dumps({"iss": APNS_TEAM_ID, "iat": int(now)}).encode()))
    r, s = utils.decode_dss_signature(key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256())))
    return signing_input + "." + b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def apns_jwt(now: float | None = None) -> str:
    """The provider token, made anew every APNS_TOKEN_SECONDS."""
    now = time.time() if now is None else now
    with _apns_lock:
        if not _apns["jwt"] or now - _apns["at"] >= APNS_TOKEN_SECONDS:
            _apns["jwt"], _apns["at"] = apns_sign(now), now
        return _apns["jwt"]


def apns_post(url: str, headers: dict[str, str], payload: dict[str, Any]) -> tuple[int, str]:
    """One POST to APNs, which only speaks HTTP/2: Apple's status and its `reason`."""
    import httpx

    with _apns_lock:
        if _apns["client"] is None:
            _apns["client"] = httpx.Client(http2=True, timeout=15)
        client = _apns["client"]
    r = client.post(url, headers=headers, json=payload)
    if r.status_code == 200:
        return 200, ""
    try:
        return r.status_code, str(r.json().get("reason") or "")
    except Exception:
        return r.status_code, r.text[:200]


def apns_send(token: str, title: str, body: str, data: dict[str, str], away: bool = False) -> tuple[bool, str]:
    """
    One push straight to an iPhone. The app's data travels beside `aps`, as FCM's does in `data`.
    Apple runs two services, production (App Store, TestFlight and ad hoc builds) and sandbox
    (builds Xcode installs), and a token belongs to one: the one that last took it is tried first,
    the other when Apple says BadDeviceToken. Apple saying the app is gone (410) answers
    "UNREGISTERED", as FCM's does, so `deliver` forgets the token.
    """
    if not apns_on():
        return False, "apns not set up (APNS_KEY_ID, APNS_KEY_FILE)"
    headers, aps = apns_parts(title, body, data, away)
    payload = {**{k: v for k, v in data.items() if k != "aps"}, "aps": aps}
    envs = state_get(APNS_ENV_KEY) or {}
    first = envs.get(token[:16], "production")
    status, reason = 0, ""
    for env in (first, "sandbox" if first == "production" else "production"):
        # A key that won't load, or Apple out of reach, is this phone's push failing, not the
        # rest of the broadcast's: the phones after it in `broadcast` are still pushed to.
        try:
            headers["authorization"] = f"bearer {apns_jwt()}"
            headers.update({"apns-topic": APNS_TOPIC, "apns-push-type": "alert"})
            status, reason = apns_post(f"{APNS_HOSTS[env]}/3/device/{token}", headers, payload)
        except Exception as e:
            return False, f"apns: {type(e).__name__}: {e}"[:300]
        if status == 200:
            if envs.get(token[:16]) != env:
                envs[token[:16]] = env
                state_set(APNS_ENV_KEY, envs)
            return True, ""
        if status == 410:
            return False, f"410 UNREGISTERED {reason}".strip()
        if reason != "BadDeviceToken":
            break
    return False, f"{status} {reason}".strip()


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

# Away mode follows exactly two phones: the household's Android phone and its iPhone, each a
# release build on real hardware. Everything else — a debug build (an emulator, the .debug app
# beside the real one, an Xcode Debug install), a release build on an emulator or simulator, an
# app too old to say which phone it runs on — still registers for push, but isn't tracked: it
# isn't listed in the presence snapshot, its away switch is ignored, and it can never hold away
# mode open. This replaced the presence authority (one phone deciding alone) on 2026-10-22, which
# had itself replaced "every release phone votes" after stale installs kept the house occupied.
#
# A phone is known by its `hardware_id`, which outlives a reinstall: Android's ANDROID_ID (per
# signing key, so the release app's differs from the debug app's), and on iOS a UUID the app keeps
# in the Keychain, which survives deleting the app. A reinstall registers a new device_id with the
# same hardware_id; `register` folds the old row into the new one, so the list never grows ghosts.
TRACKED_PLATFORMS = ("android", "ios")
TRACKED_BUILD = "release"
# A tracked phone nothing has been heard from in this long gives its place up to a release phone of
# the same platform that has been (a new phone; the old one sits in a drawer). While both are in
# use the first keeps it, so two phones can't take turns: removing the old one in Settings hands
# its place over at once.
TRACKED_STALE_SECONDS = 7 * 24 * 3600.0
# An install that isn't tracked and hasn't been heard from in this long is forgotten. One still in
# use comes back the next time it connects and re-registers.
UNTRACKED_FORGET_SECONDS = 14 * 24 * 3600.0

# The household's home, as `{"lat", "lng", "radius_m", "updated", "by"}`, or absent. Set once from
# whichever phone is standing in it; both phones draw their geofence around it.
HOME_KEY = "home"

# Where the authority used to be kept; cleared on startup.
OLD_AUTHORITY_KEY = "presence_device"


def tracked_key(platform: str) -> str:
    return f"tracked_{platform}"


def not_tracked_reason(platform: str | None, build: str | None, emulator: Any, hardware_id: str | None) -> str | None:
    """Why an install can't be one of the tracked phones ("platform", "debug", "emulator", "old_app"), or None when it can."""
    if (platform or "").lower() not in TRACKED_PLATFORMS:
        return "platform"
    if (build or "").lower() != TRACKED_BUILD:
        return "debug"
    if emulator:
        return "emulator"
    if not hardware_id:
        return "old_app"
    return None


def assign_tracked_phones(now: float | None = None) -> dict[str, str]:
    """
    Settles which install is each platform's tracked phone and answers {platform: device_id}. A
    phone keeps its place while it can be tracked, unless it has gone stale and another one of its
    platform hasn't; an empty place goes to the eligible install heard from most recently. A phone
    that takes a place starts at home: whatever its row said before was never counted.
    """
    now = time.time() if now is None else now

    def settle(c: sqlite3.Connection) -> tuple[dict[str, str], list[tuple[str, str | None, str | None]]]:
        rows = c.execute("SELECT device_id, platform, build, emulator, hardware_id, last_seen FROM devices").fetchall()
        eligible = {d: ((p or "").lower(), ls or 0.0) for d, p, b, e, h, ls in rows if not_tracked_reason(p, b, e, h) is None}
        tracked, changes = {}, []
        for platform in TRACKED_PLATFORMS:
            row = c.execute("SELECT value FROM state WHERE key=?", (tracked_key(platform),)).fetchone()
            holder = json.loads(row[0]) if row else None
            candidates = [d for _, d in sorted(((ls, d) for d, (p, ls) in eligible.items() if p == platform), reverse=True)]
            if holder in eligible and eligible[holder][0] == platform:
                fresh = [d for d in candidates if d != holder and now - eligible[d][1] <= TRACKED_STALE_SECONDS]
                chosen = fresh[0] if fresh and now - eligible[holder][1] > TRACKED_STALE_SECONDS else holder
            else:
                chosen = candidates[0] if candidates else None
            if chosen != holder:
                if chosen is None:
                    c.execute("DELETE FROM state WHERE key=?", (tracked_key(platform),))
                else:
                    c.execute("INSERT OR REPLACE INTO state VALUES (?,?)", (tracked_key(platform), json.dumps(chosen)))
                    c.execute(
                        "UPDATE devices SET away=0, away_updated=?, away_pending_since=NULL, away_pending_dwell=NULL WHERE device_id=?",
                        (now, chosen),
                    )
                changes.append((platform, holder, chosen))
            if chosen is not None:
                tracked[platform] = chosen
        if changes:
            c.commit()
        return tracked, changes

    tracked, changes = with_db(settle)
    for platform, before, after in changes:
        log.info("presence: the tracked %s phone is now %s (was %s)", platform, after, before)
    return tracked


def forget_untracked(now: float | None = None) -> int:
    """Deletes the installs that aren't tracked and haven't been heard from in UNTRACKED_FORGET_SECONDS; answers how many."""
    now = time.time() if now is None else now
    keep = list(assign_tracked_phones(now).values())
    marks = ",".join("?" * len(keep))
    query = "DELETE FROM devices WHERE COALESCE(last_seen, created, 0) < ?" + (f" AND device_id NOT IN ({marks})" if keep else "")
    gone = with_db(lambda c: (c.execute(query, (now - UNTRACKED_FORGET_SECONDS, *keep)).rowcount, c.commit())[0])
    if gone:
        log.info("presence: forgot %d install(s) not heard from in %d days", gone, UNTRACKED_FORGET_SECONDS // 86400)
    return gone


def presence_snapshot(this_device: str | None = None) -> dict[str, Any]:
    """
    Who says they're home: the tracked phones alone, Android first, and `everyone_away` when there
    is at least one and all of them are away. A phone that has *left* but is still inside its
    dwell (see `promote_pending`) shows as `pending_away` and is not away yet. `this_device`
    matches a device_id or, for old apps, a token; when it names an install that isn't listed,
    `not_tracked` says why — its `not_tracked_reason`, "taken" (another phone of its platform is
    tracked) or "unregistered". `id` is what `DELETE /devices/{id}` takes.
    """
    tracked = assign_tracked_phones()
    ids = [tracked[p] for p in TRACKED_PLATFORMS if p in tracked]
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, token, name, platform, away, away_updated, build, away_pending_since, last_seen FROM devices"
        f" WHERE device_id IN ({','.join('?' * len(ids))})",
        ids,
    ).fetchall()) if ids else []
    by_id = {row[0]: row for row in rows}
    devices = [
        {
            "id": d,
            "name": n,
            "platform": p,
            "away": bool(a),
            "away_updated": u,
            "this_device": this_device is not None and this_device in (d, t),
            "build": b,
            # Every listed phone counts; kept for the apps that still read it.
            "counts": True,
            "pending_away": ps is not None,
            "last_seen": ls,
        }
        for d, t, n, p, a, u, b, ps, ls in (by_id[i] for i in ids if i in by_id)
    ]
    not_tracked = None
    if this_device is not None and not any(d["this_device"] for d in devices):
        row = with_db(lambda c: c.execute(
            "SELECT platform, build, emulator, hardware_id FROM devices WHERE device_id=? OR token=?", (this_device, this_device)
        ).fetchone())
        not_tracked = "unregistered" if row is None else (not_tracked_reason(*row) or "taken")
    return {
        "devices": devices,
        "everyone_away": bool(devices) and all(d["away"] for d in devices),
        "home": state_get(HOME_KEY),
        "not_tracked": not_tracked,
    }


def away_since() -> float | None:
    """When the last tracked phone left, or None while one of them is home (or none is tracked)."""
    ids = list(assign_tracked_phones().values())
    if not ids:
        return None
    rows = with_db(lambda c: c.execute(
        f"SELECT away, away_updated FROM devices WHERE device_id IN ({','.join('?' * len(ids))})", ids
    ).fetchall())
    if not rows or not all(a for a, _ in rows):
        return None
    return float(max((u or 0.0) for _, u in rows))


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
    title = what
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

# What deserves a sound depends on whether anyone is home (see "away mode"). Measured
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
#   Not when any answer came in that time, and after "asleep" only every TESLA_ASLEEP_CHECK_SECONDS.
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
            found[name] = {"here": here, "since": since, "camera": camera, "known": False}
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
        verdict = tesla_verdict(name, now, "arrival")
        if verdict == "away":
            log.info("car presence: %s's arrival %s turned down, Tesla has the car away", name, row["event_id"])
            car["not_arrivals"], car["dirty"] = (turned_down + [row["event_id"]])[-20:], True
            return None
        if verdict != "home" and not name_settled(row, now):
            return None
        return self.change(name, car, float(row["start"]), row)

    def departure(self, name: str, car: dict[str, Any], rows: list[dict[str, Any]], now: float) -> dict[str, Any] | None:
        # A car found to be still here was still here for every departure before that one too.
        lefts = [r for r in rows if r["movement"] == "left" and r["final"] and float(r["start"]) > float(car.get("stayed") or 0)]
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
        verdict = tesla_verdict(name, now, "departure")
        if verdict in ("home", "asleep") or (verdict is None and spot_still_taken(gone["camera"], name)):
            reason = {"home": "Tesla has the car at home", "asleep": "Tesla has the car asleep, so it never drove off"}
            log.info("car presence: %s's departure %s turned down, %s", name, gone["event_id"],
                     reason.get(verdict, "a car still stands in its spot"))
            car["stayed"], car["dirty"] = float(gone["start"]), True
            return None
        return self.change(name, car, gone_at, gone)

    def tesla_check(self, name: str, car: dict[str, Any], now: float) -> dict[str, Any] | None:
        """
        Every TESLA_CHECK_SECONDS, asks Tesla where a car the camera hasn't settled is: one parked at
        the curb comes and goes out of the camera's car zones, so only Tesla sees it. Recorded home
        and Tesla has it well away, it left; recorded away and Tesla has it near home, it is home.
        Asleep or unsure, nothing changes. An answer already in hand that recent (a departure's, or
        parking's), or one saying the car is asleep from the last TESLA_ASLEEP_CHECK_SECONDS, is
        read as it is rather than asked again. A car streaming to the relay is read again each time
        it says where it is, so it is seen to leave or come home within CAR_PRESENCE_EVERY_SECONDS.
        """
        if name not in TESLA_CARS:
            return None
        # A car streaming its location (see "Tesla telemetry") is read again as soon as it says, without asking.
        due = now - float(car.get("checked") or 0) >= TESLA_CHECK_SECONDS
        if not due and tesla_streamed_at(name) <= float(car.get("checked") or 0):
            return None
        car["checked"], car["dirty"] = now, True
        verdict = tesla_verdict(name, now, "check", ask=due and tesla_due(name, now, TESLA_CHECK_SECONDS))
        if verdict == ("away" if car["here"] else "home"):
            log.info("car presence: %s %s, by Tesla's check", name, "left" if car["here"] else "is home")
            return self.change(name, car, now, {"camera": car.get("camera") or "", "event_id": "", "movement": "parked"}, via="tesla")
        return None

    def change(self, name: str, car: dict[str, Any], at: float, row: dict[str, Any], via: str = "camera") -> dict[str, Any]:
        here = not car["here"]
        was = at - float(car["since"]) if car.get("known") else None
        self.cars[name] = {"here": here, "since": at, "camera": row["camera"], "known": True, "checked": car.get("checked")}
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
                    data["away"] = "1"
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
    # "release" or "debug". Only a release build can be a tracked phone (see "away mode").
    build: str = "unknown"
    # The phone this install runs on, kept across reinstalls: Android's ANDROID_ID, or a UUID the
    # iOS app keeps in the Keychain. Absent from apps before 2026-10-22, which aren't tracked.
    hardware_id: str | None = None
    # A release build on an emulator or simulator is still not a phone anyone carries.
    emulator: bool = False
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
    state_set(OLD_AUTHORITY_KEY, None)
    forget_untracked()
    load_car_profiles()
    threading.Thread(target=poll_forever, name="poller", daemon=True).start()
    threading.Thread(target=car_check_forever, name="car-check", daemon=True).start()
    threading.Thread(target=boot_report, name="boot-report", daemon=True).start()
    threading.Thread(target=uptime_forever, name="uptime", daemon=True).start()
    threading.Thread(target=bank_forever, name="bank-sync", daemon=True).start()
    threading.Thread(target=timeline_forever, name="timeline", daemon=True).start()
    threading.Thread(target=doors_forever, name="doors", daemon=True).start()
    threading.Thread(target=parking_forever, name="parking", daemon=True).start()
    threading.Thread(target=tesla_stream_forever, name="tesla-stream", daemon=True).start()
    threading.Thread(target=packages_forever, name="packages", daemon=True).start()
    threading.Thread(target=charging_forever, name="charging", daemon=True).start()
    if NEST_PRIMER:
        threading.Thread(target=nest_primer_forever, name="nest-primer", daemon=True).start()
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
        if device.hardware_id:
            # A reinstall on the same phone: its old installs go, and the newest of them hands this
            # one its presence (when this one has none yet) and its place as a tracked phone.
            old = c.execute(
                "SELECT device_id, away, away_updated, away_pending_since, away_pending_dwell FROM devices"
                " WHERE hardware_id=? AND platform=? AND device_id!=? ORDER BY COALESCE(last_seen, 0) DESC",
                (device.hardware_id, device.platform, device_id),
            ).fetchall()
            if old:
                if c.execute("SELECT 1 FROM devices WHERE device_id=?", (device_id,)).fetchone() is None:
                    c.execute(
                        "INSERT INTO devices (device_id, platform, name, created, last_seen, away, away_updated, away_pending_since, away_pending_dwell)"
                        " VALUES (?,?,?,?,?,?,?,?,?)",
                        (device_id, device.platform, device.name, now, now, *old[0][1:]),
                    )
                for platform in TRACKED_PLATFORMS:
                    held = c.execute("SELECT value FROM state WHERE key=?", (tracked_key(platform),)).fetchone()
                    if held and json.loads(held[0]) in [row[0] for row in old]:
                        c.execute("UPDATE state SET value=? WHERE key=?", (json.dumps(device_id), tracked_key(platform)))
                c.executemany("DELETE FROM devices WHERE device_id=?", [(row[0],) for row in old])
                log.info("device %s replaces %d earlier install(s) on the same phone", device_id, len(old))
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
            " quiet_start, quiet_end, only_away, tz, utc_offset, hardware_id, emulator)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            " ON CONFLICT(device_id) DO UPDATE SET token=excluded.token, platform=excluded.platform, name=excluded.name,"
            " last_seen=excluded.last_seen, quiet_familiar=excluded.quiet_familiar, build=excluded.build, secret=excluded.secret,"
            " quiet_start=excluded.quiet_start, quiet_end=excluded.quiet_end, only_away=excluded.only_away,"
            " tz=excluded.tz, utc_offset=excluded.utc_offset, hardware_id=excluded.hardware_id, emulator=excluded.emulator",
            (
                device_id, device.token, device.platform, device.name, now, now, int(device.quiet_familiar), device.build, secret,
                device.quiet_start, device.quiet_end, int(device.only_away), device.tz, device.utc_offset,
                device.hardware_id, int(device.emulator),
            ),
        )
        c.commit()
        return secret

    secret = with_db(upsert)
    # Whose phone it is, when the registration says: one made with the install's own secret and
    # no session (a push token rotating in the background) leaves who it was as it stood.
    if profile and profile.get("username"):
        with_db(lambda c: (c.execute("UPDATE devices SET user=?, role=? WHERE device_id=?", (str(profile["username"]), profile.get("role"), device_id)), c.commit()))
    forget_untracked(now)
    tracked = device_id in assign_tracked_phones(now).values()
    log.info(
        "device registered by %s: %s (%s %s%s, push=%s, strangers only=%s, quiet=%s-%s %s, only away=%s, tracked=%s)",
        user, device.name or "unnamed", device.platform, device.build, " emulator" if device.emulator else "", device.token is not None,
        device.quiet_familiar, device.quiet_start, device.quiet_end, device.tz, device.only_away,
        tracked or not_tracked_reason(device.platform, device.build, device.emulator, device.hardware_id) or "taken",
    )
    # Whether the relay can push to the token it was given: an iPhone's needs APNs set up, and one
    # told no keeps its own in-app alerts (DeviceRegistrar). Older apps read only the secret.
    return {"ok": True, "device_id": device_id, "secret": secret, "push": can_push(device.token)}


@app.delete("/devices/{ident}")
def unregister(ident: str, request: Request) -> dict[str, Any]:
    """
    Forgets an install. Its own secret may remove itself; a signed-in user's cookie may remove any
    device — how Settings clears out old installs. One that is still installed comes back the next
    time it connects and re-registers.
    """
    user = authenticate(request, ident)
    with_db(lambda c: (c.execute("DELETE FROM devices WHERE device_id=? OR token=?", (ident, ident)), c.commit()))
    # A tracked phone removed hands its place to the next release phone of its platform, if any.
    log.info("device %s removed by %s; tracked now %s", ident, user, assign_tracked_phones())
    return {"ok": True}


@app.put("/devices/{ident}/presence")
def set_presence(ident: str, presence: Presence, request: Request) -> dict[str, Any]:
    """
    This phone's owner has left (or come back). `ident` is the device_id (or, for old apps, the
    token); the row is created if it doesn't exist yet so a presence change can't race
    registration. Away with a dwell only *arms* the change — see `promote_pending`. Home is
    always immediate and cancels any armed departure. Only a tracked phone's change is kept: any
    other install is answered with the snapshot, which says why it isn't tracked.
    """
    user = authenticate(request, ident)
    now = time.time()
    row = find_device(ident)
    if row is None or row[0] not in assign_tracked_phones(now).values():
        if row is not None:
            with_db(lambda c: (c.execute("UPDATE devices SET last_seen=? WHERE device_id=?", (now, row[0])), c.commit()))
        snapshot = presence_snapshot(ident)
        log.info("presence by %s (%s) ignored: not a tracked phone (%s)", user, presence.source, snapshot["not_tracked"])
        return snapshot
    device_id = row[0]

    def apply(c: sqlite3.Connection) -> str:
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
    """Every install the relay knows, tracked or not: what Settings doesn't list, for looking into from the box."""
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, platform, name, created, last_seen, away, build, token IS NOT NULL, away_pending_since IS NOT NULL,"
        " emulator, hardware_id FROM devices"
    ).fetchall())
    tracked = set(assign_tracked_phones().values())
    return [
        {
            "id": d, "platform": p, "name": n, "created": cr, "last_seen": ls, "away": bool(a), "build": b, "push": bool(push),
            "pending_away": bool(pend), "emulator": bool(emu), "tracked": d in tracked,
            "not_tracked": None if d in tracked else (not_tracked_reason(p, b, emu, hw) or "taken"),
        }
        for d, p, n, cr, ls, a, b, push, pend, emu, hw in rows
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


# ---------------------------------------------------------------- doors

# The door sensors (docs/doors.md): Zigbee contact sensors paired to the stick on this box, which
# Zigbee2MQTT drives and publishes to the Mosquitto broker beside it. A sensor's readings arrive
# as one JSON message on `zigbee2mqtt/<its name>` each time it says anything; `contact` is true
# while the magnet is against it, so false is open. The retained device list
# (`zigbee2mqtt/bridge/devices`) says which devices are contact sensors and ties each name to its
# IEEE address, which is what a door is known by here: renaming it on the pairing page keeps its
# history and its setting.
#
# Whether an opening pushes is each door's `notify`. "away", the default, pushes only while
# everyone's away, as an away alert (the loud channel, every phone). "always" also pushes while
# someone is home, as an ordinary push that each phone's quiet hours and "only when away" hold
# back. "never" keeps the history and nothing else. Only openings push, and a door opened again
# within DOOR_QUIET_SECONDS of its last push updates that notification without a sound.

MQTT_HOST = os.environ.get("MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
Z2M_TOPIC = os.environ.get("Z2M_TOPIC", "zigbee2mqtt").strip("/")
DOOR_NOTIFY_MODES = ("away", "always", "never")
DOOR_QUIET_SECONDS = 120
DOOR_HISTORY_DAYS = 90
# Whether the relay is connected to the broker, and whether Zigbee2MQTT says it's running, for the
# app to tell "no sensors yet" from "can't hear them".
DOORS_LINK = {"broker": False, "bridge": False}
# How long one GET /doors may be held open waiting for a change (see DoorsChanges).
DOORS_WAIT_MAX = 30.0
# The relay's connection to the broker, once doors_forever has made it, for asking Zigbee2MQTT
# things (see z2m_ask).
DOORS_MQTT: dict[str, Any] = {"client": None}
# Zigbee's longest permit-join window, which the app's "Pair a sensor" opens.
DOOR_PAIRING_SECONDS = 254
# How long a request to Zigbee2MQTT may take to be answered.
Z2M_ANSWER_SECONDS = 10.0


class DoorsChanges:
    """
    The doors' version, which moves on every change the app would draw, and a way for a request to
    wait for the next one. The app holds GET /doors?since=<version>&wait=<seconds> open; the relay
    answers it the moment a door changes (an MQTT message on the doors thread, an edit from the
    app), or when the wait runs out with the same version. So a door shows within a network round
    trip of Zigbee2MQTT hearing it, rather than at the app's next poll.

    The version starts at the boot's clock in milliseconds, so one from before a restart never
    matches and is answered at once. It moves after the change is committed, and a snapshot reads
    it before the rows: a snapshot can carry an older version than its rows (the app asks again
    and is answered at once) but never a newer one.
    """

    def __init__(self) -> None:
        self.version = int(time.time() * 1000)
        self._lock = threading.Lock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._event: asyncio.Event | None = None

    def bump(self) -> None:
        """A change was committed: moves the version and wakes every request waiting on the last one. From any thread."""
        with self._lock:
            self.version += 1
            loop, event = self._loop, self._event
        if loop is not None and event is not None:
            try:
                loop.call_soon_threadsafe(event.set)
            except RuntimeError:
                pass  # the server is shutting down

    async def wait(self, since: int, timeout: float) -> None:
        """Returns once the version is no longer [since], or after [timeout] seconds."""
        loop = asyncio.get_running_loop()
        with self._lock:
            if self.version != since:
                return
            # One event per version: a set one belongs to a change already answered.
            if self._loop is not loop or self._event is None or self._event.is_set():
                self._loop, self._event = loop, asyncio.Event()
            event = self._event
        try:
            await asyncio.wait_for(event.wait(), timeout)
        except TimeoutError:
            pass


DOORS_CHANGES = DoorsChanges()


class DoorPairing:
    """
    Whether Zigbee2MQTT is letting sensors join (its permit-join window, opened from the app's
    Doors page or the pairing page), until when, and what joined while it was open: each device
    by IEEE address with its name and how far it got, "joined", "interviewing", "paired" or
    "failed". A sensor that dropped off and rejoins under its old name only joins; a new one is
    interviewed. Opening the window again starts the list afresh.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.open = False
        self.until: float | None = None
        self.joined: dict[str, dict[str, Any]] = {}

    def window(self, is_open: bool, until: float | None) -> bool:
        """Zigbee2MQTT's window as it now stands; answers whether that changed what the app draws."""
        until = until if is_open else None
        with self._lock:
            if is_open and not self.open:
                self.joined = {}
            changed = is_open != self.open or (until is None) != (self.until is None) or (until is not None and abs(until - self.until) > 2)
            self.open, self.until = is_open, until
            return changed

    def info(self, info: Any, now: float) -> bool:
        """
        Takes Zigbee2MQTT's `bridge/info`, which it publishes again whenever the window opens or
        closes: `permit_join`, and when it ends as `permit_join_end` (2.x, epoch millis) or
        `permit_join_timeout` (1.x, seconds left).
        """
        if not isinstance(info, dict) or "permit_join" not in info:
            return False
        end, left = info.get("permit_join_end"), info.get("permit_join_timeout")
        until = end / 1000 if isinstance(end, (int, float)) else now + left if isinstance(left, (int, float)) and left > 0 else None
        return self.window(bool(info["permit_join"]), until)

    def event(self, event: Any, now: float) -> bool:
        """Takes one of Zigbee2MQTT's `bridge/event`s: a device joining, or how its interview went. Answers whether it was one to show."""
        if not isinstance(event, dict) or not isinstance(event.get("data"), dict):
            return False
        data = event["data"]
        ieee = data.get("ieee_address")
        if not ieee:
            return False
        kind = event.get("type")
        if kind == "device_joined":
            status = "joined"
        elif kind == "device_interview":
            status = {"started": "interviewing", "successful": "paired", "failed": "failed"}.get(data.get("status"))
        else:
            status = None
        with self._lock:
            # Only while the window is open, or to finish an interview that began in it: a sensor
            # rejoining through another router on its own isn't something the app asked for.
            if status is None or (not self.open and ieee not in self.joined):
                return False
            self.joined[ieee] = {"id": ieee, "name": data.get("friendly_name") or ieee, "status": status, "at": now}
            return True

    def json(self) -> dict[str, Any]:
        with self._lock:
            return {"open": self.open, "until": self.until, "joined": [dict(j) for j in self.joined.values()]}


DOOR_PAIRING = DoorPairing()


class Z2mRequests:
    """
    Asking Zigbee2MQTT to do something and waiting for its answer: a request goes to
    `bridge/request/<what>` with a `transaction` id, and the answer comes back on
    `bridge/response/<what>` carrying it, `status` "ok" or "error" (with `error`).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._waiting: dict[str, tuple[threading.Event, dict[str, Any]]] = {}

    def ask(self, publish: Callable[[str, str], Any], what: str, payload: dict[str, Any], timeout: float) -> dict[str, Any] | None:
        """Sends one request through [publish] and answers Zigbee2MQTT's response, or None if it didn't answer within [timeout] seconds."""
        transaction = secrets.token_hex(8)
        done, answer = threading.Event(), {}
        with self._lock:
            self._waiting[transaction] = (done, answer)
        try:
            publish(f"{Z2M_TOPIC}/bridge/request/{what}", json.dumps({**payload, "transaction": transaction}))
            return answer if done.wait(timeout) else None
        finally:
            with self._lock:
                self._waiting.pop(transaction, None)

    def answer(self, response: Any) -> None:
        if not isinstance(response, dict):
            return
        with self._lock:
            waiting = self._waiting.get(str(response.get("transaction")))
        if waiting is not None:
            waiting[1].update(response)
            waiting[0].set()


Z2M_REQUESTS = Z2mRequests()


def z2m_ask(what: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Asks Zigbee2MQTT to do [what] and answers its response's `data`; an HTTP error the app can show when it can't, won't or doesn't answer."""
    client = DOORS_MQTT["client"]
    if client is None or not DOORS_LINK["broker"] or not DOORS_LINK["bridge"]:
        raise HTTPException(status_code=503, detail="The relay can't reach Zigbee2MQTT")
    answer = Z2M_REQUESTS.ask(lambda topic, body: client.publish(topic, body), what, payload, Z2M_ANSWER_SECONDS)
    if answer is None:
        raise HTTPException(status_code=504, detail="Zigbee2MQTT didn't answer")
    if answer.get("status") != "ok":
        raise HTTPException(status_code=502, detail=f"Zigbee2MQTT: {answer.get('error') or 'refused'}")
    return answer.get("data") if isinstance(answer.get("data"), dict) else {}


def has_contact(exposes: Any) -> bool:
    """Whether a device's `exposes` (Zigbee2MQTT's description of what it reports, nested in `features`) includes `contact`."""
    for expose in exposes or []:
        if not isinstance(expose, dict):
            continue
        if expose.get("property") == "contact" or has_contact(expose.get("features")):
            return True
    return False


def contact_sensors(devices: Any) -> dict[str, dict[str, Any]]:
    """The contact sensors in Zigbee2MQTT's device list, by IEEE address: each one's name and model."""
    found = {}
    for device in devices if isinstance(devices, list) else []:
        if not isinstance(device, dict) or device.get("type") == "Coordinator" or not device.get("ieee_address"):
            continue
        definition = device.get("definition") or {}
        if has_contact(definition.get("exposes")):
            model = " ".join(part for part in (definition.get("vendor"), definition.get("model")) if part)
            found[device["ieee_address"]] = {"name": device.get("friendly_name") or device["ieee_address"], "model": model or None}
    return found


def door_message(topic: str, payload: bytes) -> tuple[str, str | None, Any] | None:
    """
    What one broker message is: ("devices", None, the device list), ("bridge", None, "online" or
    "offline"), ("info", None, Zigbee2MQTT's info), ("event", None, a device joining or being
    interviewed), ("response", what, the answer to a request), ("availability", name, "online" or
    "offline"), ("state", name, readings), or None for anything else. Zigbee2MQTT 2 sends availability as `{"state": "online"}`, 1.x as plain text.
    """
    prefix = Z2M_TOPIC + "/"
    if not topic.startswith(prefix):
        return None
    rest = topic[len(prefix):]
    text = payload.decode("utf-8", "replace").strip() if isinstance(payload, (bytes, bytearray)) else str(payload)
    try:
        body: Any = json.loads(text) if text else None
    except ValueError:
        body = text
    if rest == "bridge/devices":
        return ("devices", None, body)
    if rest == "bridge/state":
        return ("bridge", None, body.get("state") if isinstance(body, dict) else body)
    if rest == "bridge/info":
        return ("info", None, body)
    if rest == "bridge/event":
        return ("event", None, body)
    if rest.startswith("bridge/response/"):
        return ("response", rest[len("bridge/response/"):], body)
    if rest.startswith("bridge/"):
        return None
    if rest.endswith("/availability"):
        return ("availability", rest[: -len("/availability")], body.get("state") if isinstance(body, dict) else body)
    if isinstance(body, dict):
        return ("state", rest, body)
    return None


def door_sync(sensors: dict[str, dict[str, Any]]) -> None:
    """Brings the doors in line with Zigbee2MQTT's device list: new sensors join, renamed ones take their new name, removed ones are hidden."""
    def sync(c: sqlite3.Connection) -> None:
        for door_id, sensor in sensors.items():
            c.execute(
                "INSERT INTO doors (id, name, model) VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, model=excluded.model, removed=0",
                (door_id, sensor["name"], sensor["model"]),
            )
        marks = ",".join("?" * len(sensors))
        c.execute(f"UPDATE doors SET removed=1 WHERE id NOT IN ({marks})" if sensors else "UPDATE doors SET removed=1", tuple(sensors))
        c.commit()

    with_db(sync)


def door_by_name(name: str) -> tuple | None:
    return with_db(lambda c: c.execute("SELECT id, name, open, notify, last_push FROM doors WHERE name=? AND removed=0", (name,)).fetchone())


def door_shown(name: str) -> tuple | None:
    """How the app draws the door called [name]: whether it's open, and whether it's online."""
    return with_db(lambda c: c.execute("SELECT open, online FROM doors WHERE name=? AND removed=0", (name,)).fetchone())


def door_state(name: str, readings: dict[str, Any], now: float) -> dict[str, Any] | None:
    """
    Takes one message from a sensor: keeps its battery and signal, and when `contact` says the
    door changed, records the change and answers it as {"id", "name", "open", "notify",
    "last_push", "event"}. A sensor's first reading is where it starts, not a change; so is a
    reading that only repeats the state it's in (Zigbee2MQTT resends the last one on restart).
    """
    row = door_by_name(name)
    if row is None:
        return None
    door_id, door_name, was_open, notify, last_push = row
    battery = readings.get("battery")
    link = readings.get("linkquality")
    battery_low = readings.get("battery_low")
    # Only a reading with a signal strength came over the air. The one Zigbee2MQTT resends for each
    # sensor on restart has none, and right after the restart's "offline" it would bring a silent
    # sensor back online with its last state (an "open" door, as the front door's was 2026-10-20).
    heard = isinstance(link, (int, float))
    with_db(lambda c: (c.execute(
        "UPDATE doors SET battery=COALESCE(?, battery), link=COALESCE(?, link), battery_low=COALESCE(?, battery_low),"
        " last_seen=CASE WHEN ? THEN ? ELSE last_seen END, online=CASE WHEN ? THEN 1 ELSE online END WHERE id=?",
        (
            int(battery) if isinstance(battery, (int, float)) else None,
            int(link) if heard else None,
            int(battery_low) if isinstance(battery_low, bool) else None,
            heard,
            now,
            heard,
            door_id,
        ),
    ), c.commit()))
    contact = readings.get("contact")
    if not isinstance(contact, bool):
        return None
    is_open = not contact
    if was_open is not None and bool(was_open) == is_open:
        return None

    def record(c: sqlite3.Connection) -> int | None:
        c.execute("UPDATE doors SET open=?, changed=? WHERE id=?", (int(is_open), now, door_id))
        event = None
        if was_open is not None:
            event = c.execute("INSERT INTO door_events (door, open, at) VALUES (?,?,?)", (door_id, int(is_open), now)).lastrowid
            c.execute("DELETE FROM door_events WHERE at < ?", (now - DOOR_HISTORY_DAYS * 86400,))
        c.commit()
        return event

    event = with_db(record)
    if event is None:
        return None
    return {"id": door_id, "name": door_name, "open": is_open, "notify": notify, "last_push": last_push, "event": event}


def door_route(notify: str, away: bool) -> str | None:
    """How an opening goes out: "away" (loud, every phone), "home" (an ordinary push), or None for not at all."""
    if notify == "never":
        return None
    if away:
        return "away"
    return "home" if notify == "always" else None


def door_text(name: str, away: bool) -> tuple[str, str]:
    """A push's title and body in English, for a phone whose app doesn't word them itself from the push's data."""
    return (f"{name} opened", "Door sensor · nobody home") if away else (f"{name} opened", "Door sensor")


def door_announce(change: dict[str, Any], now: float, away: bool, push: Callable[..., dict[str, int]] | None = None) -> str | None:
    """Pushes one door opening, if its door's `notify` says to now; answers the route it reached a phone by. `push` stands in for `broadcast` in tests."""
    if not change["open"]:
        return None
    route = door_route(change["notify"], away)
    if route is None:
        return None
    quiet = change["last_push"] is not None and now - change["last_push"] < DOOR_QUIET_SECONDS
    title, body = door_text(change["name"], route == "away")
    data = {"door": change["id"], "door_name": change["name"], "door_event": "opened", "at": f"{now:.0f}", "notif_id": f"door-{change['id']}"}
    if route == "away":
        data["away"] = "1"
    if quiet:
        data["silent"] = "1"
    result = (push or broadcast)(title, body, data, away=route == "away")
    log.info("door %s opened -> %s push%s: %s", change["name"], route, " (quiet)" if quiet else "", result)
    # Only a push some phone got counts: one that quiet hours or "only when away" kept from every
    # phone isn't a notification the next opening could quietly update, nor one the history shows.
    if not result.get("sent"):
        return None
    with_db(lambda c: (
        c.execute("UPDATE doors SET last_push=? WHERE id=?", (now, change["id"])),
        c.execute("UPDATE door_events SET pushed=? WHERE id=?", (route, change["event"])),
        c.commit(),
    ))
    return route


def door_receive(topic: str, payload: bytes, now: float) -> None:
    """One message from the broker."""
    message = door_message(topic, payload)
    if message is None:
        return
    kind, name, body = message
    if kind == "devices":
        sensors = contact_sensors(body)
        door_sync(sensors)
        DOORS_CHANGES.bump()
        log.info("doors: %d sensor(s) on the Zigbee network: %s", len(sensors), ", ".join(sorted(s["name"] for s in sensors.values())) or "none")
    elif kind == "bridge":
        DOORS_LINK["bridge"] = body == "online"
        if not DOORS_LINK["bridge"]:
            DOOR_PAIRING.window(False, None)
        DOORS_CHANGES.bump()
    elif kind == "info":
        if DOOR_PAIRING.info(body, now):
            DOORS_CHANGES.bump()
    elif kind == "event":
        if DOOR_PAIRING.event(body, now):
            DOORS_CHANGES.bump()
    elif kind == "response":
        Z2M_REQUESTS.answer(body)
    elif kind == "availability":
        with_db(lambda c: (c.execute("UPDATE doors SET online=? WHERE name=? AND removed=0", (int(body == "online"), name)), c.commit()))
        DOORS_CHANGES.bump()
    elif kind == "state":
        before = door_shown(name)
        change = door_state(name, body, now)
        late = time.time() - now
        if late > 1:
            log.warning("doors: a message from %s took %.1f s to record", name, late)
        # The app's waiting requests first: the push can take a second, and a phone with the app
        # open shouldn't wait on Firebase. Whatever moves how the door is drawn wakes them (an
        # opening or closing, a sensor's first reading, one back online); a reading that changed
        # neither (the sensor says the same thing three times) wakes nobody, and its battery shows
        # at the next wait's end.
        if change is not None or door_shown(name) != before:
            DOORS_CHANGES.bump()
        # A push that reached a phone marks the history entry as notified: wake them again for that.
        if change is not None and door_announce(change, now, away_since() is not None) is not None:
            DOORS_CHANGES.bump()


def doors_forever() -> None:
    """Listens to Zigbee2MQTT through the broker for as long as the relay runs, reconnecting whenever it drops."""
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        log.warning("doors: paho-mqtt isn't installed, so door sensors are off")
        return

    def on_connect(client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            log.warning("doors: the broker refused the connection: %s", reason_code)
            return
        DOORS_LINK["broker"] = True
        DOORS_CHANGES.bump()
        client.subscribe(f"{Z2M_TOPIC}/#")
        log.info("doors: listening to %s on %s:%s", Z2M_TOPIC, MQTT_HOST, MQTT_PORT)

    def on_disconnect(client, userdata, flags, reason_code, properties):
        DOORS_LINK["broker"] = DOORS_LINK["bridge"] = False
        DOOR_PAIRING.window(False, None)
        DOORS_CHANGES.bump()

    def on_message(client, userdata, message):
        try:
            door_receive(message.topic, message.payload, time.time())
        except Exception:
            log.exception("doors: couldn't take a message on %s", message.topic)

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="homesafe-relay")
    client.on_connect, client.on_disconnect, client.on_message = on_connect, on_disconnect, on_message
    DOORS_MQTT["client"] = client
    client.reconnect_delay_set(min_delay=1, max_delay=60)
    warned = False
    while True:
        try:
            client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
            warned = False
            client.loop_forever(retry_first_connection=True)
        except Exception as e:
            DOORS_LINK["broker"] = DOORS_LINK["bridge"] = False
            # Said once, not every half minute: until the broker is set up there is none to reach.
            if not warned:
                log.warning("doors: no broker at %s:%s (%s); trying every 30 s", MQTT_HOST, MQTT_PORT, e)
                warned = True
            time.sleep(30)


def door_json(row: tuple) -> dict[str, Any]:
    door_id, name, model, is_open, changed, battery, battery_low, link, online, last_seen, notify, spot_floor, spot_x, spot_y = row
    return {
        "id": door_id, "name": name, "model": model, "open": None if is_open is None else bool(is_open), "changed": changed,
        "battery": battery, "battery_low": None if battery_low is None else bool(battery_low), "link": link,
        "online": None if online is None else bool(online), "last_seen": last_seen, "notify": notify,
        "spot": {"floor": spot_floor, "x": spot_x, "y": spot_y} if spot_floor is not None else None,
    }


def doors_snapshot(now: float, days: float = 7) -> dict[str, Any]:
    """Every door sensor on the network and how it stands, and its openings and closings over the last [days], newest first."""
    version = DOORS_CHANGES.version  # before the rows: see DoorsChanges
    rows = with_db(lambda c: c.execute(
        "SELECT id, name, model, open, changed, battery, battery_low, link, online, last_seen, notify, spot_floor, spot_x, spot_y FROM doors"
        " WHERE removed=0 ORDER BY name COLLATE NOCASE"
    ).fetchall())
    events = with_db(lambda c: c.execute(
        "SELECT e.door, e.open, e.at, e.pushed FROM door_events e JOIN doors d ON d.id=e.door WHERE d.removed=0 AND e.at >= ? ORDER BY e.at DESC LIMIT 500",
        (now - max(0.0, min(days, DOOR_HISTORY_DAYS)) * 86400,),
    ).fetchall())
    return {
        "doors": [door_json(row) for row in rows],
        "events": [{"door": d, "open": bool(o), "at": at, "pushed": p} for d, o, at, p in events],
        "broker": DOORS_LINK["broker"],
        "bridge": DOORS_LINK["bridge"],
        "away": away_since() is not None,
        "pairing": DOOR_PAIRING.json(),
        "version": version,
    }


class DoorPatch(BaseModel):
    # When an opening pushes: "away", "always" or "never" (see "doors").
    notify: str


@app.get("/doors")
async def get_doors(request: Request, device: str | None = None, days: float = 7, since: int | None = None, wait: float = 0) -> dict[str, Any]:
    """
    The door sensors and their recent history (see "doors"). A signed-in user or a device. With
    `since` (the `version` of the snapshot the app has) and `wait`, held open up to `wait` seconds
    until the doors change (see DoorsChanges). Async so a held request costs no worker thread; the
    database work runs on one.
    """
    await asyncio.to_thread(authenticate, request, device)
    if since is not None and wait > 0:
        await DOORS_CHANGES.wait(since, min(wait, DOORS_WAIT_MAX))
    return await asyncio.to_thread(doors_snapshot, time.time(), days)


@app.put("/doors/{door_id}")
def set_door(door_id: str, patch: DoorPatch, request: Request) -> dict[str, Any]:
    """Sets when one door's openings push. A signed-in user."""
    user = require_frigate_session(request)
    if patch.notify not in DOOR_NOTIFY_MODES:
        raise HTTPException(status_code=400, detail=f"notify must be one of {', '.join(DOOR_NOTIFY_MODES)}")
    changed = with_db(lambda c: (c.execute("UPDATE doors SET notify=? WHERE id=? AND removed=0", (patch.notify, door_id)).rowcount, c.commit())[0])
    if not changed:
        raise HTTPException(status_code=404, detail="No such door")
    DOORS_CHANGES.bump()
    log.info("doors: %s set %s to notify %s", user, door_id, patch.notify)
    return doors_snapshot(time.time())


class DoorPairingRequest(BaseModel):
    # How long to let sensors join, up to DOOR_PAIRING_SECONDS; 0 stops pairing.
    seconds: int = DOOR_PAIRING_SECONDS


@app.post("/doors/pairing")
def set_door_pairing(body: DoorPairingRequest, request: Request) -> dict[str, Any]:
    """
    Opens Zigbee2MQTT's permit-join window for [seconds], so a new sensor can pair or one that
    dropped off can rejoin, or with 0 closes it. A signed-in user. Answers the doors with
    `pairing` as Zigbee2MQTT now has it, and what joins shows there as it happens.
    """
    user = require_frigate_session(request)
    seconds = max(0, min(int(body.seconds), DOOR_PAIRING_SECONDS))
    z2m_ask("permit_join", {"time": seconds})
    now = time.time()
    # Zigbee2MQTT's bridge/info says the same (with its own clock's end) a moment later.
    if DOOR_PAIRING.window(seconds > 0, now + seconds if seconds else None):
        DOORS_CHANGES.bump()
    log.info("doors: %s %s", user, f"let sensors pair for {seconds} s" if seconds else "stopped pairing")
    return doors_snapshot(now)


class DoorRename(BaseModel):
    name: str


# The longest name the app may give a door.
DOOR_NAME_MAX = 64


@app.put("/doors/{door_id}/name")
def rename_door(door_id: str, body: DoorRename, request: Request) -> dict[str, Any]:
    """
    Renames one door's sensor in Zigbee2MQTT, as renaming it on the pairing page does: a sensor
    just paired is called by its address until it's named. A signed-in user. The door keeps its
    history, its setting and its spot.
    """
    user = require_frigate_session(request)
    name = " ".join(body.name.split())
    # A name is the sensor's MQTT topic: no wildcards, and no topic levels of its own.
    if not name or len(name) > DOOR_NAME_MAX or any(c in name for c in "/+#"):
        raise HTTPException(status_code=400, detail=f"A name is 1-{DOOR_NAME_MAX} characters, without / + or #")
    row = with_db(lambda c: c.execute("SELECT name FROM doors WHERE id=? AND removed=0", (door_id,)).fetchone())
    if row is None:
        raise HTTPException(status_code=404, detail="No such door")
    if row[0] != name:
        z2m_ask("device/rename", {"from": door_id, "to": name, "homeassistant_rename": False})
        # Zigbee2MQTT publishes its device list again with the new name, which door_sync takes the
        # same way; this has it in the answer already.
        with_db(lambda c: (c.execute("UPDATE doors SET name=? WHERE id=?", (name, door_id)), c.commit()))
        DOORS_CHANGES.bump()
        log.info("doors: %s renamed %s from %s to %s", user, door_id, row[0], name)
    return doors_snapshot(time.time())


# ---------------------------------------------------------------- floor plan

# The house's floor plan, for the app's Doors page to draw the doors on (docs/doors.md, "The floor
# plan"). It lives only on the box, as FLOORPLAN_FILE (relay/data, gitignored): the repository is
# public, and a plan with the door sensors on it says too much about the house. The shape:
#   {"floors": [{"id": "first", "name": "First floor", "outline": [[x, y], ...],
#                "rooms": [{"name": "Kitchen", "x": 5.5, "y": 18.5}], "stairs": [[x, y]],
#                "walls": [[x1, y1, x2, y2]], "doors": [{"name": "Back door", "from": [x, y], "to": [x, y],
#                "swing": 1, "sensor": "0x…"}], "stairs": [{"x": 14.5, "y": 24, "direction": "down",
#                "facing": "n", "flight": "basement"}]}, ...], "street": "Central Ave"}
# Every floor is drawn in the same frame, with the street side (`street`) at the bottom: stairs
# that join two floors share a `flight` id, go up on one and down on the other, and sit in the
# same spot on both.
# in feet, y downward, each floor's outline one closed polygon. A door is a doorway along a wall,
# hinged at `from`, swinging to the left of from→to (1) or the right (-1); one linked to a sensor
# puts that sensor's spot at the doorway's middle when the plan is saved (`link_door_sensors`). Each door's spot on it is kept with
# the door (`spot_floor`, `spot_x`, `spot_y`), placed from the app.

FLOORPLAN_FILE = os.environ.get("FLOORPLAN_FILE", os.path.join(os.path.dirname(DB_PATH), "floorplan.json"))


def floorplan() -> dict[str, Any] | None:
    """The floor plan as the file has it, or None when there is none (or it can't be read: logged)."""
    try:
        with open(FLOORPLAN_FILE) as f:
            plan = json.load(f)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        log.warning("floor plan: can't read %s: %s", FLOORPLAN_FILE, e)
        return None
    floors = plan.get("floors") if isinstance(plan, dict) else None
    if not isinstance(floors, list) or not all(isinstance(f, dict) and f.get("id") and isinstance(f.get("outline"), list) for f in floors):
        log.warning("floor plan: %s has no usable floors", FLOORPLAN_FILE)
        return None
    return plan


class DoorSpot(BaseModel):
    # Which floor of the plan, and where on it, in the plan's feet.
    floor: str
    x: float
    y: float


@app.get("/floorplan")
def get_floorplan(request: Request, device: str | None = None) -> dict[str, Any]:
    """The floor plan (see "floor plan"); 404 while the box has none. A signed-in user or a device."""
    authenticate(request, device)
    plan = floorplan()
    if plan is None:
        raise HTTPException(status_code=404, detail="No floor plan on this box")
    return plan


# Each plan the editor replaces is kept here, newest FLOORPLAN_HISTORY of them, so a bad edit can
# be put back by copying one over floorplan.json.
FLOORPLAN_HISTORY = 30


def clean_floorplan(plan: Any) -> dict[str, Any]:
    """
    [plan] as the editor sent it, checked and tidied into the file's shape (numbers rounded to a
    hundredth of a foot); raises ValueError saying what's wrong with it.
    """
    def number(value: Any) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError("Every position must be a number of feet")
        return round(float(value), 2)

    def point(value: Any) -> list[float]:
        if not isinstance(value, list) or len(value) != 2:
            raise ValueError("A point must be [x, y]")
        return [number(value[0]), number(value[1])]

    floors = plan.get("floors") if isinstance(plan, dict) else None
    if not isinstance(floors, list) or not floors:
        raise ValueError("The plan needs at least one floor")
    seen: set[str] = set()
    cleaned = []
    for floor in floors:
        if not isinstance(floor, dict):
            raise ValueError("A floor must be an object")
        floor_id = floor.get("id")
        if not isinstance(floor_id, str) or not floor_id.strip() or floor_id in seen:
            raise ValueError("Every floor needs its own id")
        seen.add(floor_id)
        outline = [point(p) for p in floor.get("outline") or []]
        if len(outline) < 3:
            raise ValueError(f"{floor_id}: the outline needs at least three corners")
        rooms = []
        for room in floor.get("rooms") or []:
            if not isinstance(room, dict) or not isinstance(room.get("name"), str) or not room["name"].strip():
                raise ValueError(f"{floor_id}: every room needs a name")
            rooms.append({"name": room["name"].strip()[:60], "x": number(room.get("x")), "y": number(room.get("y"))})
        walls = []
        for wall in floor.get("walls") or []:
            if not isinstance(wall, list) or len(wall) != 4:
                raise ValueError(f"{floor_id}: a wall must be [x1, y1, x2, y2]")
            walls.append([number(v) for v in wall])
        doors = []
        for door in floor.get("doors") or []:
            if not isinstance(door, dict):
                raise ValueError(f"{floor_id}: a door must be an object")
            hinge, latch = point(door.get("from")), point(door.get("to"))
            if math.hypot(latch[0] - hinge[0], latch[1] - hinge[1]) < 0.5:
                raise ValueError(f"{floor_id}: a door must be at least half a foot wide")
            cleaned_door: dict[str, Any] = {"from": hinge, "to": latch, "swing": -1 if door.get("swing") == -1 else 1}
            if isinstance(door.get("name"), str) and door["name"].strip():
                cleaned_door["name"] = door["name"].strip()[:60]
            if isinstance(door.get("sensor"), str) and door["sensor"].strip():
                cleaned_door["sensor"] = door["sensor"].strip()
            doors.append(cleaned_door)
        stairs = []
        for stair in floor.get("stairs") or []:
            # Plans from before stairs had a direction list each as a bare [x, y].
            if isinstance(stair, list):
                stair = {"x": stair[0] if stair else None, "y": stair[1] if len(stair) > 1 else None}
            if not isinstance(stair, dict):
                raise ValueError(f"{floor_id}: stairs must be an object")
            cleaned_stair: dict[str, Any] = {"x": number(stair.get("x")), "y": number(stair.get("y"))}
            if stair.get("direction") in ("up", "down"):
                cleaned_stair["direction"] = stair["direction"]
            cleaned_stair["facing"] = stair.get("facing") if stair.get("facing") in ("n", "e", "s", "w") else "n"
            if isinstance(stair.get("flight"), str) and stair["flight"].strip():
                cleaned_stair["flight"] = stair["flight"].strip()[:40]
            stairs.append(cleaned_stair)
        name = floor.get("name")
        cleaned.append({
            "id": floor_id,
            "name": name.strip()[:60] if isinstance(name, str) and name.strip() else floor_id,
            "outline": outline,
            "rooms": rooms,
            "stairs": stairs,
            "walls": walls,
            "doors": doors,
        })
    linked = [d["sensor"] for f in cleaned for d in f["doors"] if "sensor" in d]
    if len(linked) != len(set(linked)):
        raise ValueError("A door sensor can be linked to one door only")
    # A flight of stairs joins two floors: its two ends share an id, one going up and one down.
    ends: dict[str, list[tuple[str, dict[str, Any]]]] = {}
    for f in cleaned:
        for stair in f["stairs"]:
            if "flight" in stair:
                ends.setdefault(stair["flight"], []).append((f["id"], stair))
    for flight, pair in ends.items():
        if len(pair) > 2:
            raise ValueError("A flight of stairs joins two floors, no more")
        if len(pair) == 2:
            (floor_a, a), (floor_b, b) = pair
            if floor_a == floor_b:
                raise ValueError("Linked stairs must be on two different floors")
            if {a.get("direction"), b.get("direction")} != {"up", "down"}:
                raise ValueError("Linked stairs go up on one floor and down on the other")
    plan_out: dict[str, Any] = {"floors": cleaned}
    street = plan.get("street")
    if isinstance(street, str) and street.strip():
        plan_out["street"] = street.strip()[:60]
    return plan_out


def link_door_sensors(plan: dict[str, Any]) -> list[str]:
    """
    Puts each door sensor the plan links to a doorway at that doorway's middle, on its floor (the
    spot the app draws its dot at), and answers the ids it moved. A link to a sensor this relay
    doesn't know is left in the plan for when it's paired, and moves nothing.
    """
    moved = []
    for floor in plan["floors"]:
        for door in floor.get("doors") or []:
            sensor = door.get("sensor")
            if not sensor:
                continue
            x = round((door["from"][0] + door["to"][0]) / 2, 2)
            y = round((door["from"][1] + door["to"][1]) / 2, 2)
            changed = with_db(lambda c: (c.execute(
                "UPDATE doors SET spot_floor=?, spot_x=?, spot_y=? WHERE id=? AND removed=0", (floor["id"], x, y, sensor)
            ).rowcount, c.commit())[0])
            if changed:
                moved.append(sensor)
    return moved


def save_floorplan(plan: dict[str, Any], now: float) -> None:
    """Writes [plan] as the floor plan, keeping the one it replaces in floorplan-history/."""
    folder = os.path.dirname(FLOORPLAN_FILE) or "."
    if os.path.exists(FLOORPLAN_FILE):
        history = os.path.join(folder, "floorplan-history")
        os.makedirs(history, exist_ok=True)
        stamp = datetime.fromtimestamp(now, timezone.utc).strftime("%Y%m%d-%H%M%S")
        with open(FLOORPLAN_FILE, "rb") as old, open(os.path.join(history, f"floorplan-{stamp}.json"), "wb") as kept:
            kept.write(old.read())
        for stale in sorted(os.listdir(history))[:-FLOORPLAN_HISTORY]:
            os.remove(os.path.join(history, stale))
    temporary = FLOORPLAN_FILE + ".tmp"
    with open(temporary, "w") as f:
        json.dump(plan, f, indent=1)
    os.replace(temporary, FLOORPLAN_FILE)


@app.put("/floorplan")
def put_floorplan(plan: dict[str, Any], request: Request) -> dict[str, Any]:
    """Replaces the floor plan with the editor's (see "floor plan editor"). A signed-in user."""
    user = require_frigate_session(request)
    try:
        cleaned = clean_floorplan(plan)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    save_floorplan(cleaned, time.time())
    moved = link_door_sensors(cleaned)
    if moved:
        DOORS_CHANGES.bump()
    log.info(
        "floor plan: %s saved %s%s", user,
        ", ".join(f"{f['id']} ({len(f['walls'])} walls, {len(f['doors'])} doors, {len(f['rooms'])} rooms)" for f in cleaned["floors"]),
        f"; sensors placed at their doors: {', '.join(moved)}" if moved else "",
    )
    return cleaned


# The floor plan editor: a page served from the box, at http://<box>:8787/floorplan/edit on the home
# network or the tailnet, to draw the plan's inside walls and room names over the outline and Save
# it straight into FLOORPLAN_FILE. Like /status, the page holds nothing: the plan rides on the
# Frigate session cookie. Kept as its own file beside this one (floorplan_editor.html).
try:
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "floorplan_editor.html")) as _editor:
        FLOORPLAN_EDITOR_PAGE = _editor.read()
except OSError:
    FLOORPLAN_EDITOR_PAGE = "<!doctype html><p>The floor plan editor isn't installed with this relay.</p>"


@app.get("/floorplan/edit")
def floorplan_editor() -> Response:
    """The floor plan editor (see "floor plan editor"). The page holds nothing: its plan rides on the Frigate session cookie."""
    return Response(content=FLOORPLAN_EDITOR_PAGE, media_type="text/html", headers={"Cache-Control": "no-store"})


@app.put("/doors/{door_id}/spot")
def set_door_spot(door_id: str, spot: DoorSpot, request: Request) -> dict[str, Any]:
    """Places one door on the floor plan. A signed-in user."""
    user = require_frigate_session(request)
    plan = floorplan()
    floors = {f["id"] for f in (plan or {}).get("floors", [])}
    if spot.floor not in floors:
        raise HTTPException(status_code=400, detail="No such floor on the plan")
    if not (math.isfinite(spot.x) and math.isfinite(spot.y)):
        raise HTTPException(status_code=400, detail="The spot must be a number of feet")
    changed = with_db(lambda c: (c.execute(
        "UPDATE doors SET spot_floor=?, spot_x=?, spot_y=? WHERE id=? AND removed=0", (spot.floor, spot.x, spot.y, door_id)
    ).rowcount, c.commit())[0])
    if not changed:
        raise HTTPException(status_code=404, detail="No such door")
    DOORS_CHANGES.bump()
    log.info("doors: %s placed %s on %s at (%.1f, %.1f)", user, door_id, spot.floor, spot.x, spot.y)
    return doors_snapshot(time.time())


@app.delete("/doors/{door_id}/spot")
def clear_door_spot(door_id: str, request: Request) -> dict[str, Any]:
    """Takes one door off the floor plan. A signed-in user."""
    user = require_frigate_session(request)
    changed = with_db(lambda c: (c.execute(
        "UPDATE doors SET spot_floor=NULL, spot_x=NULL, spot_y=NULL WHERE id=? AND removed=0", (door_id,)
    ).rowcount, c.commit())[0])
    if not changed:
        raise HTTPException(status_code=404, detail="No such door")
    DOORS_CHANGES.bump()
    log.info("doors: %s took %s off the floor plan", user, door_id)
    return doors_snapshot(time.time())
# ---------------------------------------------------------------- Nest primer

# Holds every Google Nest stream in go2rtc open, and is the viewer that starts each one.
#
# go2rtc (1.9.10, and as of 1.9.14) binds the viewer that starts a Nest session to the first H.264
# payload type in Google's answer while Google sends on another, so that viewer never gets a frame;
# every viewer joining after it does (measured 2026-10-17: the first of two `stream.mp4` reads got
# 0 bytes in 15 s, the second 1.7 MB). Left alone, whoever came first lost: the app's first join,
# and every Frigate reader of a Nest camera's `_sub` stream, which would never start at all.
# Started from here, the session's dead binding is the relay's, and Frigate, the app and the
# Google displays all join a running stream. Each camera's `<name>_sub` (HomeSafe's card stream, a
# GPU transcode) is held too, once its Nest session is flowing: the transcoder can only start at
# Google's next keyframe, seconds away, which is longer than Frigate waits for it, so a transcoder
# started for each of Frigate's retries never caught one. Holding it also keeps each camera streaming through
# Google all day: the always-ready Nest cameras asked for (docs/nest-cameras.md). NEST_PRIMER=0
# turns it off.
NEST_PRIMER = os.environ.get("NEST_PRIMER", "1") == "1"
# How often go2rtc's stream list is read again for Nest streams added since.
NEST_PRIMER_SCAN_SECONDS = 60
# The pause before holding a stream again after go2rtc closed it (a restart, Google ending the
# session), doubling up to the longest for a stream that keeps failing at once.
NEST_PRIMER_RETRY_SECONDS = 5
NEST_PRIMER_MAX_RETRY_SECONDS = 300
# A hold this long was a working stream, whatever ended it: the next wait starts short again.
NEST_PRIMER_HELD_SECONDS = 60
# While a stream is held, how often go2rtc's video receiver byte counts for it are read, and how
# long they may stand still before the hold is dropped and taken again. When Google ends a session,
# go2rtc's Nest producer can be left with no receivers while the primer's viewer stays attached and
# gets nothing, and go2rtc never reopens the producer for a viewer it already has: cameras were dark
# for hours (2026-10-18) until the relay restarted. The counts are watched, not the hold's own read:
# the viewer that starts a Nest session gets 0 bytes on purpose (above), so a read timeout would
# drop the very viewer keeping the session up. The window runs from the hold's start, which is the
# grace for Google to start the camera (up to ~11 s seen) and a transcoder its first keyframe.
NEST_PRIMER_WATCH_SECONDS = 15
NEST_PRIMER_STALL_SECONDS = 60
# Dropping the hold heals a session only when the hold was its last viewer. go2rtc stops a producer
# once no viewer is bound to it, and reconnects one only when its connection ends, which a Nest
# session Google stopped feeding doesn't do. Any other viewer left on it (the `_sub` transcoder,
# a leaked `frame.jpeg` waiting for a keyframe that never comes) keeps the dead session, and the
# hold taken again just joins it: on 2026-10-19 kitchen, kids_room and the doorbell were dark from
# the small hours to the evening that way. go2rtc has no way to drop one stream's viewers, so after
# this many drops in a row with no video between them the relay restarts go2rtc (`POST
# /api/restart`, an in-place re-exec that every viewer reconnects from in seconds). Frigate reads
# only the wired cameras' detect streams through go2rtc, not their recordings.
NEST_PRIMER_RESTART_AFTER_DROPS = 2
# The least time between two restarts, doubling while restarts don't bring the stream back, so a
# camera that sends nothing for reasons of its own doesn't blip every stream all day.
NEST_PRIMER_RESTART_COOLDOWN_SECONDS = 30 * 60
NEST_PRIMER_MAX_RESTART_COOLDOWN_SECONDS = 4 * 3600
# A camera Google won't stream ("The camera is not available for streaming": turned off in Google
# Home, offline, or Google's own trouble) gets no session at all, so nothing above can heal it,
# and only someone with the Google Home app can (the doorbell on 2026-10-17, and from 07:00 on
# 2026-10-20, with nobody told). Once a Nest stream's holds have gone this long without go2rtc
# ever having a session, the relay asks Google itself why and pushes once (see "Nest outages").
NEST_OUTAGE_ALERT_SECONDS = 3600
# The cameras whose outage was pushed, kept in relay.db so a relay restarted during the outage
# doesn't push it again, and does push when the camera is back.
NEST_OUTAGE_STATE_KEY = "nest_outage_alerted"
# How long after a push of a camera's return that no phone got (quiet hours, say) it's tried again.
NEST_OUTAGE_RETRY_SECONDS = 300
NEST_TOKEN_URL = "https://oauth2.googleapis.com/token"
NEST_SDM_URL = "https://smartdevicemanagement.googleapis.com/v1"


def nest_stream_names(streams: dict[str, Any]) -> list[str]:
    """The streams in go2rtc's `GET /api/streams` that pull a Nest camera: a producer with a `nest:` source."""
    return sorted(
        name
        for name, stream in (streams or {}).items()
        if any(str((producer or {}).get("url") or "").startswith("nest:") for producer in (stream or {}).get("producers") or [])
    )


def nest_grid_streams(streams: dict[str, Any]) -> dict[str, str]:
    """Each Nest camera's grid stream (`<name>_sub`, HomeSafe's card stream) that go2rtc has, by the Nest stream it transcodes."""
    return {f"{name}_sub": name for name in nest_stream_names(streams) if f"{name}_sub" in (streams or {})}


def nest_video_bytes(name: str) -> int | None:
    """The bytes [name]'s video receivers in go2rtc have had, added up; None when it has none."""
    stream = requests.get(f"{GO2RTC}/api/streams", params={"src": name}, timeout=5).json() or {}
    counts = [
        receiver.get("bytes") or 0
        for producer in stream.get("producers") or []
        for receiver in producer.get("receivers") or []
        if (receiver.get("codec") or {}).get("codec_type") == "video"
    ]
    return sum(counts) if counts else None


def nest_session_flowing(name: str) -> bool:
    """Whether [name]'s Nest session is up and its video arriving: some video receiver has bytes."""
    return (nest_video_bytes(name) or 0) > 0


class NestPrimerWatch:
    """One hold's view of whether its stream is still getting video, from go2rtc's receiver byte counts."""

    def __init__(self, started: float):
        # The last count read, None before the first.
        self.bytes: int | None = None
        self.moved_at = started
        # Whether the counts moved from one reading to the next while held: a working stream.
        self.flowed = False
        # Whether the watchdog dropped the hold.
        self.dropped = False
        # Whether go2rtc had a session for the stream at the last reading: video receivers, moving or not.
        self.session = False

    def stalled(self, now: float, received: int | None) -> bool:
        """
        With [received] (the video bytes read now; None for no receivers, or no reading), whether
        nothing has arrived for NEST_PRIMER_STALL_SECONDS. Any change to a count above 0 counts as
        arriving, since a reopened producer's counts start again from 0.
        """
        self.session = received is not None
        if received is not None and received != self.bytes:
            if received > 0:
                self.flowed = self.flowed or self.bytes is not None
                self.moved_at = now
            self.bytes = received
        return now - self.moved_at >= NEST_PRIMER_STALL_SECONDS


def nest_primer_watchdog(name: str, sock: socket.socket, watch: NestPrimerWatch, done: threading.Event) -> None:
    """
    Drops [name]'s hold (its connection [sock]) once its video stops arriving, or stops watching at
    [done]. The socket is shut down rather than closed: on Linux a close doesn't wake the holding
    thread's blocked read, so the connection, and go2rtc's viewer, would live on.
    """
    while not done.wait(NEST_PRIMER_WATCH_SECONDS):
        try:
            received = nest_video_bytes(name)
        except Exception:  # noqa: BLE001 - go2rtc restarting: nothing seen arriving
            received = None
        stalled = watch.stalled(time.monotonic(), received)
        if watch.flowed and NEST_OUTAGES.owed(name):
            # Off the watchdog's thread: a push takes as long as FCM does.
            threading.Thread(target=nest_outage_streaming, args=(name,), name=f"nest-outage-{name}", daemon=True).start()
        if stalled:
            log.info("nest primer: %s had no video for %d s; dropping it", name, NEST_PRIMER_STALL_SECONDS)
            watch.dropped = True
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            return


def nest_primer_hold(name: str, watch: NestPrimerWatch) -> None:
    """
    Holds [name] as one viewer, its bytes read and dropped, for as long as go2rtc keeps the
    connection and [watch] sees video arriving. Over http.client, not requests, so the watchdog has
    the socket from the moment it connects: go2rtc may not answer the viewer that starts a Nest
    session at all, headers included, and that wait has to be watched too.
    """
    url = urlsplit(GO2RTC)
    opener = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection
    connection = opener(url.hostname, url.port, timeout=5)
    try:
        connection.connect()
        # No read timeout: the starting viewer gets nothing on purpose, so the watchdog decides.
        connection.sock.settimeout(None)
        done = threading.Event()
        threading.Thread(target=nest_primer_watchdog, args=(name, connection.sock, watch, done), name=f"nest-watchdog-{name}", daemon=True).start()
        try:
            connection.request("GET", f"{url.path}/api/stream.mp4?{urlencode({'src': name})}")
            response = connection.getresponse()
            if response.status != 200:
                raise http.client.HTTPException(f"HTTP {response.status}")
            while response.read(64 * 1024):
                pass
        finally:
            done.set()
    finally:
        connection.close()


def nest_primer_held(watch: NestPrimerWatch, seconds: float) -> bool:
    """
    Whether a hold that lasted [seconds] was a working stream, so the next wait starts short. One
    the watchdog dropped was if its video ever moved: a camera that never sends anything still
    backs off, though each try now lasts a minute.
    """
    return watch.flowed if watch.dropped else seconds >= NEST_PRIMER_HELD_SECONDS


class NestGo2rtcRestarts:
    """Across every Nest stream's holds, whether go2rtc is keeping a dead session and should be restarted."""

    def __init__(self):
        self.lock = threading.Lock()
        # Per stream, the drops in a row with no video between them.
        self.drops: dict[str, int] = {}
        # When go2rtc was last restarted (monotonic), and the least wait after it before the next.
        self.restarted_at: float | None = None
        self.cooldown = NEST_PRIMER_RESTART_COOLDOWN_SECONDS
        # The streams restarts were for whose video hasn't moved since. While any is left, a restart
        # (for whichever stream) hasn't helped, so the wait after the next one doubles: two cameras
        # dead by turns back off like one.
        self.unhealed: set[str] = set()

    def hold_ended(self, name: str, watch: NestPrimerWatch, now: float) -> bool:
        """
        With how [name]'s hold just ended ([watch]), whether go2rtc should be restarted now. A drop
        after video moved is the first in a row (the session just died); a drop of a hold that
        never saw any is one more (the hold joined a session go2rtc kept dead). A hold that ended
        any other way means go2rtc let go of the session, so the count starts over. So does a drop
        with no session at all: go2rtc kept nothing, so the next hold dials Google afresh, and when
        Google won't stream the camera ("The camera is not available for streaming", the doorbell
        on 2026-10-20) a restart can't change that.
        """
        with self.lock:
            if watch.flowed and name in self.unhealed:
                self.unhealed.discard(name)
                if not self.unhealed:
                    # The restarts brought every stream back: the wait is the first one again,
                    # counted from the last restart like any other.
                    self.cooldown = NEST_PRIMER_RESTART_COOLDOWN_SECONDS
            if not (watch.dropped and watch.session):
                self.drops[name] = 0
                return False
            self.drops[name] = 1 if watch.flowed else self.drops.get(name, 0) + 1
            if self.drops[name] < NEST_PRIMER_RESTART_AFTER_DROPS:
                return False
            if self.restarted_at is not None and now < self.restarted_at + self.cooldown:
                return False
            self.drops.clear()
            if self.unhealed:
                # An earlier restart hasn't brought its stream back.
                self.cooldown = min(NEST_PRIMER_MAX_RESTART_COOLDOWN_SECONDS, self.cooldown * 2)
            self.restarted_at = now
            self.unhealed.add(name)
            return True


NEST_GO2RTC_RESTARTS = NestGo2rtcRestarts()


def go2rtc_restart(name: str) -> None:
    """Restarts go2rtc in place, for [name]'s session it kept dead."""
    log.warning("nest primer: %s still has no video after %d holds; restarting go2rtc", name, NEST_PRIMER_RESTART_AFTER_DROPS)
    try:
        requests.post(f"{GO2RTC}/api/restart", timeout=5).raise_for_status()
    except Exception as e:  # noqa: BLE001 - the next stall asks again, after the cooldown
        log.warning("nest primer: go2rtc not restarted (%s)", e)


def nest_primer_delay(failures: int) -> float:
    """The wait before holding a stream again after [failures] short holds in a row: 5 s, doubling to 5 minutes."""
    return min(NEST_PRIMER_MAX_RETRY_SECONDS, NEST_PRIMER_RETRY_SECONDS * 2 ** max(0, failures - 1))


def nest_primer_stream(name: str, after: str | None = None) -> None:
    """
    Holds [name] for good. With [after] (the Nest stream a grid stream transcodes), only once
    [after]'s session is flowing: the transcoder must join that session, not start it, or it
    takes the dead binding itself.
    """
    # Holds that end quickly in a row (Google refusing the camera, say) back off, so a stream that
    # won't start isn't asked of Google every few seconds; one that held for a while starts over.
    failures = 0
    while True:
        if after is not None:
            try:
                while not nest_session_flowing(after):
                    time.sleep(NEST_PRIMER_RETRY_SECONDS)
            except Exception as e:  # noqa: BLE001 - go2rtc restarting
                log.info("nest primer: %s not read (%s)", after, e)
                time.sleep(NEST_PRIMER_RETRY_SECONDS)
                continue
        started = time.monotonic()
        watch = NestPrimerWatch(started)
        try:
            nest_primer_hold(name, watch)
            if not watch.dropped:
                log.info("nest primer: %s closed; holding it again", name)
        except Exception as e:  # noqa: BLE001 - go2rtc restarting, Google refusing the camera
            # The watchdog's drop ends the read with an error of its own, already logged.
            if not watch.dropped:
                log.info("nest primer: %s not held (%s)", name, e)
        failures = 1 if nest_primer_held(watch, time.monotonic() - started) else failures + 1
        # A grid stream stalls with its Nest stream, which decides for both.
        if after is None and NEST_GO2RTC_RESTARTS.hold_ended(name, watch, time.monotonic()):
            go2rtc_restart(name)
            failures = 0
        if after is None:
            try:
                nest_outage_hold_ended(name, watch, time.time())
            except Exception as e:  # noqa: BLE001 - FCM or relay.db: the next hold tries again
                log.warning("nest outage: %s not pushed (%s)", name, type(e).__name__)
        time.sleep(nest_primer_delay(failures))


def nest_primer_forever() -> None:
    held: dict[str, threading.Thread] = {}
    nest_outage_load()
    while True:
        try:
            streams = requests.get(f"{GO2RTC}/api/streams", timeout=10).json()
            wanted = {name: None for name in nest_stream_names(streams)} | nest_grid_streams(streams)
            for name, after in wanted.items():
                if name not in held:
                    held[name] = threading.Thread(target=nest_primer_stream, args=(name, after), name=f"nest-primer-{name}", daemon=True)
                    held[name].start()
                    log.info("nest primer: holding %s%s", name, f" once {after} is flowing" if after else "")
        except Exception as e:  # noqa: BLE001 - go2rtc not up yet
            log.info("nest primer: go2rtc's streams not read (%s)", e)
        time.sleep(NEST_PRIMER_SCAN_SECONDS)


# ---------------------------------------------------------------- Nest outages

# Telling the household when Google won't stream a Nest camera (see NEST_OUTAGE_ALERT_SECONDS).
# A hold with no go2rtc session at all, whether the watchdog dropped it or it failed outright, is
# a camera go2rtc couldn't open; a whole hour of those, and Google saying why, is one push naming
# the camera. A second push says it's back, the first time its video moves again. Nothing in
# between: the primer keeps asking every few minutes, and the outage is told once.


class NestOutages:
    """Per Nest stream, since when its holds have had no go2rtc session, and what the household was told of it."""

    def __init__(self, alerted: set[str] | None = None):
        self.lock = threading.Lock()
        # Per stream in an outage, when (epoch seconds) the first hold with no session ended.
        self.since: dict[str, float] = {}
        # Per stream, what Google said this outage, once asked: kept while no phone has been told,
        # so the next try pushes without asking Google again.
        self.reasons: dict[str, str] = {}
        # The streams whose outage was pushed, while they're still out.
        self.alerted: set[str] = set(alerted or ())
        # The streams whose outage was pushed and that are back, until a phone is told so: until
        # then, a phone may still be showing the outage.
        self.recovering: set[str] = set()
        # Per recovering stream, when (epoch seconds) a push of its return may be tried again
        # after one no phone got (quiet hours): not at every watchdog reading.
        self.retry_at: dict[str, float] = {}
        # The streams being asked about or pushed right now.
        self.busy: set[str] = set()
        # Taken around each save, from reading the state to writing it, so saves land in order.
        self.save_lock = threading.Lock()
        # Whether what was told hasn't been saved yet (a save failed): the next hold or reading saves it.
        self.dirty = False

    def told_about(self) -> list[str]:
        """The streams an outage was pushed for and whose return wasn't: what relay.db keeps."""
        with self.lock:
            return sorted(self.alerted | self.recovering)

    def owed(self, name: str) -> bool:
        """Whether a phone may be showing [name]'s outage: its return is to be pushed once its video moves."""
        return name in self.alerted or name in self.recovering

    def hold_ended(self, name: str, watch: NestPrimerWatch, now: float) -> bool:
        """
        With how [name]'s hold just ended ([watch]), whether its outage is due to be pushed now:
        an hour of holds with no session, not pushed yet. A hold that had a session (video moving
        or not) ends any outage: go2rtc reached Google, and anything after that is the primer's
        to heal (see NestGo2rtcRestarts).
        """
        with self.lock:
            if watch.flowed or watch.session:
                self.since.pop(name, None)
                self.reasons.pop(name, None)
                return False
            since = self.since.setdefault(name, now)
            if name in self.recovering and name not in self.busy:
                # Out again before any phone heard it was back: the outage on the phones still stands.
                self.recovering.discard(name)
                self.retry_at.pop(name, None)
                self.alerted.add(name)
            if name in self.alerted or name in self.busy or now - since < NEST_OUTAGE_ALERT_SECONDS:
                return False
            self.busy.add(name)
            return True

    def told(self, name: str, reason: str | None, sent: bool, now: float) -> None:
        """
        After asking Google ([reason], None when Google does stream it) and pushing when it
        didn't (whether some phone got it, [sent]). Google streaming it starts the hour over: the
        trouble is between go2rtc and Google, not one the Google Home app can fix. A push no phone
        got (quiet hours, only-away phones) is tried again at the next hold.
        """
        with self.lock:
            self.busy.discard(name)
            if sent:
                self.alerted.add(name)
            elif name in self.since:
                if reason is None:
                    self.since[name] = now
                    self.reasons.pop(name, None)
                else:
                    self.reasons[name] = reason

    def streaming(self, name: str, now: float) -> bool:
        """
        [name]'s video moved: its outage, if any, is over. Whether its return is to be pushed
        now: its outage was, and no push of its return is in flight or waiting out a retry.
        """
        with self.lock:
            self.since.pop(name, None)
            self.reasons.pop(name, None)
            if name in self.alerted and name not in self.busy:
                self.alerted.discard(name)
                self.recovering.add(name)
            if name not in self.recovering or name in self.busy or now < self.retry_at.get(name, 0.0):
                return False
            self.busy.add(name)
            return True

    def recovered(self, name: str, sent: bool, now: float) -> None:
        """After pushing [name]'s return: done once some phone got it ([sent]), else tried again later."""
        with self.lock:
            self.busy.discard(name)
            if sent:
                self.recovering.discard(name)
                self.retry_at.pop(name, None)
            elif name in self.recovering:
                self.retry_at[name] = now + NEST_OUTAGE_RETRY_SECONDS


NEST_OUTAGES = NestOutages()


def nest_source(name: str) -> dict[str, str] | None:
    """The parameters of [name]'s `nest:` source in go2rtc (its credentials among them: never log them), or None for none."""
    stream = requests.get(f"{GO2RTC}/api/streams", params={"src": name}, timeout=5).json() or {}
    for producer in stream.get("producers") or []:
        url = str((producer or {}).get("url") or "")
        if url.startswith("nest:"):
            return {key: values[0] for key, values in parse_qs(url.partition("?")[2]).items()}
    return None


def nest_probe_offer() -> str:
    """
    A WebRTC offer of the shape Google asks a Nest camera's viewer for (audio, then video, then a
    data channel), to learn whether Google will stream the camera. Nothing ever connects to it:
    its ICE credentials and DTLS fingerprint are made up.
    """
    fingerprint = ":".join(f"{b:02X}" for b in secrets.token_bytes(32))
    common = ["c=IN IP4 0.0.0.0", f"a=ice-ufrag:{secrets.token_hex(4)}", f"a=ice-pwd:{secrets.token_hex(12)}", f"a=fingerprint:sha-256 {fingerprint}", "a=setup:actpass"]
    lines = [
        "v=0", f"o=- {secrets.randbelow(10**12)} 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1 2", "a=msid-semantic: WMS",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111", *common, "a=mid:0", "a=recvonly", "a=rtcp-mux", "a=rtpmap:111 opus/48000/2",
        "m=video 9 UDP/TLS/RTP/SAVPF 96", *common, "a=mid:1", "a=recvonly", "a=rtcp-mux", "a=rtpmap:96 H264/90000",
        "a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", *common, "a=mid:2", "a=sctp-port:5000",
    ]
    return "\r\n".join(lines) + "\r\n"


def nest_google_check(name: str) -> str | None:
    """
    Asks Google whether it will stream [name], the way go2rtc does (`GenerateWebRtcStream`, with
    the credentials in go2rtc's `nest:` source): "refused" when Google says the camera is not
    available for streaming, "unconfirmed" for any other refusal or none heard, and None when
    there's nothing to tell (Google starts a session, stopped again at once, or go2rtc has no
    `nest:` source to read). The credentials, and anything that could carry them (a URL, an
    exception's text), are never logged.
    """
    try:
        source = nest_source(name)
    except Exception as e:  # noqa: BLE001 - go2rtc restarting: the next hold asks again
        log.info("nest outage: %s's go2rtc stream not read (%s)", name, type(e).__name__)
        return None
    if source is None:
        return None
    keys = ("client_id", "client_secret", "refresh_token", "project_id", "device_id")
    if not all(source.get(key) for key in keys):
        log.info("nest outage: %s's nest: source lacks what asking Google takes", name)
        return "unconfirmed"

    def scrub(text: str) -> str:
        for key in keys:
            text = text.replace(source[key], "…")
        return text[:200]

    try:
        token = requests.post(NEST_TOKEN_URL, data={
            "client_id": source["client_id"], "client_secret": source["client_secret"],
            "refresh_token": source["refresh_token"], "grant_type": "refresh_token",
        }, timeout=10)
        if not token.ok:
            log.warning("nest outage: Google gave no token to ask about %s (HTTP %d)", name, token.status_code)
            return "unconfirmed"
        headers = {"Authorization": f"Bearer {token.json()['access_token']}"}
        command = f"{NEST_SDM_URL}/enterprises/{quote(source['project_id'])}/devices/{quote(source['device_id'])}:executeCommand"
        answer = requests.post(command, headers=headers, json={
            "command": "sdm.devices.commands.CameraLiveStream.GenerateWebRtcStream", "params": {"offerSdp": nest_probe_offer()},
        }, timeout=15)
    except Exception as e:  # noqa: BLE001 - Google unreachable: say what was seen, unconfirmed
        log.warning("nest outage: Google not asked about %s (%s)", name, type(e).__name__)
        return "unconfirmed"
    try:
        body = answer.json()
    except Exception:  # noqa: BLE001 - not JSON: its status says enough
        body = None
    body = body if isinstance(body, dict) else {}
    if answer.ok:
        session = (body.get("results") or {}).get("mediaSessionId")
        if session:
            try:
                requests.post(command, headers=headers, json={
                    "command": "sdm.devices.commands.CameraLiveStream.StopWebRtcStream", "params": {"mediaSessionId": session},
                }, timeout=15)
            except Exception as e:  # noqa: BLE001 - Google ends an unused session on its own
                log.info("nest outage: the session asked of Google for %s not stopped (%s)", name, type(e).__name__)
        log.info("nest outage: Google does stream %s; its holds' trouble is on this side", name)
        return None
    error = body.get("error")
    message = scrub(str((error.get("message") if isinstance(error, dict) else error) or ""))
    log.info("nest outage: Google won't stream %s (HTTP %d: %s)", name, answer.status_code, message)
    return "refused" if "not available for streaming" in message.lower() else "unconfirmed"


def nest_outage_text(camera: str, reason: str | None, since: str) -> tuple[str, str]:
    """
    A push's title and body in English, for a phone whose app doesn't word them itself from the
    push's data: [camera] out since [since] (a clock time) for [reason] ("refused", Google said
    the camera isn't available; "unconfirmed"), or streaming again for None.
    """
    if reason is None:
        return f"{camera} is streaming again", "Google is sending its video again."
    if reason == "refused":
        return f"{camera} isn't streaming", f"Since {since}, Google says the camera isn't available for streaming. Check it in the Google Home app."
    return f"{camera} isn't streaming", f"No video from Google since {since}. Check the camera in the Google Home app."


def nest_outage_push(name: str, reason: str | None, since: float | None, push: Callable[..., dict[str, int]] | None = None) -> dict[str, int]:
    """
    Pushes [name]'s outage (for [reason], since [since]) or, with no reason, its end, as an
    ordinary alert: each phone's quiet hours and "only when away" apply. Both share one
    notification, so the second replaces the first. `push` stands in for `broadcast` in tests.
    """
    camera = camera_name(name)
    title, body = nest_outage_text(camera, reason, clock_text(since, household_zone()) if since is not None else "")
    data = {"system": "nest", "notif_id": f"nest-{name}", "camera": name, "nest_camera_name": camera, "nest_event": "streaming" if reason is None else "unavailable"}
    if reason is not None:
        data["nest_reason"] = reason
    if since is not None:
        data["nest_since"] = f"{since:.0f}"
    result = (push or broadcast)(title, body, data)
    log.info("nest outage: %s %s -> push: %s", name, data["nest_event"], result)
    return result


def nest_outage_save() -> None:
    """
    Keeps what was told in relay.db. Read and written under one lock, so a save that read the
    state earlier can't land after one that read it later; one that fails is tried again at the
    next hold or reading (see `nest_outage_hold_ended`), without pushing anything again.
    """
    with NEST_OUTAGES.save_lock:
        NEST_OUTAGES.dirty = True
        try:
            state_set(NEST_OUTAGE_STATE_KEY, NEST_OUTAGES.told_about() or None)
            NEST_OUTAGES.dirty = False
        except Exception as e:  # noqa: BLE001 - relay.db busy: saved at the next hold
            log.warning("nest outage: what was told not saved (%s)", type(e).__name__)


def nest_outage_load() -> None:
    """The outages pushed before the relay restarted: not pushed again, and their end still is."""
    try:
        NEST_OUTAGES.alerted |= set(state_get(NEST_OUTAGE_STATE_KEY) or [])
    except Exception as e:  # noqa: BLE001 - a camera's return goes untold, nothing worse
        log.warning("nest outage: the outages already pushed not read (%s)", e)


def nest_outage_hold_ended(name: str, watch: NestPrimerWatch, now: float, check: Callable[[str], str | None] | None = None, push: Callable[..., dict[str, int]] | None = None) -> None:
    """After [name]'s hold ended ([watch]): when its outage is due, asks Google why and pushes it. `check` and `push` stand in for Google and `broadcast` in tests."""
    if NEST_OUTAGES.dirty:
        nest_outage_save()
    if not NEST_OUTAGES.hold_ended(name, watch, now):
        return
    reason, sent = NEST_OUTAGES.reasons.get(name), False
    try:
        reason = reason or (check or nest_google_check)(name)
        if reason is not None:
            sent = bool(nest_outage_push(name, reason, NEST_OUTAGES.since.get(name), push).get("sent"))
    finally:
        NEST_OUTAGES.told(name, reason, sent, now)
    if sent:
        nest_outage_save()


def nest_outage_streaming(name: str, now: float | None = None, push: Callable[..., dict[str, int]] | None = None) -> None:
    """
    [name]'s video moved (at [now]): when its outage was pushed, pushes that it's back. A push
    no phone got, or one that failed, stays owed and is tried again NEST_OUTAGE_RETRY_SECONDS on.
    """
    now = time.time() if now is None else now
    if NEST_OUTAGES.dirty:
        nest_outage_save()
    if not NEST_OUTAGES.streaming(name, now):
        return
    sent = False
    try:
        sent = bool(nest_outage_push(name, None, None, push).get("sent"))
    except Exception as e:  # noqa: BLE001 - FCM or relay.db: tried again later
        log.warning("nest outage: %s's return not pushed (%s)", name, type(e).__name__)
    finally:
        NEST_OUTAGES.recovered(name, sent, now)
    if sent:
        nest_outage_save()


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


def sheet_payload(meta: dict[str, Any], values: dict[str, Any], sheet_id: str, fetched_at: float, formats: dict[tuple[str, int, int], dict[str, str]] | None = None,
                  formulas: list[list[list[int]]] | None = None) -> dict[str, Any]:
    """
    The workbook as the app reads it: each grid tab's rows of raw values and its merged ranges
    (0-based, end-exclusive), and the workbook's charts. `values` holds one range per grid tab,
    in order, and `formulas` (when they could be read) each grid tab's cells that hold a formula
    rather than a typed value, as [row, column].
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
        tab = {"title": sheet.get("properties", {}).get("title", ""), "values": rows, "merges": merges}
        if formulas is not None and i < len(formulas):
            tab["formulas"] = formulas[i]
        sheets.append(tab)
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


def formula_cells(rows: list[list[Any]]) -> list[list[int]]:
    """A tab's cells, read with their formulas as written, that hold one: [row, column] each."""
    return [[r, c] for r, row in enumerate(rows) for c, value in enumerate(row) if isinstance(value, str) and value.startswith("=")]


def sheet_formulas(session: Any, titles: list[str]) -> list[list[list[int]]] | None:
    """
    Which cells of each grid tab are worked out by a formula, in one more call: the app offers to
    update only a balance that is typed (see "typed balances"), never one the sheet calculates or
    looks up in the bank feed. A failure is logged and the sheet is served without them; the app
    then offers every balance, and the write route still refuses a formula's cell.
    """
    try:
        params = [("ranges", sheet_range(t)) for t in titles]
        params += [("valueRenderOption", "FORMULA"), ("majorDimension", "ROWS")]
        answer = session.get(f"{SHEETS_API}/{FINANCE_SHEET_ID}/values:batchGet", params=params, timeout=20)
        if answer.status_code != 200:
            log.warning("finance: formulas unavailable (%s)", answer.status_code)
            return None
        ranges = answer.json().get("valueRanges", [])
        return [formula_cells(r.get("values", [])) for r in ranges]
    except Exception as e:
        log.warning("finance: formulas unavailable: %s", e)
        return None


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
        return sheet_payload(meta.json(), values.json(), FINANCE_SHEET_ID, time.time(), chart_formats(session, meta.json()), sheet_formulas(session, titles))
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
    """
    The household budget workbook (see "finance"), every grid tab, with when each balance was last
    known to be right (see "typed balances"). A Frigate admin, or a user in FINANCE_USERS.
    """
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    # The workbook may be a minute old; when its balances were last updated never is.
    return {**finance_sheet(refresh), "updates": typed_updates(), "filled": typed_filled()}


def finance_sheet(refresh: bool) -> dict[str, Any]:
    """The workbook from memory when it was read lately enough, else from Google (see `get_finance_sheet`)."""
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
# How much of a card's past a new link asks for: as far as Plaid goes.
PLAID_TXN_DAYS = 730
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
        # Fixed for good when the link is made: Plaid would otherwise fetch 90 days and never more.
        if product == "transactions":
            body["transactions"] = {"days_requested": PLAID_TXN_DAYS}
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


def plaid_collect_links(now: float | None = None) -> list[str]:
    """
    Finishes every link that was started and never asked about again, and answers the
    institutions that brought in. The app follows a link only while it remembers starting it,
    and Android may well restart it while the person is away at their bank's sign-in: the
    sign-in is then made on Plaid's side with nobody left to collect it, and Plaid's token for it
    is good for half an hour. So the relay looks for itself, at every check and whenever the
    linked accounts are asked for. A link still open, left or refused costs one question of Plaid
    and is left as it was.
    """
    now = time.time() if now is None else now
    pending = [token for token, link in (state_get(PLAID_LINKS_KEY) or {}).items() if link.get("done") is None and link.get("expires", 0) > now - PLAID_LINK_SECONDS]
    collected: list[str] = []
    for token in pending:
        try:
            result = plaid_link_finish(token, now)
        except PlaidError as e:
            log.info("bank: a link nobody came back for couldn't be asked about: %s", e.code)
            continue
        except Exception as e:
            # Plaid unreachable. Not the detail: it may quote the request.
            log.info("bank: a link nobody came back for couldn't be asked about: %s", type(e).__name__)
            continue
        if result["status"] == "linked":
            collected += result.get("institutions") or []
    return collected


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
    new to the relay is given its key in the feed (see `plaid_assign_keys`), and how everything
    stands today goes into the history (see `plaid_keep_day`).
    """
    day = household_day(now)

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
            lots: dict[tuple[Any, Any], int] = {}
            for h in holdings:
                security = securities.get(h.get("security_id"), {})
                held = (h.get("account_id"), h.get("security_id"))
                lots[held] = lots.get(held, -1) + 1
                c.execute(
                    "INSERT OR REPLACE INTO plaid_holdings (account_id, security_id, lot, item_id, ticker, name, kind, quantity, vested, price, price_as_of,"
                    " value, cost_basis, currency) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (*held, lots[held], item_id, security.get("ticker_symbol"), security.get("name") or "", security.get("type"),
                     h.get("quantity"), h.get("vested_quantity"), h.get("institution_price"), h.get("institution_price_as_of"), h.get("institution_value"),
                     h.get("cost_basis"), h.get("iso_currency_code") or h.get("unofficial_currency_code")),
                )
        plaid_assign_keys(c)
        plaid_keep_day(c, item_id, day, holdings is not None, now)
        c.commit()

    with_db(write)


def plaid_keep_day(c: sqlite3.Connection, item_id: str, day: str, holdings_read: bool, now: float) -> None:
    """
    Puts an institution's balances and holdings as just read into the history, one row each per
    day: a second read that day takes the place of the first, a position sold in between going
    with it. Holdings Plaid hadn't ready are left as the day's first read had them. The caller commits.
    """
    c.execute(
        "INSERT OR REPLACE INTO plaid_balance_days (day, account_id, item_id, key, institution, name, mask, type, subtype, current, available, credit_limit, currency, at)"
        " SELECT ?, a.account_id, a.item_id, k.key, i.institution, a.name, a.mask, a.type, a.subtype, a.current, a.available, a.credit_limit, a.currency, ?"
        " FROM plaid_accounts a LEFT JOIN plaid_items i ON i.item_id = a.item_id LEFT JOIN plaid_feed_keys k ON k.account_id = a.account_id WHERE a.item_id=?",
        (day, now, item_id),
    )
    if holdings_read:
        c.execute("DELETE FROM plaid_holding_days WHERE day=? AND item_id=?", (day, item_id))
        c.execute(
            "INSERT OR REPLACE INTO plaid_holding_days (day, account_id, security_id, lot, item_id, key, ticker, name, kind, quantity, vested, price, price_as_of, value, cost_basis, currency, at)"
            " SELECT ?, h.account_id, h.security_id, h.lot, h.item_id, k.key, h.ticker, h.name, h.kind, h.quantity, h.vested, h.price, h.price_as_of, h.value, h.cost_basis, h.currency, ?"
            " FROM plaid_holdings h LEFT JOIN plaid_feed_keys k ON k.account_id = h.account_id WHERE h.item_id=?",
            (day, now, item_id),
        )


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
        plans = [a.get("account_id") for a in accounts if a.get("subtype") == STOCK_PLAN_SUBTYPE and a.get("account_id")]
        if plans and "investments" in products:
            stock_events_sync(item_id, access, plans, now)
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


# ---------------------------------------------------------------- stock plan
#
# An employer's stock plan (RSUs) is linked like any brokerage, and Plaid reports two things about
# it: the grants not yet vested, each as a holding of the share, and the account's activity, in
# which every vest is a transfer in of shares. Neither says when the next vest is or how many
# shares it brings. But vests come at a steady pace (every three months, around the same day), so
# the past ones say it: the next vest is one step on from the last, of as many shares as the last,
# and so on until the unvested shares run out. That is an estimate, and is called one: a grant
# ending or a new one starting changes the number, and the next vest that is read corrects it.
#
# The sheet fill can put a vest in a cell (a line with "vest" in place of "accounts"; see "sheet
# fill"), and the app shows the whole outlook.

STOCK_PLAN_SUBTYPE = "stock plan"
# How far back the activity is asked for: as far as Plaid keeps it.
STOCK_EVENTS_DAYS = 730
STOCK_EVENTS_PAGE = 500
# How many vests ahead and how many past ones the app is shown. Every vest is worked out, to the
# last unvested share, and a sheet line may name any of them; VESTS_MOST only stops a plan with
# an absurd pace (a share a month of a million) from being worked out for ever.
VESTS_AHEAD = 12
VESTS_SHOWN = 8
VESTS_MOST = 600
# A last vest smaller than this share of the usual one is not a vest of its own: the grants don't
# end on a round number, and what is left over comes with the vest before.
VEST_LEFTOVER = 0.1
# How far from the day a sheet line names a vest may fall and still be that line's vest: vests
# slip a few days around weekends, and a person types the date from memory.
VEST_MATCH_DAYS = 20


def stock_events_sync(item_id: str, access: str, account_ids: list[str], now: float) -> None:
    """
    The activity of an institution's stock plan accounts into relay.db, replacing what was kept of
    the days just asked about: what has passed out of Plaid's reach stays as it was read. A failure keeps what was read before and is only logged: the balances were read all the same.
    """
    try:
        today = datetime.fromtimestamp(now, household_zone() or timezone.utc).date()
        since = str(today - timedelta(days=STOCK_EVENTS_DAYS))
        asked = {"access_token": access, "start_date": since, "end_date": str(today)}
        events: list[dict[str, Any]] = []
        tickers: dict[str, str | None] = {}
        for _ in range(10):
            answer = plaid_post("/investments/transactions/get", {**asked, "options": {"account_ids": account_ids, "count": STOCK_EVENTS_PAGE, "offset": len(events)}})
            page = [t for t in answer.get("investment_transactions") or [] if isinstance(t, dict)]
            tickers.update({s.get("security_id"): s.get("ticker_symbol") for s in answer.get("securities") or [] if isinstance(s, dict)})
            events += page
            if not page or len(events) >= int(answer.get("total_investment_transactions") or 0):
                break

        def write(c: sqlite3.Connection) -> None:
            c.execute("DELETE FROM plaid_stock_events WHERE item_id=? AND date >= ?", (item_id, since))
            for t in events:
                if t.get("investment_transaction_id") and t.get("account_id") in account_ids and t.get("date"):
                    c.execute("INSERT OR REPLACE INTO plaid_stock_events VALUES (?,?,?,?,?,?,?,?)",
                              (t["investment_transaction_id"], item_id, t["account_id"], str(t["date"]), t.get("type"), tickers.get(t.get("security_id")),
                               t.get("quantity"), t.get("name")))
            c.commit()

        with_db(write)
    except PlaidError as e:
        log.warning("bank: %s's stock plan activity wasn't read: %s", item_id[:8], e.code)
    except Exception as e:
        log.warning("bank: %s's stock plan activity wasn't read: %s", item_id[:8], type(e).__name__)


def stock_plan_outlook(events: list[tuple[str, str | None, str | None, float | None]], holdings: list[tuple[str | None, float | None, float | None, float | None]]) -> dict[str, Any] | None:
    """
    What a stock plan account's past says about its future (see "stock plan"). `events` are its
    activity as (date, kind, ticker, shares), `holdings` what it holds as (ticker, shares, of
    which vested, price). None when no share has ever vested in it.

    Answers the share's `ticker` and `price`, the shares still `unvested`, the vests read
    (`vests`, oldest first, each a `date` and `shares`), and those to come (`upcoming`, each with
    its `value` at today's price too): one every `every_months` on the day of the month vests
    usually fall, each of `per_vest` shares (the last vest's), until the unvested shares are used
    up, however many vests that takes. With a single vest read the pace isn't known, and nothing
    is `upcoming`.
    """
    vested: dict[str, dict[str, float]] = {}
    for day, kind, ticker, shares in events:
        if kind == "transfer" and ticker and shares and shares > 0:
            vested.setdefault(ticker, {})[day] = vested.get(ticker, {}).get(day, 0.0) + shares
    if not vested:
        return None
    ticker = max(vested, key=lambda t: len(vested[t]))
    vests = sorted(vested[ticker].items())
    mine = [h for h in holdings if h[0] == ticker]
    unvested = sum(max(0.0, (shares or 0.0) - (already or 0.0)) for _, shares, already, _ in mine)
    price = next((p for _, _, _, p in mine if p), None)
    outlook: dict[str, Any] = {
        "ticker": ticker, "price": price, "unvested": unvested, "every_months": None, "per_vest": vests[-1][1],
        "vests": [{"date": day, "shares": shares} for day, shares in vests], "upcoming": [],
    }
    if len(vests) < 2:
        return outlook
    days = [datetime.strptime(day, "%Y-%m-%d").date() for day, _ in vests]
    gaps = sorted((b.year - a.year) * 12 + b.month - a.month for a, b in zip(days, days[1:]))
    every = max(1, gaps[len(gaps) // 2])
    # The day of the month vests fall on is the commonest one; a weekend moves a vest a day or two off it.
    usual = max(sorted({d.day for d in days}), key=[d.day for d in days].count)
    outlook["every_months"] = every
    per_vest, left, at = vests[-1][1], unvested, days[-1].year * 12 + days[-1].month - 1
    while left > 0 and len(outlook["upcoming"]) < VESTS_MOST:
        at += every
        year, month = divmod(at, 12)
        shares = min(per_vest, left)
        left -= shares
        if 0 < left < per_vest * VEST_LEFTOVER:
            shares, left = shares + left, 0
        day = datetime(year, month + 1, min(usual, calendar.monthrange(year, month + 1)[1])).date()
        outlook["upcoming"].append({"date": str(day), "shares": shares, "value": round(shares * price, 2) if price else None})
    return outlook


def stock_plans(shown: bool = False) -> list[dict[str, Any]]:
    """
    Every linked stock plan account with its outlook (see `stock_plan_outlook`), by the account's
    feed key. One no share has vested in yet isn't listed. Whole, for the sheet fill to find any
    vest in; `shown` cuts it to what the app's page has room for (the nearest vests either way).
    """
    accounts = with_db(lambda c: c.execute(
        "SELECT a.account_id, k.key, a.name, i.institution FROM plaid_accounts a JOIN plaid_items i ON i.item_id = a.item_id"
        " LEFT JOIN plaid_feed_keys k ON k.account_id = a.account_id WHERE a.subtype=? ORDER BY i.linked_at, a.account_id", (STOCK_PLAN_SUBTYPE,)
    ).fetchall())
    plans = []
    for account_id, key, name, institution in accounts:
        events = with_db(lambda c: c.execute("SELECT date, kind, ticker, quantity FROM plaid_stock_events WHERE account_id=?", (account_id,)).fetchall())
        holdings = with_db(lambda c: c.execute("SELECT ticker, quantity, vested, price FROM plaid_holdings WHERE account_id=?", (account_id,)).fetchall())
        outlook = stock_plan_outlook(events, holdings)
        if outlook and shown:
            outlook = {**outlook, "vests": outlook["vests"][-VESTS_SHOWN:], "upcoming": outlook["upcoming"][:VESTS_AHEAD]}
        if outlook:
            plans.append({"account": key or name, "name": name, "institution": institution or "", **outlook})
    return plans


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
        "SELECT account_id, ticker, name, quantity, price, value, cost_basis, kind, currency, price_as_of FROM plaid_holdings"
        " ORDER BY account_id, value DESC, security_id, lot"
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


# ---------------------------------------------------------------- sheet fill
#
# The feed leaves the budget sheet's own numbers alone: a balance there changes only once someone
# swaps the typed number for a lookup. The sheet fill does the typing instead. FINANCE_FILL_FILE, a
# JSON file kept only on the box (it names the household's accounts; the repo is public), lists the
# cells: each line is a row of the budget sheet by its label ("Roth IRA"), how many columns to the
# right of the label the number sits, and the accounts (by feed key) it adds up. After every sync
# each line's sum is written into its cell, where it differs from what the cell holds.
#
#     {"tab": "Home", "lines": [
#       {"label": "Roth IRA", "right": 2, "accounts": ["Chase Roth IRA 7788"]},
#       {"label": "Credit Cards", "right": 1, "accounts": ["Chase Sapphire 9911"], "negative": true},
#       {"label": "Nov 15th", "right": 2, "vest": {"account": "Acme Stock Plan 0042", "on": "2026-11-15"}}]}
#
# The last is a vest rather than a balance (see "stock plan"): what the shares due around that day
# are worth at today's price, or with "as": "shares" how many they are.
#
# What keeps a write from landing somewhere it shouldn't:
# - A cell is found by its label every time, never by a remembered address, so rows inserted above
#   it don't move the write onto its neighbour. A label that is gone, or there twice, isn't guessed,
#   and two lines that come to the same cell are both left alone.
# - Only a number or an empty cell is written over. A formula or words there mean the sheet was
#   changed by hand, and the line is left alone.
# - A line is written whole or not at all: with one of its accounts unlinked or unread, the sum of
#   the rest would be a wrong number that looks right.
# - Every write is kept (`sheet_writes`): what each cell held before and after. The app shows
#   them, and any one can be undone. An undo puts a number back only where the cell still holds
#   what was written (someone may have typed since), and pauses those lines so the next morning's
#   sync doesn't write them straight back. A paused line starts again when it is resumed.
#
# The file is read at every sync, so changing it needs no restart. Off without the file. The
# budget sheet must be shared with the service account as an Editor (docs/bank-sync.md).

FINANCE_FILL_FILE = os.environ.get("FINANCE_FILL_FILE", "/data/sheet-fill.json")
FILL_KEY = "sheet_fill"
FILL_PAUSED_KEY = "sheet_fill_paused"
# How many writes are kept, and how many of the newest the app is shown.
FILL_WRITES_KEPT = 200
FILL_WRITES_SHOWN = 20
# Two amounts closer than this are the same number of cents.
FILL_CENT = 0.005


class FillLine(BaseModel):
    # A line's `id`, as `bank_status` gives it.
    line: str
    paused: bool


def fill_text(value: Any) -> str:
    """A cell's or a label's words as they are compared: case and the spacing around and between them don't count."""
    return " ".join(str(value).split()).casefold()


def fill_lines() -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    """
    The lines FINANCE_FILL_FILE lists, and what is wrong with the file when something is. A file
    with one line that can't be understood is refused whole: half a file's writes are not what
    whoever wrote it meant. No file is no lines and nothing wrong.

    What is wrong is a `reason` the app has its own words for ("unreadable", "no_lines",
    "bad_line", "same_line"), the `line` it is about (counted from 1) where there is one, and a
    `message` for the log.
    """
    def wrong(reason: str, message: str, line: int | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        return [], {"reason": reason, "line": line, "message": message}

    try:
        with open(FINANCE_FILL_FILE, encoding="utf-8") as f:
            kept = json.load(f)
    except FileNotFoundError:
        return [], None
    except (OSError, ValueError) as e:
        return wrong("unreadable", f"{os.path.basename(FINANCE_FILL_FILE)} can't be read: {e}")
    if not isinstance(kept, dict) or not isinstance(kept.get("lines"), list):
        return wrong("no_lines", f"{os.path.basename(FINANCE_FILL_FILE)} needs a \"lines\" list")
    lines, seen = [], set()
    for n, entry in enumerate(kept["lines"], 1):
        entry = entry if isinstance(entry, dict) else {}
        tab, label, right, accounts = entry.get("tab", kept.get("tab")), entry.get("label"), entry.get("right", 1), entry.get("accounts")
        vest = fill_vest_named(entry.get("vest"))
        if vest and accounts is None:
            # A vest's line is from one account, the stock plan's.
            accounts = [vest["account"]]
        well_formed = (
            isinstance(tab, str) and tab.strip() and isinstance(label, str) and fill_text(label)
            and isinstance(right, int) and not isinstance(right, bool) and right >= 1
            and isinstance(accounts, list) and accounts and all(isinstance(a, str) and a.strip() for a in accounts)
            and ("vest" not in entry or (vest is not None and accounts == [vest["account"]]))
            and (entry.get("as") is None or (vest is not None and entry["as"] in ("value", "shares")))
        )
        if not well_formed:
            return wrong("bad_line", f"Line {n} needs a label, a tab, the accounts it adds up (or the vest it is), and \"right\" as a number of columns", n)
        line_id = f"{tab.strip()}|{fill_text(label)}|{right}"
        if line_id in seen:
            return wrong("same_line", f"Line {n} writes the same cell as an earlier line", n)
        seen.add(line_id)
        lines.append({"id": line_id, "tab": tab.strip(), "label": " ".join(label.split()), "right": right,
                      "accounts": [a.strip() for a in accounts], "negative": entry.get("negative") is True,
                      "vest": vest, "shares": entry.get("as") == "shares"})
    return lines, None


def fill_vest_named(named: Any) -> dict[str, str] | None:
    """A line's "vest" as it must be: the stock plan `account` (its feed key) and the day the vest falls `on` (YYYY-MM-DD). None for anything else."""
    if not isinstance(named, dict) or not isinstance(named.get("account"), str) or not named["account"].strip() or not isinstance(named.get("on"), str):
        return None
    try:
        on = datetime.strptime(named["on"].strip(), "%Y-%m-%d").date()
    except ValueError:
        return None
    return {"account": named["account"].strip(), "on": str(on)}


def fill_vest(outlook: dict[str, Any] | None, on: str) -> tuple[str, dict[str, Any] | None]:
    """
    The vest of a stock plan's `outlook` that a line means by the day it names: the one nearest
    that day, read or still to come, if it falls within VEST_MATCH_DAYS of it. (A plan that vests
    every month has two within reach of most days, and the nearer is the one meant.) Answers
    ("upcoming", the vest) for one still to come, ("vested", None) for one read already, and
    ("no_vest", None) when none is that near, or there is no outlook (no vest read yet, or too
    few to tell the pace).
    """
    wanted = datetime.strptime(on, "%Y-%m-%d").date()

    def away(vest: dict[str, Any]) -> int:
        return abs((datetime.strptime(vest["date"], "%Y-%m-%d").date() - wanted).days)

    outlook = outlook or {}
    # Equally near, the one still to come: a line is written for a vest that is expected.
    within = [(away(vest), order, vest) for order, vests in enumerate((outlook.get("upcoming") or [], outlook.get("vests") or [])) for vest in vests]
    within = [found for found in within if found[0] <= VEST_MATCH_DAYS]
    if not within:
        return "no_vest", None
    _, read, vest = min(within, key=lambda found: found[:2])
    return ("vested", None) if read else ("upcoming", vest)


def fill_find(rows: list[list[Any]], label: str) -> tuple[int, int] | str:
    """Where `label` is in a tab's cells, as (row, column): "no_label" when it isn't there, "ambiguous" when it is there more than once."""
    wanted = fill_text(label)
    found = [(r, c) for r, row in enumerate(rows) for c, value in enumerate(row) if isinstance(value, str) and fill_text(value) == wanted]
    if len(found) == 1:
        return found[0]
    return "ambiguous" if found else "no_label"


def fill_amount(rows: list[list[Any]], row: int, column: int) -> tuple[bool, float | None]:
    """
    A cell as something that may be written over: (True, its number), or (True, None) when it is
    empty. (False, None) for a formula, words, or a tick box. `rows` holds formulas as written
    ("=SUM(…)"), so one is told from the number it comes to.
    """
    value = rows[row][column] if row < len(rows) and column < len(rows[row]) else ""
    if value is None or value == "":
        return True, None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return True, float(value)
    return False, None


def fill_same(a: float | None, b: float | None) -> bool:
    return (a is None and b is None) or (a is not None and b is not None and abs(a - b) < FILL_CENT)


def fill_plan(lines: list[dict[str, Any]], balances: dict[str, float | None], grids: dict[str, list[list[Any]] | None], paused: set[str],
              plans: dict[str, dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    """
    What a fill would do with each line, as its `status`: "change" (with the `cell`, what it holds
    as `before` and the `value` to write), "same", or why it is left alone: "paused",
    "no_account" (one it adds up isn't linked: `missing`), "no_balance", "no_tab", "no_label",
    "ambiguous", "not_a_number", "same_cell" (another line comes to the same cell, which the file
    can't show: two labels side by side, each counting right to the cell between or beyond them;
    neither is written, since which was meant can't be told). `balances` is every linked
    account's by feed key, `grids` each tab's cells with formulas as written (None for a tab the
    sheet hasn't got).

    A line that names a vest (see "stock plan") is worth that vest's shares at today's price, or
    the number of shares when the line says so, by the stock plan's outlook in `plans` (by feed
    key). It is left alone as "vested" once that vest has been read, since what the cell then
    holds is its last estimate, as "no_vest" when the outlook has no vest near that day, and as
    "no_price" when its worth is wanted and Plaid gave no price for the share.
    """
    plan = []
    for line in lines:
        entry: dict[str, Any] = {**line, "cell": None, "row": None, "column": None, "before": None, "value": None, "missing": []}
        plan.append(entry)
        if line["id"] in paused:
            entry["status"] = "paused"
            continue
        entry["missing"] = [key for key in line["accounts"] if key not in balances]
        if entry["missing"]:
            entry["status"] = "no_account"
            continue
        if line.get("vest"):
            found, vest = fill_vest((plans or {}).get(line["vest"]["account"]), line["vest"]["on"])
            if vest is None:
                entry["status"] = found
                continue
            total = vest["shares"] if line["shares"] else vest["value"]
            if total is None:
                # What the shares are worth can't be said without the share's price. How many they are can.
                entry["status"] = "no_price"
                continue
            total = round(total, 2)
        else:
            amounts = [balances[key] for key in line["accounts"]]
            if any(a is None for a in amounts):
                entry["status"] = "no_balance"
                continue
            total = round(sum(amounts), 2)
        # A balance owed is a positive number in the feed; `negative` is for a sheet that keeps debts below zero.
        entry["value"] = -total if line["negative"] and total else total
        rows = grids.get(line["tab"])
        if rows is None:
            entry["status"] = "no_tab"
            continue
        found = fill_find(rows, line["label"])
        if isinstance(found, str):
            entry["status"] = found
            continue
        row, column = found[0], found[1] + line["right"]
        entry["cell"], entry["row"], entry["column"] = f"{column_letters(column)}{row + 1}", row, column
        writable, before = fill_amount(rows, row, column)
        if not writable:
            entry["status"] = "not_a_number"
            continue
        entry["before"] = before
        entry["status"] = "same" if fill_same(before, entry["value"]) else "change"
    aimed: dict[tuple[str, int, int], list[dict[str, Any]]] = {}
    for entry in plan:
        if entry["row"] is not None:
            aimed.setdefault((entry["tab"], entry["row"], entry["column"]), []).append(entry)
    for entries in aimed.values():
        if len(entries) > 1:
            for entry in entries:
                entry["status"], entry["before"] = "same_cell", None
    return plan


def fill_answered(response: Any) -> Any:
    if response.status_code != 200:
        raise HTTPException(*sheet_problem(response.status_code, _google_json(response), _feed_google["account"]))
    return _google_json(response)


def fill_read(session: Any, tabs: list[str]) -> dict[str, list[list[Any]] | None]:
    """The budget sheet's cells for each of `tabs`, formulas as written and numbers as numbers; None for a tab it hasn't got."""
    base = f"{SHEETS_API}/{FINANCE_SHEET_ID}"
    there = grid_titles(fill_answered(session.get(base, params={"fields": "sheets(properties(title,sheetType))"}, timeout=15)))
    grids: dict[str, list[list[Any]] | None] = {tab: None for tab in tabs}
    asked = [tab for tab in tabs if tab in there]
    if asked:
        params = [("ranges", sheet_range(tab)) for tab in asked]
        params += [("valueRenderOption", "FORMULA"), ("dateTimeRenderOption", "SERIAL_NUMBER"), ("majorDimension", "ROWS")]
        ranges = fill_answered(session.get(f"{base}/values:batchGet", params=params, timeout=20)).get("valueRanges", [])
        for tab, answer in zip(asked, ranges):
            grids[tab] = answer.get("values", [])
    return grids


def fill_put(session: Any, cells: list[tuple[str, str, float | None]]) -> None:
    """Writes each (tab, cell, number) into the budget sheet; None empties the cell. RAW, so a number stays a number and the cell keeps its own format."""
    data = [{"range": f"{sheet_range(tab)}!{cell}", "values": [["" if value is None else value]]} for tab, cell, value in cells]
    fill_answered(session.post(f"{SHEETS_API}/{FINANCE_SHEET_ID}/values:batchUpdate", json={"valueInputOption": "RAW", "data": data}, timeout=20))


def fill_balances() -> dict[str, float | None]:
    """Every linked account's balance as last read, by its feed key."""
    return {a["key"]: a["balance"] for institution in bank_institutions() for a in institution["accounts"]}


def fill_paused() -> set[str]:
    return {line for line in state_get(FILL_PAUSED_KEY) or [] if isinstance(line, str)}


def fill_sheet(now: float) -> None:
    """
    Writes the balances relay.db holds into the budget sheet's cells (see "sheet fill") and keeps
    what it changed. The caller holds `_bank_lock`. Never raises: how it went is kept for the app.
    """
    lines, problem = fill_lines()
    if problem or not lines or not FINANCE_SHEET_ID:
        if problem:
            log.warning("bank: the budget sheet wasn't filled: %s", problem["message"])
        state_set(FILL_KEY, {"at": now, "error": "bad_file" if problem else None, "message": problem["message"] if problem else None, "lines": []})
        return
    try:
        session = feed_google()
        plans = {outlook["account"]: outlook for outlook in stock_plans()}
        plan = fill_plan(lines, fill_balances(), fill_read(session, unique([line["tab"] for line in lines])), fill_paused(), plans)
        changes = [
            {"line": p["id"], "tab": p["tab"], "label": p["label"], "right": p["right"], "cell": p["cell"], "before": p["before"], "after": p["value"]}
            for p in plan if p["status"] == "change"
        ]
        if changes:
            fill_put(session, [(c["tab"], c["cell"], c["after"]) for c in changes])

            def keep(c: sqlite3.Connection) -> None:
                c.execute("INSERT INTO sheet_writes (at, changes) VALUES (?,?)", (now, json.dumps(changes)))
                c.execute("DELETE FROM sheet_writes WHERE id NOT IN (SELECT id FROM sheet_writes ORDER BY id DESC LIMIT ?)", (FILL_WRITES_KEPT,))
                c.commit()

            with_db(keep)
            # The app's next read of the budget sheet should have the new numbers, not a minute-old copy.
            _finance_cache["at"] = 0.0
            # How many, never which or how much: the log isn't where the household's balances are kept.
            log.info("bank: wrote %d of the budget sheet's cells", len(changes))
        for p in plan:
            if p["status"] == "change":
                p["status"], p["before"] = "written", p["value"]
        # Each line written, or found already holding its number, is as old as the oldest read of
        # its accounts: an institution Plaid couldn't read keeps its last balance, not a new age.
        read_at = {a["key"]: a["updated"] for institution in bank_institutions() for a in institution["accounts"]}
        typed_seen([{**p, "at": min(read_at[key] for key in p["accounts"])} for p in plan
                    if p["status"] in ("written", "same") and all(read_at.get(key) for key in p["accounts"])], now, None, "bank")
        state_set(FILL_KEY, {"at": now, "error": None, "message": None, "lines": plan})
    except HTTPException as e:
        detail = e.detail if isinstance(e.detail, dict) else {}
        log.warning("bank: the budget sheet wasn't filled: %s", detail.get("error") or e.status_code)
        state_set(FILL_KEY, {"at": now, "error": detail.get("error") or "google_error", "message": detail.get("message"), "lines": []})
    except Exception as e:
        log.warning("bank: the budget sheet wasn't filled: %s", type(e).__name__)
        state_set(FILL_KEY, {"at": now, "error": "google_error", "message": "Couldn't reach Google", "lines": []})


def fill_undo(write_id: int, user: str, now: float) -> None:
    """
    Puts back what one write changed, and pauses its lines. A cell is found by its label again
    (rows may have been inserted since), never where it was: with the label gone or there twice,
    the old address may be some other cell by now, and one that happened to hold the same number
    would be overwritten. A cell found is put back only if it still holds what was written, and
    each change says whether it was (`restored`). The caller holds `_bank_lock`. Raises HTTPException when there is no such write, it was undone
    already, or Google refuses.
    """
    row = with_db(lambda c: c.execute("SELECT changes, undone_at FROM sheet_writes WHERE id=?", (write_id,)).fetchone())
    if not row:
        raise HTTPException(404, {"error": "not_found", "message": "That write isn't kept any more"})
    if row[1]:
        raise HTTPException(409, {"error": "already_undone", "message": "That write was undone already"})
    changes = json.loads(row[0])
    session = feed_google()
    grids = fill_read(session, unique([c["tab"] for c in changes]))
    back = []
    for change in changes:
        rows = grids.get(change["tab"]) or []
        found = fill_find(rows, change["label"])
        change["restored"] = False
        if isinstance(found, str):
            continue
        row, column = found[0], found[1] + change["right"]
        writable, holds = fill_amount(rows, row, column)
        if writable and fill_same(holds, change["after"]):
            back.append((change["tab"], f"{column_letters(column)}{row + 1}", change["before"]))
            change["restored"] = True
    if back:
        fill_put(session, back)
        _finance_cache["at"] = 0.0
    with_db(lambda c: (c.execute("UPDATE sheet_writes SET undone_at=?, undone_by=?, changes=? WHERE id=?", (now, user, json.dumps(changes), write_id)), c.commit()))
    # A balance typed in the app is as old again as it was before; nothing will write it back, so nothing is paused.
    typed_restore([c for c in changes if c.get("typed") and c["restored"]])
    state_set(FILL_PAUSED_KEY, sorted(fill_paused() | {c["line"] for c in changes if not c.get("typed")}))
    log.info("bank: %s undid a write to the budget sheet (%d of %d cells put back)", user, len(back), len(changes))


def fill_status() -> dict[str, Any]:
    """The sheet fill as the app shows it: each line and how the last fill left it, and the newest writes with what each changed."""
    kept = state_get(FILL_KEY) or {}
    lines, problem = fill_lines()
    paused = fill_paused()
    last = {line.get("id"): line for line in kept.get("lines") or [] if isinstance(line, dict)}
    writes = with_db(lambda c: c.execute("SELECT id, at, changes, undone_at, undone_by FROM sheet_writes ORDER BY id DESC LIMIT ?", (FILL_WRITES_SHOWN,)).fetchall())
    return {
        "configured": bool(lines) or bool(problem),
        "checked_at": kept.get("at"),
        "error": "bad_file" if problem else kept.get("error"),
        # What is wrong with the file, for the app to say in its own words; `message` is the relay's.
        "reason": problem["reason"] if problem else None,
        "line": problem["line"] if problem else None,
        "message": problem["message"] if problem else kept.get("message"),
        "service_account": _feed_google["account"] or _finance_google["account"],
        "lines": [
            {
                "id": line["id"], "tab": line["tab"], "label": line["label"], "accounts": line["accounts"],
                # The day of the vest a line is, for one that is a vest rather than a balance.
                "vest_on": (line.get("vest") or {}).get("on"),
                "paused": line["id"] in paused,
                # A line added to the file since the last fill hasn't been tried yet.
                "status": "paused" if line["id"] in paused else (last.get(line["id"]) or {}).get("status") or "waiting",
                "cell": (last.get(line["id"]) or {}).get("cell"),
                "value": (last.get(line["id"]) or {}).get("value"),
                "missing": (last.get(line["id"]) or {}).get("missing") or [],
            }
            for line in lines
        ],
        "writes": [
            {"id": write_id, "at": at, "undone_at": undone_at, "undone_by": undone_by,
             "changes": [{"label": c.get("label"), "tab": c.get("tab"), "cell": c.get("cell"), "before": c.get("before"), "after": c.get("after"),
                          "restored": c.get("restored"),
                          # Who typed it in the app, for a balance typed there rather than read from a bank.
                          "typed_by": c.get("by") if c.get("typed") else None} for c in json.loads(changes)]}
            for write_id, at, changes, undone_at, undone_by in writes
        ],
    }


def plaid_sync_all(now: float | None = None, wait: bool = False) -> bool:
    """
    Every linked institution from Plaid, then the feed sheet and the budget sheet's own cells. One at a time: False when a sync is
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
        fill_sheet(now)
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
    the feed. Every hour in between: what was bought on the cards (see "budget"). And the day's
    copies of what isn't kept any other way (see "finance history").
    """
    while True:
        time.sleep(BANK_CHECK_SECONDS)
        finance_history_keep(time.time())
        try:
            if not plaid_on():
                continue
            # A sign-in finished on Plaid's page that the app never came back to collect.
            plaid_collect_links()
            last = float((state_get(PLAID_SYNCED_KEY) or {}).get("at") or 0)
            linked = with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_items").fetchone()[0])
            if linked and bank_sync_due(time.time(), last, household_zone()):
                plaid_sync_all()
            elif linked and transactions_due(time.time(), float((state_get(PLAID_TXN_SYNCED_KEY) or {}).get("at") or 0)):
                plaid_transactions_sync_all()
        except Exception:
            log.exception("bank sync failed")


def bank_status(now: float | None = None) -> dict[str, Any]:
    """What the app's bank sync page shows: the institutions and their accounts, when they were last read and are next, how the feed sheet's last write went, and what was written into the budget sheet (see "sheet fill")."""
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
        "fill": fill_status(),
        "stock_plans": stock_plans(shown=True),
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
    # Opening the page is the moment someone looks for what they just linked: collect it now
    # rather than at the next check, if the app lost track of it (see `plaid_collect_links`).
    if plaid_on() and plaid_collect_links():
        return {**bank_status(), "syncing": True}
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


def bank_busy() -> HTTPException:
    return HTTPException(503, {"error": "busy", "message": "The accounts are being read just now. Try again in a minute."})


@app.delete("/finance/bank/institutions/{item_id}")
def delete_bank_institution(item_id: str, request: Request, response: Response) -> dict[str, Any]:
    """Unlinks an institution: Plaid is told to forget it, and its token, accounts and feed rows go. What was spent and how it all stood stays (see "finance history")."""
    user = require_bank(request, response)
    # Behind any sync under way, and ahead of the next: a sync that read this institution before
    # it was forgotten would otherwise store its accounts again, and write them back to the feed.
    if not _bank_lock.acquire(timeout=BANK_UNLINK_WAIT_SECONDS):
        raise bank_busy()
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
            # What is still pending may never post, and nothing will now say so.
            c.execute("DELETE FROM plaid_transactions WHERE item_id=? AND pending=1", (item_id,))
            c.execute(
                "UPDATE plaid_transactions SET archived_at=?, card=COALESCE(card, (SELECT key FROM plaid_feed_keys k WHERE k.account_id = plaid_transactions.account_id)) WHERE item_id=?",
                (time.time(), item_id),
            )
            for table in ("plaid_stock_events", "plaid_holdings", "plaid_accounts", "plaid_items"):
                c.execute(f"DELETE FROM {table} WHERE item_id=?", (item_id,))
            plaid_assign_keys(c)
            c.commit()

        with_db(forget)
        log.info("bank: %s unlinked %s", user, row[1] or item_id[:8])
        write_bank_feed(time.time())
    finally:
        _bank_lock.release()
    return bank_status()


@app.post("/finance/bank/fill/writes/{write_id}/undo")
def post_bank_fill_undo(write_id: int, request: Request, response: Response) -> dict[str, Any]:
    """
    Undoes one write to the budget sheet's cells and pauses its lines (see `fill_undo`). Needs no
    Plaid: a balance typed in the app is a write too (see "typed balances").
    """
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    if not _bank_lock.acquire(timeout=BANK_UNLINK_WAIT_SECONDS):
        raise bank_busy()
    try:
        fill_undo(write_id, user, time.time())
    except HTTPException:
        raise
    except Exception as e:
        log.warning("bank: a write to the budget sheet wasn't undone: %s", type(e).__name__)
        raise HTTPException(502, {"error": "google_error", "message": "Couldn't reach Google"}) from e
    finally:
        _bank_lock.release()
    return bank_status()


@app.post("/finance/bank/fill/lines")
def post_bank_fill_line(body: FillLine, request: Request, response: Response) -> dict[str, Any]:
    """Pauses a line of the sheet fill, or resumes it. A line resumed is written at once, from the balances already read."""
    user = require_bank(request, response)
    if body.line not in {line["id"] for line in fill_lines()[0]}:
        raise HTTPException(404, {"error": "not_found", "message": "The sheet fill has no such line"})
    if not _bank_lock.acquire(timeout=BANK_UNLINK_WAIT_SECONDS):
        raise bank_busy()
    try:
        paused = fill_paused()
        state_set(FILL_PAUSED_KEY, sorted(paused | {body.line} if body.paused else paused - {body.line}))
        log.info("bank: %s %s a line of the sheet fill", user, "paused" if body.paused else "resumed")
        if not body.paused:
            fill_sheet(time.time())
    finally:
        _bank_lock.release()
    return bank_status()


# ---------------------------------------------------------------- typed balances
#
# Not every balance comes from a bank Plaid can read: a small 401(k) record keeper, a pension, a
# health savings account at a payroll provider, what the house is worth. Those stay typed in the
# budget sheet, and whoever looks after them logs in now and then and copies the number over. The
# app can do the typing instead (`PUT /finance/sheet/value`): it sends the balance it showed and
# the new one, and the relay writes the new one into that cell of the budget sheet.
#
# The cell is found the sheet fill's way (see "sheet fill"): by its row's label and how many
# columns to the right of it the number sits, never by an address, and only a typed number or an
# empty cell is written over. On top of that, the cell must still hold the number the app showed:
# with the sheet changed since the app read it, the write is refused rather than made over
# someone's edit. A cell the sheet fill writes each morning isn't typed into either, since the
# next sync would overwrite it.
#
# Every balance also has an age. Google keeps no time per cell, so the relay keeps its own
# (`sheet_values`): when a balance was typed in the app, or someone said it was still right, or
# the sheet fill wrote it or found it already there. `GET /finance/sheet` sends them along
# (`updates`), and the app says how old each typed balance is. A typed write is kept with the
# sheet fill's (`sheet_writes`), so it shows under "What was changed" and can be undone there.

# A balance beyond this is a slip of the keyboard, not money the household has.
TYPED_MAX = 1e12


class SheetValue(BaseModel):
    # The balance's row: its tab, the words in its label cell, and how many columns to the right of
    # the label the number sits (as a line of the sheet fill names one). A label in the tab twice
    # isn't guessed between: an undo finds the cell by its label again, and couldn't.
    tab: str
    label: str
    right: int = 1
    # What the app showed; the write is refused when the cell holds anything else. None for an empty cell.
    expected: float | None = None
    value: float


def typed_id(tab: str, label: str, right: int) -> str:
    """A balance's id, as a line of the sheet fill has it (`fill_lines`): tab|label|right."""
    return f"{tab.strip()}|{fill_text(label)}|{right}"


def typed_seen(entries: list[dict[str, Any]], now: float, by: str | None, source: str) -> None:
    """Marks each balance (its `tab`, `label`, `right` and `value`) as right as of its own `at`, or `now`."""
    if not entries:
        return

    def keep(c: sqlite3.Connection) -> None:
        c.executemany(
            "INSERT OR REPLACE INTO sheet_values (cell, tab, label, right, value, at, by, source) VALUES (?,?,?,?,?,?,?,?)",
            [(typed_id(e["tab"], e["label"], e["right"]), e["tab"], e["label"], e["right"], e["value"], e.get("at", now), by, source) for e in entries],
        )
        c.commit()

    with_db(keep)


def typed_restore(changes: list[dict[str, Any]]) -> None:
    """Gives each balance whose typed write was undone back the age it had before it (`was`), or none."""
    def restore(c: sqlite3.Connection) -> None:
        for change in changes:
            was = change.get("was")
            if was:
                c.execute(
                    "INSERT OR REPLACE INTO sheet_values (cell, tab, label, right, value, at, by, source) VALUES (?,?,?,?,?,?,?,?)",
                    (change["line"], change["tab"], change["label"], change["right"], was.get("value"), was["at"], was.get("by"), was["source"]),
                )
            else:
                c.execute("DELETE FROM sheet_values WHERE cell=?", (change["line"],))
        c.commit()

    if changes:
        with_db(restore)


def typed_updates() -> list[dict[str, Any]]:
    """When each balance was last known to be right, as `GET /finance/sheet` sends it."""
    rows = with_db(lambda c: c.execute("SELECT cell, tab, label, right, value, at, by, source FROM sheet_values ORDER BY cell").fetchall())
    return [{"id": cell, "tab": tab, "label": label, "right": right, "value": value, "at": at, "by": by, "source": source}
            for cell, tab, label, right, value, at, by, source in rows]


def typed_filled() -> list[dict[str, Any]]:
    """The balances the sheet fill writes every morning: its lines that aren't paused. A typed number there would be written over."""
    paused = fill_paused()
    return [{"tab": line["tab"], "label": line["label"], "right": line["right"]} for line in fill_lines()[0] if line["id"] not in paused]


def typed_write(body: SheetValue, user: str, now: float) -> dict[str, Any]:
    """
    Types `body.value` into the budget sheet's cell for the balance `body` names (see "typed
    balances") and marks it right as of `now`. The same number as the cell holds writes nothing
    and only marks it. The caller holds `_bank_lock`, so a sync can't fill the sheet meanwhile.
    Raises HTTPException with why when the cell can't be written.
    """
    if not FINANCE_SHEET_ID:
        raise HTTPException(503, {"error": "not_configured", "message": "FINANCE_SHEET_ID isn't set on the relay"})
    tab, label = body.tab.strip(), " ".join(body.label.split())
    if not tab or not fill_text(label) or not 1 <= body.right <= 26:
        raise HTTPException(422, {"error": "bad_request", "message": "A balance needs its tab, its label and how far right of it the number is"})
    if not math.isfinite(body.value) or abs(body.value) >= TYPED_MAX:
        raise HTTPException(422, {"error": "bad_value", "message": "That isn't a balance"})
    line = typed_id(tab, label, body.right)
    if line in {fill["id"] for fill in fill_lines()[0]} and line not in fill_paused():
        raise HTTPException(409, {"error": "bank_filled", "message": "The bank sync fills this balance in every morning"})
    session = feed_google()
    rows = fill_read(session, [tab])[tab]
    if rows is None:
        raise HTTPException(404, {"error": "no_tab", "message": f"The budget sheet has no tab {tab}"})
    found = fill_find(rows, label)
    if isinstance(found, str):
        raise HTTPException(404 if found == "no_label" else 409, {"error": found, "message": f"The label {label} is not in {tab} once"})
    row, column = found[0], found[1] + body.right
    cell = f"{column_letters(column)}{row + 1}"
    writable, before = fill_amount(rows, row, column)
    if not writable:
        raise HTTPException(409, {"error": "not_a_number", "message": f"{tab}!{cell} holds a formula or words, not a typed number"})
    if not fill_same(before, body.expected):
        # The app reads the sheet again to show what the cell holds now: not the minute-old copy.
        _finance_cache["at"] = 0.0
        raise HTTPException(409, {"error": "changed", "message": "The sheet has changed since it was read", "value": before})
    value = round(body.value, 2)
    written = not fill_same(before, value)
    if written:
        kept = with_db(lambda c: c.execute("SELECT value, at, by, source FROM sheet_values WHERE cell=?", (line,)).fetchone())
        was = {"value": kept[0], "at": kept[1], "by": kept[2], "source": kept[3]} if kept else None
        fill_put(session, [(tab, cell, value)])
        change = {"line": line, "tab": tab, "label": label, "right": body.right, "cell": cell, "before": before, "after": value,
                  "typed": True, "by": user, "was": was}

        def keep(c: sqlite3.Connection) -> None:
            c.execute("INSERT INTO sheet_writes (at, changes) VALUES (?,?)", (now, json.dumps([change])))
            c.execute("DELETE FROM sheet_writes WHERE id NOT IN (SELECT id FROM sheet_writes ORDER BY id DESC LIMIT ?)", (FILL_WRITES_KEPT,))
            c.commit()

        with_db(keep)
        # The app reads the sheet again straight after, and should find the new number there.
        _finance_cache["at"] = 0.0
    typed_seen([{"tab": tab, "label": label, "right": body.right, "value": value}], now, user, "app")
    # Who and which, never how much: the log isn't where the household's balances are kept.
    log.info("finance: %s %s a balance in %s", user, "typed" if written else "confirmed", tab)
    return {"id": line, "cell": cell, "before": before, "value": value, "written": written, "at": now, "by": user}


@app.put("/finance/sheet/value")
def put_finance_sheet_value(body: SheetValue, request: Request, response: Response) -> dict[str, Any]:
    """Types a balance into the budget sheet, or says it is still right (see "typed balances")."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    if not _bank_lock.acquire(timeout=BANK_UNLINK_WAIT_SECONDS):
        raise bank_busy()
    try:
        return typed_write(body, user, time.time())
    except HTTPException:
        raise
    except Exception as e:
        log.warning("finance: a balance wasn't typed: %s", type(e).__name__)
        raise HTTPException(502, {"error": "google_error", "message": "Couldn't reach Google"}) from e
    finally:
        _bank_lock.release()


# ---------------------------------------------------------------- finance history
#
# The months are to be set side by side for years (docs/finance.md, "What is kept"), and most of
# what they'd need is overwritten as it changes. So the relay keeps it as it goes:
#
# - Every purchase stays once read. Plaid can't be asked again for what has passed out of its reach,
#   so unlinking a card or closing it keeps what was bought on it, by the card's key, and it goes on
#   counting in the months it was bought in.
# - Each account's balances and each holding, one row per day (`plaid_keep_day`).
# - The budget's plan as it stood in each month (`budget_plan_keep`): a limit or a line changed
#   today isn't what last spring was held to.
# - The budget sheet, each day it differs from the last copy (`finance_sheet_keep`).
# - A copy of relay.db each night, on another disk (`relay_backup`).
#
# Nothing here is worked out ahead: comparing months is done from the rows when it's asked for, so a
# purchase sorted differently later changes the months it was in.

FINANCE_KEPT_KEY = "finance_kept"
# Where the nightly copies go, a disk other than relay.db's. Unset: there are none.
RELAY_BACKUP_DIR = os.environ.get("RELAY_BACKUP_DIR", "")
# The copies kept: the last few nights, and each month's first for good.
RELAY_BACKUP_NIGHTS = 14


def household_day(now: float) -> str:
    """The day it is on the household's clock (UTC when unknown), as YYYY-MM-DD."""
    return datetime.fromtimestamp(now, household_zone() or timezone.utc).date().isoformat()


def budget_plan_keep(config: dict[str, Any], now: float, replace: bool = True) -> None:
    """
    What the budget holds this month to, as things stand: a later month's changes never reach a
    month that has ended. Each save replaces it. The daily call only starts a month nothing was
    saved in yet (`replace` False), so settings it read just before a save can't undo that save.
    """
    plan = {name: config[name] for name in ("people", "limits", "roles", "card_paid_lines", "sheet")}
    month = household_day(now)[:7]
    verb = "INSERT OR REPLACE" if replace else "INSERT OR IGNORE"
    with_db(lambda c: (c.execute(f"{verb} INTO budget_plans VALUES (?,?,?)", (month, json.dumps(plan, sort_keys=True), now)), c.commit()))


def finance_sheet_keep(now: float) -> None:
    """Today's copy of the budget sheet's tabs, unless they are as the last copy had them."""
    if not FINANCE_SHEET_ID:
        return
    tabs = json.dumps(finance_sheet(False)["sheets"], sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(tabs.encode()).hexdigest()
    day = household_day(now)

    def write(c: sqlite3.Connection) -> None:
        last = c.execute("SELECT digest FROM finance_sheet_days ORDER BY day DESC LIMIT 1").fetchone()
        if not last or last[0] != digest:
            c.execute("INSERT OR REPLACE INTO finance_sheet_days VALUES (?,?,?,?)", (day, now, digest, zlib.compress(tabs.encode())))
            c.commit()

    with_db(write)


def relay_backup(now: float) -> None:
    """
    Tonight's copy of relay.db in RELAY_BACKUP_DIR, whole and checked or not at all, and the old
    copies cleared away. It holds what relay.db does, Plaid's tokens among them: only the relay may read it.
    """
    if not RELAY_BACKUP_DIR:
        return
    os.makedirs(RELAY_BACKUP_DIR, exist_ok=True)
    os.chmod(RELAY_BACKUP_DIR, 0o700)
    path = os.path.join(RELAY_BACKUP_DIR, f"relay-{household_day(now)}.db")
    partial = f"{path}.partial"
    try:
        source, copy = sqlite3.connect(DB_PATH), sqlite3.connect(partial)
        try:
            source.backup(copy)
            checked = copy.execute("PRAGMA quick_check").fetchone()[0]
        finally:
            source.close()
            copy.close()
        if checked != "ok":
            raise ValueError(f"the copy of relay.db fails its check: {checked}")
        os.chmod(partial, 0o600)
        os.replace(partial, path)
    finally:
        if os.path.exists(partial):
            os.remove(partial)
    copies = sorted(f for f in os.listdir(RELAY_BACKUP_DIR) if re.fullmatch(r"relay-\d{4}-\d{2}-\d{2}\.db", f))
    firsts = {min(f for f in copies if f[:13] == month) for month in {f[:13] for f in copies}}
    for name in copies[:-RELAY_BACKUP_NIGHTS]:
        if name not in firsts:
            os.remove(os.path.join(RELAY_BACKUP_DIR, name))


def finance_history_keep(now: float) -> None:
    """
    What is owed once a day, on the first check of the household's day. One that fails is logged and
    has to wait for tomorrow: the next day's copy has what it would have held, and a sheet that
    can't be read isn't asked for every few minutes.
    """
    try:
        day = household_day(now)
        if (state_get(FINANCE_KEPT_KEY) or {}).get("day") == day:
            return
        state_set(FINANCE_KEPT_KEY, {"day": day})
    except Exception:
        log.exception("finance history: couldn't tell whether today's was kept")
        return
    for keep in (lambda: budget_plan_keep(budget_config(), now, replace=False), lambda: finance_sheet_keep(now), lambda: relay_backup(now)):
        try:
            keep()
        except Exception as e:
            # Not the detail of what Google answered, which may quote the sheet.
            log.warning("finance history: one of today's copies wasn't kept: %s", type(e).__name__)


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
# card belongs to whoever the bank says made it, or to whoever its merchant was said to always
# be, or to nobody yet: someone then tags it in the app, and a tag made by hand outranks
# everything else. How a bank says who made it differs: American Express names the cardholder
# ("SARAH GAN 1006"), Capital One gives only the last four digits of the card that was used
# ("1203"). So each such mark on a card can be said once to be one person's (the card's
# "holders"), and a mark that holds a person's name needs no telling.
#
# Across that, each purchase can also be put in one of the budget sheet's lines that are paid by
# card ("Groceries", $600 a month), so the month shows each line's spending against what the sheet
# plans for it. It goes there by hand, or by its merchant having been said to always go there, or
# on its own: by what Plaid says it is (a supermarket for a line called Groceries), by its shop
# being one everybody files there (Costco), or by the line being named after the shop
# (`budget_line`).
#
# The limits, the roles and the numbers the relay can't work out for itself (the month's
# take-home, the bills that aren't paid by card, and what each card-paid line plans for, all of
# which only the app reads out of the budget sheet) are one JSON value in `state`. Past the limits, and past what take-home leaves
# after those bills, the phones of the people who may see the finances are told (`budget_check`).
#
# Only credit cards' transactions are kept, and never in the log: a purchase is nobody's business
# but the household's.
#
# Every bank account's transactions are kept too, what comes in as well as what goes out, so the
# household can ask about them (`finance_transactions`). The budget stays the cards': nothing of a
# bank account's counts toward its spending, its limits or its alerts.

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
# More expense lines than any budget sheet has.
BUDGET_MAX_LINES = 200
BUDGET_ALERT_NAMES = ("total", "savings", "buckets", "lines")
# A purchase put in none of the lines, by hand or by its merchant's rule (a line is never named this: a sheet's line has a name).
BUDGET_NO_LINE = ""
# The months before this one whose average is a line's "usual", once at least two of them spent anything.
BUDGET_USUAL_MIN_MONTHS = 2
# What finds a card-paid line's purchases on its own: a line whose name matches the first pattern
# takes the purchases Plaid files under one of its categories (primary or detailed), and those from
# a shop whose name starts with one of the third's. The first line that takes a purchase has it.
BUDGET_LINE_GUESSES: tuple[tuple[re.Pattern[str], frozenset[str], re.Pattern[str] | None], ...] = tuple(
    (re.compile(name, re.IGNORECASE), frozenset(categories), re.compile(shops) if shops else None)
    for name, categories, shops in (
        (r"grocer|supermarket|food at home|^food$", {"FOOD_AND_DRINK_GROCERIES"},
         r"^(costco|sams club|bjs wholesale|safeway|trader joes|whole foods|kroger|albertsons|sprouts|aldi|lucky|raleys|wegmans|publix|heb|vons|ralphs|qfc|fred meyer|grocery outlet|instacart)\b"),
        (r"dining|restaurant|eating out|take ?out|food out|fast food", {"FOOD_AND_DRINK_RESTAURANT", "FOOD_AND_DRINK_FAST_FOOD"}, r"^(doordash|uber eats|grubhub|postmates)\b"),
        (r"coffee|cafe", {"FOOD_AND_DRINK_COFFEE"}, None),
        (r"alcohol|liquor|wine|beer", {"FOOD_AND_DRINK_BEER_WINE_AND_LIQUOR"}, None),
        (r"meal ?kit", set(), r"^(hellofresh|hello fresh|blue apron|home chef|factor|green chef|everyplate|sunbasket)\b"),
        (r"\bgas\b|fuel|petrol", {"TRANSPORTATION_GAS"}, None),
        (r"stream|subscription", {"ENTERTAINMENT_TV_AND_MOVIES", "ENTERTAINMENT_MUSIC_AND_AUDIO"},
         r"^(netflix|hulu|spotify|disney|max|hbo|youtube|apple tv|peacock|paramount|audible)\b"),
        (r"entertainment", {"ENTERTAINMENT"}, None),
        (r"cloth|apparel", {"GENERAL_MERCHANDISE_CLOTHING_AND_ACCESSORIES"}, None),
        (r"\bpets?\b|\bdogs?\b|\bcats?\b", {"GENERAL_MERCHANDISE_PET_SUPPLIES", "MEDICAL_VETERINARY_SERVICES"}, None),
        (r"pharmac|medical|health|doctor", {"MEDICAL"}, None),
        (r"gym|fitness", {"PERSONAL_CARE_GYMS_AND_FITNESS_CENTERS"}, None),
        (r"hair|beauty|personal care", {"PERSONAL_CARE_HAIR_AND_BEAUTY"}, None),
        (r"travel|vacation|holiday", {"TRAVEL"}, None),
        (r"transit|parking|rideshare|uber|lyft", {"TRANSPORTATION_PUBLIC_TRANSIT", "TRANSPORTATION_PARKING", "TRANSPORTATION_TAXIS_AND_RIDE_SHARES"}, None),
        (r"home improvement|hardware|garden", {"HOME_IMPROVEMENT"}, None),
        (r"child ?care|daycare", {"GENERAL_SERVICES_CHILDCARE"}, None),
        (r"education|tuition|school", {"GENERAL_SERVICES_EDUCATION"}, None),
        (r"gifts?|donation|charity", {"GENERAL_MERCHANDISE_GIFTS_AND_NOVELTIES", "GOVERNMENT_AND_NON_PROFIT_DONATIONS"}, None),
        # The bills, each by its kind before any utility line takes it. Plaid files some providers
        # under another kind (AT&T's internet as telephone), so the provider's name says more.
        (r"internet|wi-?fi|broadband|\bcable\b", {"RENT_AND_UTILITIES_INTERNET_AND_CABLE"},
         r"^(at t|comcast|xfinity|sonic|spectrum|cox|frontier|google fiber|starlink|astound|wave|optimum|centurylink)\b"),
        (r"phone|mobile|\bcell\b|wireless", {"RENT_AND_UTILITIES_TELEPHONE"},
         r"^(t mobile|verizon|mint mobile|visible|cricket|google fi|metro by|us mobile|boost mobile|consumer cellular)\b"),
        (r"water|sewer|trash|garbage", {"RENT_AND_UTILITIES_WATER", "RENT_AND_UTILITIES_SEWAGE_AND_WASTE_MANAGEMENT"}, None),
        # Not "gas" alone: a line of that name is fuel (above).
        (r"electric|power|energy|gas (and|&) electric|\bpg ?& ?e\b", {"RENT_AND_UTILITIES_GAS_AND_ELECTRICITY"}, None),
        (r"financial|advis|planner|wealth", set(),
         r"^(cetera|ameriprise|edward jones|lpl|raymond james|merrill|betterment|wealthfront|facet|vanguard personal)\b"),
        (r"utilit|electric|internet|phone", {"RENT_AND_UTILITIES"}, None),
        (r"\bloans?\b", {"LOAN_PAYMENTS"}, None),
        (r"insurance", {"GENERAL_SERVICES_INSURANCE"}, None),
    )
)
# The share of the month's limit at which the phones are first told, and then told it is passed.
BUDGET_TOTAL_PERCENTS = (80, 100)
BUDGET_HISTORY_MONTHS = 6
# The longest note a purchase can carry: a line or two, not a document.
BUDGET_NOTE_MAX = 280
BUDGET_TOP_MERCHANTS = 8
# The accounts whose withdrawals are kept beside the cards' purchases, for the bills paid from
# them (see `budget_checking`), as SQL on plaid_accounts; and the state key that says their
# institutions were read again from the start, to have what was dropped before they were kept.
TXN_CHECKING = "type = 'depository' AND subtype = 'checking'"
TXN_CHECKING_READ_KEY = "txn_checking_read"
# The state key that says every institution was read again from the start, to have what was
# dropped before it was kept: `TXN_DETAILS`, and all that a bank account's transactions are.
TXN_EVERYTHING_READ_KEY = "txn_everything_read"
# The most of `finance_transactions`' matches it lists, the newest; its totals are of them all.
TXN_LIST_MAX = 1000
# What leaves a checking account that isn't a bill: money moved to the household's own savings,
# brokerage or retirement accounts (paying a card off is already no spending, see `txn_kind`).
CHECKING_NOT_BILLS = frozenset({"TRANSFER_OUT_SAVINGS", "TRANSFER_OUT_ACCOUNT_TRANSFER", "TRANSFER_OUT_INVESTMENT_AND_RETIREMENT_FUNDS"})
# What the cards are charged again and again (see `budget_recurring`): how many days apart the
# charges of each rhythm fall (twice that when one was missed in between), how many come in a
# year, how many days past the day the next one was due it has stopped, and how long past that
# day one that stopped stays listed.
RECURRING_GAPS = {"weekly": (5, 9), "biweekly": (12, 16), "monthly": (26, 35), "bimonthly": (54, 68), "quarterly": (84, 98)}
RECURRING_PER_YEAR = {"weekly": 52, "biweekly": 26, "monthly": 12, "bimonthly": 6, "quarterly": 4}
RECURRING_GRACE_DAYS = {"weekly": 4, "biweekly": 6, "monthly": 8, "bimonthly": 12, "quarterly": 15}
RECURRING_STOPPED_DAYS = 90
# How far back recurring charges are looked for: three charges three months apart, one missed between.
RECURRING_HISTORY_MONTHS = 13
# How far a price can move in one step and still be the same charge again: a share of the larger,
# and never closer than a dollar. A bill (electricity, water, the internet) can be any amount, but
# only counts when it is most of what the merchant charged meanwhile.
RECURRING_DRIFT = 0.15
RECURRING_ANY_AMOUNT = ("RENT_AND_UTILITIES",)
RECURRING_SHARE = 0.6

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
    # By card key, then by the bank's mark for a cardholder: whose card that is. A null takes one away.
    holders: dict[str, dict[str, Any]] | None = None
    card_paid_lines: list[str] | None = None
    alerts: dict[str, bool] | None = None
    sheet: dict[str, Any] | None = None


class BudgetTag(BaseModel):
    # "person:<name>", "family", or null to take the tag off.
    bucket: str | None = None
    # Whether every purchase from this merchant goes the same way from now on.
    remember: bool = False


class BudgetNote(BaseModel):
    # What someone wants to remember about a purchase ("a tire for the Tesla"), or null (or nothing but spaces) to take it off.
    note: str | None = None


class BudgetLineTag(BaseModel):
    # One of the card-paid lines by name, "" for none of them, or null to take the tag off.
    line: str | None = None
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


def txn_details(txn: dict[str, Any], category: dict[str, Any]) -> tuple:
    """
    What else Plaid says of a transaction, in `TXN_DETAILS`' order: the bank's own description,
    how it was paid, where, to whom, and how sure Plaid is of its category. Any of them can be
    missing, and only text is kept: this is read back to people, and never decides a sum.
    """
    location = txn.get("location") if isinstance(txn.get("location"), dict) else {}
    parties = [p for p in txn.get("counterparties") or [] if isinstance(p, dict)] if isinstance(txn.get("counterparties"), list) else []
    values = (
        txn.get("original_description"), txn.get("payment_channel"), txn.get("website"), location.get("city"), location.get("region"),
        parties[0].get("name") if parties else None, category.get("confidence_level"),
    )
    return tuple(v.strip() if isinstance(v, str) and v.strip() else None for v in values)


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
        *txn_details(txn, category),
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
                body: dict[str, Any] = {"access_token": access, "count": TXN_PAGE, "options": {"include_original_description": True}}
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
    as one change: a relay stopped halfway asks for the same again. The credit cards' and the bank
    accounts' transactions are kept, and those of nothing else. A tag made by
    hand stays on a transaction Plaid changed, and passes from a pending purchase to the posted
    one that replaces it. A purchase kept from when its card was linked before gives way to the
    same one read again (`txn_take_over`). Answers how many rows changed.
    """
    def write(c: sqlite3.Connection) -> int:
        kept = dict(c.execute("SELECT account_id, type FROM plaid_accounts WHERE item_id=? AND type IN ('credit', 'depository')", (item_id,)).fetchall())
        keys = dict(c.execute("SELECT account_id, key FROM plaid_feed_keys").fetchall())
        changed = 0
        for txn in pulled["added"] + pulled["modified"]:
            row = txn_row(txn, item_id, now)
            if row is None or row[2] not in kept:
                continue
            before = c.execute("SELECT pending FROM plaid_transactions WHERE txn_id=?", (row[0],)).fetchone()
            c.execute(
                "INSERT INTO plaid_transactions (txn_id, item_id, account_id, date, posted, amount, currency, name, merchant, merchant_key, category,"
                " category_detail, pending, pending_id, owner, kind, updated, " + ", ".join(TXN_DETAILS) + ", card, account_type) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                " ON CONFLICT(txn_id) DO UPDATE SET " + ", ".join(f"{column}=excluded.{column}" for column in TXN_DETAILS) + ", item_id=excluded.item_id,"
                " account_id=excluded.account_id, date=excluded.date, posted=excluded.posted,"
                " amount=excluded.amount, currency=excluded.currency, name=excluded.name, merchant=excluded.merchant, merchant_key=excluded.merchant_key,"
                " category=excluded.category, category_detail=excluded.category_detail, pending=excluded.pending, pending_id=excluded.pending_id,"
                " owner=excluded.owner, kind=excluded.kind, updated=excluded.updated, card=excluded.card, account_type=excluded.account_type, removed_at=NULL, archived_at=NULL",
                (*row, keys.get(row[2]), kept[row[2]]),
            )
            changed += 1
            # Only as it first posts: a change to one already posted has taken over its one already.
            if not row[12] and (before is None or before[0]):
                txn_take_over(c, row, keys.get(row[2]))
            # What was set by hand on the pending one carries over only as the posted one is first kept:
            # a tag or note taken off the posted one since must not come back from the pending one.
            if row[13] and before is None:
                for column in ("tag", "line_tag", "note"):
                    was = c.execute(f"SELECT {column}, {column}_by FROM plaid_transactions WHERE txn_id=? AND {column} IS NOT NULL", (row[13],)).fetchone()
                    if was:
                        c.execute(f"UPDATE plaid_transactions SET {column}=?, {column}_by=? WHERE txn_id=? AND {column} IS NULL", (was[0], was[1], row[0]))
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


def txn_take_over(c: sqlite3.Connection, row: tuple, card: str | None) -> None:
    """
    A card unlinked and linked again has its past read afresh under new ids. A purchase that has
    just posted replaces the one kept from before, the same day and amount on the same card (the
    one of the same name, when two match), and takes on its tags and its note. What the new link doesn't reach
    stays. A transaction Plaid had taken back before the unlinking isn't counted, so stands for nothing.
    """
    was = c.execute(
        "SELECT txn_id, tag, tag_by, line_tag, line_tag_by, note, note_by FROM plaid_transactions"
        " WHERE archived_at IS NOT NULL AND removed_at IS NULL AND card=? AND date=? AND ABS(amount - ?) < 0.005"
        " ORDER BY name IS NOT ?, txn_id LIMIT 1",
        (card, row[3], row[5], row[7]),
    ).fetchone()
    if was:
        c.execute(
            "UPDATE plaid_transactions SET tag=COALESCE(tag, ?), tag_by=COALESCE(tag_by, ?), line_tag=COALESCE(line_tag, ?), line_tag_by=COALESCE(line_tag_by, ?),"
            " note=COALESCE(note, ?), note_by=COALESCE(note_by, ?) WHERE txn_id=?",
            (*was[1:], row[0]),
        )
        c.execute("DELETE FROM plaid_transactions WHERE txn_id=?", (was[0],))


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
    if not state_get(TXN_CHECKING_READ_KEY):
        # Until the checking accounts' withdrawals were kept, they were read and dropped: read
        # their institutions from the start once, to have them too. The cards' rows are written
        # over as they were, their tags with them.
        with_db(lambda c: (c.execute("UPDATE plaid_items SET txn_cursor=NULL WHERE item_id IN (SELECT item_id FROM plaid_accounts WHERE " + TXN_CHECKING + ")"), c.commit()))
        state_set(TXN_CHECKING_READ_KEY, {"at": now})
    if not state_get(TXN_EVERYTHING_READ_KEY):
        # The same, once, for every institution: for what it dropped until `TXN_DETAILS` and all of
        # a bank account's transactions were kept.
        with_db(lambda c: (c.execute("UPDATE plaid_items SET txn_cursor=NULL"), c.commit()))
        state_set(TXN_EVERYTHING_READ_KEY, {"at": now})
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
    # On a thread of its own: a first read of months of emails, or a slow model, holds up neither
    # the alerts nor the next bank sync.
    threading.Thread(target=amazon_sync_quietly, name="amazon-sync", daemon=True).start()
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
        "holders": {card: dict(marks) for card, marks in (kept.get("holders") or {}).items() if marks},
        "card_paid_lines": list(kept.get("card_paid_lines") or []),
        "alerts": {name: bool((kept.get("alerts") or {}).get(name)) for name in BUDGET_ALERT_NAMES},
        "sheet": {"take_home": sheet.get("take_home"), "bills_off_card": sheet.get("bills_off_card"),
                  "lines": {str(name): amount for name, amount in (sheet.get("lines") or {}).items() if isinstance(amount, (int, float))},
                  "bills": {str(name): amount for name, amount in (sheet.get("bills") or {}).items() if isinstance(amount, (int, float))}, "at": sheet.get("at")},
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
    if patch.get("holders") is not None:
        for card, marks in patch["holders"].items():
            kept = config["holders"].setdefault(str(card)[:200], {})
            for mark, person in (marks or {}).items():
                if person is None:
                    kept.pop(str(mark), None)
                elif person in config["people"]:
                    kept[str(mark)[:200]] = person
                else:
                    raise _budget_refusal(f"{person} isn't one of the budget's people")
    if patch.get("card_paid_lines") is not None:
        config["card_paid_lines"] = sorted({str(line)[:200] for line in patch["card_paid_lines"]})
    if patch.get("alerts") is not None:
        for name, on in patch["alerts"].items():
            if name not in BUDGET_ALERT_NAMES:
                raise _budget_refusal(f"alerts are {', '.join(BUDGET_ALERT_NAMES)}")
            config["alerts"][name] = bool(on)
    if patch.get("sheet") is not None:
        sheet = patch["sheet"]
        lines, bills = sheet.get("lines") or {}, sheet.get("bills") or {}
        if not isinstance(lines, dict) or not isinstance(bills, dict) or len(lines) > BUDGET_MAX_LINES or len(bills) > BUDGET_MAX_LINES:
            raise _budget_refusal(f"the sheet's lines are up to {BUDGET_MAX_LINES} names, each with an amount")

        def amounts(named: dict[str, Any]) -> dict[str, float]:
            return {str(name)[:200]: amount for name, amount in ((name, _budget_amount(value, f"the {name} line")) for name, value in named.items()) if amount is not None}

        # The bill lines are the sheet's other expense lines, paid from the bank (Cellphone, Rent): what checking pays is put in them.
        config["sheet"] = {
            "take_home": _budget_amount(sheet.get("take_home"), "take-home"), "bills_off_card": _budget_amount(sheet.get("bills_off_card"), "the bills"),
            "lines": amounts(lines), "bills": amounts(bills), "at": now,
        }
    # A role naming someone who has since left the budget goes back to unset.
    config["roles"] = {key: role for key, role in config["roles"].items() if not role.startswith(BUDGET_PERSON) or role in budget_buckets(config["people"])}
    # Likewise a cardholder's mark said to be theirs.
    config["holders"] = {card: marks for card, marks in ((card, {m: p for m, p in marks.items() if p in config["people"]}) for card, marks in config["holders"].items()) if marks}
    state_set(BUDGET_KEY, config)
    budget_plan_keep(config, now)
    return config


def budget_rules() -> dict[str, str]:
    """Each remembered merchant (by `merchant_key`) and the bucket its purchases go in."""
    return dict(with_db(lambda c: c.execute("SELECT merchant_key, bucket FROM budget_rules ORDER BY merchant_key").fetchall()))


def budget_bucket(tag: str | None, role: str, owner: str | None, key: str | None, people: list[str], rules: dict[str, str],
                  holder: str | None = None) -> tuple[str | None, str]:
    """
    Whose a purchase is, and how that is known: a tag made by hand ("manual"); else the card it
    was on being one person's own ("account"); else, on a card two people carry, whoever the
    bank's mark for the cardholder was said to be (`holder`) or the one person it names ("bank");
    else what its merchant was said to always be ("rule"); else the family's when the card is
    the family's ("account"); else nobody's yet (None, "none").
    """
    buckets = budget_buckets(people)
    if tag in buckets:
        return tag, "manual"
    if role.startswith(BUDGET_PERSON) and role in buckets:
        return role, "account"
    if role == BUDGET_SPLIT and holder in people:
        return BUDGET_PERSON + holder, "bank"
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


def budget_line_rules() -> dict[str, str]:
    """Each merchant (by `merchant_key`) said to always go in one of the lines, and that line's name ("" for none of them)."""
    return dict(with_db(lambda c: c.execute("SELECT merchant_key, line FROM budget_line_rules ORDER BY merchant_key").fetchall()))


def budget_line_guess(lines: list[str], key: str | None, category: str | None, detail: str | None) -> str | None:
    """
    The line a purchase goes in on its own, if any: one named after its shop ("Costco" takes
    "COSTCO WHSE #0123"), else one whose name says what kind of spending it is and the purchase
    being that (see `BUDGET_LINE_GUESSES`). A bill from a provider a line knows comes first (AT&T,
    which Plaid files as a telephone bill, in Internet; not Sonic the drive-in), and so does a shop
    of a line known only by its shops (Cetera in Financial services, whatever Plaid calls it). Then Plaid's
    detailed category, which says most: fuel from Costco goes in Gas. Then a shop a line knows by
    name, so a Groceries line still has Costco though Plaid calls it a superstore; then Plaid's
    broad category.
    """
    key = key or ""
    for line in lines:
        named = merchant_key(None, line)
        if len(named) >= 3 and (key == named or key.startswith(named + " ")):
            return line
    guesses = [(line, categories, shops) for line in lines for pattern, categories, shops in BUDGET_LINE_GUESSES if pattern.search(line)]
    # A bill from a provider a line knows, whatever kind Plaid says it is (AT&T, filed as a telephone bill, is the internet's),
    # and a shop of a line known only by its shops (a financial adviser Plaid files as insurance, or as travel).
    for line, categories, shops in guesses:
        if shops is not None and (category == "RENT_AND_UTILITIES" or not categories) and shops.search(key):
            return line
    for line, categories, _ in guesses:
        if detail and detail in categories:
            return line
    for line, categories, shops in guesses:
        # A bill's providers stand for it only when it is a bill: Sonic the drive-in isn't the internet.
        bills_only = bool(categories) and all(c.startswith("RENT_AND_UTILITIES_") for c in categories)
        if shops is not None and shops.search(key) and (category == "RENT_AND_UTILITIES" or not bills_only):
            return line
    for line, categories, _ in guesses:
        if category and category in categories:
            return line
    return None


def budget_line(tag: str | None, key: str | None, category: str | None, detail: str | None, lines: list[str], rules: dict[str, str]) -> tuple[str | None, str]:
    """
    Which of the card-paid lines a purchase is in, and how that is known: a tag made by hand
    ("manual"); else what its merchant was said to always be ("rule"); else what it is on its own
    ("auto", see `budget_line_guess`); else none (None, "none"). A tag or a rule can say "none of
    them" (""), which a guess doesn't overrule. One naming a line no longer paid by card is as if
    it weren't there.
    """
    if tag is not None and (tag == BUDGET_NO_LINE or tag in lines):
        return tag or None, "manual"
    rule = rules.get(key or "")
    if rule is not None and (rule == BUDGET_NO_LINE or rule in lines):
        return rule or None, "rule"
    guess = budget_line_guess(lines, key, category, detail)
    return (guess, "auto") if guess else (None, "none")


def month_days(month: str) -> int:
    return calendar.monthrange(int(month[:4]), int(month[5:]))[1]


def month_before(month: str, months: int = 1) -> str:
    index = int(month[:4]) * 12 + int(month[5:]) - 1 - months
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def budget_cards(roles: dict[str, str], holders: dict[str, dict[str, str]] | None = None, people: list[str] | None = None) -> list[dict[str, Any]]:
    """
    Every linked credit card: what it is, its role in the budget if it has one, whether its
    purchases can be read, and each mark the bank puts on them for who made them (`holders`:
    the mark, how many purchases carry it, and whose it was said to be), the commonest first.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT k.key, i.item_id, i.institution, a.name, a.mask, a.current, a.credit_limit, i.error, i.products, a.account_id"
        " FROM plaid_accounts a JOIN plaid_items i ON i.item_id = a.item_id LEFT JOIN plaid_feed_keys k ON k.account_id = a.account_id"
        " WHERE a.type = 'credit' ORDER BY i.linked_at, i.item_id, a.name, a.account_id"
    ).fetchall())
    marks: dict[str, list[tuple[str, int]]] = {}
    for account_id, owner, count in with_db(lambda c: c.execute(
        "SELECT account_id, owner, COUNT(*) FROM plaid_transactions WHERE removed_at IS NULL AND kind != 'payment' AND owner IS NOT NULL AND owner != ''"
        " GROUP BY account_id, owner ORDER BY COUNT(*) DESC, owner"
    ).fetchall()):
        marks.setdefault(account_id, []).append((owner, count))
    cards = []
    for key, item_id, institution, name, mask, current, limit, error, products, account_id in rows:
        said = (holders or {}).get(key or name) or {}
        cards.append({
            "key": key or name, "institution_id": item_id, "institution": institution or "", "name": name, "mask": mask, "role": roles.get(key or name),
            "balance": current, "limit": limit, "error": error, "needs_relink": error in PLAID_RELINK, "transactions": "transactions" in (products or ""),
            "holders": [{"mark": mark, "count": count, "person": said.get(mark) if said.get(mark) in (people or []) else None} for mark, count in marks.get(account_id, [])],
        })
    return cards


def budget_purchases(first: str, last: str, config: dict[str, Any]) -> list[dict[str, Any]]:
    """
    What counts toward the budget between two days (inclusive), each put in its bucket: every
    purchase and refund on a card that has a role, pending ones too, whether or not the card is
    still linked (but for what was still pending on one that's gone, which nothing will now settle).
    A card being paid off doesn't count, nor a transaction Plaid took back, nor a pending one whose posted one is here.
    Each of Amazon's charges also says what its order was (see "Amazon orders").
    """
    rows = with_db(lambda c: c.execute(
        "SELECT t.txn_id, t.date, t.amount, t.name, t.merchant, t.merchant_key, t.category, t.pending, t.owner, t.kind, t.tag, COALESCE(k.key, t.card), t.category_detail,"
        " t.line_tag, t.note"
        " FROM plaid_transactions t LEFT JOIN plaid_feed_keys k ON k.account_id = t.account_id"
        " WHERE t.removed_at IS NULL AND t.kind != 'payment' AND t.date >= ? AND t.date <= ?"
        # The budget is the cards': what left a checking account is kept for its bills only (see `budget_checking`).
        " AND t.account_id NOT IN (SELECT account_id FROM plaid_accounts WHERE type = 'depository') AND COALESCE(t.account_type, '') != 'depository'"
        " AND (k.key IS NOT NULL OR t.pending = 0)"
        " AND t.txn_id NOT IN (SELECT pending_id FROM plaid_transactions WHERE pending_id IS NOT NULL AND removed_at IS NULL)"
        " ORDER BY t.date DESC, t.amount DESC, t.txn_id",
        (first, last),
    ).fetchall())
    rules = budget_rules()
    line_rules = budget_line_rules()
    lines = config["card_paid_lines"]
    purchases = []
    amazon: set[str] = set()
    for txn_id, date, amount, name, merchant, key, category, pending, owner, kind, tag, card, detail, line_tag, note in rows:
        role = config["roles"].get(card) or ""
        if role in ("", BUDGET_IGNORE):
            continue
        shipment = kind == "spend" and is_amazon_order_charge(name, merchant)
        if shipment:
            amazon.add(txn_id)
        bucket, source = budget_bucket(tag, role, owner, key, config["people"], rules, holder=(config["holders"].get(card) or {}).get(owner or ""))
        line, line_source = budget_line(line_tag, key, category, detail, lines, line_rules)
        purchases.append({
            "id": txn_id, "date": date, "amount": round(amount, 2), "name": merchant or name or "", "merchant": key or None, "category": category,
            "pending": bool(pending), "card": card, "bucket": bucket, "source": source, "kind": kind, "remembered": bool(key) and key in rules,
            "line": line, "line_source": line_source, "line_remembered": bool(key) and key in line_rules,
            # One of Amazon's shipments, told from the charge's own description ("Amazon Prime*1A2B3" is not), mailbox or not.
            "amazon_order": shipment, "note": note,
        })
    amazon_attach(purchases, amazon, first, last)
    amazon_shares(purchases, config["people"])
    return purchases


def amazon_shares(purchases: list[dict[str, Any]], people: list[str]) -> None:
    """
    Shares each Amazon charge whose order was found out between the buckets by what it was for:
    the essentials are the family's, and the rest is the person whose mailbox the order came to
    ("andrew" is Andrew's). What nobody has sorted yet, and the rest when the mailbox is no one
    of the budget's people, stays where the charge would go on its own. A charge someone put in
    a bucket by hand goes there whole. Each such purchase gets its `shares` ({bucket, amount},
    the largest first, in whole cents that add up to it) and is in the bucket of the largest,
    by "amazon".
    """
    for p in purchases:
        order = p.get("amazon")
        if not order or p["source"] == "manual":
            continue
        mailbox = (order.get("mailbox") or "").lower()
        person = next((BUDGET_PERSON + name for name in people if name.lower() == mailbox), None)
        cents: dict[str | None, int] = {}
        for need, bucket in (("essential", BUDGET_FAMILY), ("discretionary", person or p["bucket"]), ("unsorted", p["bucket"])):
            cents[bucket] = cents.get(bucket, 0) + _cents(order.get(need) or 0.0)
        # The order's split is in cents of the charge already; any odd cent goes with the charge's own bucket.
        cents[p["bucket"]] = cents.get(p["bucket"], 0) + _cents(p["amount"]) - sum(cents.values())
        shares = sorted(((bucket, amount) for bucket, amount in cents.items() if amount), key=lambda share: -share[1])
        if not shares:
            continue
        p["shares"] = [{"bucket": bucket, "amount": amount / 100} for bucket, amount in shares]
        p["bucket"], p["source"] = shares[0][0], "amazon"


def purchase_shares(p: dict[str, Any]) -> list[dict[str, Any]]:
    """Where a purchase's money goes: shared out between buckets (an Amazon charge, see `amazon_shares`), or all of it in its one bucket."""
    return p.get("shares") or [{"bucket": p["bucket"], "amount": p["amount"]}]


def budget_checking(first: str, last: str, config: dict[str, Any]) -> list[dict[str, Any]]:
    """
    What left the checking accounts between two days (inclusive) that could be a bill, shaped
    like `budget_purchases`' purchases, for what is paid every month (`budget_recurring`). None of
    it is spending against the budget, which is the cards'. Money moved to the household's own
    accounts and a card paid off are left out. Each is put in the budget sheet's bill line it
    looks like ("Cellphone" for T-Mobile, by `budget_line_guess`), if any.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT t.txn_id, t.date, t.amount, t.name, t.merchant, t.merchant_key, t.category, t.category_detail, k.key, a.name, a.mask"
        " FROM plaid_transactions t JOIN plaid_accounts a ON a.account_id = t.account_id AND a.type = 'depository' AND a.subtype = 'checking'"
        " LEFT JOIN plaid_feed_keys k ON k.account_id = t.account_id"
        " WHERE t.removed_at IS NULL AND t.kind = 'spend' AND t.amount > 0 AND t.date >= ? AND t.date <= ?"
        " AND t.txn_id NOT IN (SELECT pending_id FROM plaid_transactions WHERE pending_id IS NOT NULL AND removed_at IS NULL)"
        " ORDER BY t.date DESC, t.amount DESC, t.txn_id",
        (first, last),
    ).fetchall())
    bills = list(config["sheet"]["bills"])
    return [{
        "id": txn_id, "date": date, "amount": round(amount, 2), "name": merchant or name or "", "merchant": key or None, "category": category,
        "kind": "spend", "card": feed or f"{account} {mask or ''}".strip(), "bucket": None, "line": budget_line_guess(bills, key, category, detail),
        "amazon_order": False, "account": "checking",
    } for txn_id, date, amount, name, merchant, key, category, detail, feed, account, mask in rows if detail not in CHECKING_NOT_BILLS]


def finance_accounts(config: dict[str, Any]) -> list[dict[str, Any]]:
    """
    Every account linked, what becomes of its transactions and how far back those kept of it go,
    by its key in the feed: linked again, an account's id changes and its key doesn't. Then the
    accounts that are gone but left transactions behind (`unlinked`).
    """
    rows = with_db(lambda c: c.execute(
        "SELECT k.key, i.institution, a.name, a.mask, a.type, a.subtype, a.current, i.error, i.products, a.account_id"
        " FROM plaid_accounts a JOIN plaid_items i ON i.item_id = a.item_id LEFT JOIN plaid_feed_keys k ON k.account_id = a.account_id"
        " ORDER BY i.linked_at, i.item_id, a.name, a.account_id"
    ).fetchall())
    labels = {row[9]: row[0] or f"{row[2]} {row[3] or ''}".strip() for row in rows}
    kept: dict[str, list[Any]] = {}
    for key, account_id, kind, count, first, last in with_db(lambda c: c.execute(
        "SELECT COALESCE(k.key, t.card), t.account_id, t.account_type, COUNT(*), MIN(t.date), MAX(t.date) FROM plaid_transactions t"
        " LEFT JOIN plaid_feed_keys k ON k.account_id = t.account_id WHERE t.removed_at IS NULL GROUP BY 1, 2, 3"
    ).fetchall()):
        label = key or labels.get(account_id) or account_id
        was = kept.setdefault(label, [0, first, last, kind])
        was[:3] = [was[0] + count, min(was[1], first), max(was[2], last)]
    accounts = []
    for key, institution, name, mask, kind, subtype, current, error, products, account_id in rows:
        label = labels[account_id]
        count, first, last, _ = kept.pop(label, (0, None, None, None))
        accounts.append({
            "key": label, "institution": institution or "", "name": name, "mask": mask, "type": kind, "subtype": subtype, "balance": current,
            "reads_transactions": kind in ("credit", "depository") and "transactions" in (products or ""), "needs_relink": error in PLAID_RELINK,
            "in_budget": kind == "credit" and (config["roles"].get(label) or "") not in ("", BUDGET_IGNORE),
            "count": count, "first": first, "last": last, "unlinked": False,
        })
    for label, (count, first, last, kind) in sorted(kept.items()):
        accounts.append({
            "key": label, "institution": "", "name": label, "mask": None, "type": kind, "subtype": None, "balance": None, "reads_transactions": False, "needs_relink": False,
            "in_budget": kind != "depository" and (config["roles"].get(label) or "") not in ("", BUDGET_IGNORE), "count": count, "first": first, "last": last, "unlinked": True,
        })
    return accounts


def finance_transactions(start: str | None = None, end: str | None = None, search: str | None = None, category: str | None = None, account: str | None = None,
                         direction: str | None = None, now: float | None = None) -> dict[str, Any]:
    """
    What was read of every card and bank account between two days, inclusive (by default the last
    30, up to today), whatever the budget makes of it: cards outside the budget, a card being paid
    off, money coming into an account and moved between the household's own. An amount is Plaid's:
    positive for money going out. `search` looks through everything said of a transaction,
    `category` through Plaid's category, `account` through the account's name and type, and
    `direction` is "in" or "out". The totals, one for each currency, and the count are of every
    match; the transactions are the newest `TXN_LIST_MAX` of them.
    """
    now = time.time() if now is None else now
    try:
        last = datetime.strptime(end, "%Y-%m-%d").date() if end else datetime.fromtimestamp(now, household_zone() or timezone.utc).date()
        first = datetime.strptime(start, "%Y-%m-%d").date() if start else last - timedelta(days=30)
    except ValueError:
        raise HTTPException(400, {"error": "bad_range", "message": "start and end are YYYY-MM-DD"}) from None
    if direction not in (None, "in", "out"):
        raise HTTPException(400, {"error": "bad_range", "message": "direction is in or out"})
    rows = with_db(lambda c: c.execute(
        "SELECT t.date, t.amount, t.currency, t.name, t.merchant, t.original, t.category, t.category_detail, t.confidence, t.pending, t.kind, k.key, t.card, a.name, a.mask, a.type, a.subtype,"
        " t.channel, t.website, t.city, t.region, t.counterparty, t.note"
        " FROM plaid_transactions t LEFT JOIN plaid_feed_keys k ON k.account_id = t.account_id LEFT JOIN plaid_accounts a ON a.account_id = t.account_id"
        " WHERE t.removed_at IS NULL AND t.date >= ? AND t.date <= ?"
        " AND t.txn_id NOT IN (SELECT pending_id FROM plaid_transactions WHERE pending_id IS NOT NULL AND removed_at IS NULL)"
        " ORDER BY t.date DESC, t.amount DESC, t.txn_id",
        (first.isoformat(), last.isoformat()),
    ).fetchall())

    def has(asked: str, *texts: str | None) -> bool:
        return any(asked.casefold() in text.casefold() for text in texts if text)

    found = []
    totals: dict[str, dict[str, Any]] = {}
    for date, amount, currency, name, merchant, original, kind, detail, confidence, pending, what, key, card, account_name, mask, account_type, subtype, channel, website, city, region, party, note in rows:
        label = key or card or f"{account_name or ''} {mask or ''}".strip()
        if (search and not has(search, name, merchant, original, party, website, city, region, note)) \
                or (category and not has(category.strip().replace(" ", "_"), kind, detail)) \
                or (account and not has(account, label, account_name, subtype, account_type)) \
                or (direction == "in" and amount >= 0) or (direction == "out" and amount <= 0):
            continue
        total = totals.setdefault(currency or "", {"currency": currency, "out": 0.0, "in": 0.0})
        total["out" if amount > 0 else "in"] += abs(amount)
        found.append({
            "date": date, "amount": round(amount, 2), "currency": currency, "name": merchant or name or "", "description": next((d for d in (original, name) if d and d != (merchant or name)), None),
            "category": kind, "category_detail": detail, "confidence": confidence, "pending": bool(pending), "kind": what, "account": label, "account_type": subtype or account_type,
            "channel": channel, "website": website, "city": city, "region": region, "counterparty": party, "note": note,
        })
    return {
        "start": first.isoformat(), "end": last.isoformat(), "count": len(found),
        "totals": sorted(({**t, "out": round(t["out"], 2), "in": round(t["in"], 2)} for t in totals.values()), key=lambda t: -(t["out"] + t["in"])),
        "accounts": finance_accounts(budget_config()), "transactions": found[:TXN_LIST_MAX],
    }


def _month_on(day: Any, months: int = 1) -> Any:
    """The same day `months` months later, or that month's last day when it is shorter."""
    year, month = divmod(day.year * 12 + day.month - 1 + months, 12)
    month += 1
    return day.replace(year=year, month=month, day=min(day.day, calendar.monthrange(year, month)[1]))


def _recurring_due(lastday: Any, rhythm: str) -> Any:
    """When the next charge of a `rhythm` is due after one on `lastday`: a week or two on, or the same day one, two or three months on."""
    months = {"monthly": 1, "bimonthly": 2, "quarterly": 3}.get(rhythm)
    return _month_on(lastday, months) if months else lastday + timedelta(days=7 if rhythm == "weekly" else 14)


def _recurring_chain(charges: list[dict[str, Any]], rhythm: str) -> list[dict[str, Any]]:
    """
    The longest run among a merchant's charges (oldest first) that came one `rhythm` apart, or two
    when one was missed but no more often than one wasn't, at the same price to the cent but for
    one change of price (a bill at any amount); of runs as long, the one that ran latest. The
    change and the misses are counted as the run is built, so another charge of the merchant's at a
    price in between can't stand in for a renewal, nor older charges that miss too often take the
    place of a run that doesn't. A run goes from one of the merchant's names to another only at the
    very same price (Plaid renaming it): a change of price, or a bill's amount, stays with one
    name, so City Power's bills don't run on into City Water's, nor a $30 plan into another's at $32.
    Every two weeks, a run stays on one card: two monthly subscriptions at one price, one on each
    person's card a fortnight apart, are two monthly ones, not one every two weeks.
    """
    low, high = RECURRING_GAPS[rhythm]
    days = [datetime.fromisoformat(c["date"]).date() for c in charges]
    # For each charge, the longest run ending at it by (changes of price, gaps on time less gaps
    # with one missed): its length, and the charge and the state it came from.
    best: list[dict[tuple[int, int], tuple[int, int | None, tuple[int, int] | None]]] = []
    for i, charge in enumerate(charges):
        best.append({(0, 0): (1, None, None)})
        for j in range(i - 1, -1, -1):
            gap = (days[i] - days[j]).days
            if gap > 2 * high:
                break
            if not (low <= gap <= high or 2 * low <= gap <= 2 * high):
                continue
            if rhythm == "biweekly" and charge.get("card") != charges[j].get("card"):
                continue
            one_name = _recurring_name(charge) == _recurring_name(charges[j])
            same = _cents(charge["amount"]) == _cents(charges[j]["amount"]) or (one_name and _recurring_bill(charge) and _recurring_bill(charges[j]))
            if not same and (not one_name or abs(charge["amount"] - charges[j]["amount"]) > max(1.0, RECURRING_DRIFT * max(charge["amount"], charges[j]["amount"]))):
                continue
            for (changes, balance), (length, _, _) in best[j].items():
                state = (changes + (0 if same else 1), balance + (1 if gap <= high else -1))
                if state[0] <= 1 and length + 1 > best[i].get(state, (0, None, None))[0]:
                    best[i][state] = (length + 1, j, (changes, balance))
    ends = [(length, i, -state[0], state) for i, states in enumerate(best) for state, (length, _, _) in states.items() if state[1] >= 0]
    if not ends:
        return []
    _, at, _, state = max(ends)
    chain = []
    while at is not None:
        chain.append(charges[at])
        _, at, state = best[at][state]
    return chain[::-1]


def _recurring_name(charge: dict[str, Any]) -> str:
    """The name a charge's merchant went by this time (its key), as against `recurring_merchant`, what it goes by across its names."""
    return charge["merchant"] or charge["name"].lower()


def _recurring_bill(charge: dict[str, Any]) -> bool:
    """A bill (electricity, water, the internet), which comes every month at whatever it comes to."""
    return (charge["category"] or "") in RECURRING_ANY_AMOUNT


def _recurring_enough(chain: list[dict[str, Any]], rhythm: str, merchants: list[dict[str, Any]]) -> bool:
    """
    Whether a run of charges is something charged again and again. Three or more of the same
    amount in cents, but for one change of price; or, a month apart, two of the very same amount
    ($49.99, not $15.00: two round sums a month apart could be anything). A bill can be any amount,
    three or more times, as long as they are most of what that merchant charged meanwhile, under
    any of the names it went by in the run. A charge missed in between is allowed for, but no
    more often than one isn't: two people's $9.99 subscriptions a fortnight apart are two monthly
    ones, not one weekly one that is always missed.
    """
    cents = [_cents(c["amount"]) for c in chain]
    high = RECURRING_GAPS[rhythm][1]
    days = [datetime.fromisoformat(c["date"]).date() for c in chain]
    missed = sum((b - a).days > high for a, b in zip(days, days[1:]))
    if missed > len(chain) - 1 - missed:
        return False
    if len(chain) == 2:
        return rhythm == "monthly" and cents[0] == cents[1] and cents[0] % 100 != 0
    if len(chain) < 3:
        return False
    if all(_recurring_bill(c) for c in chain):
        names = {c["merchant"] for c in chain}
        meanwhile = [p for p in merchants if p["merchant"] in names and chain[0]["date"] <= p["date"] <= chain[-1]["date"]]
        return len(chain) >= RECURRING_SHARE * len(meanwhile)
    return sum(a != b for a, b in zip(cents, cents[1:])) <= 1


def _recurring_tail(chain: list[dict[str, Any]], rhythm: str, merchants: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    The longest stretch of a run, up to its last charge, that is something charged again and
    again (see `_recurring_enough`); empty when none is. A run that missed too often long ago is
    still what its recent charges say.
    """
    return next((chain[start:] for start in range(len(chain) - 1) if _recurring_enough(chain[start:], rhythm, merchants)), [])


def recurring_merchant(purchase: dict[str, Any]) -> str:
    """
    What a recurring charge's merchant goes by: the first word of its name ("the" passed over).
    Plaid names one merchant "Factor Inc." one week and "Factor75" the next, and hides a month's
    "Google One" as "Google"; two merchants that share a first word still keep apart, by amount.
    """
    words = (purchase["merchant"] or purchase["name"].lower()).split()
    return next((w for w in words if w != "the"), words[0] if words else "")


def budget_recurring(purchases: list[dict[str, Any]], today: Any) -> dict[str, Any]:
    """
    What the cards are charged again and again: each merchant's charges that come every week, two
    weeks, month, two months or three months at the same amount (a bill at any amount), from `purchases` as of `today`. Each
    says what it is, when it last came and when the next is due, what it comes to a month, and
    what it was before when its price changed at the last charge. One whose next charge is well
    overdue has `stopped`, and stays listed a while so a cancellation can be seen to have taken.
    A merchant can have more than one (a TV service and its add-on); a run that stopped where one
    at the same price and rhythm took over from its last charge is that one, its billing day
    moved. Amazon's shipments are
    shopping, not something charged again and again.
    """
    by_merchant: dict[str, list[dict[str, Any]]] = {}
    for p in sorted(purchases, key=lambda p: (p["date"], p["id"])):
        if p["kind"] == "spend" and p["amount"] > 0 and not p.get("amazon_order"):
            by_merchant.setdefault(recurring_merchant(p), []).append(p)
    found = []
    for charges in by_merchant.values():
        left, mine = list(charges), []
        while len(left) >= 2:
            # Each amount's own run first, so a $9.99 subscription isn't lost among the same merchant's other charges.
            amounts: dict[int, list[dict[str, Any]]] = {}
            for c in left:
                amounts.setdefault(_cents(c["amount"]), []).append(c)
            runs = [(rhythm, _recurring_chain(group, rhythm)) for rhythm in RECURRING_GAPS for group in [left, *(g for g in amounts.values() if len(g) >= 2)]]
            runs = [(rhythm, tail) for rhythm, tail in ((rhythm, _recurring_tail(chain, rhythm, charges)) for rhythm, chain in runs) if tail]
            if not runs:
                break
            # A weekly charge also comes once in most months (and a monthly one every three): the longest run is the rhythm. Of two as long, the
            # one whose price never changed: a $9.99 subscription, not it and the Supercharger charge after it.
            rhythm, chain = max(runs, key=lambda run: (len(run[1]), len({_cents(c["amount"]) for c in run[1]}) == 1, run[1][-1]["date"]))
            ids = {c["id"] for c in chain}
            left = [c for c in left if c["id"] not in ids]
            last = chain[-1]
            lastday = datetime.fromisoformat(last["date"]).date()
            due = _recurring_due(lastday, rhythm)
            stopped = (today - due).days >= RECURRING_GRACE_DAYS[rhythm]
            if stopped and (today - due).days > RECURRING_STOPPED_DAYS:
                continue
            bill = _recurring_bill(last)
            # A bill's month is what its last few came to; anything else costs what it was last charged.
            each = sum(c["amount"] for c in chain[-3:]) / len(chain[-3:]) if bill else last["amount"]
            before = chain[-2]["amount"]
            mine.append({
                "merchant": last["merchant"], "name": last["name"], "rhythm": rhythm, "amount": round(last["amount"], 2),
                "was": round(before, 2) if not bill and _cents(before) != _cents(last["amount"]) else None,
                "monthly": round(each * RECURRING_PER_YEAR[rhythm] / 12, 2), "bill": bill,
                "count": len(chain), "first": chain[0]["date"], "last": last["date"], "next": None if stopped else due.isoformat(),
                "stopped": stopped, "card": last["card"], "bucket": last["bucket"], "line": last["line"],
                "account": last.get("account", "card"),
            })
        # A subscription whose billing day moved is one still running, not one that stopped and another that started:
        # the same price at the same rhythm, starting after the other's last charge. One that ran alongside it (the
        # same subscription on the other person's card) is another, and its stopping is said.
        going = [r for r in mine if not r["stopped"]]
        found += [r for r in mine if not (r["stopped"] and any(
            _cents(g["amount"]) == _cents(r["amount"]) and g["rhythm"] == r["rhythm"] and g["first"] > r["last"] for g in going))]
    running = sorted((r for r in found if not r["stopped"]), key=lambda r: (-r["monthly"], r["name"]))
    ended = sorted((r for r in found if r["stopped"]), key=lambda r: (r["last"], r["name"]), reverse=True)
    return {"monthly": round(sum(r["monthly"] for r in running), 2), "charges": running + ended}


def budget_month(month: str | None = None, now: float | None = None) -> dict[str, Any]:
    """
    What the app's Budget page shows for a month (this one when not said): the cards, the
    settings, what was spent in each bucket and on each day, on what and where, every purchase,
    the months before it, and how fresh all of that is; for the month under way, what the cards
    are charged again and again (`budget_recurring`). Days are the household's.
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
    # The months before those for recurring charges (see `budget_recurring`), up to the last of them.
    since, older = month_before(month, RECURRING_HISTORY_MONTHS - 1), month_before(month, BUDGET_HISTORY_MONTHS)
    counted = budget_purchases(f"{earliest}-01", f"{month}-{days:02d}", config)
    purchases = [p for p in counted if p["date"][:7] == month]
    spent_by_month: dict[str, float] = {}
    for p in counted:
        spent_by_month[p["date"][:7]] = spent_by_month.get(p["date"][:7], 0.0) + p["amount"]

    def bucket(bucket_id: str | None, kind: str, name: str | None, limit: float | None) -> dict[str, Any]:
        # An Amazon charge counts for each bucket its shares are in (see `amazon_shares`).
        mine = [(p["id"], share["amount"]) for p in purchases for share in purchase_shares(p) if share["bucket"] == bucket_id]
        return {"id": bucket_id or "unassigned", "kind": kind, "name": name, "spent": round(sum(amount for _, amount in mine), 2), "limit": limit,
                "count": len({txn_id for txn_id, _ in mine})}

    # Each card-paid line against what the sheet plans for it, and what it usually comes to: the
    # average of the months before this one, of those in which the cards were used at all (one
    # whose refunds cancelled its purchases out was still a month of spending).
    earlier = [m for m in (month_before(month, back) for back in range(BUDGET_HISTORY_MONTHS - 1, 0, -1)) if m in spent_by_month]

    def line(name: str | None) -> dict[str, Any]:
        mine = [p for p in purchases if p["line"] == name]
        before = [sum(p["amount"] for p in counted if p["line"] == name and p["date"][:7] == m) for m in earlier]
        usual = round(sum(before) / len(before), 2) if len(before) >= BUDGET_USUAL_MIN_MONTHS else None
        return {"name": name, "planned": config["sheet"]["lines"].get(name) if name else None, "spent": round(sum(p["amount"] for p in mine), 2), "count": len(mine), "usual": usual}

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
        "cards": budget_cards(config["roles"], config["holders"], config["people"]),
        "config": {**config, "rules": [{"merchant": key, "bucket": to} for key, to in rules.items()],
                   "line_rules": [{"merchant": key, "line": to or None} for key, to in budget_line_rules().items()]},
        "savings_line": round(take_home - bills, 2) if take_home is not None and bills is not None else None,
        "spent": round(sum(p["amount"] for p in purchases), 2),
        "pending": round(sum(p["amount"] for p in purchases if p["pending"]), 2),
        "buckets": buckets,
        "lines": [line(name) for name in config["card_paid_lines"]],
        "outside_lines": line(None),
        "daily": [{"date": f"{month}-{d:02d}", "spent": round(by_day.get(f"{month}-{d:02d}", 0.0), 2)} for d in range(1, day + 1)],
        "categories": sorted(({"id": name, "spent": round(total, 2), "count": count} for name, (total, count) in categories.items()), key=lambda c: -c["spent"]),
        "merchants": sorted(({"name": name, "spent": round(total, 2), "count": count} for name, total, count in merchants.values()), key=lambda m: -m["spent"])[:BUDGET_TOP_MERCHANTS],
        "transactions": purchases,
        "history": [{"month": m, "spent": round(spent_by_month.get(m, 0.0), 2), "days": month_days(m)}
                    for m in (month_before(month, back) for back in range(BUDGET_HISTORY_MONTHS - 1, 0, -1))],
        "alerts_sent": sorted(alerts.get("sent") or {}) if alerts.get("month") == month else [],
        "amazon": amazon_month(purchases),
        # What the cards and the checking accounts pay again and again, as of today: only for the month under way.
        "recurring": budget_recurring(
            budget_purchases(f"{since}-01", f"{older}-{month_days(older):02d}", config) + [p for p in counted if p["date"] <= today.isoformat()]
            + budget_checking(f"{since}-01", today.isoformat(), config), today,
        ) if month == this_month else None,
    }


def budget_alerts(budget: dict[str, Any]) -> list[dict[str, Any]]:
    """
    Every line the month has crossed that the settings say to tell the phones about, the mildest
    of each `scope` first: the share of the month's limit, what take-home leaves after the bills
    that aren't on a card, each person's and the family's own limit, and what the budget sheet
    plans for each of its card-paid lines.
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
    if config["alerts"]["lines"]:
        crossed += [{"key": f"line:{line['name']}_100", "scope": f"line:{line['name']}", "kind": "line_100", "spent": line["spent"], "limit": line["planned"],
                     "person": None, "line": line["name"]}
                    for line in budget["lines"] if line["planned"] and line["spent"] > line["planned"]]
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
    if alert["kind"] == "line_100":
        return f"{alert['line']} is over budget", f"{spent} spent of the {limit} the budget sheet plans, with {days}."
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
            "person": top["person"] or "", "line": top.get("line") or "", "days_left": str(budget["days_in_month"] - budget["day"]),
            "notif_id": f"budget-{budget['month']}-{top['key']}",
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


@app.get("/finance/transactions")
def get_finance_transactions(
    request: Request, response: Response, start: str | None = None, end: str | None = None, search: str | None = None, category: str | None = None,
    account: str | None = None, direction: str | None = None,
) -> dict[str, Any]:
    """What was read of every card and bank account between two days, budget or no budget (see `finance_transactions`)."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    return finance_transactions(start, end, search, category, account, direction)


@app.put("/finance/budget/config")
def put_budget_config(body: BudgetPatch, request: Request, response: Response) -> dict[str, Any]:
    """Changes the budget's settings: the people, the limits, each card's role, the alerts, and what the app read from the sheet."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    patch = {name: getattr(body, name, None) for name in ("people", "limits", "roles", "holders", "card_paid_lines", "alerts", "sheet")}
    budget_config_save(patch, time.time())
    if any(patch[name] is not None for name in ("people", "limits", "roles", "holders", "alerts")):
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


@app.put("/finance/budget/transactions/{txn_id}/line")
def put_budget_transaction_line(txn_id: str, body: BudgetLineTag, request: Request, response: Response) -> dict[str, Any]:
    """
    Says which of the card-paid lines a purchase is in ("" for none of them; a null takes that
    back, leaving it to fall where it would on its own), and with `remember` that its merchant's
    always are.
    """
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    if body.line not in (None, BUDGET_NO_LINE) and body.line not in budget_config()["card_paid_lines"]:
        raise HTTPException(400, {"error": "bad_line", "message": "A purchase goes in one of the lines paid by card, or none of them"})
    now = time.time()

    def tag(c: sqlite3.Connection) -> tuple | None:
        row = c.execute("SELECT date, merchant_key FROM plaid_transactions WHERE txn_id=? AND removed_at IS NULL", (txn_id,)).fetchone()
        if row is None:
            return None
        c.execute("UPDATE plaid_transactions SET line_tag=?, line_tag_by=? WHERE txn_id=?", (body.line, user if body.line is not None else None, txn_id))
        if body.remember and row[1]:
            if body.line is not None:
                c.execute("INSERT OR REPLACE INTO budget_line_rules VALUES (?,?,?,?)", (row[1], body.line, user, now))
            else:
                c.execute("DELETE FROM budget_line_rules WHERE merchant_key=?", (row[1],))
        c.commit()
        return row

    row = with_db(tag)
    if row is None:
        raise HTTPException(404, {"error": "not_found", "message": "That purchase isn't there any more"})
    return budget_month(row[0][:7])


@app.put("/finance/budget/transactions/{txn_id}/note")
def put_budget_transaction_note(txn_id: str, body: BudgetNote, request: Request, response: Response) -> dict[str, Any]:
    """Writes a note on a purchase, shown with it to everyone who sees the budget; a null or blank one takes it off. Answers its month."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    user = require_finance_user(request)
    note = " ".join((body.note or "").split()) or None
    if note is not None and len(note) > BUDGET_NOTE_MAX:
        raise _budget_refusal(f"a note is up to {BUDGET_NOTE_MAX} characters")

    def write(c: sqlite3.Connection) -> tuple | None:
        row = c.execute("SELECT date FROM plaid_transactions WHERE txn_id=? AND removed_at IS NULL", (txn_id,)).fetchone()
        if row is None:
            return None
        c.execute("UPDATE plaid_transactions SET note=?, note_by=? WHERE txn_id=?", (note, user if note else None, txn_id))
        c.commit()
        return row

    row = with_db(write)
    if row is None:
        raise HTTPException(404, {"error": "not_found", "message": "That purchase isn't there any more"})
    return budget_month(row[0][:7])


@app.delete("/finance/budget/line-rules")
def delete_budget_line_rule(merchant: str, request: Request, response: Response) -> dict[str, Any]:
    """Forgets that a merchant's purchases always go in one line (see `delete_budget_rule`). The ones put in a line by hand stay as they are."""
    response.headers["Cache-Control"] = FINANCE_CACHE_CONTROL
    require_finance_user(request)
    with_db(lambda c: (c.execute("DELETE FROM budget_line_rules WHERE merchant_key=?", (merchant,)), c.commit()))
    return budget_month()


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


# ---------------------------------------------------------------- Amazon orders

# A card says only "AMAZON MKTPL*2K1" and an amount: groceries and a new game look the same. Amazon
# has no API for a person's orders, but it emails each one ("Ordered: …") and each shipment
# ("Shipped: …"), and Amazon charges the card once per shipment. So every hour, after the cards are
# read, the relay reads those emails from the household's Gmail (read-only), keeps what was ordered,
# matches each Amazon charge to the order or shipment it paid for, and sorts each item into
# essential (groceries, household supplies, medicine, the baby's and the pets' things) or
# discretionary. The sorting is a few word lists first, then the box's own model through Ollama for
# the rest, so no titles leave the house; someone can say otherwise for any item by hand.
#
# Setup (docs/budget.md, "Amazon orders"): a Desktop OAuth client with the Gmail API, published so
# its refresh tokens last, then `python3 relay/gmail_link.py` once per mailbox Amazon writes to.
# AMAZON_GMAIL_CLIENT_ID, AMAZON_GMAIL_CLIENT_SECRET and AMAZON_GMAIL_TOKENS (a JSON object, a
# refresh token by mailbox) go in amazon.env on the box. Off without them.
AMAZON_GMAIL_CLIENT_ID = os.environ.get("AMAZON_GMAIL_CLIENT_ID", "").strip()
AMAZON_GMAIL_CLIENT_SECRET = os.environ.get("AMAZON_GMAIL_CLIENT_SECRET", "").strip()
try:
    AMAZON_GMAIL_TOKENS: dict[str, str] = json.loads(os.environ.get("AMAZON_GMAIL_TOKENS") or "{}")
except ValueError:
    # A mangled amazon.env turns the Amazon orders off rather than the whole relay.
    log.warning("amazon: AMAZON_GMAIL_TOKENS isn't JSON, so Amazon's emails won't be read")
    AMAZON_GMAIL_TOKENS = {}
GMAIL_API = "https://gmail.googleapis.com/gmail/v1/users/me"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
# Every hour reads the emails of the last few months that it hasn't read yet, so a mailbox linked
# today brings its history with it.
AMAZON_LOOKBACK_DAYS = int(os.environ.get("AMAZON_LOOKBACK_DAYS", "120"))
AMAZON_QUERY = ('from:amazon.com {subject:Ordered subject:Shipped subject:"Your Amazon.com order" subject:"Your Amazon order"}'
                f" newer_than:{AMAZON_LOOKBACK_DAYS}d")
# How well inside the search's reach an email has to be for a re-read to count on finding it again.
AMAZON_REREAD_MARGIN_DAYS = 2
# The most new emails one read fetches (the rest wait for the next hour), and the most of the
# search's pages it lists to find them.
AMAZON_MAX_EMAILS = 400
AMAZON_MAX_PAGES = 30
# Between one email and the next, so a first read of months of them stays under Gmail's rate.
AMAZON_FETCH_GAP = 0.3
AMAZON_SYNCED_KEY = "amazon_synced"
# Bumped when the emails are read differently, so the ones read already are read again (see
# `amazon_reread`). 2: Amazon's own categories, since its emails stopped naming the items (2026).
# 3: an email confirming several orders gives each its own categories and total.
AMAZON_PARSER = 3
AMAZON_PARSER_KEY = "amazon_parser"
# A charge comes the day of the order or after it, up to a month later for one that waited for
# stock; a shipment's charge within a few days of its email either way.
AMAZON_ORDER_WINDOW = (-1, 30)
AMAZON_SHIPMENT_WINDOW = (-2, 3)
# How many of an order's charges a split is looked for among, and how many it may be split into.
AMAZON_SPLIT_CANDIDATES = 10
AMAZON_SPLIT_MOST = 4
AMAZON_NEEDS = ("essential", "discretionary")
# What the model is asked about at once.
# Twenty at once, the box's model miscounted; eight it answers. A batch it still answers wrongly is
# asked about one at a time.
AMAZON_MODEL_BATCH = 8
_amazon_lock = threading.Lock()
_gmail_access: dict[str, tuple[str, float]] = {}

_AMAZON_ORDER_ID = re.compile(r"\b((?:\d{3}|D\d{2})-\d{7}-\d{7})\b")
_AMAZON_QUANTITY = re.compile(r"^\s*(?:quantity|qty)\s*:?\s*(\d{1,3})\b", re.IGNORECASE)
_AMAZON_PRICE = re.compile(r"\$\s?(\d{1,3}(?:,\d{3})*\.\d{2})")
_AMAZON_TOTAL = re.compile(r"\b(grand total|order total|(?<!sub)(?<!sub )total)\b[^$\n]{0,30}(?:\n\s*)?\$\s?(\d{1,3}(?:,\d{3})*\.\d{2})", re.IGNORECASE)
_AMAZON_SHIPMENT_TOTAL = re.compile(r"\bshipment total\b[^$\n]{0,30}(?:\n\s*)?\$\s?(\d{1,3}(?:,\d{3})*\.\d{2})", re.IGNORECASE)
_AMAZON_SUBJECT_TITLE = re.compile(r"[\"“](.+?)[\"”]")
# The marks Amazon puts round order numbers and counts to keep their direction, which no reader sees.
_AMAZON_INVISIBLE = re.compile("[\u200b-\u200f\u202a-\u202e\u2066-\u2069]")
# Amazon's emails since 2026 name no items, only how many there were and Amazon's categories for
# them: "1 Kitchen item", "2 Kitchen and Dining items", "2 items: 1 Kitchen, 1 Coffee", and in the
# subject "Ordered 2 items: Pasta, Spices".
_AMAZON_COUNTED_CATEGORIES = re.compile(r"^(\d{1,3}) items?:\s*(.+)$", re.IGNORECASE)
_AMAZON_CATEGORIES_LINE = re.compile(r"^(\d{1,3}) (.+?) items?$", re.IGNORECASE)
_AMAZON_SUBJECT_CATEGORIES = re.compile(r"^(?:ordered|shipped)(?: \d{1,3} items?)?:\s*(.+)$", re.IGNORECASE)
# An email about an order that says nothing new about what was bought, or that it wasn't.
_AMAZON_NOT_ORDER_EMAIL = re.compile(r"\b(delivered|delivery|arriving|out for delivery|refund\w*|return\w*|cancel\w*)\b")
# A line of an email that is never an item's title.
_AMAZON_NOT_TITLE = re.compile(
    r"^(order\s*#|quantity|qty|sold by|seller|condition|arriving|delivered|shipped|ship to|estimated|track|view|buy it again|"
    r"\$|total|subtotal|item\(s\)|shipping|tax|grand total|order total|payment|visa|mastercard|amex|discover|gift card|promotion|your )",
    re.IGNORECASE,
)
# The charges that aren't orders: AWS, the Prime membership, Prime Video, Kindle (books and Unlimited) and Audible.
_AMAZON_CHARGE = re.compile(r"amazon|amzn", re.IGNORECASE)
_AMAZON_NOT_ORDER = re.compile(r"web services|\baws\b|\bprime\b|kindle|audible", re.IGNORECASE)
# What the word lists sort on their own: the first that has a word of the title's decides. Anything
# else goes to the model. Clothes, kitchenware and chargers are left to it: either can be true.
_AMAZON_NEED_WORDS: tuple[tuple[str, re.Pattern[str]], ...] = tuple((need, re.compile(rf"\b({words})", re.IGNORECASE)) for need, words in (
    ("discretionary", r"lego|toys?\b|board game|puzzle|video game|playstation|ps5|xbox|nintendo|gift card|jewel|necklace|earring|"
                      r"costume|d[eé]cor|poster|figurine|collectible|drone|headphone|earbud|airpods|speaker|smart ?watch|gaming|"
                      # Amazon's categories, which its emails name in place of the items.
                      r"games\b|electronics|musical instrument|travel gear|travel accessor|outdoor cooking|camping|sports|"
                      r"computer|home automation|camera|photo|video\b|movies|music\b|books?\b|party|arts|crafts|collectibles|watches"),
    ("essential", r"paper towel|toilet paper|bath tissue|facial tissue|kleenex|detergent|dishwasher|dish soap|hand soap|body wash|shampoo|"
                  r"conditioner|toothpaste|toothbrush|floss|mouthwash|deodorant|razor|diaper|wipes|baby formula|infant|tampon|"
                  r"pads\b|vitamin|supplement|ibuprofen|acetaminophen|tylenol|advil|allergy|antacid|bandage|first aid|thermometer|"
                  r"prescription|contact lens|dog food|cat food|kibble|cat litter|litter|trash bags?|garbage bags?|cleaner|cleaning|"
                  r"disinfect|bleach|sponge|air filter|water filter|furnace filter|light bulb|batter(y|ies)|coffee|milk|eggs|cereal|"
                  r"snacks?|rice|pasta|olive oil|flour|sugar|organic|grocer|"
                  # Amazon's categories, which its emails name in place of the items.
                  r"beverages|dairy|snack foods|pantry|gourmet|breakfast|produce|bakery|frozen|meat|nutrition|"
                  r"hair care|oral care|skin care|health care|health & household|household supplies|personal care|baby|pet supplies|medical|"
                  r"drugstore|sunscreen|sunblock|peanut butter|spread\b"),
))


def amazon_on() -> bool:
    return bool(AMAZON_GMAIL_CLIENT_ID and AMAZON_GMAIL_CLIENT_SECRET and AMAZON_GMAIL_TOKENS)


def html_text(markup: str) -> str:
    """An HTML email as the lines a person reads: tags gone, a new line wherever a block ends, entities undone."""
    markup = re.sub(r"(?is)<(style|script|head)\b.*?</\1>", "", markup)
    markup = re.sub(r"(?i)<br\s*/?>|</(p|div|tr|td|th|li|h[1-6]|table)>", "\n", markup)
    text = html.unescape(re.sub(r"<[^>]+>", "", markup)).replace("\u00a0", " ").replace("\u200c", "")
    return "\n".join(line for line in (" ".join(raw.split()) for raw in text.splitlines()) if line)


def amazon_money(text: str) -> float:
    return float(text.replace(",", ""))


def amazon_total(text: str) -> float | None:
    """What an email (or one order's part of it) says was paid: a grand total beats an order total beats a plain one; the last of them, which comes after any subtotals."""
    totals = [(m.group(1).lower(), amazon_money(m.group(2))) for m in _AMAZON_TOTAL.finditer(text)]
    return next((amount for rank in ("grand total", "order total") for label, amount in reversed(totals) if label == rank),
                totals[-1][1] if totals else None)


def amazon_category_names(text: str) -> list[str]:
    """Amazon's list of categories as names: "Kitchen and Dining" is two, "Camping Gear, and more" one."""
    names = re.split(r",\s*(?:and\s+)?|\s+and\s+", text.strip())
    return [n.strip() for n in names if n.strip() and n.strip().lower() not in ("more", "other", "others")]


def amazon_categories(subject: str, lines: list[str]) -> list[dict[str, Any]]:
    """
    What an email that names no items says was in it, as items titled by Amazon's categories,
    each as many as it counts: from the email's own line where it has one ("2 items: 1
    Hardware, 1 Vitamins"), else from the subject. Items in "other" categories aren't counted.
    """
    from_subject = _AMAZON_SUBJECT_CATEGORIES.match(subject.strip())
    # The subject's list read like a line of the email's ("Shipped: 1 Shoes item"), after the email's own.
    if from_subject and not _AMAZON_SUBJECT_TITLE.search(subject):
        lines = [*lines, from_subject.group(1)]
    for line in lines:
        counted = _AMAZON_COUNTED_CATEGORIES.match(line)
        if counted:
            parts = [part.strip() for part in counted.group(2).split(",") if part.strip()]
            items = []
            for part in parts:
                # "2 items: 1 Kitchen, 1 Coffee" counts each; "1 item: Pasta" counts only the whole.
                each = re.match(r"^(\d{1,3}) (.+)$", part)
                title, quantity = (each.group(2).strip(), int(each.group(1))) if each else (part, int(counted.group(1)) if len(parts) == 1 else 1)
                if title.lower() not in ("other", "others", "other items") and not title.lower().startswith("and "):
                    items.append({"title": title, "quantity": quantity, "price": None})
            if items:
                return items
        named = _AMAZON_CATEGORIES_LINE.match(line)
        if named:
            names = amazon_category_names(re.sub(r",?\s*and other$", "", named.group(2)))
            if names:
                each = int(named.group(1)) if len(names) == 1 else 1
                return [{"title": name, "quantity": each, "price": None} for name in names]
    if from_subject and not _AMAZON_SUBJECT_TITLE.search(subject):
        return [{"title": name, "quantity": 1, "price": None} for name in amazon_category_names(from_subject.group(1))]
    return []


def parse_amazon_email(subject: str, text: str) -> dict[str, Any] | None:
    """
    What one of Amazon's emails says: `kind` "ordered" or "shipped", the order numbers in it,
    each item (title, quantity, price when given), the order's total, and a shipment's total.
    None for an email that isn't an order's or a shipment's (a delivery, a refund, an ad).
    Amazon changes these emails' layout now and then, so it reads loosely: an item is whatever
    line comes before a "Quantity", with the nearest price; failing that, the title quoted in
    the subject; failing that (Amazon's emails since 2026 name no items), Amazon's categories.
    """
    subject = _AMAZON_INVISIBLE.sub("", subject)
    # Plain text writes amounts "19.80 USD" where the HTML has "$19.80".
    text = re.sub(r"(?<![\d$.])(\d{1,3}(?:,\d{3})*\.\d{2})\s?USD\b", r"$\1", _AMAZON_INVISIBLE.sub("", text))
    head = subject.strip().lower()
    # Only an order's or a shipment's own email: "Included with your Amazon order" and the like
    # carry an order number but would put the wrong day on it.
    if not head.startswith(("ordered", "shipped", "your amazon.com order", "your amazon order")):
        return None
    # Anywhere in the subject ("Your Amazon.com order has been delivered"), but not in a quoted
    # item title (an order of "Return to Oz" is still an order).
    if _AMAZON_NOT_ORDER_EMAIL.search(_AMAZON_SUBJECT_TITLE.sub("", head)):
        return None
    orders = list(dict.fromkeys(_AMAZON_ORDER_ID.findall(subject + "\n" + text)))
    if not orders:
        return None
    kind = "shipped" if head.startswith("shipped") or "has shipped" in head or "shipment" in head else "ordered"
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    items: list[dict[str, Any]] = []
    for i, line in enumerate(lines):
        quantity = _AMAZON_QUANTITY.match(line)
        if not quantity:
            continue
        title = next((lines[j] for j in range(i - 1, max(-1, i - 4), -1)
                      if not _AMAZON_NOT_TITLE.match(lines[j]) and not _AMAZON_PRICE.fullmatch(lines[j].strip()) and len(lines[j]) > 3), None)
        if title is None:
            continue
        # The price beside it, on the same line or the next two (or just before, in an older layout).
        near = [line[quantity.end():]] + lines[i + 1:i + 3] + [lines[i - 1]]
        price = next((amazon_money(m.group(1)) for part in near for m in [_AMAZON_PRICE.search(part)] if m and not _AMAZON_TOTAL.search(part)), None)
        items.append({"title": title, "quantity": int(quantity.group(1)), "price": price})
    if not items:
        items = [{"title": title, "quantity": 1, "price": None} for title in _AMAZON_SUBJECT_TITLE.findall(subject)[:1]]
    if not items:
        items = amazon_categories(subject, lines)
    total = amazon_total(text)
    shipment = _AMAZON_SHIPMENT_TOTAL.search(text)
    parsed = {"kind": kind, "orders": orders, "items": items, "total": None if kind == "shipped" else total,
              "shipment_total": amazon_money(shipment.group(1)) if shipment else (total if kind == "shipped" else None)}
    if len(orders) > 1:
        parsed["per_order"] = amazon_order_blocks(lines)
    return parsed


def amazon_order_blocks(lines: list[str]) -> dict[str, dict[str, Any]]:
    """
    What an email about several orders at once ("Ordered 17 items: …") says of each, by order
    number: its block's categories, which come just before its "Order #", and its total, just
    after it. An order can have more than one block (shipped in parts), and they add up.
    """
    marks = [(i, m.group(1)) for i, line in enumerate(lines) for m in [_AMAZON_ORDER_ID.search(line)] if m]
    blocks: dict[str, dict[str, Any]] = {}
    for k, (at, order_id) in enumerate(marks):
        before = lines[marks[k - 1][0] + 1 if k else 0:at]
        after = "\n".join(lines[at + 1:marks[k + 1][0] if k + 1 < len(marks) else len(lines)])
        block = blocks.setdefault(order_id, {"items": [], "total": None})
        block["items"] += amazon_categories("", list(reversed(before)))
        total = amazon_total(after)
        if total is not None and block["total"] is None:
            block["total"] = total
    return blocks


def gmail_parts(payload: dict[str, Any]) -> tuple[str, str]:
    """A Gmail message's text: its plain part and its HTML part, each "" when it has none."""
    import base64

    found = {"text/plain": "", "text/html": ""}
    stack = [payload]
    while stack:
        part = stack.pop()
        stack.extend(part.get("parts") or [])
        mime, data = part.get("mimeType"), (part.get("body") or {}).get("data")
        if mime in found and data and not found[mime]:
            found[mime] = base64.urlsafe_b64decode(data + "=" * (-len(data) % 4)).decode("utf-8", "replace")
    return found["text/plain"], found["text/html"]


def gmail_header(payload: dict[str, Any], name: str) -> str:
    return next((h.get("value") or "" for h in payload.get("headers") or [] if (h.get("name") or "").lower() == name), "")


def gmail_token(mailbox: str, now: float) -> str:
    """An access token for one mailbox, from its refresh token, kept until a minute before it expires."""
    held = _gmail_access.get(mailbox)
    if held and held[1] > now + 60:
        return held[0]
    r = requests.post(GOOGLE_TOKEN_URL, data={"client_id": AMAZON_GMAIL_CLIENT_ID, "client_secret": AMAZON_GMAIL_CLIENT_SECRET,
                                              "refresh_token": AMAZON_GMAIL_TOKENS[mailbox], "grant_type": "refresh_token"}, timeout=15)
    if r.status_code != 200:
        raise RuntimeError(f"Google refused the refresh token ({r.status_code} {_google_json(r).get('error')})")
    answer = r.json()
    _gmail_access[mailbox] = (answer["access_token"], now + float(answer.get("expires_in") or 3600))
    return answer["access_token"]


def gmail_get(mailbox: str, path: str, now: float, **params: Any) -> dict[str, Any]:
    # Gmail answers 403 or 429 for a burst over its per-user rate. Reading a few hundred emails in a
    # row brings that on (2026-10-29), and it passes within a minute.
    for wait in (5, 20, 60, 0):
        r = requests.get(f"{GMAIL_API}{path}", params=params, headers={"Authorization": f"Bearer {gmail_token(mailbox, now)}"}, timeout=20)
        if r.status_code not in (403, 429) or "rate" not in r.text.lower() or not wait:
            break
        time.sleep(wait)
    if r.status_code != 200:
        raise RuntimeError(f"Gmail answered {r.status_code} for {path.split('/')[1]}")
    return r.json()


def amazon_store(mailbox: str, message_id: str, day: str, parsed: dict[str, Any] | None, now: float, record: bool = True) -> None:
    """
    Keeps what one email said. An order email sets the order's day, total and items; a shipment
    email adds its total as a shipment and, until the order's own email is read, the items it
    names that the order doesn't have yet (each shipment can carry different ones). Items whose
    title is unchanged keep how they were sorted.
    """
    if parsed is not None and parsed.get("per_order"):
        # Each order of an email about several as though it had its own email; a shipment's total is kept per order.
        with_db(lambda c: (c.execute("INSERT OR REPLACE INTO amazon_emails VALUES (?,?,?,?)", (message_id, mailbox, now, parsed["kind"])), c.commit()))
        for order_id, block in parsed["per_order"].items():
            one = {"kind": parsed["kind"], "orders": [order_id], "items": block["items"],
                   "total": block["total"] if parsed["kind"] == "ordered" else None, "shipment_total": block["total"] if parsed["kind"] == "shipped" else None}
            amazon_store(mailbox, f"{message_id}:{order_id}", day, one, now, record=False)
        return

    def write(c: sqlite3.Connection) -> None:
        if record:
            c.execute("INSERT OR REPLACE INTO amazon_emails VALUES (?,?,?,?)", (message_id, mailbox, now, parsed["kind"] if parsed else None))
        if parsed is None:
            c.commit()
            return
        # An email about several orders at once can't say which item is whose: the items go to the first.
        first = parsed["orders"][0]
        for order_id in parsed["orders"]:
            known = c.execute("SELECT ordered FROM amazon_orders WHERE order_id=?", (order_id,)).fetchone()
            ordered = day if parsed["kind"] == "ordered" or not known else min(known[0], day)
            total = parsed["total"] if order_id == first and len(parsed["orders"]) == 1 else None
            confirmed = int(parsed["kind"] == "ordered" and order_id == first)
            c.execute("INSERT INTO amazon_orders VALUES (?,?,?,?,?,?) ON CONFLICT(order_id) DO UPDATE SET ordered=?, total=COALESCE(?, total), updated=?,"
                      " confirmed=MAX(confirmed, ?)", (order_id, mailbox, ordered, total, now, confirmed, ordered, total, now, confirmed))
        held = c.execute("SELECT pos, title, quantity, price, need, need_by FROM amazon_items WHERE order_id=? ORDER BY pos", (first,)).fetchall()
        confirmed = c.execute("SELECT confirmed FROM amazon_orders WHERE order_id=?", (first,)).fetchone()[0]
        if parsed["items"] and parsed["kind"] == "ordered":
            sorted_before = {title: (need, by) for _, title, _, _, need, by in held}
            c.execute("DELETE FROM amazon_items WHERE order_id=?", (first,))
            for pos, item in enumerate(parsed["items"]):
                need, by = sorted_before.get(item["title"], (None, None))
                c.execute("INSERT INTO amazon_items VALUES (?,?,?,?,?,?,?)", (first, pos, item["title"], item["quantity"], item["price"], need, by))
        elif parsed["items"] and not confirmed:
            titles = {title for _, title, *_ in held}
            pos = max((row[0] for row in held), default=-1) + 1
            for item in parsed["items"]:
                if item["title"] not in titles:
                    c.execute("INSERT INTO amazon_items VALUES (?,?,?,?,?,NULL,NULL)", (first, pos, item["title"], item["quantity"], item["price"]))
                    titles.add(item["title"])
                    pos += 1
        if parsed["kind"] == "shipped" and parsed["shipment_total"] is not None:
            c.execute("INSERT OR REPLACE INTO amazon_shipments VALUES (?,?,?,?)", (message_id, first, day, parsed["shipment_total"]))
        c.commit()

    with_db(write)


def amazon_read_mailbox(mailbox: str, now: float) -> int:
    """Reads one mailbox's Amazon emails that haven't been read yet; how many there were."""
    zone = household_zone() or timezone.utc
    seen = {row[0] for row in with_db(lambda c: c.execute("SELECT message_id FROM amazon_emails").fetchall())}
    fresh: list[str] = []
    page: str | None = None
    # Past the ones read already, so a mailbox with more than a read's worth gets through them all over the hours.
    for _ in range(AMAZON_MAX_PAGES):
        listed = gmail_get(mailbox, "/messages", now, q=AMAZON_QUERY, maxResults=100, **({"pageToken": page} if page else {}))
        fresh += [m["id"] for m in listed.get("messages") or [] if m["id"] not in seen]
        page = listed.get("nextPageToken")
        if not page or len(fresh) >= AMAZON_MAX_EMAILS:
            break
    fresh = fresh[:AMAZON_MAX_EMAILS]
    for message_id in fresh:
        time.sleep(AMAZON_FETCH_GAP)
        message = gmail_get(mailbox, f"/messages/{message_id}", now, format="full")
        payload = message.get("payload") or {}
        plain, markup = gmail_parts(payload)
        subject = gmail_header(payload, "subject")
        # The HTML as people see it first: Amazon's plain part leaves out what was in the order.
        readings = [parse_amazon_email(subject, text) for text in (html_text(markup) if markup else "", plain) if text]
        parsed = next((r for r in readings if r and r["items"]), next((r for r in readings if r), None))
        day = datetime.fromtimestamp(int(message.get("internalDate") or now * 1000) / 1000, zone).date().isoformat()
        amazon_store(mailbox, message_id, day, parsed, now)
    return len(fresh)


def amazon_need_by_words(title: str) -> str | None:
    """What the word lists make of an item's title, or None when they don't know."""
    return next((need for need, words in _AMAZON_NEED_WORDS if words.search(title)), None)


AMAZON_MODEL_PROMPT = (
    "Each line is something a household bought on Amazon, or Amazon's category for it (Amazon's emails often give only the "
    "category, such as Hardware or Kitchen). For each, in order, say whether it is essential "
    "(groceries and food, household and cleaning supplies, toiletries, medicine and health, baby and pet necessities, "
    "repairs or replacements of things the home needs) or discretionary (wants rather than needs: electronics, toys, "
    "games, hobbies, decor, entertainment, fashion, gifts, upgrades). Answer with one word per line in `needs`.\n\n"
)
AMAZON_MODEL_SCHEMA = {"type": "object", "properties": {"needs": {"type": "array", "items": {"type": "string", "enum": list(AMAZON_NEEDS)}}}, "required": ["needs"]}


def amazon_ask_model(titles: list[str]) -> list[str] | None:
    """The box's model's verdict on each title, in order, or None when it isn't there or answers something else."""
    if not OLLAMA or not titles:
        return None
    r = requests.post(f"{OLLAMA}/api/chat", json={
        "model": VLM_MODEL,
        "messages": [{"role": "user", "content": AMAZON_MODEL_PROMPT + "\n".join(f"{i + 1}. {t}" for i, t in enumerate(titles))}],
        "format": AMAZON_MODEL_SCHEMA,
        "stream": False,
        "keep_alive": "24h",
        "options": {"temperature": 0, "num_ctx": 4096, "num_gpu": 99},
    }, timeout=120)
    r.raise_for_status()
    needs = json.loads(r.json()["message"]["content"]).get("needs")
    return needs if isinstance(needs, list) and len(needs) == len(titles) and all(n in AMAZON_NEEDS for n in needs) else None


def amazon_sort_items(ask: Callable[[list[str]], list[str] | None] | None = None) -> int:
    """Sorts every item not sorted yet: by the word lists, then the rest by the model, a batch at a time. How many were sorted."""
    ask = ask or amazon_ask_model
    rows = with_db(lambda c: c.execute("SELECT order_id, pos, title FROM amazon_items WHERE need IS NULL ORDER BY order_id, pos").fetchall())
    verdicts: list[tuple[str, str, str, int, str]] = []
    left = []
    for order_id, pos, title in rows:
        need = amazon_need_by_words(title)
        if need:
            verdicts.append((need, "rule", order_id, pos, title))
        else:
            left.append((order_id, pos, title))
    for start in range(0, len(left), AMAZON_MODEL_BATCH):
        batch = left[start:start + AMAZON_MODEL_BATCH]
        try:
            needs = ask([title for _, _, title in batch])
            if needs is None:
                needs = [(ask([title]) or [None])[0] for _, _, title in batch]
        except Exception as e:
            log.warning("amazon: the model couldn't sort %d items: %s", len(batch), e)
            break
        verdicts += [(need, "model", order_id, pos, title) for need, (order_id, pos, title) in zip(needs, batch) if need]
    # By title too: an email read meanwhile may have put another item at that place, which this verdict isn't about.
    with_db(lambda c: (c.executemany("UPDATE amazon_items SET need=?, need_by=? WHERE order_id=? AND pos=? AND title=? AND need IS NULL", verdicts), c.commit()))
    return len(verdicts)


def amazon_reread(now: float) -> None:
    """
    Forgets what the emails were read as, when they are now read differently (`AMAZON_PARSER`),
    so the next read goes through them again. Only what the search still reaches: an order older
    than that stays as it was read, since its emails won't be. What someone said about an item
    stays too: a re-read item of the same title in the same order gets it back.
    """
    if state_get(AMAZON_PARSER_KEY) == AMAZON_PARSER:
        return

    since = str(datetime.fromtimestamp(now, timezone.utc).date() - timedelta(days=AMAZON_LOOKBACK_DAYS - AMAZON_REREAD_MARGIN_DAYS))

    def forget(c: sqlite3.Connection) -> None:
        c.execute("DELETE FROM amazon_emails")
        c.execute("DELETE FROM amazon_shipments WHERE shipped >= ?", (since,))
        c.execute("DELETE FROM amazon_items WHERE (need_by IS NULL OR need_by NOT LIKE 'user:%') AND order_id IN (SELECT order_id FROM amazon_orders WHERE ordered >= ?)", (since,))
        c.execute("UPDATE amazon_orders SET confirmed=0 WHERE ordered >= ?", (since,))
        c.commit()

    with_db(forget)
    state_set(AMAZON_PARSER_KEY, AMAZON_PARSER)
    log.info("amazon: the emails will be read again (parser %d)", AMAZON_PARSER)


def amazon_sync(now: float | None = None) -> None:
    """Every mailbox's new Amazon emails, then sorting what they ordered. A mailbox that fails is logged and the next read tries again."""
    if not amazon_on() or not _amazon_lock.acquire(blocking=False):
        return
    now = time.time() if now is None else now
    try:
        amazon_reread(now)
        read, failed = 0, []
        for mailbox in sorted(AMAZON_GMAIL_TOKENS):
            try:
                read += amazon_read_mailbox(mailbox, now)
            except Exception as e:
                log.warning("amazon: couldn't read %s: %s", mailbox, e)
                failed.append(mailbox)
        sorted_now = amazon_sort_items()
        state_set(AMAZON_SYNCED_KEY, {"at": now, "emails": read, "failed": failed})
        log.info("amazon: read %d new emails, sorted %d items, %d mailboxes failed", read, sorted_now, len(failed))
    finally:
        _amazon_lock.release()


def amazon_sync_quietly() -> None:
    try:
        amazon_sync()
    except Exception:
        log.exception("amazon: reading the orders failed")


def is_amazon_order_charge(name: str | None, merchant: str | None) -> bool:
    """Whether a card's purchase is one of Amazon's shipments (not AWS, Prime, Kindle Unlimited or Audible)."""
    text = f"{merchant or ''} {name or ''}"
    return bool(_AMAZON_CHARGE.search(text)) and not _AMAZON_NOT_ORDER.search(text)


def _days_between(first: str, then: str) -> int:
    return (datetime.fromisoformat(then) - datetime.fromisoformat(first)).days


def _cents(amount: float) -> int:
    return round(amount * 100)


def amazon_match(charges: list[tuple[str, str, float]], orders: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """
    Which order each Amazon charge paid for, by charge id: {"order", "match"}. Charges are (id,
    day, amount); each order has its `id`, `date`, `total` (or None) and `shipments` [(day,
    total)]. In this order, each charge and each shipment used at most once:

    1. "shipment": a shipment's email gave its total, and a charge of exactly that came within a
       few days of it; or two or three shipments' totals add up to a charge (Amazon charges
       shipments that go out together at once), those of one order first. Such a charge says
       which orders it paid toward and how much of it each (`parts`, in cents).
    2. "order": a charge of exactly what the order still owes (its total, less the shipments
       matched to it) came within a month of it; "split" when shipments had paid some of it.
    3. "split": two to four charges in that month add up to exactly what it still owes (Amazon
       charges each shipment of a bigger order on its own).

    Where several charges and emails could pair up, the nearest pairs are taken first, so a
    charge goes to the email it is closest to rather than to whichever was looked at first.
    """
    from itertools import combinations

    found: dict[str, dict[str, Any]] = {}
    by_order = {o["id"]: o for o in orders}

    def within(day: str, window: tuple[int, int], charge: tuple[str, str, float]) -> bool:
        return window[0] <= _days_between(day, charge[1]) <= window[1]

    def gap(day: str, charge: tuple[str, str, float]) -> int:
        return abs(_days_between(day, charge[1]))

    pairs = sorted(
        (gap(day, c), c[1], c[0], order["id"], n)
        for order in orders for n, (day, total) in enumerate(order.get("shipments") or [])
        for c in charges if _cents(c[2]) == _cents(total) and within(day, AMAZON_SHIPMENT_WINDOW, c)
    )
    shipped: set[tuple[str, int]] = set()
    for _, _, charge_id, order_id, n in pairs:
        if charge_id not in found and (order_id, n) not in shipped:
            found[charge_id] = {"order": order_id, "match": "shipment"}
            shipped.add((order_id, n))

    # Every charge's every group of two or three shipments adding up to it, ranked together and
    # taken best first, as the single shipments were: one order's shipments before several
    # orders', then the nearest, whichever charge they are for.
    left = [(order["id"], n, day, _cents(total)) for order in orders for n, (day, total) in enumerate(order.get("shipments") or []) if (order["id"], n) not in shipped]
    groups = []
    for charge in (c for c in charges if c[0] not in found):
        near = sorted((s for s in left if within(s[2], AMAZON_SHIPMENT_WINDOW, charge) and 0 < s[3] < _cents(charge[2])),
                      key=lambda s: (abs(_days_between(s[2], charge[1])), s[0], s[1]))[:AMAZON_SPLIT_CANDIDATES]
        for size in (2, 3):
            for group in combinations(near, size):
                if sum(s[3] for s in group) == _cents(charge[2]):
                    groups.append(((len({s[0] for s in group}), sum(abs(_days_between(s[2], charge[1])) for s in group), charge[1], charge[0]), charge, group))
    for _, charge, group in sorted(groups, key=lambda g: g[0]):
        if charge[0] in found or any((s[0], s[1]) in shipped for s in group):
            continue
        parts: dict[str, int] = {}
        for order_id, n, _, cents in group:
            parts[order_id] = parts.get(order_id, 0) + cents
            shipped.add((order_id, n))
        found[charge[0]] = {"order": max(parts, key=lambda o: (parts[o], o)), "match": "shipment", "parts": parts}

    def paid(match: dict[str, Any], order_id: str, charge: tuple[str, str, float]) -> int:
        if "parts" in match:
            return match["parts"].get(order_id, 0)
        return _cents(charge[2]) if match.get("order") == order_id else 0

    def owed(order: dict[str, Any]) -> int:
        return _cents(order["total"]) - sum(paid(found[c[0]], order["id"], c) for c in charges if c[0] in found)

    waiting = {o["id"]: owed(o) for o in orders if o.get("total") is not None}
    pairs = sorted(
        (gap(by_order[order_id]["date"], c), c[1], c[0], order_id)
        for order_id, cents in waiting.items() if cents > 0
        for c in charges if c[0] not in found and _cents(c[2]) == cents and within(by_order[order_id]["date"], AMAZON_ORDER_WINDOW, c)
    )
    for _, _, charge_id, order_id in pairs:
        if charge_id not in found and waiting[order_id] > 0:
            part = any(m["order"] == order_id or order_id in m.get("parts", {}) for m in found.values())
            found[charge_id] = {"order": order_id, "match": "split" if part else "order"}
            waiting[order_id] = 0

    for order_id in sorted((o for o, cents in waiting.items() if cents > 0), key=lambda o: (by_order[o]["date"], o)):
        order, cents = by_order[order_id], waiting[order_id]
        candidates = [c for c in charges if c[0] not in found and 0 < _cents(c[2]) < cents and within(order["date"], AMAZON_ORDER_WINDOW, c)]
        candidates = sorted(candidates, key=lambda c: (gap(order["date"], c), c[1], c[0]))[:AMAZON_SPLIT_CANDIDATES]
        best = None
        for size in range(2, AMAZON_SPLIT_MOST + 1):
            for group in combinations(candidates, size):
                if sum(_cents(c[2]) for c in group) == cents:
                    distance = sum(gap(order["date"], c) for c in group)
                    if best is None or distance < best[0]:
                        best = (distance, group)
            if best:
                break
        if best:
            for charge in best[1]:
                found[charge[0]] = {"order": order_id, "match": "split"}
    return found


def amazon_orders_near(first: str, last: str) -> list[dict[str, Any]]:
    """The orders whose charges may fall between two days, with their items and shipments."""
    start = (datetime.fromisoformat(first) - timedelta(days=AMAZON_ORDER_WINDOW[1])).date().isoformat()
    end = (datetime.fromisoformat(last) + timedelta(days=-AMAZON_ORDER_WINDOW[0])).date().isoformat()

    def read(c: sqlite3.Connection) -> list[dict[str, Any]]:
        orders = {order_id: {"id": order_id, "date": ordered, "total": total, "mailbox": mailbox, "items": [], "shipments": []}
                  for order_id, ordered, total, mailbox in c.execute(
                      "SELECT order_id, ordered, total, mailbox FROM amazon_orders WHERE ordered >= ? AND ordered <= ? ORDER BY ordered, order_id", (start, end))}
        for order_id, pos, title, quantity, price, need, by in c.execute("SELECT order_id, pos, title, quantity, price, need, need_by FROM amazon_items ORDER BY order_id, pos"):
            if order_id in orders:
                orders[order_id]["items"].append({"pos": pos, "title": title, "quantity": quantity, "price": price, "need": need, "need_by": by})
        for order_id, shipped, total in c.execute("SELECT order_id, shipped, total FROM amazon_shipments ORDER BY shipped"):
            if order_id in orders:
                orders[order_id]["shipments"].append((shipped, total))
        return list(orders.values())

    return with_db(read)


def amazon_split(amount: float, items: list[dict[str, Any]]) -> dict[str, float]:
    """
    A charge shared out between essential, discretionary and unsorted, in proportion to its order's
    item prices, in whole cents that add up to the charge. An item the email gave no price for
    weighs what the priced ones do on average (all of them evenly when none has one). It is the
    order's mix, not the shipment's own: a shipment's email rarely says which items it carried.
    """
    cents = _cents(amount)
    shares = {"essential": 0.0, "discretionary": 0.0, "unsorted": 0.0}
    if not items:
        shares["unsorted"] = float(cents)
    else:
        priced = [item["price"] for item in items if item["price"]]
        average = sum(priced) / len(priced) if priced else 1.0
        weights = [(item["price"] or average) * max(1, item["quantity"]) for item in items]
        whole = sum(weights)
        for item, weight in zip(items, weights):
            shares[item["need"] if item["need"] in AMAZON_NEEDS else "unsorted"] += cents * weight / whole
    # The cents the rounding down left over go to the shares that lost the most to it.
    whole_cents = {need: math.floor(share) for need, share in shares.items()}
    for need in sorted(shares, key=lambda n: whole_cents[n] - shares[n])[:cents - sum(whole_cents.values())]:
        whole_cents[need] += 1
    return {need: share / 100 for need, share in whole_cents.items()}


def amazon_attach(purchases: list[dict[str, Any]], charges: set[str], first: str, last: str) -> None:
    """
    Marks each of the budget's purchases whose id is in `charges` (Amazon's) `from_amazon`, and
    adds what its order says (`amazon`), or that none was found (`amazon` None). Other purchases
    get neither, and none do while no mailbox is linked. The charges are matched together with
    Amazon's others for a month either side, so an order whose charges straddle the edge of the
    month looked at still finds both.
    """
    mine = [p for p in purchases if p["id"] in charges]
    if not mine or not amazon_on():
        return
    before = (datetime.fromisoformat(first) - timedelta(days=AMAZON_ORDER_WINDOW[1])).date().isoformat()
    after = (datetime.fromisoformat(last) + timedelta(days=AMAZON_ORDER_WINDOW[1])).date().isoformat()
    nearby = with_db(lambda c: c.execute(
        "SELECT txn_id, date, amount, name, merchant FROM plaid_transactions WHERE removed_at IS NULL AND kind = 'spend' AND date >= ? AND date <= ?"
        " AND account_id NOT IN (SELECT account_id FROM plaid_accounts WHERE type = 'depository') AND COALESCE(account_type, '') != 'depository'"
        " AND txn_id NOT IN (SELECT pending_id FROM plaid_transactions WHERE pending_id IS NOT NULL AND removed_at IS NULL)",
        (before, after),
    ).fetchall())
    every = {txn_id: (txn_id, date, amount) for txn_id, date, amount, name, merchant in nearby if is_amazon_order_charge(name, merchant)}
    every.update({p["id"]: (p["id"], p["date"], p["amount"]) for p in mine})
    orders = amazon_orders_near(min(c[1] for c in every.values()), max(c[1] for c in every.values()))
    matched = amazon_match(sorted(every.values(), key=lambda c: (c[1], c[0])), orders)
    by_id = {o["id"]: o for o in orders}
    for p in mine:
        p["from_amazon"] = True
        match = matched.get(p["id"])
        if match is None:
            p["amazon"] = None
            continue
        order = by_id[match["order"]]
        parts = match.get("parts") or {order["id"]: _cents(p["amount"])}
        # A charge for several orders' shipments: each order's share by its own items, the items all listed.
        shares = [amazon_split(cents / 100, by_id[order_id]["items"]) for order_id, cents in sorted(parts.items())]
        p["amazon"] = {"order": order["id"], "match": match["match"], "ordered": order["date"], "mailbox": order.get("mailbox"),
                       "items": [{"order": order_id, **{k: item[k] for k in ("pos", "title", "quantity", "price", "need", "need_by")}}
                                 for order_id in sorted(parts, key=lambda o: o != order["id"]) for item in by_id[order_id]["items"]],
                       **{need: round(sum(share[need] for share in shares), 2) for need in (*AMAZON_NEEDS, "unsorted")}}
        if len(parts) > 1:
            p["amazon"]["orders"] = sorted(parts)


def amazon_month(purchases: list[dict[str, Any]]) -> dict[str, Any]:
    """The month's Amazon charges summed up: how much was essential, discretionary, not sorted yet, and not matched to an order."""
    mine = [p for p in purchases if "amazon" in p]
    synced = state_get(AMAZON_SYNCED_KEY) or {}
    return {
        "configured": amazon_on(),
        "read_at": synced.get("at"),
        "failed": synced.get("failed") or [],
        "charges": len(mine),
        **{need: round(sum((p.get("amazon") or {}).get(need, 0.0) for p in mine), 2) for need in (*AMAZON_NEEDS, "unsorted")},
        "unmatched": round(sum(p["amount"] for p in mine if p.get("amazon") is None), 2),
    }


class AmazonNeed(BaseModel):
    # "essential", "discretionary", or null to let it sort itself again.
    need: str | None = None
    # The item's title as the phone saw it: an email read since may have put another item at that place.
    title: str | None = None


@app.put("/finance/budget/amazon/{order_id}/items/{pos}")
def put_amazon_item(order_id: str, pos: int, body: AmazonNeed, request: Request, response: Response) -> dict[str, Any]:
    """Says by hand whether one item of an Amazon order was essential, or takes that back."""
    user = require_bank(request, response)
    if body.need is not None and body.need not in AMAZON_NEEDS:
        raise HTTPException(400, {"error": "bad_need", "message": "need is essential, discretionary or null"})

    def write(c: sqlite3.Connection) -> str:
        row = c.execute("SELECT title FROM amazon_items WHERE order_id=? AND pos=?", (order_id, pos)).fetchone()
        if row is None:
            return "no_item"
        if body.title is not None and body.title != row[0]:
            return "item_changed"
        c.execute("UPDATE amazon_items SET need=?, need_by=? WHERE order_id=? AND pos=?", (body.need, f"user:{user}" if body.need else None, order_id, pos))
        c.commit()
        return "ok"

    outcome = with_db(write)
    if outcome == "no_item":
        raise HTTPException(404, {"error": "no_item", "message": "No such item in that order"})
    if outcome == "item_changed":
        raise HTTPException(409, {"error": "item_changed", "message": "That place in the order holds another item now"})
    if body.need is None:
        threading.Thread(target=amazon_sort_items, name="amazon-sort", daemon=True).start()
    return budget_month()


# ---------------------------------------------------------------- packages

# What is on its way to the house, for the Packages app (docs/packages.md). Nothing is typed in:
# every hour's Amazon read has its mailboxes linked already (see "Amazon orders"), so the same
# read-only Gmail tokens read each carrier's and shop's shipping emails, and each email moves its
# package along. A key for a tracking service (PACKAGE_17TRACK_KEY) adds the carrier's own scans.
#
# Then the Front Door camera says what it saw. Measured on the household's own deliveries
# (2026-10-31): Amazon's "Delivered" email came 72-130 s after the camera's person event, in all
# four tried, and each had really left a parcel. The box's vision model is much less sure than the
# clock is. Shown the landing before and after a visit it found 2 of the 4 (flat mailers behind
# the railing get past it) and called 5 of 18 ordinary visits a parcel (sunlight, a dog, an open
# door); shown one picture at a time, 1 of the 4 and none of 12. So it never decides alone:
# - "confirmed": an email says delivered, a visit fits its time, and the model saw a parcel appear;
# - "seen": an email says delivered and a visit fits, and the two pictures are there to look at;
# - "not_seen": an email says delivered and the camera saw nobody at the door, which is worth saying;
# - a parcel with no email at all is only claimed when both the model's questions agree and nobody
#   opened the door, and then as "may have".
# Whether it is still out there is the door sensor's to say more than the model's.
#
# Surprises are the household's to keep from each other. A package marked as one is told to its
# owner in full and to anyone else as "a surprise": here, in each push, and in its pictures. Who is
# asking is which phone is asking (`package_phones`), since one Frigate login may serve a family;
# a phone that hasn't said whose it is sees every surprise masked.
PACKAGE_CAMERA = os.environ.get("PACKAGE_CAMERA", "amcrest_1")
# Where parcels are left, as fractions of the frame: x, y, width, height. Default: the top landing.
PACKAGE_SPOT = tuple(float(v) for v in os.environ.get("PACKAGE_SPOT", "0.36,0.34,0.30,0.30").split(","))
PACKAGE_DOOR = re.compile(os.environ.get("PACKAGE_DOOR", "front"), re.IGNORECASE)
PACKAGE_17TRACK_KEY = os.environ.get("PACKAGE_17TRACK_KEY", "").strip()
PACKAGE_FRAME_DIR = os.environ.get("PACKAGE_FRAME_DIR", os.path.join(os.path.dirname(DB_PATH), "package_frames"))
PACKAGE_LOOKBACK_DAYS = int(os.environ.get("PACKAGE_LOOKBACK_DAYS", "100"))
PACKAGE_TICK_SECONDS = 30.0
# New mail matters to the minute here (a parcel on the step), so the last two days are asked every
# couple of minutes, which costs one list per mailbox; the whole lookback once an hour.
PACKAGE_RECENT_SECONDS = 120.0
PACKAGE_FULL_SECONDS = 3600.0
PACKAGE_TRACKER_SECONDS = 1800.0
PACKAGE_MAX_EMAILS = 300
PACKAGE_FETCH_GAP = 0.25
# Bump to read every email in the lookback again when they are read differently.
PACKAGE_PARSER = 1
PACKAGE_PARSER_KEY = "package_parser"
PACKAGE_SYNCED_KEY = "package_synced"
PACKAGE_FULL_KEY = "package_full_read"
PACKAGE_SETTINGS_KEY = "package_settings"
PACKAGE_VISITS_KEY = "package_visits_after"
PACKAGE_NAGS_KEY = "package_nags_started"
# An old email read for the first time moves its package without telling anyone.
PACKAGE_FRESH_SECONDS = 3 * 3600.0
# A person's events this close together are one visit.
PACKAGE_VISIT_GAP = 45.0
# Recordings reach disk a segment behind: a visit is judged this long after it ended.
PACKAGE_VISIT_SETTLE = 40.0
PACKAGE_BEFORE_SECONDS = 10.0
PACKAGE_AFTER_SECONDS = 15.0
# How long before an email saying "delivered" a visit can be the delivery. Amazon's driver scans at
# the door (72-130 s, above); a carrier's email can follow its scan by hours.
PACKAGE_AMAZON_WINDOW = 20 * 60.0
PACKAGE_OTHER_WINDOW = 4 * 3600.0
PACKAGE_AFTER_EMAIL = 5 * 60.0
# An email-less parcel the camera saw is taken for the email that comes this soon after it.
PACKAGE_CAMERA_MERGE = 6 * 3600.0
# Past this an email's delivery is older than what the camera can still be asked about.
PACKAGE_CAMERA_REACH = 7 * 86400.0
PACKAGE_MODEL_SURE = 0.6
PACKAGE_REMIND_AFTER = 2 * 3600.0
# Past this, nobody is still wondering whether it was taken in.
PACKAGE_PORCH_GIVE_UP = 3 * 86400.0
PACKAGE_QUIET_EXPECTED = 3 * 86400.0
# `archived` when the relay did it rather than someone: news of the parcel brings it back.
PACKAGE_QUIET = 2
# Asking the model again about a visit it couldn't answer for.
PACKAGE_RETRY_SECONDS = 300.0
PACKAGE_RETRY_REACH = 3600.0
PACKAGE_QUIET_ANY = 21 * 86400.0
PACKAGE_LATE_AFTER_DAYS = 1
PACKAGE_QUIET_LATE_DAYS = 7
PACKAGE_RAIN_CHANCE = 50
PACKAGE_RAIN_HOURS = 3
PACKAGE_RAIN_SECONDS = 1800.0
PACKAGE_RETURN_WARN_DAYS = 3
PACKAGE_MORNING_HOUR = 8
PACKAGE_FRAMES_KEPT_DAYS = 180
PACKAGE_LISTED_DAYS = 400
PACKAGE_STAGES = ("ordered", "shipped", "out_for_delivery", "delivered")
PACKAGE_RETURN_STATES = ("none", "started", "sent", "refunded")
PACKAGE_NOTICES = ("shipped", "out_for_delivery", "delivered", "porch", "rain", "missing", "problems", "returns", "morning")
PACKAGE_NOTICE_DEFAULTS = {"shipped": False, "out_for_delivery": True, "delivered": True, "porch": True, "rain": True, "missing": True, "problems": True, "returns": True, "morning": True}
# What a shop allows, in days from delivery, where the email doesn't say: only what is worth a reminder.
PACKAGE_RETURN_DAYS = {"Amazon": 30, "Target": 90, "Costco": 90, "The Home Depot": 90, "Best Buy": 15, "Walmart": 30}
_package_lock = threading.Lock()
_package_status: dict[str, Any] = {}

PACKAGE_QUERY = ('{from:ups.com from:fedex.com from:usps.com from:dhl.com from:ontrac.com from:lasership.com from:veho.com subject:shipped subject:ordered '
                 'subject:"on its way" subject:"on the way" subject:"out for delivery" subject:delivered subject:delivery subject:tracking subject:shipment '
                 'subject:arriving subject:"has shipped" subject:"is here" (from:amazon.com subject:(refund OR return))} -category:promotions')

_PACKAGE_INVISIBLE = re.compile("[­͏​-‏‪-‮⁦-⁩﻿]")
_PACKAGE_NOT_SHIPPING_SENDERS = re.compile(r"grubhub|doordash|ubereats|uber\.com|postmates|eaze|instacart|drizly|gopuff|caviar|informeddelivery|linkedin|govdelivery|proxyvote", re.IGNORECASE)
# A carrier's or shop's advertising: "% off, delivered by first pitch". A tracking number outweighs it.
_PACKAGE_ADVERT = re.compile(r"\d+\s?% off|\b(sale|deals?|coupons?|credit|savings?|save \$|newsletter|last chance|free (delivery|shipping)|preference)\b", re.IGNORECASE)
_PACKAGE_POSSESSIVE = re.compile(r"\b(your|you)\b.{0,50}\b(order|package|shipment|parcel|box|meals|delivery|item|items)\b", re.IGNORECASE)
_PACKAGE_STAGE_WORDS: tuple[tuple[str, re.Pattern[str]], ...] = tuple((stage, re.compile(words, re.IGNORECASE)) for stage, words in (
    ("refund", r"\brefund"),
    ("return_sent", r"\bdrop-?\s?off confirmed\b|\breturn (was )?(received|dropped)\b"),
    ("return", r"\breturn(ed|s|ing)?\b"),
    ("delivered", r"^(\W*)order delivered|\bdelivered\b|\bpackage arrived\b|\bhas arrived\b|\bshipment is here\b|\bis here\b|\bwas dropped off\b"),
    ("attempted", r"delivery attempt|attempted delivery|attempted to deliver|missed (your )?delivery|we missed you|notice left"),
    ("exception", r"delivery exception|\bexception\b|unable to deliver|could ?n.t deliver|\bdelayed\b|\bdelay\b|running late|held at|action required|address (issue|problem)"),
    ("out_for_delivery", r"out for delivery|arriving today|scheduled for delivery today|on the truck"),
    ("expected", r"expected delivery|delivery estimate|delivery update|updated delivery date|scheduled for delivery|arriving\b"),
    ("shipped", r"\bshipped\b|has shipped|on its way|on the way|has been sent|\bshipment\b|in transit|tracking (number|information|update)"),
    ("ordered", r"^ordered\b|order confirm|thanks for your order|thank you for your order|order received|\border\b.*\bconfirmed\b"),
))
_PACKAGE_CARRIERS = (("ups", r"\bups\b|ups\.com"), ("usps", r"\busps\b|usps\.com|postal service"), ("fedex", r"\bfed ?ex\b|fedex\.com"), ("dhl", r"\bdhl\b"),
                     ("ontrac", r"\bontrac\b"), ("lasership", r"\blasership\b"), ("veho", r"\bveho\b"), ("amazon", r"\bamazon logistics\b|\bamzl\b"))
_PACKAGE_UPS = re.compile(r"\b(1Z[0-9A-Z]{16})\b")
_PACKAGE_USPS = re.compile(r"\b(9[1-5]\d{18,20}|420\d{5}9[1-5]\d{18,20})\b")
_PACKAGE_S10 = re.compile(r"\b([A-Z]{2}\d{9}US)\b")
_PACKAGE_AMAZON_TBA = re.compile(r"\b(TBA\d{12})\b")
_PACKAGE_LABELLED = re.compile(r"(?i:tracking)(?:\s*(?i:number|no\.?|#|id))?\s*[:#]?\s*#?\s*([A-Za-z0-9][A-Za-z0-9-]{7,34})\b")
_PACKAGE_NAMED_SERVICE = re.compile(r"\b([A-Z][a-zA-Z]+) (?:Standard|Express|Ground|Priority|Economy) shipping:\s*([A-Za-z0-9-]{8,34})\b")
_PACKAGE_AMAZON_ORDER = re.compile(r"\b((?:\d{3}|D\d{2})-\d{7}-\d{7})\b")
_PACKAGE_ORDER = re.compile(r"(?i:order)(?:\s*(?i:number|no\.?))?\s*[:#]?\s*#?\s*([A-Z0-9][A-Z0-9.-]{3,29})\b")
_PACKAGE_NOT_ORDERS = {"delivered", "shipped", "tracking", "shipment", "confirmed", "confirmation", "received", "cancelled", "canceled", "summary", "details", "updates"}
_PACKAGE_SIGNATURE = re.compile(r"signature (is |will be )?required|adult signature|must be signed|sign for (this|your)", re.IGNORECASE)
_PACKAGE_LEFT = re.compile(r"\b(left (near|at|on|in|by|with)|handed (directly )?to|delivered (to|at) (the|a|your)|placed (at|in|on)|in (the|your) (mailroom|mailbox|parcel locker))\b", re.IGNORECASE)
_PACKAGE_NOT_THE_DOOR = re.compile(r"mailbox|mailroom|locker|garage|back door|side door|neighbou?r|leasing|front desk|reception|handed", re.IGNORECASE)
_PACKAGE_MONTHS = {m: i + 1 for i, m in enumerate(("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"))}
_PACKAGE_DAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
_PACKAGE_DATE = re.compile(r"\b(?:(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*,?\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(20\d{2}))?\b", re.IGNORECASE)
_PACKAGE_NUMERIC_DATE = re.compile(r"\b(\d{1,2})/(\d{1,2})(?:/(20\d{2}|\d{2}))?\b")
_PACKAGE_EXPECT_LINE = re.compile(r"(arriving|now arriving|expected delivery|estimated delivery|delivery (date|estimate|by)|deliver(ed|y)? (by|on)|expects to deliver|scheduled for delivery|get it by|arrives? (by|on))", re.IGNORECASE)
_PACKAGE_TIME = r"(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?"
_PACKAGE_BETWEEN = re.compile(rf"between\s+{_PACKAGE_TIME}\s+(?:and|-|to)\s+{_PACKAGE_TIME}", re.IGNORECASE)
_PACKAGE_BY = re.compile(rf"\bby\s+{_PACKAGE_TIME}", re.IGNORECASE)
_PACKAGE_DOMAINS = (("amazon.", "Amazon"), ("homedepot", "The Home Depot"), ("tiktok", "TikTok Shop"), ("ebay", "eBay"), ("target", "Target"), ("walmart", "Walmart"),
                    ("costco", "Costco"), ("bestbuy", "Best Buy"), ("etsy", "Etsy"), ("chewy", "Chewy"), ("apple.com", "Apple"), ("google.com", "Google Store"), ("tesla", "Tesla Shop"),
                    ("mercari", "Mercari"), ("factor75", "Factor"), ("wayfair", "Wayfair"), ("lowes", "Lowe's"), ("ikea", "IKEA"), ("pokemon", "Pokémon Center"))
_PACKAGE_CARRIER_DOMAINS = {"ups.com": "ups", "usps.com": "usps", "fedex.com": "fedex", "dhl.com": "dhl", "ontrac.com": "ontrac", "lasership.com": "lasership", "veho.com": "veho", "uniuni.com": "uniuni"}
_PACKAGE_SHOP_IN_SUBJECT = re.compile(r"^[Yy]our ([A-Z][\w'&-]*(?: [A-Z][\w'&-]*){0,3}?) (?i:order|shipment|package|box|meals)\b")
_PACKAGE_AMAZON_ITEMS = re.compile(r"^(?:ordered|shipped|delivered|out for delivery|arriving)(?: \d{1,3} items?)?:?\s*(?:\d{1,3} items?:?)?\s*(.+)$", re.IGNORECASE)
PACKAGE_TRACK_URLS = {
    "ups": "https://www.ups.com/track?tracknum={}", "usps": "https://tools.usps.com/go/TrackConfirmAction?tLabels={}", "fedex": "https://www.fedex.com/fedextrack/?trknbr={}",
    "dhl": "https://www.dhl.com/us-en/home/tracking.html?tracking-id={}", "ontrac": "https://www.ontrac.com/tracking/?number={}",
}


def package_ups_valid(number: str) -> bool:
    """A UPS number's check digit: after "1Z", letters count as (ord - 63) % 10 and every second character twice."""
    body = number[2:17]
    if len(number) != 18 or not number.startswith("1Z") or not number[17].isdigit():
        return False
    values = [int(ch) if ch.isdigit() else (ord(ch) - 63) % 10 for ch in body]
    total = sum(v * (2 if i % 2 else 1) for i, v in enumerate(values))
    return (10 - total % 10) % 10 == int(number[17])


def package_usps_valid(number: str) -> bool:
    """A USPS barcode's check digit (mod 10, weighted 3 and 1 from the right), past any ZIP routing prefix."""
    digits = number[8:] if number.startswith("420") else number
    if not digits.isdigit() or len(digits) not in (20, 21, 22):
        return False
    total = sum(int(ch) * (3 if i % 2 == 0 else 1) for i, ch in enumerate(reversed(digits[:-1])))
    return (10 - total % 10) % 10 == int(digits[-1])


def package_s10_valid(number: str) -> bool:
    """An international "RR123456789US" number's check digit (S10)."""
    digits = number[2:10]
    if len(number) != 13 or not digits.isdigit():
        return False
    remainder = 11 - sum(int(ch) * w for ch, w in zip(digits, (8, 6, 4, 2, 3, 5, 9, 7))) % 11
    return {10: 0, 11: 5}.get(remainder, remainder) == int(number[10])


def package_tracking_in(text: str) -> tuple[str | None, str | None]:
    """The tracking number an email carries and its carrier (None when only the email can say): only what a check digit or a label vouches for."""
    for number in _PACKAGE_UPS.findall(text):
        if package_ups_valid(number):
            return number, "ups"
    for number in _PACKAGE_USPS.findall(text):
        if package_usps_valid(number):
            return number, "usps"
    for number in _PACKAGE_S10.findall(text):
        if package_s10_valid(number):
            return number, "usps"
    tba = _PACKAGE_AMAZON_TBA.search(text)
    if tba:
        return tba.group(1), "amazon"
    named = _PACKAGE_NAMED_SERVICE.search(text)
    if named:
        return named.group(2), named.group(1).lower()
    for number in _PACKAGE_LABELLED.findall(text):
        # A word that happens to follow "tracking" ("Tracking information will…") has no digit in it.
        if sum(ch.isdigit() for ch in number) >= 6:
            return number, None
    return None, None


def package_classify_number(raw: str) -> tuple[str, str] | None:
    """A tracking number someone typed, tidied, and its carrier ("other" when only its shape is known); None for what can't be one."""
    number = re.sub(r"[\s-]", "", raw).upper()
    if not re.fullmatch(r"[A-Z0-9]{8,34}", number) or not any(ch.isdigit() for ch in number):
        return None
    if number.startswith("1Z"):
        return (number, "ups") if package_ups_valid(number) else None
    if _PACKAGE_USPS.fullmatch(number) and package_usps_valid(number):
        return number, "usps"
    if _PACKAGE_S10.fullmatch(number):
        return (number, "usps") if package_s10_valid(number) else None
    if _PACKAGE_AMAZON_TBA.fullmatch(number):
        return number, "amazon"
    if number.isdigit() and len(number) in (12, 15, 20, 22):
        return number, "fedex"
    if number.isdigit() and len(number) == 10:
        return number, "dhl"
    return number, "other"


def package_track_url(carrier: str | None, tracking: str | None, order_id: str | None) -> str | None:
    if carrier == "amazon" or (order_id and _PACKAGE_AMAZON_ORDER.fullmatch(order_id)):
        return f"https://www.amazon.com/gp/your-account/order-details?orderID={quote(order_id)}" if order_id else None
    template = PACKAGE_TRACK_URLS.get(carrier or "")
    return template.format(quote(tracking)) if template and tracking else None


def package_domain(sender: str) -> str:
    found = re.search(r"@([\w.-]+)", sender)
    return found.group(1).lower() if found else ""


def package_shop(sender: str, domain: str, subject: str) -> str | None:
    """Who the package is from: a known shop's own name, the name in "Your SimpliSafe shipment", or the sender's. None for a carrier writing for itself."""
    in_subject = _PACKAGE_SHOP_IN_SUBJECT.match(subject.strip())
    known = next((name for piece, name in _PACKAGE_DOMAINS if piece in domain), None)
    if known:
        return known
    if any(domain == d or domain.endswith("." + d) for d in _PACKAGE_CARRIER_DOMAINS):
        return None
    if in_subject:
        return in_subject.group(1)
    display = re.sub(r"\s*<.*$", "", sender).strip().strip('"')
    if display and "@" not in display and not re.search(r"no.?reply|notifications?|support|admin|orders?$", display, re.IGNORECASE):
        return display[:40]
    return None


def package_stage(subject: str) -> str | None:
    """What a subject line says happened, by the first of these it matches, most telling first."""
    return next((stage for stage, words in _PACKAGE_STAGE_WORDS if words.search(subject)), None)


def package_day_of(month: str, day: int, year: str | None, sent: datetime) -> str | None:
    """A date an email named without a year: the coming one, unless it was only just past."""
    number = _PACKAGE_MONTHS.get(month[:3].lower())
    if number is None:
        return None
    try:
        if year:
            return datetime(int(year), number, day).date().isoformat()
        found = datetime(sent.year, number, day).date()
        if (sent.date() - found).days > 60:
            found = datetime(sent.year + 1, number, day).date()
        return found.isoformat()
    except ValueError:
        return None


def _package_clock(hour: str, minute: str | None, half: str) -> str:
    value = int(hour) % 12 + (12 if half.lower() == "p" else 0)
    return f"{value:02d}:{int(minute or 0):02d}"


def package_expected(lines: list[str], subject: str, sent: datetime) -> dict[str, Any] | None:
    """When an email says it will come: {"from", "to"} days, and a "start"/"end" clock where it narrows the day. None when it says nothing."""
    today = sent.date()
    for line in [subject] + lines:
        if not _PACKAGE_EXPECT_LINE.search(line):
            continue
        low = line.lower()
        tail = line[_PACKAGE_EXPECT_LINE.search(line).start():]
        dates = [package_day_of(m.group(2), int(m.group(3)), m.group(4), sent) for m in _PACKAGE_DATE.finditer(tail)]
        dates = [d for d in dates if d]
        if not dates:
            for m in _PACKAGE_NUMERIC_DATE.finditer(tail):
                try:
                    year = int(m.group(3)) if m.group(3) else sent.year
                    day = datetime(year + 2000 if year < 100 else year, int(m.group(1)), int(m.group(2))).date()
                    # "1/2" in a late December email is the coming January.
                    if not m.group(3) and day < today - timedelta(days=60):
                        day = day.replace(year=day.year + 1)
                    dates.append(day.isoformat())
                except ValueError:
                    pass
        if not dates:
            if re.search(r"\btoday\b", low):
                dates = [today.isoformat()]
            elif re.search(r"\btomorrow\b", low):
                dates = [(today + timedelta(days=1)).isoformat()]
            else:
                # "Arriving Tuesday": the next one.
                named = next((d for d in _PACKAGE_DAYS if re.search(rf"\b{d}\b", low)), None)
                if named is None:
                    continue
                ahead = (_PACKAGE_DAYS.index(named) - today.weekday()) % 7
                dates = [(today + timedelta(days=ahead)).isoformat()]
        found: dict[str, Any] = {"from": dates[0], "to": dates[-1] if dates[-1] >= dates[0] else dates[0]}
        between = _PACKAGE_BETWEEN.search(line)
        by = _PACKAGE_BY.search(line)
        if between:
            found["start"] = _package_clock(between.group(1), between.group(2), between.group(3))
            found["end"] = _package_clock(between.group(4), between.group(5), between.group(6))
        elif by:
            found["end"] = _package_clock(by.group(1), by.group(2), by.group(3))
        return found
    return None


def package_title(subject: str, domain: str, lines: list[str]) -> str | None:
    """What is in it, as the email has it: Amazon's categories, or the item an eBay subject quotes. Data, not copy."""
    subject = subject.strip()
    if "amazon." in domain:
        match = _PACKAGE_AMAZON_ITEMS.match(subject)
        if match and not re.match(r"^(update|estimate)\b", match.group(1), re.IGNORECASE):
            return match.group(1).strip().rstrip(".")[:120] or None
        quoted = re.search(r"[\"“](.+?)[\"”]", subject)
        return quoted.group(1).rstrip(".…")[:120] if quoted else None
    quoted = re.search(r"[\"“](.+?)[\"”]", subject)
    if quoted:
        return quoted.group(1)[:120]
    colon = re.match(r"^\W*(?:out for delivery|order delivered|delivered|shipped|order shipped)\s*:\s*(.+)$", subject, re.IGNORECASE)
    return colon.group(1).rstrip(".…")[:120] if colon else None


def parse_package_email(sender: str, subject: str, text: str, sent_at: float, zone: Any = None) -> dict[str, Any] | None:
    """
    What one email says about one package, or None when it isn't about a package at all: "stage",
    "tracking", "carrier", "order", "shop", "title", "expected", "signature", "left", "total".
    It reads loosely, since every shop words these its own way: a subject that says something
    happened, from somebody who can vouch for it (a tracking number, an order number, a carrier, or
    "your order"), and not an advert that happens to say "delivered".
    """
    subject = " ".join(_PACKAGE_INVISIBLE.sub("", subject).split())
    text = _PACKAGE_INVISIBLE.sub("", text)
    domain = package_domain(sender)
    if _PACKAGE_NOT_SHIPPING_SENDERS.search(domain):
        return None
    stage = package_stage(subject)
    if stage is None or re.match(r"review your upcoming delivery", subject, re.IGNORECASE):
        return None
    lines = [" ".join(line.split()) for line in text.splitlines() if line.strip()]
    body = "\n".join(lines)
    tracking, tracking_carrier = package_tracking_in(subject + "\n" + body)
    amazon_order = _PACKAGE_AMAZON_ORDER.search(subject + "\n" + body)
    # A shop's order number has a digit in it, or is a run of capitals: not the "DELIVERED" of "ORDER DELIVERED".
    order_match = amazon_order or next((m for m in _PACKAGE_ORDER.finditer(subject + "\n" + body) if any(ch.isdigit() for ch in m.group(1)) or (len(m.group(1)) >= 8 and m.group(1).lower() not in _PACKAGE_NOT_ORDERS)), None)
    order = order_match.group(1) if order_match else None
    # Amazon puts several orders in one box, and says so by naming them all.
    orders = list(dict.fromkeys(_PACKAGE_AMAZON_ORDER.findall(subject + "\n" + body))) if amazon_order else ([order] if order else [])
    carrier_from = next((c for d, c in _PACKAGE_CARRIER_DOMAINS.items() if domain == d or domain.endswith("." + d)), None)
    # A carrier's newsletter from its own domain says "delivered" too: it has to be about a parcel.
    known_shop = any(piece in domain for piece, _ in _PACKAGE_DOMAINS)
    vouched = bool(tracking or order or _PACKAGE_POSSESSIVE.search(subject) or ((carrier_from or known_shop) and re.search(r"\b(order|package|shipment|delivery|item)\b", subject, re.IGNORECASE)) or "amazon." in domain)
    if not vouched or (_PACKAGE_ADVERT.search(subject) and not tracking and not amazon_order):
        return None
    if "amazon." in domain and not amazon_order and stage not in ("refund", "return", "return_sent"):
        return None
    if stage in ("refund", "return", "return_sent") and not (order or tracking):
        return None
    sent = datetime.fromtimestamp(sent_at, zone or timezone.utc)
    carrier = carrier_from or tracking_carrier
    if carrier is None:
        lowered = subject + "\n" + body
        carrier = next((name for name, words in _PACKAGE_CARRIERS if re.search(words, lowered, re.IGNORECASE)), None)
    if carrier is None and "amazon." in domain:
        carrier = "amazon"
    left = next((line for line in lines if len(line) < 120 and _PACKAGE_LEFT.search(line)), None) if stage == "delivered" else None
    total = amazon_total(body) if stage in ("ordered", "shipped") else None
    return {
        "stage": stage, "tracking": tracking, "carrier": carrier, "order": order, "orders": orders, "shop": package_shop(sender, domain, subject),
        "title": package_title(subject, domain, lines), "expected": package_expected(lines, subject, sent) if stage != "delivered" else None,
        "signature": bool(_PACKAGE_SIGNATURE.search(body)), "left": left, "total": total,
    }


def package_id_of(*parts: str) -> str:
    return hashlib.sha1("|".join(parts).encode()).hexdigest()[:16]


def package_settings() -> dict[str, Any]:
    held = state_get(PACKAGE_SETTINGS_KEY) or {}
    return {"notify": {**PACKAGE_NOTICE_DEFAULTS, **(held.get("notify") or {})}, "hide_new": dict(held.get("hide_new") or {})}


def package_people() -> list[str]:
    return sorted(AMAZON_GMAIL_TOKENS)


_PACKAGE_COLUMNS = ("id", "owner", "source", "carrier", "tracking", "order_id", "shop", "title", "nickname", "stage", "stage_at", "expected_from", "expected_to", "window_start", "window_end",
                    "signature", "problem", "problem_at", "left_note", "total", "created", "updated", "delivered_at", "surprise", "pinned", "archived", "deleted", "notes", "return_by", "return_state",
                    "refund_at", "porch", "porch_at", "porch_by", "verdict", "verdict_what", "verdict_confidence", "verdict_by", "visit_event", "visit_at", "tracker_on", "merged_into")


def package_row(package_id: str) -> dict[str, Any] | None:
    row = with_db(lambda c: c.execute(f"SELECT {','.join(_PACKAGE_COLUMNS)} FROM packages WHERE id=?", (package_id,)).fetchone())
    return dict(zip(_PACKAGE_COLUMNS, row)) if row else None


def package_rows(where: str = "1=1", args: tuple = ()) -> list[dict[str, Any]]:
    rows = with_db(lambda c: c.execute(f"SELECT {','.join(_PACKAGE_COLUMNS)} FROM packages WHERE {where}", args).fetchall())
    return [dict(zip(_PACKAGE_COLUMNS, row)) for row in rows]


def package_update(package_id: str, **fields: Any) -> None:
    names = ", ".join(f"{name}=?" for name in fields)
    with_db(lambda c: (c.execute(f"UPDATE packages SET {names} WHERE id=?", (*fields.values(), package_id)), c.commit()))


def package_log(package_id: str, at: float, kind: str, detail: str | None = None, key: str | None = None) -> bool:
    """A line of a package's timeline, once per `key`. Whether it was new."""
    def write(c: sqlite3.Connection) -> bool:
        cursor = c.execute("INSERT OR IGNORE INTO package_events (package_id, at, kind, detail, key) VALUES (?,?,?,?,?)", (package_id, at, kind, detail, key or f"{kind}:{at:.0f}"))
        c.commit()
        return cursor.rowcount > 0
    return with_db(write)


def package_told(package_id: str, what: str, now: float) -> bool:
    """Marks a push as sent. False when it had been already, so each goes out once."""
    return package_log(package_id, now, f"told:{what}", key=f"told:{what}")


def package_find(parsed: dict[str, Any], mailbox: str, sent_at: float) -> str | None:
    """
    The package this email is about, if one is already held: by tracking number, then by the order
    (an order's shipments apart by what is in each, or the only one still open), then, for a shop
    that gives neither, its last open package.
    """
    tracking, order, title = parsed.get("tracking"), parsed.get("order"), parsed.get("title")
    if tracking:
        row = with_db(lambda c: c.execute("SELECT id FROM packages WHERE tracking=? ORDER BY created LIMIT 1", (tracking,)).fetchone())
        if row:
            return row[0]
    orders = parsed.get("orders") or ([order] if order else [])
    if orders:
        marks = ",".join("?" * len(orders))
        rows = with_db(lambda c: c.execute(f"SELECT id, title, stage, tracking FROM packages WHERE order_id IN ({marks}) ORDER BY created", orders).fetchall())
        rows = [r for r in rows if not (tracking and r[3] and r[3] != tracking)]
        same = [r for r in rows if title and r[1] == title]
        open_ones = [r for r in rows if r[2] != "delivered"]
        if same:
            return same[-1][0]
        if len(open_ones) == 1 and (parsed["stage"] != "shipped" or not open_ones[0][3] and open_ones[0][2] == "ordered"):
            return open_ones[0][0]
        # News of the order rather than of a shipment of it: the one still coming that it most likely means.
        if rows and parsed["stage"] in ("refund", "return", "return_sent", "exception", "attempted", "expected"):
            return (open_ones or rows)[-1][0]
        # A box of several orders can be delivered under an order number its "shipped" email didn't give: what it held says which it was.
        if parsed["stage"] in ("out_for_delivery", "delivered") and title:
            row = with_db(lambda c: c.execute(
                "SELECT id FROM packages WHERE title=? AND owner=? AND stage!='delivered' AND deleted=0 AND created>=? ORDER BY created LIMIT 1", (title, mailbox, sent_at - 21 * 86400)
            ).fetchone())
            if row:
                return row[0]
        return None
    shop = parsed.get("shop")
    if shop and not tracking:
        row = with_db(lambda c: c.execute(
            "SELECT id FROM packages WHERE shop=? AND owner=? AND stage!='delivered' AND deleted=0 AND created>=? ORDER BY created DESC LIMIT 1", (shop, mailbox, sent_at - 21 * 86400)
        ).fetchone())
        return row[0] if row else None
    return None


def package_categories(title: str | None) -> set[str]:
    """What a title says is in the box, one by one: Amazon's emails name categories, "Coffee, Dairy, and more"."""
    return {part.strip().lower() for part in re.split(r",", re.sub(r",?\s*and more\s*$", "", title or "", flags=re.IGNORECASE)) if part.strip()}


def package_merge_same_box(package_id: str, parsed: dict[str, Any], sent_at: float) -> None:
    """
    Amazon puts separate orders in one box, emails "Ordered" for each and then "Shipped" and
    "Delivered" once, naming what's in all of them. What is left over of the others would be on its
    way for ever, so whatever this email's list covers goes into this package, its price with it.
    Two orders of the same thing in different boxes stay apart: only what differs from this package
    is taken to be part of it.
    """
    title = parsed.get("title")
    named = package_categories(title)
    package = package_row(package_id)
    if package is None or parsed["stage"] not in ("shipped", "out_for_delivery", "delivered") or "amazon" != (package["carrier"] or "") or len(named) < 2:
        return
    mine = package_categories(package["title"])
    others = package_rows("id!=? AND owner=? AND carrier='amazon' AND stage!='delivered' AND deleted=0 AND created>=?", (package_id, package["owner"], sent_at - 14 * 86400))
    total = package["total"]
    merged = False
    for other in others:
        theirs = package_categories(other["title"])
        if theirs and theirs <= named and theirs != mine:
            package_update(other["id"], deleted=1, merged_into=package_id)
            package_log(package_id, sent_at, "same_box", other["title"], key=f"box:{other['id']}")
            # What someone said of the one that went stays with the box. A surprise in it makes all of it one: the title names it.
            package = package_row(package_id)
            kept = {column: other[column] for column in ("nickname", "notes") if other[column] and not package[column]}
            if other["surprise"]:
                kept["surprise"] = 1
            if other["pinned"]:
                kept["pinned"] = 1
            if kept:
                package_update(package_id, **kept)
            # A shipment's email gives its whole total already; otherwise the prices add up.
            if other["total"] and parsed.get("total") is None:
                total = (total or 0) + other["total"]
            merged = True
    if mine < named or merged:
        package_update(package_id, title=title, total=total)


def package_create(parsed: dict[str, Any], mailbox: str, sent_at: float) -> str:
    settings = package_settings()
    seed = parsed.get("tracking") or (f"{parsed['order']}|{parsed.get('title') or ''}" if parsed.get("order") else f"{mailbox}|{parsed.get('shop')}|{sent_at:.0f}")
    package_id = package_id_of(seed)
    surprise = 1 if settings["hide_new"].get(mailbox) else 0
    with_db(lambda c: (c.execute(
        "INSERT OR IGNORE INTO packages (id, owner, source, carrier, tracking, order_id, shop, title, stage, stage_at, created, updated, surprise) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (package_id, mailbox, "email", parsed.get("carrier"), parsed.get("tracking"), parsed.get("order"), parsed.get("shop"), parsed.get("title"), "ordered", sent_at, sent_at, sent_at, surprise),
    ), c.commit()))
    return package_id


def package_placed(message_id: str) -> tuple[str, str] | None:
    """The package an email was filed under, and what it was read as saying, if it has been read before."""
    return with_db(lambda c: c.execute("SELECT package_id, kind FROM package_events WHERE key=? ORDER BY id LIMIT 1", (f"mail:{message_id}",)).fetchone())


def package_store(mailbox: str, message_id: str, parsed: dict[str, Any], sent_at: float, now: float, push: Callable[..., Any] | None = None) -> str | None:
    """
    Moves a package along by what one email said, creating it if this is the first heard of it. Its
    id, or None when a refund names nothing held. An email read again (`PACKAGE_PARSER`) goes where
    it went, tombstone or not, so a change in how titles are read can't bring back what someone
    forgot; and if it still says the same, it only fills in blanks, so what someone said since stays.
    """
    placed = package_placed(message_id)
    package_id = placed[0] if placed else package_find(parsed, mailbox, sent_at)
    if package_id is None:
        if parsed["stage"] in ("refund", "return", "return_sent"):
            return None
        package_id = package_create(parsed, mailbox, sent_at)
    package = package_row(package_id)
    # An order that went in with another's box: its news is that package's now.
    while package is not None and package["merged_into"]:
        package_id = package["merged_into"]
        package = package_row(package_id)
    if package is None or package["deleted"]:
        return package_id
    fresh = now - sent_at < PACKAGE_FRESH_SECONDS
    fill: dict[str, Any] = {}
    for column, field in (("tracking", "tracking"), ("carrier", "carrier"), ("order_id", "order"), ("shop", "shop"), ("title", "title"), ("total", "total"), ("left_note", "left")):
        if parsed.get(field) is not None and not package[column]:
            fill[column] = parsed[field]
    # A carrier's own word for who carries it outranks what a shop's email guessed.
    if parsed.get("carrier") and package["carrier"] in (None, "other"):
        fill["carrier"] = parsed["carrier"]
    if parsed.get("signature") and not package["signature"]:
        fill["signature"] = 1
    # An order's first shipment takes its place, and is only what is in that shipment: the rest will have their own.
    if parsed["stage"] == "shipped" and package["stage"] == "ordered":
        for column, field in (("title", "title"), ("total", "total")):
            if parsed.get(field) is not None:
                fill[column] = parsed[field]
    if parsed.get("expected") and sent_at >= float(package["updated"] or 0) - 60:
        expected = parsed["expected"]
        fill.update(expected_from=expected["from"], expected_to=expected["to"], window_start=expected.get("start"), window_end=expected.get("end"))
    if fill:
        package_update(package_id, **fill)
    stage = parsed["stage"]
    package_merge_same_box(package_id, parsed, sent_at)
    if placed and placed[1] == stage:
        return package_id
    if placed:
        with_db(lambda c: (c.execute("UPDATE package_events SET kind=? WHERE package_id=? AND key=?", (stage, placed[0], f"mail:{message_id}")), c.commit()))
    package_log(package_id, sent_at, stage, parsed.get("left"), key=f"mail:{message_id}")
    if stage in PACKAGE_STAGES:
        package_advance(package_id, stage, sent_at, now, tell=fresh, push=push)
    elif stage == "expected":
        package_update(package_id, updated=max(float(package["updated"] or 0), sent_at))
        # Someone can promise a day, so it has left wherever it was.
        if package["stage"] == "ordered":
            package_advance(package_id, "shipped", sent_at, now, tell=False, push=push)
    elif stage in ("attempted", "exception"):
        # A first read goes newest first in batches, so this can be older than the delivery already read.
        if package["stage"] == "delivered" and sent_at <= float(package["delivered_at"] or 0):
            return package_id
        package_update(package_id, problem=stage, problem_at=sent_at, updated=max(float(package["updated"] or 0), sent_at))
        if fresh and package_told(package_id, f"problem:{stage}:{sent_at:.0f}", now):
            package_notify(package_id, "problem_" + stage, now, push=push)
    elif stage in ("refund", "return", "return_sent"):
        if stage == "refund" and package["stage"] == "ordered":
            # Money back for something that never left: a cancelled order, so nothing is coming.
            package_update(package_id, archived=PACKAGE_QUIET)
        elif package["stage"] != "delivered":
            # Something going back has been here: Amazon doesn't always write when it delivers. When isn't known, so the camera isn't asked about now, and it isn't on the porch.
            package_advance(package_id, "delivered", sent_at, now, tell=False, push=push, judge=False)
            package_update(package_id, verdict_by="return")
        state = {"refund": "refunded", "return_sent": "sent", "return": "started"}[stage]
        order_of_states = ("none", "started", "sent", "refunded")
        if order_of_states.index(state) >= order_of_states.index(package["return_state"] or "none"):
            package_update(package_id, return_state=state, updated=max(float(package["updated"] or 0), sent_at))
        if stage == "refund":
            package_update(package_id, refund_at=sent_at)
    if parsed.get("signature") and fresh and stage != "delivered" and package["stage"] != "delivered" and package_told(package_id, "signature", now):
        package_notify(package_id, "signature", now, push=push)
    return package_id


def package_advance(package_id: str, stage: str, at: float, now: float, tell: bool = True, push: Callable[..., Any] | None = None, judge: bool = True) -> None:
    """A package reached `stage`. Only ever forward: a shop's late "shipped" email doesn't take a delivered parcel off the step."""
    package = package_row(package_id)
    if package is None:
        return
    if PACKAGE_STAGES.index(stage) < PACKAGE_STAGES.index(package["stage"]):
        return
    moved = stage != package["stage"]
    fields: dict[str, Any] = {"updated": max(float(package["updated"] or 0), at)}
    if moved:
        fields.update(stage=stage, stage_at=at)
        # It went quiet and then didn't: back on the list. Someone's own archiving stays.
        if package["archived"] == PACKAGE_QUIET:
            fields["archived"] = 0
    if stage == "delivered":
        fields.update(delivered_at=at, problem=None, problem_at=None)
        # Only on arriving: a window someone cleared stays cleared.
        if moved and not package["return_by"]:
            days = PACKAGE_RETURN_DAYS.get(package["shop"] or "")
            if days:
                fields["return_by"] = (datetime.fromtimestamp(at, household_zone() or timezone.utc).date() + timedelta(days=days)).isoformat()
    package_update(package_id, **fields)
    if not moved:
        return
    if stage == "delivered" and judge:
        # The camera's word comes first, so the push can carry it. Its trouble mustn't cost the push.
        try:
            package_judge(package_id, now)
        except Exception:
            log.exception("packages: couldn't judge %s", package_id)
    if tell and stage != "ordered" and package_told(package_id, stage, now):
        package_notify(package_id, stage, now, push=push)


def package_read_mailbox(mailbox: str, now: float, full: bool, push: Callable[..., Any] | None = None) -> int:
    """Reads one mailbox's shipping emails not read yet, oldest first, so each package moves along in order. How many."""
    zone = household_zone() or timezone.utc
    days = PACKAGE_LOOKBACK_DAYS if full else 2
    seen = {row[0] for row in with_db(lambda c: c.execute("SELECT message_id FROM package_emails WHERE mailbox=?", (mailbox,)).fetchall())}
    fresh: list[str] = []
    page: str | None = None
    for _ in range(AMAZON_MAX_PAGES if full else 2):
        listed = gmail_get(mailbox, "/messages", now, q=f"{PACKAGE_QUERY} newer_than:{days}d", maxResults=100, **({"pageToken": page} if page else {}))
        fresh += [m["id"] for m in listed.get("messages") or [] if m["id"] not in seen]
        page = listed.get("nextPageToken")
        if not page or len(fresh) >= PACKAGE_MAX_EMAILS:
            break
    # Newest first from Gmail, so a first read with more than it takes at once keeps the latest.
    fresh = list(reversed(fresh[:PACKAGE_MAX_EMAILS]))
    for message_id in fresh:
        time.sleep(PACKAGE_FETCH_GAP)
        message = gmail_get(mailbox, f"/messages/{message_id}", now, format="full")
        payload = message.get("payload") or {}
        sent_at = int(message.get("internalDate") or now * 1000) / 1000
        plain, markup = gmail_parts(payload)
        text = html_text(markup) if markup else plain
        # Filed, then marked read, so what fails is read again next time and one bad email holds up nothing else.
        try:
            parsed = parse_package_email(gmail_header(payload, "from"), gmail_header(payload, "subject"), text, sent_at, zone)
            if parsed:
                package_store(mailbox, message_id, parsed, sent_at, now, push=push)
        except Exception:
            log.exception("packages: couldn't file an email in %s", mailbox)
            continue
        with_db(lambda c: (c.execute("INSERT OR REPLACE INTO package_emails VALUES (?,?,?,?)", (message_id, mailbox, now, parsed["stage"] if parsed else None)), c.commit()))
    return len(fresh)


def package_reread(now: float) -> None:
    """Forgets which emails were read, when they are now read differently (`PACKAGE_PARSER`): what someone said about a package stays, since it is on the package."""
    if state_get(PACKAGE_PARSER_KEY) == PACKAGE_PARSER:
        return
    with_db(lambda c: (c.execute("DELETE FROM package_emails"), c.commit()))
    state_set(PACKAGE_PARSER_KEY, PACKAGE_PARSER)
    state_set(PACKAGE_FULL_KEY, None)


def package_sync(now: float | None = None, full: bool | None = None) -> None:
    """Every mailbox's new shipping emails. A mailbox that fails is logged, and the next read tries again."""
    if not amazon_on() or not _package_lock.acquire(blocking=False):
        return
    now = time.time() if now is None else now
    try:
        package_reread(now)
        full_due = now - float(state_get(PACKAGE_FULL_KEY) or 0) >= PACKAGE_FULL_SECONDS
        read, failed = 0, []
        whole = full_due if full is None else full
        for mailbox in sorted(AMAZON_GMAIL_TOKENS):
            try:
                read += package_read_mailbox(mailbox, now, whole)
            except Exception as e:
                log.warning("packages: couldn't read %s: %s", mailbox, e)
                failed.append(mailbox)
        if whole:
            state_set(PACKAGE_FULL_KEY, now)
        state_set(PACKAGE_SYNCED_KEY, {"at": now, "emails": read, "failed": failed})
    finally:
        _package_lock.release()


def package_camera_on() -> bool:
    return bool(PACKAGE_CAMERA) and PACKAGE_CAMERA in detect_sizes()


def package_frame(at: float) -> bytes | None:
    """The door camera's recording at a moment, as JPEG, or None when it isn't there (not on disk yet, or gone)."""
    try:
        r = requests.get(f"{FRIGATE}/api/{PACKAGE_CAMERA}/recordings/{at:.1f}/snapshot.jpg", timeout=30)
    except Exception as e:
        log.warning("packages: no frame at %.0f: %s", at, e)
        return None
    if r.status_code != 200 or not r.headers.get("content-type", "").startswith("image"):
        return None
    return r.content


def package_crop(jpeg: bytes, edge: int = 1024) -> bytes:
    """The part of a frame where parcels are left, long edge `edge`, as JPEG."""
    from io import BytesIO

    from PIL import Image

    frame = Image.open(BytesIO(jpeg)).convert("RGB")
    x, y, w, h = PACKAGE_SPOT
    crop = frame.crop((round(x * frame.width), round(y * frame.height), round((x + w) * frame.width), round((y + h) * frame.height)))
    scale = edge / max(crop.width, crop.height)
    crop = crop.resize((max(1, round(crop.width * scale)), max(1, round(crop.height * scale))), Image.LANCZOS)
    out = BytesIO()
    crop.save(out, format="JPEG", quality=88)
    return out.getvalue()


PACKAGE_COMPARE_PROMPT = (
    "Two pictures from one fixed security camera, looking down through a railing onto a house's top landing and its doormat. The first was taken BEFORE a visit, the "
    "second AFTER. The railing, decorations, plants and the mat are always there, and the light may change. Compare the mat and the floor around it. Did a parcel appear in "
    "the AFTER picture: a cardboard box, a flat brown or white mailer, a padded envelope, or a plastic delivery bag? It may be flat on the mat or partly behind the railing. "
    "appeared: true if a parcel is in AFTER and not in BEFORE. gone: true if a parcel in BEFORE is not in AFTER. what: box, mailer, bag or none. confidence: 0 to 1."
)
PACKAGE_COMPARE_SCHEMA = {"type": "object", "properties": {"appeared": {"type": "boolean"}, "gone": {"type": "boolean"}, "what": {"type": "string", "enum": ["box", "mailer", "bag", "none"]},
                                                           "confidence": {"type": "number"}}, "required": ["appeared", "gone", "what", "confidence"]}
PACKAGE_LOOK_PROMPT = (
    "A security camera looks down through a railing at the top landing of a house's front steps, with a doormat on it. List what lies on the mat and the floor, then say "
    "whether a delivered parcel is there: a cardboard box, a flat mailer, a padded envelope or a plastic delivery bag. A person, a pet, sunlight, shadow, decorations or an open door are not parcels."
)
PACKAGE_LOOK_SCHEMA = {"type": "object", "properties": {"on_landing": {"type": "string"}, "parcel": {"type": "boolean"}}, "required": ["on_landing", "parcel"]}


def package_ask_model(prompt: str, schema: dict[str, Any], pictures: list[bytes]) -> dict[str, Any] | None:
    """What the box's vision model answers, or None when it isn't there or says something else."""
    import base64

    if not OLLAMA:
        return None
    try:
        r = requests.post(f"{OLLAMA}/api/chat", json={
            "model": VLM_MODEL,
            "messages": [{"role": "user", "content": prompt, "images": [base64.b64encode(p).decode() for p in pictures]}],
            "format": schema, "stream": False, "keep_alive": "24h",
            "options": {"temperature": 0, "num_ctx": 8192, "num_gpu": 99},
        }, timeout=120)
        r.raise_for_status()
        return json.loads(r.json()["message"]["content"])
    except Exception as e:
        log.warning("packages: the model couldn't look: %s", e)
        return None


def package_door_ids() -> list[str]:
    rows = with_db(lambda c: c.execute("SELECT id, name FROM doors").fetchall())
    return [door_id for door_id, name in rows if name and PACKAGE_DOOR.search(name)]


def package_door_opened(first: float, last: float) -> bool | None:
    """Whether the front door opened between two moments, or None when no sensor could say."""
    doors = package_door_ids()
    if not doors:
        return None
    marks = ",".join("?" * len(doors))
    return bool(with_db(lambda c: c.execute(f"SELECT 1 FROM door_events WHERE open=1 AND at BETWEEN ? AND ? AND door IN ({marks}) LIMIT 1", (first, last, *doors)).fetchone()))


def package_person_events(after: float, before: float | None = None) -> list[dict[str, Any]]:
    """
    The door camera's person events between two moments, oldest first, without the ones Frigate's
    detector imagined (see "phantom people"). Frigate answers with the newest of what it has, and
    this camera sees 100 people in a couple of days, so a look further back has to say where it stops.
    """
    params: dict[str, Any] = {"cameras": PACKAGE_CAMERA, "labels": "person", "after": after, "limit": 200}
    if before is not None:
        params["before"] = before
    r = requests.get(f"{FRIGATE}/api/events", params=params, timeout=15)
    r.raise_for_status()
    spots = phantom_spots(PACKAGE_CAMERA)
    events = [e for e in r.json() if not is_phantom(e, spots)]
    return sorted(events, key=lambda e: float(e.get("start_time") or 0))


def package_group_visits(events: list[dict[str, Any]], now: float) -> list[dict[str, Any]]:
    """Events close together as one visit each; a visit still going, or just over, isn't one yet."""
    visits: list[dict[str, Any]] = []
    for event in events:
        start = float(event.get("start_time") or 0)
        end = event.get("end_time")
        end = float(end) if end is not None else now
        name = face_name(event)
        if visits and start - visits[-1]["end"] <= PACKAGE_VISIT_GAP:
            visits[-1]["end"] = max(visits[-1]["end"], end)
            visits[-1]["open"] = visits[-1]["open"] or event.get("end_time") is None
            visits[-1]["name"] = visits[-1]["name"] or name
        else:
            visits.append({"id": event["id"], "start": start, "end": end, "open": event.get("end_time") is None, "name": name})
    return [v for v in visits if not v["open"] and now - v["end"] >= PACKAGE_VISIT_SETTLE]


def package_check_visit(visit: dict[str, Any]) -> dict[str, Any]:
    """Asks about one visit, keeps what it was told in `package_visits`. Its row: "appeared" is None where there were no pictures or no answer."""
    before, after = package_frame(visit["start"] - PACKAGE_BEFORE_SECONDS), package_frame(visit["end"] + PACKAGE_AFTER_SECONDS)
    appeared = gone = look = what = confidence = None
    if before and after:
        try:
            compared = package_ask_model(PACKAGE_COMPARE_PROMPT, PACKAGE_COMPARE_SCHEMA, [package_crop(before), package_crop(after)])
            if compared is not None:
                appeared, gone, what, confidence = compared.get("appeared"), compared.get("gone"), compared.get("what"), compared.get("confidence")
            if appeared or gone:
                looked = package_ask_model(PACKAGE_LOOK_PROMPT, PACKAGE_LOOK_SCHEMA, [package_crop(after)])
                look = looked.get("parcel") if looked else None
        except Exception as e:
            log.warning("packages: couldn't look at visit %s: %s", visit["id"], e)
    door = package_door_opened(visit["start"] - 60, visit["end"] + PACKAGE_AFTER_SECONDS)
    row = {"event": visit["id"], "start": visit["start"], "end": visit["end"], "appeared": appeared, "gone": gone, "look": look, "what": what, "confidence": confidence, "door": door, "name": visit.get("name")}
    with_db(lambda c: (c.execute(
        "INSERT OR REPLACE INTO package_visits (event, start, end, appeared, gone, look, what, confidence, door, name) VALUES (?,?,?,?,?,?,?,?,?,?)",
        (visit["id"], visit["start"], visit["end"], appeared, gone, look, what, confidence, door, visit.get("name")),
    ), c.commit()))
    return row


_PACKAGE_VISIT_COLUMNS = ("event", "start", "end", "appeared", "gone", "look", "what", "confidence", "door", "name")


def package_visit_row(event: str) -> dict[str, Any]:
    row = with_db(lambda c: c.execute(f"SELECT {','.join(_PACKAGE_VISIT_COLUMNS)} FROM package_visits WHERE event=?", (event,)).fetchone())
    return dict(zip(_PACKAGE_VISIT_COLUMNS, row))


def package_visits_between(first: float, last: float) -> list[dict[str, Any]]:
    rows = with_db(lambda c: c.execute(f"SELECT {','.join(_PACKAGE_VISIT_COLUMNS)} FROM package_visits WHERE start BETWEEN ? AND ? ORDER BY start", (first, last)).fetchall())
    return [dict(zip(_PACKAGE_VISIT_COLUMNS, row)) for row in rows]


def package_window(package: dict[str, Any]) -> float:
    return PACKAGE_AMAZON_WINDOW if package["carrier"] == "amazon" else PACKAGE_OTHER_WINDOW


def package_pick_visit(visits: list[dict[str, Any]], delivered_at: float) -> dict[str, Any] | None:
    """
    The visit that was the delivery: where the door didn't open from the inside. Someone in the
    house going out isn't one. Of those, a parcel the model saw come, or failing that, the last
    before the email, which on the household's deliveries was it each time.
    """
    candidates = [v for v in visits if not v.get("door") and not v.get("name")] or [v for v in visits if not v.get("door")]
    if not candidates:
        return None
    seen = [v for v in candidates if v.get("appeared") and float(v.get("confidence") or 0) >= PACKAGE_MODEL_SURE]
    pool = seen or candidates
    return min(pool, key=lambda v: abs(delivered_at - float(v["end"])))


def package_save_frames(package_id: str, visit: dict[str, Any]) -> bool:
    """Keeps the two pictures of a delivery's visit: the recordings they come from don't last."""
    before, after = package_frame(float(visit["start"]) - PACKAGE_BEFORE_SECONDS), package_frame(float(visit["end"]) + PACKAGE_AFTER_SECONDS)
    if not before or not after:
        return False
    try:
        os.makedirs(PACKAGE_FRAME_DIR, exist_ok=True)
        for name, jpeg in (("before", before), ("after", after)):
            cropped = package_crop(jpeg)
            with open(os.path.join(PACKAGE_FRAME_DIR, f"{package_id}_{name}.jpg"), "wb") as f:
                f.write(cropped)
    except Exception as e:
        log.warning("packages: couldn't keep %s's pictures: %s", package_id, e)
        return False
    return True


def package_judge(package_id: str, now: float) -> str | None:
    """
    What the camera says of a delivery an email told of (the section's head has the levels), kept
    on the package with its pictures. None while there is nothing to go on: no camera, an email too
    old to look for, or a visit still too recent to be judged, which the next round looks at again.
    """
    package = package_row(package_id)
    if package is None or package["verdict_by"] or not package["delivered_at"] or not package_camera_on():
        return package["verdict"] if package else None
    # Left somewhere the door camera doesn't look: nothing to find, and no alarm in not finding it.
    if package["left_note"] and _PACKAGE_NOT_THE_DOOR.search(package["left_note"]):
        return None
    at = float(package["delivered_at"])
    if now - at > PACKAGE_CAMERA_REACH:
        return None
    first, last = at - package_window(package), at + PACKAGE_AFTER_EMAIL
    # A visit that ended moments before the email hasn't been asked about yet: ask now rather than make the push wait a round.
    try:
        events = [e for e in package_person_events(first, last) if float(e.get("start_time") or 0) <= last]
        for visit in package_group_visits(events, now):
            if not package_visits_between(visit["start"], visit["start"]):
                package_check_visit(visit)
        settling = any(e.get("end_time") is None or now - float(e["end_time"]) < PACKAGE_VISIT_SETTLE for e in events)
    except Exception as e:
        log.warning("packages: no camera events for %s: %s", package_id, e)
        return None
    visit = package_pick_visit(package_visits_between(first, last), at)
    if visit is None:
        if settling or now < last:
            return None
        verdict = "not_seen"
        package_update(package_id, verdict=verdict, porch=None)
        package_log(package_id, now, "camera_not_seen", key="camera:not_seen")
        return verdict
    sure = bool(visit.get("appeared")) and float(visit.get("confidence") or 0) >= PACKAGE_MODEL_SURE
    verdict = "confirmed" if sure else "seen"
    saved = package_save_frames(package_id, visit)
    porch = package["porch"] or "porch"
    package_update(package_id, verdict=verdict, verdict_what=visit.get("what") if sure else None, verdict_confidence=visit.get("confidence") if sure else None,
                   visit_event=visit["event"], visit_at=visit["start"], porch=porch, porch_at=package["porch_at"] or at)
    package_log(package_id, float(visit["start"]), f"camera_{verdict}", "frames" if saved else None, key=f"camera:{verdict}")
    return verdict


def package_watch_door(now: float, push: Callable[..., Any] | None = None) -> int:
    """Judges each new visit to the door camera, and what it means for a parcel out there or one with no email. How many."""
    if not package_camera_on():
        return 0
    after = float(state_get(PACKAGE_VISITS_KEY) or now - 3600)
    visits = package_group_visits(package_person_events(after), now)
    # A delivery's judging may have asked about a visit first; what it means still happens here, once.
    asked = dict(with_db(lambda c: c.execute("SELECT event, meant FROM package_visits WHERE start >= ?", (after,)).fetchall()))
    checked = 0
    for visit in visits:
        if asked.get(visit["id"]):
            continue
        if visit["id"] in asked:
            row = package_visit_row(visit["id"])
        else:
            row = package_check_visit(visit)
            checked += 1
        package_visit_meaning(row, now, push=push)
        with_db(lambda c: (c.execute("UPDATE package_visits SET meant=1 WHERE event=?", (visit["id"],)), c.commit()))
        state_set(PACKAGE_VISITS_KEY, float(visit["end"]))
    if visits:
        state_set(PACKAGE_VISITS_KEY, max(float(state_get(PACKAGE_VISITS_KEY) or 0), max(v["end"] for v in visits)))
    return checked


def package_retry_visits(now: float, push: Callable[..., Any] | None = None) -> int:
    """Asks again about the last hour's visits nothing could be said of, the model having been busy or the recording not there yet. How many it now has an answer for."""
    if now - float(_package_status.get("retry_at") or 0) < PACKAGE_RETRY_SECONDS:
        return 0
    _package_status["retry_at"] = now
    columns = ",".join(_PACKAGE_VISIT_COLUMNS)
    rows = with_db(lambda c: c.execute(f"SELECT {columns} FROM package_visits WHERE appeared IS NULL AND end >= ?", (now - PACKAGE_RETRY_REACH,)).fetchall())
    answered = 0
    for values in rows:
        held = dict(zip(_PACKAGE_VISIT_COLUMNS, values))
        row = package_check_visit({"id": held["event"], "start": held["start"], "end": held["end"], "name": held["name"]})
        if row["appeared"] is None:
            continue
        answered += 1
        package_visit_meaning(row, now, push=push)
        with_db(lambda c: (c.execute("UPDATE package_visits SET meant=1 WHERE event=?", (row["event"],)), c.commit()))
        if row["appeared"] and float(row.get("confidence") or 0) >= PACKAGE_MODEL_SURE:
            for package in package_rows("verdict='seen' AND verdict_by IS NULL AND deleted=0 AND visit_event=?", (row["event"],)):
                package_update(package["id"], verdict="confirmed", verdict_what=row.get("what"), verdict_confidence=row.get("confidence"))
                package_log(package["id"], float(row["start"]), "camera_confirmed", "frames", key="camera:confirmed")
    return answered


def package_visit_meaning(visit: dict[str, Any], now: float, push: Callable[..., Any] | None = None) -> None:
    """One judged visit's consequences: a parcel taken in, one that may be gone, and one that came with no email."""
    out = package_rows("porch='porch' AND deleted=0 AND delivered_at < ?", (visit["start"],))
    if out and visit.get("door"):
        for package in out:
            package_update(package["id"], porch="inside", porch_at=visit["end"], porch_by="door")
            package_log(package["id"], float(visit["end"]), "taken_in", visit.get("name"), key=f"taken:{visit['event']}")
    elif out and visit.get("gone") and visit.get("look") is False and not visit.get("name"):
        # Named or with the door open, it was somebody of ours; this is neither, and it is a question, with its pictures.
        for package in out:
            package_update(package["id"], porch="missing", porch_at=visit["end"])
            package_save_visit_frames(package["id"], visit, ("missing_before", "missing_after"))
            package_log(package["id"], float(visit["end"]), "may_be_gone", key=f"gone:{visit['event']}")
            if package_told(package["id"], "missing", now):
                package_notify(package["id"], "missing", now, push=push)
    if visit.get("appeared") and visit.get("look") is True and not visit.get("door") and not visit.get("name") and float(visit.get("confidence") or 0) >= PACKAGE_MODEL_SURE:
        package_unexpected(visit, now, push=push)


def package_save_visit_frames(package_id: str, visit: dict[str, Any], names: tuple[str, str]) -> None:
    before, after = package_frame(float(visit["start"]) - PACKAGE_BEFORE_SECONDS), package_frame(float(visit["end"]) + PACKAGE_AFTER_SECONDS)
    if not before or not after:
        return
    try:
        os.makedirs(PACKAGE_FRAME_DIR, exist_ok=True)
        for name, jpeg in zip(names, (before, after)):
            cropped = package_crop(jpeg)
            with open(os.path.join(PACKAGE_FRAME_DIR, f"{package_id}_{name}.jpg"), "wb") as f:
                f.write(cropped)
    except Exception as e:
        log.warning("packages: couldn't keep %s's pictures: %s", package_id, e)


def package_unexpected(visit: dict[str, Any], now: float, push: Callable[..., Any] | None = None) -> str | None:
    """A parcel the camera saw land with no email yet. Given to whatever was due today, else a package of its own until an email claims it."""
    if package_rows("delivered_at BETWEEN ? AND ? AND deleted=0 AND visit_event=?", (visit["start"] - 60, visit["end"] + PACKAGE_AFTER_EMAIL, visit["event"])):
        return None
    day = datetime.fromtimestamp(float(visit["end"]), household_zone() or timezone.utc).date().isoformat()
    due = package_rows("stage!='delivered' AND deleted=0 AND archived=0 AND (stage='out_for_delivery' OR (expected_from<=? AND expected_to>=?))", (day, day))
    package_id = due[0]["id"] if len(due) == 1 else None
    if package_id is None:
        package_id = package_id_of("camera", visit["event"])
        with_db(lambda c: (c.execute(
            "INSERT OR IGNORE INTO packages (id, owner, source, stage, stage_at, created, updated, surprise) VALUES (?,?,?,?,?,?,?,0)",
            (package_id, "", "camera", "delivered", visit["end"], visit["end"], visit["end"]),
        ), c.commit()))
    package_update(package_id, stage="delivered", stage_at=visit["end"], delivered_at=visit["end"], verdict="camera_only", verdict_what=visit.get("what"), verdict_confidence=visit.get("confidence"),
                   visit_event=visit["event"], visit_at=visit["start"], porch="porch", porch_at=visit["end"])
    package_save_frames(package_id, visit)
    package_log(package_id, float(visit["start"]), "camera_only", key=f"camera:{visit['event']}")
    if package_told(package_id, "camera_only", now):
        package_notify(package_id, "camera_only", now, push=push)
    return package_id


def package_merge_camera(now: float) -> None:
    """An email that says delivered, soon after a parcel with no email: they are one package, so the one the camera made goes, and the email's takes its pictures."""
    camera = package_rows("source='camera' AND deleted=0")
    for parcel in camera:
        at = float(parcel["delivered_at"] or 0)
        claimed = package_rows("source!='camera' AND deleted=0 AND delivered_at BETWEEN ? AND ?", (at, at + PACKAGE_CAMERA_MERGE))
        if not claimed:
            continue
        package = min(claimed, key=lambda p: float(p["delivered_at"]))
        if not package["verdict_by"] and (not package["visit_event"] or package["verdict"] in (None, "not_seen", "seen")):
            package_update(package["id"], verdict="confirmed", verdict_what=parcel["verdict_what"], verdict_confidence=parcel["verdict_confidence"], visit_event=parcel["visit_event"], visit_at=parcel["visit_at"],
                           porch=package["porch"] or parcel["porch"], porch_at=package["porch_at"] or parcel["porch_at"])
            for name in ("before", "after"):
                source = os.path.join(PACKAGE_FRAME_DIR, f"{parcel['id']}_{name}.jpg")
                if os.path.exists(source):
                    os.replace(source, os.path.join(PACKAGE_FRAME_DIR, f"{package['id']}_{name}.jpg"))
        package_update(parcel["id"], deleted=1)


def package_forecast_rain(home: dict[str, Any], now: float) -> int | None:
    """The highest chance of rain in the next few hours at the house, in percent (Open-Meteo, which needs no key), or None if it won't say."""
    try:
        r = requests.get("https://api.open-meteo.com/v1/forecast", params={"latitude": home["lat"], "longitude": home["lng"], "hourly": "precipitation_probability", "timeformat": "unixtime", "forecast_days": 2}, timeout=15)
        r.raise_for_status()
        hourly = r.json()["hourly"]
    except Exception as e:
        log.warning("packages: no forecast: %s", e)
        return None
    chances = [c for t, c in zip(hourly["time"], hourly["precipitation_probability"]) if now - 1800 <= t <= now + PACKAGE_RAIN_HOURS * 3600 and c is not None]
    return max(chances) if chances else None


def package_writes_again(package: dict[str, Any]) -> bool:
    """Whether silence means something: Amazon always writes when it has delivered, and a tracking service would say. Most shops write "shipped" and never again."""
    return package["carrier"] == "amazon" or bool(package["tracker_on"])


def package_late(package: dict[str, Any], today: str) -> bool:
    """
    Well past its promised day with no word of it, from someone who would usually have said. Only
    usually: 3 of about 120 Amazon parcels on 2026-10-31 had no "Delivered" email at all, so the
    day after is no news, and what is said about it is a question.
    """
    if package["stage"] == "delivered" or not package["expected_to"] or not package_writes_again(package):
        return False
    return package["expected_to"] < (datetime.fromisoformat(today).date() - timedelta(days=PACKAGE_LATE_AFTER_DAYS)).isoformat()


def package_gone_quiet(package: dict[str, Any], now: float, today: str) -> bool:
    """Nothing more will be heard of it, so it comes off the list, rather than being on its way for ever because nobody wrote again."""
    if package["stage"] == "delivered" or package["pinned"] or package["archived"]:
        return False
    if package["expected_to"]:
        if not package_writes_again(package) and package["expected_to"] < (datetime.fromisoformat(today).date() - timedelta(seconds=PACKAGE_QUIET_EXPECTED)).isoformat():
            return True
        if package["expected_to"] < (datetime.fromisoformat(today).date() - timedelta(days=PACKAGE_QUIET_LATE_DAYS)).isoformat():
            return True
    return now - float(package["updated"] or 0) > PACKAGE_QUIET_ANY


def package_first_round(active: list[dict[str, Any]], now: float, today: str) -> None:
    """
    The first round after the relay first reads the mail would otherwise tell of months of things
    all at once: what is already so counts as told. That goes on until a read has gone through for
    every mailbox, so a mailbox that failed the first time doesn't bring it all on later.
    """
    if state_get(PACKAGE_NAGS_KEY):
        return
    synced = state_get(PACKAGE_SYNCED_KEY)
    if not amazon_on() or (synced and not synced.get("failed")):
        state_set(PACKAGE_NAGS_KEY, True)
    for package in active:
        package_told(package["id"], f"late:{package['expected_to']}", now)
        package_told(package["id"], f"porch:{float(package['porch_at'] or package['delivered_at'] or now):.0f}", now)
        package_told(package["id"], f"rain:{today}", now)
        if package["return_by"]:
            package_told(package["id"], f"return:{package['return_by']}", now)


def package_nags(now: float, push: Callable[..., Any] | None = None) -> None:
    """What the clock brings on rather than an email: a parcel still out, rain coming for it, a promised day gone by, a return window closing, the morning's list."""
    zone = household_zone() or timezone.utc
    local = datetime.fromtimestamp(now, zone)
    today = local.date().isoformat()
    active = package_rows("deleted=0 AND archived=0")
    package_first_round(active, now, today)
    for package in active:
        if package_gone_quiet(package, now, today):
            package_update(package["id"], archived=PACKAGE_QUIET)
            package_log(package["id"], now, "gone_quiet", key="quiet")
            continue
        if package["stage"] == "delivered" and package["porch"] == "porch":
            at = float(package["porch_at"] or package["delivered_at"] or now)
            if package_door_opened(at, now):
                package_update(package["id"], porch="inside", porch_at=now, porch_by="door")
                package_log(package["id"], now, "taken_in", key="taken:door")
            elif now - at > PACKAGE_PORCH_GIVE_UP:
                package_update(package["id"], porch=None)
            elif now - at >= PACKAGE_REMIND_AFTER and now - at < 86400 and package_told(package["id"], f"porch:{at:.0f}", now):
                package_notify(package["id"], "porch", now, push=push)
        if package_late(package, today) and package_told(package["id"], f"late:{package['expected_to']}", now):
            package_notify(package["id"], "late", now, push=push)
        if package["return_by"] and package["return_state"] in (None, "none") and package["stage"] == "delivered":
            left = (datetime.fromisoformat(package["return_by"]).date() - local.date()).days
            if left == PACKAGE_RETURN_WARN_DAYS and package_told(package["id"], f"return:{package['return_by']}", now):
                package_notify(package["id"], "return_closing", now, push=push)
    out = [p for p in active if p["porch"] == "porch" and p["stage"] == "delivered"]
    home = state_get(HOME_KEY)
    if out and home and now - float(_package_status.get("rain_at") or 0) >= PACKAGE_RAIN_SECONDS:
        _package_status["rain_at"] = now
        chance = package_forecast_rain(home, now)
        if chance is not None and chance >= PACKAGE_RAIN_CHANCE:
            for package in out:
                if package_told(package["id"], f"rain:{today}", now):
                    package_notify(package["id"], "rain", now, push=push, extra={"package_rain": str(chance)})
    if local.hour == PACKAGE_MORNING_HOUR and state_get("package_morning") != today:
        state_set("package_morning", today)
        coming = [p for p in active if p["stage"] != "delivered" and (p["stage"] == "out_for_delivery" or (p["expected_from"] and p["expected_from"] <= today <= (p["expected_to"] or "")))]
        if coming:
            package_morning(coming, now, push=push)


def package_phones_of() -> dict[str, str]:
    return dict(with_db(lambda c: c.execute("SELECT device_id, person FROM package_phones").fetchall()))


def package_may_see(package: dict[str, Any], viewer: str | None) -> bool:
    """Whether someone may be told what a package is: not a surprise, or it is theirs. One that is nobody's can't be kept from anybody, or it would be lost to everyone."""
    return not package["surprise"] or not package["owner"] or (viewer is not None and viewer == package["owner"])


_PACKAGE_PUSH_ENGLISH = {
    "shipped": ("Shipped", "{name} is on its way"),
    "out_for_delivery": ("Out for delivery", "{name} should arrive today"),
    "delivered": ("Delivered", "{name} has arrived"),
    "porch": ("Still on the porch", "{name} has been out for a while"),
    "rain": ("Rain is coming", "{name} is still on the porch"),
    "missing": ("Did someone take a package in?", "{name} may be gone from the porch"),
    "camera_only": ("A parcel may have arrived", "The door camera saw something left on the porch"),
    "problem_attempted": ("Delivery attempted", "{name} wasn't delivered"),
    "problem_exception": ("Delivery problem", "{name} has run into a problem"),
    "signature": ("Signature needed", "Someone needs to sign for {name}"),
    "late": ("Did this one come?", "No word of {name} since it was due"),
    "return_closing": ("Return window closing", "{name} can be returned for 3 more days"),
}


def package_label(package: dict[str, Any], masked: bool) -> str:
    if masked:
        return "A surprise"
    return package["nickname"] or package["title"] or (f"Your {package['shop']} package" if package["shop"] else "A package")


def package_notify(package_id: str, kind: str, now: float, push: Callable[..., Any] | None = None, extra: dict[str, str] | None = None) -> dict[str, int] | None:
    """Tells the phones something about one package, if the household wants told that. Each phone hears only what it may: a surprise stays one on anyone else's."""
    package = package_row(package_id)
    if package is None or package["deleted"]:
        return None
    group = "problems" if kind.startswith("problem_") or kind in ("signature", "late") else "returns" if kind == "return_closing" else "delivered" if kind == "camera_only" else kind
    if not package_settings()["notify"].get(group, True):
        return None
    send = push or package_send
    title, body = _PACKAGE_PUSH_ENGLISH[kind]
    full = package_label(package, False)
    masked = package_label(package, True)
    shared = {"package": package_id, "package_event": kind, "notif_id": f"package-{package_id}", "package_verdict": package["verdict"] or "", "package_carrier": package["carrier"] or "", **(extra or {})}
    return send(package, (title, body.format(name=full)), (title, body.format(name=masked)), {**shared, "package_name": full}, {**shared, "package_name": "", "package_masked": "1"})


def package_send(package: dict[str, Any], plain: tuple[str, str], masked: tuple[str, str], plain_data: dict[str, str], masked_data: dict[str, str]) -> dict[str, int]:
    """One push to each phone, worded for who holds it, past each one's own quiet hours."""
    rows = with_db(lambda c: c.execute("SELECT device_id, token, only_away, quiet_start, quiet_end, tz, utc_offset FROM devices WHERE token IS NOT NULL").fetchall())
    phones = package_phones_of()
    now = time.time()
    results = []
    quiet = 0
    for device_id, token, only_away, quiet_start, quiet_end, tz, utc_offset in rows:
        if silenced(bool(only_away), quiet_start, quiet_end, tz, utc_offset, now):
            quiet += 1
            continue
        allowed = package_may_see(package, phones.get(device_id))
        title, body = plain if allowed else masked
        results.append(deliver(token, title, body, plain_data if allowed else masked_data))
    return {"sent": results.count("sent"), "failed": results.count("failed"), "skipped_quiet": quiet}


def package_morning(coming: list[dict[str, Any]], now: float, push: Callable[..., Any] | None = None) -> dict[str, int] | None:
    """The morning's one push: how many come today, and for anyone not home, that nobody will be."""
    if not package_settings()["notify"].get("morning", True):
        return None
    count = len(coming)
    away = away_since() is not None
    data = {"package": "", "package_event": "morning", "package_count": str(count), "package_away": "1" if away else "0", "notif_id": "package-morning"}
    title = "Package coming today" if count == 1 else f"{count} packages coming today"
    body = "Nobody's home right now" if away else "Open Packages to see them"
    if push:
        return push(None, (title, body), (title, body), data, data)
    return broadcast(title, body, data)


def package_tracker_status(status: str | None) -> str | None:
    """A tracking service's word for where a parcel is, as one of ours."""
    return {"InTransit": "shipped", "InfoReceived": "shipped", "AvailableForPickup": "out_for_delivery", "OutForDelivery": "out_for_delivery", "Delivered": "delivered",
            "DeliveryFailure": "attempted", "Exception": "exception"}.get(status or "")


def package_tracker_apply(package_id: str, info: dict[str, Any], now: float, push: Callable[..., Any] | None = None) -> None:
    """One parcel's answer from the tracking service: each scan on its timeline, and how far it has got."""
    package = package_row(package_id)
    if package is None:
        return
    latest_at = None
    providers = ((info.get("tracking") or {}).get("providers") or [])
    for provider in providers:
        for event in provider.get("events") or []:
            stamp = event.get("time_iso") or event.get("time_utc")
            try:
                at = datetime.fromisoformat(str(stamp).replace("Z", "+00:00")).timestamp()
            except ValueError:
                continue
            latest_at = max(latest_at or at, at)
            words = " · ".join(part for part in (event.get("description"), event.get("location")) if part)
            package_log(package_id, at, "scan", words[:200], key=f"scan:{at:.0f}:{words[:40]}")
    estimate = ((info.get("time_metrics") or {}).get("estimated_delivery_date") or {})
    if estimate.get("from") and package["stage"] != "delivered":
        package_update(package_id, expected_from=str(estimate["from"])[:10], expected_to=str(estimate.get("to") or estimate["from"])[:10])
    stage = package_tracker_status((info.get("latest_status") or {}).get("status"))
    at = latest_at or now
    if stage in PACKAGE_STAGES:
        package_advance(package_id, stage, at, now, tell=now - at < PACKAGE_FRESH_SECONDS, push=push)
    elif stage and package["problem_at"] != at:
        package_update(package_id, problem=stage, problem_at=at)
        if now - at < PACKAGE_FRESH_SECONDS and package_told(package_id, f"problem:{stage}:{at:.0f}", now):
            package_notify(package_id, "problem_" + stage, now, push=push)


def package_tracker_poll(now: float, post: Callable[..., Any] | None = None, push: Callable[..., Any] | None = None) -> int:
    """
    Asks the tracking service about every open parcel with a number, when a key is set: Amazon's
    own numbers it can't follow, and each number registered costs from a limited allowance, so one
    is registered only when it first needs it. How many were answered.
    """
    if not PACKAGE_17TRACK_KEY:
        return 0
    post = post or (lambda path, body: requests.post(f"https://api.17track.net/track/v2.4/{path}", json=body, headers={"17token": PACKAGE_17TRACK_KEY, "Content-Type": "application/json"}, timeout=30))
    rows = [p for p in package_rows("tracking IS NOT NULL AND stage!='delivered' AND deleted=0 AND created>=?", (now - 45 * 86400,)) if p["carrier"] != "amazon"]
    answered = 0
    new = [p for p in rows if not p["tracker_on"]]
    for start in range(0, len(new), 40):
        batch = new[start:start + 40]
        r = post("register", [{"number": p["tracking"]} for p in batch])
        if r.status_code == 200:
            for p in batch:
                package_update(p["id"], tracker_on=1)
    by_number = {p["tracking"]: p["id"] for p in rows}
    for start in range(0, len(rows), 40):
        batch = rows[start:start + 40]
        r = post("gettrackinfo", [{"number": p["tracking"]} for p in batch])
        if r.status_code != 200:
            log.warning("packages: the tracking service answered %s", r.status_code)
            continue
        for item in (r.json().get("data") or {}).get("accepted") or []:
            package_id = by_number.get(item.get("number"))
            if package_id:
                package_tracker_apply(package_id, item.get("track_info") or {}, now, push=push)
                answered += 1
    return answered


def package_tidy(now: float) -> None:
    """Pictures of deliveries long past go; the record of them stays."""
    if not os.path.isdir(PACKAGE_FRAME_DIR):
        return
    for name in os.listdir(PACKAGE_FRAME_DIR):
        path = os.path.join(PACKAGE_FRAME_DIR, name)
        try:
            if now - os.path.getmtime(path) > PACKAGE_FRAMES_KEPT_DAYS * 86400:
                os.remove(path)
        except OSError:
            pass


def package_judge_pending(now: float, push: Callable[..., Any] | None = None) -> None:
    """
    The deliveries the camera hasn't spoken for. Nobody at the door can only be said once the time
    for someone to turn up has passed, after the email's own push has gone, so it is news of its own,
    replacing that push on the phone while the email is recent.
    """
    pending = package_rows("delivered_at IS NOT NULL AND verdict IS NULL AND verdict_by IS NULL AND deleted=0 AND delivered_at >= ?", (now - PACKAGE_CAMERA_REACH,))
    for package in pending:
        try:
            verdict = package_judge(package["id"], now)
        except Exception:
            log.exception("packages: couldn't judge %s", package["id"])
            continue
        if verdict == "not_seen" and now - float(package["delivered_at"]) < PACKAGE_FRESH_SECONDS and package_told(package["id"], "not_seen", now):
            package_notify(package["id"], "delivered", now, push=push)


def packages_forever() -> None:
    """The emails every couple of minutes, the door camera's visits and the clock's reminders each round, and the tracking service now and then. Off without a linked mailbox or a camera."""
    last_mail = last_tracker = last_tidy = 0.0
    while True:
        time.sleep(PACKAGE_TICK_SECONDS)
        now = time.time()
        try:
            if amazon_on() and now - last_mail >= PACKAGE_RECENT_SECONDS:
                last_mail = now
                package_sync(now)
            package_judge_pending(now)
            package_watch_door(now)
            package_retry_visits(now)
            package_merge_camera(now)
            package_nags(now)
            if now - last_tracker >= PACKAGE_TRACKER_SECONDS:
                last_tracker = now
                package_tracker_poll(now)
            if now - last_tidy >= 86400:
                last_tidy = now
                package_tidy(now)
        except Exception:
            log.exception("packages: a round failed")


def package_frames_of(package_id: str) -> set[str]:
    return {name for name in ("before", "after", "missing_before", "missing_after") if os.path.exists(os.path.join(PACKAGE_FRAME_DIR, f"{package_id}_{name}.jpg"))}


def package_json(package: dict[str, Any], viewer: str | None, today: str, timeline: list[tuple] | None = None) -> dict[str, Any]:
    """A package as the app shows it. A surprise that isn't this viewer's says only that one is coming, and when."""
    may_see = package_may_see(package, viewer)
    shown = {
        "id": package["id"], "owner": package["owner"], "source": package["source"], "stage": package["stage"], "stage_at": package["stage_at"], "expected_from": package["expected_from"], "expected_to": package["expected_to"],
        "window_start": package["window_start"], "window_end": package["window_end"], "signature": bool(package["signature"]), "problem": package["problem"], "problem_at": package["problem_at"],
        "created": package["created"], "updated": package["updated"], "delivered_at": package["delivered_at"], "surprise": bool(package["surprise"]), "masked": not may_see, "pinned": bool(package["pinned"]),
        "archived": bool(package["archived"]), "porch": package["porch"], "porch_at": package["porch_at"], "porch_by": package["porch_by"], "verdict": package["verdict"], "verdict_by": package["verdict_by"],
        "late": package_late(package, today), "carrier": package["carrier"],
    }
    if not may_see:
        return shown
    return {
        **shown,
        "tracking": package["tracking"], "order_id": package["order_id"], "shop": package["shop"], "title": package["title"], "nickname": package["nickname"], "left_note": package["left_note"], "total": package["total"],
        "notes": package["notes"], "return_by": package["return_by"], "return_state": package["return_state"] or "none", "refund_at": package["refund_at"], "verdict_what": package["verdict_what"],
        "verdict_confidence": package["verdict_confidence"], "visit_event": package["visit_event"], "visit_at": package["visit_at"], "url": package_track_url(package["carrier"], package["tracking"], package["order_id"]),
        "frames": sorted(package_frames_of(package["id"])),
        "timeline": [{"at": at, "kind": kind, "detail": detail} for at, kind, detail in (timeline or [])],
    }


def packages_snapshot(viewer: str | None, now: float) -> dict[str, Any]:
    zone = household_zone() or timezone.utc
    today = datetime.fromtimestamp(now, zone).date().isoformat()
    rows = package_rows("deleted=0 AND created>=?", (now - PACKAGE_LISTED_DAYS * 86400,))
    events = with_db(lambda c: c.execute("SELECT package_id, at, kind, detail FROM package_events WHERE kind NOT LIKE 'told:%' ORDER BY at").fetchall())
    timeline: dict[str, list[tuple]] = {}
    for package_id, at, kind, detail in events:
        timeline.setdefault(package_id, []).append((at, kind, detail))
    rows.sort(key=lambda p: (float(p["updated"] or 0)), reverse=True)
    return {
        "viewer": viewer, "people": package_people(), "now": now, "today": today, "mail_on": amazon_on(), "synced": state_get(PACKAGE_SYNCED_KEY), "camera_on": package_camera_on(),
        "camera": CAMERA_NAMES.get(PACKAGE_CAMERA, PACKAGE_CAMERA), "camera_id": PACKAGE_CAMERA, "door_sensor": bool(package_door_ids()), "model_on": bool(OLLAMA), "tracker_on": bool(PACKAGE_17TRACK_KEY),
        "away": away_since() is not None, "settings": package_settings(), "packages": [package_json(p, viewer, today, timeline.get(p["id"])) for p in rows],
    }


class PackageAdd(BaseModel):
    tracking: str
    nickname: str | None = None
    owner: str | None = None
    surprise: bool = False


class PackagePatch(BaseModel):
    nickname: str | None = None
    notes: str | None = None
    owner: str | None = None
    surprise: bool | None = None
    pinned: bool | None = None
    archived: bool | None = None
    return_by: str | None = None
    return_state: str | None = None
    # "inside", someone took it in; "porch", it is still out there; "yes"/"no" on what the camera said.
    porch: str | None = None
    camera: str | None = None
    stage: str | None = None


class PackagePhone(BaseModel):
    device_id: str
    person: str | None = None


class PackageSettings(BaseModel):
    notify: dict[str, bool] | None = None
    hide_new: dict[str, bool] | None = None


def package_viewer(device: str | None) -> str | None:
    if not device:
        return None
    row = with_db(lambda c: c.execute("SELECT person FROM package_phones WHERE device_id=?", (device,)).fetchone())
    return row[0] if row else None


def package_or_404(package_id: str, viewer: str | None) -> dict[str, Any]:
    """A package the asker may change or look at: someone else's surprise is as good as not there."""
    package = package_row(package_id)
    if package is None or package["deleted"] or not package_may_see(package, viewer):
        raise HTTPException(status_code=404, detail="No such package")
    return package


@app.get("/packages")
def get_packages(request: Request, device: str | None = None) -> dict[str, Any]:
    """Everything the Packages app shows (see "packages"), each surprise masked unless this phone (`device`) is its owner's."""
    require_frigate_session(request)
    return packages_snapshot(package_viewer(device), time.time())


@app.post("/packages/sync")
def post_packages_sync(request: Request, device: str | None = None) -> dict[str, Any]:
    """Reads the mailboxes now, for a pull on the app's list, then answers as `GET /packages` does."""
    require_frigate_session(request)
    package_sync(time.time(), full=False)
    return packages_snapshot(package_viewer(device), time.time())


@app.post("/packages")
def post_package(body: PackageAdd, request: Request, device: str | None = None) -> dict[str, Any]:
    """Adds a parcel by its tracking number, for one no email told of. 400 `not_a_number` for what can't be one."""
    require_frigate_session(request)
    classified = package_classify_number(body.tracking)
    if classified is None:
        raise HTTPException(status_code=400, detail={"error": "not_a_number", "message": "That doesn't look like a tracking number"})
    number, carrier = classified
    now = time.time()
    viewer = package_viewer(device)
    owner = body.owner if body.owner in package_people() else viewer or ""
    package_id = package_id_of(number)
    # A surprise is kept from everyone but its owner, so it needs one.
    surprise = int(body.surprise) if owner else 0
    with_db(lambda c: (c.execute(
        "INSERT INTO packages (id, owner, source, carrier, tracking, nickname, stage, stage_at, created, updated, surprise) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        " ON CONFLICT(id) DO UPDATE SET deleted=0, archived=0, nickname=COALESCE(excluded.nickname, nickname)",
        (package_id, owner, "manual", carrier, number, (body.nickname or "").strip() or None, "shipped", now, now, now, surprise),
    ), c.commit()))
    package_log(package_id, now, "added", key="added")
    return packages_snapshot(viewer, now)


@app.patch("/packages/{package_id}")
def patch_package(package_id: str, body: PackagePatch, request: Request, device: str | None = None) -> dict[str, Any]:
    """Changes what someone can say about a package: its name, its notes, whose it is, whether it is a surprise, a return, and what is really on the porch."""
    require_frigate_session(request)
    viewer = package_viewer(device)
    package = package_or_404(package_id, viewer)
    now = time.time()
    fields: dict[str, Any] = {}
    if body.nickname is not None:
        fields["nickname"] = body.nickname.strip() or None
    if body.notes is not None:
        fields["notes"] = body.notes.strip() or None
    if body.owner is not None and body.owner in package_people():
        fields["owner"] = body.owner
    if body.surprise is not None and body.surprise != bool(package["surprise"]):
        # Only its owner can hide a thing, or it would be hidden from whoever just did it. One that is nobody's becomes theirs.
        if not viewer or (package["owner"] and viewer != package["owner"]):
            raise HTTPException(status_code=403, detail="Not your package")
        fields["surprise"] = int(body.surprise)
        if not package["owner"]:
            fields["owner"] = viewer
    if body.pinned is not None:
        fields["pinned"] = int(body.pinned)
    if body.archived is not None:
        fields["archived"] = int(body.archived)
        # Brought back by hand from having gone quiet: pinned, or it would go straight back.
        if not body.archived and package["archived"] == PACKAGE_QUIET and package["stage"] != "delivered":
            fields["pinned"] = 1
    if body.return_by is not None:
        if body.return_by:
            try:
                datetime.fromisoformat(body.return_by)
            except ValueError:
                raise HTTPException(status_code=400, detail="Not a date")
        fields["return_by"] = body.return_by or None
    if body.return_state in PACKAGE_RETURN_STATES:
        fields["return_state"] = body.return_state
        package_log(package_id, now, f"return_{body.return_state}", key=f"return:{body.return_state}:{now:.0f}")
    if body.porch in ("inside", "porch"):
        fields.update(porch=body.porch, porch_at=now, porch_by=viewer or "someone")
        package_log(package_id, now, "taken_in" if body.porch == "inside" else "still_out", viewer, key=f"porch:{body.porch}:{now:.0f}")
    if body.camera == "yes":
        fields.update(verdict="confirmed", verdict_by=viewer or "someone")
    elif body.camera == "no":
        fields.update(verdict="not_seen", verdict_by=viewer or "someone", porch=None)
    if body.stage == "delivered" and package["stage"] != "delivered":
        # Whoever says so has it, or knows where it is: not one for the porch watch.
        package_update(package_id, stage="delivered", stage_at=now, delivered_at=now, updated=now, problem=None, problem_at=None, porch="inside", porch_at=now, porch_by=viewer or "someone")
        package_log(package_id, now, "marked_delivered", viewer, key=f"marked:{now:.0f}")
    if fields:
        package_update(package_id, **fields)
    return packages_snapshot(viewer, now)


@app.delete("/packages/{package_id}")
def delete_package(package_id: str, request: Request, device: str | None = None) -> dict[str, Any]:
    """Forgets a package. Kept as a tombstone, so the next read of the emails doesn't bring it back."""
    require_frigate_session(request)
    viewer = package_viewer(device)
    package_or_404(package_id, viewer)
    package_update(package_id, deleted=1)
    for name in package_frames_of(package_id):
        try:
            os.remove(os.path.join(PACKAGE_FRAME_DIR, f"{package_id}_{name}.jpg"))
        except OSError:
            pass
    return packages_snapshot(viewer, time.time())


@app.put("/packages/phone")
def put_package_phone(body: PackagePhone, request: Request) -> dict[str, Any]:
    """Says whose phone this is, which is who its surprises are kept from and shown to."""
    require_frigate_session(request)
    if body.person and body.person not in package_people():
        raise HTTPException(status_code=400, detail="No such person")
    if body.person:
        with_db(lambda c: (c.execute("INSERT OR REPLACE INTO package_phones VALUES (?,?)", (body.device_id, body.person)), c.commit()))
    else:
        with_db(lambda c: (c.execute("DELETE FROM package_phones WHERE device_id=?", (body.device_id,)), c.commit()))
    return packages_snapshot(body.person, time.time())


@app.put("/packages/settings")
def put_package_settings(body: PackageSettings, request: Request, device: str | None = None) -> dict[str, Any]:
    """Which pushes the household wants, and who is hiding everything new (a run of gifts in December, say)."""
    require_frigate_session(request)
    held = package_settings()
    for name, value in (body.notify or {}).items():
        if name in PACKAGE_NOTICES:
            held["notify"][name] = bool(value)
    for person, value in (body.hide_new or {}).items():
        if person in package_people():
            held["hide_new"][person] = bool(value)
    state_set(PACKAGE_SETTINGS_KEY, held)
    return packages_snapshot(package_viewer(device), time.time())


@app.get("/packages/porch/now")
def get_package_porch(request: Request) -> Response:
    """The landing as the door camera sees it this moment."""
    require_frigate_session(request)
    if not package_camera_on():
        raise HTTPException(status_code=404, detail="No camera")
    try:
        r = requests.get(f"{FRIGATE}/api/{PACKAGE_CAMERA}/latest.jpg", params={"h": 1080}, timeout=15)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Frigate unreachable: {e}")
    if r.status_code != 200:
        raise HTTPException(status_code=404, detail=f"Frigate answered {r.status_code}")
    return Response(content=package_crop(r.content, edge=900), media_type="image/jpeg", headers={"Cache-Control": "private, max-age=10"})


@app.get("/packages/{package_id}/frames/{name}")
def get_package_frame(package_id: str, name: str, request: Request, device: str | None = None) -> Response:
    """One of a delivery's kept pictures. A surprise's are as much of a giveaway as its name: only its owner's phone gets them."""
    require_frigate_session(request)
    package_or_404(package_id, package_viewer(device))
    if name not in ("before", "after", "missing_before", "missing_after"):
        raise HTTPException(status_code=404, detail="No such picture")
    path = os.path.join(PACKAGE_FRAME_DIR, f"{package_id}_{name}.jpg")
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="No such picture")
    with open(path, "rb") as f:
        return Response(content=f.read(), media_type="image/jpeg", headers={"Cache-Control": "private, max-age=86400"})


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
# vehicle_charging_cmds is what Tesla asks for its Supercharger history (see "charging"); the relay sends no commands.
TESLA_SCOPES = "openid offline_access vehicle_device_data vehicle_location vehicle_charging_cmds"
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
# Each car's last answer, as (when asked, `tesla_location`'s answer): car presence reads it for
# home or away, and parking (see "parking") for the street and the side.
_tesla_answers: dict[str, tuple[float, dict[str, Any] | None]] = {}
# Car presence (the poller) and parking ask from threads of their own; one asks at a time, so the
# second finds the first's answer rather than paying for its own.
_tesla_lock = threading.Lock()
# Why the call being made is made, for `tesla_spend`'s tally: set under _tesla_lock by `tesla_ask`.
_tesla_why = "link"
# After Tesla says a car is asleep, the half-hourly check (see `CarPresence.tesla_check`) waits
# this long before asking again: a sleeping car hasn't moved, and asked again it mostly says the
# same. Overnight that is 8 calls a car, not 32. A car that wakes and drives off past the camera is
# asked about at once (the departure); one that leaves the curb, out of its sight, is found at most
# this late, and parking asks for itself before telling anyone to move a car.
TESLA_ASLEEP_CHECK_SECONDS = float(os.environ.get("TESLA_ASLEEP_CHECK_SECONDS", "7200"))


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
    """
    Counts one Fleet API call against today's TESLA_CALLS_PER_DAY, and under why it was made
    (`_tesla_why`: "check", "arrival", "departure", "parking", ...), so `TESLA_CALLS_KEY` reads
    `{"2026-10-28": 61, "why": {"check": 40, "departure": 3, ...}}`; False once the day's are spent.
    """
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    spent = state_get(TESLA_CALLS_KEY) or {}
    count = int(spent.get(today, 0)) if isinstance(spent, dict) else 0
    if count >= TESLA_CALLS_PER_DAY:
        return False
    why = dict(spent.get("why") or {}) if count else {}
    why[_tesla_why] = int(why.get(_tesla_why, 0)) + 1
    state_set(TESLA_CALLS_KEY, {today: count + 1, "why": why})
    return True


def tesla_calls_today() -> int:
    spent = state_get(TESLA_CALLS_KEY) or {}
    return int(spent.get(datetime.now(timezone.utc).strftime("%Y-%m-%d"), 0)) if isinstance(spent, dict) else 0


def tesla_learn_vehicles(account: str) -> list[str]:
    """Files each vehicle the account can see under it, by VIN; answers the VINs."""
    global _tesla_why
    access = tesla_access(account)
    if not access:
        return []
    with _tesla_lock:
        _tesla_why = "link"
        if not tesla_spend():
            return []
    log.info("tesla: listed account %s's vehicles (link) · %d calls today", account[:8], tesla_calls_today())
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
    Where the car is, as `{"lat", "lng", "at", "heading", "shift", "speed"}` (heading in degrees
    clockwise from north; shift "P", "D", "R", "N" or None, parked cars often saying nothing), or
    `{"asleep": True}`; None when it can't be asked (not linked, out of calls, an error). Never
    wakes the car: a sleeping one answers 408.
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
    heading, speed = drive.get("heading"), drive.get("speed")
    return {
        "lat": float(drive["latitude"]), "lng": float(drive["longitude"]), "at": float(stamp),
        "heading": float(heading) if heading is not None else None, "shift": drive.get("shift_state"),
        "speed": float(speed) if speed is not None else None,
    }


def tesla_ask(name: str, now: float, why: str) -> tuple[float, dict[str, Any] | None]:
    """
    Tesla's answer about one of TESLA_CARS, as (when asked, `tesla_location`'s answer), asking at
    most once per car per TESLA_CACHE_SECONDS. A failed call is an answer of None, kept like any
    other. Each call made is logged with `why` it was made and the day's count so far.
    """
    global _tesla_why
    with _tesla_lock:
        cached = _tesla_answers.get(name)
        if cached and now - cached[0] < TESLA_CACHE_SECONDS:
            return cached
        # A car streaming to the relay right now (see "Tesla telemetry") is where it last said.
        live = tesla_stream_answer(name, now)
        if live:
            _tesla_answers[name] = (now, live)
            return now, live
        before, _tesla_why = tesla_calls_today(), why
        where, said = None, "nothing"
        try:
            where = tesla_location(TESLA_CARS[name])
            said = "asleep" if where and where.get("asleep") else f"{now - float(where['at']):.0f} s old fix" if where else "nothing"
        except Exception as e:
            said = f"failed: {e}"
        after = tesla_calls_today()
        if after > before:
            log.info("tesla: asked about %s (%s): %s · %d calls today", name, why, said, after)
        elif said != "nothing":
            log.warning("tesla: asking about %s (%s) %s", name, why, said)
        _tesla_answers[name] = (now, where)
        return now, where


def tesla_due(name: str, now: float, every: float) -> bool:
    """
    Whether `name` is due to be asked about again, by the last answer about it: older than
    `every`, or, when it was that the car is asleep, older than TESLA_ASLEEP_CHECK_SECONDS, or
    TESLA_STREAM_ASLEEP_CHECK_SECONDS while the car streams (it says itself when it wakes).
    """
    answer = _tesla_answers.get(name)
    if answer is None:
        return True
    asleep = bool(answer[1] and answer[1].get("asleep"))
    if not asleep:
        return now - answer[0] >= every
    wait = TESLA_STREAM_ASLEEP_CHECK_SECONDS if tesla_streaming(name, now) else TESLA_ASLEEP_CHECK_SECONDS
    return now - answer[0] >= max(every, wait)


def tesla_verdict(name: str, now: float | None = None, why: str = "presence", ask: bool = True) -> str | None:
    """
    "home" or "away" by where Tesla says the car is against the household's home (see HOME_KEY);
    "asleep" when the car is (it won't say where, and isn't woken to); None when Tesla can't say
    (not one of TESLA_CARS, not linked, a stale fix, no home set, or somewhere in between). One
    answer per car per TESLA_CACHE_SECONDS; with `ask` False, the last answer, however old, and
    no call.
    """
    vin = TESLA_CARS.get(name)
    home = state_get(HOME_KEY)
    if not (tesla_on() and vin and home):
        return None
    now = time.time() if now is None else now
    asked, where = tesla_ask(name, now, why) if ask else _tesla_answers.get(name) or (now, None)
    # A fix is stale by how old it was when Tesla gave it, not by how long ago that was.
    if where and not where.get("asleep") and asked - float(where["at"]) <= TESLA_STALE_SECONDS:
        d = haversine_metres((where["lat"], where["lng"]), (float(home["lat"]), float(home["lng"])))
        radius = float(home.get("radius_m") or 150)
        verdict = "home" if d <= radius + TESLA_HOME_MARGIN_METRES else "away" if d > radius + TESLA_AWAY_METRES else None
        log.info("tesla: %s is %.0f m from home -> %s", name, d, verdict)
        return verdict
    if where and where.get("asleep"):
        return "asleep"
    return None


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


def tesla_partner_token() -> str:
    """A partner token: the application's own, for what concerns no one account."""
    return tesla_token_request({
        "grant_type": "client_credentials", "client_id": TESLA_CLIENT_ID, "client_secret": TESLA_CLIENT_SECRET,
        "scope": "openid vehicle_device_data vehicle_location", "audience": TESLA_API,
    })["access_token"]


def tesla_register() -> dict[str, Any]:
    """Registers the application's domain with the Fleet API (once per region), by a partner token."""
    from urllib.parse import urlparse

    r = requests.post(f"{TESLA_API}/api/1/partner_accounts", json={"domain": urlparse(TESLA_PUBLIC_URL).hostname},
                      headers={"Authorization": f"Bearer {tesla_partner_token()}"}, timeout=15)
    r.raise_for_status()
    return r.json()


# ---------------------------------------------------------------- Tesla telemetry

# Asking Tesla only ever finds a car where it is at that moment, and a sleeping one won't say: a car
# that wakes, drives off and is asleep again between two checks is never seen to move. Sarah's was
# on the curb at 16:51 UTC on 2026-10-28, asleep at the checks at 17:26, 19:26 and 21:26, and miles
# away all along. With Fleet Telemetry the car tells instead: whenever it is awake it holds a TLS
# connection to Tesla's fleet-telemetry server beside the relay (the `tesla-telemetry` container,
# reached through Funnel on TESLA_TELEMETRY_PORT) and streams the fields in TESLA_TELEMETRY_FIELDS
# as they change, each no more often than its interval. The server publishes them to the broker the
# door sensors use: `tesla/<VIN>/v/<field>` with the value as JSON (Location as
# `{"latitude", "longitude"}`, Gear as "ShiftStateD"), and `tesla/<VIN>/connectivity` with
# `{"Status": "CONNECTED"}` or "DISCONNECTED" as the car comes and goes.
#
# Each streamed location becomes the car's latest answer (see `tesla_ask`) as if the relay had just
# asked, so car presence takes it up within CAR_PRESENCE_EVERY_SECONDS and parking within
# PARKING_EVERY_SECONDS. A car sends its location only when it changes, so while it is connected
# and has been heard in the last TESLA_STREAM_FRESH_SECONDS the last one it sent is where it is, and
# asking about it answers from the stream without a call. Only a location sent over this connection
# counts: one from before the car or the relay last connected may be from before a drive the relay
# didn't hear. A car seen shifting into P is parked from then (`parked_at`). Asking stays
# the backstop for a stream that has stopped, less often for a car that streams: asleep, it is
# asked about every TESLA_STREAM_ASLEEP_CHECK_SECONDS, since it says itself when it wakes. A VIN
# outside TESLA_CARS is ignored (Tesla's advice: a car's key could be stolen, so trust only your own).
#
# The car has to be told where to stream, by a configuration signed with the application's private
# key, which stays off the box (docs/tesla.md, "Telemetry"): `relay.py tesla-telemetry-config`
# prints it, `scripts/tesla-sign` signs it on the Mac, and `relay.py tesla-telemetry-send` hands the
# signature to Tesla. It lapses after TESLA_TELEMETRY_DAYS; send it again before then.
TESLA_TELEMETRY_TOPIC = os.environ.get("TESLA_TELEMETRY_TOPIC", "tesla").strip("/")
TESLA_TELEMETRY_PORT = int(os.environ.get("TESLA_TELEMETRY_PORT", "8443"))
# The CA that signed the telemetry server's certificate: the car trusts only it.
TESLA_TELEMETRY_CA_FILE = os.environ.get("TESLA_TELEMETRY_CA_FILE", "/data/telemetry/ca.pem")
TESLA_TELEMETRY_DAYS = 350
# Each field streamed, and the fewest seconds between two of its values. Where the car is and how
# it moves often enough to see it park; the rest for the Cars page. About $0.01 an hour of driving.
TESLA_TELEMETRY_FIELDS = {
    "Location": 10, "GpsHeading": 10, "VehicleSpeed": 10, "Gear": 1,
    "Soc": 300, "EstBatteryRange": 300, "DetailedChargeState": 60, "TimeToFullCharge": 300, "ChargeLimitSoc": 300,
    "Locked": 60, "SentryMode": 60, "Odometer": 600, "DestinationName": 60, "MinutesToArrival": 60,
    "InsideTemp": 300, "OutsideTemp": 300,
    # What a charge takes from the wall, for its price (see "charging"), and what the car has used.
    "ACChargingPower": 60, "DCChargingPower": 30, "ACChargingEnergyIn": 60, "DCChargingEnergyIn": 60, "ChargeAmps": 60, "ChargerVoltage": 60,
    "FastChargerPresent": 60, "FastChargerType": 60, "EnergyRemaining": 300, "LifetimeEnergyUsed": 3600,
}
TESLA_STREAM_FRESH_SECONDS = 300.0
# How long since a car last streamed it still counts as streaming, for how often it is asked
# about asleep: the stream works for it, so it will say when it wakes.
TESLA_STREAM_TRUST_SECONDS = 2 * 86400.0
TESLA_STREAM_ASLEEP_CHECK_SECONDS = float(os.environ.get("TESLA_STREAM_ASLEEP_CHECK_SECONDS", str(6 * 3600)))
# Each car's stream: its fields' latest values, when anything was last heard from it (`heard`), when
# its location was (`located`), whether that location was sent over the connection as it is now
# (`current`), whether it is connected now (None until the server says), and when it was seen to
# shift from D, R or N into P on this connection (`parked_at`).
_tesla_live: dict[str, dict[str, Any]] = {}


def tesla_number(value: Any) -> float | None:
    """A streamed number, which older firmware sends as a string."""
    try:
        return float(value) if value is not None and value != "" else None
    except (TypeError, ValueError):
        return None


def tesla_stream_fix(live: dict[str, Any], at: float) -> dict[str, Any] | None:
    """The stream's location as one of `tesla_location`'s answers, stamped `at`, with `streamed` set; None without one."""
    fields = live.get("fields") or {}
    location = fields.get("Location")
    if not isinstance(location, dict):
        return None
    lat, lng = tesla_number(location.get("latitude")), tesla_number(location.get("longitude"))
    if lat is None or lng is None or (lat == 0 and lng == 0):
        return None
    shift = tesla_shift(fields.get("Gear"))
    fix = {
        "lat": lat, "lng": lng, "at": at, "heading": tesla_number(fields.get("GpsHeading")),
        "shift": shift, "speed": tesla_number(fields.get("VehicleSpeed")), "streamed": True,
    }
    if shift == "P" and live.get("parked_at"):
        fix["parked_at"] = float(live["parked_at"])
    return fix


def tesla_shift(gear: Any) -> str | None:
    """ "ShiftStateD" as "D"; None for anything but P, D, R and N."""
    shift = str(gear or "").removeprefix("ShiftState")
    return shift if shift in ("P", "D", "R", "N") else None


def tesla_stream_reset(live: dict[str, Any]) -> None:
    """
    A connection begun or ended: what the car said over the last one may no longer hold, its gear
    among it (a shift into P counts only when both gears were heard on this connection). Under _tesla_lock.
    """
    live["current"], live["parked_at"] = False, None
    live["fields"].pop("Gear", None)


def tesla_stream_receive(topic: str, payload: bytes, now: float) -> None:
    """Takes one message from the telemetry server (see "Tesla telemetry")."""
    parts = topic.split("/")
    if len(parts) < 3 or parts[0] != TESLA_TELEMETRY_TOPIC:
        return
    name = next((n for n, vin in TESLA_CARS.items() if vin == parts[1]), None)
    if name is None:
        return
    try:
        value = json.loads(payload) if payload else None
    except ValueError:
        value = payload.decode("utf-8", "replace")
    with _tesla_lock:
        live = _tesla_live.setdefault(name, {"fields": {}, "heard": 0.0, "located": 0.0, "connected": None,
                                             "current": False, "parked_at": None})
        live["heard"] = now
        if parts[2] == "connectivity" and isinstance(value, dict):
            connected = value.get("Status") == "CONNECTED"
            if connected != live["connected"]:
                log.info("tesla: %s's stream %s", name, "connected" if connected else "disconnected")
            live["connected"] = connected
            tesla_stream_reset(live)
            return
        if parts[2] == "errors":
            log.warning("tesla: %s's stream reports %s: %s", name, parts[-1], value)
            return
        if parts[2] != "v" or len(parts) < 4:
            return
        field = parts[3]
        if field == "Gear":
            before, after = tesla_shift(live["fields"].get("Gear")), tesla_shift(value)
            if after != "P":
                live["parked_at"] = None
            elif before in ("D", "R", "N"):
                live["parked_at"] = now
        live["fields"][field] = value
        # Anything coming through is the car awake and connected, whatever was missed of the last connect.
        live["connected"] = True
        if field == "Location":
            live["current"] = True
        if field not in ("Location", "Gear", "VehicleSpeed", "GpsHeading") or not live.get("current"):
            return
        fix = tesla_stream_fix(live, now)
        if fix is None:
            return
        if field == "Location" and not live["located"]:
            home = state_get(HOME_KEY)
            if home:
                d = haversine_metres((fix["lat"], fix["lng"]), (float(home["lat"]), float(home["lng"])))
                log.info("tesla: %s streaming, %.0f m from home", name, d)
        if field == "Location":
            live["located"] = now
        _tesla_answers[name] = (now, fix)


def tesla_stream_lost() -> None:
    """
    The broker connection dropped: whatever the cars said meanwhile is lost, so none counts as
    connected, and none as streaming (see `tesla_streaming`) until it sends its location again: a
    car that drove off meanwhile is asked about as often as one that doesn't stream.
    """
    with _tesla_lock:
        for live in _tesla_live.values():
            live["connected"] = None
            live["located"] = 0.0
            tesla_stream_reset(live)
    with _charging_lock:
        _charging_heard.clear()


def tesla_stream_answer(name: str, now: float) -> dict[str, Any] | None:
    """
    Where the car is by its stream, while it is connected, was heard in the last
    TESLA_STREAM_FRESH_SECONDS, and has sent its location over this connection; None otherwise.
    Under _tesla_lock.
    """
    live = _tesla_live.get(name)
    if (not live or not live.get("connected") or not live.get("current")
            or now - float(live.get("heard") or 0) > TESLA_STREAM_FRESH_SECONDS):
        return None
    return tesla_stream_fix(live, now)


def tesla_streaming(name: str, now: float) -> bool:
    """Whether the car has streamed its location in the last TESLA_STREAM_TRUST_SECONDS."""
    located = float((_tesla_live.get(name) or {}).get("located") or 0)
    return bool(located) and now - located <= TESLA_STREAM_TRUST_SECONDS


def tesla_streamed_at(name: str) -> float:
    """When the car's stream last said where it is, 0 for never."""
    return float((_tesla_live.get(name) or {}).get("located") or 0)


def tesla_live_status(name: str, now: float) -> dict[str, Any] | None:
    """
    What the car's stream last said beyond where it is, for the phones: battery, charging, locks,
    the route, temperatures; and whether it is connected now. None for a car that hasn't streamed.
    Numbers as the car gives them: miles, minutes, °C, percent.
    """
    live = _tesla_live.get(name)
    if not live:
        return None
    fields = live.get("fields") or {}

    def text(key: str) -> str | None:
        value = fields.get(key)
        return str(value) if value not in (None, "") else None

    def flag(key: str) -> bool | None:
        value = fields.get(key)
        if isinstance(value, bool) or value is None:
            return value
        return {"true": True, "false": False}.get(str(value).lower())

    return {
        "connected": bool(live.get("connected")) and now - float(live.get("heard") or 0) <= TESLA_STREAM_FRESH_SECONDS,
        "heard": live.get("heard") or None,
        "battery": tesla_number(fields.get("Soc")), "range_mi": tesla_number(fields.get("EstBatteryRange")),
        "charge_limit": tesla_number(fields.get("ChargeLimitSoc")),
        "charging": (text("DetailedChargeState") or "").removeprefix("DetailedChargeState") or None,
        "hours_to_full": tesla_number(fields.get("TimeToFullCharge")),
        "locked": flag("Locked"), "sentry": (text("SentryMode") or "").removeprefix("SentryModeState") or None,
        "odometer_mi": tesla_number(fields.get("Odometer")),
        "destination": text("DestinationName"), "minutes_to_arrival": tesla_number(fields.get("MinutesToArrival")),
        "inside_c": tesla_number(fields.get("InsideTemp")), "outside_c": tesla_number(fields.get("OutsideTemp")),
    }


def tesla_stream_forever() -> None:
    """Listens to the telemetry server through the broker for as long as the relay runs, reconnecting whenever it drops."""
    if not (tesla_on() and TESLA_CARS):
        return
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        log.warning("tesla: paho-mqtt isn't installed, so the cars' streams are off")
        return

    def on_connect(client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            log.warning("tesla: the broker refused the connection: %s", reason_code)
            return
        client.subscribe(f"{TESLA_TELEMETRY_TOPIC}/#")
        log.info("tesla: listening for the cars' streams on %s", TESLA_TELEMETRY_TOPIC)

    def on_message(client, userdata, message):
        now = time.time()
        try:
            tesla_stream_receive(message.topic, message.payload, now)
        except Exception:
            log.exception("tesla: couldn't take a message on %s", message.topic)
        try:
            charging_receive(message.topic, message.payload, now)
        except Exception:
            log.exception("charging: couldn't take a message on %s", message.topic)

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="homesafe-relay-tesla")
    client.on_connect, client.on_message = on_connect, on_message
    client.on_disconnect = lambda *args: tesla_stream_lost()
    client.reconnect_delay_set(min_delay=1, max_delay=60)
    while True:
        try:
            client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
            client.loop_forever(retry_first_connection=True)
        except Exception:
            # The doors thread says when there's no broker; this one just keeps trying.
            tesla_stream_lost()
            time.sleep(30)


def tesla_telemetry_config(now: float) -> dict[str, Any]:
    """The configuration telling the cars where to stream and what: what `scripts/tesla-sign` signs (see "Tesla telemetry")."""
    from urllib.parse import urlparse

    with open(TESLA_TELEMETRY_CA_FILE, encoding="utf-8") as f:
        ca = f.read()
    return {
        "hostname": urlparse(TESLA_PUBLIC_URL).hostname, "port": TESLA_TELEMETRY_PORT, "ca": ca,
        "fields": {field: {"interval_seconds": seconds} for field, seconds in TESLA_TELEMETRY_FIELDS.items()},
        "exp": int(now + TESLA_TELEMETRY_DAYS * 86400),
    }


def tesla_telemetry_send(token: str) -> dict[str, Any]:
    """
    Hands Tesla the signed configuration (a JWS from `scripts/tesla-sign`) for every one of TESLA_CARS, by
    the account each is linked through; answers what Tesla said for each account. A car Tesla
    skips as `missing_key` hasn't had the application's virtual key added (docs/tesla.md).
    """
    global _tesla_why
    accounts: dict[str, list[str]] = {}
    for vin in TESLA_CARS.values():
        row = with_db(lambda c: c.execute("SELECT account FROM tesla_vehicles WHERE vin=?", (vin,)).fetchone())
        if row:
            accounts.setdefault(row[0], []).append(vin)
    said = {}
    for account, vins in accounts.items():
        access = tesla_access(account)
        with _tesla_lock:
            _tesla_why = "telemetry"
            if not access or not tesla_spend():
                said[account[:8]] = "not linked, or out of calls"
                continue
        r = requests.post(f"{TESLA_API}/api/1/vehicles/fleet_telemetry_config_jws", json={"vins": vins, "token": token},
                          headers={"Authorization": f"Bearer {access}"}, timeout=30)
        said[account[:8]] = r.json() if r.headers.get("content-type", "").startswith("application/json") else f"{r.status_code} {r.text[:200]}"
    return said


def tesla_telemetry_status() -> dict[str, Any]:
    """
    Whether each car has taken up the configuration (`synced`), and the errors it reported since:
    each car's own, and the application's (`partner_errors`, all its cars', by a partner token).
    """
    global _tesla_why
    report: dict[str, Any] = {}
    for name, vin in TESLA_CARS.items():
        row = with_db(lambda c: c.execute("SELECT account FROM tesla_vehicles WHERE vin=?", (vin,)).fetchone())
        access = tesla_access(row[0]) if row else None
        if not access:
            report[name] = "not linked"
            continue
        car = {}
        for path in ("fleet_telemetry_config", "fleet_telemetry_errors"):
            with _tesla_lock:
                _tesla_why = "telemetry"
                if not tesla_spend():
                    car[path] = "out of calls"
                    continue
            r = requests.get(f"{TESLA_API}/api/1/vehicles/{vin}/{path}", headers={"Authorization": f"Bearer {access}"}, timeout=15)
            body = (r.json().get("response") if r.ok else None) or f"{r.status_code} {r.text[:200]}"
            if isinstance(body, dict) and isinstance(body.get("config"), dict):
                body["config"].pop("ca", None)  # long, and only the box's own CA
            car[path] = body
        report[name] = car
    try:
        r = requests.get(f"{TESLA_API}/api/1/partner_accounts/fleet_telemetry_errors",
                         headers={"Authorization": f"Bearer {tesla_partner_token()}"}, timeout=15)
        report["partner_errors"] = (r.json().get("response") if r.ok else None) or f"{r.status_code} {r.text[:200]}"
    except Exception as e:
        report["partner_errors"] = f"failed: {e}"
    return report


# ---------------------------------------------------------------- charging

# What the Teslas cost to charge, kept for good. The cars stream their charging (see "Tesla
# telemetry"), and nothing but the stream sees a night's charge in the driveway: Tesla keeps no
# home history anyone can ask for. So every charge is written down as it happens:
#
# - `charge_sessions`: one row per charge, from the car's own "Charging" to whatever ends it. Two
#   with less than CHARGING_MERGE_SECONDS between them at the same place are one (a charge paused).
# - `charge_hours`: the energy of each session by the hour it went in, which is what a rate by the
#   hour needs. Measured from the charger's volts and amps as they stream; where they aren't (a
#   car not told to send them yet), the battery's rise spread over the charge.
# - `car_days`: each car's odometer and lifetime energy at the end of each day, for miles and
#   efficiency.
#
# Costs aren't kept, only worked out from those rows when asked for, like the budget's months: a
# plan changed in the app, or a rate corrected in CHARGING_RATES_FILE, re-prices all of it.
#
# A charge at home is priced at the electricity plan's rate for each of its hours, with the tax and
# surcharge the bill adds. A tiered plan's car is priced at the top tier: the house uses up the
# baseline with or without the car, so each kWh the car adds is one more at that rate. The monthly
# customer charge is the house's, and is left out.
#
# A Supercharger's price is what Tesla billed (`charging_history_sync`, which needs the
# vehicle_charging_cmds scope; docs/tesla.md), and goes back as far as Tesla's history does. Its
# idle fees are kept apart too (`idle_fee`): they count in what it cost, not in what a kWh did. What
# the cards were charged for Superchargers stands in for any of it Tesla's receipts don't cover.
# Anywhere else the price isn't known: someone can type it in, session by session.
#
# The plans' rates are in CHARGING_RATES_FILE on the box, not in the repository, which is public: a
# utility gives away the town. `relay/charging-rates.example.json` shows the shape.
CHARGING_RATES_FILE = os.environ.get("CHARGING_RATES_FILE", "/data/charging-rates.json")
CHARGING_PLAN_KEY = "charging_plan"
CHARGING_SOC_KEY = "charging_soc"
CHARGING_HISTORY_KEY = "charging_history"
CHARGING_SIGNALS_KEY = "charging_signals"
CHARGING_MERGE_SECONDS = 900.0
# A charge the car stops talking about: no word for this long ends it where it was last heard.
CHARGING_STALE_SECONDS = 3600.0
# How long the last power reading is taken to hold. Cars send a value only when it changes.
CHARGING_HOLD_SECONDS = 900.0
# Less than this, and over this quickly, is plugging in and out rather than a charge.
CHARGING_MIN_KWH = 0.05
CHARGING_MIN_SECONDS = 120.0
# A battery this much fuller than it last said, with no charge heard, was charged out of earshot.
CHARGING_MISSED_PERCENT = 3.0
# Used only when a car hasn't sent how much energy it holds: a Model Y's usable pack, what reaches
# the battery of what the wall gives at home, and the power a missed charge is taken to have run at.
CHARGING_PACK_KWH = float(os.environ.get("CHARGING_PACK_KWH", "75"))
CHARGING_AC_EFFICIENCY = 0.90
CHARGING_AC_KW = 7.0
# Measured energy shorter than this share of what the battery gained missed too much to believe.
CHARGING_MEASURED_SHARE = 0.7
CHARGING_HISTORY_SECONDS = 6 * 3600.0
CHARGING_CHECK_SECONDS = 300.0
CHARGING_HISTORY_PAGES = 40
CHARGING_PAGE = 50
CHARGING_MONTHS_IN_ANSWER = 120
CHARGING_HOURS_DAYS = 90
# How far apart a Tesla receipt and a charge the stream saw may start and still be the same one.
CHARGING_RECEIPT_SLACK_SECONDS = 1800.0
# How long after a Supercharger a charge written down from the battery's rise may be that one.
CHARGING_RECEIPT_GUESS_SECONDS = 86400.0
# What's streamed for this section, of TESLA_TELEMETRY_FIELDS.
CHARGING_FIELDS = (
    "Soc", "DetailedChargeState", "Location", "Odometer", "ACChargingPower", "DCChargingPower", "ACChargingEnergyIn",
    "DCChargingEnergyIn", "ChargeAmps", "ChargerVoltage", "FastChargerPresent", "FastChargerType", "EnergyRemaining", "LifetimeEnergyUsed",
)
# The fields that say the car has been told to send what `charging_kw` measures.
CHARGING_POWER_FIELDS = ("ACChargingPower", "DCChargingPower", "ChargeAmps", "ChargerVoltage")
_charging_lock = threading.RLock()
# What each car said over its connection as it is now: a charge state left from before the car last
# went quiet says nothing about it now.
_charging_heard: dict[str, dict[str, Any]] = {}
# Each car's charge in progress, as its row plus how it is being measured (`tick`, `kw`, `counter`).
_charging_open: dict[str, dict[str, Any]] = {}
_charging_file: dict[str, Any] = {"mtime": None, "config": {}}


def charging_rates() -> dict[str, Any]:
    """CHARGING_RATES_FILE as last read, read again when it changes; {} without one. A file that doesn't parse keeps the last that did."""
    try:
        mtime = os.path.getmtime(CHARGING_RATES_FILE)
    except OSError:
        return {}
    if mtime != _charging_file["mtime"]:
        _charging_file["mtime"] = mtime
        try:
            with open(CHARGING_RATES_FILE, encoding="utf-8") as f:
                config = json.load(f)
            if not isinstance(config, dict) or not isinstance(config.get("plans"), dict):
                raise ValueError("no plans")
            if not all(isinstance(v.get("from"), str) for plan in config["plans"].values() for v in plan.get("versions") or []):
                raise ValueError("a plan's version has no date")
            _charging_file["config"] = config
        except (AttributeError, OSError, ValueError) as e:
            log.warning("charging: %s doesn't read, keeping the last that did: %s", CHARGING_RATES_FILE, e)
    return _charging_file["config"]


def charging_zone(rates: dict[str, Any]) -> ZoneInfo | None:
    """The clock the utility's hours and the months go by: the rates file's `time_zone`, not wherever the phones happen to be, or else theirs."""
    try:
        return ZoneInfo(rates["time_zone"]) if rates.get("time_zone") else household_zone()
    except Exception:
        return household_zone()


def charging_plan_id(rates: dict[str, Any]) -> str | None:
    """The plan the household is on: the one chosen in the app, or the file's default, or the first."""
    plans = rates.get("plans") or {}
    chosen = state_get(CHARGING_PLAN_KEY)
    if chosen in plans:
        return chosen
    if rates.get("default_plan") in plans:
        return rates["default_plan"]
    return next(iter(plans), None)


def charging_version(plan: dict[str, Any], day: str) -> dict[str, Any]:
    """A plan's rates as they stood on `day` (YYYY-MM-DD): the latest version from then or before, or for earlier days its first."""
    versions = sorted(plan.get("versions") or [], key=lambda v: v["from"])
    if not versions:
        return {}
    return next((v for v in reversed(versions) if v["from"] <= day), versions[0])


def charging_period(version: dict[str, Any], local: datetime) -> str:
    """Which of a plan's rates applies at a moment on the household's clock: "peak" or "off_peak" by the hour, "tier2" on a tiered plan."""
    if "peak" not in version:
        return "tier2"
    workday = local.weekday() < 5 and local.date().isoformat() not in (version.get("holidays") or [])
    return "peak" if workday and int(version.get("peak_start", 0)) <= local.hour < int(version.get("peak_end", 0)) else "off_peak"


def charging_billed(rates: dict[str, Any], rate: float) -> float:
    """A rate as the bill charges it: the tax on it, and the surcharge the tax leaves alone."""
    return float(rate) * (1 + float(rates.get("tax_percent") or 0) / 100) + float(rates.get("surcharge_per_kwh") or 0)


def charging_price(rates: dict[str, Any], plan_id: str, at: float, zone: ZoneInfo | None) -> tuple[float, str]:
    """What a kWh costs on a plan at an epoch, as billed, and which rate that is."""
    local = datetime.fromtimestamp(at, zone or timezone.utc)
    version = charging_version(rates["plans"][plan_id], local.date().isoformat())
    period = charging_period(version, local)
    return charging_billed(rates, float(version.get(period) or 0)), period


def charging_cost(rates: dict[str, Any], plan_id: str | None, hours: list[tuple[float, float]], zone: ZoneInfo | None) -> float | None:
    """What (hour, kWh) rows cost on a plan; None without one."""
    if not plan_id or plan_id not in (rates.get("plans") or {}):
        return None
    return sum(kwh * charging_price(rates, plan_id, hour, zone)[0] for hour, kwh in hours)


def charging_flag(value: Any) -> bool:
    return value is True or str(value).lower() == "true"


def charging_kw(heard: dict[str, Any]) -> float:
    """
    The power going in now. From the wall, volts times amps, which is what the meter bills: what the
    car reports as AC power is rounded to a whole kW. A DC charger's is as it reports it. Volts
    times amps of one phase corrected by the reported power on a three-phase supply.
    """
    dc = tesla_number(heard.get("DCChargingPower"))
    if dc and dc > 0.2:
        return dc
    ac, volts, amps = (tesla_number(heard.get(k)) for k in ("ACChargingPower", "ChargerVoltage", "ChargeAmps"))
    if volts and amps and volts > 50:
        kw = volts * amps / 1000
        return kw * round(ac / kw) if ac and ac > 1.5 * kw else kw
    return ac or 0.0


def charging_fast(heard: dict[str, Any]) -> bool:
    dc = tesla_number(heard.get("DCChargingPower"))
    return charging_flag(heard.get("FastChargerPresent")) or bool(dc and dc > 0.5)


def charging_where(name: str, heard: dict[str, Any]) -> tuple[float, float] | None:
    """Where a car is by what it just said, or the relay's last answer about it."""
    location = heard.get("Location")
    if isinstance(location, dict):
        lat, lng = tesla_number(location.get("latitude")), tesla_number(location.get("longitude"))
        if lat is not None and lng is not None and (lat or lng):
            return lat, lng
    where = (_tesla_answers.get(name) or (0, None))[1]
    return (float(where["lat"]), float(where["lng"])) if where and not where.get("asleep") and where.get("lat") is not None else None


def charging_place(name: str, heard: dict[str, Any], where: tuple[float, float] | None) -> str | None:
    """
    "home", "supercharger", "fast" (another company's fast charger) or "other"; None while there's
    nothing to tell by. Somewhere unknown but on an AC charger stays None until it's known.
    """
    if charging_fast(heard):
        kind = str(heard.get("FastChargerType") or "")
        return "fast" if kind and "Supercharger" not in kind and "Tesla" not in kind and "Unknown" not in kind and "SNA" not in kind else "supercharger"
    home = state_get(HOME_KEY)
    if not where or not home:
        return None
    d = haversine_metres(where, (float(home["lat"]), float(home["lng"])))
    return "home" if d <= float(home.get("radius_m") or 150) + TESLA_HOME_MARGIN_METRES else "other"


def charging_add_energy(session_id: int, start: float, end: float, kw: float) -> float:
    """Files `kw` from `start` to `end` under the hours it fell in; answers the kWh."""
    total = 0.0
    while start < end and kw > 0:
        hour = start - start % 3600
        upto = min(end, hour + 3600)
        kwh = kw * (upto - start) / 3600
        total += kwh
        with_db(lambda c: c.execute(
            "INSERT INTO charge_hours VALUES (?,?,?) ON CONFLICT(session_id, hour) DO UPDATE SET kwh = kwh + excluded.kwh", (session_id, hour, kwh)))
        start = upto
    with_db(lambda c: c.commit())
    return total


CHARGE_COLUMNS = (
    "id", "car", "place", "source", "started", "ended", "soc_start", "soc_end", "energy_start", "energy_end", "power_kwh",
    "counter_kwh", "kwh_battery", "kwh_wall", "how", "peak_kw", "lat", "lng", "site", "paid", "tesla_id", "typed_cost", "note", "updated", "idle_fee",
)


def charging_row(row: tuple) -> dict[str, Any]:
    return dict(zip(CHARGE_COLUMNS, row))


def charging_save(session: dict[str, Any]) -> None:
    columns = [c for c in CHARGE_COLUMNS if c != "id"]
    values = [session.get(c) for c in columns] + [session["id"]]
    with_db(lambda c: (c.execute(f"UPDATE charge_sessions SET {', '.join(f'{k}=?' for k in columns)} WHERE id=?", values), c.commit()))


def charging_insert(session: dict[str, Any]) -> dict[str, Any]:
    columns = [c for c in CHARGE_COLUMNS if c != "id"]
    values = [session.get(c) for c in columns]

    def write(c: sqlite3.Connection) -> int:
        cursor = c.execute(f"INSERT INTO charge_sessions ({', '.join(columns)}) VALUES ({', '.join('?' * len(columns))})", values)
        c.commit()
        return int(cursor.lastrowid)

    return {**session, "id": with_db(write)}


def charging_begin(name: str, heard: dict[str, Any], now: float) -> dict[str, Any]:
    """A charge starting: a new row, or the car's last one if that ended moments ago at the same place (paused, not over)."""
    where = charging_where(name, heard)
    place = charging_place(name, heard, where)
    soc, energy = tesla_number(heard.get("Soc")), tesla_number(heard.get("EnergyRemaining"))
    row = with_db(lambda c: c.execute(
        f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE car=? AND source='stream' AND ended IS NOT NULL ORDER BY started DESC LIMIT 1", (name,)).fetchone())
    last = charging_row(row) if row else None
    if last and not last["tesla_id"] and now - float(last["ended"]) <= CHARGING_MERGE_SECONDS and (place is None or last["place"] in (None, place)):
        if last["how"] != "power":
            # Its hours were spread over what the battery gained, which the end of this charge does again for all of it.
            with_db(lambda c: (c.execute("DELETE FROM charge_hours WHERE session_id=?", (last["id"],)), c.commit()))
            last["power_kwh"] = 0.0
        last.update(ended=None, kwh_wall=None, kwh_battery=None, how=None, place=place or last["place"], updated=now)
        charging_save(last)
        log.info("charging: %s goes on charging, session %s", name, last["id"])
        return last
    session = charging_insert({
        "car": name, "place": place, "source": "stream", "started": now, "soc_start": soc, "soc_end": soc, "energy_start": energy, "energy_end": energy,
        "power_kwh": 0.0, "counter_kwh": 0.0, "peak_kw": 0.0, "lat": where and where[0], "lng": where and where[1], "updated": now,
    })
    log.info("charging: %s started charging (%s), session %s", name, place or "somewhere unknown", session["id"])
    return session


def charging_energy_added(session: dict[str, Any]) -> float | None:
    """What reached the battery, best measure first: the energy it holds, its percent, and for less than a percent the car's own counter."""
    start, end = tesla_number(session.get("energy_start")), tesla_number(session.get("energy_end"))
    if start is not None and end is not None and end > start:
        return end - start
    start, end = tesla_number(session.get("soc_start")), tesla_number(session.get("soc_end"))
    if start is not None and end is not None and end > start:
        return (end - start) / 100 * CHARGING_PACK_KWH
    return float(session.get("counter_kwh") or 0) or None


def charging_spread(session_id: int, start: float, end: float, kwh: float) -> None:
    """Files `kwh` evenly over a stretch of time, for a charge whose power wasn't measured."""
    charging_add_energy(session_id, start, max(end, start + 1), kwh * 3600 / max(end - start, 1))


def charging_finish(session: dict[str, Any], now: float) -> None:
    """
    A charge over: how much went in, from the wall, and the hours' rows made to add up to it. What
    was measured at the wall is believed if it isn't well short of what the battery says it gained;
    otherwise (a car not sending it, a stream that dropped) that gain, plus what charging loses.
    """
    battery = charging_energy_added(session)
    measured = float(session.get("power_kwh") or 0)
    fast = session.get("place") in ("supercharger", "fast")
    expected = (battery or 0) / (1.0 if fast else CHARGING_AC_EFFICIENCY)
    if measured > 0 and measured >= CHARGING_MEASURED_SHARE * expected:
        wall, how = measured, "power"
    elif expected > 0:
        wall, how = expected, "battery"
    else:
        wall, how = 0.0, None
    session.update(ended=now, kwh_battery=battery, kwh_wall=wall, how=how, updated=now)
    if session.get("place") is None:
        session["place"] = "other"
    started = float(session["started"])
    if wall < CHARGING_MIN_KWH and now - started < CHARGING_MIN_SECONDS and not session.get("paid"):
        with_db(lambda c: (c.execute("DELETE FROM charge_hours WHERE session_id=?", (session["id"],)), c.execute("DELETE FROM charge_sessions WHERE id=?", (session["id"],)), c.commit()))
        return
    if how != "power":
        with_db(lambda c: (c.execute("DELETE FROM charge_hours WHERE session_id=?", (session["id"],)), c.commit()))
        if wall > 0:
            charging_spread(session["id"], started, now, wall)
    charging_save(session)
    log.info("charging: %s finished charging (%s): %.1f kWh by %s", session["car"], session["place"], wall, how)


def charging_session_open(name: str) -> dict[str, Any] | None:
    """The car's charge in progress, the row left open before the relay last restarted included. Under _charging_lock."""
    session = _charging_open.get(name)
    if session is not None:
        return session
    row = with_db(lambda c: c.execute(
        f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE car=? AND ended IS NULL ORDER BY started DESC LIMIT 1", (name,)).fetchone())
    if not row:
        return None
    session = charging_row(row)
    session.update(tick=float(session.get("updated") or session["started"]), kw=0.0)
    _charging_open[name] = session
    return session


def charging_tick(name: str, heard: dict[str, Any], now: float) -> None:
    """Takes in what a car just said about its charging. Under _charging_lock."""
    kw = charging_kw(heard)
    state = str(heard.get("DetailedChargeState") or "").removeprefix("DetailedChargeState")
    # A car in gear isn't charging, whatever was last heard of its charger: a Complete can be lost with the broker's link.
    driving = tesla_shift(((_tesla_live.get(name) or {}).get("fields") or {}).get("Gear")) in ("D", "R")
    if driving:
        state, kw = "Driving", 0.0
    # Said to be charging; or, with the state not heard since the relay or the car reconnected, power going in.
    on = state in ("Charging", "Starting") or (state in ("", "Unknown") and kw > 0.2)
    session = charging_session_open(name)
    if session is None:
        if not on:
            return
        session = {**charging_begin(name, heard, now), "tick": now, "kw": 0.0}
        _charging_open[name] = session
        # What the last charge counted to, until this one's counts come in.
        heard.pop("ACChargingEnergyIn", None)
        heard.pop("DCChargingEnergyIn", None)
    elif not driving:
        start = max(float(session["tick"]), now - CHARGING_HOLD_SECONDS)
        session["power_kwh"] = float(session.get("power_kwh") or 0) + charging_add_energy(session["id"], start, now, float(session["kw"]))
    session.update(tick=now, kw=kw, peak_kw=max(float(session.get("peak_kw") or 0), kw))
    soc, energy = tesla_number(heard.get("Soc")), tesla_number(heard.get("EnergyRemaining"))
    # Other things a car says aren't word of its charge: with its state lost, only a filling battery keeps the charge going.
    if on or (soc is not None and soc > float(session.get("soc_end") or soc)):
        session["updated"] = now
    if soc is not None:
        session["soc_end"] = soc
        if session.get("soc_start") is None:
            session["soc_start"] = soc
    if energy is not None:
        session["energy_end"] = energy
        if session.get("energy_start") is None:
            session["energy_start"] = energy
    # The car's own counter of this charge's energy: it restarts with each charge.
    counter = tesla_number(heard.get("DCChargingEnergyIn")) if charging_fast(heard) else tesla_number(heard.get("ACChargingEnergyIn"))
    if counter is not None and counter > float(session.get("counter_kwh") or 0):
        session["counter_kwh"] = counter
    if session.get("place") is None or (charging_fast(heard) and session["place"] not in ("supercharger", "fast")):
        where = charging_where(name, heard)
        session["place"] = charging_place(name, heard, where) or session.get("place")
        if where and session.get("lat") is None:
            session["lat"], session["lng"] = where
    if on or not state or state == "Unknown":
        charging_save({k: v for k, v in session.items() if k in CHARGE_COLUMNS})
        return
    _charging_open.pop(name, None)
    charging_finish({k: v for k, v in session.items() if k in CHARGE_COLUMNS}, now)


def charging_missed(name: str, soc: float, now: float, heard: dict[str, Any]) -> None:
    """
    A battery fuller than it last said with no charge heard: charged while the relay, the box or the
    stream was down. Written down from the rise alone, ending now and running at CHARGING_AC_KW, and
    marked so (`source` "soc"). Under _charging_lock.
    """
    seen = (state_get(CHARGING_SOC_KEY) or {}).get(name) or {}
    before, at = tesla_number(seen.get("soc")), tesla_number(seen.get("at"))
    if before is None or at is None or soc - before < CHARGING_MISSED_PERCENT or charging_session_open(name) is not None:
        return
    # Coasting down a mountain charges a battery too.
    if tesla_shift(((_tesla_live.get(name) or {}).get("fields") or {}).get("Gear")) in ("D", "R", "N"):
        return
    row = with_db(lambda c: c.execute(
        f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE car=? AND ended >= ? ORDER BY ended DESC LIMIT 1", (name, at)).fetchone())
    last = charging_row(row) if row else None
    if last and (last["tesla_id"] or last["source"] == "tesla"):
        return  # Tesla's receipt for it came first.
    if last and last["source"] == "stream" and now - float(last["ended"]) <= CHARGING_MERGE_SECONDS:
        # A charge's last reading, sent after it ended: each value comes on its own clock.
        last["soc_end"] = soc
        charging_finish(last, float(last["ended"]))
        return
    battery = (soc - before) / 100 * CHARGING_PACK_KWH
    where = charging_where(name, heard)
    place = charging_place(name, heard, where) or "other"
    fast = place in ("supercharger", "fast")
    wall = battery if fast else battery / CHARGING_AC_EFFICIENCY
    started = max(at, float(last["ended"]) if last else at, now - wall / CHARGING_AC_KW * 3600)
    session = charging_insert({
        "car": name, "place": place, "source": "soc", "started": started, "ended": now, "soc_start": before, "soc_end": soc,
        "kwh_battery": battery, "kwh_wall": wall, "how": "battery", "lat": where and where[0], "lng": where and where[1], "updated": now,
    })
    charging_spread(session["id"], started, now, wall)
    log.info("charging: %s went from %.0f%% to %.0f%% unheard: %.1f kWh written down (%s)", name, before, soc, wall, place)


def charging_note_soc(name: str, soc: float, now: float) -> None:
    seen = state_get(CHARGING_SOC_KEY) or {}
    seen[name] = {"soc": soc, "at": now}
    state_set(CHARGING_SOC_KEY, seen)


def charging_note_day(name: str, field: str, value: float, now: float) -> None:
    """The car's odometer or lifetime energy as of today: the last of each day stands for it."""
    column = "odometer" if field == "Odometer" else "energy_used"
    day = household_day(now)
    with_db(lambda c: (c.execute(
        f"INSERT INTO car_days (car, day, {column}) VALUES (?,?,?) ON CONFLICT(car, day) DO UPDATE SET {column} = excluded.{column}",
        (name, day, value)), c.commit()))


def charging_receive(topic: str, payload: bytes, now: float) -> None:
    """Takes one message from the telemetry server for the charging ledger (see "charging")."""
    parts = topic.split("/")
    if len(parts) < 3 or parts[0] != TESLA_TELEMETRY_TOPIC:
        return
    name = next((n for n, vin in TESLA_CARS.items() if vin == parts[1]), None)
    if name is None:
        return
    try:
        value = json.loads(payload) if payload else None
    except ValueError:
        value = payload.decode("utf-8", "replace")
    with _charging_lock:
        heard = _charging_heard.setdefault(name, {})
        if parts[2] == "connectivity":
            heard.clear()
            return
        if parts[2] != "v" or len(parts) < 4 or parts[3] not in CHARGING_FIELDS:
            return
        field = parts[3]
        heard[field] = value
        number = tesla_number(value)
        if field in CHARGING_POWER_FIELDS and not state_get(CHARGING_SIGNALS_KEY):
            state_set(CHARGING_SIGNALS_KEY, {"at": now})
        if field in ("Odometer", "LifetimeEnergyUsed") and number is not None:
            charging_note_day(name, field, number, now)
        if field == "Soc" and number is not None:
            charging_missed(name, number, now, heard)
            charging_note_soc(name, number, now)
        charging_tick(name, heard, now)


def charging_close_stale(now: float) -> None:
    """Ends each charge its car has said nothing about for CHARGING_STALE_SECONDS, where it was last heard."""
    rows = with_db(lambda c: c.execute("SELECT car FROM charge_sessions WHERE ended IS NULL").fetchall())
    with _charging_lock:
        for (name,) in rows:
            session = charging_session_open(name)
            if session and now - float(session.get("updated") or session["started"]) >= CHARGING_STALE_SECONDS:
                _charging_open.pop(name, None)
                # What it last said of its charger is as old, and says nothing about what comes next.
                _charging_heard.pop(name, None)
                charging_finish({k: v for k, v in session.items() if k in CHARGE_COLUMNS}, float(session["updated"]))


def charging_receipt(entry: dict[str, Any]) -> dict[str, Any] | None:
    """One of Tesla's charging history entries, as what the ledger keeps; None for one that can't be read."""
    try:
        started = datetime.fromisoformat(str(entry["chargeStartDateTime"]).replace("Z", "+00:00")).timestamp()
        stop = entry.get("chargeStopDateTime") or entry.get("unlatchDateTime")
        ended = datetime.fromisoformat(str(stop).replace("Z", "+00:00")).timestamp() if stop else started
    except (KeyError, ValueError):
        return None
    paid, idle, kwh = 0.0, 0.0, 0.0
    for fee in entry.get("fees") or []:
        due = float(tesla_number(fee.get("totalDue")) or 0)
        paid += due
        if str(fee.get("feeType")).upper() != "CHARGING":
            idle += due
        elif str(fee.get("uom")).lower() == "kwh":
            kwh += sum(float(tesla_number(fee.get(k)) or 0) for k in ("usageBase", "usageTier1", "usageTier2", "usageTier3", "usageTier4"))
    return {
        "tesla_id": str(entry.get("sessionId")), "vin": entry.get("vin"), "started": started, "ended": ended,
        "site": entry.get("siteLocationName") or None, "paid": round(paid, 2), "idle": round(idle, 2), "kwh": kwh,
    }


def charging_keep_receipt(name: str, receipt: dict[str, Any], now: float) -> None:
    """A receipt onto the charge the stream saw of it, or as a row of its own for one it didn't. The receipt's kWh and price win."""
    tesla_id = receipt["tesla_id"]
    known = with_db(lambda c: c.execute("SELECT id FROM charge_sessions WHERE tesla_id=?", (tesla_id,)).fetchone())
    # The stream's charge may be this one and still open; the next read of the history finds it over.
    if not known and with_db(lambda c: c.execute(
            "SELECT 1 FROM charge_sessions WHERE car=? AND ended IS NULL AND COALESCE(place, '') != 'home' AND started <= ?",
            (name, receipt["ended"] + CHARGING_RECEIPT_SLACK_SECONDS)).fetchone()):
        return
    row = None if known else with_db(lambda c: c.execute(
        f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE car=? AND tesla_id IS NULL AND ended IS NOT NULL AND place != 'home'"
        " AND started <= ? AND ended >= ? ORDER BY ABS(started - ?) LIMIT 1",
        (name, receipt["ended"] + CHARGING_RECEIPT_SLACK_SECONDS, receipt["started"] - CHARGING_RECEIPT_SLACK_SECONDS, receipt["started"])).fetchone())
    # Or it was written down from the battery's rise, where the car next spoke, likely home.
    guess = None if known or row or receipt["kwh"] <= 0 else with_db(lambda c: c.execute(
        f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE car=? AND source='soc' AND ended >= ? AND ended <= ? ORDER BY ended LIMIT 1",
        (name, receipt["ended"] - CHARGING_RECEIPT_SLACK_SECONDS, receipt["ended"] + CHARGING_RECEIPT_GUESS_SECONDS)).fetchone())
    fields = {"tesla_id": tesla_id, "site": receipt["site"], "paid": receipt["paid"], "idle_fee": receipt.get("idle"), "updated": now, "place": "supercharger"}
    if receipt["kwh"] > 0:
        fields.update(kwh_wall=receipt["kwh"], how="receipt")
    if known:
        with_db(lambda c: (c.execute("UPDATE charge_sessions SET site=?, paid=?, idle_fee=?, updated=? WHERE id=?", (receipt["site"], receipt["paid"], receipt.get("idle"), now, known[0])), c.commit()))
    elif row:
        session = charging_row(row)
        session.update(fields)
        charging_save(session)
    elif guess:
        session = charging_row(guess)
        session.update(fields, source="tesla", started=receipt["started"], ended=receipt["ended"])
        with_db(lambda c: (c.execute("DELETE FROM charge_hours WHERE session_id=?", (session["id"],)), c.commit()))
        charging_save(session)
        charging_spread(session["id"], receipt["started"], receipt["ended"], receipt["kwh"])
    else:
        session = charging_insert({"car": name, "source": "tesla", "started": receipt["started"], "ended": receipt["ended"], **fields})
        if receipt["kwh"] > 0:
            charging_spread(session["id"], receipt["started"], receipt["ended"], receipt["kwh"])


def charging_history_sync(now: float) -> dict[str, Any]:
    """
    Reads Tesla's history of each linked account's Supercharger charges into the ledger, and what
    came of it (kept in CHARGING_HISTORY_KEY): `scope_missing` for an account linked before the
    relay asked for the vehicle_charging_cmds scope, which needs linking again.
    """
    global _tesla_why
    names = {vin: name for name, vin in TESLA_CARS.items()}
    accounts = with_db(lambda c: sorted({r[0] for r in c.execute("SELECT account FROM tesla_vehicles") if r[0]}))
    report: dict[str, Any] = {"at": now, "kept": 0, "accounts": len(accounts), "scope_missing": False, "error": None}
    for account in accounts:
        try:
            access = tesla_access(account)
        except Exception as e:
            report["error"] = type(e).__name__
            continue
        if not access:
            continue
        for page in range(1, CHARGING_HISTORY_PAGES + 1):
            with _tesla_lock:
                _tesla_why = "charging"
                if not tesla_spend():
                    report["error"] = "out of calls"
                    break
            try:
                r = requests.get(f"{TESLA_API}/api/1/dx/charging/history", params={"pageNo": page, "pageSize": CHARGING_PAGE},
                                 headers={"Authorization": f"Bearer {access}"}, timeout=30)
            except Exception as e:
                report["error"] = type(e).__name__
                break
            if r.status_code == 403 and "scope" in r.text:
                report["scope_missing"] = True
                break
            if not r.ok:
                report["error"] = f"HTTP {r.status_code}"
                break
            body = r.json()
            entries = body.get("data") or []
            for entry in entries:
                receipt = charging_receipt(entry)
                name = names.get((receipt or {}).get("vin"))
                if receipt and name:
                    charging_keep_receipt(name, receipt, now)
                    report["kept"] += 1
            if not entries or not body.get("hasMoreData"):
                break
    state_set(CHARGING_HISTORY_KEY, report)
    log.info("charging: read Tesla's charging history: %s receipts%s%s", report["kept"], ", needs the scope" if report["scope_missing"] else "", f", {report['error']}" if report["error"] else "")
    return report


def charging_forever() -> None:
    """Ends charges their cars went quiet in, and reads Tesla's receipts every CHARGING_HISTORY_SECONDS."""
    if not (tesla_on() and TESLA_CARS):
        return
    while True:
        time.sleep(CHARGING_CHECK_SECONDS)
        now = time.time()
        try:
            charging_close_stale(now)
            if now - float((state_get(CHARGING_HISTORY_KEY) or {}).get("at") or 0) >= CHARGING_HISTORY_SECONDS:
                charging_history_sync(now)
        except Exception:
            log.exception("charging: the check failed")


def charging_card_spend() -> list[tuple[str, float]]:
    """Every card's Supercharger charges, refunds taken off, as (day, amount)."""
    rows = with_db(lambda c: c.execute(
        "SELECT date, amount FROM plaid_transactions WHERE removed_at IS NULL AND kind IN ('spend', 'refund')"
        " AND UPPER(name || ' ' || COALESCE(original, '')) LIKE '%SUPERCHARG%'").fetchall())
    return [(day, float(amount)) for day, amount in rows]


def charging_bills(match: str | None) -> dict[str, float]:
    """What was paid to the electricity provider by month, by the name its charges go under."""
    if not match:
        return {}
    months: dict[str, float] = {}
    rows = with_db(lambda c: c.execute(
        "SELECT date, amount FROM plaid_transactions WHERE removed_at IS NULL AND kind = 'spend' AND amount > 0 AND LOWER(name) LIKE ?", (f"%{match.lower()}%",)).fetchall())
    for day, amount in rows:
        months[day[:7]] = months.get(day[:7], 0.0) + float(amount)
    return months


def charging_hours_by_session() -> dict[int, list[tuple[float, float]]]:
    by_session: dict[int, list[tuple[float, float]]] = {}
    for session_id, hour, kwh in with_db(lambda c: c.execute("SELECT session_id, hour, kwh FROM charge_hours ORDER BY hour").fetchall()):
        by_session.setdefault(session_id, []).append((float(hour), float(kwh)))
    return by_session


def charging_session_view(session: dict[str, Any], hours: list[tuple[float, float]], rates: dict[str, Any], plan_id: str | None, zone: ZoneInfo | None) -> dict[str, Any]:
    """
    A session as the phones see it. `cost` is, in order: what someone typed, Tesla's receipt, or at
    home the plan's price of its hours. `cost_is` says which, null with no price known. `idle_fee` is
    how much of a receipt's price was for staying plugged in. An open charge counts what's been measured so far.
    """
    place = session.get("place")
    kwh = session.get("kwh_wall")
    how = session.get("how")
    if kwh is None:
        kwh = session.get("power_kwh") or 0.0
        how = how or ("power" if kwh else None)
    if not kwh and session.get("ended") is None:
        # In progress on a car not sending its power: what the battery has gained so far, as of its start.
        gained = charging_energy_added(session)
        kwh = gained / (1.0 if place in ("supercharger", "fast") else CHARGING_AC_EFFICIENCY) if gained else 0.0
        hours = [(float(session["started"]), kwh)]
        how = "battery"
    cost, cost_is = None, None
    if session.get("typed_cost") is not None:
        cost, cost_is = float(session["typed_cost"]), "typed"
    elif session.get("paid") is not None:
        cost, cost_is = float(session["paid"]), "receipt"
    elif place == "home":
        cost = charging_cost(rates, plan_id, hours, zone)
        cost_is = "plan" if cost is not None else None
    return {
        "id": session["id"], "car": session["car"], "name": display_name(session["car"]), "place": place or "other",
        "source": session.get("source"), "started": session["started"], "ended": session.get("ended"),
        "soc_start": session.get("soc_start"), "soc_end": session.get("soc_end"), "kwh": round(float(kwh or 0), 2),
        "kwh_battery": session.get("kwh_battery"), "peak_kw": session.get("peak_kw"), "how": how, "site": session.get("site"),
        "cost": round(cost, 2) if cost is not None else None, "cost_is": cost_is, "note": session.get("note"),
        "idle_fee": float(session.get("idle_fee") or 0) if cost_is == "receipt" else 0.0,
    }


def charging_sessions(before: float | None = None, car: str | None = None, place: str | None = None, limit: int = CHARGING_PAGE) -> list[dict[str, Any]]:
    """The newest charges first, as the phones see them, by page: pass the last one's `started` as `before`."""
    rates = charging_rates()
    zone = charging_zone(rates)
    plan_id = charging_plan_id(rates)
    where, args = ["1=1"], []
    if before is not None:
        where.append("started < ?")
        args.append(before)
    if car:
        where.append("car = ?")
        args.append(car)
    if place:
        where.append("place = ?")
        args.append(place)
    query = f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE {' AND '.join(where)} ORDER BY started DESC LIMIT ?"
    rows = with_db(lambda c: c.execute(query, (*args, limit)).fetchall())
    views = []
    for row in rows:
        session = charging_row(row)
        hours = with_db(lambda c: [(float(h), float(k)) for h, k in c.execute("SELECT hour, kwh FROM charge_hours WHERE session_id=?", (session["id"],))])
        views.append(charging_session_view(session, hours, rates, plan_id, zone))
    return views


def charging_session_detail(session_id: int) -> dict[str, Any] | None:
    """One charge, with each of its hours priced on the plan, and what the charge would be on each plan."""
    row = with_db(lambda c: c.execute(f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions WHERE id=?", (session_id,)).fetchone())
    if not row:
        return None
    rates = charging_rates()
    zone = charging_zone(rates)
    plan_id = charging_plan_id(rates)
    session = charging_row(row)
    hours = with_db(lambda c: [(float(h), float(k)) for h, k in c.execute("SELECT hour, kwh FROM charge_hours WHERE session_id=? ORDER BY hour", (session_id,))])
    view = charging_session_view(session, hours, rates, plan_id, zone)
    priced = []
    for hour, kwh in hours:
        # Only the house pays the plan: a Supercharger's hours have no price of the utility's.
        price, period = charging_price(rates, plan_id, hour, zone) if plan_id and session.get("place") == "home" else (None, None)
        priced.append({"hour": hour, "kwh": round(kwh, 3), "price": round(price, 5) if price is not None else None, "period": period})
    view["hours"] = priced
    view["plans"] = {p: round(charging_cost(rates, p, hours, zone) or 0, 2) for p in (rates.get("plans") or {})} if session.get("place") == "home" else {}
    return view


def charging_months(sessions: list[dict[str, Any]], by_session: dict[int, list[tuple[float, float]]], rates: dict[str, Any], plan_id: str | None, zone: ZoneInfo | None, money: bool) -> list[dict[str, Any]]:
    """
    Each month, newest first: each car's charging at home and away, its miles and the energy it used
    (from `car_days`), and for someone allowed the finances what the cards paid for Superchargers
    and what was paid to the electricity provider. `unmatched` is what the cards paid for
    Superchargers beyond Tesla's receipts: real money no one car can be said to have spent.

    A price per kWh or per mile only divides what has a price by the energy that has one, so each
    place also has `_priced_kwh`, and `_battery_kwh` for what of it reached the battery. `fast_idle`
    is what of `fast_cost` was idle fees.
    """
    months: dict[str, dict[str, Any]] = {}

    def month_of(key: str) -> dict[str, Any]:
        return months.setdefault(key, {"month": key, "cars": {}, "card_fast": 0.0, "receipts": 0.0, "electric_paid": 0.0, "peak_kwh": 0.0, "plans": {}, "unpriced": 0})

    def car_of(key: str, name: str) -> dict[str, Any]:
        return month_of(key)["cars"].setdefault(name, {
            "car": name, "name": display_name(name), "home_kwh": 0.0, "home_cost": 0.0, "fast_kwh": 0.0, "fast_cost": 0.0,
            "other_kwh": 0.0, "other_cost": 0.0, "sessions": 0, "miles": None, "used_kwh": None, "used_miles": None, "unpriced": 0,
            "home_priced_kwh": 0.0, "fast_priced_kwh": 0.0, "other_priced_kwh": 0.0, "home_battery_kwh": 0.0, "fast_battery_kwh": 0.0, "other_battery_kwh": 0.0, "fast_idle": 0.0,
            "home_priced_cost": 0.0, "fast_priced_cost": 0.0, "other_priced_cost": 0.0,
        })

    plan_ids = list((rates.get("plans") or {}).keys())
    tou_id = next((p for p in plan_ids if (rates["plans"][p].get("kind") == "tou")), None)
    for session in sessions:
        key = datetime.fromtimestamp(float(session["started"]), zone or timezone.utc).strftime("%Y-%m")
        car = car_of(key, session["car"])
        car["sessions"] += 1
        place = session["place"]
        prefix = "home" if place == "home" else "fast" if place in ("supercharger", "fast") else "other"
        car[f"{prefix}_kwh"] += session["kwh"]
        car[f"{prefix}_cost"] += session["cost"] or 0.0
        if session["cost"] is not None and session["kwh"] > 0:
            # Not more than went in, which a battery's rounded percent can claim of a short charge.
            battery = float(session.get("kwh_battery") or 0) or session["kwh"] * (1.0 if prefix == "fast" else CHARGING_AC_EFFICIENCY)
            car[f"{prefix}_priced_kwh"] += session["kwh"]
            # What those kWh cost, idle fee aside: a receipt with a price and no energy can't raise their rate.
            car[f"{prefix}_priced_cost"] += session["cost"] - (session["idle_fee"] if prefix == "fast" else 0.0)
            car[f"{prefix}_battery_kwh"] += min(battery, session["kwh"])
        car["fast_idle"] += session["idle_fee"]
        if session["cost"] is None and session["kwh"] > 0:
            car["unpriced"] += 1
            # What the cards paid stands in for a Supercharger's receipt, for whoever may see them.
            if not (money and place == "supercharger"):
                months[key]["unpriced"] += 1
        if session["cost_is"] == "receipt" or (session["cost_is"] == "typed" and place == "supercharger"):
            months[key]["receipts"] += session["cost"]
        if place == "home":
            hours = by_session.get(session["id"], [])
            month = months[key]
            for plan in plan_ids:
                month["plans"][plan] = month["plans"].get(plan, 0.0) + (charging_cost(rates, plan, hours, zone) or 0.0)
            if tou_id:
                month["peak_kwh"] += sum(kwh for hour, kwh in hours if charging_price(rates, tou_id, hour, zone)[1] == "peak")
    if money:
        for day, amount in charging_card_spend():
            month_of(day[:7])["card_fast"] += amount
        for key, paid in charging_bills(rates.get("bill_match")).items():
            month_of(key)["electric_paid"] += paid
    days = with_db(lambda c: c.execute("SELECT car, day, odometer, energy_used FROM car_days ORDER BY car, day").fetchall())
    # Miles go by the odometer alone. What was used goes only between days with both counters, with
    # `used_miles` the miles of those same days: the two can start apart, and energy from part of a
    # month over all its miles would make a car look thrifty.
    odometers: dict[str, float] = {}
    both: dict[str, tuple[float, float]] = {}
    for name, day, odometer, used in days:
        if odometer is None:
            continue
        before = odometers.get(name)
        odometers[name] = float(odometer)
        if before is not None and odometer >= before:
            car = car_of(day[:7], name)
            car["miles"] = (car["miles"] or 0.0) + float(odometer) - before
        if used is None:
            continue
        paired = both.get(name)
        both[name] = (float(odometer), float(used))
        if paired and odometer >= paired[0] and used >= paired[1]:
            car = car_of(day[:7], name)
            car["used_miles"] = (car["used_miles"] or 0.0) + float(odometer) - paired[0]
            car["used_kwh"] = (car["used_kwh"] or 0.0) + float(used) - paired[1]
    # A charge at the end of a month reaches the card in the next.
    unmatched: dict[str, float] = {}
    carried = 0.0
    for key in sorted(months):
        unmatched[key] = max(0.0, months[key]["card_fast"] - months[key]["receipts"] - carried)
        carried = max(0.0, months[key]["receipts"] - months[key]["card_fast"])
    answer = []
    for key in sorted(months, reverse=True)[:CHARGING_MONTHS_IN_ANSWER]:
        month = months[key]
        cars = sorted(month["cars"].values(), key=lambda c: c["car"])
        for car in cars:
            for field in ("home_kwh", "home_cost", "fast_kwh", "fast_cost", "other_kwh", "other_cost", "miles", "used_kwh", "used_miles", "home_priced_kwh", "fast_priced_kwh", "other_priced_kwh", "home_battery_kwh", "fast_battery_kwh", "other_battery_kwh", "fast_idle", "home_priced_cost", "fast_priced_cost", "other_priced_cost"):
                if car[field] is not None:
                    car[field] = round(car[field], 2)
        answer.append({
            "month": key, "cars": cars, "card_fast": round(month["card_fast"], 2) if money else None, "unmatched": round(unmatched[key], 2),
            "electric_paid": round(month["electric_paid"], 2) if money else None, "peak_kwh": round(month["peak_kwh"], 2),
            "plans": {plan: round(cost, 2) for plan, cost in month["plans"].items()}, "unpriced": month["unpriced"],
        })
    return answer


def charging_plans_view(rates: dict[str, Any], plan_id: str | None, zone: ZoneInfo | None, now: float) -> list[dict[str, Any]]:
    """Each plan as it stands today: its rates, raw and as billed, and what each hour of a weekday and of a weekend costs."""
    plans = []
    today = datetime.fromtimestamp(now, zone or timezone.utc).date().isoformat()
    for key, plan in (rates.get("plans") or {}).items():
        version = charging_version(plan, today)
        periods = [
            {"period": period, "rate": version[period], "billed": round(charging_billed(rates, version[period]), 5)}
            for period in ("tier1", "tier2", "peak", "off_peak") if period in version
        ]

        def by_hour(weekend: bool) -> list[float]:
            # Any weekend day and any weekday without a holiday, each hour of it.
            sample = datetime(2001, 1, 6 if weekend else 8)
            return [round(charging_billed(rates, float(version.get(charging_period({**version, "holidays": []}, sample.replace(hour=h))) or 0)), 5) for h in range(24)]

        plans.append({
            "id": key, "name": plan.get("name") or key, "kind": plan.get("kind") or ("tou" if "peak" in version else "tiered"), "url": plan.get("url"),
            "current": key == plan_id, "effective": version.get("from"), "periods": periods, "peak_start": version.get("peak_start"), "peak_end": version.get("peak_end"),
            "baseline_summer": version.get("baseline_summer"), "baseline_winter": version.get("baseline_winter"),
            "weekday": by_hour(False), "weekend": by_hour(True),
        })
    return plans


def charging_now(now: float, rates: dict[str, Any], plan_id: str | None, zone: ZoneInfo | None) -> list[dict[str, Any]]:
    """Each car's charging at this moment: its battery and limit, and a charge in progress's power, energy and cost so far."""
    cars = []
    for name in TESLA_CARS:
        live = tesla_live_status(name, now) or {}
        with _charging_lock:
            session = charging_session_open(name)
            heard = dict(_charging_heard.get(name) or {})
        car: dict[str, Any] = {
            "car": name, "name": display_name(name), "battery": live.get("battery"), "limit": live.get("charge_limit"),
            "range_mi": live.get("range_mi"), "plugged": live.get("charging"), "minutes_to_full": None, "session": None, "kw": None,
        }
        hours_to_full = live.get("hours_to_full")
        if hours_to_full is not None:
            car["minutes_to_full"] = round(float(hours_to_full) * 60)
        if session:
            row = {k: v for k, v in session.items() if k in CHARGE_COLUMNS}
            hours = with_db(lambda c: [(float(h), float(k)) for h, k in c.execute("SELECT hour, kwh FROM charge_hours WHERE session_id=?", (session["id"],))])
            car["session"] = charging_session_view(row, hours, rates, plan_id, zone)
            car["kw"] = round(charging_kw(heard), 2) if heard else None
        cars.append(car)
    return cars


def charging_summary(now: float, money: bool) -> dict[str, Any]:
    """Everything the charging app opens on (see "charging"), worked out from the rows."""
    rates = charging_rates()
    zone = charging_zone(rates)
    plan_id = charging_plan_id(rates)
    rows = with_db(lambda c: c.execute(f"SELECT {', '.join(CHARGE_COLUMNS)} FROM charge_sessions ORDER BY started DESC").fetchall())
    by_session = charging_hours_by_session()
    sessions = [charging_session_view(charging_row(r), by_session.get(r[0], []), rates, plan_id, zone) for r in rows]
    # What hour of the day the home charging goes in, lately.
    by_hour = [0.0] * 24
    recent = now - CHARGING_HOURS_DAYS * 86400
    # And how much of it would be at the peak's price: by the day, since a weekend's evening isn't peak.
    tou_id = next((p for p, plan in (rates.get("plans") or {}).items() if plan.get("kind") == "tou"), None)
    peak_kwh = 0.0
    home_ids = {s["id"] for s in sessions if s["place"] == "home"}
    for session_id, hours in by_session.items():
        if session_id in home_ids:
            for hour, kwh in hours:
                if hour >= recent:
                    by_hour[datetime.fromtimestamp(hour, zone or timezone.utc).hour] += kwh
                    if tou_id and charging_price(rates, tou_id, hour, zone)[1] == "peak":
                        peak_kwh += kwh
    history = state_get(CHARGING_HISTORY_KEY) or {}
    # The first day a car was heard: no charge at home before it was written down, whatever the receipts and cards go back to.
    first_day = with_db(lambda c: c.execute("SELECT MIN(day) FROM car_days").fetchone())[0]
    return {
        "provider": {"name": rates.get("provider"), "url": rates.get("url")} if rates else None,
        "plan": plan_id, "plans": charging_plans_view(rates, plan_id, zone, now),
        "tax_percent": rates.get("tax_percent"), "surcharge_per_kwh": rates.get("surcharge_per_kwh"), "customer_charge": rates.get("customer_charge"),
        "gas": rates.get("gas"), "now": charging_now(now, rates, plan_id, zone),
        "months": charging_months(sessions, by_session, rates, plan_id, zone, money),
        "by_hour": [round(kwh, 2) for kwh in by_hour], "recent_peak_share": round(peak_kwh / sum(by_hour), 3) if tou_id and sum(by_hour) > 0 else None, "sessions": sessions[:CHARGING_PAGE], "more_sessions": len(sessions) > CHARGING_PAGE,
        "first_session": min((s["started"] for s in sessions), default=None),
        "recording_from": first_day[:7] if first_day else None,
        "setup": {
            "rates": bool(rates), "power_stream": bool(state_get(CHARGING_SIGNALS_KEY)), "receipts": not history.get("scope_missing") and bool(history.get("kept") or (history.get("accounts") and not history.get("error"))),
            "receipts_at": history.get("at"),
        },
    }


class ChargingPlanChoice(BaseModel):
    plan: str


# ---------------------------------------------------------------- fitness
#
# The training log of each Frigate login, so that every device signed in with it has the same one
# (docs/fitness.md, "On the server"). The app keeps its own copy to work from, in the gym with no
# connection as much as at home, and sends what changed: this only holds rows and says what has
# changed since a device last asked. What a row means is the app's, so it is kept as it came, and
# the last device to send a row wins. A deleted row stays as a tombstone (`data` NULL), so a device
# that was away hears of it.
#
# `owner` is whose log it is, and is opaque to everything but `fitness_owner`: that is all that
# would change for profiles under one login.

# What a log is made of. Anything else is refused, rather than kept for ever on a typo.
FITNESS_KINDS = {"exercise", "set", "workout", "phase", "weigh_in", "heart_profile", "heart_summary", "run"}
# A device is told about this much of a log at a time, and goes on from where the answer stopped.
FITNESS_PAGE_BYTES = 1_000_000
# A recorded run is the largest row: its GPS positions, once a second, come to about 300 KB an hour.
FITNESS_MAX_ROW_BYTES = 8_000_000
FITNESS_MAX_CHANGES = 5_000


def fitness_owner(request: Request) -> str:
    """Whose log a request is about: the Frigate login it is signed in with, or a 401. A session Frigate can't name gets no log at all, not a shared one."""
    owner = require_frigate_session(request)
    if owner == "?":
        raise HTTPException(status_code=502, detail="Frigate didn't say who is signed in")
    return owner


def fitness_changes(body: dict[str, Any]) -> list[tuple[str, str, str | None]]:
    """The changes a device sent, each as (kind, id, the row as text, or None for deleted). 400 for anything that can't be one."""
    changes = body.get("changes", [])
    if not isinstance(changes, list) or len(changes) > FITNESS_MAX_CHANGES:
        raise HTTPException(status_code=400, detail="Not a list of changes, or too long a one")
    read = []
    for change in changes:
        if not isinstance(change, dict) or change.get("kind") not in FITNESS_KINDS:
            raise HTTPException(status_code=400, detail="Not a kind of row")
        row_id, data = change.get("id"), change.get("data")
        if not isinstance(row_id, str) or not row_id or len(row_id) > 200:
            raise HTTPException(status_code=400, detail="Not a row's id")
        if data is not None and not isinstance(data, dict):
            raise HTTPException(status_code=400, detail="Not a row")
        text = None if data is None else json.dumps(data, sort_keys=True, separators=(",", ":"))
        if text is not None and len(text) > FITNESS_MAX_ROW_BYTES:
            raise HTTPException(status_code=413, detail="Too large a row")
        read.append((change["kind"], row_id, text))
    return read


def fitness_sync(owner: str, since: int, changes: list[tuple[str, str, str | None]], now: float, expected_owner: str | None = None) -> dict[str, Any]:
    """
    Saves `changes` to `owner`'s log and answers with what else has changed since `since`, oldest
    first and a page at a time. `rev` is where the device goes on from; `more` says it should, with
    no need to wait. All under the one lock, so nothing lands between the reading and the writing.

    `expected_owner` is whose log the device believes it is sending. If that isn't who is signed in
    nothing is saved and nothing is told: the answer's `owner` says who it is, and the device decides.

    `reset` says the device has heard of more of this log than there is, so this database isn't the
    one it was talking to (restored, or begun again). Nothing is saved: it is to send its log anew.
    """

    def work(c: sqlite3.Connection) -> dict[str, Any]:
        if expected_owner is not None and expected_owner != owner:
            return {"owner": owner, "reset": False, "rev": 0, "more": False, "rows": []}
        head = c.execute("SELECT COALESCE(MAX(rev), 0) FROM fitness_rows WHERE owner=?", (owner,)).fetchone()[0]
        if since > head:
            return {"owner": owner, "reset": True, "rev": 0, "more": False, "rows": []}
        # Read before this device's own changes are written, so they aren't handed back to it.
        rows: list[dict[str, Any]] = []
        size, reached, more = 0, since, False
        for kind, row_id, rev, data in c.execute("SELECT kind, id, rev, data FROM fitness_rows WHERE owner=? AND rev>? ORDER BY rev", (owner, since)):
            if rows and size + len(data or "") > FITNESS_PAGE_BYTES:
                more = True
                break
            rows.append({"kind": kind, "id": row_id, "data": json.loads(data) if data is not None else None})
            size += len(data or "")
            reached = rev
        for kind, row_id, data in changes:
            held = c.execute("SELECT data FROM fitness_rows WHERE owner=? AND kind=? AND id=?", (owner, kind, row_id)).fetchone()
            # The same row sent again (the answer to the last send never arrived) is no news to anyone, and neither is deleting what was never here.
            if (held is not None and held[0] == data) or (held is None and data is None):
                continue
            head += 1
            c.execute("INSERT OR REPLACE INTO fitness_rows (owner, kind, id, rev, data, updated) VALUES (?,?,?,?,?,?)", (owner, kind, row_id, head, data, now))
        c.commit()
        # Part-way through what there was to hear, this device's own changes will come back to it further on, which does no harm.
        return {"owner": owner, "reset": False, "rev": reached if more else head, "more": more, "rows": rows}

    return with_db(work)


@app.post("/fitness/sync")
def post_fitness_sync(body: dict[str, Any], request: Request) -> dict[str, Any]:
    """Saves what changed in this device's training log, and says what changed on the others (see "fitness")."""
    owner = fitness_owner(request)
    since = body.get("since", 0)
    if not isinstance(since, int) or isinstance(since, bool) or since < 0:
        raise HTTPException(status_code=400, detail="Not a place in the log")
    expected = body.get("owner")
    if expected is not None and not isinstance(expected, str):
        raise HTTPException(status_code=400, detail="Not a login")
    return fitness_sync(owner, since, fitness_changes(body), time.time(), expected)


class ChargingSessionPatch(BaseModel):
    # A price typed in, or null to take it away; a note likewise.
    cost: float | None = None
    note: str | None = None


def charging_caller(request: Request) -> bool:
    """A signed-in user or a 401; whether they're allowed what the cards and bills say."""
    profile = frigate_profile(request)
    if profile is None:
        raise HTTPException(status_code=401, detail="Frigate session cookie required")
    return finance_may_read(profile)


@app.get("/charging")
def get_charging(request: Request) -> dict[str, Any]:
    """The months, the rates, what's charging now and the latest charges (see "charging")."""
    return charging_summary(time.time(), charging_caller(request))


@app.get("/charging/sessions")
def get_charging_sessions(request: Request, before: float | None = None, car: str | None = None, place: str | None = None) -> dict[str, Any]:
    """Older charges, a page at a time."""
    charging_caller(request)
    sessions = charging_sessions(before, car, place)
    return {"sessions": sessions, "more_sessions": len(sessions) == CHARGING_PAGE}


@app.get("/charging/sessions/{session_id}")
def get_charging_session(session_id: int, request: Request) -> dict[str, Any]:
    """One charge hour by hour."""
    charging_caller(request)
    detail = charging_session_detail(session_id)
    if detail is None:
        raise HTTPException(status_code=404, detail="no such charge")
    return detail


@app.post("/charging/sessions/{session_id}")
def set_charging_session(session_id: int, body: ChargingSessionPatch, request: Request) -> dict[str, Any]:
    """What someone paid for a charge the relay can't price, and a note on it."""
    charging_caller(request)
    if body.cost is not None and body.cost < 0:
        raise HTTPException(status_code=400, detail="a cost can't be negative")
    note = (body.note or "").strip()[:500] or None
    with _charging_lock:
        with_db(lambda c: (c.execute("UPDATE charge_sessions SET typed_cost=?, note=?, updated=? WHERE id=?", (body.cost, note, time.time(), session_id)), c.commit()))
        # A charge in progress is written back whole from what's held of it.
        for session in _charging_open.values():
            if session["id"] == session_id:
                session.update(typed_cost=body.cost, note=note)
    detail = charging_session_detail(session_id)
    if detail is None:
        raise HTTPException(status_code=404, detail="no such charge")
    return detail


@app.post("/charging/plan")
def set_charging_plan(body: ChargingPlanChoice, request: Request) -> dict[str, Any]:
    """The electricity plan the household is on. Every charge at home is priced by it, past ones included."""
    money = charging_caller(request)
    if body.plan not in (charging_rates().get("plans") or {}):
        raise HTTPException(status_code=400, detail="no such plan")
    state_set(CHARGING_PLAN_KEY, body.plan)
    return charging_summary(time.time(), money)


# ---------------------------------------------------------------- parking

# The Teslas usually sleep in the driveway, but now and then one is left at the curb, and each side
# of each street around the house has its own hours a car mustn't be there: street sweeping one
# morning a week or a month, two-hour parking on weekdays. Tesla (see "Tesla") puts a car to within
# a few metres and says which way it faces, which is enough to tell the street and the side: a car
# at a two-way curb faces the way that side's traffic goes, so its heading settles the side even
# when the fix lands on the middle of the road. A car facing across the street has nosed into a
# driveway, not parked at the curb. The relay reads that side's signs and tells the car's drivers
# before it must move (docs/parking.md).
#
# The signs are written down on the box in PARKING_FILE, not in the repository (which is public:
# the streets give the house away), and read again whenever the file changes:
#
#   {"streets": [
#      {"street": "Elm St", "sides": {"north": [rule, ...], "south": [rule, ...]}},
#      {"street": "Oak St", "only": {"side": "north", "of": "Elm St"}, "sides": {"home": [...], "across": [...]}}],
#    "permits": ["B"], "drivers": {"andrews_tesla": ["andrew"]}}
#
# A street is looked up by name in OpenStreetMap within PARKING_RADIUS_METRES of home, or given as
# its own `line` ([[lat, lng], ...] down its middle); `only` keeps the part of it on one side of
# another street, and `within_m` the part within that many metres of home (the blocks whose signs
# were read). A side is a compass word, "home" (the side of the street the house is on) and
# "across", or "both". A rule is
#   {"kind": "no_parking", "days": ["thu"], "weeks": [1, 3], "start": "08:00", "end": "11:00", "note": "Street sweeping"}
#   {"kind": "limit", "minutes": 120, "days": ["mon", "tue", "wed", "thu", "fri"], "start": "08:00", "end": "18:00", "permit": "B"}
# holding on `days` (every day when left out), on the `weeks`' of those weekdays in the month (1 to
# 5, or -1 for the last), in `months` (1 to 12), from `start` to `end` (the whole day when left out;
# an end before the start runs past midnight), on the household's clock (`tz`, or the phones'). A
# `permit` the household holds (`permits`) lifts the rule. `drivers` names, by the Frigate user
# signed in on the phone, whose phones hear about each car; every phone does otherwise.
#
# What a phone hears, as one notification per car that each push replaces: a deadline before noon
# at 8 PM the evening before, any other once it is a day away, then PARKING_REMIND_SECONDS before
# it, and once it has come with the car still there. Before telling anyone to go out to the car,
# Tesla is asked whether it is still there (asleep, it is). A phone in its quiet hours hears the
# latest once they're over, if the car is still there. When the car moves, the notification is
# replaced, quietly, by one saying so.
PARKING_FILE = os.environ.get("PARKING_FILE", "/data/parking.json")
OVERPASS_URL = os.environ.get("OVERPASS_URL", "https://overpass-api.de/api/interpreter")
PARKING_KEY = "parking"
PARKING_STREETS_KEY = "parking_streets"
PARKING_EVERY_SECONDS = 60.0
# Streets are looked for this far around home, and their shape kept this long (an hour after a failed look).
PARKING_RADIUS_METRES = 600
PARKING_STREETS_SECONDS = 7 * 86400.0
PARKING_STREETS_RETRY_SECONDS = 3600.0
# Curb to curb where the file doesn't say: two lanes with parking on both sides.
PARKING_WIDTH_METRES = 12.0
# How far past the curb a fix may land and still be a car at it, and past a stretch's end.
PARKING_GPS_SLACK_METRES = 4.0
PARKING_END_SLACK_METRES = 5.0
# A fix this far off the middle of the road says the side by itself (where the heading can't).
PARKING_SIDE_METRES = 1.5
# The cosine of the angle between the car and the street: below it, the car faces across the street.
PARKING_ALONG_COS = 0.7
# A fix this close to the last is the car still where it was.
PARKING_SAME_SPOT_METRES = 15.0
PARKING_NOTICE_SECONDS = 86400.0
PARKING_REMIND_SECONDS = float(os.environ.get("PARKING_REMIND_MINUTES", "30")) * 60
PARKING_EVENING_HOUR = 20
PARKING_MORNING_HOUR = 12
# An answer older than this is asked again before a phone is told to go out to the car.
PARKING_VERIFY_SECONDS = 300.0
PARKING_LOOKAHEAD_DAYS = 40
PARKING_LOOKBACK_DAYS = 7
# How far each phone has been told about a deadline: due soon, the evening before, shortly before, now.
PARKING_SOON, PARKING_EVENING, PARKING_REMIND, PARKING_NOW = 1, 2, 3, 4
COMPASS = {"north": 0.0, "east": 90.0, "south": 180.0, "west": 270.0}
DAY_WORDS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
STREET_ABBREVIATIONS = {
    "st": "Street", "ave": "Avenue", "av": "Avenue", "blvd": "Boulevard", "rd": "Road", "dr": "Drive", "ln": "Lane",
    "ct": "Court", "pl": "Place", "pkwy": "Parkway", "ter": "Terrace", "hwy": "Highway", "cir": "Circle",
}
_parking_file: dict[str, Any] = {"mtime": None, "config": {}}


def parking_config() -> dict[str, Any]:
    """PARKING_FILE as last read, read again when it changes; {} without one. A file that doesn't parse keeps the last that did."""
    try:
        mtime = os.path.getmtime(PARKING_FILE)
    except OSError:
        return {}
    if mtime != _parking_file["mtime"]:
        _parking_file["mtime"] = mtime
        try:
            with open(PARKING_FILE, encoding="utf-8") as f:
                config = json.load(f)
            if not isinstance(config, dict):
                raise ValueError("not a JSON object")
            _parking_file["config"] = config
            log.info("parking: read %d streets from %s", len(config.get("streets") or []), PARKING_FILE)
        except (OSError, ValueError) as e:
            log.warning("parking: %s doesn't read, keeping what it said before: %s", PARKING_FILE, e)
    return _parking_file["config"]


def parking_zone(config: dict[str, Any]) -> Any:
    """The clock the signs are read on: the file's `tz`, else the phones', else UTC."""
    try:
        return ZoneInfo(config["tz"]) if config.get("tz") else household_zone() or timezone.utc
    except Exception:
        return household_zone() or timezone.utc


def local_metres(origin: tuple[float, float], point: tuple[float, float]) -> tuple[float, float]:
    """`point` as metres east and north of `origin`, both (lat, lng): flat, which a few streets are."""
    return (
        math.radians(point[1] - origin[1]) * math.cos(math.radians(origin[0])) * 6_371_000,
        math.radians(point[0] - origin[0]) * 6_371_000,
    )


def nearest_segment(point: tuple[float, float], line: list[list[float]]) -> dict[str, float] | None:
    """
    Where `point` stands against the nearest segment of `line`: its `offset` from it in metres
    (positive to the left, going the way the line runs), how far `beyond` either end, and the
    segment's compass `bearing`. None for a line with no length.
    """
    best = None
    for a, b in zip(line, line[1:]):
        ax, ay = local_metres(point, (a[0], a[1]))
        bx, by = local_metres(point, (b[0], b[1]))
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        if length < 0.5:
            continue
        along = (-ax * dx - ay * dy) / length
        offset = (dx * -ay - dy * -ax) / length
        beyond = max(0.0, -along, along - length)
        fit = {"offset": offset, "beyond": beyond, "distance": math.hypot(offset, beyond), "bearing": math.degrees(math.atan2(dx, dy)) % 360}
        if best is None or fit["distance"] < best["distance"]:
            best = fit
    return best


def side_of(word: str, bearing: float) -> str:
    """Which of a street's sides, "left" or "right" going `bearing`, the compass `word` names."""
    return "left" if math.cos(math.radians(COMPASS[word] - (bearing - 90))) > 0 else "right"


def compass_word(bearing: float) -> str:
    return min(COMPASS, key=lambda word: abs((bearing - COMPASS[word] + 180) % 360 - 180))


def osm_street_name(street: str) -> str:
    """ "Central Ave" -> "Central Avenue", as OpenStreetMap names it."""
    words = street.replace(".", "").split()
    if words and words[-1].lower() in STREET_ABBREVIATIONS:
        words[-1] = STREET_ABBREVIATIONS[words[-1].lower()]
    return " ".join(words)


def osm_ways(name: str, lat: float, lng: float) -> list[dict[str, Any]]:
    """Every way for cars called `name` within PARKING_RADIUS_METRES of (lat, lng), as {"line", "one_way"}."""
    quoted = name.replace("\\", "\\\\").replace('"', '\\"')
    query = f'[out:json][timeout:60];way["highway"]["name"="{quoted}"](around:{PARKING_RADIUS_METRES},{lat},{lng});out geom;'
    # The public server queues a query before it runs it: from the box, 34-42 s for one street
    # (2026-10-27). Asked once a week, in the background, so waiting costs nothing.
    r = requests.post(OVERPASS_URL, data={"data": query}, headers={"User-Agent": "HomeSafe relay"}, timeout=90)
    r.raise_for_status()
    ways = []
    for element in r.json().get("elements") or []:
        tags = element.get("tags") or {}
        line = [[round(float(p["lat"]), 7), round(float(p["lon"]), 7)] for p in element.get("geometry") or []]
        if len(line) >= 2 and tags.get("highway") not in ("footway", "cycleway", "path", "steps", "pedestrian", "service"):
            ways.append({"line": line, "one_way": tags.get("oneway") in ("yes", "1", "-1")})
    return ways


def street_lines(street: str, config: dict[str, Any], home: dict[str, Any] | None, now: float) -> list[tuple[list[list[float]], bool]]:
    """
    A street's lines as (line, one way): its `line` in the file where an entry gives one, else
    OpenStreetMap's around home, looked up once a PARKING_STREETS_SECONDS (what was found last is
    kept through a failed look).
    """
    given = [(e["line"], bool(e.get("one_way"))) for e in config.get("streets") or [] if e.get("street") == street and e.get("line")]
    if given or not home:
        return given
    name = osm_street_name(street)
    key = f"{name}@{float(home['lat']):.4f},{float(home['lng']):.4f}"
    kept = state_get(PARKING_STREETS_KEY) or {}
    found = kept.get(key) or {}
    if now - float(found.get("at") or 0) < (PARKING_STREETS_SECONDS if found.get("ways") else PARKING_STREETS_RETRY_SECONDS):
        return [(w["line"], w["one_way"]) for w in found["ways"]]
    try:
        found = {"at": now, "ways": osm_ways(name, float(home["lat"]), float(home["lng"]))}
        log.info("parking: %s is %d ways in OpenStreetMap", name, len(found["ways"]))
    except Exception as e:
        log.warning("parking: looking %s up in OpenStreetMap failed: %s", name, e)
        ways = found.get("ways") or []
        found = {"at": now - (PARKING_STREETS_SECONDS - PARKING_STREETS_RETRY_SECONDS if ways else 0), "ways": ways}
    kept[key] = found
    state_set(PARKING_STREETS_KEY, kept)
    return [(w["line"], w["one_way"]) for w in found["ways"]]


def clip_line(line: list[list[float]], keep: Callable[[tuple[float, float]], bool]) -> list[list[list[float]]]:
    """
    The runs of `line` whose points `keep` keeps, to within PARKING_END_SLACK_METRES: the line is
    cut into pieces that short first, so a cut needn't fall on one of its points.
    """
    pieces = [line[0]] if line else []
    for a, b in zip(line, line[1:]):
        x, y = local_metres((a[0], a[1]), (b[0], b[1]))
        n = max(1, math.ceil(math.hypot(x, y) / PARKING_END_SLACK_METRES))
        pieces += [[a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n] for i in range(1, n + 1)]
    runs: list[list[list[float]]] = []
    run: list[list[float]] = []
    for a, b in zip(pieces, pieces[1:]):
        if keep(((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)):
            run = run or [a]
            run.append(b)
        elif run:
            runs.append(run)
            run = []
    if run:
        runs.append(run)
    return runs


def beside(word: str, others: list[list[list[float]]]) -> Callable[[tuple[float, float]], bool]:
    """Whether a point lies on the `word` (compass) side of the nearest of `others`."""
    def keep(point: tuple[float, float]) -> bool:
        fits = [fit for fit in (nearest_segment(point, other) for other in others) if fit]
        fit = min(fits, key=lambda f: f["distance"]) if fits else None
        return bool(fit) and ("left" if fit["offset"] > 0 else "right") == side_of(word, fit["bearing"])
    return keep


def parking_stretches(config: dict[str, Any], home: dict[str, Any] | None, now: float) -> list[dict[str, Any]]:
    """
    Each street of the file as stretches of line to match a car against: {"street", "line",
    "width", "one_way", "sides", "home_side"}, `home_side` being which of its sides ("left" or
    "right", going the way the line runs) the house is on.
    """
    stretches = []
    for entry in config.get("streets") or []:
        street = str(entry.get("street") or "")
        lines = [(entry["line"], bool(entry.get("one_way")))] if entry.get("line") else street_lines(street, config, home, now)
        only = entry.get("only") or {}
        if only.get("side") in COMPASS and only.get("of"):
            keep = beside(only["side"], [line for line, _ in street_lines(str(only["of"]), config, home, now)])
            lines = [(part, one_way) for line, one_way in lines for part in clip_line(line, keep)]
        if entry.get("within_m") and home:
            centre, reach = (float(home["lat"]), float(home["lng"])), float(entry["within_m"])
            lines = [(part, one_way) for line, one_way in lines for part in clip_line(line, lambda p: haversine_metres(p, centre) <= reach)]
        if not lines:
            log.warning("parking: no line for %s", street)
        for line, one_way in lines:
            fit = nearest_segment((float(home["lat"]), float(home["lng"])), line) if home else None
            stretches.append({
                "street": street, "line": line, "width": float(entry.get("width_m") or PARKING_WIDTH_METRES),
                "one_way": bool(entry.get("one_way", one_way)), "sides": entry.get("sides") or {},
                "home_side": ("left" if fit["offset"] > 0 else "right") if fit else None,
            })
    return stretches


def side_rules(stretch: dict[str, Any], bearing: float, side: str | None) -> list[dict[str, Any]]:
    """The rules of a stretch's `side` ("left" or "right" going `bearing`); of every side when it's unsure (None)."""
    rules = []
    for word, listed in stretch["sides"].items():
        if word in ("home", "across"):
            hit = stretch["home_side"] is not None and (side is None or (side == stretch["home_side"]) == (word == "home"))
        else:
            hit = word == "both" or (word in COMPASS and (side is None or side_of(word, bearing) == side))
        if hit:
            rules += [rule for rule in listed or [] if isinstance(rule, dict)]
    return rules


def parking_match(fix: dict[str, Any], stretches: list[dict[str, Any]]) -> dict[str, Any] | None:
    """
    Which street and side a parked car is on, as {"street", "side" (a compass word, or None when
    neither the heading nor the fix can tell), "rules", "how"}; None when it is at none of the
    file's curbs: somewhere else, or facing across the street (in a driveway).
    """
    point = (float(fix["lat"]), float(fix["lng"]))
    heading = fix.get("heading")
    best = None
    for stretch in stretches:
        fit = nearest_segment(point, stretch["line"])
        if fit is None or fit["beyond"] > PARKING_END_SLACK_METRES or abs(fit["offset"]) > stretch["width"] / 2 + PARKING_GPS_SLACK_METRES:
            continue
        along = math.cos(math.radians(float(heading) - fit["bearing"])) if heading is not None else None
        if along is not None and abs(along) < PARKING_ALONG_COS:
            continue  # facing across this street: in a driveway off it, or parked along the cross street
        if best is None or abs(fit["offset"]) < abs(best[1]["offset"]):
            best = (stretch, fit, along)
    if best is None:
        return None
    stretch, fit, along = best
    by_position = ("left" if fit["offset"] > 0 else "right") if abs(fit["offset"]) >= PARKING_SIDE_METRES else None
    # Traffic keeps right, and a car at the curb faces the way its side's traffic goes.
    by_heading = ("right" if along > 0 else "left") if along is not None and not stretch["one_way"] else None
    side = by_heading or by_position
    if by_heading and by_position and by_heading != by_position:
        log.info("parking: on %s the fix has the car %.1f m %s of the middle, but it faces the %s side's way; going by the heading",
                 stretch["street"], abs(fit["offset"]), by_position, by_heading)
    return {
        "street": stretch["street"],
        "side": compass_word(fit["bearing"] + (-90 if side == "left" else 90)) if side else None,
        "rules": side_rules(stretch, fit["bearing"], side),
        "how": "heading" if by_heading else "position" if by_position else None,
    }


def clock_minutes(text: Any, default: int) -> int:
    """ "08:00" -> 480, "24:00" -> 1440; `default` when there is none."""
    if not text:
        return default
    hours, _, minutes = str(text).partition(":")
    return int(hours) * 60 + int(minutes or 0)


def local_epoch(day: Any, minutes: int, zone: Any) -> float:
    """`minutes` after midnight starting `day`, on `zone`'s clock (past 1440, the next day)."""
    day = day + timedelta(days=minutes // 1440)
    return datetime(day.year, day.month, day.day, minutes % 1440 // 60, minutes % 60, tzinfo=zone).timestamp()


def rule_windows(rule: dict[str, Any], zone: Any, after: float, until: float):
    """
    A rule's windows, as (start, end) in epoch seconds, that end after `after` and start before
    `until`, in order. Days whose windows meet are one window: a time limit that holds all day
    doesn't start counting again at midnight.
    """
    days = {DAY_WORDS.index(str(d).lower()[:3]) for d in rule.get("days") or [] if str(d).lower()[:3] in DAY_WORDS}
    weeks, months = set(rule.get("weeks") or []), set(rule.get("months") or [])
    start, end = clock_minutes(rule.get("start"), 0), clock_minutes(rule.get("end"), 1440)
    if end <= start:
        end += 1440
    # From further back than the windows asked for, so one that began days ago is found whole.
    day = datetime.fromtimestamp(after, zone).date() - timedelta(days=PARKING_LOOKBACK_DAYS)
    last = datetime.fromtimestamp(until, zone).date()
    held: tuple[float, float] | None = None
    while day <= last:
        nth = (day.day - 1) // 7 + 1
        final = (day + timedelta(days=7)).month != day.month
        if (not months or day.month in months) and (not days or day.weekday() in days) and (not weeks or nth in weeks or (-1 in weeks and final)):
            s, e = local_epoch(day, start, zone), local_epoch(day, end, zone)
            if held and s <= held[1]:
                held = (held[0], max(held[1], e))
            else:
                if held and held[1] > after and held[0] < until:
                    yield held
                held = (s, e)
        day += timedelta(days=1)
    if held and held[1] > after and held[0] < until:
        yield held


def rule_deadline(rule: dict[str, Any], since: float, now: float, zone: Any) -> dict[str, Any] | None:
    """
    When a car parked since `since` must be gone by `rule`, as {"at", "from", "until", "rule"}:
    the next window's start for no parking (past, while one is on), and for a time limit the
    first window the car would outstay, counted from when it parked or the window opened.
    """
    minutes = float(rule.get("minutes") or 0) if rule.get("kind") == "limit" else None
    if minutes is not None and minutes <= 0:
        return None
    for start, end in rule_windows(rule, zone, now, now + PARKING_LOOKAHEAD_DAYS * 86400):
        at = start if minutes is None else max(since, start) + minutes * 60
        if at < end:
            return {"at": at, "from": start, "until": end, "rule": rule}
    return None


def parking_deadline(rules: list[dict[str, Any]], since: float, now: float, zone: Any, permits: set[str]) -> dict[str, Any] | None:
    """The soonest of the rules' deadlines, leaving out the rules a permit the household holds lifts."""
    found = [d for d in (rule_deadline(r, since, now, zone) for r in rules if not (r.get("permit") and r["permit"] in permits)) if d]
    return min(found, key=lambda d: d["at"]) if found else None


def parking_stage(at: float, now: float, zone: Any) -> int:
    """How far the phones are owed about a deadline at `at` (see PARKING_SOON), 0 for nothing yet."""
    if now >= at:
        return PARKING_NOW
    if now >= at - PARKING_REMIND_SECONDS:
        return PARKING_REMIND
    local = datetime.fromtimestamp(at, zone)
    if local.hour < PARKING_MORNING_HOUR:
        eve = local.date() - timedelta(days=1)
        return PARKING_EVENING if now >= local_epoch(eve, PARKING_EVENING_HOUR * 60, zone) else 0
    return PARKING_SOON if at - now <= PARKING_NOTICE_SECONDS else 0


def parking_read(car: dict[str, Any], answer: tuple[float, dict[str, Any] | None], stretches: list[dict[str, Any]],
                 name: str | None = None) -> None:
    """
    Takes in an answer from Tesla newer than the last: where the car is parked (`spot`, the fix
    and since when), and when it was last seen anywhere else (`elsewhere`). Asleep, it is where it
    was. A car parked between two answers is taken to have parked just after the earlier one, so a
    time limit is never counted from later than it began. `name` says which car, for how far
    apart its answers can be.
    """
    asked, where = answer
    if where is None or asked <= float(car.get("asked") or 0) or (not where.get("asleep") and asked - float(where["at"]) > TESLA_STALE_SECONDS):
        return
    before, car["asked"] = float(car.get("asked") or 0), asked
    parking_seen(car, answer)
    if where.get("asleep"):
        if not car.get("spot"):
            car["elsewhere"] = asked
        return
    fix = {k: where.get(k) for k in ("lat", "lng", "heading", "at")}
    moving = where.get("shift") in ("D", "R", "N") or float(where.get("speed") or 0) > 0
    match = None if moving else parking_match(fix, stretches)
    spot = car.get("spot")
    if (spot and match and (match["street"], match["side"]) == (spot.get("street"), spot.get("side"))
            and haversine_metres((spot["lat"], spot["lng"]), (fix["lat"], fix["lng"])) <= PARKING_SAME_SPOT_METRES):
        return
    if spot and car.get("heard"):
        # The phones told about the old spot hear that the car left it (see `parking_tell`).
        car["moved"] = spot.get("street") or ""
        car["moved_to"] = sorted(set(car.get("moved_to") or []) | set(car["heard"]))
        car["heard"] = []
    if match is None:
        car["spot"], car["elsewhere"] = None, float(fix["at"])
        return
    # From another spot at the curb, it was there until the last answer; else it was last seen elsewhere.
    # Answers come every TESLA_CHECK_SECONDS, or every TESLA_ASLEEP_CHECK_SECONDS while it slept
    # (TESLA_STREAM_ASLEEP_CHECK_SECONDS for a car that streams). A car whose stream was heard
    # shifting into P parked then (see "Tesla telemetry").
    asleep_gap = TESLA_STREAM_ASLEEP_CHECK_SECONDS if name and tesla_streaming(name, asked) else TESLA_ASLEEP_CHECK_SECONDS
    gap = max(TESLA_CHECK_SECONDS, TESLA_ASLEEP_CHECK_SECONDS, asleep_gap)
    since = max(before if spot else float(car.get("elsewhere") or 0), float(fix["at"]) - gap)
    if where.get("parked_at"):
        since = float(where["parked_at"])
    car["spot"] = {**fix, "since": since, "street": match["street"], "side": match["side"]}
    log.info("parking: parked on the %s side of %s since %s (by its %s)", match["side"] or "unsure", match["street"],
             datetime.fromtimestamp(since, timezone.utc).isoformat(timespec="minutes"), match["how"] or "nothing")


def parking_seen(car: dict[str, Any], answer: tuple[float, dict[str, Any] | None]) -> None:
    """
    Keeps what an answer from Tesla says about where the car is, wherever that is, for the phones'
    map of the cars: whether it is asleep, and its fix as `last` (with whether it was moving), when
    that is fresh and newer than the one kept. Watched streets or not.
    """
    asked, where = answer
    if where is None:
        return
    if where.get("asleep"):
        car["asleep"] = True
        return
    if asked - float(where["at"]) > TESLA_STALE_SECONDS or float(where["at"]) < float((car.get("last") or {}).get("at") or 0):
        return
    car["asleep"] = False
    moving = where.get("shift") in ("D", "R", "N") or float(where.get("speed") or 0) > 0
    car["last"] = {**{k: where.get(k) for k in ("lat", "lng", "heading", "at")}, "moving": moving}


def parking_phones(name: str, config: dict[str, Any], now: float, quiet_too: bool = False) -> list[tuple[str, str]]:
    """
    The phones to tell about `name` now, as (device id, token): its `drivers`' (by the Frigate
    user last signed in on the phone), or every phone when none of theirs is registered; those in
    their quiet hours only when `quiet_too`. A phone that wants only Away alerts still hears:
    that choice is about the cameras.
    """
    rows = with_db(lambda c: c.execute(
        "SELECT device_id, token, user, quiet_start, quiet_end, tz, utc_offset FROM devices WHERE token IS NOT NULL"
    ).fetchall())
    drivers = set((config.get("drivers") or {}).get(name) or [])
    phones = []
    for device_id, token, _, quiet_start, quiet_end, tz, utc_offset in [r for r in rows if r[2] in drivers] or rows:
        minute = local_minute(now, tz, utc_offset)
        if quiet_too or minute is None or not in_quiet_hours(quiet_start, quiet_end, minute):
            phones.append((device_id, token))
    return phones


def parking_plan(car: dict[str, Any], stretches: list[dict[str, Any]], config: dict[str, Any], zone: Any, now: float) -> dict[str, Any] | None:
    """What the phones are owed about where the car is parked, as {"spot", "match", "deadline", "stage", "key"}; None for nothing."""
    spot = car.get("spot")
    match = parking_match(spot, stretches) if spot else None
    if match is None:
        return None
    deadline = parking_deadline(match["rules"], float(spot["since"]), now, zone, set(config.get("permits") or []))
    stage = parking_stage(deadline["at"], now, zone) if deadline else 0
    if not stage:
        return None
    # The spot is part of the key: parked again further along the same curb, it is told afresh.
    key = f"{match['street']}|{match['side']}|{int(float(spot['since']))}|{int(deadline['at'])}"
    return {"spot": spot, "match": match, "deadline": deadline, "stage": stage, "key": key}


def parking_text(name: str, plan: dict[str, Any], now: float, zone: Any) -> tuple[str, str, dict[str, str]]:
    """ "Move Andrew's Tesla by Thu 8:00 AM", "North side of Elm St · Street sweeping", and the push's data for the phone to word it."""
    match, deadline = plan["match"], plan["deadline"]
    rule, at, car = deadline["rule"], deadline["at"], display_name(name)
    when = clock_text(at, zone)
    if datetime.fromtimestamp(at, zone).date() != datetime.fromtimestamp(now, zone).date():
        when = f"{datetime.fromtimestamp(at, zone).strftime('%a')} {when}"
    title = f"Move {car} now" if plan["stage"] == PARKING_NOW else f"Move {car} by {when}"
    place = f"{match['side'].capitalize()} side of {match['street']}" if match["side"] else match["street"]
    minutes = int(float(rule.get("minutes") or 0)) if rule.get("kind") == "limit" else 0
    if rule.get("note"):
        reason = str(rule["note"])
    elif minutes:
        reason = f"{minutes // 60}-hour parking" if minutes % 60 == 0 else f"{minutes}-minute parking"
    else:
        reason = f"No parking {clock_text(deadline['from'], zone)}–{clock_text(deadline['until'], zone)}"
    data = {
        "parking": "1", "notif_id": f"parking-{name}", "parking_event": "now" if plan["stage"] == PARKING_NOW else "due",
        "parking_car": name, "parking_car_name": car, "parking_by": str(int(at)),
        "parking_street": match["street"], "parking_side": match["side"] or "",
        "parking_rule": "limit" if minutes else "no_parking", "parking_minutes": str(minutes) if minutes else "",
        "parking_from": str(int(deadline["from"])), "parking_until": str(int(deadline["until"])), "parking_note": str(rule.get("note") or ""),
        # Where the car is, for the phone's map of the spot (only the household's own phones hear this).
        **parking_where(plan.get("spot")),
    }
    return title, f"{place} · {reason}", data


def parking_where(spot: dict[str, Any] | None) -> dict[str, str]:
    """The push's `parking_lat`, `parking_lng` and `parking_heading` for a spot: to about a metre, and empty for what isn't known."""
    spot = spot or {}

    def number(key: str, places: int) -> str:
        return f"{float(spot[key]):.{places}f}" if spot.get(key) is not None else ""

    return {"parking_lat": number("lat", 5), "parking_lng": number("lng", 5), "parking_heading": number("heading", 0)}


def parking_tell(name: str, car: dict[str, Any], config: dict[str, Any], stretches: list[dict[str, Any]], zone: Any, now: float,
                 push: Callable[..., str]) -> list[dict[str, Any]]:
    """Pushes what `name`'s phones are owed about where it is parked; answers the pushes, as {"car", "event", "title", "body", "to"}."""
    pushed = []
    plan = parking_plan(car, stretches, config, zone, now)
    told = car.get("told") or {}
    stages = (told.get("stages") or {}) if plan and told.get("key") == plan["key"] else {}
    owed = [p for p in parking_phones(name, config, now) if plan and stages.get(p[0], 0) < plan["stage"]]
    if owed and now - float(car.get("asked") or 0) > PARKING_VERIFY_SECONDS:
        # About to tell someone the car must move: make sure it is still there.
        parking_read(car, tesla_ask(name, now, "parking: still there?"), stretches, name)
        plan = parking_plan(car, stretches, config, zone, now)
        stages = stages if plan and told.get("key") == plan["key"] else {}
        owed = [p for p in owed if plan and stages.get(p[0], 0) < plan["stage"]]

    # A phone still showing a warning about a spot the car has left hears that it moved, until
    # the push gets through (or a warning about where it is now replaces it, below).
    if car.get("moved_to"):
        moved, car_name = car.get("moved") or "", display_name(name)
        data = {"parking": "1", "notif_id": f"parking-{name}", "parking_event": "moved", "parking_car": name,
                "parking_car_name": car_name, "parking_street": moved, "silent": "1"}
        title, body = f"{car_name} has moved", f"No longer parked on {moved}" if moved else "No longer parked there"
        phones = [p for p in parking_phones(name, config, now, quiet_too=True) if p[0] in car["moved_to"] and p not in owed]
        sent = [device_id for device_id, token in phones if push(token, title, body, data) == "sent"]
        if sent:
            pushed.append({"car": name, "event": "moved", "title": title, "body": body, "to": sent})
        # A phone no longer registered is let go; one that failed is tried again next time.
        car["moved_to"] = [device_id for device_id, _ in phones if device_id not in sent]
    if not car.get("moved_to"):
        car.pop("moved", None)
        car.pop("moved_to", None)

    if not plan:
        car.pop("told", None)
        return pushed
    title, body, data = parking_text(name, plan, now, zone)
    sent = [device_id for device_id, token in owed if push(token, title, body, data) == "sent"]
    for device_id in sent:
        stages[device_id] = plan["stage"]
    car["told"] = {"key": plan["key"], "stages": stages}
    car["heard"] = sorted(set(car.get("heard") or []) | set(sent))
    if car.get("moved_to"):
        car["moved_to"] = [device_id for device_id in car["moved_to"] if device_id not in sent]
    if owed:
        pushed.append({"car": name, "event": data["parking_event"], "title": title, "body": body, "to": sent})
        log.info("parking: %s | %s -> %d of %d phones", title, body, len(sent), len(owed))
    return pushed


def parking_tick(now: float | None = None, push: Callable[..., str] | None = None) -> list[dict[str, Any]]:
    """
    Once a PARKING_EVERY_SECONDS: takes in what Tesla last said about each of TESLA_CARS (car
    presence asks every TESLA_CHECK_SECONDS; asked here only when it hasn't been), and pushes what
    is owed. With no watched streets it only keeps where each car was last seen, from what car
    presence already heard. Answers the pushes. `push` stands in for `deliver` in tests.
    """
    now = time.time() if now is None else now
    config = parking_config()
    if not tesla_on():
        return []
    saved = state_get(PARKING_KEY) or {}
    if not config.get("streets"):
        # Nothing to watch at a curb, so Tesla isn't asked for it; but where car presence last
        # heard each car is is still kept, for the phones' map of the cars.
        for name in TESLA_CARS:
            answer = _tesla_answers.get(name)
            if answer is not None:
                parking_seen(saved.setdefault(name, {}), answer)
        state_set(PARKING_KEY, saved)
        return []
    stretches = parking_stretches(config, state_get(HOME_KEY), now)
    zone = parking_zone(config)
    pushed = []
    for name in TESLA_CARS:
        car = saved.setdefault(name, {})
        answer = _tesla_answers.get(name)
        if answer is None or tesla_due(name, now, TESLA_CHECK_SECONDS + 5 * PARKING_EVERY_SECONDS):
            answer = tesla_ask(name, now, "parking")
        parking_read(car, answer, stretches, name)
        pushed += parking_tell(name, car, config, stretches, zone, now, push or deliver)
    state_set(PARKING_KEY, saved)
    return pushed


def parking_forever() -> None:
    while True:
        time.sleep(PARKING_EVERY_SECONDS)
        try:
            parking_tick()
        except Exception:
            log.exception("parking check failed")


def parking_status(now: float) -> dict[str, Any]:
    """
    Where each of TESLA_CARS is parked by the file's streets, and its next deadline there; and,
    wherever it is, where Tesla last saw it (`last`: the fix, whether it was moving, and whether
    that is home), whether the car is asleep now, and what its stream last said (`live`, see
    `tesla_live_status`).
    """
    config = parking_config()
    home = state_get(HOME_KEY)
    stretches = parking_stretches(config, home, now) if config.get("streets") else []
    zone = parking_zone(config)
    saved = state_get(PARKING_KEY) or {}
    presence = state_get(CAR_PRESENCE_KEY) or {}
    cars = []
    for name in TESLA_CARS:
        car = saved.get(name) or {}
        spot = car.get("spot")
        match = parking_match(spot, stretches) if spot else None
        deadline = parking_deadline(match["rules"], float(spot["since"]), now, zone, set(config.get("permits") or [])) if match else None
        # A relay from before `last` was kept knows only the spot at the curb.
        last = car.get("last") or (spot and {**{k: spot.get(k) for k in ("lat", "lng", "heading", "at")}, "moving": False})
        at_home = None
        if last and home:
            d = haversine_metres((float(last["lat"]), float(last["lng"])), (float(home["lat"]), float(home["lng"])))
            at_home = d <= float(home.get("radius_m") or 150) + TESLA_HOME_MARGIN_METRES
        # A car that drove home and fell asleep between two answers is still where Tesla last had it,
        # away, though the camera saw it arrive (Sarah's, 2026-10-29: four hours 8 km off while in
        # the driveway). Arrived after that fix, it is home, at the house: the driveway is all the
        # camera's car zones cover. A car Tesla found home stays at its own fix, the curb's included.
        # Only a change car presence saw counts: a starting state's `since` is when it started, not an arrival.
        seen = presence.get(name) or {}
        arrived = float(seen.get("since") or 0) if seen.get("here") and seen.get("known") else 0.0
        if home and not spot and arrived and not at_home and arrived > float((last or {}).get("at") or 0):
            last, at_home = {"lat": float(home["lat"]), "lng": float(home["lng"]), "heading": None, "at": arrived, "moving": False}, True
        cars.append({
            "car": name, "name": display_name(name), "asked": car.get("asked"),
            "street": match and match["street"], "side": match and match["side"], "how": match and match["how"],
            "lat": spot and spot.get("lat"), "lng": spot and spot.get("lng"), "heading": spot and spot.get("heading"),
            "since": spot and spot["since"], "move_by": deadline and deadline["at"],
            "rule": deadline and {k: v for k, v in deadline["rule"].items()},
            "window": deadline and {"from": deadline["from"], "until": deadline["until"]},
            "last": last and {**{k: last.get(k) for k in ("lat", "lng", "heading", "at", "moving")}, "home": at_home},
            "asleep": bool(car.get("asleep")),
            "live": tesla_live_status(name, now),
        })
    return {"streets": parking_signs(stretches, config, zone, now), "cars": cars}


def parking_signs(stretches: list[dict[str, Any]], config: dict[str, Any], zone: Any, now: float) -> list[dict[str, Any]]:
    """
    Each stretch of the file's streets with the signs along both its curbs, for the cars' map to
    draw: {"street", "points", "home_side", "line", "width_m", "sides"}. Each of `sides` is {"side", "offset",
    "rules"}: `offset` is which way of the line its curb is ("left" or "right", going the way the
    line runs), `side` the compass word for that, and each rule comes with when it next holds
    (`next`: {"from", "until"}, under way when `from` has passed; null for none within
    PARKING_LOOKAHEAD_DAYS) and whether a permit the household holds lifts it (`lifted`). A
    stretch is read end to end for its compass words, as near enough for the few blocks a file
    keeps; a curb with no signs isn't listed.
    """
    permits = set(config.get("permits") or [])
    signs = []
    for stretch in stretches:
        line = stretch["line"]
        east, north = local_metres((line[0][0], line[0][1]), (line[-1][0], line[-1][1]))
        bearing = math.degrees(math.atan2(east, north)) % 360
        sides = []
        for offset in ("left", "right"):
            rules = []
            for rule in side_rules(stretch, bearing, offset):
                window = next(rule_windows(rule, zone, now, now + PARKING_LOOKAHEAD_DAYS * 86400), None)
                rules.append({
                    **rule, "next": window and {"from": window[0], "until": window[1]},
                    "lifted": bool(rule.get("permit") and rule["permit"] in permits),
                })
            if rules:
                sides.append({"side": compass_word(bearing + (-90 if offset == "left" else 90)), "offset": offset, "rules": rules})
        signs.append({
            "street": stretch["street"], "points": len(line), "home_side": stretch["home_side"],
            "line": [[round(float(p[0]), 6), round(float(p[1]), 6)] for p in line], "width_m": stretch["width"], "sides": sides,
        })
    return signs


@app.get("/parking")
def get_parking(request: Request) -> dict[str, Any]:
    """Where each Tesla is parked and when it must move (see "parking")."""
    require_frigate_session(request)
    return parking_status(time.time())


# ---------------------------------------------------------------- vault
#
# The household's API keys (Anthropic's for the assistant, Google's for the maps), kept here so no
# phone has to, and readable only by the phones (docs/key-vault.md). Everything below is ciphertext
# the phones made; the relay never sees a key, a password, or anything that opens one.
#
# - Each Frigate user who has signed in on a phone is a *member*, with a P-256 key pair: `public`
#   as it is, `private` sealed with a key the phone derives from that user's Frigate password.
# - One *household key* opens the secrets. Each member gets their own copy, a *grant*, sealed to
#   their public key. A phone that holds the household key grants it to any member still without
#   one, so a new member is let in the next time someone already in opens the app.
# - A member whose password changes seals a new key pair, which loses their grant until someone
#   grants them again. When nobody holds the household key at all, a phone starts a new one, and
#   the secrets sealed under the old one are dropped: someone types them in again.

VAULT_KEY = "vault"
VAULT_LOCK = threading.Lock()
VAULT_SECRET_NAME = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
VAULT_BLOB_MAX = 8192


class VaultMember(BaseModel):
    public: str
    private: str
    # The public key this replaces, as the phone last saw it; None when it saw no key pair at all.
    replaces: str | None = None


class VaultGrant(BaseModel):
    household: str
    # The public key the household key was sealed to: the grant only counts while it is still theirs.
    recipient: str
    ephemeral: str
    box: str


class VaultSecrets(BaseModel):
    household: str
    secrets: dict[str, str | None]


def vault_state() -> dict[str, Any]:
    return state_get(VAULT_KEY) or {"household": None, "members": {}, "secrets": {}}


def vault_granted(state: dict[str, Any], member: dict[str, Any] | None) -> bool:
    """Whether `member` holds the current household key: a grant made for the key pair they have now."""
    grant = (member or {}).get("grant")
    return bool(grant and state.get("household") and grant.get("household") == state["household"] and grant.get("for") == member.get("public"))


def vault_view(state: dict[str, Any], user: str) -> dict[str, Any]:
    """What a phone signed in as `user` is shown: everyone's public key and whether they are in, its own sealed keys, and the sealed secrets."""
    members = state.get("members") or {}
    me = members.get(user)
    mine = None
    if me:
        grant = me.get("grant") if vault_granted(state, me) else None
        mine = {"public": me["public"], "private": me["private"], "grant": grant and {"ephemeral": grant["ephemeral"], "box": grant["box"]}}
    return {
        "user": user, "household": state.get("household"), "me": mine,
        "members": [{"user": name, "public": m["public"], "granted": vault_granted(state, m)} for name, m in sorted(members.items())],
        "secrets": (state.get("secrets") or {}) if state.get("household") else {},
    }


def vault_blob(*values: str) -> None:
    for value in values:
        if not value or len(value) > VAULT_BLOB_MAX:
            raise HTTPException(status_code=400, detail="Sealed value missing or too long")


def vault_change(user: str, change: Callable[[dict[str, Any]], None]) -> dict[str, Any]:
    with VAULT_LOCK:
        state = vault_state()
        change(state)
        state_set(VAULT_KEY, state)
        return vault_view(state, user)


def vault_require_granted(state: dict[str, Any], user: str, household: str) -> None:
    if household != state.get("household") or not vault_granted(state, (state.get("members") or {}).get(user)):
        raise HTTPException(status_code=409, detail="This login doesn't hold the household key")


@app.get("/vault")
def get_vault(request: Request) -> dict[str, Any]:
    """The vault as the signed-in user's phones see it."""
    user = require_frigate_session(request)
    with VAULT_LOCK:
        return vault_view(vault_state(), user)


@app.put("/vault/me")
def put_vault_member(body: VaultMember, request: Request) -> dict[str, Any]:
    """Sets the signed-in user's key pair (a first sign-in, or a new password). Any grant made for an older pair no longer counts."""
    user = require_frigate_session(request)
    vault_blob(body.public, body.private)

    def change(state: dict[str, Any]) -> None:
        members = state.setdefault("members", {})
        current = (members.get(user) or {}).get("public")
        # Two phones signing in as one user at once: the second must look again and use the pair
        # the first set, not replace it (which would take the first's grant away with it).
        if current != body.replaces:
            raise HTTPException(status_code=409, detail="The key pair changed; look again")
        members[user] = {"public": body.public, "private": body.private, "grant": None}

    log.info("vault: %s set a key pair", user)
    return vault_change(user, change)


@app.put("/vault/grants/{member}")
def put_vault_grant(member: str, body: VaultGrant, request: Request) -> dict[str, Any]:
    """Gives `member` the household key, sealed to their public key, from a phone that holds it."""
    user = require_frigate_session(request)
    vault_blob(body.ephemeral, body.box)

    def change(state: dict[str, Any]) -> None:
        vault_require_granted(state, user, body.household)
        target = (state.get("members") or {}).get(member)
        if not target:
            raise HTTPException(status_code=404, detail="No such member")
        if target["public"] != body.recipient:
            raise HTTPException(status_code=409, detail="Their key pair changed; look again")
        target["grant"] = {"household": body.household, "ephemeral": body.ephemeral, "box": body.box, "for": target["public"]}

    view = vault_change(user, change)
    log.info("vault: %s let %s in", user, member)
    return view


@app.post("/vault/household")
def start_vault_household(body: VaultGrant, request: Request) -> dict[str, Any]:
    """Starts a new household key, granted to the signed-in user. Only while nobody holds the current one: the old secrets go with it."""
    user = require_frigate_session(request)
    vault_blob(body.household, body.ephemeral, body.box)

    def change(state: dict[str, Any]) -> None:
        members = state.get("members") or {}
        if user not in members:
            raise HTTPException(status_code=409, detail="Set a key pair first")
        if any(vault_granted(state, m) for m in members.values()):
            raise HTTPException(status_code=409, detail="Someone holds the household key: ask them to open the app")
        if members[user]["public"] != body.recipient:
            raise HTTPException(status_code=409, detail="Your key pair changed; look again")
        state["household"] = body.household
        state["secrets"] = {}
        members[user]["grant"] = {"household": body.household, "ephemeral": body.ephemeral, "box": body.box, "for": members[user]["public"]}

    view = vault_change(user, change)
    log.info("vault: %s started a new household key", user)
    return view


@app.put("/vault/secrets")
def put_vault_secrets(body: VaultSecrets, request: Request) -> dict[str, Any]:
    """Sets or (with null) removes sealed secrets, from a phone that holds the household key."""
    user = require_frigate_session(request)
    for name, box in body.secrets.items():
        if not VAULT_SECRET_NAME.match(name):
            raise HTTPException(status_code=400, detail="Bad secret name")
        if box is not None:
            vault_blob(box)

    def change(state: dict[str, Any]) -> None:
        vault_require_granted(state, user, body.household)
        secrets = state.setdefault("secrets", {})
        for name, box in body.secrets.items():
            if box is None:
                secrets.pop(name, None)
            else:
                secrets[name] = box

    view = vault_change(user, change)
    log.info("vault: %s set %s", user, ", ".join(sorted(body.secrets)))
    return view


# ---------------------------------------------------------------- timeline
#
# Where one person has been since they first carried a phone, from Google Maps Timeline. Google
# keeps Timeline on the phone now, and the only way out is the phone's own export (Settings,
# Location, Location services, Timeline, Export Timeline data): one JSON file of every visit and
# every trip. Saved into a Drive folder shared with the relay's key, that file is found here on a
# schedule, read once, and kept in a database of its own (timeline.db: a twelve-year export is
# millions of points, and relay.db is read every few seconds). The app's Atlas section then fetches
# a year at a time and keeps its own copy, so it works with the box out of reach (docs/atlas.md).
#
# Three shapes of file are read: the phone's export (Android's `semanticSegments`, the iPhone's
# bare array), and the two older Takeout ones (the monthly `timelineObjects` files and the raw
# `Records.json`), loose or inside a Takeout zip. Whatever the shape, it becomes three kinds of row:
# a *visit* (stayed somewhere), a *move* (went from one place to another, and how) and a *path*
# (the points along the way, each with its time).
#
# A newer export replaces what an older one said a day at a time: every day the new file mentions
# is dropped and written again, and a day it doesn't mention is left alone. So a phone that only
# remembers the last three months adds to the history rather than replacing it.
#
# The export names no places, only Google's place ids. Each is asked of the Places API once, the
# places most time was spent at first, and never more in a month than TIMELINE_PLACES_MONTHLY: a
# name is the "Pro" price, which is free up to a monthly allowance.
#
# This is one person's whereabouts, so it is narrower than a Frigate sign-in: a Frigate admin may
# read it, or, once TIMELINE_USERS lists usernames, only those.
#
# Setup, once (docs/atlas.md): switch on the Google Drive API for the key's Cloud project, share a
# Drive folder with the key's `client_email` (read-only), and put the folder's id and a Places API
# key in timeline.env on the box (the repo is public). `GET /timeline` says which step is missing.

TIMELINE_DB = os.environ.get("TIMELINE_DB", os.path.join(os.path.dirname(DB_PATH) or ".", "timeline.db"))
TIMELINE_DRIVE_FOLDER = os.environ.get("TIMELINE_DRIVE_FOLDER", "").strip()
TIMELINE_KEY = os.environ.get("TIMELINE_KEY", KEY_FILE)
TIMELINE_USERS = {u.strip() for u in os.environ.get("TIMELINE_USERS", "").split(",") if u.strip()}
TIMELINE_PLACES_KEY = os.environ.get("TIMELINE_PLACES_KEY", "").strip()
# Below Google's free monthly allowance for the Pro price (5,000 when this was written), with room
# for the month turning over at midnight Pacific rather than UTC.
TIMELINE_PLACES_MONTHLY = int(os.environ.get("TIMELINE_PLACES_MONTHLY", "4000"))
TIMELINE_PLACES_PER_RUN = 500
# How often the Drive folder is looked in. A listing is one small call; the file is only fetched
# when it isn't the one already read.
TIMELINE_CHECK_SECONDS = max(300.0, float(os.environ.get("TIMELINE_CHECK_SECONDS", "3600")))
DRIVE_API = "https://www.googleapis.com/drive/v3/files"
DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
PLACES_API = "https://places.googleapis.com/v1/places"
# `displayName` and `primaryType` are what make this the Pro price; the rest ride along free.
# `addressComponents` is where the town, state and country come from: what the app counts as
# "cities and states visited" without a map of borders.
PLACES_FIELDS = "id,displayName,formattedAddress,shortFormattedAddress,primaryType,types,addressComponents"
TIMELINE_CACHE_CONTROL = "private, no-store"

# A stay, when all there is to go on is raw fixes (Records.json): within this far of where it
# began, for at least this long.
STAY_METRES = 150.0
STAY_SECONDS = 600
# A fix less sure of itself than this says nothing about which street it was.
FIX_MAX_ACCURACY_METRES = 200.0
# Fixes further apart in time than this aren't one journey.
FIX_GAP_SECONDS = 3600

TIMELINE_SCHEMA = (
    "CREATE TABLE IF NOT EXISTS visits (started INTEGER, ended INTEGER, utc_offset INTEGER, lat REAL, lon REAL, place TEXT, kind TEXT, p REAL, day INTEGER)",
    "CREATE TABLE IF NOT EXISTS moves (started INTEGER, ended INTEGER, utc_offset INTEGER, mode TEXT, lat REAL, lon REAL, to_lat REAL, to_lon REAL, metres REAL, day INTEGER)",
    "CREATE TABLE IF NOT EXISTS paths (started INTEGER, ended INTEGER, utc_offset INTEGER, points TEXT, times TEXT, n INTEGER, day INTEGER)",
    "CREATE TABLE IF NOT EXISTS places (place TEXT PRIMARY KEY, lat REAL, lon REAL, kind TEXT, name TEXT, address TEXT, type TEXT, asked REAL, status TEXT, city TEXT, region TEXT, country TEXT)",
    "CREATE TABLE IF NOT EXISTS years (year INTEGER PRIMARY KEY, etag TEXT, body BLOB, visits INTEGER, moves INTEGER, points INTEGER)",
    "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",
    "CREATE INDEX IF NOT EXISTS visits_day ON visits (day)",
    "CREATE INDEX IF NOT EXISTS visits_started ON visits (started)",
    "CREATE INDEX IF NOT EXISTS visits_place ON visits (place)",
    "CREATE INDEX IF NOT EXISTS moves_day ON moves (day)",
    "CREATE INDEX IF NOT EXISTS moves_started ON moves (started)",
    "CREATE INDEX IF NOT EXISTS paths_day ON paths (day)",
    "CREATE INDEX IF NOT EXISTS paths_started ON paths (started)",
    # The same three as refined (see "timeline: refining"): what the app is given. A visit says the
    # place Google had given it (`was`) when refining moved it, and why.
    "CREATE TABLE IF NOT EXISTS refined_visits (started INTEGER, ended INTEGER, utc_offset INTEGER, lat REAL, lon REAL, place TEXT, kind TEXT, p REAL, day INTEGER, was TEXT, why TEXT)",
    "CREATE TABLE IF NOT EXISTS refined_moves (started INTEGER, ended INTEGER, utc_offset INTEGER, mode TEXT, lat REAL, lon REAL, to_lat REAL, to_lon REAL, metres REAL, day INTEGER)",
    "CREATE TABLE IF NOT EXISTS refined_paths (started INTEGER, ended INTEGER, utc_offset INTEGER, points TEXT, times TEXT, n INTEGER, day INTEGER)",
    "CREATE INDEX IF NOT EXISTS refined_visits_started ON refined_visits (started)",
    "CREATE INDEX IF NOT EXISTS refined_moves_started ON refined_moves (started)",
    "CREATE INDEX IF NOT EXISTS refined_paths_started ON refined_paths (started)",
)
TIMELINE_TABLES = ("visits", "moves", "paths")

# How Google has named ways of getting about, across the three exports ("IN_PASSENGER_VEHICLE",
# "in passenger vehicle", "DRIVE"), as the app's handful. Looked up with any "IN_" taken off.
TIMELINE_MODES = {
    "WALKING": "walk", "WALK": "walk", "ON_FOOT": "walk", "HIKING": "walk", "RUNNING": "run", "RUN": "run",
    "CYCLING": "cycle", "CYCLE": "cycle", "ON_BICYCLE": "cycle", "BICYCLE": "cycle",
    "PASSENGER_VEHICLE": "drive", "VEHICLE": "drive", "ROAD_VEHICLE": "drive", "DRIVE": "drive", "DRIVING": "drive", "TAXI": "drive", "CAR": "drive",
    "MOTORCYCLING": "motorcycle", "MOTORCYCLE": "motorcycle", "BUS": "bus", "TRAIN": "train", "RAIL_VEHICLE": "train",
    "SUBWAY": "subway", "TRAM": "tram", "FLYING": "fly", "FLY": "fly", "PLANE": "fly",
    "FERRY": "ferry", "SAILING": "boat", "BOATING": "boat", "BOAT": "boat", "SKIING": "ski", "SNOWBOARDING": "ski",
}

_timeline_sync_lock = threading.Lock()
# One look in Drive and one round of naming at a time, whoever asked for it (see `timeline_check`).
_timeline_check_lock = threading.Lock()
_timeline_google: dict[str, Any] = {"session": None, "account": None}
_TIMELINE_NUMBER = re.compile(r"-?\d+(?:\.\d+)?")


def json_items(stream: Any, chunk: int = 1 << 20):
    """
    A JSON document too large to hold, a piece at a time: `(key, element)` for every element of
    an array one level into the top object, `(key, value)` for anything else there, and
    `(None, element)` when the document is itself an array. [stream] is read as text.
    """
    decoder = json.JSONDecoder()
    buffer, at, done = "", 0, False

    def more() -> bool:
        nonlocal buffer, at, done
        piece = stream.read(chunk)
        if not piece:
            done = True
            return False
        buffer, at = buffer[at:] + piece, 0
        return True

    def peek() -> str:
        """The next character that says something, without taking it; "" at the end."""
        nonlocal at
        while True:
            while at < len(buffer) and buffer[at] in " \t\r\n,:":
                at += 1
            if at < len(buffer):
                return buffer[at]
            if not more():
                return ""

    def value() -> Any:
        nonlocal at
        while True:
            try:
                parsed, end = decoder.raw_decode(buffer, at)
                # A number or a word that runs to the end of what has been read may go on.
                if end < len(buffer) or done:
                    at = end
                    return parsed
            except json.JSONDecodeError:
                if done:
                    raise
            more()

    first = peek()
    if first == "[":
        at += 1
        while peek() not in ("]", ""):
            yield None, value()
        return
    if first != "{":
        return
    at += 1
    while peek() not in ("}", ""):
        key = value()
        if peek() != "[":
            yield key, value()
            continue
        at += 1
        while True:
            following = peek()
            if following == "]":
                at += 1
                break
            if following == "":
                return
            yield key, value()


def _polyline_number(value: int) -> str:
    value = ~(value << 1) if value < 0 else value << 1
    out = []
    while value >= 0x20:
        out.append(chr((0x20 | (value & 0x1F)) + 63))
        value >>= 5
    out.append(chr(value + 63))
    return "".join(out)


def polyline(points: list[tuple[float, float]]) -> str:
    """Google's encoded polyline: each point as its difference from the last, to five decimal places (about a metre). The app's `Polyline.kt` reads it."""
    out, last_lat, last_lon = [], 0, 0
    for lat, lon in points:
        a, b = round(lat * 1e5), round(lon * 1e5)
        out.append(_polyline_number(a - last_lat))
        out.append(_polyline_number(b - last_lon))
        last_lat, last_lon = a, b
    return "".join(out)


def polyline_numbers(numbers: list[int]) -> str:
    """Whole numbers the same way: each as its difference from the last. A path's times, in seconds."""
    out, last = [], 0
    for number in numbers:
        out.append(_polyline_number(number - last))
        last = number
    return "".join(out)


def _number(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def timeline_point(value: Any) -> tuple[float, float] | None:
    """
    A place on Earth however an export wrote it: "41.88°, -87.63°" (Android), "geo:41.88,-87.63"
    (iPhone), an object holding either, or Takeout's `latitudeE7`/`lngE7` integers. None when it
    isn't one (and 0, 0 isn't: it is what a missing fix is written as).
    """
    if isinstance(value, dict):
        for lat_key, lon_key in (("latitudeE7", "longitudeE7"), ("latE7", "lngE7")):
            if lat_key in value and lon_key in value:
                lat, lon = _number(value[lat_key]) / 1e7, _number(value[lon_key]) / 1e7
                break
        else:
            return timeline_point(value.get("latLng") or value.get("point"))
    elif isinstance(value, str):
        found = _TIMELINE_NUMBER.findall(value)
        if len(found) < 2:
            return None
        lat, lon = float(found[0]), float(found[1])
    else:
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
        return None
    return lat, lon


def timeline_time(value: Any, zone: ZoneInfo | None = None) -> tuple[int, int] | None:
    """
    A moment as (epoch seconds, minutes ahead of UTC the clock on the wall was). The phone's export
    writes the offset; Takeout writes UTC or milliseconds and says nothing of where the clock was,
    so those take [zone]'s offset at that moment (the household's: right at home, an hour or more
    out on a trip to another zone).
    """
    if value is None or value == "":
        return None
    text = str(value)
    if isinstance(value, (int, float)) or text.isdigit():
        epoch = float(value)
        epoch, explicit = (epoch / 1000 if epoch > 1e11 else epoch), None
    else:
        try:
            moment = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return None
        explicit = None if text.endswith("Z") or moment.tzinfo is None else moment.utcoffset()
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
        epoch = moment.timestamp()
    if explicit is None:
        explicit = datetime.fromtimestamp(epoch, zone).utcoffset() if zone is not None else timedelta(0)
    return int(epoch), int(explicit.total_seconds() // 60)


def timeline_mode(value: Any) -> str:
    key = re.sub(r"[^A-Z]+", "_", str(value or "").upper()).strip("_")
    return TIMELINE_MODES.get(key.removeprefix("IN_"), "other")


def timeline_kind(value: Any) -> str:
    """Home, work, or anywhere else, from "HOME", "INFERRED_WORK", "TYPE_HOME" and the like."""
    key = str(value or "").upper().removeprefix("TYPE_").removeprefix("INFERRED_")
    return "home" if key == "HOME" else "work" if key == "WORK" else "other"


def timeline_db(path: str | None = None) -> sqlite3.Connection:
    """
    A connection of the caller's own, to close when done. Write-ahead logging, so a phone can go on
    fetching years while an export is being read in.
    """
    path = path or TIMELINE_DB
    if os.path.dirname(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path, timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    for statement in TIMELINE_SCHEMA:
        conn.execute(statement)
    conn.commit()
    return conn


def timeline_meta(conn: sqlite3.Connection, key: str) -> Any | None:
    row = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return json.loads(row[0]) if row else None


def timeline_meta_set(conn: sqlite3.Connection, key: str, value: Any) -> None:
    conn.execute("INSERT OR REPLACE INTO meta VALUES (?,?)", (key, json.dumps(value)))
    conn.commit()


class TimelineStage:
    """
    The rows read from one export, held aside until the whole file has been read: a file that
    turns out to be cut short then changes nothing (see `timeline_ingest`).
    """

    FLUSH = 5000

    def __init__(self, conn: sqlite3.Connection, zone: ZoneInfo | None = None):
        self.conn, self.zone = conn, zone
        self.rows: dict[str, list[tuple]] = {table: [] for table in TIMELINE_TABLES}
        self.counts = {"visits": 0, "moves": 0, "paths": 0, "points": 0}
        # What the export itself says of a place: which is home and work, and (Takeout) its name.
        self.kinds: dict[str, str] = {}
        self.names: dict[str, tuple[str, str | None]] = {}
        # Where the clock on the wall last was, from the phone's export (see `timeline_phone_segment`).
        self.local_offset: int | None = None
        for table in TIMELINE_TABLES:
            conn.execute(f"DROP TABLE IF EXISTS temp.new_{table}")
            conn.execute(f"CREATE TEMP TABLE new_{table} AS SELECT * FROM {table} WHERE 0")

    def visit(self, began: tuple[int, int], ended: tuple[int, int], at: tuple[float, float], place: Any, kind: str, p: float) -> None:
        place = str(place) if place else None
        self._add("visits", (began[0], ended[0], began[1], round(at[0], 6), round(at[1], 6), place, kind, round(p, 3), began[0] // 86400))
        if place and kind != "other":
            self.kinds[place] = kind

    def move(self, began: tuple[int, int], ended: tuple[int, int], mode: str, a: tuple[float, float], b: tuple[float, float], metres: float) -> None:
        if metres <= 0:
            metres = haversine_metres(a, b)
        self._add("moves", (began[0], ended[0], began[1], mode, round(a[0], 6), round(a[1], 6), round(b[0], 6), round(b[1], 6), round(metres, 1), began[0] // 86400))

    def path(self, offset: int, points: list[tuple[int, float, float]]) -> None:
        """[points] as (epoch seconds, latitude, longitude). Sorted here; two in a row at one spot are one."""
        kept: list[tuple[int, float, float]] = []
        for point in sorted(points):
            if not kept or (round(point[1], 5), round(point[2], 5)) != (round(kept[-1][1], 5), round(kept[-1][2], 5)):
                kept.append(point)
        if len(kept) < 2:
            return
        started, ended = kept[0][0], kept[-1][0]
        self._add("paths", (started, ended, offset, polyline([(p[1], p[2]) for p in kept]), polyline_numbers([p[0] - started for p in kept]), len(kept), started // 86400))
        self.counts["points"] += len(kept)

    def _add(self, table: str, row: tuple) -> None:
        self.rows[table].append(row)
        self.counts[table] += 1
        if len(self.rows[table]) >= self.FLUSH:
            self.flush(table)

    def flush(self, table: str | None = None) -> None:
        for name in (table,) if table else TIMELINE_TABLES:
            rows, self.rows[name] = self.rows[name], []
            if rows:
                self.conn.executemany(f"INSERT INTO temp.new_{name} VALUES ({','.join('?' * len(rows[0]))})", rows)


def timeline_phone_segment(segment: dict[str, Any], stage: TimelineStage) -> None:
    """
    One entry of the phone's export: a visit, an activity, or a stretch of path.

    Android writes every time on the clock the phone keeps *now*, with that clock's offset, and
    says where the clock on the wall was at the time in a field of its own
    (`startTimeTimezoneUtcOffsetMinutes`): a decade lived in Philadelphia, exported from a phone
    since moved to Seattle, is all written in Pacific time. The field is the one that says when in
    the day something was. Visits and activities carry it; a stretch of path doesn't, and takes the
    last one seen, which is from the visit or activity it lies beside.
    """
    began, ended = timeline_time(segment.get("startTime"), stage.zone), timeline_time(segment.get("endTime"), stage.zone)
    if began is None or ended is None or ended[0] < began[0]:
        return
    local = segment.get("startTimeTimezoneUtcOffsetMinutes")
    if isinstance(local, (int, float)) and not isinstance(local, bool):
        stage.local_offset = int(local)
    if stage.local_offset is not None:
        began = (began[0], stage.local_offset)
    visit, activity, path = segment.get("visit"), segment.get("activity"), segment.get("timelinePath")
    if isinstance(visit, dict):
        # A shop inside the mall that is also a visit: the mall is the one that counts the time.
        if _number(visit.get("hierarchyLevel")) > 0:
            return
        top = visit.get("topCandidate") if isinstance(visit.get("topCandidate"), dict) else {}
        at = timeline_point(top.get("placeLocation"))
        if at:
            stage.visit(began, ended, at, top.get("placeId") or top.get("placeID"), timeline_kind(top.get("semanticType")), _number(top.get("probability")))
    elif isinstance(activity, dict):
        a, b = timeline_point(activity.get("start")), timeline_point(activity.get("end"))
        top = activity.get("topCandidate") if isinstance(activity.get("topCandidate"), dict) else {}
        if a and b:
            stage.move(began, ended, timeline_mode(top.get("type")), a, b, _number(activity.get("distanceMeters")))
    elif isinstance(path, list):
        points = []
        for entry in path:
            if not isinstance(entry, dict):
                continue
            at, when = timeline_point(entry.get("point")), timeline_time(entry.get("time"), stage.zone)
            # The iPhone writes minutes since the stretch began instead of a time.
            seconds = when[0] if when else began[0] + int(60 * _number(entry.get("durationMinutesOffsetFromStartTime")))
            if at:
                points.append((seconds, at[0], at[1]))
        stage.path(began[1], points)


def _takeout_span(duration: Any, zone: ZoneInfo | None) -> tuple[tuple[int, int], tuple[int, int]] | None:
    if not isinstance(duration, dict):
        return None
    began = timeline_time(duration.get("startTimestamp") or duration.get("startTimestampMs"), zone)
    ended = timeline_time(duration.get("endTimestamp") or duration.get("endTimestampMs"), zone)
    return (began, ended) if began and ended and ended[0] >= began[0] else None


def timeline_takeout_object(item: dict[str, Any], stage: TimelineStage) -> None:
    """One entry of Takeout's monthly Semantic Location History: a `placeVisit` or an `activitySegment`."""
    visit, segment = item.get("placeVisit"), item.get("activitySegment")
    if isinstance(visit, dict):
        span = _takeout_span(visit.get("duration"), stage.zone)
        location = visit.get("location") if isinstance(visit.get("location"), dict) else {}
        at = timeline_point(location) or timeline_point({"latitudeE7": visit.get("centerLatE7"), "longitudeE7": visit.get("centerLngE7")})
        if span is None or at is None:
            return
        confidence = {"HIGH_CONFIDENCE": 0.9, "MEDIUM_CONFIDENCE": 0.6, "LOW_CONFIDENCE": 0.3}.get(str(visit.get("placeConfidence")), _number(visit.get("visitConfidence")) / 100)
        place = location.get("placeId")
        stage.visit(span[0], span[1], at, place, timeline_kind(location.get("semanticType")), confidence)
        if place and location.get("name"):
            stage.names[str(place)] = (str(location["name"]), str(location["address"]).replace("\n", ", ") if location.get("address") else None)
    elif isinstance(segment, dict):
        span = _takeout_span(segment.get("duration"), stage.zone)
        a, b = timeline_point(segment.get("startLocation")), timeline_point(segment.get("endLocation"))
        if span is None or a is None or b is None:
            return
        began, ended = span
        stage.move(began, ended, timeline_mode(segment.get("activityType")), a, b, _number(segment.get("distance")))
        raw = (segment.get("simplifiedRawPath") or {}).get("points") if isinstance(segment.get("simplifiedRawPath"), dict) else None
        points = [(began[0], a[0], a[1])]
        if isinstance(raw, list) and raw:
            for entry in raw:
                at, when = timeline_point(entry), timeline_time(entry.get("timestamp") or entry.get("timestampMs"), stage.zone)
                if at and when:
                    points.append((when[0], at[0], at[1]))
        else:
            # Waypoints carry no times: spread evenly over the trip.
            way = (segment.get("waypointPath") or {}).get("waypoints") if isinstance(segment.get("waypointPath"), dict) else None
            found = [p for p in (timeline_point(w) for w in way or []) if p]
            for i, at in enumerate(found):
                points.append((began[0] + int((ended[0] - began[0]) * (i + 1) / (len(found) + 1)), at[0], at[1]))
        points.append((ended[0], b[0], b[1]))
        stage.path(began[1], points)


class TimelineFixes:
    """
    Takeout's Records.json is every raw fix and nothing else, so the stays are found here: fixes
    that keep within STAY_METRES of where they began for STAY_SECONDS are a visit, and what lies
    between two visits is a move with its path. The fixes arrive in time order.
    """

    def __init__(self, stage: TimelineStage):
        self.stage = stage
        self.stay: list[tuple[int, float, float]] = []
        self.trail: list[tuple[int, float, float]] = []
        self.offset = 0

    def add(self, item: dict[str, Any]) -> None:
        at, when = timeline_point(item), timeline_time(item.get("timestamp") or item.get("timestampMs"), self.stage.zone)
        if at is None or when is None or _number(item.get("accuracy")) > FIX_MAX_ACCURACY_METRES:
            return
        last = self.stay[-1] if self.stay else self.trail[-1] if self.trail else None
        if last is not None and when[0] - last[0] > FIX_GAP_SECONDS:
            self.finish()
        elif self.stay and haversine_metres((self.stay[0][1], self.stay[0][2]), at) > STAY_METRES:
            self._close()
        self.offset = when[1]
        self.stay.append((when[0], at[0], at[1]))

    def _close(self) -> None:
        stay, self.stay = self.stay, []
        if not stay:
            return
        if stay[-1][0] - stay[0][0] < STAY_SECONDS:
            self.trail.extend(stay)
            return
        centre = (sum(p[1] for p in stay) / len(stay), sum(p[2] for p in stay) / len(stay))
        self._flush(self.trail + [stay[0]])
        self.stage.visit((stay[0][0], self.offset), (stay[-1][0], self.offset), centre, None, "other", 0.0)
        self.trail = [stay[-1]]

    def _flush(self, trail: list[tuple[int, float, float]]) -> None:
        if len(trail) < 2:
            return
        metres = sum(haversine_metres((a[1], a[2]), (b[1], b[2])) for a, b in zip(trail, trail[1:]))
        if metres < STAY_METRES:
            return
        self.stage.move((trail[0][0], self.offset), (trail[-1][0], self.offset), "other", (trail[0][1], trail[0][2]), (trail[-1][1], trail[-1][2]), metres)
        self.stage.path(self.offset, trail)

    def finish(self) -> None:
        self._close()
        self._flush(self.trail)
        self.trail = []


def timeline_read(stream: Any, stage: TimelineStage, worked_out: bool = True, raw: bool = True) -> None:
    """
    Reads one export of any of the three shapes into [stage]. A file that is none of them adds
    nothing. [worked_out] is the visits and trips Google had already made of the fixes (the phone's
    export, Takeout's monthly files); [raw] is the fixes themselves (Records.json), cut into stays here.
    """
    fixes = TimelineFixes(stage)
    for key, item in json_items(stream):
        if not isinstance(item, dict):
            continue
        if key == "locations":
            if raw:
                fixes.add(item)
        elif not worked_out:
            continue
        elif key is None or key == "semanticSegments":
            timeline_phone_segment(item, stage)
        elif key == "timelineObjects":
            timeline_takeout_object(item, stage)
        elif key == "userLocationProfile":
            for place in item.get("frequentPlaces") or []:
                if isinstance(place, dict) and place.get("placeId") and timeline_kind(place.get("label")) != "other":
                    stage.kinds[str(place["placeId"])] = timeline_kind(place.get("label"))
    fixes.finish()


def timeline_read_file(path: str, stage: TimelineStage) -> None:
    """
    An export, or a Takeout zip of them: every JSON file inside is read, and those that aren't
    Timeline add nothing. A Takeout zip holds the same years twice over, as the monthly files of
    visits and trips and as Records.json's raw fixes; read both and every mile would count double.
    So the fixes are only gone to when the zip has nothing already worked out.
    """
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            names = [name for name in sorted(archive.namelist()) if name.lower().endswith(".json")]

            def read(worked_out: bool) -> None:
                for name in names:
                    with archive.open(name) as member:
                        timeline_read(io.TextIOWrapper(member, encoding="utf-8-sig"), stage, worked_out=worked_out, raw=not worked_out)

            read(worked_out=True)
            if not (stage.counts["visits"] or stage.counts["moves"]):
                read(worked_out=False)
    else:
        with open(path, encoding="utf-8-sig") as f:
            timeline_read(f, stage)


def timeline_year_body(conn: sqlite3.Connection, year: int) -> dict[str, Any]:
    """
    One calendar year (UTC, by when each row began) as the app fetches it, as refined (see
    "timeline: refining"). Columns rather than rows, a place as its index into `places`, and a
    path as two encoded strings: a year of a busy life is about a megabyte this way. A visit
    refining moved says the place Google had given it (`was`, -1 for one it didn't) and why.
    """
    span = (calendar.timegm((year, 1, 1, 0, 0, 0)), calendar.timegm((year + 1, 1, 1, 0, 0, 0)))
    visits = conn.execute("SELECT started, ended, utc_offset, lat, lon, place, kind, p, was, why FROM refined_visits WHERE started >= ? AND started < ? ORDER BY started, ended", span).fetchall()
    moves = conn.execute("SELECT started, ended, utc_offset, mode, lat, lon, to_lat, to_lon, metres FROM refined_moves WHERE started >= ? AND started < ? ORDER BY started, ended", span).fetchall()
    paths = conn.execute("SELECT started, utc_offset, points, times, n FROM refined_paths WHERE started >= ? AND started < ? ORDER BY started", span).fetchall()
    places = sorted({v[5] for v in visits if v[5]} | {v[8] for v in visits if v[8]})
    index = {place: i for i, place in enumerate(places)}

    def column(rows: list[tuple], i: int) -> list[Any]:
        return [row[i] for row in rows]

    return {
        "year": year,
        "places": places,
        "visits": {
            "started": column(visits, 0), "ended": column(visits, 1), "offset": column(visits, 2), "lat": column(visits, 3), "lon": column(visits, 4),
            "place": [index.get(v[5], -1) for v in visits], "kind": column(visits, 6), "p": column(visits, 7),
            "was": [index.get(v[8], -1) for v in visits], "why": column(visits, 9),
        },
        "moves": {
            "started": column(moves, 0), "ended": column(moves, 1), "offset": column(moves, 2), "mode": column(moves, 3), "lat": column(moves, 4),
            "lon": column(moves, 5), "to_lat": column(moves, 6), "to_lon": column(moves, 7), "metres": column(moves, 8),
        },
        "paths": {"started": column(paths, 0), "offset": column(paths, 1), "points": column(paths, 2), "times": column(paths, 3), "count": column(paths, 4)},
    }


def timeline_rebuild_years(conn: sqlite3.Connection) -> list[int]:
    """Writes each year's body again and says which changed. A year's `etag` is how a phone knows to fetch it."""
    have = {row[0]: row[1] for row in conn.execute("SELECT year, etag FROM years")}
    present = {
        int(row[0]) for table in REFINED_TABLES
        for row in conn.execute(f"SELECT DISTINCT strftime('%Y', started, 'unixepoch') FROM {table}") if row[0]
    }
    changed = []
    for year in sorted(present):
        body = timeline_year_body(conn, year)
        text = json.dumps(body, separators=(",", ":")).encode()
        etag = hashlib.sha1(text).hexdigest()[:16]
        if have.get(year) != etag:
            conn.execute(
                "INSERT OR REPLACE INTO years VALUES (?,?,?,?,?,?)",
                (year, etag, gzip.compress(text, mtime=0), len(body["visits"]["started"]), len(body["moves"]["started"]), sum(body["paths"]["count"])),
            )
            changed.append(year)
    for year in set(have) - present:
        conn.execute("DELETE FROM years WHERE year=?", (year,))
        changed.append(year)
    conn.commit()
    return sorted(changed)


def timeline_ingest(conn: sqlite3.Connection, stage: TimelineStage, now: float | None = None) -> dict[str, Any]:
    """
    Puts what [stage] read into the history, a day at a time: each day the export mentions is
    dropped and written from it, and the rest are left as they were. One transaction, so a phone
    never fetches half of it.
    """
    now = time.time() if now is None else now
    stage.flush()
    days = "SELECT day FROM temp.new_visits UNION SELECT day FROM temp.new_moves UNION SELECT day FROM temp.new_paths"
    with conn:
        day_count = conn.execute(f"SELECT COUNT(*) FROM ({days})").fetchone()[0]
        for table in TIMELINE_TABLES:
            conn.execute(f"DELETE FROM {table} WHERE day IN ({days})")
            conn.execute(f"INSERT INTO {table} SELECT * FROM temp.new_{table}")
        for table in TIMELINE_TABLES:
            conn.execute(f"DROP TABLE temp.new_{table}")
        conn.execute("INSERT OR IGNORE INTO places (place, lat, lon, kind) SELECT place, AVG(lat), AVG(lon), 'other' FROM visits WHERE place IS NOT NULL GROUP BY place")
        # A place nobody goes to any more (a corrected visit) has nothing to show and no name worth paying for.
        conn.execute("DELETE FROM places WHERE place NOT IN (SELECT place FROM visits WHERE place IS NOT NULL)")
        for place, kind in stage.kinds.items():
            conn.execute("UPDATE places SET kind=? WHERE place=?", (kind, place))
        for place, (name, address) in stage.names.items():
            # A name to go by until Google is asked: the lookup still happens, for the town and state.
            conn.execute("UPDATE places SET name=?, address=? WHERE place=? AND name IS NULL", (name, address, place))
    # The whole history refined again: a new export can say something about years it never touched.
    _, changed = timeline_publish(conn, now)
    timeline_meta_set(conn, "places_version", int(timeline_meta(conn, "places_version") or 0) + 1)
    return {**stage.counts, "days": day_count, "years_changed": changed}


def timeline_import(path: str, source: dict[str, Any] | None = None, zone: ZoneInfo | None = None) -> dict[str, Any]:
    """Reads the export at [path] into the history and remembers it as the file last read."""
    began = time.time()
    conn = timeline_db()
    try:
        stage = TimelineStage(conn, zone)
        timeline_read_file(path, stage)
        summary = timeline_ingest(conn, stage)
        timeline_meta_set(conn, "file", {**(source or {"name": os.path.basename(path)}), "ingested_at": time.time(), **summary})
    finally:
        conn.close()
    log.info("timeline: read %s in %.0fs: %s", (source or {}).get("name") or path, time.time() - began, summary)
    return summary


# ---------------------------------------------------------------- timeline: refining
#
# The export is Google's reading of radio fixes, one stay at a time, and with the whole history in
# view some of it is plainly wrong: nights "at" the coffee shop under a flat, one building under
# two ids, the early years' nights scattered a kilometre round the flat the record later settles
# on (a phone that fixed itself by cell tower), points that wander off while the phone sat on a
# table. Refining reads every visit, move and path there is and writes a corrected copy beside
# them (`refined_*`), which is what the app is given. What the export said is kept as it was, so
# refining starts again from it after every import and every round of place names, with whatever
# more the history knows by then, and a newer TIMELINE_REFINE_VERSION is applied when the relay
# starts.
#
# Each step is a reason a person could check, taken in this order. "The months around" a stay are
# AROUND_SECONDS either side of it, and "a night" is a stay through NIGHT_SECONDS of the hours
# between NIGHT_HOURS on the wall clock.
#
# 1. One building, two ids. Google names the flat one night and the building the next, or the
#    house next door in a terrace. A stay at a residential place goes to whichever residential
#    place within SAME_BUILDING_METRES of it the months around spent most time at.
# 2. Nobody sleeps at the coffee shop. A night at a place of a kind nobody sleeps at goes to the
#    place within NIGHT_SNAP_METRES where the months around spent their nights, or failing that
#    to the nearest residential place within NEXT_DOOR_METRES.
# 3. A visit Google was unsure of (under UNSURE_P) goes to a place within UNSURE_METRES the
#    months around spent UNSURE_RATIO times as long at: nights against nights, days against days.
# 4. A thin record's nights go home. Where a month has a night on record for fewer than
#    THIN_NIGHTS of its days, the phone was fixing itself coarsely and its nights land on
#    whatever is near. Such a night within THIN_METRES of the home of the nearest well-recorded
#    months goes home, unless where it landed is a hotel, or somewhere SETTLED_NIGHTS nights were
#    spent in well-recorded months too (a friend's, a partner's). A place seen only in thin months
#    whose nights mostly went home was that home all along, days too.
# 5. A night nothing was recorded in was spent at home, when the evening's last stay, the
#    morning's first, and anything recorded between were within NEAR_HOME_METRES of the home of
#    the time (the month's own, or the nearest well-recorded month's) and no more than
#    UNRECORDED_SECONDS apart. Such a stay is marked "inferred": nothing Google said put it there.
# 6. One stay, not several. Two stays at one place with nothing between them straying more than
#    STAY_METRES from it are one, and the jitter between them Google took for moves goes: when
#    they are no more than SHORT_GAP_SECONDS apart anywhere, and up to JOIN_SECONDS apart at home
#    (where HOME_SHARE of the nights of the months around were spent) in a well-recorded month. A
#    gap in a good record between two stretches at home was time at home; between two at work it
#    wasn't, and in a thin record it says nothing.
# 7. Paths are smoothed by everywhere else the history has been:
#    a. A point during a stay is at the place stayed at (a phone on a table wanders by hundreds of
#       metres), unless it is further off than DRIFT_METRES.
#    b. A point a path goes out to and straight back from is a bad fix and goes: faster than
#       SPIKE_SPEED anywhere; at any speed where fewer than TURN_TRIPS other trips have been
#       within TURN_TRIP_METRES of it; and at any speed within ARRIVING_SECONDS of arriving at a
#       stay or leaving it, when the path either side is at the place (a phone going indoors).
#    c. A point within SNAP_METRES of where CORRIDOR_TRIPS other trips went the same way moves to
#       the middle of them, SNAP_ROUNDS times over: the middle of many trips' wandering fixes is
#       the street.
#    d. Two fixes FILL_METRES apart are a straight line across whatever was between; when another
#       trip went from near the one to near the other the same way in about the same time, its
#       points between are put in, so the line follows the road it took.
#    Flights are left as they are. The export writes a path's times to the minute, so speeds are
#    worked out with the minute each leg may have taken.
# 8. A move starts where the stay before it was and ends where the next one is.

TIMELINE_REFINE_VERSION = 1
REFINED_TABLES = ("refined_visits", "refined_moves", "refined_paths")
AROUND_SECONDS = 180 * 86400
NIGHT_HOURS = (2, 5)
NIGHT_SECONDS = 2 * 3600
SAME_BUILDING_METRES = 25.0
NIGHT_SNAP_METRES = 150.0
NEXT_DOOR_METRES = 60.0
UNSURE_P = 0.5
UNSURE_METRES = 120.0
UNSURE_RATIO = 5
UNSURE_MIN_SECONDS = 4 * 3600
THIN_NIGHTS = 0.5
THIN_METRES = 1500.0
THIN_REACH_MONTHS = 24
# A well-recorded month's home is where at least this share of its nights were spent.
HOME_SHARE = 0.5
SETTLED_NIGHTS = 3
SHORT_GAP_SECONDS = 3600
JOIN_SECONDS = 48 * 3600
UNRECORDED_SECONDS = 20 * 3600
NEAR_HOME_METRES = 3000.0
# How fast the way home from the evening's last stop is taken to be, about a city's: a guess, for where an inferred night begins.
TRAVEL_SPEED = 4.0
DRIFT_METRES = 2000.0
# A path's points this close to a stay's start or end are the leaving and arriving, left alone.
EDGE_SECONDS = 120
SPIKE_METRES = 300.0
SPIKE_SPEED = 40.0
TURN_METRES = 200.0
TURN_TRIP_METRES = 60.0
TURN_TRIPS = 2
ARRIVING_SECONDS = 15 * 60
SNAP_METRES = 40.0
SNAP_ANGLE = math.pi / 6
CORRIDOR_TRIPS = 3
SNAP_ROUNDS = 2
FILL_METRES = 400.0
FILL_NEAR_METRES = 100.0
FILL_CANDIDATES = 25
MOVE_SNAP_METRES = 300.0
MOVE_SNAP_SECONDS = 600

# Places API (New) primary types, by what they say of a night spent there.
BUILDING_TYPES = frozenset({
    "premise", "subpremise", "street_address", "apartment_building", "apartment_complex", "condominium_complex", "housing_complex",
})
LODGING_TYPES = frozenset({
    "lodging", "hotel", "motel", "hostel", "inn", "resort_hotel", "extended_stay_hotel", "bed_and_breakfast", "guest_house", "cottage",
    "farmstay", "private_guest_room", "japanese_inn", "budget_japanese_inn", "camping_cabin", "campground", "rv_park", "mobile_home_park",
    "hospital", "general_hospital",
})
# Where a night is believable: a home, a bed, somewhere open all night, or a place Google only knew as an area.
SLEEP_TYPES = BUILDING_TYPES | LODGING_TYPES | frozenset({
    "route", "medical_center", "casino", "night_club", "international_airport", "airport", "ferry_terminal", "train_station",
    "bus_station", "transit_station", "national_park", "state_park", "locality", "neighborhood", "sublocality", "sublocality_level_1",
    "postal_code", "administrative_area_level_1", "administrative_area_level_2", "administrative_area_level_3", "country", "natural_feature",
})

_timeline_refine_lock = threading.Lock()


def _polyline_deltas(text: str) -> list[int]:
    out, number, shift = [], 0, 0
    for ch in text:
        b = ord(ch) - 63
        number |= (b & 0x1F) << shift
        shift += 5
        if b < 0x20:
            out.append(~(number >> 1) if number & 1 else number >> 1)
            number, shift = 0, 0
    return out


def unpolyline(text: str) -> list[tuple[float, float]]:
    """`polyline` undone."""
    deltas, lat, lon, out = _polyline_deltas(text), 0, 0, []
    for i in range(0, len(deltas) - 1, 2):
        lat, lon = lat + deltas[i], lon + deltas[i + 1]
        out.append((lat / 1e5, lon / 1e5))
    return out


def unpolyline_numbers(text: str) -> list[int]:
    """`polyline_numbers` undone."""
    total, out = 0, []
    for delta in _polyline_deltas(text):
        total += delta
        out.append(total)
    return out


def _metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """`haversine_metres` without the tuples, for the hundreds of thousands of points refining measures."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(min(1.0, h)))


def nights_by_month(started: int, ended: int, offset: int) -> Counter:
    """`night_seconds`, shared out among the months on the wall clock that the nights fell in."""
    local, end = started + offset * 60, ended + offset * 60
    out, day = Counter(), local - local % 86400
    while day < end:
        seconds = max(0, min(end, day + NIGHT_HOURS[1] * 3600) - max(local, day + NIGHT_HOURS[0] * 3600))
        if seconds:
            out[_local_month(day, 0)] += seconds
        day += 86400
    return out


def night_seconds(started: int, ended: int, offset: int) -> int:
    """How much of a stay fell between NIGHT_HOURS on the wall clock [offset] minutes ahead of UTC."""
    local, end = started + offset * 60, ended + offset * 60
    total, day = 0, local - local % 86400
    while day < end:
        total += max(0, min(end, day + NIGHT_HOURS[1] * 3600) - max(local, day + NIGHT_HOURS[0] * 3600))
        day += 86400
    return total


def _sleepable(kind_of: str | None) -> bool:
    """A night at a place of this type is believable. A place Google hasn't said the type of could be anything."""
    return kind_of is None or kind_of in SLEEP_TYPES


class _Stay:
    """A visit as refining works on it: [google] is the place the export gave, [place] where refining has it."""

    __slots__ = ("started", "ended", "offset", "lat", "lon", "place", "kind", "p", "google", "why", "night", "seen")

    def __init__(self, started: int, ended: int, offset: int, lat: float, lon: float, place: str | None, kind: str, p: float):
        self.started, self.ended, self.offset, self.lat, self.lon = started, ended, offset, lat, lon
        # Where the export put it, which is where the moves either side of it start and end.
        self.seen = (lat, lon)
        self.place, self.kind, self.p, self.google, self.why = place, kind, p or 0.0, place, None
        self.night = night_seconds(started, ended, offset)

    @property
    def middle(self) -> int:
        return (self.started + self.ended) // 2

    @property
    def is_night(self) -> bool:
        return self.night >= NIGHT_SECONDS


class _Places:
    """Every place visited: where it is, Google's type for it, what it is to the person; found by where."""

    CELL = 0.01

    def __init__(self, conn: sqlite3.Connection, stays: list[_Stay]):
        self.at: dict[str, tuple[float, float, str | None]] = {}
        self.kinds: dict[str, str] = {}
        self.cells: dict[tuple[int, int], list[str]] = {}
        for place, lat, lon, kind_of, kind in conn.execute("SELECT place, lat, lon, type, kind FROM places"):
            self.at[place] = (lat, lon, kind_of)
            self.kinds[place] = kind or "other"
        for stay in stays:
            if stay.place and stay.place not in self.at:
                self.at[stay.place] = (stay.lat, stay.lon, None)
            # Any visit Google called home makes the place home, as the app reads it.
            if stay.place and stay.kind != "other" and self.kinds.get(stay.place) != "home":
                self.kinds[stay.place] = stay.kind
        for place, (lat, lon, _) in self.at.items():
            self.cells.setdefault((math.floor(lat / self.CELL), math.floor(lon / self.CELL)), []).append(place)

    def type_of(self, place: str | None) -> str | None:
        return self.at[place][2] if place in self.at else None

    def near(self, lat: float, lon: float, metres: float) -> list[tuple[str, float]]:
        """The places within [metres] of a spot, and how far each is."""
        north = math.ceil(metres / 111_000 / self.CELL)
        east = math.ceil(metres / (111_000 * max(0.05, math.cos(math.radians(lat)))) / self.CELL)
        row, column = math.floor(lat / self.CELL), math.floor(lon / self.CELL)
        found = []
        for r in range(row - north, row + north + 1):
            for c in range(column - east, column + east + 1):
                for place in self.cells.get((r, c), ()):
                    there = self.at[place]
                    d = _metres(lat, lon, there[0], there[1])
                    if d <= metres:
                        found.append((place, d))
        return found

    def move(self, stay: _Stay, place: str, why: str) -> None:
        lat, lon, _ = self.at[place]
        stay.place, stay.lat, stay.lon, stay.kind = place, lat, lon, self.kinds.get(place, "other")
        stay.why = why if place != stay.google else None


class _Support:
    """How long was spent at each place and when, to be asked how much of it fell in the months around a moment."""

    def __init__(self, stays: list[_Stay]):
        # "*" is every stay, wherever it was.
        groups: dict[str, list[_Stay]] = {"*": list(stays)}
        for stay in stays:
            if stay.place:
                groups.setdefault(stay.place, []).append(stay)
        self.index: dict[str, tuple[list[int], list[int], list[int]]] = {}
        for place, group in groups.items():
            group.sort(key=lambda stay: stay.middle)
            spent, nights = [0], [0]
            for stay in group:
                spent.append(spent[-1] + stay.ended - stay.started)
                nights.append(nights[-1] + stay.night)
            self.index[place] = ([stay.middle for stay in group], spent, nights)

    def is_home(self, place: str | None, when: int) -> bool:
        """[place] is where HOME_SHARE of the nights of the months around [when] were spent."""
        return self.around(place, when, nights=True) >= HOME_SHARE * max(1, self.around("*", when, nights=True))

    def around(self, place: str | None, when: int, nights: bool = False, leaving: _Stay | None = None) -> int:
        """Seconds spent at [place] in the months around [when] (only the nights, with [nights]); [leaving], the stay being judged, not counted."""
        found = self.index.get(place) if place else None
        if found is None:
            return 0
        middles, spent, night = found
        totals = night if nights else spent
        total = totals[bisect.bisect_right(middles, when + AROUND_SECONDS)] - totals[bisect.bisect_left(middles, when - AROUND_SECONDS)]
        if leaving is not None and leaving.place == place and abs(leaving.middle - when) <= AROUND_SECONDS:
            total -= leaving.night if nights else leaving.ended - leaving.started
        return total


def _refine_buildings(stays: list[_Stay], places: _Places) -> None:
    support = _Support(stays)
    for stay in stays:
        if places.type_of(stay.place) not in BUILDING_TYPES:
            continue
        lat, lon, _ = places.at[stay.place]
        when, best = stay.middle, stay.place
        most = support.around(best, when)
        for other, _ in places.near(lat, lon, SAME_BUILDING_METRES):
            if other != stay.place and places.type_of(other) in BUILDING_TYPES and support.around(other, when) > most:
                best, most = other, support.around(other, when)
        if best != stay.place:
            places.move(stay, best, "building")


def _refine_nights(stays: list[_Stay], places: _Places) -> None:
    support = _Support(stays)
    for stay in stays:
        if not stay.is_night or stay.place is None or _sleepable(places.type_of(stay.place)):
            continue
        lat, lon, _ = places.at[stay.place]
        best, most, next_door, nearest = None, NIGHT_SECONDS - 1, None, NEXT_DOOR_METRES
        for other, metres in places.near(lat, lon, NIGHT_SNAP_METRES):
            kind_of = places.type_of(other)
            if other == stay.place or not _sleepable(kind_of):
                continue
            nights = support.around(other, stay.middle, nights=True)
            if nights > most:
                best, most = other, nights
            if kind_of in BUILDING_TYPES and metres <= nearest:
                next_door, nearest = other, metres
        if best or next_door:
            places.move(stay, best or next_door, "night")


def _refine_unsure(stays: list[_Stay], places: _Places) -> None:
    support = _Support(stays)
    for stay in stays:
        if stay.p >= UNSURE_P or stay.place != stay.google:
            continue
        night, when = stay.is_night, stay.middle
        best, most = None, max(UNSURE_MIN_SECONDS, UNSURE_RATIO * support.around(stay.place, when, nights=night, leaving=stay))
        for other, _ in places.near(stay.lat, stay.lon, UNSURE_METRES):
            if other == stay.place or (night and not _sleepable(places.type_of(other))):
                continue
            spent = support.around(other, when, nights=night)
            if spent >= most:
                best, most = other, spent
        if best:
            places.move(stay, best, "unsure")


def _local_month(epoch: int, offset: int) -> int:
    """Months since year 0 on the wall clock: comparable, and one apart for neighbours."""
    when = time.gmtime(epoch + offset * 60)
    return when.tm_year * 12 + when.tm_mon - 1


class _NightHomes:
    """
    Which months the record is thin in (a night on record for fewer than THIN_NIGHTS of their
    days: a day counts when 03:30 on its wall clock fell in a stay), and where the nights of each
    well-recorded month were spent, where HOME_SHARE of them were spent in one place.
    """

    def __init__(self, stays: list[_Stay]):
        covered: set[int] = set()
        nights: dict[int, Counter] = {}
        for stay in stays:
            shift = stay.offset * 60 - 12_600
            covered.update(range(-((stay.started + shift) // -86400), (stay.ended + shift - 1) // 86400 + 1))
            # A stay across the turn of a month is a home to both.
            if stay.night and stay.place:
                for month, seconds in nights_by_month(stay.started, stay.ended, stay.offset).items():
                    nights.setdefault(month, Counter())[stay.place] += seconds
        on_record = Counter(_local_month(day * 86400, 0) for day in covered)
        first = _local_month(stays[0].started, stays[0].offset)
        last = max(_local_month(stay.ended - 1, stay.offset) for stay in stays)
        self.thin = {month for month in range(first, last + 1) if on_record[month] < THIN_NIGHTS * calendar.monthrange(month // 12, month % 12 + 1)[1]}
        self.homes: dict[int, str] = {}
        for month, spent in nights.items():
            place, most = spent.most_common(1)[0]
            if month not in self.thin and most >= HOME_SHARE * sum(spent.values()):
                self.homes[month] = place
        self.known = sorted(self.homes)

    def beside(self, month: int) -> list[str]:
        """The homes of the nearest well-recorded months before and after [month], within THIN_REACH_MONTHS, nearest first."""
        at = bisect.bisect_left(self.known, month)
        near = sorted((abs(self.known[i] - month), self.known[i]) for i in (at - 1, at) if 0 <= i < len(self.known) and abs(self.known[i] - month) <= THIN_REACH_MONTHS)
        return list(dict.fromkeys(self.homes[m] for _, m in near))

    def of(self, month: int) -> str | None:
        """Home in [month]: its own, or the nearest well-recorded month's."""
        return self.homes.get(month) or next(iter(self.beside(month)), None)


def _refine_thin(stays: list[_Stay], places: _Places, homes: _NightHomes) -> None:
    thin = homes.thin
    settled = Counter(stay.place for stay in stays if stay.is_night and _local_month(stay.started, stay.offset) not in thin)
    homes_beside = homes.beside

    # Where each stay was before this step: a place whose nights go home is judged as a whole below.
    landed = [stay.place for stay in stays]
    went: dict[str, Counter] = {}
    for stay in stays:
        month = _local_month(stay.started, stay.offset)
        if month not in thin or not stay.is_night or places.type_of(stay.place) in LODGING_TYPES or (stay.place and settled[stay.place] >= SETTLED_NIGHTS):
            continue
        options = [(_metres(stay.lat, stay.lon, *places.at[home][:2]), home) for home in homes_beside(month) if home != stay.place]
        options = [option for option in options if option[0] <= THIN_METRES]
        if options:
            home = min(options)[1]
            if stay.place:
                went.setdefault(stay.place, Counter())[home] += 1
            places.move(stay, home, "thin")
    # A place seen only in thin months, whose nights mostly went home, was that home.
    seen_well = {place for stay, place in zip(stays, landed) if _local_month(stay.started, stay.offset) not in thin}
    slept = Counter(place for stay, place in zip(stays, landed) if stay.is_night)
    for stay, place in zip(stays, landed):
        sent = went.get(place)
        if sent is None or stay.place != place or place in seen_well or 2 * sum(sent.values()) < slept[place]:
            continue
        home = sent.most_common(1)[0][0]
        if _metres(stay.lat, stay.lon, *places.at[home][:2]) <= THIN_METRES:
            places.move(stay, home, "thin")


def _refine_unrecorded(stays: list[_Stay], places: _Places, moves: list[list], points: list[tuple[int, float, float]], homes: _NightHomes) -> list[_Stay]:
    times = [point[0] for point in points]
    starts = [move[0] for move in moves]
    whole_night = (NIGHT_HOURS[1] - NIGHT_HOURS[0]) * 3600
    added = []
    for a, b in zip(stays, stays[1:]):
        if b.started - a.ended > UNRECORDED_SECONDS or night_seconds(a.ended, b.started, a.offset) < whole_night:
            continue
        home = homes.of(_local_month(a.ended, a.offset))
        if home is None:
            continue
        lat, lon, _ = places.at[home]
        here, there = _metres(a.lat, a.lon, lat, lon), _metres(b.lat, b.lon, lat, lon)
        between = [moves[i] for i in range(bisect.bisect_left(starts, a.ended - EDGE_SECONDS), bisect.bisect_left(starts, b.started + EDGE_SECONDS))]
        if max(here, there) > NEAR_HOME_METRES or any(
            night_seconds(move[0], move[1], a.offset) or max(_metres(move[4], move[5], lat, lon), _metres(move[6], move[7], lat, lon)) > NEAR_HOME_METRES
            for move in between
        ) or any(
            _metres(points[k][1], points[k][2], lat, lon) > NEAR_HOME_METRES for k in range(bisect.bisect_right(times, a.ended), bisect.bisect_left(times, b.started))
        ):
            continue
        # Home once the way there from the evening's last stop is done, and until the way to the morning's first.
        began = max([a.ended + int(here / TRAVEL_SPEED)] + [move[1] for move in between if move[1] <= b.started and move[0] < a.ended + whole_night])
        ended = min([b.started - int(there / TRAVEL_SPEED)] + [move[0] for move in between if move[0] > began])
        if night_seconds(began, ended, a.offset) < NIGHT_SECONDS:
            continue
        stay = _Stay(began, ended, a.offset, lat, lon, home, places.kinds.get(home, "other"), 0.0)
        stay.google, stay.why = None, "inferred"
        added.append(stay)
    return sorted(stays + added, key=lambda stay: (stay.started, stay.ended))


def _refine_join(stays: list[_Stay], moves: list[list], points: list[tuple[int, float, float]], thin: set[int]) -> tuple[list[_Stay], list[list], int]:
    times = [point[0] for point in points]
    starts = [move[0] for move in moves]
    support = _Support(stays)
    dropped: set[int] = set()
    out: list[_Stay] = []
    joined = 0
    for stay in stays:
        last = out[-1] if out else None
        gap = stay.started - last.ended if last else 0
        # A stay refining moved or put in is joined only to one moved from the same place for the same
        # reason: what the app is told of a joined stay is one `was` and one `why`.
        if (
            last is None or stay.place is None or stay.place != last.place or gap > JOIN_SECONDS
            or (stay.why, stay.google if stay.why else None) != (last.why, last.google if last.why else None)
            or (gap > SHORT_GAP_SECONDS and (_local_month(last.ended, last.offset) in thin or not support.is_home(stay.place, last.ended + gap // 2)))
        ):
            out.append(stay)
            continue
        between = [
            i for i in range(bisect.bisect_left(starts, last.ended - EDGE_SECONDS), bisect.bisect_left(starts, max(last.ended, stay.started) + EDGE_SECONDS))
            if moves[i][1] <= max(last.ended, stay.started) + EDGE_SECONDS
        ]
        strays = any(
            moves[i][8] > 2 * STAY_METRES or _metres(stay.lat, stay.lon, moves[i][4], moves[i][5]) > STAY_METRES
            or _metres(stay.lat, stay.lon, moves[i][6], moves[i][7]) > STAY_METRES
            for i in between
        ) or any(
            _metres(stay.lat, stay.lon, points[k][1], points[k][2]) > STAY_METRES
            for k in range(bisect.bisect_right(times, last.ended + EDGE_SECONDS), bisect.bisect_left(times, stay.started - EDGE_SECONDS))
        )
        if strays:
            out.append(stay)
            continue
        last.ended = max(last.ended, stay.ended)
        last.night += stay.night
        last.p = max(last.p, stay.p)
        dropped.update(between)
        joined += 1
    return out, [move for i, move in enumerate(moves) if i not in dropped], joined


def _despike(points: list[list]) -> list[list]:
    """
    Points a path goes out to and straight back from, faster than SPIKE_SPEED, taken out. The
    export writes a path's times to the minute, so two fixes a minute apart may say no time
    passed: a leg is given the minute it may have taken.
    """
    while len(points) > 2:
        kept = [points[0]]
        for i in range(1, len(points) - 1):
            a, b, c = kept[-1], points[i], points[i + 1]
            out, back, across = _metres(a[1], a[2], b[1], b[2]), _metres(b[1], b[2], c[1], c[2]), _metres(a[1], a[2], c[1], c[2])
            fast = min(out / (b[0] - a[0] + 60), back / (c[0] - b[0] + 60)) > SPIKE_SPEED
            if b[3] or not (fast and out + back - across > SPIKE_METRES and out + back > 3 * across):
                kept.append(b)
        kept.append(points[-1])
        if len(kept) == len(points):
            break
        points = kept
    return points


def _axis(path: list[list], i: int) -> float | None:
    """Which way a path runs through its point [i], as an angle that doesn't mind which way along: None where it isn't going anywhere."""
    a, b = path[max(0, i - 1)], path[min(len(path) - 1, i + 1)]
    north, east = (b[1] - a[1]) * 111_195, (b[2] - a[2]) * 111_195 * math.cos(math.radians(a[1]))
    if north * north + east * east < 400:
        return None
    return math.atan2(north, east) % math.pi


def _across(a: float, b: float) -> float:
    turn = abs(a - b)
    return min(turn, math.pi - turn)


class _Corridors:
    """
    Every point of every path that was on the move, found by where: the roads, paths and lines
    this history has been along, as many times as it has been along them. A point settled at a
    stay says nothing of a road and isn't here.
    """

    CELL_LAT, CELL_LON = 0.0004, 0.0005
    # A square keeps the points of this many trips and no more: nothing asks for more than a few
    # dozen, and the squares round home would otherwise hold thousands.
    CELL_TRIPS = 48

    def __init__(self, paths: list[list[list]], skip: Callable[[int], bool]):
        self.cells: dict[tuple[int, int], list[tuple[int, int, float, float, float | None]]] = {}
        trips: dict[tuple[int, int], set[int]] = {}
        for pid, path in enumerate(paths):
            if skip(pid):
                continue
            for i, point in enumerate(path):
                if not point[3]:
                    key = (math.floor(point[1] / self.CELL_LAT), math.floor(point[2] / self.CELL_LON))
                    seen = trips.setdefault(key, set())
                    if pid in seen or len(seen) < self.CELL_TRIPS:
                        seen.add(pid)
                        self.cells.setdefault(key, []).append((pid, i, point[1], point[2], _axis(path, i)))

    def near(self, lat: float, lon: float, metres: float, other_than: int):
        """(path, index, lat, lon, axis, metres away) of the points of other paths within [metres]."""
        # Flat-earth distances: a few hundred metres across, the curve is a hair's breadth.
        squash = math.cos(math.radians(lat))
        rows, columns = math.ceil(metres / 111_195 / self.CELL_LAT), math.ceil(metres / (111_195 * squash) / self.CELL_LON)
        row, column = math.floor(lat / self.CELL_LAT), math.floor(lon / self.CELL_LON)
        reach = (metres / 111_195) ** 2
        for r in range(row - rows, row + rows + 1):
            for c in range(column - columns, column + columns + 1):
                for entry in self.cells.get((r, c), ()):
                    north, east = entry[2] - lat, (entry[3] - lon) * squash
                    across = north * north + east * east
                    if across <= reach and entry[0] != other_than:
                        yield (*entry, math.sqrt(across) * 111_195)

    def trips(self, lat: float, lon: float, metres: float, other_than: int) -> int:
        """How many other paths have been within [metres] of a spot."""
        return len({entry[0] for entry in self.near(lat, lon, metres, other_than)})


def _drop_lone_turns(paths: list[list[list]], corridors: _Corridors, stays: list[_Stay], skip: Callable[[int], bool], tally: Counter) -> None:
    """
    A point a path goes out to and straight back from is a bad fix whatever its speed, where no
    other trip has been near it, or in the minutes of arriving somewhere or leaving it, when the
    path either side of it is at the place (the phone going indoors and out, between fixes). A
    point at a place stayed at, around then, is that stay, and is never one.
    """
    starts = [stay.started for stay in stays]

    def around(b: list) -> list[_Stay]:
        i = bisect.bisect_right(starts, b[0])
        return [stay for stay in stays[max(0, i - 2):i + 2] if stay.started - ARRIVING_SECONDS <= b[0] <= stay.ended + ARRIVING_SECONDS]

    def at_a_stay(b: list) -> bool:
        return any(_metres(b[1], b[2], stay.lat, stay.lon) <= STAY_METRES for stay in around(b))

    def jumps_off_a_stay(a: list, b: list, c: list) -> bool:
        for stay in around(b):
            if min(abs(b[0] - stay.started), abs(b[0] - stay.ended)) <= ARRIVING_SECONDS and _metres(b[1], b[2], stay.lat, stay.lon) > STAY_METRES and (
                min(_metres(a[1], a[2], stay.lat, stay.lon), _metres(c[1], c[2], stay.lat, stay.lon)) <= STAY_METRES
            ):
                return True
        return False

    for pid, path in enumerate(paths):
        if len(path) < 3 or skip(pid):
            continue
        kept = [path[0]]
        for i in range(1, len(path) - 1):
            a, b, c = kept[-1], path[i], path[i + 1]
            out, back, across = _metres(a[1], a[2], b[1], b[2]), _metres(b[1], b[2], c[1], c[2]), _metres(a[1], a[2], c[1], c[2])
            if not b[3] and out + back - across > TURN_METRES and out + back > 2.5 * across and not at_a_stay(b) and (
                jumps_off_a_stay(a, b, c) or corridors.trips(b[1], b[2], TURN_TRIP_METRES, pid) < TURN_TRIPS
            ):
                tally["points_dropped"] += 1
                continue
            kept.append(b)
        kept.append(path[-1])
        paths[pid] = kept


def _snap_to_corridors(paths: list[list[list]], skip: Callable[[int], bool], tally: Counter) -> None:
    """
    A point within about SNAP_METRES of where CORRIDOR_TRIPS other trips have gone the same way
    (within SNAP_ANGLE) moves to the middle of all their points there and its own: one trip's fix
    wanders a street's width either side, and the middle of many is the street. Points are
    summed into small squares by which way they were going, so a point asks a few dozen sums
    rather than the thousands of points of a street driven every day.
    """
    step_lat, step_lon, ways = 0.00015, 0.0002, 6
    width = math.pi / ways
    sums: dict[tuple[int, int, int], list] = {}
    for pid, path in enumerate(paths):
        if skip(pid):
            continue
        for i, point in enumerate(path):
            axis = _axis(path, i)
            if point[3] or axis is None:
                continue
            total = sums.setdefault((math.floor(point[1] / step_lat), math.floor(point[2] / step_lon), int(axis / width) % ways), [0.0, 0.0, 0, set()])
            total[0] += point[1]
            total[1] += point[2]
            total[2] += 1
            if len(total[3]) <= CORRIDOR_TRIPS:
                total[3].add(pid)
    reach: dict[int, list[tuple[int, int]]] = {}

    def squares(lat: float) -> list[tuple[int, int]]:
        band = round(lat)
        if band not in reach:
            squash = math.cos(math.radians(band))
            rows, columns = math.ceil(SNAP_METRES / (step_lat * 111_195)), math.ceil(SNAP_METRES / (step_lon * 111_195 * squash))
            reach[band] = [
                (r, c) for r in range(-rows, rows + 1) for c in range(-columns, columns + 1)
                if (r * step_lat * 111_195) ** 2 + (c * step_lon * 111_195 * squash) ** 2 <= SNAP_METRES ** 2
            ]
        return reach[band]

    moved: dict[tuple[int, int], tuple[float, float]] = {}
    for pid, path in enumerate(paths):
        if skip(pid):
            continue
        for i, point in enumerate(path):
            axis = _axis(path, i)
            if point[3] or axis is None:
                continue
            row, column, way = math.floor(point[1] / step_lat), math.floor(point[2] / step_lon), int(axis / width) % ways
            lat = lon = 0.0
            count, trips = 0, set()
            for r, c in squares(point[1]):
                for w in (way - 1, way, way + 1):
                    total = sums.get((row + r, column + c, w % ways))
                    if total is not None:
                        lat, lon, count = lat + total[0], lon + total[1], count + total[2]
                        trips |= total[3]
            trips.discard(pid)
            if len(trips) >= CORRIDOR_TRIPS:
                moved[(pid, i)] = (lat / count, lon / count)
    for (pid, i), (lat, lon) in moved.items():
        point = paths[pid][i]
        if _metres(point[1], point[2], lat, lon) > 1:
            tally["points_snapped"] += 1
        point[1], point[2] = lat, lon


def _fill_from_earlier_trips(paths: list[list[list]], skip: Callable[[int], bool], tally: Counter) -> None:
    """
    Two fixes FILL_METRES or more apart are a straight line across whatever was between them. When
    another trip went from near the one to near the other the same way, in about the same time,
    the points it has between are the way it goes, and are put in. Of several such trips the one
    whose way is of the most usual length is taken.
    """
    corridors = _Corridors(paths, skip)
    filled: dict[int, list[list]] = {}
    for pid, path in enumerate(paths):
        if skip(pid):
            continue
        out = [path[0]]
        for a, b in zip(path, path[1:]):
            span = _metres(a[1], a[2], b[1], b[2])
            if span >= FILL_METRES and not (a[3] and b[3]):
                way = _borrowed_way(paths, corridors, pid, a, b, span)
                if way:
                    lengths = [0.0]
                    for p, q in zip([a] + way, way + [b]):
                        lengths.append(lengths[-1] + _metres(p[1], p[2], q[1], q[2]))
                    for k, point in enumerate(way):
                        out.append([a[0] + round((b[0] - a[0]) * lengths[k + 1] / lengths[-1]), point[1], point[2], False])
                    tally["points_filled"] += len(way)
            out.append(b)
        filled[pid] = out
    for pid, out in filled.items():
        paths[pid] = out


def _borrowed_way(paths: list[list[list]], corridors: _Corridors, pid: int, a: list, b: list, span: float) -> list[list] | None:
    heading = math.atan2(b[1] - a[1], (b[2] - a[2]) * math.cos(math.radians(a[1])))
    starts: dict[int, tuple[float, int]] = {}
    for other, i, lat, lon, _, d in corridors.near(a[1], a[2], FILL_NEAR_METRES, pid):
        if other not in starts or d < starts[other][0]:
            starts[other] = (d, i)
    ways = []
    for other, (_, i) in sorted(starts.items(), key=lambda item: item[1][0])[:FILL_CANDIDATES]:
        them = paths[other]
        if i + 2 >= len(them):
            continue
        onward = math.atan2(them[i + 1][1] - them[i][1], (them[i + 1][2] - them[i][2]) * math.cos(math.radians(them[i][1])))
        if abs((onward - heading + math.pi) % (2 * math.pi) - math.pi) > math.pi / 3:
            continue
        length = 0.0
        for j in range(i + 1, len(them)):
            length += _metres(them[j - 1][1], them[j - 1][2], them[j][1], them[j][2])
            if length > 1.5 * span + FILL_NEAR_METRES * 2 or them[j][3]:
                break
            if _metres(them[j][1], them[j][2], b[1], b[2]) <= FILL_NEAR_METRES:
                took, ours = them[j][0] - them[i][0] + 60, b[0] - a[0] + 60
                if j > i + 1 and 0.5 <= took / ours <= 2:
                    ways.append((length, [list(p) for p in them[i + 1:j]]))
                break
    if not ways:
        return None
    usual = sorted(length for length, _ in ways)[len(ways) // 2]
    return min(ways, key=lambda way: (abs(way[0] - usual), -len(way[1])))[1]


def _refine_paths(tracks: list[tuple[int, list[tuple[int, float, float]]]], stays: list[_Stay], moves: list[list], tally: Counter) -> list[tuple]:
    # A flight's few fixes are a line between airports, drawn as an arc by the app: nothing to
    # learn roads from, nothing to straighten, and a turn in the air is a turn.
    flights = sorted((move[0], move[1]) for move in moves if move[3] == "fly")
    flight_starts = [flight[0] for flight in flights]

    def in_the_air(points: list) -> bool:
        if not points:
            return True
        i = bisect.bisect_right(flight_starts, points[-1][0]) - 1
        return i >= 0 and flights[i][1] >= points[0][0]

    airborne = {pid for pid, (_, points) in enumerate(tracks) if in_the_air(points)}

    def flying(pid: int) -> bool:
        return pid in airborne

    starts = [stay.started for stay in stays]
    paths: list[list[list]] = []
    for pid, (offset, points) in enumerate(tracks):
        settled = []
        for t, lat, lon in points:
            i = bisect.bisect_right(starts, t) - 1
            stay = stays[i] if i >= 0 else None
            at_stay = False
            if stay is not None and stay.started + EDGE_SECONDS <= t <= stay.ended - EDGE_SECONDS:
                off = _metres(lat, lon, stay.lat, stay.lon)
                if off <= DRIFT_METRES:
                    if off > 1:
                        tally["points_settled"] += 1
                    lat, lon, at_stay = stay.lat, stay.lon, True
            settled.append([t, lat, lon, at_stay])
        clean = settled if flying(pid) else _despike(settled)
        tally["points_dropped"] += len(settled) - len(clean)
        paths.append(clean)
    _drop_lone_turns(paths, _Corridors(paths, flying), stays, flying, tally)
    for _ in range(SNAP_ROUNDS):
        _snap_to_corridors(paths, flying, tally)
    _fill_from_earlier_trips(paths, flying, tally)
    rows = []
    for (offset, _), path in zip(tracks, paths):
        # A run at one spot keeps its first and last points, so a day played back leaves when it left.
        kept: list[list] = []
        for i, point in enumerate(path):
            spot = (round(point[1], 5), round(point[2], 5))
            same_before = kept and (round(kept[-1][1], 5), round(kept[-1][2], 5)) == spot
            same_after = i + 1 < len(path) and (round(path[i + 1][1], 5), round(path[i + 1][2], 5)) == spot
            if not (same_before and same_after):
                kept.append(point)
        if len({(round(p[1], 5), round(p[2], 5)) for p in kept}) < 2:
            tally["paths_dropped"] += 1
            continue
        started = kept[0][0]
        rows.append((started, kept[-1][0], offset, polyline([(p[1], p[2]) for p in kept]), polyline_numbers([p[0] - started for p in kept]), len(kept), started // 86400))
    return rows


def _refine_moves(moves: list[list], stays: list[_Stay], tally: Counter) -> None:
    starts = [stay.started for stay in stays]
    ends = sorted((stay.ended, i) for i, stay in enumerate(stays))
    end_times = [end for end, _ in ends]

    def snap(move: list, at: int, lat_i: int, candidates: list[_Stay]) -> None:
        # The stay a move leaves or reaches is known by where the export had it: refining may have
        # moved it a kilometre since, and the move goes with it.
        best = min(candidates, key=lambda stay: abs((stay.ended if lat_i == 4 else stay.started) - at), default=None)
        if best is not None and _metres(move[lat_i], move[lat_i + 1], best.lat, best.lon) > 1 and (
            min(_metres(move[lat_i], move[lat_i + 1], *best.seen), _metres(move[lat_i], move[lat_i + 1], best.lat, best.lon)) <= MOVE_SNAP_METRES
        ):
            move[lat_i], move[lat_i + 1] = best.lat, best.lon
            tally["move_ends"] += 1

    for move in moves:
        left = [stays[i] for _, i in ends[bisect.bisect_left(end_times, move[0] - MOVE_SNAP_SECONDS):bisect.bisect_right(end_times, move[0] + MOVE_SNAP_SECONDS)]]
        snap(move, move[0], 4, left)
        arrived = stays[bisect.bisect_left(starts, move[1] - MOVE_SNAP_SECONDS):bisect.bisect_right(starts, move[1] + MOVE_SNAP_SECONDS)]
        snap(move, move[1], 6, arrived)


def timeline_refine(conn: sqlite3.Connection, now: float | None = None) -> dict[str, Any]:
    """
    Works the refined history out again from what the exports said, the whole of it (see
    "timeline: refining"), and says what it changed. The years' bodies are left to
    `timeline_rebuild_years`.
    """
    now = time.time() if now is None else now
    with _timeline_refine_lock:
        stays = [_Stay(*row) for row in conn.execute("SELECT started, ended, utc_offset, lat, lon, place, kind, p FROM visits ORDER BY started, ended")]
        moves = [list(row) for row in conn.execute("SELECT started, ended, utc_offset, mode, lat, lon, to_lat, to_lon, metres, day FROM moves ORDER BY started, ended")]
        tracks = []
        for started, offset, points, times in conn.execute("SELECT started, utc_offset, points, times FROM paths ORDER BY started"):
            seconds = unpolyline_numbers(times)
            tracks.append((offset, [(started + (seconds[i] if i < len(seconds) else seconds[-1] if seconds else 0), lat, lon) for i, (lat, lon) in enumerate(unpolyline(points))]))
        places = _Places(conn, stays)
        if stays:
            _refine_buildings(stays, places)
            _refine_nights(stays, places)
            _refine_unsure(stays, places)
            _refine_thin(stays, places, _NightHomes(stays))
        everywhere = sorted(point for _, points in tracks for point in points)
        thin: set[int] = set()
        if stays:
            homes = _NightHomes(stays)
            stays, thin = _refine_unrecorded(stays, places, moves, everywhere, homes), homes.thin
        tally: Counter = Counter(stay.why for stay in stays if stay.why)
        stays, moves, tally["joined"] = _refine_join(stays, moves, everywhere, thin)
        tally["moves_dropped"] = conn.execute("SELECT COUNT(*) FROM moves").fetchone()[0] - len(moves)
        paths = _refine_paths(tracks, stays, moves, tally)
        _refine_moves(moves, stays, tally)
        with conn:
            for table in REFINED_TABLES:
                conn.execute(f"DELETE FROM {table}")
            conn.executemany("INSERT INTO refined_visits VALUES (?,?,?,?,?,?,?,?,?,?,?)", [
                (s.started, s.ended, s.offset, round(s.lat, 6), round(s.lon, 6), s.place, s.kind, round(s.p, 3), s.started // 86400, s.google if s.why else None, s.why)
                for s in stays
            ])
            conn.executemany("INSERT INTO refined_moves VALUES (?,?,?,?,?,?,?,?,?,?)", [
                (*move[:4], round(move[4], 6), round(move[5], 6), round(move[6], 6), round(move[7], 6), move[8], move[9]) for move in moves
            ])
            conn.executemany("INSERT INTO refined_paths VALUES (?,?,?,?,?,?,?)", paths)
        summary = {
            "version": TIMELINE_REFINE_VERSION, "at": now,
            "moved": {why: tally[why] for why in ("building", "night", "unsure", "thin")}, "inferred": tally["inferred"],
            **{key: tally[key] for key in ("joined", "moves_dropped", "move_ends", "points_settled", "points_dropped", "points_snapped", "points_filled", "paths_dropped")},
        }
        return summary


def timeline_publish(conn: sqlite3.Connection, now: float | None = None) -> tuple[dict[str, Any], list[int]]:
    """
    Refines the history and writes the years the app fetches from it; what refining changed, and
    which years did. `refined` is written only once the years are, and `refining` says one is
    under way, so a relay stopped between the two is put right when it starts again
    (`timeline_refresh`) rather than serving the years as they were.
    """
    timeline_meta_set(conn, "refining", True)
    summary = timeline_refine(conn, now)
    changed = timeline_rebuild_years(conn)
    timeline_meta_set(conn, "refined", summary)
    timeline_meta_set(conn, "refining", False)
    return summary, changed


def timeline_refresh() -> None:
    """
    Refines the history again if the relay now refines it differently from when it last did (a
    newer TIMELINE_REFINE_VERSION), or stopped before the years from the last refining were written.
    """
    conn = timeline_db()
    try:
        done = timeline_meta(conn, "refined") or {}
        stale = done.get("version") != TIMELINE_REFINE_VERSION or timeline_meta(conn, "refining")
        if stale and any(conn.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone() for table in TIMELINE_TABLES):
            summary, changed = timeline_publish(conn)
            log.info("timeline: refined again (version %s): %s, years changed %s", TIMELINE_REFINE_VERSION, summary, changed)
    finally:
        conn.close()


def timeline_account() -> str | None:
    """Who the Drive folder is to be shared with: the key's `client_email`. None when there is no key to read."""
    if _timeline_google["account"] is None:
        try:
            with open(TIMELINE_KEY, encoding="utf-8") as f:
                _timeline_google["account"] = json.load(f).get("client_email")
        except (OSError, ValueError):
            return None
    return _timeline_google["account"]


def timeline_google() -> Any:
    """The read-only Drive session, made on first use from TIMELINE_KEY."""
    if _timeline_google["session"] is None:
        if not os.path.exists(TIMELINE_KEY):
            raise HTTPException(503, {"error": "no_key", "message": "The relay has no service-account key to read Drive with"})
        try:
            creds = service_account.Credentials.from_service_account_file(TIMELINE_KEY, scopes=[DRIVE_SCOPE])
        except (ValueError, KeyError) as e:
            raise HTTPException(503, {"error": "no_key", "message": "The relay's key isn't a service-account key"}) from e
        _timeline_google["account"] = creds.service_account_email
        _timeline_google["session"] = AuthorizedSession(creds)
    return _timeline_google["session"]


def timeline_newest_file(session: Any) -> dict[str, Any] | None:
    """
    The export most recently changed in the Drive folder, or None when it holds none. Raises
    HTTPException with the app's setup problem when Google refuses (see `sheet_problem`).
    """
    account = timeline_account()
    shared = {"supportsAllDrives": "true", "includeItemsFromAllDrives": "true"}
    listing = session.get(DRIVE_API, params={
        "q": f"'{TIMELINE_DRIVE_FOLDER}' in parents and trashed = false", "orderBy": "modifiedTime desc", "pageSize": 100,
        "fields": "files(id,name,size,md5Checksum,modifiedTime)", **shared,
    }, timeout=20)
    if listing.status_code != 200:
        raise HTTPException(*sheet_problem(listing.status_code, _google_json(listing), account))
    for found in _google_json(listing).get("files", []):
        # A file with nothing in it yet is one the phone is still sending: Drive lists it the
        # moment the upload starts, a hundred megabytes before it ends.
        if str(found.get("name", "")).lower().endswith((".json", ".zip")) and _number(found.get("size")) > 0:
            return found
    # A folder the key can't see lists as empty rather than refused: asking for the folder itself tells them apart.
    folder = session.get(f"{DRIVE_API}/{quote(TIMELINE_DRIVE_FOLDER, safe='')}", params={"fields": "id", **shared}, timeout=20)
    if folder.status_code != 200:
        status, detail = sheet_problem(folder.status_code, _google_json(folder), account)
        if detail.get("error") == "not_found":
            detail["error"] = "not_shared"
        raise HTTPException(status, detail)
    return None


def timeline_download(session: Any, found: dict[str, Any], path: str) -> None:
    """Fetches the file's bytes to [path], a megabyte at a time."""
    answer = session.get(f"{DRIVE_API}/{quote(str(found['id']), safe='')}", params={"alt": "media", "supportsAllDrives": "true"}, stream=True, timeout=120)
    if answer.status_code != 200:
        raise HTTPException(*sheet_problem(answer.status_code, _google_json(answer), timeline_account()))
    with open(path, "wb") as out:
        for piece in answer.iter_content(1 << 20):
            out.write(piece)


def timeline_pull(now: float | None = None, again: bool = False) -> dict[str, Any] | None:
    """
    Looks in the Drive folder and reads the newest export when it isn't the one already read
    (or, with [again], even when it is: for after the reading itself has been changed).
    What it read, or None when there was nothing new (or no folder, or a pull already running).
    How it went is kept for `GET /timeline` either way.
    """
    if not TIMELINE_DRIVE_FOLDER or not _timeline_sync_lock.acquire(blocking=False):
        return None
    now = time.time() if now is None else now
    status: dict[str, Any] = {"checked_at": now, "error": None}
    summary = None
    try:
        conn = timeline_db()
        try:
            seen = timeline_meta(conn, "file") or {}
        finally:
            conn.close()
        session = timeline_google()
        found = timeline_newest_file(session)
        if found is None:
            status.update(error="empty", message="The Drive folder has no Timeline export in it yet")
        else:
            stamp = [found.get("id"), found.get("md5Checksum"), found.get("modifiedTime")]
            status["found"] = found.get("name")
            if again or seen.get("stamp") != stamp:
                path = os.path.join(os.path.dirname(TIMELINE_DB) or ".", "timeline-incoming")
                try:
                    timeline_download(session, found, path)
                    summary = timeline_import(path, {"name": found.get("name"), "modified": found.get("modifiedTime"), "size": int(_number(found.get("size"))), "stamp": stamp}, household_zone())
                finally:
                    if os.path.exists(path):
                        os.remove(path)
    except HTTPException as e:
        detail = e.detail if isinstance(e.detail, dict) else {"error": "google_error", "message": str(e.detail)}
        log.warning("timeline: Drive unavailable (%s): %s", e.status_code, detail.get("error"))
        status.update(error=detail.get("error"), message=detail.get("message"), activation_url=detail.get("activation_url"))
    except Exception as e:
        log.exception("timeline: couldn't read the export")
        status.update(error="failed", message=f"Couldn't read the export: {type(e).__name__}")
    finally:
        _timeline_sync_lock.release()
    conn = timeline_db()
    try:
        timeline_meta_set(conn, "drive", status)
    finally:
        conn.close()
    return summary


def timeline_place_lookup(place: str) -> tuple[int, dict[str, Any]]:
    """Asks Google what a place id is. The status, and the body when it is JSON."""
    answer = requests.get(
        f"{PLACES_API}/{quote(place, safe='')}", params={"languageCode": "en"},
        headers={"X-Goog-Api-Key": TIMELINE_PLACES_KEY, "X-Goog-FieldMask": PLACES_FIELDS}, timeout=10,
    )
    body = _google_json(answer)
    return answer.status_code, body if isinstance(body, dict) else {}


def place_locality(components: Any) -> tuple[str | None, str | None, str | None]:
    """
    The town, the state or province (its short form, "IL") and the country (its code, "US") from a
    place's `addressComponents`. A place with no `locality` (much of the UK, the countryside) goes
    by the next thing down that names where it is.
    """
    found: dict[str, dict[str, Any]] = {}
    for part in components if isinstance(components, list) else []:
        if isinstance(part, dict):
            for kind in part.get("types") or []:
                found.setdefault(str(kind), part)
    city = next((found[k].get("longText") for k in ("locality", "postal_town", "sublocality", "administrative_area_level_3", "administrative_area_level_2") if k in found), None)
    region = found.get("administrative_area_level_1", {}).get("shortText")
    country = found.get("country", {}).get("shortText")
    return city, region, country


def timeline_month(now: float) -> str:
    return time.strftime("%Y-%m", time.gmtime(now))


def timeline_name_places(now: float | None = None, limit: int = TIMELINE_PLACES_PER_RUN, pause: float = 0.05) -> int:
    """
    Names places that have never been asked about, the ones most time was spent at first, until
    [limit] or the month's allowance runs out. Each is asked once: a place Google no longer knows
    is marked gone and not asked again. How many it named.
    """
    if not TIMELINE_PLACES_KEY:
        return 0
    now = time.time() if now is None else now
    conn = timeline_db()
    try:
        asked = timeline_meta(conn, "asked") or {}
        if asked.get("month") != timeline_month(now):
            asked = {"month": timeline_month(now), "count": 0}
        room = min(limit, TIMELINE_PLACES_MONTHLY - int(asked.get("count") or 0))
        if room <= 0:
            return 0
        wanted = [row[0] for row in conn.execute(
            "SELECT p.place FROM places p LEFT JOIN (SELECT place, SUM(ended - started) AS spent FROM visits GROUP BY place) v ON v.place = p.place"
            " WHERE p.asked IS NULL ORDER BY v.spent DESC LIMIT ?", (room,),
        )]
        named, error = 0, None
        for place in wanted:
            try:
                status, body = timeline_place_lookup(place)
            except Exception as e:
                error = {"error": "google_error", "message": f"Couldn't reach Google: {type(e).__name__}"}
                break
            asked["count"] = int(asked.get("count") or 0) + 1
            if status == 200:
                display = body.get("displayName") if isinstance(body.get("displayName"), dict) else {}
                kinds = body.get("types") if isinstance(body.get("types"), list) else []
                conn.execute(
                    "UPDATE places SET name=COALESCE(?, name), address=COALESCE(?, address), type=?, asked=?, status='ok', city=?, region=?, country=? WHERE place=?",
                    (display.get("text"), body.get("shortFormattedAddress") or body.get("formattedAddress"), body.get("primaryType") or (kinds[0] if kinds else None), now,
                     *place_locality(body.get("addressComponents")), place),
                )
                named += 1
            elif status in (400, 404):
                # An id Google has retired. The visit keeps its spot on the map and goes by its town.
                conn.execute("UPDATE places SET asked=?, status='gone' WHERE place=?", (now, place))
            else:
                problem = body.get("error") if isinstance(body.get("error"), dict) else {}
                error = {"error": "api_disabled" if status == 403 else "google_error", "message": str(problem.get("message") or f"Google answered {status}")}
                break
            if pause:
                time.sleep(pause)
        conn.commit()
        timeline_meta_set(conn, "asked", asked)
        timeline_meta_set(conn, "naming", {"at": now, **(error or {"error": None})})
        if named:
            timeline_meta_set(conn, "places_version", int(timeline_meta(conn, "places_version") or 0) + 1)
            # What a place is (a flat, a coffee shop) is some of what refining goes on.
            timeline_publish(conn, now)
        if error:
            log.warning("timeline: place names unavailable: %s", error["message"])
        return named
    finally:
        conn.close()


def timeline_check() -> dict[str, Any] | None:
    """
    A look in the Drive folder, then some more places named, by one caller at a time: the hourly
    check and a phone's "look now" (or two phones') must not both be naming places, or each asks
    Google about the same ones and the month's allowance is spent twice. None when one is already
    under way.
    """
    if not _timeline_check_lock.acquire(blocking=False):
        return None
    try:
        return {"read": timeline_pull(), "named": timeline_name_places()}
    finally:
        _timeline_check_lock.release()


def timeline_forever() -> None:
    """Every TIMELINE_CHECK_SECONDS: a look in the Drive folder, then some more places named."""
    time.sleep(30)
    try:
        timeline_refresh()
    except Exception:
        log.exception("timeline: refining failed")
    while True:
        try:
            timeline_check()
        except Exception:
            log.exception("timeline: check failed")
        time.sleep(TIMELINE_CHECK_SECONDS)


def timeline_manifest(conn: sqlite3.Connection, now: float | None = None, user: str | None = None) -> dict[str, Any]:
    """
    What the app asks first: which years there are and each one's `etag`, how far the names have
    got, and how the last look in Drive went. [user] is who asked, said back so the app knows whose
    copy the one it keeps is.
    """
    now = time.time() if now is None else now
    years = [
        {"year": year, "etag": etag, "visits": visits, "moves": moves, "points": points, "bytes": size}
        for year, etag, visits, moves, points, size in conn.execute("SELECT year, etag, visits, moves, points, LENGTH(body) FROM years ORDER BY year")
    ]
    first, last = conn.execute("SELECT MIN(started), MAX(ended) FROM (SELECT started, ended FROM visits UNION ALL SELECT started, ended FROM moves)").fetchone()
    total, named, gone = conn.execute("SELECT COUNT(*), COUNT(name), SUM(status = 'gone') FROM places").fetchone()
    asked = timeline_meta(conn, "asked") or {}
    naming = timeline_meta(conn, "naming") or {}
    drive = timeline_meta(conn, "drive") or {}
    source = timeline_meta(conn, "file") or {}
    return {
        "user": user,
        "years": years,
        "first": first,
        "last": last,
        "places": {"version": int(timeline_meta(conn, "places_version") or 0), "total": total, "named": named, "gone": int(gone or 0)},
        "file": {key: source.get(key) for key in ("name", "modified", "size", "ingested_at", "visits", "moves", "points", "days")} if source else None,
        "drive": {
            "configured": bool(TIMELINE_DRIVE_FOLDER), "service_account": timeline_account(), "checked_at": drive.get("checked_at"),
            "error": drive.get("error"), "message": drive.get("message"), "activation_url": drive.get("activation_url"),
            "next_check_at": (drive.get("checked_at") or now) + TIMELINE_CHECK_SECONDS if TIMELINE_DRIVE_FOLDER else None,
        },
        "naming": {
            "configured": bool(TIMELINE_PLACES_KEY), "error": naming.get("error"), "message": naming.get("message"),
            "asked_this_month": int(asked.get("count") or 0) if asked.get("month") == timeline_month(now) else 0, "monthly_cap": TIMELINE_PLACES_MONTHLY,
        },
        "refined": timeline_meta(conn, "refined"),
        "syncing": _timeline_sync_lock.locked() or _timeline_check_lock.locked(),
    }


def timeline_may_read(profile: dict[str, Any]) -> bool:
    """Whether Frigate's `/api/profile` answer is someone allowed the timeline: a listed user, or with no list an admin."""
    if TIMELINE_USERS:
        return str(profile.get("username", "")) in TIMELINE_USERS
    return profile.get("role") == "admin"


def require_timeline_user(request: Request) -> str:
    """A signed-in Frigate user who may read the timeline (see "timeline"); their username."""
    profile = frigate_profile(request)
    if profile is None:
        raise HTTPException(status_code=401, detail="Frigate session required")
    if not timeline_may_read(profile):
        raise HTTPException(403, {"error": "not_allowed", "message": "This account can't see the timeline"})
    return str(profile.get("username", "?"))


@app.get("/timeline")
def get_timeline(request: Request, response: Response) -> dict[str, Any]:
    """The timeline's years and how its upkeep is going (see "timeline"). A Frigate admin, or a user in TIMELINE_USERS."""
    response.headers["Cache-Control"] = TIMELINE_CACHE_CONTROL
    user = require_timeline_user(request)
    conn = timeline_db()
    try:
        return timeline_manifest(conn, user=user)
    finally:
        conn.close()


@app.get("/timeline/year/{year}")
def get_timeline_year(year: int, request: Request) -> Response:
    """One year's visits, moves and paths (see `timeline_year_body`), gzipped for a caller that says it reads gzip."""
    require_timeline_user(request)
    conn = timeline_db()
    try:
        row = conn.execute("SELECT etag, body FROM years WHERE year=?", (year,)).fetchone()
    finally:
        conn.close()
    if row is None:
        raise HTTPException(status_code=404, detail="No such year")
    headers = {"Cache-Control": TIMELINE_CACHE_CONTROL, "ETag": f'"{row[0]}"'}
    if "gzip" in (request.headers.get("accept-encoding") or "").lower():
        return Response(content=bytes(row[1]), media_type="application/json", headers={**headers, "Content-Encoding": "gzip"})
    return Response(content=gzip.decompress(row[1]), media_type="application/json", headers=headers)


@app.get("/timeline/places")
def get_timeline_places(request: Request, response: Response) -> dict[str, Any]:
    """Every place visited, with the name Google gave it once asked. `version` is the manifest's, so the app fetches this only when it moved."""
    response.headers["Cache-Control"] = TIMELINE_CACHE_CONTROL
    require_timeline_user(request)
    conn = timeline_db()
    try:
        return {
            "version": int(timeline_meta(conn, "places_version") or 0),
            "places": [
                {"id": place, "lat": round(lat, 6), "lon": round(lon, 6), "kind": kind, "name": name, "address": address, "type": kind_of, "city": city, "region": region, "country": country}
                for place, lat, lon, kind, name, address, kind_of, city, region, country in conn.execute(
                    "SELECT place, lat, lon, kind, name, address, type, city, region, country FROM places ORDER BY place")
            ],
        }
    finally:
        conn.close()


@app.post("/timeline/sync")
def post_timeline_sync(request: Request, response: Response) -> dict[str, Any]:
    """Looks in the Drive folder now rather than at the next check. Answers at once with the manifest; `syncing` says a check is under way."""
    response.headers["Cache-Control"] = TIMELINE_CACHE_CONTROL
    user = require_timeline_user(request)
    # Started whether or not one is running: `timeline_check` itself lets only one through.
    if TIMELINE_DRIVE_FOLDER or TIMELINE_PLACES_KEY:
        threading.Thread(target=timeline_check, name="timeline-sync-now", daemon=True).start()
    conn = timeline_db()
    try:
        return timeline_manifest(conn, user=user)
    finally:
        conn.close()


if __name__ == "__main__":
    # `python relay.py tesla-register` / `tesla-link` / `tesla-telemetry-config` / `tesla-telemetry-send` /
    # `tesla-telemetry-status` / `parking-check` / `timeline-import FILE` / `timeline-pull` / `timeline-refine` /
    # `timeline-reread`, run in the relay's container.
    import sys

    CONN = db()
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "timeline-import" and len(sys.argv) > 2:
        # An export copied to the box by hand, for a first load before the Drive folder is set up.
        print(json.dumps(timeline_import(sys.argv[2], zone=household_zone()), indent=2))
        sys.exit(0)
    if command == "timeline-pull":
        print(json.dumps(timeline_check(), indent=2))
        sys.exit(0)
    if command == "timeline-refine":
        # The history refined again from what the exports said, now.
        conn = timeline_db()
        summary, changed = timeline_publish(conn)
        print(json.dumps({**summary, "years_changed": changed}, indent=2))
        conn.close()
        sys.exit(0)
    if command == "timeline-reread":
        # The export already read, read again: after a change to how it is read.
        print(json.dumps({"read": timeline_pull(again=True), "named": timeline_name_places()}, indent=2))
        sys.exit(0)
    if not tesla_on():
        sys.exit("Set TESLA_CLIENT_ID, TESLA_CLIENT_SECRET and TESLA_PUBLIC_URL first (tesla.env).")
    if command == "tesla-register":
        print(json.dumps(tesla_register(), indent=2))
    elif command == "tesla-link":
        print(f"Open within {TESLA_LINK_SECONDS // 60} minutes, signed in as the Tesla account that owns the car:\n\n{tesla_link_url()}")
    elif command == "tesla-telemetry-config":
        # What the cars are to be told about the telemetry server, for `scripts/tesla-sign` to sign (docs/tesla.md, "Telemetry").
        print(json.dumps(tesla_telemetry_config(time.time())))
    elif command == "tesla-telemetry-send":
        # The signature `scripts/tesla-sign` made of that, on stdin.
        token = sys.stdin.read().strip()
        if token.count(".") != 2:
            sys.exit("Pipe in the JWS scripts/tesla-sign printed.")
        print(json.dumps(tesla_telemetry_send(token), indent=2))
    elif command == "charging-history":
        # Reads Tesla's receipts now, rather than at the next check.
        print(json.dumps(charging_history_sync(time.time()), indent=2))
    elif command == "charging-summary":
        print(json.dumps(charging_summary(time.time(), True), indent=2, default=str))
    elif command == "tesla-telemetry-status":
        print(json.dumps(tesla_telemetry_status(), indent=2, default=str))
    elif command == "parking-check":
        # The streets PARKING_FILE names as the relay found them, and where each car is by them now
        # (asking Tesla, a call per car). Pushes nothing.
        now = time.time()
        stretches = parking_stretches(parking_config(), state_get(HOME_KEY), now)
        report = parking_status(now)
        for name in TESLA_CARS:
            asked, where = tesla_ask(name, now, "parking-check")
            match = parking_match(where, stretches) if where and not where.get("asleep") else None
            report.setdefault("now", {})[name] = {"tesla": where, "match": match}
        for street in report["streets"]:
            street.pop("line", None)  # the points, for the map: their count says enough here
        print(json.dumps(report, indent=2, default=str))
    else:
        sys.exit("usage: python relay.py tesla-register | tesla-link | tesla-telemetry-config | tesla-telemetry-send | tesla-telemetry-status"
                 " | parking-check | timeline-import FILE | timeline-pull | timeline-refine | timeline-reread")
