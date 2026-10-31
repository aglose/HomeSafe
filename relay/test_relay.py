"""
Offline checks for the relay's pure decisions, and for its device table against a scratch SQLite
database. Runs with nothing but the standard library:

    python3 -m unittest relay/test_relay.py

The web framework, Firebase and HTTP client are stubbed so `relay` imports without its
requirements; every test here calls a function that touches none of them.
"""

import json
import os
import socket
import sys
import threading
import time
import types
import unittest


def _stub(name: str, **attrs) -> None:
    module = types.ModuleType(name)
    for key, value in attrs.items():
        setattr(module, key, value)
    sys.modules.setdefault(name, module)


class _App:
    """Stands in for FastAPI: every route decorator is a no-op."""

    def __init__(self, *args, **kwargs):
        pass

    def __getattr__(self, name):
        return lambda *args, **kwargs: (lambda fn: fn)


class _HTTPException(Exception):
    def __init__(self, status_code, detail=None):
        super().__init__(detail)
        self.status_code, self.detail = status_code, detail


class _FastApiResponse:
    def __init__(self, content=None, status_code=200, media_type=None, headers=None):
        self.body, self.status_code, self.media_type = content, status_code, media_type


_stub("fastapi", FastAPI=_App, HTTPException=_HTTPException, Request=object, Response=_FastApiResponse)
_stub("pydantic", BaseModel=object)
_stub("requests", get=None, post=None)
_stub("google")
_stub("google.auth")
_stub("google.auth.transport")
_stub("google.auth.transport.requests", AuthorizedSession=object)
_stub("google.oauth2", service_account=object)
os.environ.setdefault("FCM_PROJECT", "test")


def _load_relay():
    """By file path: from the repo root `relay` names this directory, not the module."""
    import importlib.util

    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "relay.py")
    spec = importlib.util.spec_from_file_location("homesafe_relay", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


relay = _load_relay()


def event(box, path, kind="object"):
    """A `/api/events/{id}` body: `path` is bottom-centre points, timestamps don't matter here."""
    return {"data": {"type": kind, "box": box, "path_data": [[[x, y], 0.0] for x, y in path]}}


# Real shapes from the Front Yard camera, 2026-09-15.
PARKED_BOX = [0.1796875, 0.4222, 0.2046875, 0.2306]
# Andrew's Tesla in the driveway: six looks, all within a car's width of each other.
PARKED_PATH = [(0.3125, 0.6056), (0.3375, 0.5806), (0.3406, 0.5833), (0.3406, 0.5806), (0.3125, 0.6056), (0.3375, 0.5806)]
# The two-point path every flicker re-detection carries.
FLICKER_PATH = [(0.3406, 0.5833), (0.3406, 0.5806)]
# A car crossing the frame.
DRIVE_PATH = [(0.10 + i * 0.0447, 0.30) for i in range(20)]
# Front Yard, 2026-10-05, event 1791241252.491864-h433y2: Andrew's Tesla up the street, round, and
# down to the garage in 32 seconds, then the box flickering there for hours. The best frame is the
# close-up by the garage, so the box is over a third of the frame tall. The same path as the app's
# `TrackedCars.andrewsPath`.
GARAGE_BOX = [0.0453, 0.1528, 0.2828, 0.3625]
GARAGE_ARRIVAL = [
    (0.3922, 0.0625), (0.4328, 0.0653), (0.5094, 0.1069), (0.5859, 0.1361), (0.6883, 0.1667), (0.7484, 0.2194), (0.8773, 0.3278),
    (0.9398, 0.3389), (0.8938, 0.2764), (0.8227, 0.2319), (0.7516, 0.1917), (0.6852, 0.2056), (0.6211, 0.2181), (0.5617, 0.2333),
    (0.5055, 0.2639), (0.4453, 0.2917), (0.3859, 0.3222), (0.3273, 0.3472), (0.2695, 0.3861), (0.2219, 0.4347), (0.1812, 0.4792),
    (0.2359, 0.3972), (0.1812, 0.4833), (0.2055, 0.3972), (0.1844, 0.4847),
]
GARAGE_FLICKERS = [
    (0.2594, 0.3944), (0.1797, 0.4861), (0.2078, 0.4319), (0.1883, 0.4875), (0.2039, 0.4153), (0.1938, 0.5028),
    (0.2008, 0.4069), (0.1805, 0.4847), (0.1906, 0.4222), (0.1898, 0.4861), (0.2094, 0.3806), (0.2117, 0.4639),
]


class IsStill(unittest.TestCase):
    def test_a_parked_car_is_still(self):
        self.assertTrue(relay.is_still(event(PARKED_BOX, PARKED_PATH)))
        self.assertTrue(relay.is_still(event(PARKED_BOX, FLICKER_PATH)))

    def test_fewer_than_four_points_is_still(self):
        self.assertTrue(relay.is_still(event(PARKED_BOX, [(0.34, 0.58)])))
        self.assertTrue(relay.is_still(event(PARKED_BOX, [])))
        self.assertTrue(relay.is_still({"data": {"box": PARKED_BOX}}))
        # The box flipping from part of the car to all of it: one jump of a fifth of the frame, no journey.
        self.assertTrue(relay.is_still(event([0.30, 0.41, 0.08, 0.17], [(0.34, 0.58), (0.34, 0.58), (0.53, 0.60)])))
        self.assertFalse(relay.is_still(event([0.30, 0.41, 0.08, 0.17], [(0.10, 0.58), (0.30, 0.58), (0.50, 0.58), (0.70, 0.58)])))

    def test_a_car_driving_through_is_not_still(self):
        self.assertFalse(relay.is_still(event([0.62, 0.24, 0.14, 0.10], DRIVE_PATH)))

    def test_no_box_is_never_still(self):
        self.assertFalse(relay.is_still(event(None, FLICKER_PATH)))
        self.assertFalse(relay.is_still({"data": {"path_data": []}}))
        self.assertFalse(relay.is_still({}))

    def test_a_stolen_tracker_burst_does_not_make_a_parked_car_moving(self):
        # Twelve jitter points at the spot, then four far-away ones as a passer-by takes the id.
        burst = [(0.84, 0.54), (0.85, 0.40)] * 6 + [(0.60, 0.30), (0.45, 0.28), (0.30, 0.25), (0.15, 0.22)]
        self.assertTrue(relay.is_still(event([0.75, 0.34, 0.18, 0.21], burst)))

    def test_an_arrival_that_ends_close_to_the_camera_moved_however_large_its_best_frame_box(self):
        # 22 of the 25 points are within the box's 0.36 of the median; the car crossed 0.76 of the frame.
        self.assertGreater(max(GARAGE_BOX[2:]), relay.STILL_RADIUS_CAP)
        self.assertFalse(relay.is_still(event(GARAGE_BOX, GARAGE_ARRIVAL)))
        self.assertFalse(relay.is_still(event(GARAGE_BOX, GARAGE_ARRIVAL + GARAGE_FLICKERS)))

    def test_sitting_afterwards_does_not_undo_a_drive(self):
        # Every flicker of the box adds a point at the garage: 12 in four and a half hours. Three
        # times that and the points there are 70% of the path, the drive in among them.
        for times in (3, 20):
            self.assertFalse(relay.is_still(event(GARAGE_BOX, GARAGE_ARRIVAL + GARAGE_FLICKERS * times)), times)

    def test_a_car_that_came_sat_and_left_in_one_event_moved(self):
        leaving = GARAGE_ARRIVAL[20::-1]
        self.assertFalse(relay.is_still(event(GARAGE_BOX, GARAGE_ARRIVAL + GARAGE_FLICKERS * 20 + leaving)))

    def test_a_car_first_seen_parked_is_still_though_its_path_ends_in_a_few_points_of_travel(self):
        # A stolen tracker or its own leaving: the path can't tell, so the points at the spot decide.
        leaving = GARAGE_ARRIVAL[20:8:-1]
        self.assertTrue(relay.is_still(event(GARAGE_BOX, GARAGE_FLICKERS * 4 + leaving)))

    def test_a_box_jumping_between_two_places_is_not_travel(self):
        # 0.12 apart under a 0.11 box: three points here and two there aren't gathered, but have gone nowhere.
        here, there = (0.47, 0.03), (0.35, 0.02)
        self.assertFalse(relay.gathered([here, here, there, here, there], 0.11))
        self.assertTrue(relay.is_still(event([0.41, 0.0, 0.11, 0.05], [here, here, there, here, there, here, there, there])))

    def test_a_car_parked_close_to_the_camera_stays_still(self):
        self.assertTrue(relay.is_still(event(GARAGE_BOX, GARAGE_ARRIVAL[-4:] + GARAGE_FLICKERS)))

    def test_a_small_box_is_judged_by_its_own_size(self):
        # A path that strays 0.15 from its median: still under a 0.21 box, moved under a 0.12 one.
        strays = [(0.50, 0.30)] * 3 + [(0.65, 0.30)] * 2
        self.assertTrue(relay.is_still(event([0.75, 0.34, 0.18, 0.21], strays)))
        self.assertFalse(relay.is_still(event([0.44, 0.18, 0.12, 0.12], strays)))


class MotionVerdict(unittest.TestCase):
    def setUp(self):
        self.events = {}
        self._real = relay.event_detail
        relay.event_detail = lambda event_id: self.events.get(event_id)

    def tearDown(self):
        relay.event_detail = self._real

    def item(self, objects, detections, ended=True, age=5.0):
        now = time.time()
        return {
            "id": "r1",
            "start_time": now - age,
            "end_time": now if ended else None,
            "data": {"objects": objects, "detections": detections},
        }

    def test_a_person_pushes_whatever_the_cars_are_doing(self):
        self.events["c"] = event(PARKED_BOX, FLICKER_PATH)
        self.assertEqual("push", relay.motion_verdict(self.item(["person", "car"], ["p", "c"], ended=False)))

    def test_a_car_that_moved_pushes(self):
        self.events["c"] = event([0.62, 0.24, 0.14, 0.10], DRIVE_PATH)
        self.assertEqual("push", relay.motion_verdict(self.item(["car", "car-verified"], ["c"], ended=False)))

    def test_a_car_that_drove_in_and_parked_by_the_camera_pushes(self):
        self.events["c"] = event(GARAGE_BOX, GARAGE_ARRIVAL)
        self.assertEqual("push", relay.motion_verdict(self.item(["car"], ["c"], ended=False)))
        self.assertEqual("push", relay.motion_verdict(self.item(["car"], ["c"], ended=True)))

    def test_a_still_car_waits_while_the_alert_is_open_and_is_skipped_once_it_ends(self):
        self.events["c"] = event(PARKED_BOX, FLICKER_PATH)
        self.assertEqual("wait", relay.motion_verdict(self.item(["car"], ["c"], ended=False)))
        self.assertEqual("skip", relay.motion_verdict(self.item(["car"], ["c"], ended=True)))

    def test_an_open_alert_past_the_wait_cap_is_judged_as_it_stands(self):
        self.events["c"] = event(PARKED_BOX, FLICKER_PATH)
        self.assertEqual("skip", relay.motion_verdict(self.item(["car"], ["c"], ended=False, age=relay.MOTION_WAIT_CAP_SECONDS + 1)))

    def test_several_still_cars_are_skipped_but_one_mover_among_them_pushes(self):
        self.events["a"] = event(PARKED_BOX, FLICKER_PATH)
        self.events["b"] = event([0.62, 0.41, 0.20, 0.20], PARKED_PATH)
        self.assertEqual("skip", relay.motion_verdict(self.item(["car", "car"], ["a", "b"])))
        self.events["b"] = event([0.62, 0.24, 0.14, 0.10], DRIVE_PATH)
        self.assertEqual("push", relay.motion_verdict(self.item(["car", "car"], ["a", "b"])))

    def test_anything_that_cannot_be_judged_pushes(self):
        self.assertEqual("push", relay.motion_verdict(self.item(["car"], ["missing"])))
        self.events["api"] = event(PARKED_BOX, FLICKER_PATH, kind="api")
        self.assertEqual("push", relay.motion_verdict(self.item(["car"], ["api"])))
        self.assertEqual("push", relay.motion_verdict(self.item([], [])))
        self.assertEqual("push", relay.motion_verdict(self.item(["car"], [])))


class QuietHours(unittest.TestCase):
    # 2026-01-15 06:30 UTC: 22:30 the evening before in Los Angeles (PST, UTC-8), 07:30 in Berlin (CET, UTC+1).
    NOW = 1768458600.0

    def test_a_window_that_wraps_midnight(self):
        self.assertTrue(relay.in_quiet_hours(22 * 60, 7 * 60, 23 * 60))
        self.assertTrue(relay.in_quiet_hours(22 * 60, 7 * 60, 0))
        self.assertTrue(relay.in_quiet_hours(22 * 60, 7 * 60, 6 * 60 + 59))
        self.assertFalse(relay.in_quiet_hours(22 * 60, 7 * 60, 7 * 60), "the end minute is outside")
        self.assertFalse(relay.in_quiet_hours(22 * 60, 7 * 60, 12 * 60))

    def test_a_daytime_window(self):
        self.assertTrue(relay.in_quiet_hours(13 * 60, 15 * 60, 13 * 60))
        self.assertFalse(relay.in_quiet_hours(13 * 60, 15 * 60, 15 * 60))
        self.assertFalse(relay.in_quiet_hours(13 * 60, 15 * 60, 12 * 60))

    def test_an_unset_or_empty_window_is_never_quiet(self):
        self.assertFalse(relay.in_quiet_hours(None, None, 0))
        self.assertFalse(relay.in_quiet_hours(22 * 60, None, 23 * 60))
        self.assertFalse(relay.in_quiet_hours(8 * 60, 8 * 60, 8 * 60))

    def test_the_phones_own_clock_decides(self):
        self.assertEqual(22 * 60 + 30, relay.local_minute(self.NOW, "America/Los_Angeles", None))
        self.assertEqual(7 * 60 + 30, relay.local_minute(self.NOW, "Europe/Berlin", None))
        night = (22 * 60, 7 * 60)
        self.assertTrue(relay.silenced(False, *night, "America/Los_Angeles", -480, self.NOW))
        self.assertFalse(relay.silenced(False, *night, "Europe/Berlin", 60, self.NOW))

    def test_an_unknown_zone_falls_back_to_the_reported_offset(self):
        self.assertEqual(22 * 60 + 30, relay.local_minute(self.NOW, "Not/A_Zone", -480))
        self.assertEqual(22 * 60 + 30, relay.local_minute(self.NOW, None, -480))
        self.assertIsNone(relay.local_minute(self.NOW, None, None))
        self.assertFalse(relay.silenced(False, 0, 23 * 60 + 59, None, None, self.NOW), "no clock to read: never quiet")

    def test_only_away_silences_every_ordinary_alert(self):
        self.assertTrue(relay.silenced(True, None, None, None, None, self.NOW))

    def test_an_existing_database_gains_the_columns_and_keeps_its_phones(self):
        import sqlite3
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "relay.db")
            old = sqlite3.connect(path)
            old.execute(
                "CREATE TABLE devices ("
                " device_id TEXT PRIMARY KEY, token TEXT UNIQUE, platform TEXT, name TEXT, created REAL, last_seen REAL,"
                " away INTEGER NOT NULL DEFAULT 0, away_updated REAL, quiet_familiar INTEGER NOT NULL DEFAULT 0,"
                " build TEXT NOT NULL DEFAULT 'unknown', secret TEXT, away_pending_since REAL, away_pending_dwell REAL)"
            )
            old.execute("INSERT INTO devices (device_id, token, platform, name, away) VALUES ('d1', 't1', 'android', 'Pixel', 1)")
            old.commit()
            old.close()
            real, relay.DB_PATH = relay.DB_PATH, path
            try:
                conn = relay.db()
                row = conn.execute("SELECT name, away, quiet_start, quiet_end, only_away, tz, utc_offset FROM devices").fetchone()
                conn.close()
            finally:
                relay.DB_PATH = real
            self.assertEqual(("Pixel", 1, None, None, 0, None, None), row)


class Devices(unittest.TestCase):
    """The tracked phones, the presence snapshot and device removal, against a scratch database."""

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._path, self._conn, self._auth, self._profile = relay.DB_PATH, relay.CONN, relay.authenticate, relay.frigate_profile
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()
        # Stands in for the cookie: a signed-in user, who may act on any device.
        relay.authenticate = lambda request, device=None: "andrew"
        relay.frigate_profile = lambda request: None

    def tearDown(self):
        relay.CONN.close()
        relay.DB_PATH, relay.CONN, relay.authenticate, relay.frigate_profile = self._path, self._conn, self._auth, self._profile
        self._dir.cleanup()

    def add(self, device_id, name, platform="android", build="release", hardware_id="hw", emulator=False, last_seen=None):
        last_seen = time.time() if last_seen is None else last_seen
        relay.with_db(lambda c: (c.execute(
            "INSERT INTO devices (device_id, platform, name, created, last_seen, build, hardware_id, emulator) VALUES (?,?,?,?,?,?,?,?)",
            (device_id, platform, name, last_seen, last_seen, build, hardware_id, int(emulator)),
        ), c.commit()))

    def household(self):
        """The Pixel and the iPhone, release builds on real phones, and the installs that must never count."""
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw")
        self.add("iphone", "Apple iPhone", platform="ios", hardware_id="iphone-hw")
        self.add("pixel-debug", "Google Pixel 10 Pro XL", build="debug", hardware_id="pixel-debug-hw")
        self.add("emulator", "Google sdk_gphone64_arm64", hardware_id="emu-hw", emulator=True)
        self.add("old-app", "Google Pixel 10 Pro XL", hardware_id=None)
        self.add("iphone-debug", "Apple iPhone", platform="ios", build="debug", hardware_id="iphone-hw-2")
        relay.assign_tracked_phones()

    def away(self, device_id, away=True, at=5000.0):
        relay.with_db(lambda c: (c.execute("UPDATE devices SET away=?, away_updated=? WHERE device_id=?", (int(away), at, device_id)), c.commit()))

    def register(self, device_id, hardware_id, platform="android", build="release", emulator=False):
        device = types.SimpleNamespace(
            device_id=device_id, token=None, platform=platform, name="Phone", quiet_familiar=False, build=build, quiet_start=None,
            quiet_end=None, only_away=False, tz=None, utc_offset=None, hardware_id=hardware_id, emulator=emulator,
        )
        return relay.register(device, types.SimpleNamespace(headers={}))

    def ids(self, this_device=None):
        return [d["id"] for d in relay.presence_snapshot(this_device)["devices"]]

    def test_only_one_release_phone_of_each_platform_is_listed_android_first(self):
        self.household()
        self.assertEqual(["pixel", "iphone"], self.ids())
        self.assertEqual({"android": "pixel", "ios": "iphone"}, relay.assign_tracked_phones())

    def test_the_house_is_empty_once_both_phones_are_away_and_not_before(self):
        self.household()
        self.away("pixel", at=5000.0)
        self.away("pixel-debug")
        self.away("emulator")
        self.assertFalse(relay.presence_snapshot()["everyone_away"])
        self.assertIsNone(relay.away_since())
        self.away("iphone", at=6000.0)
        self.assertTrue(relay.presence_snapshot()["everyone_away"])
        self.assertEqual(6000.0, relay.away_since(), "when the last one left")
        self.away("old-app", away=False)
        self.away("iphone-debug", away=False)
        self.assertEqual(6000.0, relay.away_since(), "an install that isn't tracked has no say")

    def test_one_phone_alone_decides_while_it_is_the_only_one_tracked(self):
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw")
        self.add("iphone-debug", "Apple iPhone", platform="ios", build="debug", hardware_id="iphone-hw")
        relay.assign_tracked_phones()
        self.away("pixel")
        self.assertTrue(relay.presence_snapshot()["everyone_away"])

    def test_nothing_tracked_is_never_away(self):
        self.add("emulator", "Google sdk_gphone64_arm64", hardware_id="emu-hw", emulator=True)
        self.away("emulator")
        self.assertFalse(relay.presence_snapshot()["everyone_away"])
        self.assertIsNone(relay.away_since())

    def test_an_install_that_is_not_listed_is_told_why(self):
        self.household()
        self.add("second-pixel", "Google Pixel 9", hardware_id="other-hw")
        reasons = {d: relay.presence_snapshot(d)["not_tracked"] for d in ("pixel", "pixel-debug", "emulator", "old-app", "second-pixel", "nobody")}
        self.assertEqual(
            {"pixel": None, "pixel-debug": "debug", "emulator": "emulator", "old-app": "old_app", "second-pixel": "taken", "nobody": "unregistered"},
            reasons,
        )
        self.assertEqual([True, False], [d["this_device"] for d in relay.presence_snapshot("pixel")["devices"]])

    def test_a_reinstall_takes_over_its_old_row_and_its_place(self):
        self.register("pixel-1", "pixel-hw")
        relay.set_presence("pixel-1", types.SimpleNamespace(away=True, source="manual", dwell_seconds=0), request=None)
        self.register("pixel-2", "pixel-hw")
        snapshot = relay.presence_snapshot("pixel-2")
        self.assertEqual(["pixel-2"], [d["id"] for d in snapshot["devices"]])
        self.assertTrue(snapshot["devices"][0]["away"], "the reinstall keeps the presence it had")
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT count(*) FROM devices").fetchone()[0]))

    def test_a_second_release_phone_waits_while_the_first_is_in_use_and_takes_over_once_it_goes_quiet(self):
        week = relay.TRACKED_STALE_SECONDS
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw", last_seen=time.time() - 3600)
        self.assertEqual(["pixel"], self.ids())
        self.register("new-pixel", "new-hw")
        self.assertEqual(["pixel"], self.ids(), "two phones in use can't take turns")
        relay.with_db(lambda c: (c.execute("UPDATE devices SET last_seen=? WHERE device_id='pixel'", (time.time() - week - 60,)), c.commit()))
        self.assertEqual(["new-pixel"], self.ids())

    def test_a_stale_phone_keeps_its_place_when_nothing_fresher_wants_it(self):
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw", last_seen=time.time() - relay.TRACKED_STALE_SECONDS - 60)
        self.assertEqual(["pixel"], self.ids())

    def test_removing_the_tracked_phone_hands_its_place_to_the_other(self):
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw")
        self.add("new-pixel", "Google Pixel 11", hardware_id="new-hw", last_seen=time.time() - 60)
        self.away("new-pixel")
        self.assertEqual(["pixel"], self.ids())
        self.assertEqual({"ok": True}, relay.unregister("pixel", request=None))
        snapshot = relay.presence_snapshot()
        self.assertEqual(["new-pixel"], [d["id"] for d in snapshot["devices"]])
        self.assertFalse(snapshot["everyone_away"], "a phone that takes the place starts at home")

    def test_a_phone_that_becomes_a_debug_build_gives_up_its_place(self):
        self.register("iphone", "iphone-hw", platform="ios")
        self.assertEqual(["iphone"], self.ids())
        self.register("iphone", "iphone-hw", platform="ios", build="debug")
        self.assertEqual([], self.ids())

    def test_presence_from_an_install_that_is_not_tracked_is_ignored(self):
        self.household()
        snapshot = relay.set_presence("pixel-debug", types.SimpleNamespace(away=True, source="manual", dwell_seconds=0), request=None)
        self.assertEqual("debug", snapshot["not_tracked"])
        away = relay.with_db(lambda c: c.execute("SELECT away FROM devices WHERE device_id='pixel-debug'").fetchone()[0])
        self.assertEqual(0, away)
        relay.set_presence("nobody", types.SimpleNamespace(away=True, source="geofence", dwell_seconds=600), request=None)
        self.assertIsNone(relay.find_device("nobody"), "no row is made for an install the relay doesn't know")

    def test_a_tracked_phone_leaving_is_armed_then_promoted(self):
        self.household()
        relay.set_presence("pixel", types.SimpleNamespace(away=True, source="geofence", dwell_seconds=600), request=None)
        self.assertTrue(relay.presence_snapshot()["devices"][0]["pending_away"])
        relay.with_db(lambda c: (c.execute("UPDATE devices SET away_pending_since=away_pending_since-601 WHERE device_id='pixel'"), c.commit()))
        relay.promote_pending()
        self.assertTrue(relay.presence_snapshot()["devices"][0]["away"])

    def test_installs_not_tracked_and_long_silent_are_forgotten(self):
        long_ago = time.time() - relay.UNTRACKED_FORGET_SECONDS - 60
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw", last_seen=long_ago)
        self.add("old-1", "Google Pixel 10 Pro XL", hardware_id=None, last_seen=long_ago)
        self.add("old-2", "Apple iPhone", platform="ios", build="debug", last_seen=long_ago)
        self.add("recent-debug", "Google sdk_gphone64_arm64", build="debug")
        self.assertEqual(2, relay.forget_untracked())
        left = relay.with_db(lambda c: [r[0] for r in c.execute("SELECT device_id FROM devices ORDER BY device_id")])
        self.assertEqual(["pixel", "recent-debug"], left, "the tracked phone stays, however quiet")

    def test_a_phone_reading_presence_is_seen_and_the_others_are_not(self):
        self.add("pixel", "Google Pixel 10 Pro XL", hardware_id="pixel-hw", last_seen=1000.0)
        self.add("iphone", "Apple iPhone", platform="ios", hardware_id="iphone-hw", last_seen=1000.0)
        snapshot = relay.get_presence(request=None, device="pixel")
        seen = {d["id"]: d["last_seen"] for d in snapshot["devices"]}
        self.assertGreater(seen["pixel"], time.time() - 60)
        self.assertEqual(1000.0, seen["iphone"])


class PushMessageTest(unittest.TestCase):
    """What a phone is sent. Android must get data only, or a backgrounded app never sees the push."""

    DATA = {"review_id": "r1", "camera": "hikvision_1", "event_id": "1790131143.212347-qq7fe8", "start_time": "1790131143.2"}

    def test_android_gets_a_data_only_message_carrying_the_text_and_the_moment(self):
        message = relay.push_message("tok", "Person in the driveway", "Front Yard", self.DATA)["message"]
        self.assertNotIn("notification", message, "a notification block lets Android draw it without the app")
        self.assertNotIn("notification", message["android"])
        self.assertEqual("high", message["android"]["priority"])
        self.assertEqual("Person in the driveway", message["data"]["title"])
        self.assertEqual("Front Yard", message["data"]["body"])
        for key in ("camera", "event_id", "start_time"):
            self.assertEqual(self.DATA[key], message["data"][key], "the app opens the moment from these")
        self.assertTrue(all(isinstance(v, str) for v in message["data"].values()), "FCM data values must be strings")

    def test_ios_still_gets_a_banner(self):
        aps = relay.push_message("tok", "Person in the driveway", "Front Yard", self.DATA)["message"]["apns"]["payload"]["aps"]
        self.assertEqual({"title": "Person in the driveway", "body": "Front Yard"}, aps["alert"])
        self.assertNotIn("interruption-level", aps)
        self.assertNotIn("sound", aps, "HomeSafe never makes a sound")

    def test_away_is_marked_for_both(self):
        message = relay.push_message("tok", "Person", "Front Yard · nobody home", {**self.DATA, "away": "1"}, away=True)["message"]
        self.assertEqual("1", message["data"]["away"], "the app picks the loud channel from this")
        self.assertEqual("time-sensitive", message["apns"]["payload"]["aps"]["interruption-level"])
        self.assertNotIn("sound", message["apns"]["payload"]["aps"], "not even while nobody is home")


    def test_a_visits_follow_up_is_quiet_and_lands_on_the_same_notification(self):
        message = relay.push_message("tok", "Front Yard", "Person in the driveway · 2 alerts", {**self.DATA, "notif_id": "r0", "silent": "1"})["message"]
        self.assertEqual("1", message["data"]["silent"], "the app posts it without a sound from this")
        self.assertEqual("r0", message["data"]["notif_id"], "the app tags the notification with this")
        self.assertNotIn("notification", message, "still data only")
        aps = message["apns"]["payload"]["aps"]
        self.assertNotIn("sound", aps)
        self.assertEqual("passive", aps["interruption-level"])
        self.assertEqual("r0", message["apns"]["headers"]["apns-collapse-id"])

    def test_a_push_outside_a_visit_collapses_by_its_own_review(self):
        message = relay.push_message("tok", "Front Yard", "Person in the driveway", self.DATA)["message"]
        self.assertEqual("r1", message["apns"]["headers"]["apns-collapse-id"])


def review(rid, start, objects, sub_labels=(), end=None, camera="hikvision_1", zones=("driveway",)):
    """A `/api/review` item; `end` None is still open."""
    return {
        "id": rid, "camera": camera, "start_time": start, "end_time": end,
        "data": {"objects": list(objects), "sub_labels": list(sub_labels), "zones": list(zones), "detections": [rid + "-e"]},
    }


class SubjectTest(unittest.TestCase):
    def setUp(self):
        self.cars, relay.HOUSEHOLD_CARS = relay.HOUSEHOLD_CARS, {"andrews_tesla": {}, "sarahs_car": {}}

    def tearDown(self):
        relay.HOUSEHOLD_CARS = self.cars

    def test_a_person_is_never_hidden_behind_a_household_cars_name(self):
        # Seen 60 times in the week to 2026-09-25: "Andrews Tesla in the driveway" with a person in it.
        self.assertEqual("Person and Andrew's Tesla", relay.subject_for(["car", "car-verified", "person"], ["andrews_tesla"]))

    def test_named_cars_and_faces_and_plain_labels(self):
        self.assertEqual("Andrew's Tesla", relay.subject_for(["car", "car-verified"], ["andrews_tesla"]))
        self.assertEqual("Andrew's Tesla and Sarah's Car", relay.subject_for(["car-verified"], ["andrews_tesla", "sarahs_car"]))
        self.assertEqual("Sarah and car", relay.subject_for(["person", "car"], ["sarah"]))
        self.assertEqual("Person and car", relay.subject_for(["car", "person"], []), "people first")
        self.assertEqual("Person, bicycle and Andrew's Tesla", relay.subject_for(["bicycle", "car", "person"], ["andrews_tesla"]))
        self.assertEqual("Car", relay.subject_for(["car"], ["none"]), "the classifier's none class is not a name")

    def test_a_household_car_is_not_a_familiar_face(self):
        self.assertFalse(relay.is_recognised_person(review("r", 0, ["car", "person"], ["andrews_tesla"])))
        self.assertTrue(relay.is_recognised_person(review("r", 0, ["car", "person"], ["andrews_tesla", "sarah"])))

    def test_kinds(self):
        self.assertEqual({"person", "car:andrews_tesla"}, relay.review_kinds(review("r", 0, ["car", "car-verified", "person"], ["andrews_tesla"])))
        self.assertEqual({"person:sarah", "car"}, relay.review_kinds(review("r", 0, ["car", "person"], ["sarah"])))
        self.assertEqual({"dog"}, relay.review_kinds(review("r", 0, ["dog"])))

    def test_only_a_review_of_unnamed_cars_offers_a_car_tag(self):
        self.assertTrue(relay.is_unnamed_car(review("r", 0, ["car", "car-verified"])))
        self.assertTrue(relay.is_unnamed_car(review("r", 0, ["car"], ["none"])), "the none class is not a name")
        self.assertFalse(relay.is_unnamed_car(review("r", 0, ["car"], ["andrews_tesla"])), "already named")
        self.assertFalse(relay.is_unnamed_car(review("r", 0, ["car", "person"])), "its first detection may be the person")
        self.assertFalse(relay.is_unnamed_car(review("r", 0, ["truck"])), "the classifier only runs on cars")

    def test_a_review_of_unnamed_people_offers_not_a_person_and_nothing_else_does(self):
        self.assertEqual({"person_unnamed": "1"}, relay.offers(review("r", 0, ["person"])))
        self.assertEqual({"person_unnamed": "1"}, relay.offers(review("r", 0, ["person"], ["unknown"])), "a placeholder is no name")
        self.assertEqual({}, relay.offers(review("r", 0, ["person"], ["sarah"])), "Frigate knows the face")
        self.assertEqual({}, relay.offers(review("r", 0, ["person", "car"])), "its first detection may be the car")
        self.assertEqual({"car_unnamed": "1"}, relay.offers(review("r", 0, ["car"])))
        self.assertEqual({}, relay.offers(review("r", 0, ["dog"])))


class VisitsTest(unittest.TestCase):
    """How a run of alerts on one camera becomes one notification, and which of its pushes sound."""

    T = 1_790_360_000.0

    def setUp(self):
        self.cars, relay.HOUSEHOLD_CARS = relay.HOUSEHOLD_CARS, {"andrews_tesla": {}}
        self.visits = relay.Visits()

    def tearDown(self):
        relay.HOUSEHOLD_CARS = self.cars

    def judge(self, item, now=None):
        visit, sound = self.visits.judge(item, now if now is not None else item["start_time"] + 5)
        return visit.id, sound

    def test_more_of_the_same_updates_the_first_notification_quietly(self):
        self.assertEqual(("a", True), self.judge(review("a", self.T, ["person"], end=self.T + 30)))
        self.assertEqual(("a", False), self.judge(review("b", self.T + 80, ["person"], end=self.T + 120)))
        self.assertEqual(("a", False), self.judge(review("c", self.T + 400, ["person"])), "within five minutes of b's end")
        visit = self.visits.by_camera["hikvision_1"]
        self.assertEqual(("Front Yard", "Person in the driveway · 3 alerts"), visit.sentence(["driveway"]))
        self.assertEqual(self.T, visit.first_start)

    def test_something_new_in_a_visit_sounds_on_the_same_notification(self):
        self.judge(review("a", self.T, ["car"], end=self.T + 30))
        self.assertEqual(("a", True), self.judge(review("b", self.T + 60, ["car", "person"], end=self.T + 90)))
        self.assertEqual(("a", True), self.judge(review("c", self.T + 120, ["dog"])))

    def test_a_stranger_after_the_family_sounds(self):
        self.judge(review("a", self.T, ["person"], ["sarah"], end=self.T + 30))
        self.assertEqual(("a", True), self.judge(review("b", self.T + 60, ["person"])))

    def test_a_quiet_gap_ends_the_visit(self):
        self.judge(review("a", self.T, ["person"], end=self.T + 30))
        self.assertEqual(("b", True), self.judge(review("b", self.T + 30 + 301, ["person"])))

    def test_a_long_alert_keeps_its_visit_open_while_it_lasts(self):
        first = review("a", self.T, ["person"])
        self.judge(first)
        self.visits.observe([first], self.T + 900)  # still going fifteen minutes on
        self.assertEqual(("a", False), self.judge(review("b", self.T + 1000, ["person"])))

    def test_each_camera_has_its_own_visit(self):
        self.judge(review("a", self.T, ["person"], end=self.T + 30))
        self.assertEqual(("b", True), self.judge(review("b", self.T + 60, ["person"], camera="hikvision_2")))

    def test_a_household_car_sounds_once_an_hour(self):
        self.assertEqual(("a", True), self.judge(review("a", self.T, ["car-verified"], ["andrews_tesla"], end=self.T + 30)))
        # Back twenty minutes later, a new visit, but the family's car doing its rounds.
        self.assertEqual(("b", False), self.judge(review("b", self.T + 1200, ["car-verified"], ["andrews_tesla"], end=self.T + 1230)))
        self.assertEqual(("c", True), self.judge(review("c", self.T + 1230 + 3601, ["car-verified"], ["andrews_tesla"])))

    def test_a_household_car_named_late_in_a_visit_is_no_news(self):
        self.judge(review("a", self.T, ["car-verified"], ["andrews_tesla"], end=self.T + 30))
        self.judge(review("b", self.T + 1200, ["car"], end=self.T + 1230))  # the same car, not named yet: a new visit
        self.assertEqual(("b", False), self.judge(review("c", self.T + 1260, ["car-verified"], ["andrews_tesla"])))

    def test_a_household_car_in_view_for_most_of_an_hour_was_seen_until_it_left(self):
        first = review("a", self.T, ["car-verified"], ["andrews_tesla"])
        self.judge(first)
        self.visits.observe([dict(first, end_time=self.T + 3000)], self.T + 3000)  # sat in view fifty minutes
        # It drives off and is picked up again twenty minutes later: well within the hour of last being seen.
        self.assertEqual(("c", False), self.judge(review("c", self.T + 4200, ["car-verified"], ["andrews_tesla"])))

    def test_a_person_with_a_household_car_still_sounds(self):
        self.judge(review("a", self.T, ["car-verified"], ["andrews_tesla"], end=self.T + 30))
        self.assertEqual(("b", True), self.judge(review("b", self.T + 1200, ["car-verified", "person"], ["andrews_tesla"])))

    def test_a_backlog_is_quiet(self):
        self.assertEqual(("a", False), self.judge(review("a", self.T, ["person"], end=self.T + 30), now=self.T + 600))
        self.assertEqual(("a", False), self.judge(review("b", self.T + 60, ["car"], end=self.T + 90), now=self.T + 700))


class ReviewBacklogTest(unittest.TestCase):
    """A backlog longer than one page is read to its end, back to the last alert already handled."""

    class Response:
        def __init__(self, body):
            self.body = body

        def raise_for_status(self):
            pass

        def json(self):
            return self.body

    def setUp(self):
        self.page, relay.REVIEW_PAGE = relay.REVIEW_PAGE, 3
        self.get = relay.requests.get
        # Frigate's newest first; `before` is strict on start_time, as the real API is.
        self.reviews = [review(f"r{i}", 1000.0 - i, ["person"]) for i in range(10)]
        self.asked = []

        def get(url, params=None, timeout=None):
            self.asked.append(params.get("before"))
            older = [r for r in self.reviews if params.get("before") is None or r["start_time"] < params["before"]]
            return self.Response(older[: params["limit"]])

        relay.requests.get = get

    def tearDown(self):
        relay.REVIEW_PAGE = self.page
        relay.requests.get = self.get

    def test_pages_back_until_it_reaches_an_alert_it_has_handled(self):
        handled = {"r7", "r8", "r9"}
        ids = [r["id"] for r in relay.recent_review("alert", known=lambda rid: rid in handled)]
        self.assertEqual([f"r{i}" for i in range(9)], ids, "every unhandled one, however far back")
        self.assertEqual(4, len(self.asked), "pages overlap by one, so an alert sharing the oldest start isn't skipped")

    def test_one_page_when_nothing_is_behind(self):
        self.assertEqual(["r0", "r1", "r2"], [r["id"] for r in relay.recent_review("alert", known=lambda rid: rid == "r1")])
        self.assertEqual([None], self.asked)

    def test_without_known_it_reads_one_page(self):
        self.assertEqual(3, len(relay.recent_review("detection")))

    def test_stops_at_the_page_cap(self):
        pages, relay.REVIEW_MAX_PAGES = relay.REVIEW_MAX_PAGES, 2
        try:
            self.assertEqual(5, len(relay.recent_review("alert", known=lambda rid: False)), "two pages, overlapping by one")
        finally:
            relay.REVIEW_MAX_PAGES = pages


class EventMediaTest(unittest.TestCase):
    def test_serves_only_a_pushed_notifications_pictures(self):
        self.assertEqual(
            relay.FRIGATE + "/api/events/1790131143.212347-qq7fe8/preview.gif",
            relay.event_media_url("1790131143.212347-qq7fe8", "preview.gif"),
        )
        self.assertIsNotNone(relay.event_media_url("1790131143.212347-qq7fe8", "thumbnail.jpg"))
        self.assertIsNone(relay.event_media_url("1790131143.212347-qq7fe8", "clip.mp4"), "not the clip")
        self.assertIsNone(relay.event_media_url("1790131143.212347-qq7fe8", "snapshot.jpg"))

    def test_an_id_cannot_walk_out_of_the_events_api(self):
        for bad in ("../config", "1790131143.2-a/../../config", "x", "", "1790131143.212347-qq7fe8?x=1", "1790131143.212347-qq7fe8\n"):
            self.assertIsNone(relay.event_media_url(bad, "thumbnail.jpg"), bad)


class ClassificationCropTest(unittest.TestCase):
    """Mirrors Frigate's calculate_region(frame, *box, max(w, h), 1.0) on a 1280x720 detect frame."""

    def test_square_on_the_longer_side_centred_on_the_box(self):
        # A 200x100 px car at (400, 300): a 200 px square, centred vertically on it.
        self.assertEqual((400, 250, 600, 450), relay.classification_crop(1280, 720, 400 / 1280, 300 / 720, 200 / 1280, 100 / 720))

    def test_pushed_back_inside_the_frame(self):
        # Hard against the right and bottom edges: the square slides in rather than being cut.
        self.assertEqual((1100, 540, 1280, 720), relay.classification_crop(1280, 720, 1150 / 1280, 650 / 720, 130 / 1280, 180 / 720))

    def test_cut_off_when_taller_than_the_frame(self):
        # A car filling most of the width: its square is taller than the frame, so it's cut at the bottom.
        self.assertEqual((140, 0, 1140, 720), relay.classification_crop(1280, 720, 140 / 1280, 300 / 720, 1000 / 1280, 300 / 720))

    def test_a_box_with_no_area_is_refused(self):
        self.assertIsNone(relay.classification_crop(1280, 720, 0.5, 0.5, 0.0, 0.2))

    def test_names_cannot_leave_the_dataset(self):
        for good in ("known_cars", "sarahs_car", "in-laws_mercedes", "none"):
            self.assertIsNotNone(relay.DATASET_NAME.fullmatch(good), good)
        for bad in ("..", ".", "../config", "a/b", "", "-x", "sarah's", "car\n"):
            self.assertIsNone(relay.DATASET_NAME.fullmatch(bad), bad)

    def test_file_named_like_frigates_own(self):
        name = relay.dataset_file_name("sarahs_car", 1790131143.25)
        self.assertRegex(name, r"^sarahs_car-1790131143\.25-[a-z0-9]{6}\.png$")


class CarCheckTest(unittest.TestCase):
    """The pure halves of the car check: which crops are passing street cars, and what the vision model's description does to a name."""

    CARS = {
        "andrews_tesla": {"make": "tesla", "model": "Model Y", "colour": "blue", "plate": "8ABC123"},
        "sarahs_car": {"make": "tesla", "model": "Model Y", "colour": "red", "plate": "9XYZ789"},
        "in-laws_mercedes": {"make": "mercedes", "colour": "silver"},
        "yayas_car": {"colour": "white"},
    }

    def street(self, **overrides):
        e = {"id": "1790131143.2-abc", "label": "car", "camera": "hikvision_1", "start_time": 1.0, "end_time": 9.0, "zones": [],
             "data": {"box": [0.6, 0.25, 0.14, 0.1], "path_data": [[[x, y], 0.0] for x, y in DRIVE_PATH]}}
        e.update(overrides)
        return e

    def test_queued_crop_names_give_their_event(self):
        self.assertEqual("1790227958.373915-3c1ue3", relay.train_crop_event("1790227958.373915-3c1ue3-1790227960.625516-andrews_tesla-1.0.webp"))
        self.assertEqual("1790227958.373915-3c1ue3", relay.train_crop_event("1790227958.373915-3c1ue3-1790227960.6-none-0.97.webp"))
        for other in ("example_003.jpg", "none-1790131143.25-abcdef.png", "../x-y-z-w-v.webp"):
            self.assertIsNone(relay.train_crop_event(other), other)

    def test_a_car_driving_past_is_street_traffic(self):
        self.assertTrue(relay.is_passing_street_car(self.street(), ["driveway"]))

    DRIVEWAY = [(0.419, 0.458), (0.504, 0.5), (0.138, 0.676), (0.217, 0.504)]

    def test_a_car_pulling_out_of_the_driveway_too_briskly_to_be_tagged_is_not(self):
        # Andrew's Tesla, 2026-09-24 15:40: no zone tag, the path starts at the driveway's edge.
        leaving = [(0.391, 0.547), (0.402, 0.56), (0.456, 0.526), (0.516, 0.499), (0.577, 0.467), (0.638, 0.451), (0.709, 0.44), (0.78, 0.444), (0.859, 0.461), (0.914, 0.482)]
        event = self.street(data={"box": [0.308, 0.344, 0.24, 0.197], "path_data": [[[x, y], 0.0] for x, y in leaving]})
        self.assertTrue(relay.is_passing_street_car(event, ["driveway"]), "without the outline it looks like street traffic")
        self.assertFalse(relay.is_passing_street_car(event, ["driveway"], [self.DRIVEWAY]))

    def test_a_car_on_the_far_side_of_the_street_stays_street_traffic_with_the_outline_known(self):
        self.assertTrue(relay.is_passing_street_car(self.street(), ["driveway"], [self.DRIVEWAY]))

    def test_distance_to_a_polygon(self):
        square = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
        self.assertEqual(0.0, relay.distance_to_polygon((0.5, 0.5), square))
        self.assertAlmostEqual(0.25, relay.distance_to_polygon((1.25, 0.5), square))
        self.assertEqual([(0.1, 0.2), (0.3, 0.4), (0.5, 0.6)], relay.zone_polygon({"coordinates": "0.1,0.2,0.3,0.4,0.5,0.6"}))
        self.assertEqual([], relay.zone_polygon({}))

    def test_a_car_that_entered_the_driveway_is_not(self):
        self.assertFalse(relay.is_passing_street_car(self.street(zones=["driveway"]), ["driveway"]))

    def test_a_parked_car_is_not(self):
        self.assertFalse(relay.is_passing_street_car(self.street(data={"box": PARKED_BOX, "path_data": [[[x, y], 0.0] for x, y in PARKED_PATH]}), ["driveway"]))

    def test_nothing_is_street_traffic_on_a_camera_with_no_car_zone(self):
        self.assertFalse(relay.is_passing_street_car(self.street(), []))

    def test_a_car_still_in_view_is_judged_later(self):
        self.assertFalse(relay.is_passing_street_car(self.street(end_time=None), ["driveway"]))

    # Real boxes from the Front Yard (1280x720 detect), 2026-09-27.
    FRAME = (1280, 720)
    SUV_ACROSS = {"box": [0.86, 0.25, 0.036, 0.042], "path_data": [[[0.878, 0.292], 0.0], [[0.879, 0.292], 0.0]]}
    CURB_TESLA = {"box": [0.75, 0.33, 0.15, 0.2], "path_data": [[[0.825, 0.53], 0.0], [[0.826, 0.53], 0.0]]}
    SARAH_AT_THE_CURB = {"box": [0.714, 0.332, 0.09, 0.157], "path_data": [[[0.759, 0.489], 0.0], [[0.76, 0.489], 0.0]]}
    BEAM_CUT = {"box": [0.3, 0.43, 0.08, 0.15], "path_data": [[[0.34, 0.58], 0.0], [[0.341, 0.58], 0.0]]}

    def far(self, data, **overrides):
        e = {"id": "1790570880.906516-25u212", "label": "car", "camera": "hikvision_1", "start_time": 1000.0, "end_time": 1030.0,
             "zones": [], "sub_label": "andrews_tesla", "data": dict(data, sub_label_score=0.93)}
        e.update(overrides)
        return relay.is_far_car(e, ["driveway"], [self.DRIVEWAY], self.FRAME, 2000.0)

    def test_the_classifier_crop_is_the_boxs_longer_side_in_detect_pixels(self):
        self.assertAlmostEqual(192.0, relay.classifier_crop_px({"data": self.CURB_TESLA}, self.FRAME))
        self.assertAlmostEqual(46.08, relay.classifier_crop_px({"data": self.SUV_ACROSS}, self.FRAME))
        self.assertIsNone(relay.classifier_crop_px({"data": {}}, self.FRAME))
        self.assertIsNone(relay.classifier_crop_px({"data": self.CURB_TESLA}, None))

    def test_a_small_car_across_the_street_is_far(self):
        self.assertTrue(self.far(self.SUV_ACROSS))

    def test_the_household_cars_at_the_curb_are_near_enough(self):
        self.assertFalse(self.far(self.CURB_TESLA))
        self.assertFalse(self.far(self.SARAH_AT_THE_CURB), "half behind the tree, 115 px")

    def test_a_car_the_porch_beam_cuts_in_half_in_the_driveway_is_not_far(self):
        self.assertFalse(self.far(self.BEAM_CUT), "its path is on the driveway, tagged or not")
        self.assertFalse(self.far(self.SUV_ACROSS, zones=["driveway"]), "Frigate's tag is enough")

    def test_a_small_car_whose_path_came_near_the_driveway_is_not_far(self):
        near = {"box": [0.2, 0.3, 0.05, 0.05], "path_data": [[[0.225, 0.35], 0.0], [[0.3, 0.46], 0.0]]}
        self.assertFalse(self.far(near))

    def test_a_far_car_still_in_view_waits_until_settled(self):
        self.assertFalse(self.far(self.SUV_ACROSS, end_time=None, start_time=1990.0))
        self.assertTrue(self.far(self.SUV_ACROSS, end_time=None, start_time=1000.0))

    def test_far_cant_be_told_without_a_car_zone_outline(self):
        e = {"label": "car", "start_time": 1000.0, "end_time": 1030.0, "zones": [], "data": self.SUV_ACROSS}
        self.assertFalse(relay.is_far_car(e, ["driveway"], [], self.FRAME, 2000.0))
        self.assertFalse(relay.is_far_car(e, [], [self.DRIVEWAY], self.FRAME, 2000.0))

    def test_zones_that_want_a_car(self):
        cam = {"zones": {"street": {"objects": ["bird"]}, "driveway": {"objects": ["person", "car", "dog"]}, "any": {"objects": []}, "lawn": {"objects": ["person"]}}}
        self.assertEqual(["driveway", "any"], relay.zones_wanting(cam, "car"))

    @staticmethod
    def saw(colour="blue", make="tesla", model="", plate=""):
        return {"colour": colour, "make": make, "model": model, "body": "suv", "delivery": "none", "plate": plate}

    def verdict(self, name, description, plate=""):
        return relay.second_opinion_verdict(name, description, self.CARS, plate)

    def test_a_red_car_called_andrews_tesla_is_renamed_to_the_one_red_tesla(self):
        self.assertEqual(("relabel", "sarahs_car", "looks"), self.verdict("andrews_tesla", self.saw("red", model="Model Y")))

    def test_a_red_car_of_no_readable_make_only_loses_the_wrong_name(self):
        self.assertEqual(("clear", None, None), self.verdict("andrews_tesla", self.saw("red", "unknown")))

    def test_a_blue_toyota_called_andrews_tesla_loses_the_name(self):
        self.assertEqual([], relay.household_matches(self.saw("blue", "toyota"), {"andrews_tesla": self.CARS["andrews_tesla"]}))
        self.assertEqual(("clear", None, None), self.verdict("andrews_tesla", self.saw("blue", "toyota")))

    def test_a_blue_tesla_is_verified_and_a_black_one_is_not_andrews(self):
        self.assertEqual(("keep", "andrews_tesla", "looks"), self.verdict("andrews_tesla", self.saw("blue")))
        self.assertEqual(("clear", None, None), self.verdict("andrews_tesla", self.saw("black")), "strict: black is not blue")

    def test_a_model_that_isnt_the_cars_rules_it_out(self):
        self.assertEqual("match", relay.looks_verdict(self.saw(model="Tesla Model Y"), self.CARS["andrews_tesla"]))
        self.assertEqual("match", relay.looks_verdict(self.saw(model="model-y long range"), self.CARS["andrews_tesla"]))
        self.assertEqual("mismatch", relay.looks_verdict(self.saw(model="Model 3"), self.CARS["andrews_tesla"]))
        self.assertEqual(("clear", None, None), self.verdict("andrews_tesla", self.saw(model="Model 3")))

    def test_infrared_rules_nothing_out_but_verifies_nothing_either(self):
        night = self.saw("unknown")
        self.assertEqual(["andrews_tesla", "sarahs_car", "yayas_car"], relay.household_matches(night, self.CARS))
        self.assertEqual(("keep", "andrews_tesla", None), self.verdict("andrews_tesla", night))

    def test_silver_and_grey_are_one_colour(self):
        self.assertEqual(["in-laws_mercedes"], relay.household_matches(self.saw("grey", "mercedes"), self.CARS))

    def test_an_unknown_make_rules_out_nothing_by_make(self):
        self.assertEqual(["sarahs_car"], relay.household_matches(self.saw("red", "unknown"), self.CARS))

    def test_a_white_toyota_called_andrews_tesla_is_not_handed_to_the_white_car_with_no_make_on_file(self):
        self.assertEqual(["yayas_car"], relay.household_matches(self.saw("white", "toyota"), self.CARS))
        self.assertEqual(("clear", None, None), self.verdict("andrews_tesla", self.saw("white", "toyota")))

    def test_never_names_a_car_the_classifier_left_unnamed_on_looks(self):
        for unnamed in (None, "none"):
            self.assertEqual(("keep", unnamed, None), self.verdict(unnamed, self.saw("blue")))

    def test_a_name_with_no_profile_is_left_alone(self):
        self.assertEqual(("keep", "moms_car", None), self.verdict("moms_car", self.saw()))

    def test_a_plate_of_ours_names_the_car_whatever_it_looks_like(self):
        self.assertEqual(("keep", "andrews_tesla", "plate"), self.verdict("andrews_tesla", self.saw("black"), "8ABC123"), "dusk")
        self.assertEqual(("relabel", "sarahs_car", "plate"), self.verdict("andrews_tesla", self.saw("unknown"), "9XYZ789"))
        self.assertEqual(("relabel", "andrews_tesla", "plate"), self.verdict(None, self.saw("unknown"), "8ABC12B"), "one character off")

    def test_a_plate_far_from_the_named_cars_takes_the_name_away(self):
        self.assertEqual(("clear", None, None), self.verdict("andrews_tesla", self.saw("blue"), "7QRS456"))
        self.assertEqual(("keep", "andrews_tesla", "looks"), self.verdict("andrews_tesla", self.saw("blue"), "8ABD124"), "two off: a misread, not proof")
        self.assertEqual(("keep", "andrews_tesla", "looks"), self.verdict("andrews_tesla", self.saw("blue"), "7QRS"), "a scrap of a longer plate rules nothing out")
        short = {"vanity": {"make": "tesla", "colour": "blue", "plate": "AB12"}}
        self.assertEqual(("clear", None, None), relay.second_opinion_verdict("vanity", self.saw("blue"), short, "XY99"), "a four-character plate is a full read of a four-character plate")

    def test_plates_are_read_as_letters_and_digits(self):
        self.assertEqual("8ABC123", relay.normal_plate(" 8abc-123 "))
        self.assertEqual("8ABC123", relay.plate_read({"data": {"recognized_license_plate": "8abc 123"}}, self.saw(plate="")))
        self.assertEqual("9XYZ789", relay.plate_read({"data": {}}, self.saw(plate="9xyz789")))
        self.assertEqual("", relay.plate_read({"data": {}}, self.saw(plate="9X")), "a scrap of a plate is no plate")
        self.assertEqual(1, relay.edit_distance("8ABC123", "8ABC12B"))
        self.assertIsNone(relay.plate_owner("8ABC123", {"a": {"plate": "8ABC123"}, "b": {"plate": "8ABC124"}}), "two owners: a typo somewhere")

    def test_a_car_still_in_the_driveway_waits_to_settle(self):
        car = {"label": "car", "zones": ["driveway"], "start_time": 100.0, "end_time": None, "sub_label": None, "data": {}}
        self.assertFalse(relay.second_opinion_due(car, ["driveway"], 130.0))
        self.assertTrue(relay.second_opinion_due(car, ["driveway"], 170.0))

    def test_only_cars_in_a_car_zone_get_a_second_opinion(self):
        car = {"label": "car", "zones": [], "start_time": 0.0, "end_time": 5.0, "sub_label": "andrews_tesla", "data": {}}
        self.assertFalse(relay.second_opinion_due(car, ["driveway"], 100.0))

    def test_sub_label_as_a_pair(self):
        self.assertEqual(("andrews_tesla", 0.98), relay.sub_label_of({"sub_label": ["andrews_tesla", 0.98]}))
        self.assertEqual(("andrews_tesla", 0.9), relay.sub_label_of({"sub_label": "andrews_tesla", "data": {"sub_label_score": 0.9}}))
        self.assertEqual((None, None), relay.sub_label_of({"sub_label": None}))

    def test_the_car_is_looked_for_where_its_path_ended(self):
        e = {"start_time": 1.0, "data": {"box": [0.1, 0.2, 0.2, 0.1], "path_data": [[[0.5, 0.5], 10.0], [[0.3, 0.6], 12.5]]}}
        t, (x, y, w, h) = relay.last_sighting(e)
        self.assertEqual(12.5, t)
        self.assertAlmostEqual(0.2, x)
        self.assertAlmostEqual(0.5, y)
        self.assertEqual((0.2, 0.1), (w, h))
        self.assertIsNone(relay.last_sighting({"data": {}}))

    def test_the_crop_has_room_to_spare_and_stays_in_frame(self):
        x, y, w, h = relay.vlm_crop_box((0.9, 0.1, 0.2, 0.2))
        self.assertAlmostEqual(0.85, x)
        self.assertAlmostEqual(0.05, y)
        self.assertAlmostEqual(1.0, x + w)
        self.assertAlmostEqual(0.35, y + h)

    def test_a_car_arriving_in_or_leaving_the_driveway_is_near_one_parked_throughout_is_not(self):
        street = self.street(start_time=10_000.0, end_time=10_030.0)
        def visit(start, end, zones=("driveway",)):
            return [{"id": "v", "zones": list(zones), "start_time": start, "end_time": end}]
        self.assertTrue(relay.car_zone_came_or_went(street, ["driveway"], visit(10_100.0, 10_400.0)), "arrived just after")
        self.assertTrue(relay.car_zone_came_or_went(street, ["driveway"], visit(10_000.0 - 5 * 3600, 9_900.0)), "left after five hours parked")
        self.assertTrue(relay.car_zone_came_or_went(street, ["driveway"], visit(10_050.0, None)), "arrived and still there")
        self.assertFalse(relay.car_zone_came_or_went(street, ["driveway"], visit(5_000.0, None)), "parked right through")
        self.assertFalse(relay.car_zone_came_or_went(street, ["driveway"], visit(5_000.0, 20_000.0)), "parked right through")
        self.assertFalse(relay.car_zone_came_or_went(street, ["driveway"], visit(9_000.0, 9_500.0)), "left well before")
        self.assertFalse(relay.car_zone_came_or_went(street, ["driveway"], visit(10_000.0, 10_030.0, zones=["front_lawn"])))
        self.assertFalse(relay.car_zone_came_or_went(street, ["driveway"], [dict(street, zones=["driveway"])]), "not itself")


class _Response:
    def __init__(self, status=200, body=None):
        self.status_code, self._body, self.ok = status, body, status < 400
        self.text = repr(body)

    def json(self):
        return self._body

    def raise_for_status(self):
        if not self.ok:
            raise RuntimeError(f"HTTP {self.status_code}")


class _FakeFrigate(unittest.TestCase):
    """A scratch relay.db and a fake Frigate for the car check's rounds; no tests of its own."""

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._saved = {name: getattr(relay, name) for name in (
            "DB_PATH", "CONN", "CLIPS_DIR", "CAR_CLASSIFIER", "STREET_NONE_MAX", "RETRAIN_AFTER", "HOUSEHOLD_CARS",
            "car_zones", "car_zone_polygons", "describe_car", "car_picture")}
        self._requests = (relay.requests.get, relay.requests.post)
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()
        relay.CLIPS_DIR = self._dir.name
        relay.CAR_CLASSIFIER = "known_cars"
        relay.car_zones = lambda: {"hikvision_1": ["driveway"]}
        relay.car_zone_polygons = lambda: {}
        relay.requests.get, relay.requests.post = self.get, self.post
        self.events = {}  # id -> the event, or the status Frigate answers for it
        self.visits = []  # what /api/events lists
        self.posts = []
        self.refuse = set()
        self.now = time.time()

    def tearDown(self):
        relay.CONN.close()
        for name, value in self._saved.items():
            setattr(relay, name, value)
        relay.requests.get, relay.requests.post = self._requests
        self._dir.cleanup()

    def get(self, url, params=None, timeout=None):
        if url.endswith("/api/events"):
            p = params or {}
            return _Response(200, [
                v for v in self.visits
                if p.get("after", float("-inf")) < v["start_time"] < p.get("before", float("inf"))
                and ("in_progress" not in p or v["end_time"] is None)
                and ("min_length" not in p or (v["end_time"] is not None and v["end_time"] - v["start_time"] >= p["min_length"]))
            ])
        found = self.events.get(url.rsplit("/", 1)[1], 404)
        return _Response(found) if isinstance(found, int) else _Response(200, found)

    def post(self, url, json=None, timeout=None):
        self.posts.append((url, json))
        return _Response(400 if any(part in url for part in self.refuse) else 200, {})

    def verdict(self, event_id, kind="street"):
        row = relay.with_db(lambda c: c.execute("SELECT verdict FROM car_checks WHERE event_id=? AND kind=?", (event_id, kind)).fetchone())
        return row and row[0]

    def sub_labels(self):
        return [body for url, body in self.posts if url.endswith("/sub_label")]


class CarCheckAgainstFrigate(_FakeFrigate):
    """The car check's rounds against a fake Frigate: what it files, writes off, retries and leaves alone."""

    def street_car(self, age=600.0, crops=2):
        start = self.now - age
        event_id = f"{start:.6f}-abc{len(self.events)}"
        self.events[event_id] = {"id": event_id, "label": "car", "camera": "hikvision_1", "start_time": start, "end_time": start + 20, "zones": [],
                                 "data": {"box": [0.6, 0.25, 0.14, 0.1], "path_data": [[[x, y], 0.0] for x, y in DRIVE_PATH]}}
        train = os.path.join(self._dir.name, "known_cars", "train")
        os.makedirs(train, exist_ok=True)
        for i in range(crops):
            open(os.path.join(train, f"{event_id}-{start + i:.6f}-andrews_tesla-0.98.webp"), "wb").close()
        return event_id

    def categorized(self):
        return [body["training_file"] for url, body in self.posts if url.endswith("/categorize")]

    def test_the_none_cap_counts_every_crop_it_moves(self):
        none = os.path.join(self._dir.name, "known_cars", "dataset", "none")
        os.makedirs(none)
        for i in range(2):
            open(os.path.join(none, f"{i}.webp"), "wb").close()
        relay.STREET_NONE_MAX = 3
        self.street_car(age=900.0)
        self.street_car(age=600.0)
        relay.file_street_crops()
        self.assertEqual(1, len(self.categorized()))

    def test_a_car_frigate_hasnt_written_yet_is_looked_at_again(self):
        young = self.street_car(age=60.0)
        self.events[young] = 404
        broken = self.street_car(age=7200.0)
        self.events[broken] = 503
        old = self.street_car(age=7200.0 + 1)
        self.events[old] = 404
        relay.file_street_crops()
        self.assertIsNone(self.verdict(young))
        self.assertIsNone(self.verdict(broken))
        self.assertEqual("gone", self.verdict(old))

    def test_a_car_that_left_after_hours_in_the_driveway_keeps_a_street_car_out(self):
        passing = self.street_car()
        start = self.events[passing]["start_time"]
        self.visits = [{"id": "parked", "zones": ["driveway"], "start_time": start - 5 * 3600, "end_time": start + 10}]
        relay.file_street_crops()
        self.assertEqual("near-car-zone", self.verdict(passing))
        self.assertEqual([], self.categorized())

    def test_a_car_parked_right_through_doesnt_keep_a_street_car_out(self):
        passing = self.street_car()
        start = self.events[passing]["start_time"]
        self.visits = [{"id": "parked", "zones": ["driveway"], "start_time": start - 5 * 3600, "end_time": None},
                       {"id": "earlier", "zones": ["driveway"], "start_time": start - 4 * 3600, "end_time": start - 3 * 3600}]
        relay.file_street_crops()
        self.assertEqual("filed", self.verdict(passing))
        self.assertEqual(2, len(self.categorized()))

    def test_a_retrain_frigate_refuses_is_asked_for_again_an_hour_later(self):
        relay.RETRAIN_AFTER = 2
        for i in range(2):
            relay.record_check(f"e{i}", "street", "filed")
        self.refuse.add("/train")
        relay.maybe_retrain()
        self.assertIsNone(relay.state_get("car_retrain_at"))
        relay.maybe_retrain()
        self.assertEqual(1, len(self.posts), "not every round")
        relay.state_set("car_retrain_tried_at", self.now - relay.RETRAIN_RETRY_SECONDS - 1)
        self.refuse.clear()
        relay.maybe_retrain()
        self.assertEqual(2, len(self.posts))
        self.assertIsNotNone(relay.state_get("car_retrain_at"))

    def driveway_car(self):
        car = {"id": f"{self.now - 300:.6f}-drv1", "label": "car", "camera": "hikvision_1", "zones": ["driveway"],
               "start_time": self.now - 300, "end_time": self.now - 200, "sub_label": "andrews_tesla", "data": {"sub_label_score": 0.98, "box": [0.3, 0.4, 0.2, 0.2]}}
        self.events[car["id"]] = car
        self.visits = [car]
        relay.HOUSEHOLD_CARS = CarCheckTest.CARS
        relay.car_picture = lambda event: b"jpeg"
        relay.describe_car = lambda jpeg: {"colour": "red", "make": "tesla", "model": "", "body": "suv", "delivery": "none"}
        return car

    def test_a_person_tagging_the_car_while_the_model_looks_is_left_alone(self):
        car = self.driveway_car()
        def tagged_meanwhile(jpeg):
            self.events[car["id"]] = dict(car, data={"sub_label_score": 1.0})
            return {"colour": "red", "make": "tesla", "model": "", "body": "suv", "delivery": "none"}
        relay.describe_car = tagged_meanwhile
        relay.second_opinions()
        self.assertEqual([], self.sub_labels())
        self.assertEqual("person", self.verdict(car["id"], "vlm"))

    def test_a_verdict_frigate_refuses_is_tried_again(self):
        car = self.driveway_car()
        self.refuse.add("/sub_label")
        relay.second_opinions()
        self.assertIsNone(self.verdict(car["id"], "vlm"))
        self.refuse.clear()
        relay.second_opinions()
        self.assertEqual([{"subLabel": "sarahs_car", "subLabelScore": relay.VLM_SCORE}] * 2, self.sub_labels())
        self.assertEqual("relabel", self.verdict(car["id"], "vlm"))

    def queue(self, event_id, *guesses):
        train = os.path.join(self._dir.name, "known_cars", "train")
        os.makedirs(train, exist_ok=True)
        start = float(event_id.split("-")[0])
        for i, guess in enumerate(guesses):
            open(os.path.join(train, f"{event_id}-{start + i:.6f}-{guess}.webp"), "wb").close()

    def test_a_blue_tesla_the_classifier_was_sure_of_is_filed_into_andrews(self):
        car = self.driveway_car()
        relay.describe_car = lambda jpeg: CarCheckTest.saw("blue", model="Model Y")
        self.queue(car["id"], "andrews_tesla-1.0", "andrews_tesla-0.97", "andrews_tesla-1.0", "andrews_tesla-1.0")
        relay.second_opinions()
        self.assertEqual("keep", self.verdict(car["id"], "vlm"))
        relay.file_verified_crops()
        self.assertEqual("filed", self.verdict(car["id"], "verified"))
        filed = [(url.rsplit("/", 3)[1], body["category"], body["training_file"]) for url, body in self.posts if url.endswith("/categorize")]
        self.assertEqual(relay.VERIFIED_CROPS_PER_EVENT, len(filed))
        self.assertTrue(all(category == "andrews_tesla" and f.endswith("-1.0.webp") for _, category, f in filed), filed)
        self.assertEqual({"verdict": "keep", "classifier": "andrews_tesla", "name": "andrews_tesla", "verified": "looks",
                          "saw": {"colour": "blue", "make": "tesla", "model": "Model Y", "body": "suv"}, "plate_read": False, "filed": "filed"},
                         relay.car_check_json(car["id"]))

    def test_a_black_tesla_the_classifier_was_sure_was_andrews_stays_in_the_queue(self):
        car = self.driveway_car()
        relay.describe_car = lambda jpeg: CarCheckTest.saw("black")
        self.queue(car["id"], "andrews_tesla-1.0")
        relay.second_opinions()
        relay.file_verified_crops()
        self.assertEqual("clear", self.verdict(car["id"], "vlm"))
        self.assertEqual("unverified", self.verdict(car["id"], "verified"))
        self.assertEqual([], self.categorized())

    def test_a_car_seen_only_by_infrared_isnt_filed_on_the_classifiers_word(self):
        car = self.driveway_car()
        relay.describe_car = lambda jpeg: CarCheckTest.saw("unknown")
        self.queue(car["id"], "andrews_tesla-1.0")
        relay.second_opinions()
        relay.file_verified_crops()
        self.assertEqual("keep", self.verdict(car["id"], "vlm"))
        self.assertEqual("unverified", self.verdict(car["id"], "verified"))
        self.assertEqual([], self.categorized())

    def test_crops_wait_for_the_check_and_one_car_is_filed_at_most_hourly(self):
        car = self.driveway_car()
        relay.describe_car = lambda jpeg: CarCheckTest.saw("blue")
        self.queue(car["id"], "andrews_tesla-1.0")
        relay.file_verified_crops()
        self.assertIsNone(self.verdict(car["id"], "verified"), "not looked at yet")
        relay.second_opinions()
        relay.file_verified_crops()
        self.assertEqual("filed", self.verdict(car["id"], "verified"))
        again = dict(car, id=f"{self.now - 100:.6f}-drv2", start_time=self.now - 100, end_time=self.now - 70)
        self.events[again["id"]] = again
        self.visits.append(again)
        self.queue(again["id"], "andrews_tesla-1.0")
        relay.second_opinions()
        relay.file_verified_crops()
        self.assertEqual("enough", self.verdict(again["id"], "verified"))
        self.assertEqual(1, len(self.categorized()))

    def test_verified_crops_count_towards_a_retrain(self):
        relay.RETRAIN_AFTER = 2
        relay.record_check("e1", "street", "filed")
        relay.record_check("e2", "verified", "filed", json.dumps({"name": "andrews_tesla"}))
        relay.maybe_retrain()
        self.assertTrue(any(url.endswith("/train") for url, _ in self.posts))


class CarProfilesTest(unittest.TestCase):
    """Car profiles: seeded from the environment once, then the app's to edit."""

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._saved = {name: getattr(relay, name) for name in ("DB_PATH", "CONN", "HOUSEHOLD_CARS")}
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()

    def tearDown(self):
        relay.CONN.close()
        for name, value in self._saved.items():
            setattr(relay, name, value)
        self._dir.cleanup()

    def test_the_environment_seeds_strict_profiles_the_app_then_owns(self):
        relay.HOUSEHOLD_CARS = {"andrews_tesla": {"make": "tesla", "colour": ["blue", "black"]}}
        relay.load_car_profiles()
        self.assertEqual({"andrews_tesla": {"make": "tesla", "colour": "blue"}}, relay.HOUSEHOLD_CARS, "a list of colours becomes its first")
        relay.save_car_profile("andrews_tesla", {"make": "tesla", "model": "Model Y", "colour": "blue", "plate": "8ABC123"}, "andrew")
        relay.HOUSEHOLD_CARS = {"andrews_tesla": {"make": "tesla", "colour": ["blue", "black"]}}  # the next boot
        relay.load_car_profiles()
        self.assertEqual({"andrews_tesla": {"make": "tesla", "model": "Model Y", "colour": "blue", "plate": "8ABC123"}}, relay.HOUSEHOLD_CARS)
        relay.save_car_profile("andrews_tesla", None, "andrew")
        self.assertEqual({}, relay.HOUSEHOLD_CARS)
        relay.HOUSEHOLD_CARS = {"andrews_tesla": {"make": "tesla", "colour": ["blue", "black"]}}
        relay.load_car_profiles()
        self.assertEqual({}, relay.HOUSEHOLD_CARS, "removed in the app, not seeded again")

    def test_the_app_saves_only_what_the_vision_model_can_answer(self):
        saved, relay.require_frigate_session = relay.require_frigate_session, lambda request: "andrew"
        try:
            body = relay.put_car_profile("sarahs_car", {"make": "Tesla", "model": " Model Y ", "colour": "Red", "plate": "9xyz-789"}, object())
            self.assertEqual({"name": "sarahs_car", "display_name": "Sarah's Car", "make": "tesla", "model": "Model Y", "colour": "red", "plate": "9XYZ789"}, body)
            relay.put_car_profile("sarahs_car", {"make": "tesla", "colour": "", "plate": ""}, object())
            self.assertEqual({"make": "tesla"}, relay.HOUSEHOLD_CARS["sarahs_car"], "blanks clear a field")
            for bad in ({"colour": "teal"}, {"colour": "unknown"}, {"make": "yugo"}, {"plate": "9X"}, {"model": "x" * 41}):
                with self.assertRaises(relay.HTTPException, msg=str(bad)):
                    relay.put_car_profile("sarahs_car", bad, object())
            for name in ("none", "../x"):
                with self.assertRaises(relay.HTTPException, msg=name):
                    relay.put_car_profile(name, {}, object())
        finally:
            relay.require_frigate_session = saved


class FarCarsAgainstFrigate(_FakeFrigate):
    """The far-car round against a fake Frigate: which names it takes off, and which it leaves."""

    def setUp(self):
        super().setUp()
        self._saved["detect_sizes"] = relay.detect_sizes
        relay.detect_sizes = lambda: {"hikvision_1": (1280, 720)}
        relay.car_zone_polygons = lambda: {"hikvision_1": [CarCheckTest.DRIVEWAY]}
        relay.HOUSEHOLD_CARS = CarCheckTest.CARS

    def car(self, tag, data, name="andrews_tesla", score=0.93, age=300.0, ended=True, zones=()):
        start = self.now - age
        e = {"id": f"{start:.6f}-{tag}", "label": "car", "camera": "hikvision_1", "start_time": start,
             "end_time": start + 30 if ended else None, "zones": list(zones), "sub_label": name,
             "data": dict(data, sub_label_score=score)}
        self.visits.append(e)
        return e["id"]

    def test_the_suv_across_the_street_loses_the_name_once(self):
        suv = self.car("suv", CarCheckTest.SUV_ACROSS)
        relay.clear_far_car_names()
        self.assertEqual([{"subLabel": "", "subLabelScore": None}], self.sub_labels())
        self.assertEqual("cleared", self.verdict(suv, "far"))
        self.visits[0]["sub_label"] = None  # as Frigate has it now
        relay.clear_far_car_names()
        self.assertEqual(1, len(self.sub_labels()))

    def test_and_again_should_the_classifier_name_it_anew(self):
        self.car("suv", CarCheckTest.SUV_ACROSS, ended=False, age=120.0)
        relay.clear_far_car_names()
        relay.clear_far_car_names()  # Frigate still names it: taken off again
        self.assertEqual(2, len(self.sub_labels()))

    def test_near_cars_and_a_persons_tag_keep_their_names(self):
        curb = self.car("curb", CarCheckTest.CURB_TESLA)
        beam = self.car("beam", CarCheckTest.BEAM_CUT, name="sarahs_car")
        tagged = self.car("tagged", CarCheckTest.SUV_ACROSS, score=1.0)
        relay.state_set("person_tags_since", self.now)  # tagged before the table: its 1.0 is a person's
        unnamed = self.car("unnamed", CarCheckTest.SUV_ACROSS, name=None)
        relay.clear_far_car_names()
        self.assertEqual([], self.sub_labels())
        self.assertEqual(("near", "near", None, None), tuple(self.verdict(e, "far") for e in (curb, beam, tagged, unnamed)))

    def test_a_tag_taken_away_leaves_the_classifiers_name_to_be_judged(self):
        relay.state_set("person_tags_since", 0.0)
        suv = self.car("suv", CarCheckTest.SUV_ACROSS)
        relay.record_person_tag(suv, "andrews_tesla", "andrew")
        relay.clear_far_car_names()
        self.assertEqual([], self.sub_labels())
        relay.record_person_tag(suv, None, "andrew")  # untagged, and the classifier's name is back
        relay.clear_far_car_names()
        self.assertEqual("cleared", self.verdict(suv, "far"))

    def test_a_car_in_view_since_before_the_hour_is_still_looked_at(self):
        suv = self.car("suv", CarCheckTest.SUV_ACROSS, ended=False, age=2 * 3600.0)
        relay.clear_far_car_names()
        self.assertEqual("cleared", self.verdict(suv, "far"))
        self.assertEqual(1, len(self.sub_labels()), "found by both lookups, cleared once")

    def test_a_car_just_seen_is_looked_at_later(self):
        young = self.car("young", CarCheckTest.SUV_ACROSS, ended=False, age=10.0)
        near = self.car("near", CarCheckTest.CURB_TESLA, ended=False, age=120.0)
        relay.clear_far_car_names()
        self.assertEqual([], self.sub_labels())
        self.assertEqual((None, None), (self.verdict(young, "far"), self.verdict(near, "far")), "either may yet change")

    def test_a_refused_clear_is_tried_again(self):
        suv = self.car("suv", CarCheckTest.SUV_ACROSS)
        self.refuse.add("/sub_label")
        with self.assertRaises(Exception):
            relay.clear_far_car_names()
        self.assertIsNone(self.verdict(suv, "far"))
        self.refuse.clear()
        relay.clear_far_car_names()
        self.assertEqual("cleared", self.verdict(suv, "far"))

    def test_no_names_to_take_off_asks_frigate_nothing(self):
        relay.HOUSEHOLD_CARS = {}
        relay.CAR_CLASSIFIER = ""
        self.car("suv", CarCheckTest.SUV_ACROSS)
        relay.requests.get = lambda *a, **k: self.fail("no names, no lookup")
        relay.clear_far_car_names()


class BootReportTest(unittest.TestCase):
    HEALTHY = {"frigate": True, "cameras": {"hikvision_1": 5.0, "hikvision_2": 5.0, "amcrest_1": 5.1}, "recording_mb": 3_700_000, "vlm": True, "webrtc": True}

    def test_all_back(self):
        self.assertEqual(("Server restarted", "Back since 5:33 PM · all 3 cameras · recording drive OK"),
                         relay.boot_report_text(self.HEALTHY, "5:33 PM"))

    def test_a_dead_camera_and_the_boot_disk_are_named(self):
        health = dict(self.HEALTHY, cameras={"hikvision_1": 5.0, "amcrest_1": 0.0}, recording_mb=420_000)
        title, body = relay.boot_report_text(health, "5:33 PM")
        self.assertEqual("Server restarted with problems", title)
        self.assertEqual("Back since 5:33 PM: no video from Front Door; recordings aren't on the 4 TB drive", body)

    def test_frigate_down(self):
        title, body = relay.boot_report_text({"frigate": False, "cameras": {}, "recording_mb": None, "vlm": None}, "5:33 PM")
        self.assertEqual("Back since 5:33 PM: Frigate isn't answering", body)

    def test_vision_model_missing_is_a_problem_only_when_ollama_is_configured(self):
        self.assertIn("vision model not loaded", relay.boot_report_text(dict(self.HEALTHY, vlm=False), "5:33 PM")[1])
        self.assertEqual("Server restarted", relay.boot_report_text(dict(self.HEALTHY, vlm=None), "5:33 PM")[0])

    def test_webrtc_that_never_started_is_a_problem(self):
        title, body = relay.boot_report_text(dict(self.HEALTHY, webrtc=False), "1:23 PM")
        self.assertEqual("Server restarted with problems", title)
        self.assertIn("HLS fallback", body)
        self.assertEqual("Server restarted", relay.boot_report_text(dict(self.HEALTHY, webrtc=None), "1:23 PM")[0])

    def test_udp_port_listening(self):
        # Shape of /proc/net/udp: go2rtc on 192.168.68.65:8555 (0x216B), and something else on 53.
        table = (
            "   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n"
            "  103: 4144A8C0:216B 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 45121 2 0000000000000000 0\n"
            "  201: 3500007F:0035 00000000:0000 07 00000000:00000000 00:00000000 00000000   101        0 17005 2 0000000000000000 0\n"
        )
        self.assertTrue(relay.udp_port_listening(table, 8555))
        self.assertTrue(relay.udp_port_listening(table, 53))
        self.assertFalse(relay.udp_port_listening(table, 1984))
        self.assertFalse(relay.udp_port_listening(table.splitlines()[0], 8555))

    def test_clock_text(self):
        from zoneinfo import ZoneInfo
        self.assertEqual("5:33 PM", relay.clock_text(1790296380.0, ZoneInfo("America/Los_Angeles")))
        self.assertEqual("12:33 AM UTC", relay.clock_text(1790296380.0))


class GoogleTokensTest(unittest.TestCase):
    """Google Home's account-link and stream tokens, against a scratch database."""

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._path, self._conn = relay.DB_PATH, relay.CONN
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()

    def tearDown(self):
        relay.CONN.close()
        relay.DB_PATH, relay.CONN = self._path, self._conn
        self._dir.cleanup()

    def test_a_token_names_its_subject_until_it_expires(self):
        token = relay.google_issue("access", "andrew", 3600, now=1000.0)
        self.assertEqual("andrew", relay.google_check(token, "access", now=4599.0))
        self.assertIsNone(relay.google_check(token, "access", now=4601.0))

    def test_a_token_is_only_good_for_its_kind(self):
        token = relay.google_issue("stream", "amcrest_1", 120, now=1000.0)
        self.assertIsNone(relay.google_check(token, "access", now=1001.0))
        self.assertEqual("amcrest_1", relay.google_check(token, "stream", now=1001.0))

    def test_a_code_is_good_once(self):
        code = relay.google_issue("code", "andrew", 600, now=1000.0)
        self.assertEqual("andrew", relay.google_check(code, "code", consume=True, now=1001.0))
        self.assertIsNone(relay.google_check(code, "code", consume=True, now=1002.0))

    def test_a_refresh_token_lasts_until_unlinked(self):
        refresh = relay.google_issue("refresh", "andrew", None, now=1000.0)
        self.assertEqual("andrew", relay.google_check(refresh, "refresh", now=1e12))
        relay.google_unlink()
        self.assertIsNone(relay.google_check(refresh, "refresh", now=1001.0))

    def test_only_the_hash_is_stored_and_empty_or_unknown_tokens_fail(self):
        token = relay.google_issue("access", "andrew", 3600, now=1000.0)
        stored = relay.with_db(lambda c: [r[0] for r in c.execute("SELECT hash FROM google_tokens")])
        self.assertNotIn(token, stored)
        self.assertIsNone(relay.google_check("", "access", now=1001.0))
        self.assertIsNone(relay.google_check("nope", "access", now=1001.0))


class GoogleHomeTest(unittest.TestCase):
    CAMERAS = {"amcrest_1": "amcrest_1_sub", "hikvision_1": "hikvision_1_sub"}

    def test_redirect_must_be_googles_for_our_project(self):
        ok = relay.google_redirect_ok
        self.assertTrue(ok("https://oauth-redirect.googleusercontent.com/r/homesafe-1234", "homesafe-1234"))
        self.assertTrue(ok("https://oauth-redirect-sandbox.googleusercontent.com/r/homesafe-1234", "homesafe-1234"))
        self.assertFalse(ok("https://oauth-redirect.googleusercontent.com/r/someone-else", "homesafe-1234"))
        self.assertFalse(ok("https://evil.example/r/homesafe-1234", "homesafe-1234"))
        self.assertFalse(ok("https://oauth-redirect.googleusercontent.com.evil.example/r/homesafe-1234", "homesafe-1234"))
        self.assertFalse(ok("https://oauth-redirect.googleusercontent.com/r/homesafe-1234?next=https://evil.example", "homesafe-1234"))
        # Without a project id nothing is accepted, not even Google's.
        self.assertFalse(ok("https://oauth-redirect.googleusercontent.com/r/homesafe-1234", ""))
        self.assertFalse(ok("https://oauth-redirect.googleusercontent.com/r/", ""))

    def test_sign_ins_in_flight_together_share_the_limit(self):
        attempts = []
        # Every one is counted before its password is checked, so a burst stops at the limit.
        self.assertEqual([True] * relay.GOOGLE_LOGIN_LIMIT + [False], [relay.reserve_login(attempts, 1000.0 + i) for i in range(relay.GOOGLE_LOGIN_LIMIT + 1)])
        # A correct password hands its reservation back.
        attempts.remove(1000.0)
        self.assertTrue(relay.reserve_login(attempts, 1010.0))
        # And the window passes.
        self.assertTrue(relay.reserve_login(attempts, 1010.0 + relay.GOOGLE_LOGIN_WINDOW_SECONDS))

    def test_only_cameras_given_a_stream_that_frigate_still_has_are_offered(self):
        known = {"amcrest_1": ["amcrest_1", "amcrest_1_sub"], "hikvision_1": ["hikvision_1", "hikvision_1_sub"]}
        self.assertEqual({"amcrest_1": "amcrest_1_sub"}, relay.google_offered({"amcrest_1": "amcrest_1_sub", "gone": "gone_sub"}, known))
        self.assertEqual({}, relay.google_offered({}, known))

    def test_display_answer_makes_audio_one_way_and_leaves_video(self):
        answer = "\r\n".join([
            "v=0", "a=group:BUNDLE 0 1",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:0", "a=sendrecv",
            "m=video 9 UDP/TLS/RTP/SAVPF 96", "a=mid:1", "a=sendonly", "",
        ])
        out = relay.display_answer(answer)
        self.assertEqual(["a=sendonly", "a=sendonly"], [l for l in out.split("\r\n") if l.startswith("a=send") or l.startswith("a=recv")])
        self.assertTrue(out.endswith("\r\n"))
        # A stream with no audio: go2rtc would take the display's microphone; answer inactive instead.
        silent = answer.replace("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=sendrecv", "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\na=recvonly")
        self.assertIn("a=mid:0\r\na=inactive", relay.display_answer(silent))
        # Video's direction is never touched, even if it were sendrecv.
        self.assertIn("a=mid:1\r\na=sendrecv", relay.display_answer(answer.replace("a=mid:1\r\na=sendonly", "a=mid:1\r\na=sendrecv")))
        # Bare newlines too.
        self.assertIn("a=mid:0\na=sendonly", relay.display_answer(answer.replace("\r\n", "\n")))

    def test_basic_credentials(self):
        import base64

        header = "Basic " + base64.b64encode(b"homesafe:s3cret:with-colon").decode()
        self.assertEqual(("homesafe", "s3cret:with-colon"), relay.basic_credentials(header))
        self.assertEqual(("", ""), relay.basic_credentials("Basic not*base64=="))
        self.assertEqual(("", ""), relay.basic_credentials("Basic " + base64.b64encode(b"\xff\xfe:x").decode()))

    def test_sync_lists_each_camera_by_its_app_name(self):
        devices = relay.google_sync("r1", self.CAMERAS)["payload"]["devices"]
        self.assertEqual(["amcrest_1", "hikvision_1"], [d["id"] for d in devices])
        self.assertEqual("Front Door", devices[0]["name"]["name"])
        self.assertEqual(["webrtc"], devices[0]["attributes"]["cameraStreamSupportedProtocols"])

    def test_query(self):
        states = relay.google_query("r1", {"devices": [{"id": "amcrest_1"}, {"id": "gone"}]}, self.CAMERAS)["payload"]["devices"]
        self.assertTrue(states["amcrest_1"]["online"])
        self.assertEqual("deviceNotFound", states["gone"]["errorCode"])

    def execute(self, camera, command=None, protocols=None):
        params = {"StreamToChromecast": True, "SupportedStreamProtocols": protocols or ["hls", "webrtc"]}
        payload = {"commands": [{"devices": [{"id": camera}], "execution": [{"command": command or relay.GET_CAMERA_STREAM, "params": params}]}]}
        return relay.google_execute("r1", payload, self.CAMERAS, lambda cam: f"token-for-{cam}")["payload"]["commands"][0]

    def test_execute_hands_out_a_signaling_address_with_its_token(self):
        result = self.execute("amcrest_1")
        self.assertEqual("SUCCESS", result["status"])
        states = result["states"]
        self.assertEqual("webrtc", states["cameraStreamProtocol"])
        self.assertEqual("token-for-amcrest_1", states["cameraStreamAuthToken"])
        self.assertTrue(states["cameraStreamSignalingUrl"].endswith("/google/signal/amcrest_1?token=token-for-amcrest_1"))

    def test_execute_refuses_what_it_cant_do(self):
        self.assertEqual("deviceNotFound", self.execute("gone")["errorCode"])
        self.assertEqual("notSupported", self.execute("amcrest_1", protocols=["hls"])["errorCode"])
        self.assertEqual("functionNotSupported", self.execute("amcrest_1", command="action.devices.commands.OnOff")["errorCode"])


class DisplayNameTest(unittest.TestCase):
    """The relay words a car's name as the app's DetectionNames.kt does."""

    def test_the_apostrophe_comes_back_for_an_owners_vehicle(self):
        self.assertEqual("Andrew's Tesla", relay.display_name("andrews_tesla"))
        self.assertEqual("Yaya's Car", relay.display_name("yayas_car"))
        self.assertEqual("In-Laws' Mercedes", relay.display_name("in-laws_mercedes"))
        self.assertEqual("James's Car", relay.display_name("james_car"))
        self.assertEqual("Parents' Van", relay.display_name("parents_van"))
        self.assertEqual("Yaya's BMW", relay.display_name("yayas_bmw"))

    def test_anything_else_is_left_alone(self):
        self.assertEqual("Andrews", relay.display_name("andrews"), "a face, not a vehicle")
        self.assertEqual("Sarah", relay.display_name("sarah"))
        self.assertEqual("Sarah's Tesla", relay.display_name("sarahs_tesla"), "spelled out")

    def test_told(self):
        self.assertEqual("Andrew's Tesla left the driveway", relay.told("Andrew's Tesla", "driveway", "left"))
        self.assertEqual("Car arrived in the driveway", relay.told("Car", "driveway", "arrived"))
        self.assertEqual("Car moved in the driveway", relay.told("Car", "driveway", "moved"))
        self.assertEqual("Andrew's Tesla in the driveway", relay.told("Andrew's Tesla", "driveway", "parked"))
        self.assertEqual("Person on the front lawn", relay.told("Person", "front_lawn"))
        self.assertEqual("Car arrived", relay.told("Car", None, "arrived"))
        self.assertEqual("Car detected", relay.told("Car", None))


# A driveway for the memory's tests: the left half of the frame's lower half.
DRIVEWAY = [(0.1, 0.4), (0.6, 0.4), (0.6, 0.95), (0.1, 0.95)]
CAR_BOX = [0.25, 0.4, 0.2, 0.2]
AT_SPOT = [(0.35, 0.6), (0.36, 0.61), (0.35, 0.6), (0.34, 0.6), (0.35, 0.61)]
JITTER = [(0.35, 0.6), (0.30, 0.6), (0.40, 0.6), (0.35, 0.62), (0.31, 0.6)]
ARRIVING = [(0.95, 0.5), (0.85, 0.52), (0.75, 0.55), (0.62, 0.58), (0.5, 0.6), (0.4, 0.6), (0.35, 0.6)]
LEAVING = list(reversed(ARRIVING))
ACROSS = [(0.35, 0.45), (0.35, 0.55), (0.35, 0.65), (0.35, 0.75), (0.35, 0.85), (0.35, 0.93)]


def car_event(event_id, path, start, end=None, name=None, score=None, box=CAR_BOX, zones=("driveway",), camera="hikvision_1"):
    return {"id": event_id, "label": "car", "camera": camera, "start_time": start, "end_time": end, "zones": list(zones),
            "sub_label": name, "data": {"type": "object", "box": list(box), "sub_label_score": score,
                                        "path_data": [[[x, y], start + i] for i, (x, y) in enumerate(path)]}}


class _ScratchDb(unittest.TestCase):
    NAMES = ("DB_PATH", "CONN", "HOUSEHOLD_CARS", "car_zones", "car_zone_polygons", "car_zone_outlines", "event_detail")

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._saved = {name: getattr(relay, name) for name in self.NAMES}
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()
        relay.HOUSEHOLD_CARS = CarCheckTest.CARS
        relay.car_zones = lambda: {"hikvision_1": ["driveway"], "amcrest_1": []}
        relay.car_zone_polygons = lambda: {"hikvision_1": [DRIVEWAY]}
        relay.car_zone_outlines = lambda: {"hikvision_1": {"driveway": DRIVEWAY}}
        self.events = {}
        relay.event_detail = lambda event_id: self.events.get(event_id)
        self.T = time.time() - 7200

    def tearDown(self):
        relay.CONN.close()
        for name, value in self._saved.items():
            setattr(relay, name, value)
        self._dir.cleanup()

    def observe(self, event, now=None):
        self.events[event["id"]] = event
        return relay.observe_car(event, now if now is not None else (event["end_time"] or event["start_time"] + 30))

    def here(self, name):
        v = relay.vehicle("hikvision_1", name)
        return bool(v and v["here"])


class MovementTest(unittest.TestCase):
    def test_what_a_car_did_in_the_driveway(self):
        polys = [DRIVEWAY]
        self.assertEqual("arrived", relay.movement_of(car_event("a", ARRIVING, 0), polys))
        self.assertEqual("left", relay.movement_of(car_event("l", LEAVING, 0), polys))
        self.assertEqual("moved", relay.movement_of(car_event("m", ACROSS, 0), polys))
        self.assertEqual("parked", relay.movement_of(car_event("p", AT_SPOT, 0), polys))
        self.assertEqual("parked", relay.movement_of(car_event("j", JITTER, 0), polys), "the box flickering is not a move")
        self.assertEqual("parked", relay.movement_of(car_event("f", AT_SPOT[:2], 0), polys))
        self.assertIsNone(relay.movement_of(car_event("s", DRIVE_PATH, 0, box=[0.6, 0.25, 0.14, 0.1]), polys), "went by outside")
        self.assertIsNone(relay.movement_of(dict(car_event("n", AT_SPOT, 0), data={}), polys), "no box")

    def test_without_the_outline_only_a_still_car_is_told(self):
        self.assertEqual("parked", relay.movement_of(car_event("p", AT_SPOT, 0), []))
        self.assertIsNone(relay.movement_of(car_event("a", ARRIVING, 0), []))

    def test_the_real_tesla_pulling_out_left(self):
        leaving = [(0.391, 0.547), (0.402, 0.56), (0.456, 0.526), (0.516, 0.499), (0.577, 0.467), (0.638, 0.451), (0.709, 0.44), (0.78, 0.444), (0.859, 0.461), (0.914, 0.482)]
        event = car_event("t", leaving, 0, box=[0.308, 0.344, 0.24, 0.197], zones=())
        self.assertEqual("left", relay.movement_of(event, [CarCheckTest.DRIVEWAY]))
        self.assertTrue(relay.in_car_zone(event, ["driveway"], [CarCheckTest.DRIVEWAY]), "no zone tag, but its path began inside")


class VehicleMemoryTest(_ScratchDb):
    """Where the household's cars are parked, and what that makes of the cars Frigate didn't name."""

    def park_andrews_tesla(self):
        return self.observe(car_event("tagged", AT_SPOT, self.T, self.T + 60, name="andrews_tesla", score=1.0))

    def test_a_tagged_car_is_remembered_where_it_parked(self):
        story = self.park_andrews_tesla()
        self.assertEqual(("andrews_tesla", "tagged", "parked"), (story["name"], story["how"], story["movement"]))
        self.assertTrue(self.here("andrews_tesla"))
        self.assertEqual(self.T, relay.vehicle("hikvision_1", "andrews_tesla")["since"])

    def test_an_unnamed_car_where_it_is_parked_is_it(self):
        self.park_andrews_tesla()
        story = self.observe(car_event("flicker", JITTER, self.T + 600, self.T + 640))
        self.assertEqual(("andrews_tesla", "parked", "parked"), (story["name"], story["how"], story["movement"]))

    def test_it_is_named_when_it_leaves_and_forgotten_once_gone(self):
        self.park_andrews_tesla()
        story = self.observe(car_event("out", LEAVING, self.T + 900, self.T + 930))
        self.assertEqual(("andrews_tesla", "parked", "left"), (story["name"], story["how"], story["movement"]))
        self.assertFalse(self.here("andrews_tesla"))
        later = self.observe(car_event("stranger", JITTER, self.T + 1200, self.T + 1230))
        self.assertIsNone(later["name"], "an empty spot names nobody")

    def test_an_old_event_seen_late_doesnt_bring_back_a_car_that_left(self):
        self.park_andrews_tesla()
        self.observe(car_event("out", LEAVING, self.T + 900, self.T + 930))
        self.observe(car_event("before", AT_SPOT, self.T + 100, self.T + 160, name="andrews_tesla", score=0.98))
        self.assertFalse(self.here("andrews_tesla"))
        self.observe(car_event("back", ARRIVING, self.T + 3000, self.T + 3030, name="andrews_tesla", score=0.98))
        self.assertTrue(self.here("andrews_tesla"))
        self.assertEqual(self.T + 3000, relay.vehicle("hikvision_1", "andrews_tesla")["since"])

    def test_a_long_stay_still_in_view_leaving_is_a_departure(self):
        # Remembered first from a flicker the classifier named, while Frigate still tracks the car it has seen all morning.
        self.observe(car_event("flicker", AT_SPOT, self.T + 600, self.T + 630, name="andrews_tesla", score=0.98))
        self.observe(car_event("morning", LEAVING, self.T, None), now=self.T + 1000)
        self.assertFalse(self.here("andrews_tesla"))

    def test_a_car_arriving_in_a_remembered_cars_spot_means_that_car_is_gone(self):
        self.park_andrews_tesla()
        story = self.observe(car_event("visitor", ARRIVING, self.T + 5000, self.T + 5040))
        self.assertEqual((None, None, "arrived"), (story["name"], story["how"], story["movement"]), "an arrival is never named by the spot")
        self.assertFalse(self.here("andrews_tesla"))

    def test_but_not_while_it_is_still_seen_there(self):
        self.park_andrews_tesla()
        self.observe(car_event("still-there", AT_SPOT, self.T + 4000, None), now=self.T + 6000)
        self.observe(car_event("visitor", ARRIVING, self.T + 5000, self.T + 5040))
        self.assertTrue(self.here("andrews_tesla"))

    def test_a_name_frigate_takes_back_takes_back_the_memory_too(self):
        self.observe(car_event("misnamed", AT_SPOT, self.T, self.T + 60, name="andrews_tesla", score=0.98))
        self.assertTrue(self.here("andrews_tesla"))
        story = self.observe(car_event("misnamed", AT_SPOT, self.T, self.T + 60))
        self.assertEqual((None, "not"), (story["name"], story["how"]), "and it isn't guessed straight back from its own spot")
        self.assertFalse(self.here("andrews_tesla"))

    def test_a_named_car_pulling_into_anothers_spot_moves_that_one_out(self):
        self.park_andrews_tesla()
        self.observe(car_event("sarah", ARRIVING, self.T + 5000, self.T + 5040, name="sarahs_car", score=0.98))
        self.assertTrue(self.here("sarahs_car"))
        self.assertFalse(self.here("andrews_tesla"), "two cars can't stand in one spot")
        later = self.observe(car_event("flicker", JITTER, self.T + 6000, self.T + 6030))
        self.assertEqual("sarahs_car", later["name"])

    def test_each_sighting_is_filed_under_the_zone_it_was_in(self):
        garage = [(0.65, 0.4), (0.95, 0.4), (0.95, 0.95), (0.65, 0.95)]
        relay.car_zones = lambda: {"hikvision_1": ["driveway", "garage"]}
        relay.car_zone_polygons = lambda: {"hikvision_1": [DRIVEWAY, garage]}
        relay.car_zone_outlines = lambda: {"hikvision_1": {"driveway": DRIVEWAY, "garage": garage}}
        in_garage = [(0.8, 0.6), (0.81, 0.6), (0.8, 0.61)]
        self.assertEqual("garage", self.observe(car_event("tagged", in_garage, self.T, self.T + 30, zones=("garage",)))["zone"])
        self.assertEqual("garage", self.observe(car_event("untagged", in_garage, self.T, self.T + 30, zones=()))["zone"], "by its path")
        self.assertEqual("driveway", self.observe(car_event("drive", AT_SPOT, self.T, self.T + 30))["zone"])

    def test_cars_outside_the_car_zones_and_other_things_are_not_filed(self):
        self.assertIsNone(self.observe(car_event("street", DRIVE_PATH, self.T, self.T + 20, zones=(), box=[0.6, 0.25, 0.14, 0.1])))
        self.assertIsNone(self.observe(dict(car_event("dog", AT_SPOT, self.T), label="dog")))
        self.assertIsNone(self.observe(car_event("door", AT_SPOT, self.T, camera="amcrest_1")))

    def test_the_review_item_carries_the_names_and_the_story(self):
        self.park_andrews_tesla()
        self.events["out"] = car_event("out", LEAVING, self.T + 900, self.T + 930)
        item = {"id": "r", "camera": "hikvision_1", "start_time": self.T + 900, "end_time": None,
                "data": {"objects": ["car"], "sub_labels": [], "zones": ["driveway"], "detections": ["out"]}}
        told = relay.with_vehicle_memory(item)
        self.assertEqual(["andrews_tesla"], told["data"]["sub_labels"])
        self.assertEqual([{"event_id": "out", "name": "andrews_tesla", "how": "parked", "movement": "left"}], told["data"]["vehicles"])
        self.assertFalse(relay.is_unnamed_car(told), "no Tag car button for a car the memory knows")
        self.assertEqual({"car:andrews_tesla"}, relay.review_kinds(told))
        visit = relay.Visit(told)
        visit.add(told, self.T + 930)
        self.assertEqual(("Front Yard", "Andrew's Tesla left the driveway"), visit.sentence(["driveway"]))

    def flicker_item(self, *detections, ended=False, objects=("car",)):
        return {"id": "r", "camera": "hikvision_1", "start_time": time.time() - 30, "end_time": time.time() if ended else None,
                "data": {"objects": list(objects), "sub_labels": [], "zones": ["driveway"], "detections": list(detections)}}

    def test_remembered_cars_that_stayed_parked_are_not_news(self):
        self.park_andrews_tesla()
        self.events["flicker"] = car_event("flicker", JITTER, self.T + 600, None)
        told = relay.with_vehicle_memory(self.flicker_item("flicker"))
        self.assertEqual([("andrews_tesla", "parked")], [(v["name"], v["movement"]) for v in told["data"]["vehicles"]])
        self.assertEqual("wait", relay.parked_verdict(told), "it may yet drive off")
        self.assertEqual("skip", relay.parked_verdict(relay.with_vehicle_memory(self.flicker_item("flicker", ended=True))))

    def test_a_remembered_car_driving_off_is_news(self):
        self.park_andrews_tesla()
        self.events["out"] = car_event("out", LEAVING, self.T + 600, None)
        self.assertEqual("push", relay.parked_verdict(relay.with_vehicle_memory(self.flicker_item("out", ended=True))))

    def test_a_car_it_doesnt_know_or_a_person_is_news(self):
        self.park_andrews_tesla()
        self.events["flicker"] = car_event("flicker", JITTER, self.T + 600, None)
        self.events["unknown"] = car_event("unknown", [(0.2, 0.85), (0.21, 0.85), (0.2, 0.86)], self.T + 600, None, box=[0.15, 0.75, 0.1, 0.1])
        both = relay.with_vehicle_memory(self.flicker_item("flicker", "unknown", ended=True))
        self.assertEqual(["andrews_tesla", None], [v["name"] for v in both["data"]["vehicles"]])
        self.assertEqual("push", relay.parked_verdict(both))
        person = relay.with_vehicle_memory(self.flicker_item("flicker", "person-1", ended=True, objects=("car", "person")))
        self.assertEqual("push", relay.parked_verdict(person))

    def test_the_memory_never_stops_an_alert(self):
        relay.event_detail = lambda event_id: (_ for _ in ()).throw(RuntimeError("boom"))
        item = {"id": "r", "camera": "hikvision_1", "data": {"objects": ["car"], "detections": ["x"]}}
        self.assertIs(item, relay.with_vehicle_memory(item))
        person = {"id": "p", "data": {"objects": ["person"], "detections": ["x"]}}
        self.assertIs(person, relay.with_vehicle_memory(person))

    def test_notes(self):
        self.assertEqual("Andrew's Tesla left the driveway · blue tesla Model Y suv",
                         relay.event_note({"name": "andrews_tesla", "how": "parked", "movement": "left", "zone": "driveway", "saw": "blue tesla Model Y suv"}))
        self.assertEqual("Car arrived in the driveway", relay.event_note({"name": None, "movement": "arrived", "zone": "driveway"}))
        self.assertEqual("Andrew's Tesla in the driveway", relay.event_note({"name": "andrews_tesla", "how": "parked", "movement": "parked", "zone": "driveway"}))
        self.assertEqual("", relay.event_note({"name": "andrews_tesla", "how": "classifier", "movement": "parked", "zone": "driveway"}), "Frigate names it already")

    def test_the_snapshot(self):
        self.park_andrews_tesla()
        self.observe(car_event("out", LEAVING, self.T + 900, self.T + 930))
        snap = relay.vehicle_memory_snapshot(now=self.T + 1000)
        self.assertEqual([("andrews_tesla", "Andrew's Tesla", False)], [(v["name"], v["display_name"], v["here"]) for v in snap["vehicles"]])
        self.assertEqual(["Andrew's Tesla left the driveway"], [e["text"] for e in snap["recent"]])


class VisitMovementTest(unittest.TestCase):
    def item(self, rid, vehicles, objects=("car",), sub_labels=()):
        return {"id": rid, "camera": "hikvision_1", "start_time": 0.0, "end_time": 10.0,
                "data": {"objects": list(objects), "sub_labels": list(sub_labels), "zones": ["driveway"], "vehicles": vehicles}}

    def visit(self, *items):
        visit = relay.Visit(items[0])
        for item in items:
            visit.add(item, 10.0)
        return visit

    def test_one_car_is_told_by_its_latest_move(self):
        a = self.item("a", [{"name": None, "movement": "arrived"}])
        b = self.item("b", [{"name": None, "movement": "parked"}])
        self.assertEqual("Car arrived in the driveway · 2 alerts", self.visit(a, b).sentence(["driveway"])[1])

    def test_two_cars_or_a_person_are_not(self):
        a = self.item("a", [{"name": "andrews_tesla", "movement": "parked"}], sub_labels=["andrews_tesla"])
        b = self.item("b", [{"name": None, "movement": "arrived"}])
        self.assertEqual("Andrew's Tesla in the driveway · 2 alerts", self.visit(a, b).sentence(["driveway"])[1])
        c = self.item("c", [{"name": None, "movement": "left"}], objects=("car", "person"))
        self.assertEqual("Person and car in the driveway", self.visit(c).sentence(["driveway"])[1])


class FollowupsTest(unittest.TestCase):
    """A car's notification is told again, quietly, once the memory knows more."""

    def setUp(self):
        self._real = relay.with_vehicle_memory
        self.known = {}
        relay.with_vehicle_memory = lambda item: dict(item, data=dict(item["data"], **self.known.get(item["id"], {})))
        self.pushes = []

    def tearDown(self):
        relay.with_vehicle_memory = self._real

    def push(self, title, body, data, familiar=False):
        self.pushes.append((title, body, data))
        return {"sent": 1}

    def test_a_name_and_a_departure_found_later_rewrite_the_notification_quietly(self):
        item = review("r", 1000.0, ["car"])
        visit = relay.Visit(item)
        visit.add(item, 1005.0)
        followups = relay.Followups()
        followups.track(visit, "Car in the driveway", {"review_id": "r", "notif_id": "r", "car_unnamed": "1"}, 1005.0)
        followups.run([item], {"hikvision_1": ["driveway"]}, 1010.0, push=self.push)
        self.assertEqual([], self.pushes, "not due yet")
        followups.run([item], {"hikvision_1": ["driveway"]}, 1040.0, push=self.push)
        self.assertEqual([], self.pushes, "nothing new: nothing pushed")
        self.known["r"] = {"sub_labels": ["andrews_tesla"], "vehicles": [{"name": "andrews_tesla", "movement": "left"}]}
        followups.run([item], {"hikvision_1": ["driveway"]}, 1080.0, push=self.push)
        self.assertEqual([("Front Yard", "Andrew's Tesla left the driveway", {"review_id": "r", "notif_id": "r", "silent": "1"})], self.pushes)
        followups.run([item], {"hikvision_1": ["driveway"]}, 1120.0, push=self.push)
        self.assertEqual(1, len(self.pushes), "told once")

    def test_it_gives_up_after_a_while_and_ignores_visits_without_cars(self):
        item = review("r", 1000.0, ["car"])
        visit = relay.Visit(item)
        visit.add(item, 1005.0)
        followups = relay.Followups()
        followups.track(visit, "Car in the driveway", {}, 1005.0)
        self.known["r"] = {"sub_labels": ["andrews_tesla"]}
        followups.run([], {}, 1005.0 + relay.FOLLOWUP_SECONDS + 1, push=self.push)
        self.assertEqual([], self.pushes)
        person = review("p", 1000.0, ["person"])
        visit = relay.Visit(person)
        visit.add(person, 1005.0)
        followups.track(visit, "Person in the driveway", {}, 1005.0)
        self.assertEqual({}, followups.by_visit)


class MemoryVerdictTest(unittest.TestCase):
    TESLA = {"make": "tesla", "colour": ["blue", "black"]}

    def seen(self, colour="blue", make="tesla"):
        return {"colour": colour, "make": make, "model": "", "body": "suv", "delivery": "none"}

    def test_verdicts(self):
        self.assertEqual("confirm", relay.memory_verdict(self.seen(), self.TESLA, "yes"))
        self.assertEqual("reject", relay.memory_verdict(self.seen(), self.TESLA, "no"))
        self.assertEqual("reject", relay.memory_verdict(self.seen("red"), self.TESLA, "yes"), "never red")
        self.assertEqual("reject", relay.memory_verdict(self.seen(make="toyota"), self.TESLA, None))
        self.assertEqual("confirm", relay.memory_verdict(self.seen(), self.TESLA, None), "its own make, no picture to compare")
        self.assertEqual("unsure", relay.memory_verdict(self.seen("unknown", "unknown"), self.TESLA, None), "infrared, no badge")
        self.assertEqual("unsure", relay.memory_verdict(self.seen(), self.TESLA, "unsure"))
        self.assertEqual("unsure", relay.memory_verdict(self.seen("white", "unknown"), {}, None), "nothing known of it")

    def test_learned_looks(self):
        looks = relay.learn_looks({}, self.seen())
        looks = relay.learn_looks(looks, self.seen("black", "unknown"))
        self.assertEqual({"colour": ["blue", "black"], "make": "tesla", "body": "suv"}, looks)
        self.assertEqual({"make": "tesla"}, relay.expected_looks("grandmas_tesla", looks), "learned colours rule nothing out")
        saved, relay.HOUSEHOLD_CARS = relay.HOUSEHOLD_CARS, CarCheckTest.CARS
        try:
            self.assertEqual(CarCheckTest.CARS["andrews_tesla"], relay.expected_looks("andrews_tesla", looks), "the configured looks win")
        finally:
            relay.HOUSEHOLD_CARS = saved


class VehicleMemoryAgainstFrigate(_ScratchDb):
    """The memory's round and the vision model's part in it, against a fake Frigate."""

    NAMES = _ScratchDb.NAMES + ("OLLAMA", "describe_car", "car_picture", "same_car")

    def setUp(self):
        super().setUp()
        self._requests = (relay.requests.get, relay.requests.post)
        relay.requests.get, relay.requests.post = self.get, self.post
        relay.OLLAMA = "http://ollama"
        relay.car_picture = lambda event: b"jpeg"
        self.saw = {"colour": "blue", "make": "tesla", "model": "Model Y", "body": "suv", "delivery": "none"}
        relay.describe_car = lambda jpeg: self.saw
        self.answers = []
        relay.same_car = lambda reference, picture, prompt: (self.answers.append(prompt), self.same)[1]
        self.same = "yes"
        self.posts = []
        self.listed = []
        self.now = time.time()
        relay._vehicle_history_read.clear()

    def tearDown(self):
        relay.requests.get, relay.requests.post = self._requests
        super().tearDown()

    def get(self, url, params=None, timeout=None):
        if url.endswith("/api/events"):
            p = params or {}
            zones = set(p["zones"].split(",")) if p.get("zones") else None
            names = set(p["sub_labels"].split(",")) if p.get("sub_labels") else None
            listed = [e for e in self.events.values() if e["camera"] == p.get("camera") and p.get("after", float("-inf")) < e["start_time"] < p.get("before", float("inf"))
                      and (not p.get("in_progress") or e["end_time"] is None)
                      and (zones is None or zones & set(e.get("zones") or []))
                      and (names is None or e.get("sub_label") in names)]
            self.listed.append(p)
            listed.sort(key=lambda e: e["start_time"], reverse=True)
            return _Response(200, listed[:p.get("limit") or None])
        found = self.events.get(url.rsplit("/", 1)[1])
        return _Response(200, found) if found else _Response(404)

    def post(self, url, json=None, timeout=None):
        self.posts.append((url, json))
        event_id = url.split("/api/events/")[1].split("/")[0] if "/api/events/" in url else None
        if event_id in self.events and url.endswith("/sub_label"):
            self.events[event_id] = dict(self.events[event_id], sub_label=json["subLabel"] or None,
                                         data=dict(self.events[event_id]["data"], sub_label_score=json["subLabelScore"]))
        return _Response(200, {})

    def descriptions(self):
        return {url.split("/")[-2]: body["description"] for url, body in self.posts if url.endswith("/description")}

    def sub_labels(self):
        return [(url.split("/")[-2], body) for url, body in self.posts if url.endswith("/sub_label")]

    def test_a_tag_is_learned_and_what_the_car_did_goes_into_frigate(self):
        relay.OLLAMA = ""
        self.events["tagged"] = car_event("tagged", AT_SPOT, self.now - 3000, self.now - 2900, name="andrews_tesla", score=1.0)
        self.events["out"] = car_event("out", LEAVING, self.now - 600, self.now - 560)
        relay.vehicle_memory_round(self.now)
        self.assertEqual("left", relay.sighting("out")["movement"])
        self.assertEqual("andrews_tesla", relay.sighting("out")["name"])
        self.assertEqual({"out": "Andrew's Tesla left the driveway"}, self.descriptions())
        relay.vehicle_memory_round(self.now + 30)
        self.assertEqual(1, len(self.descriptions()), "written once")

    def test_a_late_tag_is_picked_up(self):
        self.events["e"] = car_event("e", AT_SPOT, self.now - 600, self.now - 500)
        relay.vehicle_memory_round(self.now)
        self.assertIsNone(relay.sighting("e")["name"])
        self.events["e"] = dict(self.events["e"], sub_label="andrews_tesla", data=dict(self.events["e"]["data"], sub_label_score=1.0))
        relay.vehicle_memory_round(self.now + 30)
        self.assertEqual("tagged", relay.sighting("e")["how"])
        self.assertTrue(self.here("andrews_tesla"))

    def test_a_brisk_departure_frigate_didnt_tag_is_still_found(self):
        relay.OLLAMA = ""
        self.observe(car_event("tagged", AT_SPOT, self.now - 3000, self.now - 2900, name="andrews_tesla", score=1.0))
        self.events["out"] = car_event("out", LEAVING, self.now - 600, self.now - 560, zones=())
        self.events["street"] = car_event("street", DRIVE_PATH, self.now - 500, self.now - 480, zones=(), box=[0.6, 0.05, 0.14, 0.1])
        relay.vehicle_memory_round(self.now)
        self.assertEqual(("andrews_tesla", "left"), (relay.sighting("out")["name"], relay.sighting("out")["movement"]))
        self.assertFalse(self.here("andrews_tesla"))
        self.assertIsNone(relay.sighting("street"))
        looked_up = []
        relay.event_detail = lambda event_id: (looked_up.append(event_id), self.events.get(event_id))[1]
        relay.vehicle_memory_round(self.now + 30)
        self.assertNotIn("street", looked_up, "a finished street car is looked up once")

    def test_a_tag_from_hours_ago_is_remembered(self):
        relay.OLLAMA = ""
        self.events["tagged"] = car_event("tagged", AT_SPOT, self.now - 5 * 3600, self.now - 5 * 3600 + 60, name="andrews_tesla", score=1.0)
        relay.vehicle_memory_round(self.now)
        self.assertTrue(self.here("andrews_tesla"))
        story = self.observe(car_event("flicker", JITTER, self.now - 60, self.now - 30))
        self.assertEqual(("andrews_tesla", "parked"), (story["name"], story["how"]))

    def test_nor_is_a_departure_frigate_didnt_tag_hours_ago_missed(self):
        relay.OLLAMA = ""
        self.events["tagged"] = car_event("tagged", AT_SPOT, self.now - 5 * 3600, self.now - 5 * 3600 + 60, name="andrews_tesla", score=1.0)
        self.events["out"] = car_event("out", LEAVING, self.now - 3 * 3600, self.now - 3 * 3600 + 40, zones=())
        relay.vehicle_memory_round(self.now)
        self.assertEqual(("andrews_tesla", "left"), (relay.sighting("out")["name"], relay.sighting("out")["movement"]))
        self.assertFalse(self.here("andrews_tesla"), "a stale spot names nobody")

    def test_the_day_is_read_at_start_and_hourly_and_the_hour_in_between(self):
        relay.OLLAMA = ""
        relay.vehicle_memory_round(self.now)
        self.assertEqual(3, len(self.listed))
        self.assertLessEqual(min(p["after"] for p in self.listed if p["after"]), self.now - relay.VEHICLE_STALE_SECONDS + 1)
        self.listed.clear()
        relay.vehicle_memory_round(self.now + 30)
        self.assertEqual(self.now + 30 - relay.VEHICLE_LOOKBACK_SECONDS, min(p["after"] for p in self.listed if p["after"]))
        self.listed.clear()
        relay.vehicle_memory_round(self.now + relay.VEHICLE_HISTORY_EVERY_SECONDS)
        self.assertEqual(self.now + relay.VEHICLE_HISTORY_EVERY_SECONDS - relay.VEHICLE_STALE_SECONDS, min(p["after"] for p in self.listed if p["after"]))

    def test_a_long_history_is_paged(self):
        relay.OLLAMA = ""
        saved, relay.CAR_ZONE_PAGE = relay.CAR_ZONE_PAGE, 2
        try:
            for i in range(5):
                self.events[f"s{i}"] = car_event(f"s{i}", DRIVE_PATH, self.now - 1000 * (i + 1), self.now - 1000 * (i + 1) + 20, zones=(), box=[0.6, 0.05, 0.14, 0.1])
            found = relay.events_since({"camera": "hikvision_1", "label": "car"}, self.now - 86400, pages=10)
            self.assertEqual(5, len(found))
            self.assertEqual(2, len(relay.events_since({"camera": "hikvision_1", "label": "car"}, self.now - 86400, pages=1)))
        finally:
            relay.CAR_ZONE_PAGE = saved

    def test_a_car_tagged_after_the_model_looked_still_gives_its_picture(self):
        self.events["e"] = car_event("e", AT_SPOT, self.now - 900, self.now - 800)
        relay.second_opinions()
        self.assertEqual("keep", CarCheckAgainstFrigate.verdict(self, "e", "vlm"))
        self.assertIsNone(relay.reference_picture("hikvision_1", "andrews_tesla"))
        self.events["e"] = dict(self.events["e"], sub_label="andrews_tesla", data=dict(self.events["e"]["data"], sub_label_score=1.0))
        relay.second_opinions()
        self.assertIsNotNone(relay.reference_picture("hikvision_1", "andrews_tesla"))
        self.assertEqual("keep", CarCheckAgainstFrigate.verdict(self, "e", "vlm"), "its verdict stands")
        seen = []
        relay.describe_car = lambda jpeg: (seen.append(jpeg), self.saw)[1]
        relay.second_opinions()
        self.assertEqual([], seen, "once")

    def test_a_judged_car_whose_name_isnt_a_persons_is_looked_up_once(self):
        self.saw = dict(self.saw, colour="unknown")  # night: no reference picture to learn from it
        self.events["e"] = car_event("e", AT_SPOT, self.now - 900, self.now - 800, name="andrews_tesla", score=0.98)
        relay.second_opinions()
        looked_up = []
        relay.event_detail = lambda event_id: (looked_up.append(event_id), self.events.get(event_id))[1]
        relay.second_opinions()
        relay.second_opinions()
        self.assertEqual(["e"], looked_up)
        self.events["e"] = dict(self.events["e"], sub_label="yayas_car")
        relay.second_opinions()
        self.assertEqual(["e", "e"], looked_up, "a new name is looked at again")

    def test_a_night_tag_is_tried_once(self):
        self.saw = dict(self.saw, colour="unknown")
        self.events["e"] = car_event("e", AT_SPOT, self.now - 900, self.now - 800, name="andrews_tesla", score=1.0)
        relay.second_opinions()
        self.assertIsNone(relay.reference_picture("hikvision_1", "andrews_tesla"))
        self.assertEqual("night", CarCheckAgainstFrigate.verdict(self, "e", "learn"))

    def test_the_first_look_at_a_tagged_car_is_its_reference(self):
        self.events["tagged"] = car_event("tagged", AT_SPOT, self.now - 900, self.now - 800, name="andrews_tesla", score=1.0)
        relay.second_opinions()
        self.assertIsNotNone(relay.reference_picture("hikvision_1", "andrews_tesla"))
        self.assertEqual({"colour": ["blue"], "make": "tesla", "model": "Model Y", "body": "suv"}, relay.vehicle("hikvision_1", "andrews_tesla")["looks"])
        self.assertEqual([], self.sub_labels())

    def test_a_car_named_by_its_spot_is_confirmed_by_the_picture(self):
        self.observe(car_event("tagged", AT_SPOT, self.now - 3000, self.now - 2900, name="andrews_tesla", score=1.0))
        relay.learn_vehicle("hikvision_1", "andrews_tesla", self.saw, b"reference", tagged=True)
        self.events["flicker"] = car_event("flicker", JITTER, self.now - 600, self.now - 560)
        relay.second_opinions()
        self.assertEqual([("flicker", {"subLabel": "andrews_tesla", "subLabelScore": relay.VLM_SCORE})], self.sub_labels())
        self.assertIn("Andrew's Tesla", self.answers[0])
        story = relay.sighting("flicker")
        self.assertEqual(("andrews_tesla", "looked"), (story["name"], story["how"]))
        self.assertEqual("Andrew's Tesla in the driveway · blue tesla Model Y suv", self.descriptions()["flicker"])
        relay.vehicle_memory_round(self.now)
        self.assertEqual("looked", relay.sighting("flicker")["how"], "Frigate's copy of the name doesn't change how it was given")

    def test_a_different_car_in_its_spot_is_turned_down_and_the_car_forgotten(self):
        self.observe(car_event("tagged", AT_SPOT, self.now - 3000, self.now - 2900, name="andrews_tesla", score=1.0))
        relay.learn_vehicle("hikvision_1", "andrews_tesla", self.saw, b"reference", tagged=True)
        self.events["flicker"] = car_event("flicker", JITTER, self.now - 600, self.now - 560)
        self.same = "no"
        relay.second_opinions()
        self.assertEqual([], self.sub_labels())
        self.assertEqual((None, "not"), (relay.sighting("flicker")["name"], relay.sighting("flicker")["how"]))
        self.assertFalse(self.here("andrews_tesla"))

    def test_a_car_arriving_is_recognised_from_the_cars_that_are_away(self):
        self.observe(car_event("tagged", AT_SPOT, self.now - 9000, self.now - 8900, name="andrews_tesla", score=1.0))
        relay.learn_vehicle("hikvision_1", "andrews_tesla", self.saw, b"reference", tagged=True)
        self.observe(car_event("out", LEAVING, self.now - 8000, self.now - 7960))
        self.events["home"] = car_event("home", ARRIVING, self.now - 600, self.now - 560)
        relay.second_opinions()
        self.assertEqual([("home", {"subLabel": "andrews_tesla", "subLabelScore": relay.VLM_SCORE})], self.sub_labels())
        self.assertTrue(self.here("andrews_tesla"))
        self.assertEqual("Andrew's Tesla arrived in the driveway · blue tesla Model Y suv", self.descriptions()["home"])

    def test_a_comparison_the_model_fails_leaves_the_guess_standing(self):
        self.observe(car_event("tagged", AT_SPOT, self.now - 3000, self.now - 2900, name="andrews_tesla", score=1.0))
        relay.learn_vehicle("hikvision_1", "andrews_tesla", self.saw, b"reference", tagged=True)
        self.events["flicker"] = car_event("flicker", JITTER, self.now - 600, self.now - 560)
        relay.same_car = lambda reference, picture, prompt: (_ for _ in ()).throw(RuntimeError("model can't take two images"))
        relay.second_opinions()
        self.assertEqual([], self.sub_labels())
        self.assertEqual(("andrews_tesla", "parked"), (relay.sighting("flicker")["name"], relay.sighting("flicker")["how"]))
        self.assertTrue(self.here("andrews_tesla"))

    def test_an_arrival_nothing_matches_stays_unnamed(self):
        self.observe(car_event("tagged", AT_SPOT, self.now - 9000, self.now - 8900, name="andrews_tesla", score=1.0))
        relay.learn_vehicle("hikvision_1", "andrews_tesla", self.saw, b"reference", tagged=True)
        self.observe(car_event("out", LEAVING, self.now - 8000, self.now - 7960))
        self.events["visitor"] = car_event("visitor", ARRIVING, self.now - 600, self.now - 560)
        self.same = "unsure"
        relay.second_opinions()
        self.assertEqual([], self.sub_labels())
        self.assertEqual("Car arrived in the driveway · blue tesla Model Y suv", self.descriptions()["visitor"])


# The Front Yard on 2026-09-27, with the camera's real driveway outline. `timed` paths are
# `(x, y, seconds into the event)`.
TESLA_SPOT = [(0.3609, 0.5306), (0.3609, 0.5319)]
# 11:58: the parked Tesla's event, a second in, jumps to a car going by on the street.
SWITCHED_TO_THE_STREET = [(0.3063, 0.5694), (0.3063, 0.5764), (0.475, 0.3111), (0.5703, 0.3375), (0.6898, 0.3611),
                          (0.7508, 0.3639), (0.8336, 0.4278), (0.9078, 0.4625)]
# 09:50: the Tesla backing out and driving off, first seen just past the driveway's edge; no zone tag.
BACKED_OUT = [(0.3047, 0.6389), (0.3047, 0.6333), (0.3523, 0.5958), (0.4078, 0.5625), (0.4578, 0.5333), (0.5133, 0.5014),
              (0.5648, 0.4722), (0.6195, 0.4458), (0.6656, 0.4014), (0.7336, 0.3681), (0.7891, 0.4972), (0.8758, 0.4625), (0.9336, 0.4875)]
# 22:41-10:58: Sarah's car at the curb all night, until the tracker moved to the Tesla driving past it into the driveway.
CURB_THEN_TESLA = [(0.8281, 0.5278, 0), (0.7484, 0.5278, 821), (0.8273, 0.5167, 925), (0.8258, 0.5278, 11152),
                   (0.8281, 0.5278, 43135), (0.7602, 0.4847, 44222), (0.6562, 0.4181, 44224), (0.6039, 0.4431, 44227),
                   (0.5508, 0.4708, 44230), (0.4992, 0.5014, 44232), (0.4477, 0.5292, 44234), (0.3883, 0.5375, 44237),
                   (0.3453, 0.5819, 44238), (0.3594, 0.5319, 44246)]


def timed_car_event(event_id, timed, start, end, **kwargs):
    event = car_event(event_id, [(x, y) for x, y, _ in timed], start, end, **kwargs)
    event["data"]["path_data"] = [[[x, y], start + t] for x, y, t in timed]
    return event


class TrackerSwitchTest(_ScratchDb):
    """The memory reading around Frigate's tracker handing a box from one car to another."""

    def setUp(self):
        super().setUp()
        relay.car_zone_polygons = lambda: {"hikvision_1": [CarCheckTest.DRIVEWAY]}
        relay.car_zone_outlines = lambda: {"hikvision_1": {"driveway": CarCheckTest.DRIVEWAY}}
        self.observe(car_event("parked", TESLA_SPOT, self.T, self.T + 2, name="andrews_tesla", score=1.0))

    def test_the_parked_car_carrying_on_as_a_passing_car_is_still_parked(self):
        story = self.observe(car_event("switch", SWITCHED_TO_THE_STREET, self.T + 600, self.T + 607, name="andrews_tesla", score=0.99))
        self.assertEqual(("andrews_tesla", "parked"), (story["name"], story["movement"]))
        self.assertTrue(self.here("andrews_tesla"))

    def test_without_the_jump_that_path_would_be_a_departure(self):
        # The same shape drawn by one car (a step no bigger than 0.2) is a car leaving.
        steady = [(0.3063, 0.5694), (0.36, 0.5), (0.42, 0.42), (0.5, 0.36), (0.6898, 0.3611), (0.8336, 0.4278), (0.9078, 0.4625)]
        story = self.observe(car_event("out", steady, self.T + 600, self.T + 607, name="andrews_tesla", score=0.99))
        self.assertEqual("left", story["movement"])
        self.assertFalse(self.here("andrews_tesla"))

    def test_a_jump_from_the_street_onto_the_parked_car_is_no_arrival(self):
        onto = [(0.793, 0.4431), (0.3047, 0.5458), (0.3047, 0.5458)]
        # Frigate tags the driveway from the part after the jump; the tag goes with that part.
        self.assertIsNone(self.observe(car_event("onto", onto, self.T + 600, self.T + 603)), "only the street part is that car's")
        self.assertTrue(self.here("andrews_tesla"))

    def test_a_jump_from_one_car_zone_into_another_is_a_switch_too(self):
        curb = [(0.72, 0.45), (0.95, 0.45), (0.95, 0.6), (0.72, 0.6)]
        relay.car_zones = lambda: {"hikvision_1": ["driveway", "curb"]}
        relay.car_zone_polygons = lambda: {"hikvision_1": [CarCheckTest.DRIVEWAY, curb]}
        relay.car_zone_outlines = lambda: {"hikvision_1": {"driveway": CarCheckTest.DRIVEWAY, "curb": curb}}
        across = [(0.3609, 0.5306), (0.3609, 0.5319), (0.8281, 0.5278), (0.8281, 0.5278)]
        story = self.observe(car_event("across", across, self.T + 600, self.T + 603, name="andrews_tesla", score=0.99, zones=("driveway", "curb")))
        self.assertEqual(("parked", "driveway"), (story["movement"], story["zone"]), "not the Tesla moving to the curb")
        self.assertTrue(self.here("andrews_tesla"))
        self.assertEqual(0.3609, relay.vehicle("hikvision_1", "andrews_tesla")["spot"]["x"])

    def test_a_car_backing_out_past_the_edge_before_it_is_seen_left(self):
        story = self.observe(car_event("backed", BACKED_OUT, self.T + 600, self.T + 624, name="andrews_tesla", score=0.974, zones=()))
        self.assertEqual(("andrews_tesla", "left"), (story["name"], story["movement"]))
        self.assertFalse(self.here("andrews_tesla"))

    def test_a_car_going_by_near_the_edge_without_starting_there_is_not_filed(self):
        along = [(0.62, 0.62), (0.45, 0.66), (0.3, 0.72), (0.2, 0.75)]  # beyond the edge the whole way
        self.assertIsNone(self.observe(car_event("along", along, self.T + 600, self.T + 610, zones=())))

    def test_an_arrival_hours_into_another_cars_event_is_filed_late_and_unnamed(self):
        self.observe(car_event("backed", BACKED_OUT, self.T + 600, self.T + 624, name="andrews_tesla", score=0.974, zones=()))
        event = timed_car_event("curb", CURB_THEN_TESLA, self.T - 36000, self.T + 8246, name="sarahs_car", score=1.0)
        story = self.observe(event)
        self.assertEqual((None, "late", "arrived"), (story["name"], story["how"], story["movement"]))
        self.assertEqual(self.T - 36000 + 44230, story["start"], "when it came in, not when the event began")
        self.assertIsNone(relay.vehicle("hikvision_1", "sarahs_car"), "the name is the car at the curb's")
        self.assertFalse(relay.renamed(event, story), "and it isn't looked up again for it")

    def test_an_arrival_soon_after_its_event_began_keeps_its_name(self):
        self.observe(car_event("backed", BACKED_OUT, self.T + 600, self.T + 624, name="andrews_tesla", score=0.974, zones=()))
        timed = [(x, y, t - 44222 + 5) for x, y, t in CURB_THEN_TESLA[5:]]
        story = self.observe(timed_car_event("home", timed, self.T + 3000, self.T + 3035, name="andrews_tesla", score=0.98))
        self.assertEqual(("andrews_tesla", "classifier", "arrived"), (story["name"], story["how"], story["movement"]))
        self.assertTrue(self.here("andrews_tesla"))
        self.assertEqual(self.T + 3000, relay.vehicle("hikvision_1", "andrews_tesla")["since"])



class _FrigateAnswer(_Response):
    def __init__(self, status=200, body=None):
        super().__init__(status, body)
        self.content, self.headers = json.dumps(body or {}).encode(), {"content-type": "application/json"}


class _Caller:
    def __init__(self, cookie="frigate_token=abc"):
        self.headers = {"cookie": cookie} if cookie else {}


class PersonTagTest(_ScratchDb):
    """Whose name an event carries: a person's, given through the relay, or the classifier's."""

    NAMES = _ScratchDb.NAMES + ("require_frigate_session",)

    def setUp(self):
        super().setUp()
        self._post = relay.requests.post
        self.posted, self.answer = [], _FrigateAnswer(200, {"success": True})
        relay.requests.post = lambda url, json=None, headers=None, timeout=None: (self.posted.append((url, json, headers)), self.answer)[1]
        relay.require_frigate_session = lambda request: "andrew"
        self.now = relay.person_tags_since() + 60

    def tearDown(self):
        relay.requests.post = self._post
        super().tearDown()

    def event(self, event_id, name="andrews_tesla", score=1.0, start=None):
        return {"id": event_id, "label": "car", "camera": "hikvision_1", "zones": ["driveway"], "start_time": self.now if start is None else start,
                "end_time": (self.now if start is None else start) + 5, "sub_label": name, "data": {"sub_label_score": score}}

    def tag(self, event_id, name, caller=None):
        return relay.tag_event(event_id, {"subLabel": name, "subLabelScore": 1.0 if name else None}, caller or _Caller())

    def test_the_classifiers_1_0_is_not_a_persons_tag(self):
        event = self.event("e")
        self.assertFalse(relay.by_a_person(event))
        self.assertTrue(relay.second_opinion_due(event, ["driveway"], self.now + 100), "the vision model gets a look")
        self.assertFalse(relay.worth_learning(event, ["driveway"], self.now + 100), "nor is its picture the car's")

    def test_a_1_0_from_before_the_relay_kept_tags_is_still_a_persons(self):
        tagged = self.event("old", name="sarahs_car", start=0.0)
        self.assertTrue(relay.by_a_person(tagged))
        self.assertFalse(relay.second_opinion_due(tagged, ["driveway"], 100.0))
        tagged["data"]["sub_label_score"] = 0.98
        self.assertTrue(relay.second_opinion_due(tagged, ["driveway"], 100.0))

    def test_a_tag_through_the_relay_goes_to_frigate_as_that_person_and_is_theirs(self):
        answer = self.tag("e", "andrews_tesla")
        self.assertEqual(200, answer.status_code)
        [(url, body, headers)] = self.posted
        self.assertEqual(f"{relay.FRIGATE_AUTH}/api/events/e/sub_label", url)
        self.assertEqual({"subLabel": "andrews_tesla", "subLabelScore": 1.0}, body)
        self.assertEqual({"Cookie": "frigate_token=abc"}, headers)
        self.assertTrue(relay.by_a_person(self.event("e")))
        self.assertFalse(relay.second_opinion_due(self.event("e"), ["driveway"], self.now + 100))
        self.assertFalse(relay.by_a_person(self.event("e", name="sarahs_car")), "a name it was given since is not the person's")

    def test_the_memory_files_a_persons_tag_as_tagged_and_the_classifiers_as_classifier(self):
        self.tag("mine", "andrews_tesla")
        path = [[[0.35, 0.6], self.now], [[0.35, 0.6], self.now + 1]]
        for event_id in ("mine", "guess"):
            self.events[event_id] = dict(self.event(event_id), data={"sub_label_score": 1.0, "box": list(CAR_BOX), "path_data": path})
        self.assertEqual("tagged", relay.observe_car(self.events["mine"], self.now + 10)["how"])
        self.assertEqual("classifier", relay.observe_car(self.events["guess"], self.now + 10)["how"])

    def test_a_person_confirming_the_classifiers_name_undoes_what_was_decided_under_it(self):
        path = [[[0.35, 0.6], self.now], [[0.35, 0.6], self.now + 1]]
        self.events["e"] = dict(self.event("e", score=0.98), data={"sub_label_score": 0.98, "box": list(CAR_BOX), "path_data": path})
        self.assertEqual("classifier", relay.observe_car(self.events["e"], self.now + 10)["how"])
        relay.record_check("e", "learn", "untagged", "andrews_tesla")
        self.tag("e", "andrews_tesla")
        self.assertIsNone(relay.check_of("e", "learn"), "its picture may now be the car's")
        self.assertEqual(0, relay.sighting("e")["final"], "the memory reads it again")
        self.events["e"]["data"]["sub_label_score"] = 1.0
        self.assertEqual("tagged", relay.observe_car(self.events["e"], self.now + 20)["how"])

    def test_what_frigate_refuses_is_not_kept(self):
        self.answer = _FrigateAnswer(403, {"success": False, "message": "Admin only"})
        self.assertEqual(403, self.tag("e", "andrews_tesla").status_code)
        self.assertFalse(relay.by_a_person(self.event("e")))

    def test_taking_the_name_away_forgets_the_tag(self):
        self.tag("e", "andrews_tesla")
        self.tag("e", "")
        self.assertEqual({"subLabel": "", "subLabelScore": None}, self.posted[-1][1])
        self.assertIsNone(relay.with_db(lambda c: c.execute("SELECT 1 FROM person_tags WHERE event_id='e'").fetchone()))

    def test_no_session_no_tag(self):
        with self.assertRaises(relay.HTTPException) as refused:
            self.tag("e", "andrews_tesla", _Caller(cookie=None))
        self.assertEqual(401, refused.exception.status_code)
        self.assertEqual([], self.posted)


# The Front Door's phantom, 2026-09-29: something by the door the detector kept calling a person.
DOOR_BOX = [0.05, 0.30, 0.22, 0.40]
DOOR_PATH = [(0.16, 0.70), (0.161, 0.702), (0.159, 0.70), (0.16, 0.699), (0.162, 0.701)]
# Someone walking up the steps to the door.
WALK_PATH = [(0.60, 0.95), (0.45, 0.88), (0.32, 0.80), (0.20, 0.72), (0.17, 0.70)]


class PhantomTest(_FakeFrigate):
    """Phantom people: marking one, knowing it again, and what the person classifier learns from it."""

    def setUp(self):
        super().setUp()
        self._more = {name: getattr(relay, name) for name in (
            "PERSON_CLASSIFIER", "PERSON_CLASS_MAX", "PERSON_RETRAIN_AFTER", "require_frigate_session", "detect_sizes",
            "save_classification_example", "file_queued_crop")}
        relay.PERSON_CLASSIFIER = ""
        relay.require_frigate_session = lambda request: "andrew"
        relay.detect_sizes = lambda: {"amcrest_1": (704, 480)}
        self.filed, self.filed_as = [], {}  # (model, category, queued crop) as filed, and each crop's name in the dataset
        relay.file_queued_crop = self.file_queued_crop

    def file_queued_crop(self, model, category, training_file):
        """What the real one does, without Pillow: the crop leaves the queue for the category's folder under a new name."""
        folder = os.path.join(self._dir.name, model, "dataset", category)
        os.makedirs(folder, exist_ok=True)
        name = relay.dataset_file_name(category, time.time())
        os.replace(os.path.join(self._dir.name, model, "train", training_file), os.path.join(folder, name))
        self.filed.append((model, category, training_file))
        self.filed_as[training_file] = name
        return name

    def tearDown(self):
        for name, value in self._more.items():
            setattr(relay, name, value)
        super().tearDown()

    def person(self, path=DOOR_PATH, box=DOOR_BOX, camera="amcrest_1", name=None, age=600.0, ended=True, label="person", verdict=None):
        """A detection; [verdict] is the person classifier's (class, score), as Frigate keeps it in the event's data."""
        start = self.now - age
        event_id = f"{start:.6f}-p{len(self.events)}"
        self.events[event_id] = {
            "id": event_id, "label": label, "camera": camera, "start_time": start, "end_time": start + 3 if ended else None,
            "sub_label": name, "data": {"type": "object", "box": list(box), "path_data": [[[x, y], start + i] for i, (x, y) in enumerate(path)]},
        }
        if verdict:
            self.events[event_id]["data"].update({"person_check": verdict[0], "person_check_score": verdict[1]})
        return event_id

    def review(self, detections, objects=None, ended=True, age=600.0, camera="amcrest_1"):
        start = self.now - age
        return {"id": f"r{len(detections)}", "camera": camera, "start_time": start, "end_time": start + 3 if ended else None,
                "data": {"objects": objects or ["person"], "detections": detections}}

    def mark(self, event_id):
        return relay.not_a_person(event_id, _Caller())

    def classifier(self):
        relay.PERSON_CLASSIFIER = "person_check"
        os.makedirs(os.path.join(self._dir.name, "person_check", "train"), exist_ok=True)

    def queue(self, event_id, model="person_check"):
        name = f"{event_id}-{self.now:.6f}-person-0.81.webp"
        open(os.path.join(self._dir.name, model, "train", name), "wb").close()
        return name

    def categorized(self):
        return [(body["category"], body["training_file"]) for url, body in self.posts if url.endswith("/categorize")]

    def test_box_iou(self):
        self.assertAlmostEqual(1.0, relay.box_iou(DOOR_BOX, DOOR_BOX))
        self.assertEqual(0.0, relay.box_iou([0, 0, 0.1, 0.1], [0.5, 0.5, 0.1, 0.1]))
        self.assertAlmostEqual(1 / 3, relay.box_iou([0, 0, 0.2, 0.1], [0.1, 0, 0.2, 0.1]))

    def test_marking_keeps_the_spot_and_the_same_phantom_is_known_again(self):
        answer = self.mark(self.person())
        self.assertEqual(DOOR_BOX, answer["spot"]["box"])
        self.assertIsNone(answer["example"], "no person classifier configured")
        spots = relay.phantom_spots("amcrest_1")
        self.assertTrue(relay.is_phantom(self.events[self.person(box=[0.06, 0.31, 0.21, 0.38])], spots))
        self.assertTrue(relay.is_phantom(self.events[self.person(path=DOOR_PATH[:2])], spots), "a one-second flicker")

    def test_a_real_person_at_the_spot_is_not_a_phantom(self):
        self.mark(self.person())
        spots = relay.phantom_spots()
        self.assertFalse(relay.is_phantom(self.events[self.person(path=WALK_PATH)], spots), "they walked there")
        self.assertFalse(relay.is_phantom(self.events[self.person(name="andrew")], spots), "a face Frigate knows")
        self.assertTrue(relay.is_phantom(self.events[self.person(name="none")], spots), "`none` is no name")
        self.assertTrue(relay.is_phantom(self.events[self.person(name="Unknown")], spots), "nor, in any case, is `unknown`")
        self.assertFalse(relay.is_phantom(self.events[self.person(box=[0.6, 0.3, 0.2, 0.4])], spots), "somewhere else")
        self.assertFalse(relay.is_phantom(self.events[self.person(camera="hikvision_1")], spots), "another camera")
        self.assertFalse(relay.is_phantom(self.events[self.person(label="dog")], spots))

    def test_only_a_person_with_a_box_can_be_marked(self):
        with self.assertRaises(relay.HTTPException) as refused:
            self.mark(self.person(label="car"))
        self.assertEqual(400, refused.exception.status_code)
        with self.assertRaises(relay.HTTPException) as known:
            self.mark(self.person(name="andrew"))
        self.assertEqual(400, known.exception.status_code, "a face Frigate knows is someone")
        with self.assertRaises(relay.HTTPException) as missing:
            self.mark("1789612937.165365-gone")
        self.assertEqual(404, missing.exception.status_code)
        with self.assertRaises(relay.HTTPException) as bad:
            self.mark("../config")
        self.assertEqual(404, bad.exception.status_code)
        boxless = self.person()
        self.events[boxless]["data"]["box"] = None
        with self.assertRaises(relay.HTTPException):
            self.mark(boxless)
        self.assertEqual([], relay.phantom_spots())

    def test_taking_it_back_forgets_the_spot(self):
        first = self.person()
        self.mark(first)
        self.assertTrue(relay.undo_not_a_person(first, _Caller())["removed"])
        self.assertEqual([], relay.phantom_spots())
        self.assertFalse(relay.undo_not_a_person(first, _Caller())["removed"])

    def test_verdict_skips_phantoms_waits_on_open_ones_and_pushes_anything_else(self):
        self.assertEqual("push", relay.phantom_verdict(self.review([self.person()])), "nothing marked yet")
        self.mark(self.person())
        again = self.person()
        self.assertEqual("skip", relay.phantom_verdict(self.review([again])))
        self.assertEqual("wait", relay.phantom_verdict(self.review([again], ended=False, age=5.0)))
        self.assertEqual("skip", relay.phantom_verdict(self.review([again], ended=False, age=relay.MOTION_WAIT_CAP_SECONDS + 1)))
        self.assertEqual("push", relay.phantom_verdict(self.review([again, self.person(path=WALK_PATH)])), "someone real with it")
        self.assertEqual("push", relay.phantom_verdict(self.review([again], objects=["person", "car"])))
        self.assertEqual("push", relay.phantom_verdict(self.review(["1789612937.165365-gone"])), "can't be judged")
        self.assertEqual("push", relay.phantom_verdict(self.review([again], camera="hikvision_1")))

    def test_skipping_marks_the_alert_sent(self):
        self.mark(self.person())
        item = self.review([self.person()])
        self.assertTrue(relay.skip_phantom(item))
        self.assertTrue(relay.was_sent(item["id"]))
        waiting = dict(self.review([self.person()], ended=False, age=5.0), id="open")
        self.assertTrue(relay.skip_phantom(waiting))
        self.assertFalse(relay.was_sent("open"), "judged again next poll")
        self.assertFalse(relay.skip_phantom(dict(self.review([self.person(path=WALK_PATH)]), id="real")))

    def test_a_mark_files_the_queued_crop_as_a_phantom(self):
        self.classifier()
        phantom = self.person()
        crop = self.queue(phantom)
        self.assertEqual("queued", self.mark(phantom)["example"])
        self.assertEqual([("person_check", "phantom", crop)], self.filed)
        self.assertEqual("queued", self.mark(phantom)["example"], "marked again from the app")
        self.assertEqual(1, len(self.filed), "one example is enough")
        self.assertEqual(1, relay.person_examples_since(0))

    def test_two_phones_marking_at_once_file_one_example(self):
        import threading

        self.classifier()
        phantom = self.person()
        saved, started = [], threading.Event()
        release = threading.Event()

        def slow_save(model, category, jpeg, box):
            started.set()
            release.wait(5)  # the first mark holds here while the second arrives
            saved.append(category)
            return f"x{len(saved)}.png"

        relay.save_classification_example = slow_save
        fake_get = self.get
        relay.requests.get = lambda url, params=None, timeout=None: (
            _FrigateAnswer(200, {}) if url.endswith("/snapshot.jpg") else fake_get(url, params, timeout))
        answers = []
        first = threading.Thread(target=lambda: answers.append(relay.file_not_a_person(self.events[phantom])))
        second = threading.Thread(target=lambda: answers.append(relay.file_not_a_person(self.events[phantom])))
        first.start()
        self.assertTrue(started.wait(5))
        second.start()
        second.join(0.2)
        self.assertTrue(second.is_alive(), "the second mark waits for the first")
        release.set()
        first.join(5)
        second.join(5)
        self.assertEqual(["phantom"], saved, "one example, the one Undo knows")
        self.assertEqual(["recording", "recording"], answers)

    def test_taking_a_mark_back_takes_its_example_out_of_the_dataset(self):
        self.classifier()
        phantom = self.person()
        self.queue(phantom)
        self.mark(phantom)
        [(_, _, crop)] = self.filed
        dataset = os.path.join(self._dir.name, "person_check", "dataset", "phantom")
        self.assertEqual([self.filed_as[crop]], os.listdir(dataset))
        answer = relay.undo_not_a_person(phantom, _Caller())
        self.assertEqual({"ok": True, "removed": True, "unfiled": True}, answer)
        self.assertEqual([], os.listdir(dataset))
        self.assertEqual([self.filed_as[crop]], os.listdir(os.path.join(self._dir.name, "person_check", "undone")), "kept, not deleted")
        self.assertEqual(0, relay.person_examples_since(0), "it no longer counts towards a retrain")
        self.assertFalse(relay.undo_not_a_person(phantom, _Caller())["unfiled"])

    def test_a_notification_marks_on_the_installs_secret(self):
        relay.require_frigate_session = self._more["require_frigate_session"]
        relay.with_db(lambda c: (c.execute("INSERT INTO devices (device_id, token, name, secret, platform) VALUES ('d1','t1','Pixel','s3cret','android')"), c.commit()))
        phantom = self.person()
        caller = _Caller(cookie=None)
        caller.headers["authorization"] = "Bearer s3cret"
        self.assertEqual(DOOR_BOX, relay.not_a_person(phantom, caller, device="d1")["spot"]["box"])
        self.assertTrue(relay.undo_not_a_person(phantom, caller, device="d1")["removed"])
        caller.headers["authorization"] = "Bearer wrong"
        with self.assertRaises(relay.HTTPException) as refused:
            relay.not_a_person(phantom, caller, device="d1")
        self.assertEqual(401, refused.exception.status_code)
        self.assertEqual([], relay.phantom_spots())

    def test_a_person_who_stayed_put_and_the_classifier_calls_a_phantom_is_one_anywhere(self):
        self.classifier()
        elsewhere = [0.6, 0.3, 0.2, 0.4]
        sure = self.person(box=elsewhere, verdict=("phantom", 0.95))
        self.assertTrue(relay.is_phantom(self.events[sure], []))
        self.assertEqual("skip", relay.phantom_verdict(self.review([sure])), "no spot needed")
        self.assertEqual("wait", relay.phantom_verdict(self.review([sure], ended=False, age=5.0)))
        self.assertFalse(relay.is_phantom(self.events[self.person(box=elsewhere, verdict=("phantom", 0.85))], []), "not sure enough")
        self.assertFalse(relay.is_phantom(self.events[self.person(path=WALK_PATH, verdict=("phantom", 0.99))], []), "they walked")
        self.assertFalse(relay.is_phantom(self.events[self.person(name="andrew", verdict=("phantom", 0.99))], []), "a face Frigate knows")
        self.assertFalse(relay.is_phantom(self.events[self.person(verdict=("person", 0.99))], []))
        self.assertEqual("push", relay.phantom_verdict(self.review([self.person(box=elsewhere)])), "no verdict yet")
        relay.PERSON_CLASSIFIER = ""
        self.assertFalse(relay.is_phantom(self.events[sure], []), "no classifier, no verdict")

    def test_a_phantom_only_the_classifier_saw_is_not_filed_back_into_it(self):
        self.classifier()
        own = self.person(box=[0.6, 0.3, 0.2, 0.4], verdict=("phantom", 0.99))
        self.queue(own)
        relay.file_person_crops()
        self.assertEqual([], self.categorized())
        self.assertEqual("unsure", self.verdict(own, "person-check"))

    def test_a_mark_with_no_queued_crop_cuts_one_from_the_recording(self):
        self.classifier()
        saved = []
        relay.save_classification_example = lambda model, category, jpeg, box: saved.append((model, category, box)) or "x.png"
        phantom = self.person()
        asked = []
        fake_get = self.get
        relay.requests.get = lambda url, params=None, timeout=None: (
            (asked.append((url, params)), _FrigateAnswer(200, {}))[1] if url.endswith("/snapshot.jpg") else fake_get(url, params, timeout))
        self.assertEqual("recording", self.mark(phantom)["example"])
        [(url, params)] = asked
        self.assertIn("/api/amcrest_1/recordings/", url)
        self.assertEqual({"height": 480}, params, "the detect frame's size, which is what the classifier crops from")
        [(model, category, box)] = saved
        self.assertEqual(("person_check", "phantom"), (model, category))
        self.assertAlmostEqual(DOOR_PATH[-1][0], box[0] + box[2] / 2)
        self.assertEqual(1, relay.person_examples_since(0))

    def test_queued_crops_of_phantoms_named_faces_and_walkers_are_filed_and_the_rest_left(self):
        self.classifier()
        self.mark(self.person())
        phantom, face, walker = self.person(), self.person(name="andrew"), self.person(path=WALK_PATH)
        standing, fresh = self.person(box=[0.6, 0.3, 0.2, 0.4]), self.person(age=10.0)
        crops = {e: self.queue(e) for e in (phantom, face, walker, standing, fresh)}
        relay.file_person_crops()
        self.assertEqual({("phantom", crops[phantom]), ("person", crops[face]), ("person", crops[walker])}, set(self.categorized()))
        self.assertEqual("unsure", self.verdict(standing, "person-check"), "stood still somewhere new: can't be told")
        self.assertIsNone(self.verdict(fresh, "person-check"), "its face may yet be named")
        relay.file_person_crops()
        self.assertEqual(3, len(self.categorized()), "each event is judged once")

    def test_a_full_class_takes_no_more(self):
        self.classifier()
        relay.PERSON_CLASS_MAX = 1
        folder = os.path.join(self._dir.name, "person_check", "dataset", "person")
        os.makedirs(folder)
        open(os.path.join(folder, "a.png"), "wb").close()
        face = self.person(path=WALK_PATH, name="andrew")
        self.queue(face)
        relay.file_person_crops()
        self.assertEqual([], self.categorized())
        self.assertEqual("full", self.verdict(face, "person-check"))

    def test_retrains_once_enough_examples_went_in(self):
        self.classifier()
        relay.PERSON_RETRAIN_AFTER = 2
        relay.record_check("a", "person-check", "filed", "person")
        relay.maybe_retrain_person()
        self.assertFalse(any(url.endswith("/train") for url, _ in self.posts))
        relay.record_check("b", "not-a-person", "filed", "queued")
        relay.maybe_retrain_person()
        self.assertEqual([f"{relay.FRIGATE}/api/classification/person_check/train"], [url for url, _ in self.posts if url.endswith("/train")])
        relay.maybe_retrain_person()
        self.assertEqual(1, len([url for url, _ in self.posts if url.endswith("/train")]), "once a day")

    def test_the_phantom_list_answers_every_spot(self):
        self.mark(self.person())
        self.mark(self.person(camera="hikvision_2", box=[0.5, 0.5, 0.1, 0.2]))
        saved, relay.authenticate = relay.authenticate, lambda request, device=None: "andrew"
        try:
            answer = relay.phantoms(_Caller())
        finally:
            relay.authenticate = saved
        self.assertEqual(["amcrest_1", "hikvision_2"], [s["camera"] for s in answer["spots"]])
        self.assertEqual(relay.PHANTOM_IOU, answer["iou"])


class PolicyTest(_FakeFrigate):
    """What an alert is while someone is home, and how the Front Yard's people become one notification."""

    T = 1_790_360_000.0

    def setUp(self):
        super().setUp()
        self._policy = relay.NOTIFY_POLICY
        relay.HOUSEHOLD_CARS = {"andrews_tesla": {}, "sarahs_car": {}}
        self.yard = relay.YardWatch()
        self.pushes = []

    def tearDown(self):
        relay.NOTIFY_POLICY = self._policy
        super().tearDown()

    def route(self, item, now=None):
        return relay.home_route(item, self.yard, now if now is not None else item["start_time"] + 5)

    def push(self, title, body, data, **kwargs):
        self.pushes.append((title, body, data))
        return {"sent": 1}

    def arrival(self, event_id, name, start, how="classifier", camera="hikvision_1"):
        relay.save_sighting({"event_id": event_id, "camera": camera, "name": name, "how": how, "movement": "arrived",
                             "zone": "driveway", "start": start, "end": start + 60, "final": 1, "at": start + 60})

    def test_a_person_on_the_front_yard_sounds_and_the_next_ten_minutes_update_it(self):
        route, run = self.route(review("a", self.T, ["person"], end=self.T + 30, zones=("front_lawn",)))
        self.assertEqual("instant", route)
        self.assertEqual("update", self.route(review("b", self.T + 90, ["person"], end=self.T + 120))[0])
        self.assertEqual("update", self.route(review("c", self.T + 120 + 600, ["person"]))[0], "ten minutes after b ended")
        self.assertEqual("a", run["id"])
        self.assertEqual(("Front Yard", "Person in the driveway · 3 sightings"), relay.yard_sentence(run, review("c", self.T, ["person"]), ["driveway"]))

    def test_the_yard_sounds_again_once_it_has_been_empty_ten_minutes(self):
        self.route(review("a", self.T, ["person"], end=self.T + 30))
        route, run = self.route(review("b", self.T + 30 + 601, ["person"]))
        self.assertEqual(("instant", "b"), (route, run["id"]))

    def test_someone_still_in_view_keeps_the_yard_busy(self):
        first = review("a", self.T, ["person"])
        self.route(first)
        self.yard.observe([first], self.T + 1500)
        self.assertEqual("update", self.route(review("b", self.T + 1800, ["person"]))[0])

    def test_the_other_cameras_and_cars_wait_for_the_summary(self):
        self.assertEqual("digest", self.route(review("a", self.T, ["person"], camera="amcrest_1", zones=()))[0])
        self.assertEqual("digest", self.route(review("b", self.T, ["person"], camera="hikvision_2", zones=()))[0])
        self.assertEqual("digest", self.route(review("c", self.T, ["car"]))[0])
        self.assertEqual("digest", self.route(review("d", self.T, ["dog"]))[0])

    def test_a_backlog_never_sounds(self):
        item = review("a", self.T, ["person"], end=self.T + 30)
        self.assertEqual("update", self.route(item, now=self.T + relay.BACKLOG_SECONDS + 1)[0])

    def test_people_with_a_household_car_coming_or_going_are_folded_into_it(self):
        item = review("a", self.T, ["person", "car"], ["sarahs_car"])
        item["data"]["vehicles"] = [{"event_id": "c", "name": "sarahs_car", "how": "classifier", "movement": "arrived"}]
        self.assertEqual("fold", self.route(item)[0])

    def test_people_who_turn_up_just_after_a_household_car_arrived_are_its_passengers(self):
        self.arrival("car", "sarahs_car", self.T - 120)
        self.assertEqual("fold", self.route(review("a", self.T, ["person"], end=self.T + 30))[0])
        self.assertEqual("update", self.route(review("b", self.T + 90, ["person"]))[0], "past the fold, but the yard is busy with them")

    def test_a_car_arrival_long_before_or_a_stranger_car_does_not_fold(self):
        self.arrival("old", "sarahs_car", self.T - relay.FOLD_SECONDS - 60)
        self.arrival("stranger", None, self.T - 30, how=None)
        self.arrival("turned-down", "sarahs_car", self.T - 30, how="not")
        self.assertEqual("instant", self.route(review("a", self.T, ["person"]))[0])

    def test_people_with_a_car_that_is_parked_are_not_folded(self):
        item = review("a", self.T, ["person", "car"], ["andrews_tesla"])
        item["data"]["vehicles"] = [{"event_id": "c", "name": "andrews_tesla", "how": "parked", "movement": "parked"}]
        self.assertEqual("instant", self.route(item)[0])

    def test_a_person_beside_a_car_arriving_unnamed_waits_for_its_name_then_sounds(self):
        item = review("a", self.T, ["person", "car"])
        item["data"]["vehicles"] = [{"event_id": "c", "name": None, "how": None, "movement": "arrived"}]
        self.assertEqual(("wait", None), self.route(item, now=self.T + 5))
        self.assertEqual("instant", self.route(item, now=self.T + relay.FOLD_NAME_WAIT_SECONDS + 1)[0])

    def test_v2_pushes_the_yard_and_keeps_the_rest_for_the_summary(self):
        relay.NOTIFY_POLICY = "v2"
        zones = {"hikvision_1": ["driveway", "front_lawn"]}
        first = review("a", self.T, ["person"], end=self.T + 30, zones=("front_lawn",))
        relay.tell_home(first, *self.route(first), zones, push=self.push)
        second = review("b", self.T + 60, ["person"], zones=("front_lawn",))
        relay.tell_home(second, *self.route(second), zones, push=self.push)
        door = review("c", self.T + 70, ["person"], camera="amcrest_1", zones=())
        relay.tell_home(door, *self.route(door), zones, push=self.push)
        self.assertEqual(
            [("Front Yard", "Person on the front lawn"), ("Front Yard", "Person on the front lawn · 2 sightings")],
            [(t, b) for t, b, _ in self.pushes],
        )
        self.assertNotIn("silent", self.pushes[0][2])
        self.assertEqual(("1", "a", str(self.T)), (self.pushes[1][2]["silent"], self.pushes[1][2]["notif_id"], self.pushes[1][2]["start_time"]))
        self.assertTrue(all(relay.was_sent(r) for r in ("a", "b", "c")))
        routes = dict(relay.with_db(lambda c: c.execute("SELECT key, route FROM notify_log").fetchall()))
        self.assertEqual({"a": "instant", "b": "update", "c": "digest"}, routes)

    def test_shadow_only_logs(self):
        relay.NOTIFY_POLICY = "shadow"
        item = review("a", self.T, ["person"])
        relay.tell_home(item, *self.route(item), {}, push=self.push)
        self.assertEqual([], self.pushes)
        self.assertFalse(relay.was_sent("a"), "the old rules still push it")
        self.assertEqual(("instant",), relay.with_db(lambda c: c.execute("SELECT route FROM notify_log WHERE key='a'").fetchone()))


class CarPresenceTest(_FakeFrigate):
    """Whether each household car is home, and saying so once each way."""

    T = 1_790_360_000.0
    SPOT = {"x": 0.31, "y": 0.60, "w": 0.20, "h": 0.23}

    def setUp(self):
        super().setUp()
        self._ollama = relay.OLLAMA
        relay.OLLAMA = ""
        relay.HOUSEHOLD_CARS = {"andrews_tesla": {}, "sarahs_car": {}}
        relay.save_vehicle({"camera": "hikvision_1", "name": "andrews_tesla", "here": 1, "spot": self.SPOT,
                            "since": self.T - 7200, "last_seen": self.T - 60, "event_id": "x", "looks": {}})
        relay.save_vehicle({"camera": "hikvision_1", "name": "sarahs_car", "here": 0, "spot": None,
                            "since": None, "last_seen": self.T - 3600, "event_id": "y", "looks": {}})
        # The day so far: the Tesla re-detected in the driveway after a false "left"; Sarah's car gone.
        self.seen("t-left", "andrews_tesla", "left", self.T - 1500, self.T - 1490)
        self.seen("t-parked", "andrews_tesla", "parked", self.T - 600, self.T - 560)
        self.seen("s-left", "sarahs_car", "left", self.T - 3700, self.T - 3600)
        self.cars = relay.CarPresence()
        self.clock = self.T

    def tearDown(self):
        relay.OLLAMA = self._ollama
        super().tearDown()

    def tick(self, at):
        self.clock = max(self.clock + relay.CAR_PRESENCE_EVERY_SECONDS, at)
        return [(c["name"], c["movement"], c["was"]) for c in self.cars.tick(self.clock)]

    def seen(self, event_id, name, movement, start, end=None, how="classifier"):
        relay.save_sighting({"event_id": event_id, "camera": "hikvision_1", "name": name, "how": how, "movement": movement,
                             "zone": "driveway", "start": start, "end": end, "final": int(end is not None), "at": start})

    def test_starts_where_the_vehicle_memory_has_each_car_and_says_nothing(self):
        self.assertEqual([], self.tick(self.T))
        self.assertEqual({"andrews_tesla": True, "sarahs_car": False}, {n: c["here"] for n, c in self.cars.cars.items()})
        self.assertEqual(self.cars.cars, relay.CarPresence().cars or relay.state_get(relay.CAR_PRESENCE_KEY))

    def test_a_departure_is_told_once_nothing_has_seen_the_car_for_ten_minutes(self):
        self.tick(self.T)
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.assertEqual([], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS - 5))
        self.assertEqual([("andrews_tesla", "left", None)], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))
        self.assertEqual([], self.tick(self.T + 3000), "once")
        self.seen("back", "andrews_tesla", "arrived", self.T + 130 + 3 * 3600, self.T + 130 + 3 * 3600 + 40)
        self.assertEqual([("andrews_tesla", "arrived", 3 * 3600.0)], self.tick(self.T + 130 + 3 * 3600 + 50))

    def test_a_car_back_an_hour_later_still_left_and_then_arrived(self):
        # The relay was down through the departure's window: it sees both at once when it's back.
        self.tick(self.T)
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.seen("back", "andrews_tesla", "arrived", self.T + 3600, self.T + 3640)
        self.assertEqual([("andrews_tesla", "left", None)], self.tick(self.T + 4000))
        self.assertEqual([("andrews_tesla", "arrived", 3600 - 130.0)], self.tick(self.T + 4015))

    def test_a_car_starts_home_by_its_sightings_not_the_memorys_flag(self):
        # The memory's `here` says the Tesla is gone (a false "left" cleared it); its sightings say otherwise.
        relay.save_vehicle(dict(relay.vehicle("hikvision_1", "andrews_tesla"), here=0))
        relay.save_vehicle(dict(relay.vehicle("hikvision_1", "sarahs_car"), here=1))
        self.tick(self.T)
        self.assertEqual({"andrews_tesla": True, "sarahs_car": False}, {n: c["here"] for n, c in self.cars.cars.items()})
        self.assertEqual((False, self.T), relay.starting_state("nobody", self.T))

    def test_a_departure_still_being_confirmed_at_the_start_is_judged_like_any_other(self):
        # 2026-09-29 21:29: the relay came up three minutes after a "left" of the Tesla.
        self.seen("t-left2", "andrews_tesla", "left", self.T - 360, self.T - 180)
        self.assertEqual([], self.tick(self.T))
        self.assertTrue(self.cars.cars["andrews_tesla"]["here"])
        self.assertEqual([("andrews_tesla", "left", None)], self.tick(self.T - 180 + relay.CAR_LEFT_CONFIRM_SECONDS))

    def test_twenty_departures_of_a_car_that_never_moved_are_none(self):
        # 2026-09-28 12:39-13:37: "left" after "left", each followed by the Tesla re-detected where it stands.
        self.tick(self.T)
        for i in range(20):
            start = self.T + 100 + i * 180
            self.seen(f"l{i}", "andrews_tesla", "left", start, start + 10)
            self.seen(f"p{i}", "andrews_tesla", "parked", start + 60, start + 90)
        self.assertEqual([], self.tick(self.T + 100 + 20 * 180 + 3600))
        self.assertTrue(self.cars.cars["andrews_tesla"]["here"])

    def test_a_car_still_standing_in_its_spot_turns_the_departure_down(self):
        self.tick(self.T)
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.visits.append({"id": "standing", "start_time": self.T + 50, "end_time": None})
        self.events["standing"] = {"id": "standing", "camera": "hikvision_1", "label": "car",
                                   "data": {"box": [0.21, 0.37, 0.2, 0.23], "path_data": [[[0.31, 0.6], self.T + 50]]}}
        self.assertEqual([], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))
        self.assertEqual("go", self.cars.cars["andrews_tesla"]["skip"])
        del self.events["standing"]
        self.visits.clear()
        self.assertEqual([], self.tick(self.T + 3000), "turned down for good")

    def test_another_car_pulling_into_the_spot_does_not_hold_the_departure(self):
        self.tick(self.T)
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.visits.append({"id": "visitor", "start_time": self.T + 300, "end_time": None})
        self.events["visitor"] = {"id": "visitor", "camera": "hikvision_1", "label": "car",
                                  "data": {"box": [0.21, 0.37, 0.2, 0.23], "path_data": [[[0.31, 0.6], self.T + 300]]}}
        self.seen("visitor", None, "arrived", self.T + 300, how=None)
        self.assertEqual([("andrews_tesla", "left", None)], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))

    def test_a_car_found_parked_after_it_left_is_home(self):
        self.tick(self.T)
        self.seen("parked", "sarahs_car", "parked", self.T + 500, self.T + 530)
        self.assertEqual([("sarahs_car", "is home", None)], self.tick(self.T + 540))

    def test_the_classifiers_name_waits_for_the_vision_model(self):
        relay.OLLAMA = "http://ollama"
        self.tick(self.T)
        self.seen("in", "sarahs_car", "arrived", self.T + 500, self.T + 540)
        self.assertEqual([], self.tick(self.T + 560))
        relay.record_check("in", "vlm", "verified")
        self.assertEqual([("sarahs_car", "arrived", None)], self.tick(self.T + 580))

    def test_the_classifiers_name_is_told_after_a_while_without_the_vision_model(self):
        relay.OLLAMA = "http://ollama"
        self.tick(self.T)
        self.seen("in", "sarahs_car", "arrived", self.T + 500, self.T + 540)
        self.assertEqual([("sarahs_car", "arrived", None)], self.tick(self.T + 500 + relay.CAR_NAME_WAIT_SECONDS))

    def test_a_name_the_vision_model_turned_down_never_arrives(self):
        self.tick(self.T)
        self.seen("in", "sarahs_car", "arrived", self.T + 500, self.T + 540, how="not")
        self.assertEqual([], self.tick(self.T + 1000))

    def test_only_a_car_tesla_knows_is_checked(self):
        self.tick(self.T)
        self.assertEqual("andrews_tesla" in relay.TESLA_CARS, self.cars.cars["andrews_tesla"].get("checked") is not None)

    def test_a_car_first_heard_of_starts_silently(self):
        self.tick(self.T)
        relay.save_vehicle({"camera": "hikvision_1", "name": "yayas_car", "here": 1, "spot": None,
                            "since": self.T + 100, "last_seen": self.T + 100, "event_id": "z", "looks": {}})
        self.seen("z", "yayas_car", "arrived", self.T + 50, self.T + 100)
        self.assertEqual([], self.tick(self.T + 200))
        self.assertTrue(self.cars.cars["yayas_car"]["here"])

    def test_wording(self):
        self.assertEqual(("Sarah's Car arrived home", "Front Yard · away 3h 10m"),
                         relay.car_sentence({"name": "sarahs_car", "movement": "arrived", "camera": "hikvision_1", "was": 3 * 3600 + 600}))
        self.assertEqual(("Andrew's Tesla left", "Front Yard · home 45m"),
                         relay.car_sentence({"name": "andrews_tesla", "movement": "left", "camera": "hikvision_1", "was": 2700}))
        self.assertEqual(("Sarah's Car is home", "Front Yard"),
                         relay.car_sentence({"name": "sarahs_car", "movement": "is home", "camera": "hikvision_1", "was": None}))
        self.assertEqual(["1m", "59m", "1h", "2d 4h", "3d"], [relay.span_text(s) for s in (5, 59 * 60, 3600, 2 * 86400 + 4 * 3600, 3 * 86400)])

    def test_v2_pushes_a_change_and_shadow_only_logs_it(self):
        pushes = []
        change = {"name": "sarahs_car", "movement": "arrived", "camera": "hikvision_1", "event_id": "in", "at": self.T, "was": None}
        saved = relay.NOTIFY_POLICY
        try:
            relay.NOTIFY_POLICY = "shadow"
            relay.tell_car(change, "home", push=lambda *a, **k: pushes.append((a, k)))
            self.assertEqual([], pushes)
            relay.NOTIFY_POLICY = "v2"
            relay.tell_car(change, "home", push=lambda *a, **k: pushes.append((a, k)))
        finally:
            relay.NOTIFY_POLICY = saved
        (title, body, data), kwargs = pushes[0]
        self.assertEqual(("Sarah's Car arrived home", "car-sarahs_car", False), (title, data["notif_id"], kwargs["away"]))
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM notify_log WHERE route='car'").fetchone()[0]))


class SummaryTest(_FakeFrigate):
    """What stayed quiet while someone was home, told a few times a day."""

    # 2026-09-29 12:00 UTC (the household's clock is UTC here: no phone has said its zone).
    NOON = 1790683200.0

    def setUp(self):
        super().setUp()
        self._policy, self._hours = relay.NOTIFY_POLICY, relay.DIGEST_HOURS
        relay.NOTIFY_POLICY, relay.DIGEST_HOURS = "v2", [9, 12, 15, 18, 21]
        relay.HOUSEHOLD_CARS = {"andrews_tesla": {}, "sarahs_car": {}}
        self.pushes = []

    def tearDown(self):
        relay.NOTIFY_POLICY, relay.DIGEST_HOURS = self._policy, self._hours
        super().tearDown()

    def push(self, title, body, data, **kwargs):
        self.pushes.append((title, body, data))
        return {"sent": 1}

    def kept(self, rid, start, objects, sub_labels=(), camera="amcrest_1", at=None):
        item = review(rid, start, objects, sub_labels, camera=camera, zones=())
        relay.with_db(lambda c: c.execute("DELETE FROM notify_log WHERE key=?", (rid,)))
        relay.log_decision(rid, "home", "digest", camera, "", "", start, rid + "-e", item)
        relay.with_db(lambda c: (c.execute("UPDATE notify_log SET at=? WHERE key=?", (at or start + 5, rid)), c.commit()))

    def test_the_slot_is_the_latest_hour_passed(self):
        self.assertEqual(self.NOON, relay.digest_slot(self.NOON, None))
        self.assertEqual(self.NOON, relay.digest_slot(self.NOON + 3 * 3600 - 1, None))
        self.assertEqual(self.NOON - 3 * 3600, relay.digest_slot(self.NOON - 1, None))
        self.assertEqual(self.NOON - 15 * 3600, relay.digest_slot(self.NOON - 12 * 3600, None), "past midnight: last night's 9 PM")

    def test_the_first_slot_starts_the_summaries_and_the_next_tells_what_was_kept(self):
        relay.summarise(self.NOON + 10, push=self.push)
        self.assertEqual([], self.pushes)
        self.kept("a", self.NOON + 600, ["person"], ["sarah"])
        self.kept("b", self.NOON + 700, ["person"])  # the same visit as a: still Sarah's
        self.kept("c", self.NOON + 3600, ["person"])
        self.kept("d", self.NOON + 4000, ["person"], camera="hikvision_2")
        self.kept("e", self.NOON + 4100, ["dog"], camera="hikvision_2")
        self.kept("f", self.NOON + 5000, ["car"], camera="hikvision_1")
        self.kept("g", self.NOON + 6000, ["car"], ["sarahs_car"], camera="hikvision_1")
        relay.summarise(self.NOON + 3 * 3600 + 20, push=self.push)
        self.assertEqual(
            [("Since 12:00 PM UTC: 5 visits",
              "Front Door: Sarah, 1 unknown person · Backyard: 1 unknown person, dog · Front Yard: Sarah's Car, 1 unknown car",
              {"notif_id": "summary", "summary": "1", "silent": "1"})],
            self.pushes,
        )
        relay.summarise(self.NOON + 3 * 3600 + 40, push=self.push)
        self.assertEqual(1, len(self.pushes), "once a slot")

    def test_a_summary_of_only_named_people_skips_the_phones_that_want_strangers(self):
        familiar = []
        push = lambda title, body, data, **kwargs: familiar.append(kwargs.get("familiar")) or {"sent": 1}
        relay.summarise(self.NOON + 10, push=push)
        self.kept("a", self.NOON + 600, ["person"], ["sarah"])
        self.kept("b", self.NOON + 4000, ["person"], ["andrew"], camera="hikvision_2")
        relay.summarise(self.NOON + 3 * 3600 + 10, push=push)
        self.kept("c", self.NOON + 3 * 3600 + 600, ["person"], ["sarah"])
        self.kept("d", self.NOON + 3 * 3600 + 700, ["dog"], camera="hikvision_2")
        relay.summarise(self.NOON + 6 * 3600 + 10, push=push)
        self.kept("e", self.NOON + 6 * 3600 + 600, ["person"])
        relay.summarise(self.NOON + 9 * 3600 + 10, push=push)
        self.assertEqual([True, False, False], familiar, "family only; family and a dog; a stranger")

    def test_nothing_kept_no_summary(self):
        relay.summarise(self.NOON + 10, push=self.push)
        relay.summarise(self.NOON + 3 * 3600 + 10, push=self.push)
        self.assertEqual([], self.pushes)

    def test_a_slot_slept_through_is_told_by_the_next(self):
        relay.summarise(self.NOON + 10, push=self.push)
        self.kept("a", self.NOON + 600, ["person"])
        relay.summarise(self.NOON + 3 * 3600 + relay.DIGEST_LATE_SECONDS + 1, push=self.push)
        self.assertEqual([], self.pushes)
        self.kept("b", self.NOON + 4 * 3600, ["person"], camera="hikvision_2")
        relay.summarise(self.NOON + 6 * 3600 + 5, push=self.push)
        self.assertEqual([("Since 12:00 PM UTC: 2 visits", "Front Door: 1 unknown person · Backyard: 1 unknown person")],
                         [(t, b) for t, b, _ in self.pushes])

    def test_shadow_only_logs_the_summary(self):
        relay.NOTIFY_POLICY = "shadow"
        relay.summarise(self.NOON + 10, push=self.push)
        self.kept("a", self.NOON + 600, ["person"])
        relay.summarise(self.NOON + 3 * 3600 + 10, push=self.push)
        self.assertEqual([], self.pushes)
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM notify_log WHERE route='summary'").fetchone()[0]))

    def test_the_log_from_before_the_summary_gains_its_columns(self):
        import sqlite3
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "relay.db")
            old = sqlite3.connect(path)
            old.execute("CREATE TABLE notify_log (key TEXT PRIMARY KEY, at REAL, policy TEXT, mode TEXT, route TEXT,"
                        " camera TEXT, title TEXT, body TEXT, start REAL, event_id TEXT)")
            old.commit()
            old.close()
            real, relay.DB_PATH = relay.DB_PATH, path
            try:
                conn = relay.db()
                columns = [row[1] for row in conn.execute("PRAGMA table_info(notify_log)")]
                conn.close()
            finally:
                relay.DB_PATH = real
            self.assertEqual(["labels", "names"], columns[-2:])


def _jwt(sub):
    import base64

    part = base64.urlsafe_b64encode(json.dumps({"sub": sub}).encode()).decode().rstrip("=")
    return f"h.{part}.s"


class TeslaTest(CarPresenceTest):
    """
    Tesla's word on where a household car is, over the camera's guess. Runs every CarPresenceTest
    too, with Tesla linked but unable to find the cars: the camera must decide exactly as before.
    """

    HOME = {"lat": 37.4220, "lng": -122.0841, "radius_m": 150}
    NEAR = (37.4225, -122.0845)  # ~65 m
    FAR = (37.4500, -122.1000)  # ~3.4 km

    def setUp(self):
        super().setUp()
        self._tesla = {n: getattr(relay, n) for n in ("TESLA_CLIENT_ID", "TESLA_CLIENT_SECRET", "TESLA_PUBLIC_URL", "TESLA_CARS", "TESLA_CALLS_PER_DAY")}
        relay.TESLA_CLIENT_ID, relay.TESLA_CLIENT_SECRET, relay.TESLA_PUBLIC_URL = "client", "secret", "https://box.example.ts.net"
        relay.TESLA_CARS = {"andrews_tesla": "VIN_A", "sarahs_car": "VIN_S"}
        relay._tesla_answers.clear()
        relay._tesla_live.clear()
        relay.state_set(relay.HOME_KEY, self.HOME)
        relay.tesla_save_tokens({"access_token": _jwt("andrew"), "refresh_token": "r1", "expires_in": 3600})
        for vin in ("VIN_A", "VIN_S"):
            relay.with_db(lambda c: (c.execute("INSERT OR REPLACE INTO tesla_vehicles VALUES (?,?,?,?)", (vin, "andrew", "", 0)), c.commit()))
        self.where = {}  # vin -> (lat, lng), or "asleep"
        self.fix_age = 5.0
        self.tesla_calls = []
        self.token_posts = []

    def tearDown(self):
        for name, value in self._tesla.items():
            setattr(relay, name, value)
        relay._tesla_answers.clear()
        relay._tesla_live.clear()
        super().tearDown()

    def stream(self, vin, at, connectivity=None, **fields):
        """What the telemetry server publishes for one record of the car's, as the broker hands it over."""
        if connectivity:
            relay.tesla_stream_receive(f"tesla/{vin}/connectivity", json.dumps({"ConnectionId": "c1", "Status": connectivity}).encode(), at)
        for field, value in fields.items():
            if field == "Location":
                value = {"latitude": value[0], "longitude": value[1]}
            relay.tesla_stream_receive(f"tesla/{vin}/v/{field}", json.dumps(value).encode(), at)

    def get(self, url, params=None, timeout=None, headers=None):
        if url.startswith(relay.TESLA_API):
            self.tesla_calls.append(url)
            if url.endswith("/api/1/vehicles"):
                return _Response(200, {"response": [{"vin": "VIN_A", "display_name": "Blue"}, {"vin": "VIN_S", "display_name": "Red"}]})
            vin = url.split("/")[-2]
            where = self.where.get(vin)
            if where == "asleep":
                return _Response(408, {"error": "vehicle unavailable"})
            if where is None:
                return _Response(404, {})
            return _Response(200, {"response": {"drive_state": {"latitude": where[0], "longitude": where[1], "gps_as_of": time.time() - self.fix_age}}})
        return super().get(url, params, timeout)

    def post(self, url, json=None, timeout=None, data=None, headers=None):
        if url == relay.TESLA_TOKEN:
            self.token_posts.append(data)
            n = len(self.token_posts) + 1
            return _Response(200, {"access_token": _jwt("andrew"), "refresh_token": f"r{n}", "expires_in": 28800})
        return super().post(url, json, timeout)

    def test_near_home_is_home_far_is_away_asleep_or_in_between_is_the_cameras_call(self):
        self.where = {"VIN_A": self.NEAR, "VIN_S": self.FAR}
        self.assertEqual("home", relay.tesla_verdict("andrews_tesla"))
        self.assertEqual("away", relay.tesla_verdict("sarahs_car"))
        relay._tesla_answers.clear()
        self.where = {"VIN_A": "asleep", "VIN_S": (37.4238, -122.0841)}  # ~200 m: past the margin, short of away
        self.assertEqual("asleep", relay.tesla_verdict("andrews_tesla"))
        self.assertIsNone(relay.tesla_verdict("sarahs_car"))
        self.assertIsNone(relay.tesla_verdict("yayas_car"), "not a Tesla")

    def test_a_stale_fix_says_nothing(self):
        self.where, self.fix_age = {"VIN_A": self.FAR}, relay.TESLA_STALE_SECONDS + 60
        self.assertIsNone(relay.tesla_verdict("andrews_tesla"))

    def test_one_call_per_car_a_minute_and_a_daily_cap(self):
        self.where = {"VIN_A": self.NEAR}
        relay.tesla_verdict("andrews_tesla", now=1000.0)
        relay.tesla_verdict("andrews_tesla", now=1030.0)
        self.assertEqual(1, len(self.tesla_calls))
        relay.TESLA_CALLS_PER_DAY = 1
        self.assertIsNone(relay.tesla_verdict("andrews_tesla", now=1100.0))
        self.assertEqual(1, len(self.tesla_calls))

    def test_an_expired_token_is_refreshed_and_the_next_refresh_token_kept(self):
        relay.with_db(lambda c: (c.execute("UPDATE tesla_accounts SET expires=0"), c.commit()))
        self.where = {"VIN_A": self.NEAR}
        self.assertEqual("home", relay.tesla_verdict("andrews_tesla"))
        self.assertEqual([{"grant_type": "refresh_token", "client_id": "client", "refresh_token": "r1"}], self.token_posts)
        self.assertEqual(("r2",), relay.with_db(lambda c: c.execute("SELECT refresh FROM tesla_accounts").fetchone()))

    def test_nothing_is_asked_without_a_home(self):
        relay.state_set(relay.HOME_KEY, None)
        self.where = {"VIN_A": self.FAR}
        self.assertIsNone(relay.tesla_verdict("andrews_tesla"))
        self.assertEqual([], self.tesla_calls)

    def presence_with(self, name):
        presence = relay.CarPresence()
        presence.cars = {name: {"here": True, "since": 0.0, "camera": "hikvision_1", "skip": None, "known": True}}
        return presence, presence.cars[name]

    def test_a_car_asleep_is_asked_about_again_only_every_two_hours(self):
        self.where = {"VIN_A": "asleep"}
        presence, car = self.presence_with("andrews_tesla")
        now = time.time()
        presence.tesla_check("andrews_tesla", car, now)
        presence.tesla_check("andrews_tesla", car, now + relay.TESLA_CHECK_SECONDS + 1)
        self.assertEqual(1, len(self.tesla_calls), "asleep half an hour ago, it hasn't moved")
        presence.tesla_check("andrews_tesla", car, now + relay.TESLA_ASLEEP_CHECK_SECONDS + 1)
        self.assertEqual(2, len(self.tesla_calls))

    def test_an_answer_already_in_hand_covers_the_half_hourly_check(self):
        self.where = {"VIN_A": self.NEAR}
        now = time.time()
        relay.tesla_verdict("andrews_tesla", now, "departure")
        presence, car = self.presence_with("andrews_tesla")
        presence.tesla_check("andrews_tesla", car, now + 600)
        self.assertEqual(1, len(self.tesla_calls), "the departure's answer is ten minutes old")

    def test_an_answer_in_hand_that_the_car_left_is_taken_without_asking_again(self):
        self.where = {"VIN_A": self.FAR}
        now = time.time()
        relay.tesla_ask("andrews_tesla", now, "parking: still there?")
        presence, car = self.presence_with("andrews_tesla")
        change = presence.tesla_check("andrews_tesla", car, now + 600)
        self.assertEqual(("left", "tesla"), (change["movement"], change["via"]), "parking's answer had it 3 km away")
        self.assertEqual(1, len(self.tesla_calls))

    def test_signing_in_is_tallied_as_a_link_whatever_was_asked_before(self):
        self.where = {"VIN_A": "asleep"}
        relay.tesla_verdict("andrews_tesla", time.time(), "check")
        relay.tesla_learn_vehicles("andrew")
        self.assertEqual({"check": 1, "link": 1}, relay.state_get(relay.TESLA_CALLS_KEY)["why"])

    def test_each_call_is_tallied_by_why_it_was_made(self):
        self.where = {"VIN_A": "asleep", "VIN_S": self.NEAR}
        now = time.time()
        relay.tesla_verdict("andrews_tesla", now, "departure")
        relay.tesla_verdict("sarahs_car", now, "check")
        relay.tesla_verdict("sarahs_car", now + 30, "check")  # within the minute: the same answer, no call
        relay.tesla_verdict("sarahs_car", now + 120, "check")
        spent = relay.state_get(relay.TESLA_CALLS_KEY)
        self.assertEqual({"departure": 1, "check": 2}, spent["why"])
        self.assertEqual(3, relay.tesla_calls_today())

    def test_a_link_signs_in_once(self):
        url = relay.tesla_link_url()
        state = relay.parse_qs(url.split("?", 1)[1])["state"][0]
        self.assertIn("redirect_uri=https%3A%2F%2Fbox.example.ts.net%2Ftesla%2Fcallback", url)
        page = relay.tesla_callback(code="c1", state=state)
        self.assertEqual(200, page.status_code)
        self.assertIn("Andrew&#x27;s Tesla, Sarah&#x27;s Car", page.body)
        self.assertEqual("authorization_code", self.token_posts[0]["grant_type"])
        self.assertEqual(400, relay.tesla_callback(code="c1", state=state).status_code, "used")
        self.assertEqual(400, relay.tesla_callback(code="c1", state="made-up").status_code)

    def test_tesla_turns_down_a_departure_whose_spot_looks_empty(self):
        self.tick(self.T)
        self.where = {"VIN_A": self.NEAR}
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.assertEqual([], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))
        self.assertTrue(self.cars.cars["andrews_tesla"]["here"])

    def test_tesla_confirms_a_departure_though_a_car_stands_in_the_spot(self):
        self.tick(self.T)
        self.where = {"VIN_A": self.FAR}
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.visits.append({"id": "standing", "start_time": self.T + 50, "end_time": None})
        self.events["standing"] = {"id": "standing", "camera": "hikvision_1", "label": "car",
                                   "data": {"box": [0.21, 0.37, 0.2, 0.23], "path_data": [[[0.31, 0.6], self.T + 50]]}}
        self.assertEqual([("andrews_tesla", "left", None)], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))

    def test_tesla_confirms_an_arrival_without_waiting_for_the_vision_model(self):
        relay.OLLAMA = "http://ollama"
        self.tick(self.T)
        self.where = {"VIN_S": self.NEAR}
        self.seen("in", "sarahs_car", "arrived", self.T + 500, self.T + 540)
        self.assertEqual([("sarahs_car", "arrived", None)], self.tick(self.T + 545))

    def test_an_arrival_of_a_car_tesla_has_far_away_was_another_car(self):
        self.tick(self.T)
        self.where = {"VIN_S": self.FAR}
        self.seen("in", "sarahs_car", "arrived", self.T + 500, self.T + 540)
        self.assertEqual([], self.tick(self.T + 600))
        relay._tesla_answers.clear()
        self.where = {"VIN_S": self.NEAR}
        self.seen("in2", "sarahs_car", "arrived", self.T + 3000, self.T + 3040)
        self.assertEqual([("sarahs_car", "arrived", None)], self.tick(self.T + 3100))

    def test_a_car_tesla_cant_see_falls_back_to_the_camera(self):
        self.tick(self.T)
        self.where = {}  # Tesla answers 404: the car isn't on the linked account
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.assertEqual([("andrews_tesla", "left", None)], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))

    def test_a_car_asleep_ten_minutes_after_it_left_never_left(self):
        # 2026-09-30: the camera lost the parked Tesla five times; Tesla had it asleep each time.
        self.tick(self.T)
        self.where = {"VIN_A": "asleep"}
        self.seen("go", "andrews_tesla", "left", self.T + 100, self.T + 130)
        self.assertEqual([], self.tick(self.T + 130 + relay.CAR_LEFT_CONFIRM_SECONDS))
        self.assertTrue(self.cars.cars["andrews_tesla"]["here"])
        self.assertEqual("go", self.cars.cars["andrews_tesla"]["skip"], "turned down for good")

    def test_a_car_asleep_at_an_arrival_leaves_it_to_the_camera(self):
        relay.OLLAMA = "http://ollama"
        self.tick(self.T)
        self.where = {"VIN_S": "asleep"}
        self.seen("in", "sarahs_car", "parked", self.T + 500, self.T + 540)
        self.assertEqual([], self.tick(self.T + 545), "the name waits for the vision model, as without Tesla")
        relay._tesla_answers.clear()
        self.assertEqual([("sarahs_car", "is home", None)], self.tick(self.T + 500 + relay.CAR_NAME_WAIT_SECONDS))

    def test_a_car_leaving_from_the_curb_is_found_by_the_half_hourly_check(self):
        # 2026-10-01: Sarah's car parked in the street, out of the driveway zone, where the camera can't see it come or go.
        self.cars.cars = None
        relay.state_set(relay.CAR_PRESENCE_KEY, {"sarahs_car": {"here": True, "since": self.T - 3600, "camera": "hikvision_1", "skip": None, "known": True}})
        self.where = {"VIN_A": self.NEAR, "VIN_S": self.NEAR}
        self.assertEqual([], self.tick(self.T))
        self.where["VIN_S"] = self.FAR
        relay._tesla_answers.clear()
        self.assertEqual([], self.tick(self.clock + relay.TESLA_CHECK_SECONDS - 60), "not asked again before the half hour")
        changes = self.cars.tick(self.clock + relay.TESLA_CHECK_SECONDS)
        self.assertEqual([("sarahs_car", "left", "tesla")], [(c["name"], c["movement"], c["via"]) for c in changes])
        title, body = relay.car_sentence(changes[0])
        self.assertEqual("Sarah's Car left", title)
        self.assertTrue(body.startswith("Seen by Tesla · home 1h "), body)

    def test_a_car_parking_at_the_curb_is_found_home_by_the_check(self):
        self.where = {"VIN_S": self.FAR}
        self.assertEqual([], self.tick(self.T))
        self.assertFalse(self.cars.cars["sarahs_car"]["here"])
        self.where["VIN_S"] = self.NEAR
        relay._tesla_answers.clear()
        self.assertEqual([("sarahs_car", "is home", None)], self.tick(self.clock + relay.TESLA_CHECK_SECONDS))

    def test_the_check_changes_nothing_while_tesla_agrees_sleeps_or_is_unsure(self):
        self.where = {"VIN_A": "asleep", "VIN_S": (37.4238, -122.0841)}  # asleep; ~200 m, in between
        self.assertEqual([], self.tick(self.T))
        relay._tesla_answers.clear()
        self.assertEqual([], self.tick(self.clock + relay.TESLA_CHECK_SECONDS))
        self.assertEqual({"andrews_tesla": True, "sarahs_car": False}, {n: c["here"] for n, c in self.cars.cars.items()})
        self.assertEqual(4, len(self.tesla_calls), "each car once a half hour")

    def test_a_car_that_streams_is_seen_leaving_the_curb_at_once_without_asking(self):
        # 2026-10-28: Sarah's car drove off from the curb and slept again between two checks, never seen to move.
        self.cars.cars = None
        relay.state_set(relay.CAR_PRESENCE_KEY, {"sarahs_car": {"here": True, "since": self.T - 3600, "camera": "hikvision_1", "skip": None, "known": True}})
        self.where = {"VIN_A": self.NEAR, "VIN_S": "asleep"}
        self.assertEqual([], self.tick(self.T))
        calls = len(self.tesla_calls)
        self.stream("VIN_S", self.T + 300, connectivity="CONNECTED", Location=self.NEAR, Gear="ShiftStateD", VehicleSpeed=12.0)
        self.assertEqual([], self.tick(self.T + 310), "still near home")
        self.stream("VIN_S", self.T + 400, Location=self.FAR, VehicleSpeed=35.0)
        changes = self.cars.tick(self.T + 410)
        self.assertEqual([("sarahs_car", "left", "tesla")], [(c["name"], c["movement"], c["via"]) for c in changes])
        self.assertEqual(calls, len(self.tesla_calls), "the stream said, so nobody asked")

    def test_a_streaming_car_is_answered_from_its_stream_until_it_disconnects(self):
        self.where = {"VIN_S": "asleep"}
        self.stream("VIN_S", 1000.0, connectivity="CONNECTED", Location=self.FAR, Gear="ShiftStateP", GpsHeading=270)
        self.assertEqual("away", relay.tesla_verdict("sarahs_car", now=1100.0))
        asked, where = relay._tesla_answers["sarahs_car"]
        self.assertEqual((1100.0, "P", 270.0, True), (asked, where["shift"], where["heading"], where["streamed"]))
        self.assertEqual([], self.tesla_calls)
        relay._tesla_answers.clear()
        self.assertIsNone(relay.tesla_stream_answer("sarahs_car", 1000.0 + relay.TESLA_STREAM_FRESH_SECONDS + 1), "quiet too long")
        self.stream("VIN_S", 1200.0, connectivity="DISCONNECTED")
        self.assertEqual("asleep", relay.tesla_verdict("sarahs_car", now=1300.0), "gone to sleep: Tesla is asked")
        self.assertEqual(1, len(self.tesla_calls))

    def test_a_location_from_before_the_car_reconnected_is_not_taken_as_where_it_is(self):
        self.where = {"VIN_S": "asleep"}
        self.stream("VIN_S", 1000.0, connectivity="CONNECTED", Location=self.NEAR, Gear="ShiftStateP")
        self.stream("VIN_S", 1100.0, connectivity="DISCONNECTED")
        relay._tesla_answers.clear()
        self.stream("VIN_S", 5000.0, connectivity="CONNECTED", Soc=70, Gear="ShiftStateD")
        self.assertNotIn("sarahs_car", relay._tesla_answers, "the old location isn't restamped by a gear change")
        with relay._tesla_lock:
            self.assertIsNone(relay.tesla_stream_answer("sarahs_car", 5010.0), "nor by the battery")
        self.stream("VIN_S", 5020.0, Location=self.FAR)
        self.assertEqual("away", relay.tesla_verdict("sarahs_car", now=5030.0))
        relay.tesla_stream_lost()
        with relay._tesla_lock:
            self.assertIsNone(relay.tesla_stream_answer("sarahs_car", 5040.0), "the broker dropped: what was missed is unknown")
        self.assertFalse(relay.tesla_streaming("sarahs_car", 5040.0), "so it is asked about as often as any car")
        self.stream("VIN_S", 5100.0, Location=self.FAR)
        self.assertTrue(relay.tesla_streaming("sarahs_car", 5100.0), "until it streams again")

    def test_a_shift_into_p_counts_only_when_both_gears_came_over_this_connection(self):
        self.stream("VIN_S", 1000.0, connectivity="CONNECTED", Location=self.FAR, Gear="ShiftStateD")
        self.stream("VIN_S", 1100.0, connectivity="DISCONNECTED")
        self.stream("VIN_S", 9000.0, connectivity="CONNECTED", Location=self.FAR, Gear="ShiftStateP")
        self.assertNotIn("parked_at", relay._tesla_answers["sarahs_car"][1], "parked who knows when, not as it reconnected")
        self.stream("VIN_S", 9100.0, Gear="ShiftStateD")
        self.stream("VIN_S", 9200.0, Gear="ShiftStateP")
        self.assertEqual(9200.0, relay._tesla_answers["sarahs_car"][1]["parked_at"])

    def test_a_sleeping_car_that_streams_is_asked_about_less_often(self):
        relay._tesla_answers["sarahs_car"] = (1000.0, {"asleep": True})
        later = 1000.0 + relay.TESLA_ASLEEP_CHECK_SECONDS
        self.assertTrue(relay.tesla_due("sarahs_car", later, relay.TESLA_CHECK_SECONDS))
        self.stream("VIN_S", 900.0, Location=self.NEAR)
        relay._tesla_answers["sarahs_car"] = (1000.0, {"asleep": True})  # asked once it had gone to sleep
        self.assertFalse(relay.tesla_due("sarahs_car", later, relay.TESLA_CHECK_SECONDS), "it says itself when it wakes")
        self.assertTrue(relay.tesla_due("sarahs_car", 1000.0 + relay.TESLA_STREAM_ASLEEP_CHECK_SECONDS, relay.TESLA_CHECK_SECONDS))
        self.assertTrue(relay.tesla_due("sarahs_car", 900.0 + relay.TESLA_STREAM_TRUST_SECONDS + relay.TESLA_ASLEEP_CHECK_SECONDS,
                                        relay.TESLA_CHECK_SECONDS), "a stream not heard for days is trusted no more")

    def test_only_the_households_cars_are_listened_to_and_strings_are_read_as_numbers(self):
        relay.tesla_stream_receive("tesla/VIN_X/v/Location", b'{"latitude": 37.45, "longitude": -122.1}', 1000.0)
        relay.tesla_stream_receive("zigbee2mqtt/VIN_S/v/Soc", b"80", 1000.0)
        self.assertEqual({}, relay._tesla_live)
        self.stream("VIN_A", 1000.0, connectivity="CONNECTED", Soc="81.5", EstBatteryRange=240.25, DetailedChargeState="DetailedChargeStateCharging",
                    Locked=True, SentryMode="SentryModeStateArmed", DestinationName="Springfield", MinutesToArrival="12.0")
        self.assertEqual({}, relay._tesla_answers, "no location yet")
        live = relay.tesla_live_status("andrews_tesla", 1010.0)
        self.assertEqual((True, 81.5, 240.25, "Charging", True, "Armed", "Springfield", 12.0),
                         (live["connected"], live["battery"], live["range_mi"], live["charging"], live["locked"], live["sentry"],
                          live["destination"], live["minutes_to_arrival"]))
        self.assertIsNone(relay.tesla_live_status("sarahs_car", 1010.0))
        self.assertIsNone(relay.tesla_stream_fix({"fields": {"Location": {"latitude": 0, "longitude": 0}}}, 1.0), "no fix")

    def test_the_configuration_names_the_box_its_ca_and_the_fields(self):
        ca = os.path.join(self._dir.name, "ca.pem")
        with open(ca, "w", encoding="utf-8") as f:
            f.write("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        old, relay.TESLA_TELEMETRY_CA_FILE = relay.TESLA_TELEMETRY_CA_FILE, ca
        try:
            config = relay.tesla_telemetry_config(1000.0)
        finally:
            relay.TESLA_TELEMETRY_CA_FILE = old
        self.assertEqual(("box.example.ts.net", 8443), (config["hostname"], config["port"]))
        self.assertTrue(config["ca"].startswith("-----BEGIN CERTIFICATE-----"))
        self.assertEqual({"interval_seconds": 10}, config["fields"]["Location"])
        self.assertEqual(1000 + relay.TESLA_TELEMETRY_DAYS * 86400, config["exp"])

    def test_account_by_the_tokens_subject(self):
        self.assertEqual("andrew", relay.jwt_subject(_jwt("andrew")))
        self.assertEqual("", relay.jwt_subject("not a jwt"))
        self.assertAlmostEqual(3400, relay.haversine_metres(self.FAR, (self.HOME["lat"], self.HOME["lng"])), delta=200)


class FinanceSheet(unittest.TestCase):
    """The budget sheet route: Google's refusals as setup steps, the payload, who may read it, and its cache."""

    ACCOUNT = "relay@example.iam.gserviceaccount.com"

    def test_a_disabled_api_says_so_with_the_link_to_enable_it(self):
        body = {"error": {"code": 403, "message": "Google Sheets API has not been used in project 123 before or it is disabled.", "status": "PERMISSION_DENIED",
                          "details": [{"@type": "type.googleapis.com/google.rpc.Help", "links": []},
                                      {"reason": "SERVICE_DISABLED", "metadata": {"activationUrl": "https://console.developers.google.com/apis/api/sheets.googleapis.com/overview?project=123"}}]}}
        status, detail = relay.sheet_problem(403, body, self.ACCOUNT)
        self.assertEqual(503, status)
        self.assertEqual("api_disabled", detail["error"])
        self.assertIn("project=123", detail["activation_url"])
        self.assertEqual(self.ACCOUNT, detail["service_account"])

    def test_the_link_is_found_in_the_message_when_google_leaves_out_the_details(self):
        body = {"error": {"message": "Sheets API is disabled. Enable it by visiting https://console.developers.google.com/apis/api/sheets.googleapis.com/overview?project=9 then retry."}}
        _, detail = relay.sheet_problem(403, body, None)
        self.assertEqual("api_disabled", detail["error"])
        self.assertEqual("https://console.developers.google.com/apis/api/sheets.googleapis.com/overview?project=9", detail["activation_url"])

    def test_a_sheet_not_shared_with_the_key_names_the_account_to_share_with(self):
        body = {"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}}
        status, detail = relay.sheet_problem(403, body, self.ACCOUNT)
        self.assertEqual((503, "not_shared", self.ACCOUNT), (status, detail["error"], detail["service_account"]))

    def test_a_403_for_another_reason_is_not_called_unshared(self):
        body = {"error": {"code": 403, "message": "Request had insufficient authentication scopes.", "details": [{"reason": "ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
        status, detail = relay.sheet_problem(403, body, None)
        self.assertEqual((502, "google_error"), (status, detail["error"]))

    def test_missing_sheets_and_odd_bodies(self):
        self.assertEqual("not_found", relay.sheet_problem(404, {}, None)[1]["error"])
        self.assertEqual((502, "google_error"), (relay.sheet_problem(500, "not json", None)[0], relay.sheet_problem(500, "not json", None)[1]["error"]))
        status, detail = relay.sheet_problem(500, {"error": "backend down"}, None)
        self.assertEqual((502, "backend down"), (status, detail["message"]))

    def test_tab_titles_are_quoted_as_ranges(self):
        self.assertEqual("'Home'", relay.sheet_range("Home"))
        self.assertEqual("'Form Responses 1'", relay.sheet_range("Form Responses 1"))
        self.assertEqual("'Alex''s'", relay.sheet_range("Alex's"))

    META = {
        "properties": {"title": "Budget"},
        "sheets": [
            {"properties": {"title": "Home", "sheetType": "GRID"}, "merges": [{"startRowIndex": 5, "endRowIndex": 6, "startColumnIndex": 9, "endColumnIndex": 11}, {"endRowIndex": 2, "endColumnIndex": 3}]},
            {"properties": {"title": "Chart1", "sheetType": "OBJECT"}},
            {"properties": {"title": "Empty"}},
        ],
    }

    def test_chart_tabs_are_left_out_of_the_read(self):
        self.assertEqual(["Home", "Empty"], relay.grid_titles(self.META))

    def test_the_payload_pairs_each_grid_tab_with_its_values_and_merges(self):
        values = {"valueRanges": [{"range": "Home!A1:Z9", "values": [["Flow In", "Alex"], [], ["Rent", -4321.5]]}, {"range": "Empty!A1"}]}
        body = relay.sheet_payload(self.META, values, "abc", 1000.0)
        self.assertEqual("Budget", body["title"])
        self.assertEqual("https://docs.google.com/spreadsheets/d/abc/edit", body["url"])
        self.assertEqual(["Home", "Empty"], [s["title"] for s in body["sheets"]])
        self.assertEqual([["Flow In", "Alex"], [], ["Rent", -4321.5]], body["sheets"][0]["values"])
        self.assertEqual({"start_row": 5, "end_row": 6, "start_column": 9, "end_column": 11}, body["sheets"][0]["merges"][0])
        self.assertEqual({"start_row": 0, "end_row": 2, "start_column": 0, "end_column": 3}, body["sheets"][0]["merges"][1])
        self.assertEqual([], body["sheets"][1]["values"])

    CHART_META = {
        "properties": {"title": "Budget"},
        "sheets": [
            {"properties": {"sheetId": 0, "title": "Home", "sheetType": "GRID"}, "charts": [
                {"chartId": 2, "position": {"overlayPosition": {"anchorCell": {"sheetId": 0, "rowIndex": 40, "columnIndex": 1}}},
                 "spec": {"title": "Debt", "basicChart": {"chartType": "COLUMN", "domains": [{"domain": {"sourceRange": {"sources": [
                     {"sheetId": 0, "startRowIndex": 60, "endRowIndex": 90, "startColumnIndex": 18, "endColumnIndex": 19}]}}}],
                     "series": [{"series": {"sourceRange": {"sources": [{"sheetId": 0, "startRowIndex": 60, "endRowIndex": 90, "startColumnIndex": 25, "endColumnIndex": 26}]}}}]}}},
                {"chartId": 1, "position": {"overlayPosition": {"anchorCell": {"sheetId": 0, "rowIndex": 10}}},
                 "spec": {"title": "Assets", "basicChart": {"chartType": "AREA", "stackedType": "STACKED", "headerCount": 1, "domains": [{"domain": {"sourceRange": {"sources": [
                     {"sheetId": 0, "startRowIndex": 59, "startColumnIndex": 18, "endColumnIndex": 19}]}}}],
                     "series": [{"series": {"sourceRange": {"sources": [{"sheetId": 0, "startRowIndex": 59, "startColumnIndex": 23, "endColumnIndex": 24}]}}, "targetAxis": "RIGHT_AXIS"},
                                {"series": {"sourceRange": {"sources": []}}}]}}},
            ]},
            {"properties": {"sheetId": 77, "title": "Forecasts", "sheetType": "GRID"}, "charts": [
                {"chartId": 3, "spec": {"title": "Split", "pieChart": {"pieHole": 0.5,
                    "domain": {"sourceRange": {"sources": [{"sheetId": 77, "startRowIndex": 0, "endRowIndex": 1, "startColumnIndex": 2, "endColumnIndex": 6}]}},
                    "series": {"sourceRange": {"sources": [{"sheetId": 77, "startRowIndex": 1, "endRowIndex": 2, "startColumnIndex": 2, "endColumnIndex": 6}]}}}}},
                {"chartId": 4, "spec": {"title": "Steps", "waterfallChart": {}}},
                {"chartId": 5, "spec": {"basicChart": {"chartType": "LINE", "series": [{"series": {"sourceRange": {"sources": [
                    {"sheetId": 99, "startRowIndex": 0, "endRowIndex": 5, "startColumnIndex": 0, "endColumnIndex": 1}]}}}]}}},
            ]},
        ],
    }

    def test_charts_are_read_tab_by_tab_and_top_to_bottom(self):
        charts = relay.sheet_charts(self.CHART_META)
        self.assertEqual([1, 2, 3, 4, 5], [c["id"] for c in charts])
        self.assertEqual(["Home", "Home", "Forecasts", "Forecasts", "Forecasts"], [c["sheet"] for c in charts])
        self.assertEqual([0, 0, 77, 77, 77], [c["gid"] for c in charts])

    def test_a_basic_chart_names_its_ranges_by_tab_with_open_ends_kept(self):
        assets = relay.sheet_charts(self.CHART_META)[0]
        self.assertEqual(("AREA", "STACKED", 1), (assets["kind"], assets["stacked"], assets["header_count"]))
        self.assertEqual([{"sheet": "Home", "start_row": 59, "end_row": None, "start_column": 18, "end_column": 19}], assets["domain"])
        self.assertEqual(1, len(assets["series"]), "a series with no range is dropped")
        self.assertEqual(("AREA", "RIGHT_AXIS"), (assets["series"][0]["type"], assets["series"][0]["axis"]))
        self.assertEqual({"row": 10, "column": 0}, assets["anchor"])

    def test_pies_other_kinds_and_ranges_on_unknown_tabs(self):
        pie, waterfall, orphan = relay.sheet_charts(self.CHART_META)[2:]
        self.assertEqual(("PIE", 0.5), (pie["kind"], pie["pie_hole"]))
        self.assertEqual("Forecasts", pie["domain"][0]["sheet"])
        self.assertEqual(("WATERFALL", []), (waterfall["kind"], waterfall["series"]))
        self.assertEqual(("LINE", [], ""), (orphan["kind"], orphan["series"], orphan["title"]))

    def test_each_range_is_probed_for_its_format_one_cell_past_its_start(self):
        self.assertEqual(("Home", 61, 18), relay.format_probe({"sheet": "Home", "start_row": 60, "end_row": 90, "start_column": 18, "end_column": 19}))
        self.assertEqual(("Home", 60, 3), relay.format_probe({"sheet": "Home", "start_row": 60, "end_row": 61, "start_column": 2, "end_column": 6}))
        self.assertEqual(("Home", 60, 2), relay.format_probe({"sheet": "Home", "start_row": 60, "end_row": 61, "start_column": 2, "end_column": 3}))
        self.assertEqual(("Home", 60, 2), relay.format_probe({"sheet": "Home", "start_row": 59, "end_row": None, "start_column": 2, "end_column": 3}))
        cells = relay.format_probes(relay.sheet_charts(self.CHART_META))
        self.assertEqual(len(cells), len(set(cells)), "a range two charts share is asked about once")
        self.assertIn("'Home'!S61", [relay.cell_a1(c) for c in cells])
        self.assertEqual(["A", "Z", "AA", "AZ", "BA"], [relay.column_letters(i) for i in (0, 25, 26, 51, 52)])

    def test_formats_are_matched_back_to_the_ranges(self):
        answer = {"sheets": [{"properties": {"title": "Home"}, "data": [
            {"startRow": 60, "startColumn": 18, "rowData": [{"values": [{"effectiveFormat": {"numberFormat": {"type": "DATE", "pattern": "mmm-yy"}}}]}]},
            {"startRow": 60, "startColumn": 23, "rowData": [{"values": [{"effectiveFormat": {"numberFormat": {"type": "CURRENCY"}}}]}]},
            {"startRow": 61, "startColumn": 25, "rowData": [{"values": [{}]}]},
        ]}]}
        formats = relay.cell_formats(answer)
        self.assertEqual({"type": "DATE", "pattern": "mmm-yy"}, formats[("Home", 60, 18)])
        self.assertNotIn(("Home", 61, 25), formats)
        values = {"valueRanges": [{"values": []}, {"values": []}]}
        assets, debt = relay.sheet_payload(self.CHART_META, values, "abc", 1.0, formats)["charts"][:2]
        self.assertEqual("DATE", assets["domain_format"]["type"])
        self.assertEqual("CURRENCY", assets["series"][0]["format"]["type"])
        self.assertIsNone(debt["series"][0]["format"])

    def test_only_admins_or_the_listed_users_may_read_it(self):
        saved = relay.FINANCE_USERS
        try:
            relay.FINANCE_USERS = set()
            self.assertTrue(relay.finance_may_read({"username": "alex", "role": "admin"}))
            self.assertFalse(relay.finance_may_read({"username": "sitter", "role": "viewer"}))
            self.assertFalse(relay.finance_may_read({}))
            relay.FINANCE_USERS = {"alex"}
            self.assertTrue(relay.finance_may_read({"username": "alex", "role": "viewer"}), "a listed viewer may")
            self.assertTrue(relay.finance_may_read({"username": "sam", "role": "admin"}), "an admin still may")
            self.assertFalse(relay.finance_may_read({"username": "sitter", "role": "viewer"}))
        finally:
            relay.FINANCE_USERS = saved


class _GoogleResponse:
    def __init__(self, status, body):
        self.status_code, self._body = status, body

    def json(self):
        return self._body


class _FakeSheets:
    """Stands in for the AuthorizedSession: answers the metadata and values calls, counting them."""

    def __init__(self, meta=(200, None), values=(200, None)):
        self.meta, self.values, self.calls = meta, values, []

    def get(self, url, params=None, timeout=None):
        self.calls.append((url, params))
        status, body = self.values if url.endswith(":batchGet") else self.meta
        if isinstance(body, Exception):
            raise body
        return _GoogleResponse(status, body)


class FinanceSheetRoute(unittest.TestCase):
    META = {"properties": {"title": "Budget"}, "sheets": [{"properties": {"title": "Home"}}, {"properties": {"title": "Chart", "sheetType": "OBJECT"}}]}
    VALUES = {"valueRanges": [{"values": [["Flow In", "Alex"]]}]}

    def setUp(self):
        self.response = types.SimpleNamespace(headers={})
        self.saved = (relay.FINANCE_SHEET_ID, relay.require_finance_user, relay.finance_google, dict(relay._finance_cache), relay.typed_updates, relay.typed_filled)
        relay.FINANCE_SHEET_ID = "sheet123"
        relay.require_finance_user = lambda request: "alex"
        relay.typed_updates = lambda: []
        relay.typed_filled = lambda: []
        relay._finance_cache.update(at=0.0, body=None, failed_at=0.0, failure=None)
        self.sheets = _FakeSheets((200, self.META), (200, self.VALUES))
        relay.finance_google = lambda: self.sheets
        relay._finance_google["account"] = "relay@example.iam.gserviceaccount.com"

    def tearDown(self):
        relay.FINANCE_SHEET_ID, relay.require_finance_user, relay.finance_google, cache, relay.typed_updates, relay.typed_filled = self.saved
        relay._finance_cache.clear()
        relay._finance_cache.update(cache)

    def test_reads_only_the_grid_tabs_unformatted(self):
        body = relay.get_finance_sheet(object(), self.response)
        self.assertEqual("private, no-store", self.response.headers["Cache-Control"])
        self.assertEqual([["Flow In", "Alex"]], body["sheets"][0]["values"])
        meta_url, meta_params = self.sheets.calls[0]
        self.assertTrue(meta_url.endswith("/sheet123"))
        self.assertIn("sheetType", meta_params["fields"])
        _, params = self.sheets.calls[1]
        self.assertIn(("ranges", "'Home'"), params)
        self.assertNotIn(("ranges", "'Chart'"), params)
        self.assertIn(("valueRenderOption", "UNFORMATTED_VALUE"), params)

    def test_a_minute_of_cache_that_a_refresh_skips_but_not_within_seconds(self):
        relay.get_finance_sheet(object(), self.response)
        relay.get_finance_sheet(object(), self.response)
        self.assertEqual(3, len(self.sheets.calls))
        relay.get_finance_sheet(object(), self.response, refresh=True)
        self.assertEqual(3, len(self.sheets.calls), "a refresh within the minimum interval is served from memory")
        relay._finance_cache["at"] -= relay.FINANCE_MIN_REFRESH_SECONDS + 1
        relay.get_finance_sheet(object(), self.response, refresh=True)
        self.assertEqual(6, len(self.sheets.calls))

    def test_each_tab_says_which_of_its_cells_are_formulas(self):
        def get(url, params=None, timeout=None):
            self.sheets.calls.append((url, params))
            if not url.endswith(":batchGet"):
                return _GoogleResponse(200, self.META)
            if ("valueRenderOption", "FORMULA") in params:
                return _GoogleResponse(200, {"valueRanges": [{"values": [["Flow In", "=B3"], ["", "x", "=SUM(A1:A2)"]]}]})
            return _GoogleResponse(200, self.VALUES)

        self.sheets.get = get
        body = relay.get_finance_sheet(object(), self.response)
        self.assertEqual([[0, 1], [1, 2]], body["sheets"][0]["formulas"])
        self.assertEqual([], body["updates"])

    def test_the_sheet_goes_without_formulas_when_they_cannot_be_read(self):
        def get(url, params=None, timeout=None):
            self.sheets.calls.append((url, params))
            if url.endswith(":batchGet") and ("valueRenderOption", "FORMULA") in params:
                return _GoogleResponse(500, {})
            return _GoogleResponse(200, self.VALUES if url.endswith(":batchGet") else self.META)

        self.sheets.get = get
        self.assertNotIn("formulas", relay.get_finance_sheet(object(), self.response)["sheets"][0])

    def test_when_each_balance_was_updated_comes_with_every_answer_even_from_memory(self):
        relay.get_finance_sheet(object(), self.response)
        relay.typed_updates = lambda: [{"id": "Home|roth ira|2", "at": 5.0}]
        self.assertEqual([{"id": "Home|roth ira|2", "at": 5.0}], relay.get_finance_sheet(object(), self.response)["updates"])
        self.assertNotIn("updates", relay._finance_cache["body"], "the cached workbook doesn't keep a copy that would go stale")

    def test_a_failure_is_answered_from_memory_for_a_few_seconds(self):
        self.sheets.meta = (403, {"error": {"message": "The caller does not have permission", "status": "PERMISSION_DENIED"}})
        for _ in range(3):
            with self.assertRaises(relay.HTTPException) as caught:
                relay.get_finance_sheet(object(), self.response)
            self.assertEqual("not_shared", caught.exception.detail["error"])
        self.assertEqual(1, len(self.sheets.calls))
        relay._finance_cache["failed_at"] -= relay.FINANCE_MIN_REFRESH_SECONDS + 1
        self.sheets.meta = (200, self.META)
        self.assertEqual("Budget", relay.get_finance_sheet(object(), self.response)["title"])

    def test_an_unreachable_google_is_a_502_without_the_details(self):
        self.sheets.meta = (200, ConnectionError("https://sheets.googleapis.com/v4/spreadsheets/sheet123 refused"))
        with self.assertRaises(relay.HTTPException) as caught:
            relay.get_finance_sheet(object(), self.response)
        self.assertEqual(502, caught.exception.status_code)
        self.assertNotIn("sheet123", caught.exception.detail["message"])

    def test_charts_ask_for_their_formats_and_go_without_them_on_a_failure(self):
        self.sheets.meta = (200, FinanceSheet.CHART_META)
        self.sheets.values = (200, {"valueRanges": [{"values": []}, {"values": []}]})
        body = relay.get_finance_sheet(object(), self.response)
        self.assertEqual(4, len(self.sheets.calls))
        _, params = self.sheets.calls[2]
        self.assertIn(("ranges", "'Home'!S61"), params)
        self.assertTrue(any(k == "fields" and "numberFormat" in v for k, v in params))
        self.assertEqual(5, len(body["charts"]))
        failing = _FakeSheets((200, FinanceSheet.CHART_META))
        failing.get = lambda url, params=None, timeout=None: (_ for _ in ()).throw(ConnectionError("down"))
        self.assertEqual({}, relay.chart_formats(failing, FinanceSheet.CHART_META))

    def test_no_sheet_configured(self):
        relay.FINANCE_SHEET_ID = ""
        with self.assertRaises(relay.HTTPException) as caught:
            relay.get_finance_sheet(object(), self.response)
        self.assertEqual((503, "not_configured"), (caught.exception.status_code, caught.exception.detail["error"]))

    def test_a_read_stuck_on_google_is_not_queued_behind(self):
        relay._finance_cache.update(at=time.time() - 3600, body={"title": "stale"})
        saved = relay.FINANCE_LOCK_SECONDS
        relay.FINANCE_LOCK_SECONDS = 0.05
        relay._finance_lock.acquire()
        try:
            self.assertEqual("stale", relay.get_finance_sheet(object(), self.response)["title"])
        finally:
            relay._finance_lock.release()
            relay.FINANCE_LOCK_SECONDS = saved


class UptimeTest(unittest.TestCase):
    """The uptime record's pure parts: reading the box's routes and tailnet, and turning samples into the status picture."""

    # The box's own table, 2026-10-04: the wired default route, and Wi-Fi's as the spare.
    ROUTES = (
        "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n"
        "wlo1\t00000000\t0244A8C0\t0003\t0\t0\t3003\t00000000\t0\t0\t0\n"
        "eno2\t00000000\t0144A8C0\t0003\t0\t0\t1002\t00000000\t0\t0\t0\n"
        "eno2\t0001A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n"
    )
    T = 1_791_000_000  # on a minute

    def test_the_default_gateway_is_the_lowest_metric_default_route(self):
        self.assertEqual("192.168.68.1", relay.default_gateway(self.ROUTES))

    def test_no_default_route_is_no_gateway(self):
        self.assertIsNone(relay.default_gateway(self.ROUTES.splitlines()[0] + "\neno2\t0001A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n"))
        self.assertIsNone(relay.default_gateway(""))
        self.assertIsNone(relay.default_gateway("header\nshort line\neno2\t00000000\tnothex!!\t0003\t0\t0\t1\t00000000\t0\t0\t0\n"))

    def test_tailnet_devices_go_by_their_dns_name_and_leave_out_funnel(self):
        status = {"Peer": {
            "a": {"HostName": "localhost", "DNSName": "iphone-15-pro.tail4c441a.ts.net.", "OS": "iOS", "Online": True, "LastSeen": "2026-10-04T02:50:00.1Z"},
            "b": {"HostName": "Pixel 10 Pro XL", "DNSName": "pixel-10-pro-xl.tail4c441a.ts.net.", "OS": "android", "Online": False, "LastSeen": "2026-10-04T05:27:17.1Z"},
            "c": {"HostName": "funnel-ingress-node", "DNSName": "", "Online": False, "LastSeen": "2026-10-02T23:10:28.1Z"},
            "d": {"HostName": "old-laptop", "DNSName": "", "OS": "linux", "LastSeen": "0001-01-01T00:00:00Z"},
        }}
        devices = relay.tailnet_devices(status)
        self.assertEqual(["iphone-15-pro", "old-laptop", "pixel-10-pro-xl"], sorted(devices))
        self.assertEqual((True, "iOS"), (devices["iphone-15-pro"]["online"], devices["iphone-15-pro"]["os"]))
        self.assertFalse(devices["pixel-10-pro-xl"]["online"])
        self.assertEqual(1791091637, int(devices["pixel-10-pro-xl"]["last_seen"]))
        self.assertEqual((False, None), (devices["old-laptop"]["online"], devices["old-laptop"]["last_seen"]))
        self.assertEqual({}, relay.tailnet_devices({}))

    def test_tailscaled_times(self):
        self.assertEqual(1791091637, int(relay.epoch_of("2026-10-04T05:27:17.1Z")))
        self.assertEqual(1791091637, int(relay.epoch_of("2026-10-03T22:27:17.123456789-07:00")))
        self.assertIsNone(relay.epoch_of("0001-01-01T00:00:00Z"))
        self.assertIsNone(relay.epoch_of(None))
        self.assertIsNone(relay.epoch_of("yesterday"))

    def test_a_bucket_is_up_down_some_of_each_or_unmeasured(self):
        self.assertEqual(["u", "x", "d", "n"], [relay.bucket_state(3, 0), relay.bucket_state(0, 2), relay.bucket_state(1, 1), relay.bucket_state(0, 0)])

    def test_a_refused_connection_is_an_answer(self):
        import socket

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        listener.listen(1)
        self.assertTrue(relay.tcp_reachable("127.0.0.1", port, timeout=2))
        listener.close()
        self.assertTrue(relay.tcp_reachable("127.0.0.1", port, timeout=2), "nothing listening: refused, so the host is there")
        # Silence, made here rather than found on the network: some sandboxes refuse a connection to
        # an address nothing answers on (192.0.2.1, say) at once instead of letting it time out.
        def silent(address, timeout=None):
            raise TimeoutError

        real = relay.socket.create_connection
        relay.socket.create_connection = silent
        try:
            self.assertFalse(relay.tcp_reachable("192.0.2.1", 9, timeout=0.3))
        finally:
            relay.socket.create_connection = real

    def test_a_lookup_stuck_in_the_resolver_is_not_joined_by_another(self):
        import threading

        release = threading.Event()
        calls = []

        def hang(*args, **kwargs):
            calls.append(args)
            release.wait(5)
            return []

        saved = relay.socket.getaddrinfo
        relay.socket.getaddrinfo = hang
        try:
            self.assertFalse(relay.resolves("example.test", timeout=0.05))
            self.assertFalse(relay.resolves("example.test", timeout=0.05), "still stuck: still not resolving")
            self.assertEqual(1, len(calls), "and no second thread went in behind the first")
            release.set()
            relay._dns_lookup["thread"].join(2)
            self.assertTrue(relay.resolves("example.test", timeout=2), "the resolver is back, and so is the check")
            self.assertEqual(2, len(calls))
        finally:
            release.set()
            relay.socket.getaddrinfo = saved

    def test_a_missing_tailscale_socket_is_no_status(self):
        self.assertIsNone(relay.tailscale_status("/nonexistent/tailscaled.sock", timeout=1))

    def minutes(self, flags):
        """(time, up) samples a minute apart from a string: "u" up, "x" down, " " no sample."""
        return [(self.T + i * 60, c == "u") for i, c in enumerate(flags) if c != " "]

    def test_a_down_stretch_runs_from_its_first_down_sample_to_the_next_up_one(self):
        runs = relay.uptime_runs(self.minutes("uuxxxuu"), self.T + 7 * 60)
        self.assertEqual([{"start": self.T + 120, "end": self.T + 300}], runs)

    def test_one_still_down_has_no_end_while_the_samples_are_fresh(self):
        self.assertEqual([{"start": self.T + 60, "end": None}], relay.uptime_runs(self.minutes("uxx"), self.T + 3 * 60))
        self.assertEqual([{"start": self.T + 60, "end": self.T + 180}], relay.uptime_runs(self.minutes("uxx"), self.T + 3600), "the samples stopped long ago")

    def test_a_hole_in_the_samples_ends_a_stretch(self):
        runs = relay.uptime_runs(self.minutes("ux     xu"), self.T + 9 * 60)
        self.assertEqual([{"start": self.T + 60, "end": self.T + 120}, {"start": self.T + 420, "end": self.T + 480}], runs)

    def test_a_link_that_flaps_is_one_stretch(self):
        runs = relay.uptime_runs(self.minutes("uxxuuxxxuuuuuuuuxu"), self.T + 18 * 60)
        self.assertEqual([{"start": self.T + 60, "end": self.T + 480}, {"start": self.T + 960, "end": self.T + 1020}], runs, "two minutes up joins them; eight doesn't")
        self.assertEqual([{"start": self.T + 60, "end": None}], relay.uptime_runs(self.minutes("uxxuux"), self.T + 6 * 60), "and it is still going")

    def rows(self, flags_by_check, devices=None):
        length = len(next(iter(flags_by_check.values())))
        rows = []
        for i in range(length):
            checks = {k: int(f[i] == "u") for k, f in flags_by_check.items() if f[i] != " "}
            if not checks:
                continue
            rows.append((self.T + i * 60, checks, {name: int(f[i] == "u") for name, f in (devices or {}).items() if f[i] != " "}))
        return rows

    def test_the_summary_cuts_the_samples_into_spans(self):
        rows = self.rows({"router": "u" * 60, "internet": "u" * 20 + "x" * 5 + "u" * 35}, {"pixel": "u" * 30 + "x" * 30})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T - 86400, {"pixel": {"os": "android", "last_seen": 5}})
        by_key = {c["key"]: c for c in body["checks"]}
        self.assertEqual(["server", "router", "internet"], [c["key"] for c in body["checks"]], "in the fixed order, without the checks never made")
        self.assertEqual("u" * 12, by_key["router"]["states"])
        self.assertEqual("uuuuxuuuuuuu", by_key["internet"]["states"])
        self.assertAlmostEqual(55 / 60, by_key["internet"]["up_fraction"])
        self.assertEqual(300, by_key["internet"]["down_seconds"])
        self.assertEqual((1.0, 0), (by_key["router"]["up_fraction"], by_key["router"]["down_seconds"]))
        self.assertEqual("u" * 12, by_key["server"]["states"])
        self.assertEqual(300.0, body["bucket_seconds"])
        self.assertEqual([{"check": "internet", "start": self.T + 1200, "end": self.T + 1500, "seconds": 300}], body["outages"])
        pixel = body["devices"][0]
        self.assertEqual(("pixel", "android", False, self.T + 29 * 60, "uuuuuuxxxxxx"), (pixel["name"], pixel["os"], pixel["online"], pixel["last_seen"], pixel["states"]))
        self.assertTrue(by_key["internet"]["up"])

    def test_a_device_seen_at_all_in_a_span_was_on_the_tailnet_for_it(self):
        rows = self.rows({"router": "u" * 60}, {"phone": "xuxxx" + "x" * 5 + "u" * 50})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)
        self.assertEqual("ux" + "u" * 10, body["devices"][0]["states"])

    def test_a_span_that_is_partly_down_says_so(self):
        rows = self.rows({"dns": "uuxuu" + "u" * 55})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)
        self.assertEqual("d" + "u" * 11, {c["key"]: c for c in body["checks"]}["dns"]["states"])

    def test_minutes_without_a_sample_are_the_server_being_down(self):
        rows = self.rows({"router": "u" * 15 + " " * 20 + "u" * 25})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T - 86400)
        by_key = {c["key"]: c for c in body["checks"]}
        self.assertEqual("uuuxxxxuuuuu", by_key["server"]["states"])
        self.assertEqual("uuunnnnuuuuu", by_key["router"]["states"], "the router wasn't measured then, which is not the same as down")
        self.assertEqual(1200, by_key["server"]["down_seconds"])
        self.assertEqual([{"check": "server", "start": self.T + 15 * 60, "end": self.T + 35 * 60, "seconds": 1200}], body["outages"])
        self.assertEqual(1.0, by_key["router"]["up_fraction"])

    def test_a_hole_the_range_opens_in_the_middle_of_is_listed_from_where_it_began(self):
        rows = self.rows({"router": " " * 20 + "u" * 40})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T - 86400, previous_at=self.T - 600)
        self.assertEqual("xxxxuuuuuuuu", body["checks"][0]["states"])
        self.assertEqual([{"check": "server", "start": self.T - 540, "end": self.T + 1200, "seconds": 1740}], body["outages"])
        # Without the sample before the range there is nothing to say when it began, and it isn't guessed.
        self.assertEqual([], relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T - 86400)["outages"])

    def test_a_hole_that_runs_to_the_end_of_the_range_ends_there(self):
        rows = self.rows({"router": "u" * 30})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T - 86400)
        self.assertEqual([{"check": "server", "start": self.T + 1800, "end": self.T + 3600, "seconds": 1800}], body["outages"], "whoever asks is being answered: it is over")
        # And a range with no sample in it at all, on a relay that had been recording.
        empty = relay.uptime_summary([], self.T, self.T + 3600, 12, self.T - 86400, previous_at=self.T - 120)
        self.assertEqual([{"check": "server", "start": self.T - 60, "end": self.T + 3600, "seconds": 3660}], empty["outages"])
        self.assertEqual("x" * 12, empty["checks"][0]["states"])

    def test_whether_a_device_is_online_now_is_the_newest_samples_word_alone(self):
        rows = [(self.T + i * 60, {"router": 1}, {"phone": 1, "laptop": 1} if i < 59 else {"laptop": 0}) for i in range(60)]
        devices = {d["name"]: d for d in relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)["devices"]}
        self.assertIsNone(devices["phone"]["online"], "the tailnet didn't name it that minute: unknown, not what an older sample said")
        self.assertEqual(self.T + 58 * 60, devices["phone"]["last_seen"])
        self.assertIs(False, devices["laptop"]["online"])

    def test_nothing_before_the_first_sample_ever_counts_as_down(self):
        rows = self.rows({"router": " " * 30 + "u" * 30})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T + 30 * 60)
        server = body["checks"][0]
        self.assertEqual("nnnnnnuuuuuu", server["states"])
        self.assertEqual((1.0, 0), (server["up_fraction"], server["down_seconds"]))
        self.assertEqual([], body["outages"])

    def test_an_outage_inside_its_cause_is_left_off_the_list(self):
        down = "u" * 10 + "x" * 10 + "u" * 40
        rows = self.rows({"router": down, "internet": down, "dns": "u" * 9 + "x" * 12 + "u" * 39, "frigate": "u" * 60, "cameras": "u" * 30 + "x" * 5 + "u" * 25})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)
        self.assertEqual([("cameras", 300), ("router", 600)], [(o["check"], o["seconds"]) for o in body["outages"]], "newest first; dns a sample either side still counts as inside")
        self.assertEqual("uuxxuuuuuuuu", {c["key"]: c for c in body["checks"]}["internet"]["states"], "the bars still show it")

    def test_an_outage_that_outlasts_its_cause_is_listed(self):
        rows = self.rows({"router": "u" * 10 + "x" * 5 + "u" * 45, "internet": "u" * 10 + "x" * 30 + "u" * 20})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)
        self.assertEqual(["internet", "router"], sorted(o["check"] for o in body["outages"]))

    def test_no_samples_at_all(self):
        body = relay.uptime_summary([], self.T, self.T + 3600, 12, None)
        self.assertEqual([{"key": "server", "states": "n" * 12, "up_fraction": None, "down_seconds": 0, "up": None}], body["checks"])
        self.assertEqual(([], []), (body["devices"], body["outages"]))

    def test_stale_samples_say_nothing_about_now(self):
        rows = self.rows({"router": "u" * 10})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)
        self.assertEqual([None, None], [c["up"] for c in body["checks"]])

    def test_an_outage_still_going_is_measured_up_to_now(self):
        rows = self.rows({"frigate": "u" * 50 + "x" * 10})
        body = relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)
        self.assertEqual([{"check": "frigate", "start": self.T + 3000, "end": None, "seconds": 600}], body["outages"])
        self.assertFalse({c["key"]: c for c in body["checks"]}["frigate"]["up"])

    def test_a_check_the_relay_learns_later_is_shown_after_the_known_ones(self):
        rows = self.rows({"zigbee": "u" * 60, "router": "u" * 60})
        self.assertEqual(["server", "router", "zigbee"], [c["key"] for c in relay.uptime_summary(rows, self.T, self.T + 3600, 12, self.T)["checks"]])


class UptimeRecordTest(_ScratchDb):
    """The uptime record against a scratch relay.db."""

    # Not `T`: the scratch database's own `T` is a moment two hours ago, to the microsecond.
    MINUTE = 1_791_000_000

    def test_samples_are_kept_and_read_back_as_a_report(self):
        for i in range(60):
            relay.record_uptime(self.MINUTE + i * 60, {"router": True, "internet": i not in (10, 11)},
                                {"pixel-10-pro-xl": {"online": i < 30, "last_seen": 123.0, "os": "android"}})
        body = relay.uptime_report(1, 12, now=self.MINUTE + 3600)
        by_key = {c["key"]: c for c in body["checks"]}
        self.assertEqual("uuduuuuuuuuu", by_key["internet"]["states"], "two minutes of the third five down")
        self.assertEqual(self.MINUTE, body["recording_since"])
        device = body["devices"][0]
        self.assertEqual(("pixel-10-pro-xl", "android", self.MINUTE + 29 * 60), (device["name"], device["os"], device["last_seen"]))
        self.assertEqual({"os": "android", "last_seen": 123.0}, relay.state_get("uptime_devices")["pixel-10-pro-xl"], "offline now: Tailscale's own last-seen")

    def test_a_device_last_seen_is_remembered_past_the_range(self):
        relay.record_uptime(self.MINUTE, {"router": True}, {"phone": {"online": True, "last_seen": None, "os": "iOS"}})
        relay.record_uptime(self.MINUTE + 60, {"router": True}, {"phone": {"online": False, "last_seen": None, "os": "iOS"}})
        self.assertEqual(self.MINUTE, relay.state_get("uptime_devices")["phone"]["last_seen"])
        body = relay.uptime_report(1, 12, now=self.MINUTE + 7200)
        self.assertEqual([], body["devices"], "no sample in the range names it")

    def test_the_report_reaches_back_for_the_sample_before_its_range(self):
        relay.record_uptime(self.MINUTE - 600, {"router": True}, {})
        for i in range(20, 60):
            relay.record_uptime(self.MINUTE + i * 60, {"router": True}, {})
        body = relay.uptime_report(1, 12, now=self.MINUTE + 3600)
        self.assertEqual([("server", self.MINUTE - 540, self.MINUTE + 1200)], [(o["check"], o["start"], o["end"]) for o in body["outages"]])

    def test_a_minute_recorded_twice_is_one_row(self):
        relay.record_uptime(self.MINUTE, {"router": False}, {})
        relay.record_uptime(self.MINUTE, {"router": True}, {})
        self.assertEqual([(self.MINUTE, '{"router": 1}')], relay.with_db(lambda c: c.execute("SELECT at, checks FROM uptime").fetchall()))

    def test_old_samples_are_dropped_on_the_hour(self):
        hour = self.MINUTE - self.MINUTE % 3600
        relay.record_uptime(hour - relay.UPTIME_KEEP_DAYS * 86400 - 60, {"router": True}, {})
        relay.record_uptime(hour + 120, {"router": True}, {})
        self.assertEqual(2, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM uptime").fetchone()[0]), "not on the hour: nothing dropped")
        relay.record_uptime(hour, {"router": True}, {})
        self.assertEqual([hour, hour + 120], [r[0] for r in relay.with_db(lambda c: c.execute("SELECT at FROM uptime ORDER BY at").fetchall())])

    def test_the_route_wants_a_session_and_names_the_checks(self):
        relay.record_uptime(int(time.time()) // 60 * 60, {"router": True, "dns": True}, {})
        with self.assertRaises(relay.HTTPException) as refused:
            relay.status_data(_Caller(None))
        self.assertEqual(401, refused.exception.status_code)
        saved = relay.require_frigate_session
        relay.require_frigate_session = lambda request: "andrew"
        try:
            body = relay.status_data(_Caller("frigate_token=x"), hours=0.1, buckets=5000)
        finally:
            relay.require_frigate_session = saved
        self.assertEqual(["Server running", "Home router", "Name lookups (DNS)"], [c["name"] for c in body["checks"]])
        self.assertEqual(288, len(body["checks"][0]["states"]), "buckets are capped")
        self.assertAlmostEqual(3600, body["until"] - body["since"], delta=1, msg="and the range is at least an hour")

    def test_the_page_is_html_that_asks_for_the_data(self):
        page = relay.status_page()
        self.assertEqual("text/html", page.media_type)
        self.assertIn("status/data?hours=", page.body)
        # It isn't a raw string: an escape meant for the script would be eaten by Python first
        # (an `isn\'t` once ended a string early and left the page stuck on "Loading").
        self.assertNotIn("\\", page.body)
        self.assertNotIn("isn't", page.body)
        # Each timeline is one stop for a keyboard, with a name and its span's words for a screen reader.
        self.assertIn('role="slider"', page.body)
        self.assertIn("aria-valuetext", page.body)
        self.assertIn('aria-live="polite"', page.body)


class _FakePlaid:
    """Stands in for `plaid_post`: answers each path from `answers` (a dict, or an exception to raise), keeping the calls."""

    def __init__(self, **answers):
        self.answers, self.calls = answers, []

    def __call__(self, path, body):
        self.calls.append((path, body))
        answer = self.answers.get(path.strip("/").replace("/", "_"), {})
        if isinstance(answer, Exception):
            raise answer
        return answer

    def paths(self):
        return [path for path, _ in self.calls]


class _FeedSheets:
    """Stands in for the feed sheet's AuthorizedSession: the tabs it has, and every write made to it."""

    def __init__(self, tabs=(), status=200, body=None):
        self.tabs, self.status, self.body, self.posts = list(tabs), status, body, []

    def get(self, url, params=None, timeout=None):
        return _GoogleResponse(self.status, self.body or {"sheets": [{"properties": {"title": t}} for t in self.tabs]})

    def post(self, url, json=None, timeout=None):
        self.posts.append((url, json))
        return _GoogleResponse(self.status, self.body or {})


class BankSyncTest(_ScratchDb):
    """Bank sync: linking through Plaid's hosted page, reading an institution into relay.db, the feed sheet's cells and the daily slot."""

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "feed_google", "FINANCE_FEED_SHEET_ID", "PLAID_CLIENT_ID", "PLAID_SECRET", "PLAID_REDIRECT_URI", "BANK_SYNC_HOUR")
    T = 1_791_000_000.0
    ACCOUNTS = [
        {"account_id": "chk", "name": "Total Checking", "official_name": "Chase Total Checking", "mask": "0123", "type": "depository", "subtype": "checking",
         "balances": {"current": 4200.5, "available": 4100.0, "limit": None, "iso_currency_code": "USD"}},
        {"account_id": "card", "name": "Sapphire", "mask": "9911", "type": "credit", "subtype": "credit card",
         "balances": {"current": 812.4, "available": 9187.6, "limit": 10000, "iso_currency_code": "USD"}},
        {"account_id": "ira", "name": "Roth IRA", "mask": "7788", "type": "investment", "subtype": "roth",
         "balances": {"current": 51000.0, "available": None, "limit": None, "iso_currency_code": "USD"}},
    ]
    HOLDINGS = {
        "holdings": [{"account_id": "ira", "security_id": "s1", "quantity": 100, "institution_price": 510.0, "institution_price_as_of": "2026-10-02",
                      "institution_value": 51000.0, "cost_basis": 40000.0, "iso_currency_code": "USD"}],
        "securities": [{"security_id": "s1", "ticker_symbol": "VTI", "name": "Vanguard Total Stock Market ETF", "type": "etf"}],
    }
    LIABILITIES = {"liabilities": {
        "credit": [{"account_id": "card", "aprs": [{"apr_type": "cash_apr", "apr_percentage": 29.99}, {"apr_type": "purchase_apr", "apr_percentage": 21.49}],
                    "minimum_payment_amount": 40.0, "next_payment_due_date": "2026-10-20"}],
        "student": [{"account_id": "loan", "interest_rate_percentage": 3.08, "minimum_payment_amount": 210.0, "next_payment_due_date": "2026-10-15"}],
        "mortgage": [{"account_id": "house", "interest_rate": {"percentage": 6.25, "type": "fixed"}, "next_monthly_payment": 3100.0, "next_payment_due_date": "2026-11-01"}],
    }}

    def setUp(self):
        super().setUp()
        relay.PLAID_CLIENT_ID, relay.PLAID_SECRET, relay.PLAID_REDIRECT_URI = "client", "secret", ""
        relay.FINANCE_FEED_SHEET_ID = ""
        self.background = []
        relay.bank_sync_in_background = lambda: self.background.append(True)

    def plaid(self, **answers):
        relay.plaid_post = _FakePlaid(**answers)
        return relay.plaid_post

    def link(self, item_id="item1", name="Chase"):
        relay.plaid_save_item(item_id, f"access-{item_id}", {"institution_id": "ins_3", "name": name}, "alex", self.T)

    def synced(self, **overrides):
        answers = {"item_get": {"item": {"products": ["transactions", "investments", "liabilities"], "consent_expiration_time": None}},
                   "accounts_get": {"accounts": self.ACCOUNTS}, "investments_holdings_get": self.HOLDINGS, "liabilities_get": self.LIABILITIES}
        answers.update(overrides)
        return self.plaid(**answers)

    def test_a_link_asks_for_what_the_kind_must_have_and_the_rest_where_there_is_any(self):
        bank = relay.plaid_link_request("alex", "bank")
        self.assertEqual(["transactions"], bank["products"])
        self.assertEqual(["investments", "liabilities"], bank["required_if_supported_products"])
        self.assertEqual({}, bank["hosted_link"])
        self.assertEqual(["investments"], relay.plaid_link_request("alex", "investments")["products"])
        self.assertEqual(["liabilities"], relay.plaid_link_request("alex", "investments")["required_if_supported_products"])
        self.assertEqual(["liabilities"], relay.plaid_link_request("alex", "loans")["products"])
        # Nothing that moves money, whatever is linked.
        for kind in relay.PLAID_KINDS:
            asked = relay.plaid_link_request("alex", kind)
            self.assertFalse({"auth", "transfer", "payment_initiation"} & set(asked["products"] + asked["required_if_supported_products"]))

    def test_plaid_is_told_who_is_linking_only_as_a_hash(self):
        asked = relay.plaid_link_request("alex", "bank")
        self.assertNotIn("alex", json.dumps(asked))
        self.assertEqual(asked["user"], relay.plaid_link_request("alex", "loans")["user"])
        self.assertNotEqual(asked["user"], relay.plaid_link_request("sam", "bank")["user"])

    def test_signing_in_again_sends_the_token_and_asks_for_nothing_new(self):
        asked = relay.plaid_link_request("alex", "bank", access="access-item1")
        self.assertEqual("access-item1", asked["access_token"])
        self.assertNotIn("products", asked)

    def test_starting_a_link_answers_the_page_and_remembers_the_token(self):
        self.plaid(link_token_create={"link_token": "link-1", "hosted_link_url": "https://secure.plaid.com/hl/abc"})
        started = relay.plaid_link_start("alex", "bank", now=self.T)
        self.assertEqual({"token": "link-1", "url": "https://secure.plaid.com/hl/abc", "expires_at": self.T + relay.PLAID_LINK_SECONDS}, started)
        self.assertEqual("alex", relay.state_get(relay.PLAID_LINKS_KEY)["link-1"]["by"])

    def test_a_link_without_a_page_and_an_unknown_kind_are_refused(self):
        self.plaid(link_token_create={"link_token": "link-1"})
        with self.assertRaises(relay.PlaidError) as refused:
            relay.plaid_link_start("alex", "bank", now=self.T)
        self.assertEqual("NO_HOSTED_LINK", refused.exception.code)
        with self.assertRaises(relay.HTTPException) as bad:
            relay.plaid_link_start("alex", "crypto", now=self.T)
        self.assertEqual(400, bad.exception.status_code)
        with self.assertRaises(relay.HTTPException) as unknown:
            relay.plaid_link_start("alex", item_id="nope", now=self.T)
        self.assertEqual(404, unknown.exception.status_code)

    def start(self, item_id=None):
        self.plaid(link_token_create={"link_token": "link-1", "hosted_link_url": "https://secure.plaid.com/hl/abc"})
        relay.plaid_link_start("alex", "bank", item_id, now=self.T)

    def test_a_link_still_open_is_pending_and_one_left_is_exited(self):
        self.start()
        self.plaid(link_token_get={"link_sessions": []})
        self.assertEqual({"status": "pending"}, relay.plaid_link_finish("link-1", now=self.T + 60))
        self.plaid(link_token_get={"link_sessions": [{"link_session_id": "s", "exit": {"metadata": {"status": "institution_not_found"}}}]})
        self.assertEqual({"status": "exited"}, relay.plaid_link_finish("link-1", now=self.T + 60))
        self.assertEqual([], self.background)

    def test_a_link_too_old_or_never_made_is_expired(self):
        self.start()
        self.plaid(link_token_get={"link_sessions": []})
        self.assertEqual({"status": "expired"}, relay.plaid_link_finish("link-1", now=self.T + relay.PLAID_LINK_SECONDS + 1))
        self.assertEqual({"status": "expired"}, relay.plaid_link_finish("never", now=self.T))

    def test_a_finished_link_keeps_the_token_once_and_starts_a_sync(self):
        self.start()
        plaid = self.plaid(
            link_token_get={"link_sessions": [{"finished_at": "2026-10-05T12:00:00Z", "results": {"item_add_results": [
                {"public_token": "public-1", "institution": {"institution_id": "ins_3", "name": "Chase"}}]}}]},
            item_public_token_exchange={"access_token": "access-item1", "item_id": "item1"},
        )
        self.assertEqual({"status": "linked", "institutions": ["Chase"]}, relay.plaid_link_finish("link-1", now=self.T + 90))
        self.assertEqual([("item1", "access-item1", "Chase", "alex")],
                         relay.with_db(lambda c: c.execute("SELECT item_id, access, institution, linked_by FROM plaid_items").fetchall()))
        self.assertEqual([True], self.background)
        # Asked again (the app polls), it answers from memory: the public token is good only once.
        self.assertEqual({"status": "linked", "institutions": ["Chase"]}, relay.plaid_link_finish("link-1", now=self.T + 95))
        self.assertEqual(1, plaid.paths().count("/item/public_token/exchange"))
        self.assertEqual([True], self.background)

    def test_a_link_finished_with_nobody_asking_is_collected_by_the_relay_itself(self):
        # The app was restarted while the person was at their bank: it never asks about link-1 again.
        self.start()
        plaid = self.plaid(
            link_token_get={"link_sessions": [{"finished_at": "2026-10-05T12:00:00Z", "results": {"item_add_results": [
                {"public_token": "public-1", "institution": {"institution_id": "ins_128026", "name": "Capital One"}}]}}]},
            item_public_token_exchange={"access_token": "access-item1", "item_id": "item1"},
        )
        self.assertEqual(["Capital One"], relay.plaid_collect_links(now=self.T + 120))
        self.assertEqual([("item1", "Capital One", "alex")], relay.with_db(lambda c: c.execute("SELECT item_id, institution, linked_by FROM plaid_items").fetchall()))
        self.assertEqual([True], self.background)
        # Collected once: the next look asks Plaid nothing, and the app asking late is told it is linked.
        asked = len(plaid.calls)
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 420))
        self.assertEqual(asked, len(plaid.calls))
        self.assertEqual({"status": "linked", "institutions": ["Capital One"]}, relay.plaid_link_finish("link-1", now=self.T + 430))

    def test_a_link_still_open_or_refused_is_left_as_it_was_and_looked_at_again(self):
        self.start()
        self.plaid(link_token_get={"link_sessions": []})
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 60))
        self.plaid(link_token_get=relay.PlaidError("INVALID_LINK_TOKEN"))
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 360))
        self.assertIsNone(relay.state_get(relay.PLAID_LINKS_KEY)["link-1"].get("done"))
        self.assertEqual([], self.background)
        # Finished just before Plaid's token for it lapses, and found a check later: still collected.
        self.plaid(
            link_token_get={"link_sessions": [{"finished_at": "2026-10-05T12:29:00Z", "results": {"item_add_results": [
                {"public_token": "public-1", "institution": {"name": "Chase"}}]}}]},
            item_public_token_exchange={"access_token": "access-item1", "item_id": "item1"},
        )
        self.assertEqual(["Chase"], relay.plaid_collect_links(now=self.T + relay.PLAID_LINK_SECONDS + 240))

    def test_a_link_long_dead_is_not_asked_about_for_ever(self):
        self.start()
        plaid = self.plaid(link_token_get={"link_sessions": []})
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 2 * relay.PLAID_LINK_SECONDS + 1))
        self.assertEqual([], plaid.calls)

    def test_signing_in_again_clears_the_error_and_keeps_the_token(self):
        self.link()
        relay.with_db(lambda c: (c.execute("UPDATE plaid_items SET error='ITEM_LOGIN_REQUIRED'"), c.commit()))
        self.start(item_id="item1")
        self.assertEqual("access-item1", relay.plaid_post.calls[0][1]["access_token"])
        plaid = self.plaid(link_token_get={"link_sessions": [{"finished_at": "2026-10-05T12:00:00Z", "results": {"item_add_results": []}}]})
        self.assertEqual({"status": "linked", "institutions": ["Chase"]}, relay.plaid_link_finish("link-1", now=self.T + 90))
        self.assertEqual([("access-item1", None)], relay.with_db(lambda c: c.execute("SELECT access, error FROM plaid_items").fetchall()))
        self.assertNotIn("/item/public_token/exchange", plaid.paths())

    def test_loan_terms_come_from_each_kind_of_liability(self):
        terms = relay.plaid_liability_terms(self.LIABILITIES["liabilities"])
        self.assertEqual({"apr": 21.49, "min_payment": 40.0, "due": "2026-10-20"}, terms["card"], "the purchase rate, not the cash advance's")
        self.assertEqual({"apr": 3.08, "min_payment": 210.0, "due": "2026-10-15"}, terms["loan"])
        self.assertEqual({"apr": 6.25, "min_payment": 3100.0, "due": "2026-11-01"}, terms["house"])
        self.assertEqual({}, relay.plaid_liability_terms(None))

    def test_a_sync_reads_balances_holdings_and_loan_terms(self):
        self.link()
        plaid = self.synced()
        self.assertIsNone(relay.plaid_sync_item("item1", "access-item1", self.T + 100))
        self.assertTrue(all(body == {"access_token": "access-item1"} for _, body in plaid.calls))
        chase, = relay.bank_institutions()
        self.assertEqual(("Chase", self.T + 100, None, False), (chase["name"], chase["synced_at"], chase["error"], chase["needs_relink"]))
        by_id = {a["id"]: a for a in chase["accounts"]}
        self.assertEqual((4200.5, 4100.0, "Chase Total Checking 0123"), (by_id["chk"]["balance"], by_id["chk"]["available"], by_id["chk"]["key"]))
        self.assertEqual((812.4, 10000, 21.49, "2026-10-20"), (by_id["card"]["balance"], by_id["card"]["limit"], by_id["card"]["apr"], by_id["card"]["due"]))
        self.assertEqual(1, by_id["ira"]["holdings"])
        self.assertNotIn("access", json.dumps(relay.bank_status(self.T)), "no token ever leaves the relay")

    def test_a_bank_with_no_investments_or_loans_is_not_asked_for_them(self):
        self.link()
        plaid = self.synced(item_get={"item": {"products": ["transactions"]}})
        self.assertIsNone(relay.plaid_sync_item("item1", "access-item1", self.T))
        self.assertEqual(["/item/get", "/accounts/get"], plaid.paths())

    def test_nothing_of_a_kind_is_not_a_failure(self):
        self.link()
        self.synced(investments_holdings_get=relay.PlaidError("NO_INVESTMENT_ACCOUNTS"), liabilities_get=relay.PlaidError("NO_LIABILITY_ACCOUNTS"))
        self.assertIsNone(relay.plaid_sync_item("item1", "access-item1", self.T))
        self.assertEqual(3, len(relay.bank_institutions()[0]["accounts"]))

    def test_holdings_not_ready_yet_leave_the_last_ones_in_place(self):
        self.link()
        self.synced()
        relay.plaid_sync_item("item1", "access-item1", self.T)
        self.synced(investments_holdings_get=relay.PlaidError("PRODUCT_NOT_READY"))
        self.assertIsNone(relay.plaid_sync_item("item1", "access-item1", self.T + 60))
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_holdings").fetchone()[0]))
        # ...but an empty answer is the account sold out of everything.
        self.synced(investments_holdings_get={"holdings": [], "securities": []})
        relay.plaid_sync_item("item1", "access-item1", self.T + 120)
        self.assertEqual(0, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_holdings").fetchone()[0]))

    def test_a_refused_sync_keeps_the_last_balances_and_says_to_sign_in_again(self):
        self.link()
        self.synced()
        relay.plaid_sync_item("item1", "access-item1", self.T)
        self.synced(accounts_get=relay.PlaidError("ITEM_LOGIN_REQUIRED", "the login details of this item have changed"))
        self.assertEqual("ITEM_LOGIN_REQUIRED", relay.plaid_sync_item("item1", "access-item1", self.T + 86400))
        chase, = relay.bank_institutions()
        self.assertEqual(("ITEM_LOGIN_REQUIRED", True, self.T), (chase["error"], chase["needs_relink"], chase["synced_at"]))
        self.assertEqual(3, len(chase["accounts"]))

    def test_an_account_closed_at_the_bank_goes_with_what_it_held(self):
        self.link()
        self.synced()
        relay.plaid_sync_item("item1", "access-item1", self.T)
        self.synced(accounts_get={"accounts": self.ACCOUNTS[:2]}, investments_holdings_get=relay.PlaidError("PRODUCT_NOT_READY"))
        relay.plaid_sync_item("item1", "access-item1", self.T + 60)
        self.assertEqual(["card", "chk"], sorted(a["id"] for a in relay.bank_institutions()[0]["accounts"]))
        self.assertEqual(0, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_holdings").fetchone()[0]))

    def test_a_key_is_the_label_or_its_first_free_number(self):
        self.assertEqual("Fidelity Roth IRA", relay.feed_key("Fidelity Roth IRA", set()))
        self.assertEqual("Fidelity Roth IRA (2)", relay.feed_key("Fidelity Roth IRA", {"Fidelity Roth IRA"}))
        self.assertEqual("Fidelity Roth IRA (2)", relay.feed_key("Fidelity Roth IRA", {"Fidelity Roth IRA", "Fidelity Roth IRA (3)"}))
        self.assertEqual("Account", relay.feed_key("", set()))

    TWINS = [{"account_id": "a", "name": "Roth IRA", "type": "investment", "balances": {"current": 1.0}},
             {"account_id": "b", "name": "Roth IRA", "type": "investment", "balances": {"current": 2.0}}]

    def keys(self):
        return {a["id"]: a["key"] for i in relay.bank_institutions() for a in i["accounts"]}

    def test_two_accounts_of_one_name_get_keys_of_their_own(self):
        self.link(name="Fidelity")
        relay.plaid_store("item1", self.TWINS, {}, [], {}, self.T)
        self.assertEqual({"a": "Fidelity Roth IRA", "b": "Fidelity Roth IRA (2)"}, self.keys())

    def test_closing_one_of_two_does_not_hand_its_key_to_the_other(self):
        self.link(name="Fidelity")
        relay.plaid_store("item1", self.TWINS, {}, [], {}, self.T)
        relay.plaid_store("item1", self.TWINS[1:], {}, [], {}, self.T + 60)
        self.assertEqual({"b": "Fidelity Roth IRA (2)"}, self.keys(), "a formula for the closed account must not start reading the other one")
        # A new account of that name takes the key that was freed, never the survivor's.
        relay.plaid_store("item1", self.TWINS[1:] + [dict(self.TWINS[0], account_id="c")], {}, [], {}, self.T + 120)
        self.assertEqual({"b": "Fidelity Roth IRA (2)", "c": "Fidelity Roth IRA"}, self.keys())

    def test_a_renamed_account_keeps_its_key(self):
        self.link()
        relay.plaid_store("item1", self.ACCOUNTS[:1], {}, [], {}, self.T)
        relay.plaid_store("item1", [dict(self.ACCOUNTS[0], name="Chase Checking Plus")], {}, [], {}, self.T + 60)
        self.assertEqual({"chk": "Chase Total Checking 0123"}, self.keys())

    def test_an_institution_linked_again_comes_back_under_its_keys(self):
        self.link()
        relay.plaid_store("item1", self.ACCOUNTS[:1], {}, [], {}, self.T)
        relay.with_db(lambda c: (c.execute("DELETE FROM plaid_accounts"), c.execute("DELETE FROM plaid_items"), relay.plaid_assign_keys(c), c.commit()))
        self.link("item2")
        relay.plaid_store("item2", [dict(self.ACCOUNTS[0], account_id="chk-again")], {}, [], {}, self.T + 60)
        self.assertEqual({"chk-again": "Chase Total Checking 0123"}, self.keys())

    def test_loan_terms_not_ready_yet_leave_the_last_ones_in_place(self):
        self.link()
        self.synced()
        relay.plaid_sync_item("item1", "access-item1", self.T)
        self.synced(liabilities_get=relay.PlaidError("PRODUCT_NOT_READY"))
        self.assertIsNone(relay.plaid_sync_item("item1", "access-item1", self.T + 60))
        card = next(a for a in relay.bank_institutions()[0]["accounts"] if a["id"] == "card")
        self.assertEqual((21.49, 40.0, "2026-10-20"), (card["apr"], card["min_payment"], card["due"]))
        # ...but an answer with no terms in it is the terms gone.
        self.synced(liabilities_get={"liabilities": {"credit": [], "student": [], "mortgage": []}})
        relay.plaid_sync_item("item1", "access-item1", self.T + 120)
        card = next(a for a in relay.bank_institutions()[0]["accounts"] if a["id"] == "card")
        self.assertEqual((None, None, None), (card["apr"], card["min_payment"], card["due"]))

    def test_the_feed_has_every_account_and_holding_with_blanks_for_what_is_unknown(self):
        self.link()
        self.synced()
        relay.plaid_sync_item("item1", "access-item1", self.T)
        holdings = relay.with_db(lambda c: c.execute(
            "SELECT account_id, ticker, name, quantity, price, value, cost_basis, kind, currency, price_as_of FROM plaid_holdings").fetchall())
        tables = relay.feed_tables(relay.bank_institutions(), holdings, None)
        accounts = tables[relay.FEED_ACCOUNTS_TAB]
        self.assertEqual(relay.FEED_ACCOUNT_COLUMNS, accounts[0])
        self.assertEqual(4, len(accounts))
        checking = next(row for row in accounts if row[0] == "Chase Total Checking 0123")
        self.assertEqual([4200.5, 4100.0, "", "Chase", "Total Checking", "0123", "depository", "checking", "", "", "", "USD"], checking[1:13])
        self.assertTrue(all(len(row) == len(relay.FEED_ACCOUNT_COLUMNS) and None not in row for row in accounts))
        self.assertEqual([relay.FEED_HOLDING_COLUMNS, ["Chase Roth IRA 7788", "VTI", "Vanguard Total Stock Market ETF", 100.0, 510.0, 51000.0, 40000.0, "etf", "Chase", "USD", "2026-10-02"]],
                         tables[relay.FEED_HOLDINGS_TAB])

    def write(self, sheets, tables):
        relay.FINANCE_FEED_SHEET_ID = "feed123"
        relay.feed_google = lambda: sheets
        relay.feed_write(tables)

    def test_the_feed_adds_its_tabs_once_and_writes_raw_from_a1(self):
        sheets = _FeedSheets(tabs=["Sheet1", relay.FEED_ACCOUNTS_TAB])
        self.write(sheets, {relay.FEED_ACCOUNTS_TAB: [["Key"], ["a"]], relay.FEED_HOLDINGS_TAB: [["Account key"]]})
        (added_url, added), (written_url, written) = sheets.posts
        self.assertTrue(added_url.endswith("/feed123:batchUpdate"))
        self.assertEqual([{"addSheet": {"properties": {"title": relay.FEED_HOLDINGS_TAB}}}], added["requests"])
        self.assertTrue(written_url.endswith("/feed123/values:batchUpdate"))
        self.assertEqual("RAW", written["valueInputOption"])
        self.assertEqual(["'Bank feed'!A1", "'Holdings feed'!A1"], [d["range"] for d in written["data"]])

    def test_rows_that_went_are_blanked_in_the_same_write(self):
        sheets = _FeedSheets(tabs=[relay.FEED_ACCOUNTS_TAB])
        self.write(sheets, {relay.FEED_ACCOUNTS_TAB: [["Key", "Balance"], ["a", 1], ["b", 2], ["c", 3]]})
        self.write(sheets, {relay.FEED_ACCOUNTS_TAB: [["Key", "Balance"], ["a", 1]]})
        self.assertEqual([["Key", "Balance"], ["a", 1], ["", ""], ["", ""]], sheets.posts[-1][1]["data"][0]["values"])
        self.assertEqual(2, len(sheets.posts), "no clearing call before either write")

    def test_a_feed_sheet_not_shared_for_editing_is_kept_as_the_feed_s_problem(self):
        self.link()
        relay.FINANCE_FEED_SHEET_ID = "feed123"
        relay.feed_google = lambda: _FeedSheets(status=403, body={"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}})
        relay.write_bank_feed(self.T)
        feed = relay.bank_status(self.T)["feed"]
        self.assertEqual((True, "not_shared", None), (feed["configured"], feed["error"], feed["written_at"]))

    def test_a_written_feed_makes_the_next_read_of_the_budget_sheet_fresh(self):
        self.link()
        relay.FINANCE_FEED_SHEET_ID = "feed123"
        relay.feed_google = lambda: _FeedSheets(tabs=[relay.FEED_ACCOUNTS_TAB, relay.FEED_HOLDINGS_TAB])
        saved = dict(relay._finance_cache)
        try:
            relay._finance_cache["at"] = self.T
            relay.write_bank_feed(self.T)
            self.assertEqual(0.0, relay._finance_cache["at"])
        finally:
            relay._finance_cache.update(saved)
        feed = relay.bank_status(self.T)["feed"]
        self.assertEqual((self.T, None), (feed["written_at"], feed["error"]))

    def test_without_a_feed_sheet_nothing_is_written(self):
        relay.feed_google = lambda: self.fail("no feed sheet is configured")
        relay.write_bank_feed(self.T)
        self.assertFalse(relay.bank_status(self.T)["feed"]["configured"])

    def test_a_sync_of_everything_counts_what_failed(self):
        self.link("item1")
        self.link("item2", "Fidelity")
        answers = {"item_get": {"item": {"products": ["transactions"]}}, "accounts_get": {"accounts": self.ACCOUNTS[:1]}}
        plaid = self.plaid(**answers)

        def post(path, body):
            if body.get("access_token") == "access-item2":
                raise relay.PlaidError("INSTITUTION_DOWN")
            return plaid(path, body)

        relay.plaid_post = post
        self.assertTrue(relay.plaid_sync_all(now=self.T + 10))
        self.assertEqual({"at": self.T + 10, "institutions": 2, "failed": 1}, relay.state_get(relay.PLAID_SYNCED_KEY))
        self.assertEqual([None, "INSTITUTION_DOWN"], [i["error"] for i in relay.bank_institutions()])

    def test_only_one_sync_runs_at_a_time(self):
        relay._bank_lock.acquire()
        try:
            self.assertFalse(relay.plaid_sync_all(now=self.T))
            self.assertTrue(relay.bank_status(self.T)["syncing"])
        finally:
            relay._bank_lock.release()

    def test_the_day_s_sync_is_owed_from_its_hour_until_it_has_run(self):
        from zoneinfo import ZoneInfo

        relay.BANK_SYNC_HOUR = 6
        zone = ZoneInfo("America/Los_Angeles")
        at = lambda hour, minute=0, day=5: relay.datetime(2026, 10, day, hour, minute, tzinfo=zone).timestamp()  # noqa: E731
        self.assertFalse(relay.bank_sync_due(at(5, 59), last=at(6, 2, day=4), zone=zone), "yesterday's ran; today's hour hasn't come")
        self.assertTrue(relay.bank_sync_due(at(6, 0), last=at(6, 2, day=4), zone=zone))
        self.assertFalse(relay.bank_sync_due(at(18), last=at(6, 3), zone=zone))
        self.assertTrue(relay.bank_sync_due(at(3), last=0, zone=zone), "never synced")
        # A "Sync now" in the evening doesn't stand in for the next morning's.
        self.assertTrue(relay.bank_sync_due(at(6, 1, day=6), last=at(21), zone=zone))
        self.assertEqual(at(6, day=6), (relay.bank_sync_slot(at(18), zone) + relay.timedelta(days=1)).timestamp())


class BankRoutesTest(_ScratchDb):
    """The bank routes: who gets in, what Plaid's refusals become, and that unlinking forgets the token."""

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "require_finance_user", "write_bank_feed", "PLAID_CLIENT_ID", "PLAID_SECRET",
                                "FINANCE_FEED_SHEET_ID", "BANK_UNLINK_WAIT_SECONDS")

    def setUp(self):
        super().setUp()
        relay.PLAID_CLIENT_ID, relay.PLAID_SECRET, relay.FINANCE_FEED_SHEET_ID = "client", "secret", ""
        relay.require_finance_user = lambda request: "alex"
        self.background = []
        relay.bank_sync_in_background = lambda: self.background.append(True)
        self.fed = []
        relay.write_bank_feed = lambda now: self.fed.append(relay._bank_lock.locked())
        relay._sync_asked["at"] = 0.0
        self.response = types.SimpleNamespace(headers={})
        relay.plaid_save_item("item1", "access-item1", {"institution_id": "ins_3", "name": "Chase"}, "alex", 1_791_000_000.0)

    def test_the_status_answers_without_plaid_set_up_and_is_never_cached(self):
        relay.PLAID_CLIENT_ID = ""
        body = relay.get_bank(object(), self.response)
        self.assertFalse(body["configured"])
        self.assertEqual("private, no-store", self.response.headers["Cache-Control"])
        with self.assertRaises(relay.HTTPException) as off:
            relay.post_bank_sync(object(), self.response)
        self.assertEqual((503, "not_configured"), (off.exception.status_code, off.exception.detail["error"]))

    def test_opening_the_page_collects_a_link_the_app_lost_track_of(self):
        relay.plaid_post = _FakePlaid(
            link_token_create={"link_token": "link-9", "hosted_link_url": "https://secure.plaid.com/hl/abc"},
            link_token_get={"link_sessions": [{"finished_at": "2026-10-05T12:00:00Z", "results": {"item_add_results": [
                {"public_token": "public-9", "institution": {"name": "American Express"}}]}}]},
            item_public_token_exchange={"access_token": "access-item9", "item_id": "item9"},
        )
        relay.post_bank_link(types.SimpleNamespace(kind="bank", institution=None), object(), self.response)
        body = relay.get_bank(object(), self.response)
        self.assertEqual(["Chase", "American Express"], [i["name"] for i in body["institutions"]])
        self.assertTrue(body["syncing"])
        self.assertEqual([True], self.background)
        # Nothing waiting: the page is answered without a word to Plaid.
        asked = len(relay.plaid_post.calls)
        self.assertFalse(relay.get_bank(object(), self.response)["syncing"])
        self.assertEqual(asked, len(relay.plaid_post.calls))

    def test_someone_who_may_not_see_the_finances_gets_nowhere(self):
        def refuse(request):
            raise relay.HTTPException(403, {"error": "not_allowed"})

        relay.require_finance_user = refuse
        for route in (lambda: relay.get_bank(object(), self.response), lambda: relay.post_bank_sync(object(), self.response),
                      lambda: relay.get_bank_link("link-1", object(), self.response), lambda: relay.delete_bank_institution("item1", object(), self.response),
                      lambda: relay.post_bank_link(types.SimpleNamespace(kind="bank", institution=None), object(), self.response)):
            with self.assertRaises(relay.HTTPException) as refused:
                route()
            self.assertEqual(403, refused.exception.status_code)
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_items").fetchone()[0]))

    def test_plaid_s_refusal_of_a_link_is_passed_on_with_its_code(self):
        relay.plaid_post = _FakePlaid(link_token_create=relay.PlaidError("INVALID_API_KEYS", "invalid client_id or secret provided"))
        with self.assertRaises(relay.HTTPException) as refused:
            relay.post_bank_link(types.SimpleNamespace(kind="bank", institution=None), object(), self.response)
        self.assertEqual((502, "plaid_error", "INVALID_API_KEYS"), (refused.exception.status_code, refused.exception.detail["error"], refused.exception.detail["code"]))

    def test_sync_now_starts_one_sync_and_not_another_within_the_minute(self):
        self.assertTrue(relay.post_bank_sync(object(), self.response)["syncing"])
        self.assertEqual([True], self.background)
        relay.state_set(relay.PLAID_SYNCED_KEY, {"at": time.time()})
        self.assertFalse(relay.post_bank_sync(object(), self.response)["syncing"])
        self.assertEqual([True], self.background)

    def test_a_second_tap_before_the_first_sync_has_begun_does_not_queue_another(self):
        self.assertTrue(relay.post_bank_sync(object(), self.response)["syncing"])
        relay.post_bank_sync(object(), self.response)
        self.assertEqual([True], self.background, "the first one's worker hasn't taken the lock or recorded a sync yet")

    def test_unlinking_tells_plaid_and_forgets_the_token_and_accounts(self):
        relay.plaid_post = _FakePlaid()
        relay.plaid_store("item1", BankSyncTest.ACCOUNTS, {}, BankSyncTest.HOLDINGS["holdings"], {}, 1_791_000_000.0)
        body = relay.delete_bank_institution("item1", object(), self.response)
        self.assertEqual([("/item/remove", {"access_token": "access-item1"})], relay.plaid_post.calls)
        self.assertEqual([], body["institutions"])
        for table in ("plaid_items", "plaid_accounts", "plaid_holdings", "plaid_feed_keys"):
            self.assertEqual(0, relay.with_db(lambda c: c.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]), table)
        self.assertEqual([True], self.fed, "the feed is rewritten before a sync can run again")
        self.assertFalse(relay._bank_lock.locked())

    def test_unlinking_waits_its_turn_behind_a_sync_and_says_so_when_it_cannot(self):
        relay.plaid_post = _FakePlaid()
        relay.BANK_UNLINK_WAIT_SECONDS = 0.05
        relay._bank_lock.acquire()
        try:
            with self.assertRaises(relay.HTTPException) as busy:
                relay.delete_bank_institution("item1", object(), self.response)
        finally:
            relay._bank_lock.release()
        self.assertEqual((503, "busy"), (busy.exception.status_code, busy.exception.detail["error"]))
        self.assertEqual([], relay.plaid_post.calls, "Plaid isn't told while the sync that might put it back is running")
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_items").fetchone()[0]))

    def test_unlinking_what_plaid_already_forgot_still_forgets_it_here(self):
        relay.plaid_post = _FakePlaid(item_remove=relay.PlaidError("ITEM_NOT_FOUND"))
        self.assertEqual([], relay.delete_bank_institution("item1", object(), self.response)["institutions"])

    def test_unlinking_keeps_the_token_when_plaid_could_not_be_told(self):
        relay.plaid_post = _FakePlaid(item_remove=relay.PlaidError("INTERNAL_SERVER_ERROR"))
        with self.assertRaises(relay.HTTPException) as failed:
            relay.delete_bank_institution("item1", object(), self.response)
        self.assertEqual(502, failed.exception.status_code)
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_items").fetchone()[0]))
        self.assertFalse(relay._bank_lock.locked(), "a refusal lets go of the lock")
        with self.assertRaises(relay.HTTPException) as unknown:
            relay.delete_bank_institution("nope", object(), self.response)
        self.assertEqual(404, unknown.exception.status_code)


class _BudgetSheet:
    """Stands in for the budget sheet's AuthorizedSession: each tab's cells, which a write changes, and every write made."""

    def __init__(self, status=200, body=None, **tabs):
        self.tabs = {tab: [list(row) for row in rows] for tab, rows in tabs.items()}
        self.status, self.body, self.posts, self.render = status, body, [], None

    def get(self, url, params=None, timeout=None):
        if self.status != 200:
            return _GoogleResponse(self.status, self.body)
        if not url.endswith(":batchGet"):
            return _GoogleResponse(200, {"sheets": [{"properties": {"title": tab}} for tab in self.tabs]})
        self.render = dict(params)["valueRenderOption"]
        return _GoogleResponse(200, {"valueRanges": [{"values": self.tabs[value.strip("'")]} for name, value in params if name == "ranges"]})

    def post(self, url, json=None, timeout=None):
        if self.status != 200:
            return _GoogleResponse(self.status, self.body)
        self.posts.append((url, json))
        for written in json["data"]:
            tab, cell = written["range"].split("!")
            rows, row, column = self.tabs[tab.strip("'")], int(cell[1:]) - 1, ord(cell[0]) - ord("A")
            while len(rows) <= row:
                rows.append([])
            rows[row] += [""] * (column + 1 - len(rows[row]))
            rows[row][column] = written["values"][0][0]
        return _GoogleResponse(200, {})

    def cell(self, a1, tab="Home"):
        return self.tabs[tab][int(a1[1:]) - 1][ord(a1[0]) - ord("A")]


class _SheetFillSheet(_ScratchDb):
    """A budget sheet with a linked bank and a sheet fill's lines, for the sheet fill and the balances typed in the app."""

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "feed_google", "require_finance_user", "FINANCE_FEED_SHEET_ID", "FINANCE_SHEET_ID",
                                "FINANCE_FILL_FILE", "PLAID_CLIENT_ID", "PLAID_SECRET", "BANK_UNLINK_WAIT_SECONDS")
    T = 1_791_000_000.0
    HOME = [
        ["Savings", "", "Alex", "Sam"],
        ["Roth IRA", "", "", 50000],
        ["Checking", "", 4000.25, ""],
        ["Total", "", "=SUM(C2:C3)", "=SUM(D2:D3)"],
        [],
        ["Debt", "Alex", "Sam"],
        ["Credit Cards", -100, 0],
    ]
    LINES = [
        {"label": "Roth IRA", "right": 3, "accounts": ["Chase Roth IRA 7788"]},
        {"label": "Checking", "right": 2, "accounts": ["Chase Total Checking 0123"]},
        {"label": "credit cards", "accounts": ["Chase Sapphire 9911"], "negative": True},
    ]

    def setUp(self):
        super().setUp()
        relay.PLAID_CLIENT_ID, relay.PLAID_SECRET, relay.FINANCE_FEED_SHEET_ID, relay.FINANCE_SHEET_ID = "client", "secret", "", "budget123"
        relay.FINANCE_FILL_FILE = os.path.join(self._dir.name, "sheet-fill.json")
        relay.require_finance_user = lambda request: "alex"
        relay.bank_sync_in_background = lambda: None
        self.response = types.SimpleNamespace(headers={})
        self.sheet = _BudgetSheet(Home=self.HOME)
        relay.feed_google = lambda: self.sheet
        relay.plaid_save_item("item1", "access-item1", {"institution_id": "ins_3", "name": "Chase"}, "alex", self.T)
        self.read()
        self.lines(self.LINES)

    def read(self, **balances):
        """Plaid's answer for the three accounts, with any of their balances changed."""
        accounts = [{**a, "balances": {**a["balances"], "current": balances.get(a["account_id"], a["balances"]["current"])}} for a in BankSyncTest.ACCOUNTS]
        relay.plaid_post = _FakePlaid(item_get={"item": {"products": ["transactions"]}}, accounts_get={"accounts": accounts})
        relay.plaid_sync_item("item1", "access-item1", self.T)

    def lines(self, lines, tab="Home"):
        with open(relay.FINANCE_FILL_FILE, "w") as f:
            json.dump({"tab": tab, "lines": lines}, f)

    def statuses(self):
        return {line["label"]: line["status"] for line in relay.fill_status()["lines"]}

    def writes(self):
        return relay.fill_status()["writes"]


class SheetFillTest(_SheetFillSheet):
    """The sheet fill: which of the budget sheet's cells are written, what is kept of each write, and undoing one."""

    def test_each_line_s_sum_goes_into_the_cell_beside_its_label_and_what_changed_is_kept(self):
        relay.fill_sheet(self.T)
        self.assertEqual((51000.0, 4200.5, -812.4), (self.sheet.cell("D2"), self.sheet.cell("C3"), self.sheet.cell("B7")))
        self.assertEqual("FORMULA", self.sheet.render, "a formula must be told from the number it comes to")
        (url, written), = self.sheet.posts
        self.assertTrue(url.endswith("/budget123/values:batchUpdate"))
        self.assertEqual("RAW", written["valueInputOption"])
        self.assertEqual(["'Home'!D2", "'Home'!C3", "'Home'!B7"], [d["range"] for d in written["data"]])
        write, = self.writes()
        self.assertEqual((self.T, None), (write["at"], write["undone_at"]))
        self.assertEqual([("Roth IRA", "D2", 50000.0, 51000.0), ("Checking", "C3", 4000.25, 4200.5), ("credit cards", "B7", -100.0, -812.4)],
                         [(c["label"], c["cell"], c["before"], c["after"]) for c in write["changes"]])
        self.assertEqual({"Roth IRA": "written", "Checking": "written", "credit cards": "written"}, self.statuses())

    def test_nothing_is_written_or_kept_when_the_numbers_are_already_there(self):
        relay.fill_sheet(self.T)
        relay.fill_sheet(self.T + 60)
        self.assertEqual(1, len(self.sheet.posts))
        self.assertEqual(1, len(self.writes()))
        self.assertEqual({"same"}, set(self.statuses().values()))
        # Only the balance that moved is written the next day.
        self.read(chk=4300.0)
        relay.fill_sheet(self.T + 86400)
        self.assertEqual(["'Home'!C3"], [d["range"] for d in self.sheet.posts[-1][1]["data"]])
        self.assertEqual([("Checking", 4200.5, 4300.0)], [(c["label"], c["before"], c["after"]) for c in self.writes()[0]["changes"]])

    def test_a_row_inserted_above_takes_the_write_with_its_label(self):
        self.sheet.tabs["Home"].insert(1, ["Brokerage", "", 1, 2])
        relay.fill_sheet(self.T)
        self.assertEqual(51000.0, self.sheet.cell("D3"))
        self.assertEqual(2, self.sheet.cell("D2"), "the row that took its place is left alone")

    def test_a_cell_that_is_not_a_typed_number_is_left_alone(self):
        self.lines([{"label": "Total", "right": 2, "accounts": ["Chase Total Checking 0123"]},
                    {"label": "Savings", "right": 2, "accounts": ["Chase Total Checking 0123"]}])
        relay.fill_sheet(self.T)
        self.assertEqual([], self.sheet.posts)
        self.assertEqual({"Total": "not_a_number", "Savings": "not_a_number"}, self.statuses())
        self.assertEqual("=SUM(C2:C3)", self.sheet.cell("C4"))

    def test_an_empty_cell_is_filled_and_undoing_empties_it_again(self):
        self.lines([{"label": "Roth IRA", "right": 2, "accounts": ["Chase Roth IRA 7788"]}])
        relay.fill_sheet(self.T)
        self.assertEqual(51000.0, self.sheet.cell("C2"))
        self.assertIsNone(self.writes()[0]["changes"][0]["before"])
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 5)
        self.assertEqual("", self.sheet.cell("C2"))

    def test_a_label_that_is_gone_or_there_twice_is_not_guessed(self):
        self.sheet.tabs["Home"].append(["Checking", "", 1])
        self.lines([{"label": "Checking", "right": 2, "accounts": ["Chase Total Checking 0123"]},
                    {"label": "Brokerage", "right": 2, "accounts": ["Chase Roth IRA 7788"]},
                    {"label": "Roth IRA", "tab": "Elsewhere", "right": 3, "accounts": ["Chase Roth IRA 7788"]}])
        relay.fill_sheet(self.T)
        self.assertEqual([], self.sheet.posts)
        self.assertEqual(["ambiguous", "no_label", "no_tab"], [line["status"] for line in relay.fill_status()["lines"]])

    def test_a_line_with_an_account_that_is_not_linked_is_not_written_as_the_sum_of_the_rest(self):
        self.lines([{"label": "Checking", "right": 2, "accounts": ["Chase Total Checking 0123", "Ally Savings 4455"]}])
        relay.fill_sheet(self.T)
        self.assertEqual([], self.sheet.posts)
        line, = relay.fill_status()["lines"]
        self.assertEqual(("no_account", ["Ally Savings 4455"]), (line["status"], line["missing"]))

    def test_several_accounts_in_one_line_add_up(self):
        self.lines([{"label": "Checking", "right": 2, "accounts": ["Chase Total Checking 0123", "Chase Roth IRA 7788"]}])
        relay.fill_sheet(self.T)
        self.assertEqual(55200.5, self.sheet.cell("C3"))

    def test_undoing_a_write_puts_the_numbers_back_and_pauses_its_lines(self):
        relay.fill_sheet(self.T)
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 30)
        self.assertEqual((50000.0, 4000.25, -100.0), (self.sheet.cell("D2"), self.sheet.cell("C3"), self.sheet.cell("B7")))
        write, = self.writes()
        self.assertEqual((self.T + 30, "alex", [True, True, True]), (write["undone_at"], write["undone_by"], [c["restored"] for c in write["changes"]]))
        # The next sync would otherwise write them straight back.
        relay.fill_sheet(self.T + 86400)
        self.assertEqual(2, len(self.sheet.posts), "the fill and its undo, nothing since")
        self.assertEqual({"paused"}, set(self.statuses().values()))
        with self.assertRaises(relay.HTTPException) as again:
            relay.fill_undo(write["id"], "alex", self.T + 60)
        self.assertEqual((409, "already_undone"), (again.exception.status_code, again.exception.detail["error"]))
        with self.assertRaises(relay.HTTPException) as unknown:
            relay.fill_undo(999, "alex", self.T + 60)
        self.assertEqual(404, unknown.exception.status_code)

    def test_an_undo_leaves_a_cell_someone_has_typed_in_since(self):
        relay.fill_sheet(self.T)
        self.sheet.tabs["Home"][2][2] = 9999
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 30)
        self.assertEqual(9999, self.sheet.cell("C3"))
        self.assertEqual(50000.0, self.sheet.cell("D2"))
        self.assertEqual([True, False, True], [c["restored"] for c in self.writes()[0]["changes"]])

    def test_an_undo_follows_a_cell_whose_row_moved(self):
        relay.fill_sheet(self.T)
        self.sheet.tabs["Home"].insert(1, ["Brokerage", "", 1, 2])
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 30)
        self.assertEqual((50000.0, 4000.25, -100.0), (self.sheet.cell("D3"), self.sheet.cell("C4"), self.sheet.cell("B8")))
        self.assertEqual([True, True, True], [c["restored"] for c in self.writes()[0]["changes"]])

    def test_an_undo_never_goes_by_where_a_cell_was(self):
        relay.fill_sheet(self.T)
        # The label is renamed, and a row put in above it leaves another cell at the old address
        # (C3), one that happens to hold the very number that was written.
        rows = self.sheet.tabs["Home"]
        rows[2][0] = "Everyday account"
        rows.insert(2, ["Brokerage", "", 4200.5, 7])
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 30)
        self.assertEqual(4200.5, self.sheet.cell("C3"), "the stranger at the old address is untouched")
        self.assertEqual(4200.5, self.sheet.cell("C4"), "its label is gone, so it stays as written")
        self.assertEqual((50000.0, -100.0), (self.sheet.cell("D2"), self.sheet.cell("B8")))
        self.assertEqual([True, False, True], [c["restored"] for c in self.writes()[0]["changes"]])

    def test_two_lines_that_come_to_the_same_cell_are_both_left_alone(self):
        # "Debt" and "Owed" sit side by side: two columns right of one is one right of the other.
        self.sheet.tabs["Home"][5] = ["Debt", "Owed", 5, "Sam"]
        self.lines([{"label": "Debt", "right": 2, "accounts": ["Chase Total Checking 0123"]},
                    {"label": "Owed", "right": 1, "accounts": ["Chase Roth IRA 7788"]},
                    {"label": "Roth IRA", "right": 3, "accounts": ["Chase Roth IRA 7788"]}])
        relay.fill_sheet(self.T)
        self.assertEqual(5, self.sheet.cell("C6"))
        self.assertEqual({"Debt": "same_cell", "Owed": "same_cell", "Roth IRA": "written"}, self.statuses())
        self.assertEqual(["Roth IRA"], [c["label"] for c in self.writes()[0]["changes"]])

    def test_a_resumed_line_is_written_at_once_and_a_paused_one_is_not(self):
        relay.fill_sheet(self.T)
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 30)
        ids = {line["label"]: line["id"] for line in relay.fill_status()["lines"]}
        body = relay.post_bank_fill_line(types.SimpleNamespace(line=ids["Checking"], paused=False), object(), self.response)
        self.assertEqual("private, no-store", self.response.headers["Cache-Control"])
        self.assertEqual((4200.5, 50000.0), (self.sheet.cell("C3"), self.sheet.cell("D2")))
        self.assertEqual({"Roth IRA": "paused", "Checking": "written", "credit cards": "paused"}, {line["label"]: line["status"] for line in body["fill"]["lines"]})
        self.assertEqual(2, len(body["fill"]["writes"]))
        relay.post_bank_fill_line(types.SimpleNamespace(line=ids["Checking"], paused=True), object(), self.response)
        self.read(chk=1.0)
        relay.fill_sheet(self.T + 86400)
        self.assertEqual(4200.5, self.sheet.cell("C3"))
        with self.assertRaises(relay.HTTPException) as unknown:
            relay.post_bank_fill_line(types.SimpleNamespace(line="Home|nothing|1", paused=True), object(), self.response)
        self.assertEqual(404, unknown.exception.status_code)

    def test_the_undo_route_answers_the_page_and_waits_its_turn(self):
        relay.fill_sheet(self.T)
        body = relay.post_bank_fill_undo(self.writes()[0]["id"], object(), self.response)
        self.assertEqual("alex", body["fill"]["writes"][0]["undone_by"])
        self.assertFalse(relay._bank_lock.locked())
        relay.BANK_UNLINK_WAIT_SECONDS = 0.01
        relay._bank_lock.acquire()
        try:
            with self.assertRaises(relay.HTTPException) as busy:
                relay.post_bank_fill_undo(1, object(), self.response)
            self.assertEqual((503, "busy"), (busy.exception.status_code, busy.exception.detail["error"]))
        finally:
            relay._bank_lock.release()

    def test_a_file_with_a_line_that_cannot_be_understood_is_refused_whole(self):
        for bad in ({"label": "Checking", "accounts": []}, {"accounts": ["a"]}, {"label": "Checking", "accounts": ["a"], "right": 0},
                    {"label": "Checking", "accounts": ["a"], "right": True}, "Checking"):
            self.lines([self.LINES[0], bad])
            relay.fill_sheet(self.T)
            fill = relay.fill_status()
            self.assertEqual(("bad_file", [], []), (fill["error"], fill["lines"], self.sheet.posts), bad)
            # What is wrong goes to the app as a reason and a line number, for it to say in its own words.
            self.assertEqual(("bad_line", 2), (fill["reason"], fill["line"]))
            self.assertIn("Line 2", fill["message"])
        self.lines([self.LINES[1], {**self.LINES[1], "label": " CHECKING "}])
        self.assertEqual(("same_line", 2), (relay.fill_lines()[1]["reason"], relay.fill_lines()[1]["line"]))
        self.lines([{"label": "Checking", "accounts": ["a"]}], tab=None)
        self.assertEqual(("bad_line", 1), (relay.fill_lines()[1]["reason"], relay.fill_lines()[1]["line"]))
        with open(relay.FINANCE_FILL_FILE, "w") as f:
            json.dump({"cells": []}, f)
        self.assertEqual("no_lines", relay.fill_lines()[1]["reason"])
        with open(relay.FINANCE_FILL_FILE, "w") as f:
            f.write("{not json")
        fill = relay.fill_status()
        self.assertEqual(("bad_file", "unreadable", None), (fill["error"], fill["reason"], fill["line"]))

    def test_without_the_file_there_is_no_fill(self):
        os.remove(relay.FINANCE_FILL_FILE)
        relay.feed_google = lambda: self.fail("nothing lists a cell to write")
        relay.fill_sheet(self.T)
        fill = relay.fill_status()
        self.assertEqual((False, None, []), (fill["configured"], fill["error"], fill["lines"]))

    def test_a_budget_sheet_not_shared_for_editing_is_kept_as_the_fill_s_problem(self):
        self.sheet = _BudgetSheet(status=403, body={"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}})
        relay.fill_sheet(self.T)
        fill = relay.bank_status(self.T)["fill"]
        self.assertEqual((True, "not_shared", []), (fill["configured"], fill["error"], fill["writes"]))
        self.assertEqual({"waiting"}, {line["status"] for line in fill["lines"]})

    def test_a_sync_of_everything_fills_the_sheet_and_freshens_the_app_s_next_read_of_it(self):
        saved = dict(relay._finance_cache)
        try:
            relay._finance_cache["at"] = self.T
            self.assertTrue(relay.plaid_sync_all(now=self.T + 10))
            self.assertEqual(0.0, relay._finance_cache["at"])
        finally:
            relay._finance_cache.update(saved)
        self.assertEqual(51000.0, self.sheet.cell("D2"))
        self.assertEqual(self.T + 10, relay.bank_status(self.T + 10)["fill"]["checked_at"])

    def test_only_the_newest_writes_are_kept(self):
        saved, relay.FILL_WRITES_KEPT = relay.FILL_WRITES_KEPT, 3
        try:
            for day in range(5):
                self.read(chk=100.0 + day)
                relay.fill_sheet(self.T + day)
        finally:
            relay.FILL_WRITES_KEPT = saved
        self.assertEqual([104.0, 103.0, 102.0], [w["changes"][-1]["after"] for w in self.writes()])


class TypedBalanceTest(_SheetFillSheet):
    """Balances typed in the app: which cell is written, what is refused, and how old each balance is."""

    def setUp(self):
        super().setUp()
        self.saved_cache = dict(relay._finance_cache)
        # None of the balances is filled in by the bank, unless a test says so.
        self.lines([])

    def tearDown(self):
        relay._finance_cache.clear()
        relay._finance_cache.update(self.saved_cache)
        super().tearDown()

    def typed(self, label="Roth IRA", right=3, value=52000.0, expected=50000.0, tab="Home", now=None):
        body = types.SimpleNamespace(tab=tab, label=label, right=right, expected=expected, value=value)
        return relay.typed_write(body, "alex", self.T if now is None else now)

    def refused(self, **kwargs):
        with self.assertRaises(relay.HTTPException) as caught:
            self.typed(**kwargs)
        self.assertEqual([], self.sheet.posts, "nothing is written when the balance is refused")
        return caught.exception.status_code, caught.exception.detail["error"]

    def ages(self):
        return {u["id"]: (u["value"], u["at"], u["by"], u["source"]) for u in relay.typed_updates()}

    def test_the_balance_goes_into_the_cell_beside_its_label_and_is_kept_with_its_age(self):
        relay._finance_cache["at"] = self.T
        answer = self.typed()
        self.assertEqual(52000.0, self.sheet.cell("D2"))
        self.assertEqual(("D2", 50000.0, 52000.0, True), (answer["cell"], answer["before"], answer["value"], answer["written"]))
        self.assertEqual("RAW", self.sheet.posts[0][1]["valueInputOption"])
        self.assertEqual({"Home|roth ira|3": (52000.0, self.T, "alex", "app")}, self.ages())
        self.assertEqual(0.0, relay._finance_cache["at"], "the app's next read gets the new number")
        write, = self.writes()
        self.assertEqual([("Roth IRA", "D2", 50000.0, 52000.0, "alex")], [(c["label"], c["cell"], c["before"], c["after"], c["typed_by"]) for c in write["changes"]])

    def test_the_same_number_writes_nothing_but_says_the_balance_is_still_right(self):
        self.typed(value=50000.001, now=self.T + 60)
        self.assertEqual([], self.sheet.posts)
        self.assertEqual([], self.writes())
        self.assertEqual({"Home|roth ira|3": (50000.0, self.T + 60, "alex", "app")}, self.ages())

    def test_a_cell_changed_since_the_app_read_it_is_not_written_over(self):
        relay._finance_cache["at"] = self.T
        self.assertEqual((409, "changed"), self.refused(expected=49000.0))
        self.assertEqual({}, self.ages())
        self.assertEqual(0.0, relay._finance_cache["at"], "the app's read straight after gets what the cell holds now")

    def test_a_formula_words_or_a_missing_label_are_refused(self):
        self.assertEqual((409, "not_a_number"), self.refused(label="Total", right=2, expected=None))
        self.assertEqual((409, "not_a_number"), self.refused(label="Savings", right=2, expected=None))
        self.assertEqual((404, "no_label"), self.refused(label="Pension"))
        self.assertEqual((404, "no_tab"), self.refused(tab="Elsewhere"))
        self.assertEqual("=SUM(C2:C3)", self.sheet.cell("C4"))

    def test_an_empty_cell_is_typed_into(self):
        self.typed(right=2, expected=None, value=1000.0)
        self.assertEqual(1000.0, self.sheet.cell("C2"))

    def test_a_label_there_twice_is_not_guessed_between(self):
        # An undo finds the cell by its label again, and couldn't tell the two apart either.
        self.sheet.tabs["Home"].append(["Roth IRA", "", "", 7])
        self.assertEqual((409, "ambiguous"), self.refused())

    def test_a_balance_the_bank_fills_in_is_not_typed_over_unless_its_line_is_paused(self):
        self.lines(self.LINES)
        self.assertEqual((409, "bank_filled"), self.refused())
        relay.state_set(relay.FILL_PAUSED_KEY, ["Home|roth ira|3"])
        self.typed()
        self.assertEqual(52000.0, self.sheet.cell("D2"))

    def test_what_is_not_a_balance_is_refused(self):
        self.assertEqual((422, "bad_value"), self.refused(value=float("nan")))
        self.assertEqual((422, "bad_value"), self.refused(value=1e13))
        self.assertEqual((422, "bad_request"), self.refused(right=0))
        self.assertEqual((422, "bad_request"), self.refused(label="  "))

    def test_undoing_a_typed_balance_puts_back_the_number_and_its_age_and_pauses_nothing(self):
        self.typed(now=self.T)
        self.typed(value=53000.0, expected=52000.0, now=self.T + 100)
        relay.fill_undo(self.writes()[0]["id"], "alex", self.T + 200)
        self.assertEqual(52000.0, self.sheet.cell("D2"))
        self.assertEqual({"Home|roth ira|3": (52000.0, self.T, "alex", "app")}, self.ages())
        relay.fill_undo(self.writes()[1]["id"], "alex", self.T + 300)
        self.assertEqual(50000, self.sheet.cell("D2"))
        self.assertEqual({}, self.ages(), "a balance never typed before has no age again")
        self.assertEqual(set(), relay.fill_paused())

    def test_the_sheet_fill_dates_the_balances_it_writes_or_finds_already_there_by_their_banks_last_read(self):
        self.lines(self.LINES)
        relay.fill_sheet(self.T + 60)
        ages = self.ages()
        self.assertEqual((51000.0, self.T, None, "bank"), ages["Home|roth ira|3"], "as old as Plaid's read, not the write")
        self.assertEqual({"Home|roth ira|3", "Home|checking|2", "Home|credit cards|1"}, set(ages))
        # A morning Plaid couldn't read the bank: the kept balance is still there, but no younger.
        relay.fill_sheet(self.T + 86400)
        self.assertEqual(self.T, self.ages()["Home|roth ira|3"][1])

    def test_the_balances_the_sheet_fill_keeps_are_those_of_its_lines_not_paused(self):
        self.lines(self.LINES)
        relay.state_set(relay.FILL_PAUSED_KEY, ["Home|checking|2"])
        self.assertEqual([("Home", "Roth IRA", 3), ("Home", "credit cards", 1)], [(f["tab"], f["label"], f["right"]) for f in relay.typed_filled()])

    def test_a_typed_write_is_undone_on_a_relay_without_plaid(self):
        relay.PLAID_CLIENT_ID = ""
        self.typed()
        relay.post_bank_fill_undo(self.writes()[0]["id"], object(), self.response)
        self.assertEqual(50000, self.sheet.cell("D2"))

    def test_the_route_answers_with_the_cell_written(self):
        body = types.SimpleNamespace(tab="Home", label="roth  ira", right=3, expected=50000, value=50500.5)
        answer = relay.put_finance_sheet_value(body, object(), self.response)
        self.assertEqual(("D2", 50500.5, "alex"), (answer["cell"], answer["value"], answer["by"]))
        self.assertEqual("private, no-store", self.response.headers["Cache-Control"])

    def test_the_route_waits_behind_a_sync_then_says_it_is_busy(self):
        relay.BANK_UNLINK_WAIT_SECONDS = 0.05
        relay._bank_lock.acquire()
        try:
            with self.assertRaises(relay.HTTPException) as caught:
                relay.put_finance_sheet_value(types.SimpleNamespace(tab="Home", label="Roth IRA", right=3, expected=50000, value=1), object(), self.response)
            self.assertEqual("busy", caught.exception.detail["error"])
        finally:
            relay._bank_lock.release()


class StockPlanTest(_ScratchDb):
    """A stock plan account: every grant kept, the vests read from its activity, the vests worked out from them, and a sheet cell filled from one."""

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "feed_google", "FINANCE_FEED_SHEET_ID", "FINANCE_SHEET_ID", "FINANCE_FILL_FILE",
                                "PLAID_CLIENT_ID", "PLAID_SECRET")
    T = 1_791_000_000.0  # 2026-10-03
    KEY = "Acme Plans Acme Corp 0042"
    ACCOUNTS = [{"account_id": "plan", "name": "Acme Corp", "mask": "0042", "type": "investment", "subtype": "stock plan",
                 "balances": {"current": 9000.0, "available": None, "limit": None, "iso_currency_code": "USD"}}]
    # Three grants of the same share, one of them all vested and gone, and the plan's cash.
    HOLDINGS = {
        "holdings": [{"account_id": "plan", "security_id": "acme", "quantity": quantity, "vested_quantity": 0, "institution_price": 10.0,
                      "institution_price_as_of": "2026-10-02", "institution_value": quantity * 10.0, "iso_currency_code": "USD"} for quantity in (0, 250, 650)]
        + [{"account_id": "plan", "security_id": "usd", "quantity": 0, "institution_price": 1, "institution_value": 0, "iso_currency_code": "USD"}],
        "securities": [{"security_id": "acme", "ticker_symbol": "ACME", "name": "Acme Corp", "type": "equity"},
                       {"security_id": "usd", "ticker_symbol": "CUR:USD", "name": "U S Dollar", "type": "cash"}],
    }
    VESTS = [("2025-11-17", 280), ("2026-02-14", 300), ("2026-05-15", 300), ("2026-08-15", 300)]

    def setUp(self):
        super().setUp()
        relay.PLAID_CLIENT_ID, relay.PLAID_SECRET, relay.FINANCE_FEED_SHEET_ID, relay.FINANCE_SHEET_ID = "client", "secret", "", "budget123"
        relay.FINANCE_FILL_FILE = os.path.join(self._dir.name, "sheet-fill.json")
        relay.bank_sync_in_background = lambda: None
        self.T = StockPlanTest.T
        relay.plaid_save_item("item1", "access-item1", {"institution_id": "ins_9", "name": "Acme Plans"}, "alex", self.T)

    def activity(self, vests=None, sales=(("2026-08-20", -180),)):
        """Plaid's account of what happened in the plan: each vest a transfer in, each sale a sell."""
        moved = [(day, "transfer", shares, "Distribution") for day, shares in (self.VESTS if vests is None else vests)] + [(day, "sell", shares, "Sale") for day, shares in sales]
        return {"investment_transactions": [{"investment_transaction_id": f"t{n}", "account_id": "plan", "security_id": "acme", "date": day, "type": kind,
                                             "subtype": kind, "quantity": shares, "price": 10.0, "amount": -shares * 10.0, "name": name}
                                            for n, (day, kind, shares, name) in enumerate(moved)],
                "securities": self.HOLDINGS["securities"], "total_investment_transactions": len(moved)}

    def read(self, **overrides):
        answers = {"item_get": {"item": {"products": ["investments"]}}, "accounts_get": {"accounts": self.ACCOUNTS},
                   "investments_holdings_get": self.HOLDINGS, "investments_transactions_get": self.activity()}
        answers.update(overrides)
        relay.plaid_post = _FakePlaid(**answers)
        self.assertIsNone(relay.plaid_sync_item("item1", "access-item1", self.T))
        return relay.plaid_post

    def test_every_grant_of_the_same_share_is_kept(self):
        self.read()
        rows = relay.with_db(lambda c: c.execute("SELECT ticker, lot, quantity FROM plaid_holdings ORDER BY ticker, lot").fetchall())
        self.assertEqual([("ACME", 0, 0.0), ("ACME", 1, 250.0), ("ACME", 2, 650.0), ("CUR:USD", 0, 0.0)], rows)
        self.assertEqual(4, relay.bank_institutions()[0]["accounts"][0]["holdings"])
        holdings = relay.with_db(lambda c: c.execute(
            "SELECT account_id, ticker, name, quantity, price, value, cost_basis, kind, currency, price_as_of FROM plaid_holdings ORDER BY value DESC, lot").fetchall())
        fed = relay.feed_tables(relay.bank_institutions(), holdings, None)[relay.FEED_HOLDINGS_TAB]
        self.assertEqual([650.0, 250.0, 0.0, 0.0], [row[3] for row in fed[1:]], "each grant has its own row in the feed")
        # A grant that is gone the next day takes its row with it.
        self.read(investments_holdings_get={**self.HOLDINGS, "holdings": self.HOLDINGS["holdings"][2:]})
        self.assertEqual(2, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_holdings").fetchone()[0]))

    def test_holdings_kept_before_there_were_lots_move_over(self):
        relay.CONN.execute("DROP TABLE plaid_holdings")
        relay.CONN.execute("CREATE TABLE plaid_holdings (account_id TEXT, security_id TEXT, item_id TEXT, ticker TEXT, name TEXT, kind TEXT,"
                           " quantity REAL, price REAL, price_as_of TEXT, value REAL, cost_basis REAL, currency TEXT, PRIMARY KEY (account_id, security_id))")
        relay.CONN.execute("INSERT INTO plaid_holdings VALUES ('ira','s1','item1','VTI','Vanguard','etf',100,510,'2026-10-02',51000,40000,'USD')")
        relay.CONN.commit()
        relay.CONN.close()
        relay.CONN = relay.db()
        self.assertEqual([("ira", "s1", 0, "VTI", 100.0, None, 51000.0)],
                         relay.CONN.execute("SELECT account_id, security_id, lot, ticker, quantity, vested, value FROM plaid_holdings").fetchall())
        self.assertEqual([], relay.CONN.execute("SELECT name FROM sqlite_master WHERE name='plaid_holdings_before_lots'").fetchall())
        relay.CONN.close()
        relay.CONN = relay.db()
        self.assertEqual(1, relay.CONN.execute("SELECT COUNT(*) FROM plaid_holdings").fetchone()[0], "opened again, nothing moves twice")

    def test_the_plan_s_activity_is_asked_for_and_kept_by_date_and_shares(self):
        plaid = self.read()
        asked = next(body for path, body in plaid.calls if path == "/investments/transactions/get")
        self.assertEqual((["plan"], 0), (asked["options"]["account_ids"], asked["options"]["offset"]))
        self.assertEqual(("2024-10-03", "2026-10-03"), (asked["start_date"], asked["end_date"]))
        kept = relay.with_db(lambda c: c.execute("SELECT date, kind, ticker, quantity FROM plaid_stock_events ORDER BY date").fetchall())
        self.assertEqual(("2025-11-17", "transfer", "ACME", 280.0), kept[0])
        self.assertEqual(("2026-08-20", "sell", "ACME", -180.0), kept[-1])
        # Plaid failing at this the next day keeps what was read, and the balances are still a good read.
        self.read(investments_transactions_get=relay.PlaidError("PRODUCT_NOT_READY"))
        self.assertEqual(5, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_stock_events").fetchone()[0]))
        self.assertIsNone(relay.bank_institutions()[0]["error"])

    def test_an_account_that_is_not_a_stock_plan_is_never_asked_for_its_activity(self):
        plaid = self.read(accounts_get={"accounts": [{**self.ACCOUNTS[0], "subtype": "brokerage"}]})
        self.assertNotIn("/investments/transactions/get", plaid.paths())
        self.assertEqual([], relay.stock_plans())

    def test_activity_longer_than_a_page_is_read_to_its_end(self):
        saved, relay.STOCK_EVENTS_PAGE = relay.STOCK_EVENTS_PAGE, 2
        try:
            everything = self.activity()["investment_transactions"]
            answers = {"item_get": {"item": {"products": ["investments"]}}, "accounts_get": {"accounts": self.ACCOUNTS}, "investments_holdings_get": self.HOLDINGS}
            fake = _FakePlaid(**answers)

            def post(path, body):
                if path != "/investments/transactions/get":
                    return fake(path, body)
                offset = body["options"]["offset"]
                return {"investment_transactions": everything[offset:offset + 2], "securities": self.HOLDINGS["securities"], "total_investment_transactions": len(everything)}

            relay.plaid_post = post
            relay.plaid_sync_item("item1", "access-item1", self.T)
        finally:
            relay.STOCK_EVENTS_PAGE = saved
        self.assertEqual(5, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_stock_events").fetchone()[0]))

    def test_the_vests_to_come_follow_the_pace_and_size_of_those_read(self):
        self.read()
        plan, = relay.stock_plans()
        self.assertEqual((self.KEY, "ACME", 900.0, 10.0, 3, 300.0), (plan["account"], plan["ticker"], plan["unvested"], plan["price"], plan["every_months"], plan["per_vest"]))
        self.assertEqual([("2026-11-15", 300.0, 3000.0), ("2027-02-15", 300.0, 3000.0), ("2027-05-15", 300.0, 3000.0)],
                         [(v["date"], v["shares"], v["value"]) for v in plan["upcoming"]])
        self.assertEqual("2026-08-15", plan["vests"][-1]["date"])

    def test_what_is_left_over_comes_with_the_last_vest_and_a_real_part_vest_stands_alone(self):
        events = [(day, "transfer", "ACME", float(shares)) for day, shares in self.VESTS]
        outlook = relay.stock_plan_outlook(events, [("ACME", 620.0, 0.0, 10.0)])
        self.assertEqual([300.0, 320.0], [v["shares"] for v in outlook["upcoming"]], "20 left over is no vest of its own")
        outlook = relay.stock_plan_outlook(events, [("ACME", 750.0, 0.0, 10.0)])
        self.assertEqual([300.0, 300.0, 150.0], [v["shares"] for v in outlook["upcoming"]])
        # Shares the person already owns in the plan are not waiting to vest.
        outlook = relay.stock_plan_outlook(events, [("ACME", 700.0, 400.0, 10.0)])
        self.assertEqual((300.0, [300.0]), (outlook["unvested"], [v["shares"] for v in outlook["upcoming"]]))

    def test_the_pace_takes_two_vests_and_a_plan_nothing_vested_in_has_no_outlook(self):
        self.assertIsNone(relay.stock_plan_outlook([("2026-08-20", "sell", "ACME", -180.0)], [("ACME", 900.0, 0.0, 10.0)]))
        one = relay.stock_plan_outlook([("2026-08-15", "transfer", "ACME", 300.0)], [("ACME", 900.0, 0.0, 10.0)])
        self.assertEqual((None, []), (one["every_months"], one["upcoming"]))
        # A vest at the end of the month stays in its month where the next one is shorter.
        monthly = relay.stock_plan_outlook([("2026-12-31", "transfer", "ACME", 100.0), ("2027-01-31", "transfer", "ACME", 100.0)], [("ACME", 200.0, 0.0, None)])
        self.assertEqual([("2027-02-28", None), ("2027-03-31", None)], [(v["date"], v["value"]) for v in monthly["upcoming"]])

    def test_every_vest_is_worked_out_and_only_the_page_is_cut_short(self):
        # Four years of a new grant, a quarter at a time: sixteen vests, more than the page shows.
        self.read(investments_holdings_get={**self.HOLDINGS, "holdings": [{**self.HOLDINGS["holdings"][2], "quantity": 4800}]})
        whole, = relay.stock_plans()
        self.assertEqual((16, "2030-08-15", 4), (len(whole["upcoming"]), whole["upcoming"][-1]["date"], len(whole["vests"])))
        page, = relay.bank_status(self.T)["stock_plans"]
        self.assertEqual(relay.VESTS_AHEAD, len(page["upcoming"]))
        # A line for the last of them is still that vest, not one the plan hasn't got.
        sheet, lines = self.fill([{"label": "Last", "right": 1, "vest": {"account": self.KEY, "on": "2030-08-15"}}], [["Last", 1]])
        self.assertEqual(("written", 3000.0), (lines["Last"]["status"], sheet.cell("B1")))

    def test_a_line_means_the_vest_nearest_its_day(self):
        monthly = {"vests": [{"date": "2026-11-30", "shares": 100.0}, {"date": "2026-12-31", "shares": 100.0}],
                   "upcoming": [{"date": "2027-01-31", "shares": 100.0, "value": 1000.0}, {"date": "2027-02-28", "shares": 120.0, "value": 1200.0}]}
        # Both of a monthly plan's next vests are within reach of the middle of February; the nearer is meant.
        self.assertEqual(("upcoming", "2027-02-28"), (lambda found: (found[0], found[1]["date"]))(relay.fill_vest(monthly, "2027-02-15")))
        self.assertEqual(("upcoming", "2027-01-31"), (lambda found: (found[0], found[1]["date"]))(relay.fill_vest(monthly, "2027-02-10")))
        # A vest already read is the one meant when it is the nearer, though one to come is in reach too.
        self.assertEqual(("vested", None), relay.fill_vest(monthly, "2027-01-10"))
        self.assertEqual("upcoming", relay.fill_vest(monthly, "2027-01-20")[0])
        self.assertEqual(("no_vest", None), relay.fill_vest(monthly, "2027-06-15"))
        self.assertEqual(("no_vest", None), relay.fill_vest(None, "2027-02-15"))

    def test_a_vest_s_worth_needs_the_share_s_price_and_its_share_count_does_not(self):
        priceless = [{**h, "institution_price": None} for h in self.HOLDINGS["holdings"]]
        self.read(investments_holdings_get={**self.HOLDINGS, "holdings": priceless})
        sheet, lines = self.fill(
            [{"label": "Worth", "right": 1, "vest": {"account": self.KEY, "on": "2026-11-15"}},
             {"label": "Shares", "right": 1, "vest": {"account": self.KEY, "on": "2026-11-15"}, "as": "shares"}],
            [["Worth", 1], ["Shares", 1]])
        self.assertEqual({"Worth": "no_price", "Shares": "written"}, {label: line["status"] for label, line in lines.items()})
        self.assertEqual((1, 300.0), (sheet.cell("B1"), sheet.cell("B2")))

    def fill(self, lines, home):
        with open(relay.FINANCE_FILL_FILE, "w") as f:
            json.dump({"tab": "Home", "lines": lines}, f)
        sheet = _BudgetSheet(Home=home)
        relay.feed_google = lambda: sheet
        relay.fill_sheet(self.T)
        return sheet, {line["label"]: line for line in relay.fill_status()["lines"]}

    def test_a_line_that_is_a_vest_fills_its_cell_with_what_the_vest_is_worth(self):
        self.read()
        sheet, lines = self.fill(
            [{"label": "Nov 15th", "right": 2, "vest": {"account": self.KEY, "on": "2026-11-15"}},
             # Typed from memory a fortnight off, and as a number of shares.
             {"label": "Feb 28th", "right": 3, "vest": {"account": self.KEY, "on": "2027-02-28"}, "as": "shares"},
             {"label": "Aug 15th", "right": 2, "vest": {"account": self.KEY, "on": "2026-08-15"}},
             {"label": "Aug 2029", "right": 2, "vest": {"account": self.KEY, "on": "2029-08-15"}},
             {"label": "Other plan", "right": 2, "vest": {"account": "Nobody's Plan 1", "on": "2026-11-15"}}],
            [["Nov 15th", "RSU", 2500, ""], ["Feb 28th", "RSU", 2500, ""], ["Aug 15th", "RSU", 2900, ""], ["Aug 2029", "RSU", 1, ""], ["Other plan", "RSU", 1, ""]])
        self.assertEqual((3000.0, 300.0), (sheet.cell("C1"), sheet.cell("D2")))
        self.assertEqual({"Nov 15th": "written", "Feb 28th": "written", "Aug 15th": "vested", "Aug 2029": "no_vest", "Other plan": "no_account"},
                         {label: line["status"] for label, line in lines.items()})
        self.assertEqual((2900, 1), (sheet.cell("C3"), sheet.cell("C4")), "a vest that has happened keeps its last estimate")
        self.assertEqual(("2026-11-15", [self.KEY]), (lines["Nov 15th"]["vest_on"], lines["Nov 15th"]["accounts"]))
        self.assertEqual([("Nov 15th", 2500.0, 3000.0), ("Feb 28th", None, 300.0)], [(c["label"], c["before"], c["after"]) for c in relay.fill_status()["writes"][0]["changes"]])

    def test_a_vest_line_that_is_not_a_plan_and_a_day_is_refused_with_the_file(self):
        for bad in ({"label": "Nov", "vest": {"account": self.KEY}}, {"label": "Nov", "vest": {"account": self.KEY, "on": "Nov 15th"}},
                    {"label": "Nov", "vest": {"on": "2026-11-15"}}, {"label": "Nov", "vest": {"account": self.KEY, "on": "2026-11-15"}, "as": "dollars"},
                    {"label": "Nov", "vest": {"account": self.KEY, "on": "2026-11-15"}, "accounts": ["Chase Checking 1"]},
                    {"label": "Nov", "accounts": ["Chase Checking 1"], "as": "shares"}):
            with open(relay.FINANCE_FILL_FILE, "w") as f:
                json.dump({"tab": "Home", "lines": [bad]}, f)
            self.assertEqual("bad_line", (relay.fill_lines()[1] or {}).get("reason"), bad)

    def test_the_page_is_told_the_plan_s_outlook_and_unlinking_forgets_its_activity(self):
        self.read()
        plan, = relay.bank_status(self.T)["stock_plans"]
        self.assertEqual(("Acme Plans", "Acme Corp", 3), (plan["institution"], plan["name"], len(plan["upcoming"])))
        relay.plaid_post = _FakePlaid()
        relay.require_finance_user, saved = (lambda request: "alex"), relay.require_finance_user
        try:
            relay.delete_bank_institution("item1", object(), types.SimpleNamespace(headers={}))
        finally:
            relay.require_finance_user = saved
        self.assertEqual(0, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_stock_events").fetchone()[0]))
        self.assertEqual([], relay.bank_status(self.T)["stock_plans"])


class _PagedPlaid:
    """Stands in for `plaid_post` where the same path is asked more than once: each path's answers in turn, the last one repeating."""

    def __init__(self, **answers):
        self.answers, self.calls = {path: list(pages) for path, pages in answers.items()}, []

    def __call__(self, path, body):
        self.calls.append((path, body))
        pages = self.answers.get(path.strip("/").replace("/", "_")) or [{}]
        answer = pages.pop(0) if len(pages) > 1 else pages[0]
        if isinstance(answer, Exception):
            raise answer
        return answer

    def asked(self, path):
        return [body for asked, body in self.calls if asked == path]


def _txn(txn_id, amount, date="2026-10-05", account="venture", name="COSTCO WHSE #0123", merchant="Costco", category="GENERAL_MERCHANDISE", detailed=None, **more):
    return {"transaction_id": txn_id, "account_id": account, "amount": amount, "date": date, "authorized_date": date, "name": name, "merchant_name": merchant,
            "iso_currency_code": "USD", "pending": False, "personal_finance_category": {"primary": category, "detailed": detailed or f"{category}_OTHER"}, **more}


class _Budget(_ScratchDb):
    """A scratch relay.db with two cards linked for their transactions: a Venture two people carry, and the family's Platinum."""

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "budget_sync_in_background", "budget_check_in_background", "require_finance_user",
                                "write_bank_feed", "PLAID_CLIENT_ID", "PLAID_SECRET", "FINANCE_FEED_SHEET_ID", "FINANCE_USERS", "send_push")
    # Noon on 7 October 2026, with no phone to say where: the household's clock is UTC.
    NOW = 1_791_374_400.0
    VENTURE, PLATINUM = "Capital One Venture 1234", "American Express Platinum Card 1009"
    CARDS = {
        "cap": ("Capital One", [{"account_id": "venture", "name": "Venture", "mask": "1234", "type": "credit", "subtype": "credit card", "balances": {"current": 812.4, "limit": 10000}},
                                {"account_id": "checking", "name": "360 Checking", "mask": "5555", "type": "depository", "subtype": "checking", "balances": {"current": 4000.0}}]),
        "amex": ("American Express", [{"account_id": "platinum", "name": "Platinum Card", "mask": "1009", "type": "credit", "subtype": "credit card", "balances": {"current": 2100.0}}]),
    }

    def setUp(self):
        super().setUp()
        relay.PLAID_CLIENT_ID, relay.PLAID_SECRET, relay.FINANCE_FEED_SHEET_ID, relay.FINANCE_USERS = "client", "secret", "", {"sam"}
        relay.require_finance_user = lambda request: "alex"
        self.background = []
        relay.bank_sync_in_background = lambda: self.background.append("bank")
        relay.budget_sync_in_background = lambda: self.background.append("budget")
        relay.budget_check_in_background = lambda: self.background.append("check")
        relay.write_bank_feed = lambda now: None
        relay._txn_asked["at"] = 0.0
        self.response = types.SimpleNamespace(headers={})
        for item_id in self.CARDS:
            self.link(item_id)
        relay.budget_config_save({"people": ["Andrew", "Sarah"], "roles": {self.VENTURE: "split", self.PLATINUM: "family"}}, self.NOW)

    def link(self, item_id, products=("transactions",), source=None):
        name, accounts = self.CARDS[source or item_id]
        relay.plaid_save_item(item_id, f"access-{item_id}", {"institution_id": "ins", "name": name}, "alex", self.NOW - 86400)
        relay.plaid_store(item_id, [dict(a, account_id=a["account_id"] if source is None else f"{a['account_id']}-again") for a in accounts], {}, [], {}, self.NOW)
        relay.with_db(lambda c: (c.execute("UPDATE plaid_items SET products=? WHERE item_id=?", (json.dumps(list(products)), item_id)), c.commit()))

    def store(self, *added, item="cap", modified=(), removed=(), cursor="c1", status="HISTORICAL_UPDATE_COMPLETE", now=None):
        return relay.plaid_transactions_store(item, {"added": list(added), "modified": list(modified), "removed": list(removed), "cursor": cursor, "status": status},
                                              self.NOW if now is None else now)

    def month(self, month=None):
        return relay.budget_month(month, self.NOW)

    def bucket(self, bucket_id, month=None):
        return next(b for b in self.month(month)["buckets"] if b["id"] == bucket_id)

    def phone(self, device_id, user="alex", role="admin", token="yes", **columns):
        values = {"device_id": device_id, "token": f"token-{device_id}" if token == "yes" else token, "platform": "android", "user": user, "role": role, **columns}
        relay.with_db(lambda c: (c.execute(f"INSERT INTO devices ({', '.join(values)}) VALUES ({', '.join('?' * len(values))})", tuple(values.values())), c.commit()))


class BudgetSyncTest(_Budget):
    """The cards' transactions: reading Plaid's pages, what is kept of them, and what a purchase is."""

    def test_every_page_is_read_and_the_cursor_moves_only_when_they_all_were(self):
        plaid = relay.plaid_post = _PagedPlaid(transactions_sync=[
            {"added": [_txn("t1", 54.2)], "next_cursor": "page-2", "has_more": True},
            {"added": [_txn("t2", 12.0)], "modified": [_txn("t0", 9.0)], "removed": [{"transaction_id": "gone"}], "next_cursor": "end", "has_more": False,
             "transactions_update_status": "HISTORICAL_UPDATE_COMPLETE"},
        ])
        pulled = relay.plaid_transactions_pull("access-cap", "start")
        self.assertEqual(["start", "page-2"], [body["cursor"] for body in plaid.asked("/transactions/sync")])
        self.assertEqual((["t1", "t2"], ["t0"], ["gone"], "end", "HISTORICAL_UPDATE_COMPLETE"),
                         ([t["transaction_id"] for t in pulled["added"]], [t["transaction_id"] for t in pulled["modified"]], pulled["removed"], pulled["cursor"], pulled["status"]))

    def test_the_first_ask_carries_no_cursor(self):
        plaid = relay.plaid_post = _PagedPlaid(transactions_sync=[{"next_cursor": "c1", "has_more": False}])
        relay.plaid_transactions_pull("access-cap", None)
        self.assertNotIn("cursor", plaid.asked("/transactions/sync")[0])

    def test_transactions_changing_between_pages_start_the_read_over_from_where_it_began(self):
        plaid = relay.plaid_post = _PagedPlaid(transactions_sync=[
            {"added": [_txn("stale", 1.0)], "next_cursor": "page-2", "has_more": True},
            relay.PlaidError("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"),
            {"added": [_txn("t1", 54.2)], "next_cursor": "end", "has_more": False},
        ])
        pulled = relay.plaid_transactions_pull("access-cap", "start")
        self.assertEqual(["start", "page-2", "start"], [body["cursor"] for body in plaid.asked("/transactions/sync")])
        self.assertEqual(["t1"], [t["transaction_id"] for t in pulled["added"]])

    def test_transactions_that_never_settle_are_given_up_on(self):
        relay.plaid_post = _PagedPlaid(transactions_sync=[relay.PlaidError("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION")])
        with self.assertRaises(relay.PlaidError):
            relay.plaid_transactions_pull("access-cap", "start")

    def test_only_the_cards_purchases_are_kept(self):
        self.store(_txn("t1", 54.2), _txn("paycheck", -3000.0, account="checking", name="ACME PAYROLL"), {"transaction_id": "broken"})
        self.assertEqual(["t1"], [row[0] for row in relay.with_db(lambda c: c.execute("SELECT txn_id FROM plaid_transactions").fetchall())])

    def test_an_institution_is_asked_only_if_it_was_linked_for_its_transactions_and_its_accounts_are_known(self):
        self.link("amex", products=("liabilities",))
        relay.plaid_save_item("new", "access-new", {"name": "Chase"}, "alex", self.NOW)
        relay.with_db(lambda c: (c.execute("UPDATE plaid_items SET products='[\"transactions\"]' WHERE item_id='new'"), c.commit()))
        plaid = relay.plaid_post = _PagedPlaid(transactions_sync=[{"added": [_txn("t1", 54.2)], "next_cursor": "c1", "has_more": False}])
        relay.plaid_transactions_sync(self.NOW)
        self.assertEqual(["access-cap"], [body["access_token"] for body in plaid.asked("/transactions/sync")])
        self.assertEqual({"at": self.NOW, "institutions": 1, "failed": 0}, relay.state_get(relay.PLAID_TXN_SYNCED_KEY))
        self.assertEqual(("c1", self.NOW), relay.with_db(lambda c: c.execute("SELECT txn_cursor, txn_changed_at FROM plaid_items WHERE item_id='cap'").fetchone()))

    def test_a_read_that_finds_nothing_new_says_when_it_looked_but_not_that_anything_changed(self):
        self.store(_txn("t1", 54.2), now=self.NOW - 7200)
        self.store(cursor="c2")
        self.assertEqual(("c2", self.NOW, self.NOW - 7200), relay.with_db(lambda c: c.execute("SELECT txn_cursor, txn_synced_at, txn_changed_at FROM plaid_items WHERE item_id='cap'").fetchone()))

    def test_a_sign_in_that_lapsed_is_noted_at_once_and_the_cursor_stays(self):
        self.store(_txn("t1", 54.2))
        relay.plaid_post = _PagedPlaid(transactions_sync=[relay.PlaidError("ITEM_LOGIN_REQUIRED", "sign in again")])
        relay.plaid_transactions_sync(self.NOW + 3600)
        self.assertEqual(("c1", "ITEM_LOGIN_REQUIRED"), relay.with_db(lambda c: c.execute("SELECT txn_cursor, error FROM plaid_items WHERE item_id='cap'").fetchone()))
        self.assertEqual(2, relay.state_get(relay.PLAID_TXN_SYNCED_KEY)["failed"])
        self.assertTrue(next(card for card in self.month()["cards"] if card["key"] == self.VENTURE)["needs_relink"])

    def test_plaid_being_out_of_reach_for_an_hour_is_not_an_institution_s_error(self):
        relay.plaid_post = _PagedPlaid(transactions_sync=[OSError("no route")])
        relay.plaid_transactions_sync(self.NOW)
        self.assertIsNone(relay.with_db(lambda c: c.execute("SELECT error FROM plaid_items WHERE item_id='cap'").fetchone())[0])

    def test_a_purchase_a_refund_and_paying_the_card_off_are_told_apart(self):
        self.assertEqual("spend", relay.txn_kind(54.2, "GENERAL_MERCHANDISE", "COSTCO"))
        self.assertEqual("refund", relay.txn_kind(-54.2, "GENERAL_MERCHANDISE", "COSTCO"))
        self.assertEqual("payment", relay.txn_kind(-900.0, "LOAN_PAYMENTS", "CAPITAL ONE"))
        self.assertEqual("payment", relay.txn_kind(-900.0, None, "AUTOPAY PAYMENT - THANK YOU"))
        self.assertEqual("refund", relay.txn_kind(-15.0, "TRANSPORTATION", "APPLE PAY UBER CREDIT"))
        # As Plaid's sandbox files a card's payment: under its own category, with the amount pointing out.
        self.assertEqual("payment", relay.txn_kind(2078.5, "LOAN_PAYMENTS", "AUTOMATIC PAYMENT - THANK", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"))

    def test_a_transaction_as_plaid_really_sends_it_is_kept(self):
        # The keys /transactions/sync answered with in the sandbox on 2026-10-07, values and all.
        sent = {
            "account_id": "venture", "account_owner": None, "amount": 500, "authorized_date": None, "authorized_datetime": None, "category": None, "category_id": None,
            "check_number": None, "counterparties": [], "date": "2026-10-05", "datetime": None, "iso_currency_code": "USD",
            "location": {"address": None, "city": None, "country": None, "lat": None, "lon": None, "postal_code": None, "region": None, "store_number": None},
            "logo_url": None, "merchant_category_code": None, "merchant_entity_id": None, "merchant_name": None, "name": "United Airlines", "payment_channel": "in store",
            "payment_meta": {"by_order_of": None, "payee": None, "payer": None, "payment_method": None, "payment_processor": None, "ppd_id": None, "reason": None, "reference_number": None},
            "pending": False, "pending_transaction_id": None,
            "personal_finance_category": {"confidence_level": "LOW", "detailed": "TRAVEL_FLIGHTS", "primary": "TRAVEL", "version": "v2"},
            "personal_finance_category_icon_url": "https://plaid-category-icons.plaid.com/PFC_TRAVEL.png", "running_balance": None, "transaction_code": None,
            "transaction_id": "sandbox-1", "transaction_type": "special", "unofficial_currency_code": None, "website": None,
        }
        self.store(sent)
        (purchase,) = self.month()["transactions"]
        self.assertEqual(("sandbox-1", "2026-10-05", 500.0, "United Airlines", "united airlines", "TRAVEL", "spend"),
                         tuple(purchase[k] for k in ("id", "date", "amount", "name", "merchant", "category", "kind")))

    def test_a_shop_is_the_same_shop_whichever_branch_it_was(self):
        self.assertEqual(relay.merchant_key(None, "COSTCO WHSE #0123"), relay.merchant_key(None, "Costco Whse #0456"))
        self.assertEqual("trader joes", relay.merchant_key("Trader Joe's", "TRADER JOE S #552"))
        self.assertEqual("", relay.merchant_key(None, None))
        # Letters of any alphabet are letters; a name with none is kept as written, slash and all.
        self.assertEqual("café böhm", relay.merchant_key("Café Böhm #12", None))
        self.assertEqual("12/34", relay.merchant_key(None, " 12/34 "))

    def test_a_shop_whose_name_holds_a_slash_can_be_remembered_and_forgotten(self):
        self.store(_txn("t1", 54.2, name="24/7 #12", merchant=None))
        body = relay.put_budget_transaction("t1", types.SimpleNamespace(bucket="person:Sarah", remember=True), object(), self.response)
        (rule,) = body["config"]["rules"]
        self.assertIn("/", rule["merchant"])
        self.assertEqual([], relay.delete_budget_rule(rule["merchant"], object(), self.response)["config"]["rules"])

    def test_a_tag_stays_through_a_change_and_passes_from_the_pending_purchase_to_the_posted_one(self):
        self.store(_txn("p1", 54.2, pending=True))
        relay.put_budget_transaction("p1", types.SimpleNamespace(bucket="person:Sarah", remember=False), object(), self.response)
        self.store(modified=[_txn("p1", 56.0, pending=True)], cursor="c2")
        self.assertEqual(56.0, self.bucket("person:Sarah")["spent"])
        self.store(_txn("t1", 56.0, pending_transaction_id="p1"), removed=["p1"], cursor="c3")
        month = self.month()
        self.assertEqual([("t1", "person:Sarah", "manual")], [(t["id"], t["bucket"], t["source"]) for t in month["transactions"]])
        self.assertEqual(56.0, month["spent"])

    def test_a_pending_purchase_plaid_has_not_yet_taken_back_is_not_counted_twice(self):
        self.store(_txn("p1", 54.2, pending=True), _txn("t1", 54.2, pending_transaction_id="p1"))
        self.assertEqual(54.2, self.month()["spent"])

    def test_what_plaid_takes_back_leaves_the_month_and_is_forgotten_in_time(self):
        self.store(_txn("t1", 54.2), _txn("t2", 10.0))
        self.store(removed=["t2"], cursor="c2")
        self.assertEqual(54.2, self.month()["spent"])
        self.store(cursor="c3", now=self.NOW + relay.TXN_TOMBSTONE_SECONDS + 1)
        self.assertEqual(1, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_transactions").fetchone()[0]))

    def test_the_whole_sync_reads_the_transactions_too(self):
        plaid = relay.plaid_post = _PagedPlaid(
            item_get=[{"item": {"products": ["transactions"]}}], accounts_get=[{"accounts": self.CARDS["cap"][1]}],
            transactions_sync=[{"added": [_txn("t1", 54.2)], "next_cursor": "c1", "has_more": False}])
        relay.with_db(lambda c: (c.execute("DELETE FROM plaid_items WHERE item_id='amex'"), c.commit()))
        self.assertTrue(relay.plaid_sync_all(self.NOW))
        self.assertEqual(1, len(plaid.asked("/transactions/sync")))
        self.assertEqual(54.2, self.month()["spent"])

    def test_the_cards_are_asked_again_an_hour_after_the_last_time(self):
        for item in self.CARDS:
            self.store(item=item)
        self.assertFalse(relay.transactions_due(self.NOW, self.NOW - 3599))
        self.assertTrue(relay.transactions_due(self.NOW, self.NOW - 3600))

    def test_a_card_just_linked_is_asked_at_every_check_until_its_first_purchases_are_in(self):
        relay.with_db(lambda c: (c.execute("UPDATE plaid_items SET linked_at=?", (self.NOW - 600,)), c.commit()))
        self.store(item="amex")
        self.store(status="NOT_READY")
        self.assertTrue(relay.transactions_due(self.NOW, self.NOW - relay.BANK_CHECK_SECONDS))
        self.assertFalse(relay.transactions_due(self.NOW, self.NOW - relay.BANK_CHECK_SECONDS + 1))
        self.store(cursor="c2", status="INITIAL_UPDATE_COMPLETE")
        self.assertFalse(relay.transactions_due(self.NOW, self.NOW - relay.BANK_CHECK_SECONDS))
        # One that has gone a day without them is asked hourly like the rest, not every five minutes for ever.
        self.store(cursor="c3", status="NOT_READY")
        relay.with_db(lambda c: (c.execute("UPDATE plaid_items SET linked_at=?", (self.NOW - relay.TXN_NEW_SECONDS - 1,)), c.commit()))
        self.assertFalse(relay.transactions_due(self.NOW, self.NOW - relay.BANK_CHECK_SECONDS))


class BudgetMonthTest(_Budget):
    """The month: whose each purchase is, what counts, and the settings."""

    def test_a_purchase_goes_to_whoever_it_is_known_to_be(self):
        relay.with_db(lambda c: (c.execute("INSERT INTO budget_rules VALUES ('barber shop', 'person:Andrew', 'alex', 0)"), c.commit()))
        self.store(_txn("named", 20.0, account_owner="SARAH GLOSE"), _txn("both", 5.0, account_owner="ANDREW AND SARAH GLOSE"),
                   _txn("rule", 30.0, name="BARBER SHOP 12", merchant=None), _txn("nobody", 7.5))
        self.store(_txn("family", 100.0, account="platinum", account_owner="SARAH GLOSE"), _txn("his", 40.0, account="platinum", name="Barber Shop", merchant=None), item="amex")
        found = {t["id"]: (t["bucket"], t["source"]) for t in self.month()["transactions"]}
        self.assertEqual({"named": ("person:Sarah", "bank"), "both": (None, "none"), "rule": ("person:Andrew", "rule"), "nobody": (None, "none"),
                          "family": ("family", "account"), "his": ("person:Andrew", "rule")}, found)
        self.assertEqual((70.0, 20.0, 100.0, 12.5), tuple(self.bucket(b)["spent"] for b in ("person:Andrew", "person:Sarah", "family", "unassigned")))
        self.assertEqual(202.5, self.month()["spent"])

    def test_a_card_s_last_four_is_whose_it_was_said_to_be(self):
        # Capital One marks each purchase with the card that made it, not with a name.
        self.store(_txn("mine", 20.0, account_owner="0035"), _txn("hers", 30.0, account_owner="1203"), _txn("hers2", 5.0, account_owner="1203"),
                   _txn("paid", -900.0, account_owner="0035", name="AUTOPAY PAYMENT", merchant=None, category="LOAN_PAYMENTS"), _txn("blank", 7.0))
        venture = next(card for card in self.month()["cards"] if card["key"] == self.VENTURE)
        self.assertEqual([{"mark": "1203", "count": 2, "person": None}, {"mark": "0035", "count": 1, "person": None}], venture["holders"])
        self.assertEqual({"unassigned": 62.0}, {b["id"]: b["spent"] for b in self.month()["buckets"] if b["spent"]})

        relay.budget_config_save({"holders": {self.VENTURE: {"0035": "Andrew", "1203": "Sarah"}}}, self.NOW)
        month = self.month()
        self.assertEqual({"mine": ("person:Andrew", "bank"), "hers": ("person:Sarah", "bank"), "hers2": ("person:Sarah", "bank"), "blank": (None, "none")},
                         {t["id"]: (t["bucket"], t["source"]) for t in month["transactions"]})
        self.assertEqual(["Sarah", "Andrew"], [h["person"] for h in next(card for card in month["cards"] if card["key"] == self.VENTURE)["holders"]])
        # A tag by hand still outranks the card, and taking the say-so back unsorts them again.
        relay.put_budget_transaction("hers2", types.SimpleNamespace(bucket="family", remember=False), object(), self.response)
        self.assertEqual("family", next(t["bucket"] for t in self.month()["transactions"] if t["id"] == "hers2"))
        relay.budget_config_save({"holders": {self.VENTURE: {"1203": None}}}, self.NOW)
        self.assertEqual({self.VENTURE: {"0035": "Andrew"}}, relay.budget_config()["holders"])
        self.assertIsNone(next(t["bucket"] for t in self.month()["transactions"] if t["id"] == "hers"))

    def test_a_cardholder_s_mark_means_nothing_on_the_family_s_card_and_goes_with_the_person(self):
        self.store(_txn("f", 100.0, account="platinum", account_owner="SARAH GLOSE 1006"), item="amex")
        relay.budget_config_save({"holders": {self.PLATINUM: {"SARAH GLOSE 1006": "Sarah"}, self.VENTURE: {"1203": "Sarah"}}}, self.NOW)
        self.assertEqual(("family", "account"), next((t["bucket"], t["source"]) for t in self.month()["transactions"]))
        with self.assertRaises(relay.HTTPException) as refused:
            relay.budget_config_save({"holders": {self.VENTURE: {"0035": "Nobody"}}}, self.NOW)
        self.assertEqual("bad_budget", refused.exception.detail["error"])
        self.assertEqual({}, relay.budget_config_save({"people": ["Andrew"]}, self.NOW)["holders"])

    def test_a_card_of_one_person_s_own_is_theirs_and_a_tag_by_hand_outranks_it(self):
        relay.budget_config_save({"roles": {self.VENTURE: "person:Sarah"}}, self.NOW)
        self.store(_txn("t1", 20.0), _txn("t2", 30.0))
        relay.put_budget_transaction("t2", types.SimpleNamespace(bucket="family", remember=False), object(), self.response)
        self.assertEqual((20.0, 30.0), (self.bucket("person:Sarah")["spent"], self.bucket("family")["spent"]))

    def test_paying_the_card_off_is_not_spending_and_a_refund_comes_off(self):
        self.store(_txn("t1", 100.0), _txn("back", -40.0), _txn("paid", -900.0, name="AUTOPAY PAYMENT", merchant=None, category="LOAN_PAYMENTS"),
                   _txn("held", 15.0, pending=True))
        month = self.month()
        self.assertEqual((75.0, 15.0), (month["spent"], month["pending"]))
        self.assertEqual(["t1", "held", "back"], [t["id"] for t in month["transactions"]])

    def test_a_card_with_no_role_or_ignored_is_no_part_of_the_budget(self):
        relay.budget_config_save({"roles": {self.VENTURE: "ignore", self.PLATINUM: None}}, self.NOW)
        self.store(_txn("t1", 100.0))
        self.store(_txn("t2", 50.0, account="platinum"), item="amex")
        month = self.month()
        self.assertEqual((0, []), (month["spent"], month["transactions"]))
        self.assertEqual({self.VENTURE: "ignore", self.PLATINUM: None}, {card["key"]: card["role"] for card in month["cards"]})

    def test_the_month_is_the_days_things_were_bought_on_the_household_s_clock(self):
        self.store(_txn("sep", 300.0, date="2026-09-30"), _txn("oct", 20.0, date="2026-10-01"), _txn("today", 5.0, date="2026-10-07"),
                   {**_txn("late", 9.0, date="2026-10-02"), "authorized_date": "2026-09-29"})
        month = self.month()
        self.assertEqual(("2026-10", "2026-10-07", 7, 31, 25.0), (month["month"], month["today"], month["day"], month["days_in_month"], month["spent"]))
        self.assertEqual([20.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0], [d["spent"] for d in month["daily"]])
        # The bank's clock ahead of the household's: tomorrow's purchase is in the month, and on today.
        self.store(_txn("ahead", 11.0, date="2026-10-08"), cursor="c2")
        month = self.month()
        self.assertEqual((36.0, 16.0, 36.0), (month["spent"], month["daily"][-1]["spent"], sum(d["spent"] for d in month["daily"])))
        self.store(removed=["ahead"], cursor="c3")
        month = self.month()
        self.assertEqual({"month": "2026-09", "spent": 309.0, "days": 30}, month["history"][-1])
        self.assertEqual(5, len(month["history"]))
        september = self.month("2026-09")
        self.assertEqual((30, 30, 309.0), (september["day"], len(september["daily"]), september["spent"]))
        self.assertEqual((0, []), (self.month("2026-11")["day"], self.month("2026-11")["daily"]))
        with self.assertRaises(relay.HTTPException) as bad:
            self.month("October")
        self.assertEqual(400, bad.exception.status_code)

    def test_what_it_went_on_and_where(self):
        self.store(_txn("a", 60.0), _txn("b", 40.0, name="COSTCO WHSE #0456"), _txn("c", 30.0, name="CHEZ PANISSE", merchant="Chez Panisse", category="FOOD_AND_DRINK"))
        month = self.month()
        self.assertEqual([{"id": "GENERAL_MERCHANDISE", "spent": 100.0, "count": 2}, {"id": "FOOD_AND_DRINK", "spent": 30.0, "count": 1}], month["categories"])
        self.assertEqual([{"name": "Costco", "spent": 100.0, "count": 2}, {"name": "Chez Panisse", "spent": 30.0, "count": 1}], month["merchants"])

    def test_a_card_linked_again_keeps_its_role(self):
        relay.with_db(lambda c: ([c.execute(f"DELETE FROM {table} WHERE item_id='cap'") for table in ("plaid_transactions", "plaid_accounts", "plaid_items")],
                                 relay.plaid_assign_keys(c), c.commit()))
        self.link("cap2", source="cap")
        self.store(_txn("t1", 54.2, account="venture-again"), item="cap2")
        self.assertEqual("split", next(card for card in self.month()["cards"] if card["key"] == self.VENTURE)["role"])
        self.assertEqual(54.2, self.month()["spent"])

    def test_the_month_is_not_all_there_while_plaid_is_still_fetching(self):
        self.assertFalse(self.month()["ready"])
        self.store(status="NOT_READY")
        self.store(item="amex")
        self.assertFalse(self.month()["ready"])
        self.store(cursor="c2")
        self.assertTrue(self.month()["ready"])

    def test_the_settings_change_a_part_at_a_time(self):
        relay.budget_config_save({"limits": {"total": 4500, "family": 2100, "people": {"Andrew": 1200}}}, self.NOW)
        relay.budget_config_save({"limits": {"family": None}, "alerts": {"total": True}, "card_paid_lines": ["Groceries", "Dining"],
                                  "sheet": {"take_home": 14170, "bills_off_card": 9000}}, self.NOW)
        config = self.month()["config"]
        self.assertEqual({"total": 4500.0, "people": {"Andrew": 1200.0, "Sarah": None}, "family": None}, config["limits"])
        self.assertEqual({"total": True, "savings": False, "buckets": False, "lines": False}, config["alerts"])
        self.assertEqual(["Dining", "Groceries"], config["card_paid_lines"])
        self.assertEqual(5170.0, self.month()["savings_line"])
        self.assertEqual(1200.0, self.bucket("person:Andrew")["limit"])

    def test_someone_leaving_the_budget_takes_their_limit_and_their_card_s_role(self):
        relay.budget_config_save({"limits": {"people": {"Sarah": 900}}, "roles": {self.VENTURE: "person:Sarah"}}, self.NOW)
        config = relay.budget_config_save({"people": ["Andrew"]}, self.NOW)
        self.assertEqual(({"Andrew": None}, {self.PLATINUM: "family"}), (config["limits"]["people"], config["roles"]))

    def test_settings_that_are_not_what_they_should_be_change_nothing(self):
        before = relay.budget_config()
        for patch in ({"limits": {"total": -1}}, {"limits": {"total": "lots"}}, {"limits": {"people": {"Nobody": 5}}}, {"roles": {self.VENTURE: "person:Nobody"}},
                      {"roles": {self.VENTURE: "mine"}}, {"people": ["Andrew", "andrew"]}, {"people": ["family"]}, {"people": ["a:b"]}, {"alerts": {"weekly": True}}):
            with self.assertRaises(relay.HTTPException) as refused:
                relay.budget_config_save(patch, self.NOW)
            self.assertEqual((400, "bad_budget"), (refused.exception.status_code, refused.exception.detail["error"]), patch)
        self.assertEqual(before, relay.budget_config())


class BudgetRecurringTest(_Budget):
    """What the cards are charged again and again: found by its rhythm and its amount, when the next one is due, and when one stops."""

    def recurring(self):
        return self.month()["recurring"]

    def named(self):
        return {r["name"]: r for r in self.recurring()["charges"]}

    def test_a_subscription_and_a_weekly_box_are_found_and_shopping_is_not(self):
        self.store(*(_txn(f"tv{i}", 15.99, date=day, name="STREAMFLIX.COM", merchant="Streamflix") for i, day in enumerate(("2026-07-17", "2026-08-17", "2026-09-16"))),
                   *(_txn(f"kit{i}", 105.93, date=day, name="FACTOR75LLC", merchant="Factor") for i, day in enumerate(("2026-09-14", "2026-09-21", "2026-09-28", "2026-10-05"))),
                   # The same coffee shop every week is not a subscription: its amount is never the same twice.
                   *(_txn(f"cafe{i}", amount, date=day, name="GREEN CAFE", merchant="Green Cafe") for i, (day, amount) in
                     enumerate((("2026-09-05", 6.5), ("2026-09-12", 7.25), ("2026-09-19", 6.75), ("2026-09-26", 7.0)))),
                   # Nor are Amazon's shipments, however alike.
                   *(_txn(f"amzn{i}", 25.0, date=day, name="AMAZON MKTPL*2K1", merchant="Amazon") for i, day in enumerate(("2026-08-05", "2026-09-05", "2026-10-05"))))
        recurring = self.recurring()
        self.assertEqual(["Factor", "Streamflix"], [r["name"] for r in recurring["charges"]])
        self.assertEqual({"merchant": "streamflix", "name": "Streamflix", "rhythm": "monthly", "amount": 15.99, "was": None, "monthly": 15.99, "bill": False,
                          "count": 3, "first": "2026-07-17", "last": "2026-09-16", "next": "2026-10-16", "stopped": False, "card": self.VENTURE, "bucket": None,
                          "line": None}, recurring["charges"][1])
        kit = recurring["charges"][0]
        self.assertEqual(("weekly", 4, "2026-10-12", 459.03), (kit["rhythm"], kit["count"], kit["next"], kit["monthly"]))
        self.assertEqual(475.02, recurring["monthly"])

    def test_a_price_that_changed_says_what_it_was_and_a_subscription_is_found_among_the_merchant_s_other_charges(self):
        self.store(*(_txn(f"d{i}", amount, date=day, name="DISNEY PLUS", merchant="Disney+") for i, (day, amount) in
                     enumerate((("2026-06-23", 32.99), ("2026-07-23", 32.99), ("2026-08-23", 32.99), ("2026-09-23", 34.99)))),
                   *(_txn(f"sub{i}", 9.99, date=day, name="TESLA SUBSCRIPTION US", merchant="Tesla") for i, day in enumerate(("2026-08-01", "2026-09-01", "2026-10-01"))),
                   *(_txn(f"charge{i}", amount, date=day, name="TESLA SUPERCHARGER US", merchant="Tesla") for i, (day, amount) in
                     enumerate((("2026-08-01", 8.77), ("2026-08-17", 7.31), ("2026-09-03", 11.5), ("2026-10-03", 9.0)))))
        named = self.named()
        self.assertEqual(["Disney+", "Tesla"], sorted(named))
        self.assertEqual((34.99, 32.99, 4), (named["Disney+"]["amount"], named["Disney+"]["was"], named["Disney+"]["count"]))
        self.assertEqual((9.99, None, 3, "2026-11-01"), (named["Tesla"]["amount"], named["Tesla"]["was"], named["Tesla"]["count"], named["Tesla"]["next"]))

    def test_a_price_change_is_found_past_another_charge_of_the_merchant_s_at_a_price_in_between(self):
        self.store(*(_txn(f"s{i}", amount, date=day, name="STREAMFLIX.COM", merchant="Streamflix") for i, (day, amount) in
                     enumerate((("2026-06-01", 9.99), ("2026-07-01", 9.99), ("2026-08-01", 9.99), ("2026-09-01", 10.99)))),
                   _txn("gift", 10.5, date="2026-07-02", name="STREAMFLIX GIFT CARD", merchant="Streamflix"))
        streamflix = self.named()["Streamflix"]
        self.assertEqual((10.99, 9.99, 4, "2026-10-01", False), (streamflix["amount"], streamflix["was"], streamflix["count"], streamflix["next"], streamflix["stopped"]))

    def test_an_amazon_membership_is_not_taken_for_a_shipment(self):
        self.store(*(_txn(f"prime{i}", 14.99, date=day, name="Amazon Prime*1A2B3", merchant="Amazon") for i, day in enumerate(("2026-07-18", "2026-08-18", "2026-09-18"))))
        self.assertEqual((14.99, 3), (self.named()["Amazon"]["amount"], self.named()["Amazon"]["count"]))

    def test_a_bill_s_share_counts_every_name_it_went_by(self):
        # The utility's bills, the last under a new name, among as many of its other charges: half of what it charged is not most.
        self.store(*(_txn(f"bill{i}", amount, date=day, name="CITY POWER", merchant=merchant, category="RENT_AND_UTILITIES") for i, (day, amount, merchant) in
                     enumerate((("2026-07-14", 120.0, "City Power"), ("2026-08-13", 150.0, "City Power"), ("2026-09-12", 140.0, "City Power Co")))),
                   *(_txn(f"shop{i}", amount, date=day, name="CITY POWER STORE", merchant="City Power") for i, (day, amount) in
                     enumerate((("2026-07-20", 30.0), ("2026-08-01", 45.0), ("2026-08-25", 12.0)))))
        self.assertEqual({}, self.named())

    def test_a_bill_can_be_any_amount_and_costs_what_its_last_few_came_to(self):
        self.store(*(_txn(f"amp{i}", amount, date=day, name="ALAMEDA MUNICIPAL POWER", merchant="Alameda Municipal Power", category="RENT_AND_UTILITIES") for i, (day, amount) in
                     enumerate((("2026-07-14", 261.38), ("2026-08-11", 321.98), ("2026-09-02", 530.81), ("2026-09-11", 406.09)))))
        power = self.named()["Alameda Municipal Power"]
        self.assertEqual((True, None, 3, 329.82, "2026-10-11"), (power["bill"], power["was"], power["count"], power["monthly"], power["next"]))

    def test_two_charges_a_month_apart_count_only_at_a_price_to_the_cent(self):
        self.store(_txn("sonic1", 49.99, date="2026-08-09", name="SONIC.NET", merchant="Sonic"), _txn("sonic2", 49.99, date="2026-09-09", name="SONIC.NET", merchant="Sonic"),
                   _txn("stand1", 15.0, date="2026-08-22", name="FLOWER STAND", merchant=None), _txn("stand2", 15.0, date="2026-09-22", name="FLOWER STAND", merchant=None))
        self.assertEqual(["Sonic"], list(self.named()))

    def test_one_that_stopped_says_so_for_a_while_and_a_renamed_merchant_is_one_run(self):
        self.store(_txn("l1", 14.43, date="2026-07-13", name="LEMONADE INSURANCE", merchant="Lemonade"), _txn("l2", 14.43, date="2026-08-12", name="LEMONADE INSURANCE", merchant="Lemonade"),
                   # Stopped too long ago to say.
                   _txn("g1", 29.99, date="2026-05-01", name="OLD GYM", merchant="Old Gym"), _txn("g2", 29.99, date="2026-06-01", name="OLD GYM", merchant="Old Gym"),
                   # Plaid named it one thing for two weeks and another the third.
                   _txn("f1", 105.93, date="2026-09-21", name="FACTOR75LLC", merchant="Factor Inc."), _txn("f2", 105.93, date="2026-09-28", name="FACTOR75LLC", merchant="Factor Inc."),
                   _txn("f3", 105.93, date="2026-10-05", name="FACTOR75LLC", merchant="Factor75"))
        recurring = self.recurring()
        self.assertEqual([("Factor75", False, 3), ("Lemonade", True, 2)], [(r["name"], r["stopped"], r["count"]) for r in recurring["charges"]])
        self.assertIsNone(recurring["charges"][1]["next"])
        # What has stopped costs nothing a month.
        self.assertEqual(459.03, recurring["monthly"])

    def test_only_the_month_under_way_says(self):
        self.store(*(_txn(f"tv{i}", 15.99, date=day, name="STREAMFLIX.COM", merchant="Streamflix") for i, day in enumerate(("2026-07-17", "2026-08-17", "2026-09-16"))))
        self.assertIsNone(self.month("2026-09")["recurring"])
        self.assertEqual(1, len(self.recurring()["charges"]))


class BudgetLinesTest(_Budget):
    """The budget sheet's card-paid lines: which purchases fill each, by hand, by rule and on their own, and each against its plan."""

    def setUp(self):
        super().setUp()
        relay.budget_config_save({"card_paid_lines": ["Groceries", "Dining out", "Amazon"],
                                  "sheet": {"take_home": 10000, "bills_off_card": 6000, "lines": {"Groceries": 600, "Dining out": 250, "Amazon": 100}}}, self.NOW)

    def line(self, name, month=None):
        body = self.month(month)
        return next(line for line in body["lines"] if line["name"] == name) if name else body["outside_lines"]

    def found(self, body=None):
        return {t["id"]: (t["line"], t["line_source"]) for t in (body or self.month())["transactions"]}

    def test_a_purchase_finds_its_line_on_its_own(self):
        self.store(_txn("costco", 180.0, category="GENERAL_MERCHANDISE"),
                   _txn("safeway", 64.5, name="SAFEWAY #1234", merchant="Safeway", category="FOOD_AND_DRINK", detailed="FOOD_AND_DRINK_GROCERIES"),
                   _txn("market", 22.0, name="CORNER MARKET", merchant=None, category="FOOD_AND_DRINK", detailed="FOOD_AND_DRINK_GROCERIES"),
                   _txn("pizza", 38.0, name="LUNA PIZZERIA", merchant="Luna Pizzeria", category="FOOD_AND_DRINK", detailed="FOOD_AND_DRINK_RESTAURANT"),
                   _txn("amazon", 41.0, name="AMAZON MKTPL*2K1", merchant="Amazon", category="GENERAL_MERCHANDISE", detailed="GENERAL_MERCHANDISE_ONLINE_MARKETPLACES"),
                   _txn("shell", 50.0, name="SHELL OIL", merchant="Shell", category="TRANSPORTATION", detailed="TRANSPORTATION_GAS"))
        self.assertEqual({"costco": ("Groceries", "auto"), "safeway": ("Groceries", "auto"), "market": ("Groceries", "auto"), "pizza": ("Dining out", "auto"),
                          "amazon": ("Amazon", "auto"), "shell": (None, "none")}, self.found())
        self.assertEqual({"name": "Groceries", "planned": 600.0, "spent": 266.5, "count": 3, "usual": None}, self.line("Groceries"))
        self.assertEqual({"name": None, "planned": None, "spent": 50.0, "count": 1, "usual": None}, self.line(None))

    def test_only_the_lines_paid_by_card_are_lines(self):
        self.store(_txn("safeway", 64.5, name="SAFEWAY #1234", merchant="Safeway", category="FOOD_AND_DRINK"))
        relay.budget_config_save({"card_paid_lines": ["Dining out"]}, self.NOW)
        month = self.month()
        self.assertEqual(["Dining out"], [line["name"] for line in month["lines"]])
        self.assertEqual({"safeway": (None, "none")}, self.found(month))

    def test_putting_a_purchase_in_a_line_by_hand_outranks_the_rest_and_can_be_taken_back(self):
        self.store(_txn("tv", 899.0), _txn("bulk", 120.0, name="COSTCO WHSE #0456"))
        relay.put_budget_transaction_line("tv", types.SimpleNamespace(line="", remember=False), object(), self.response)
        body = relay.put_budget_transaction_line("bulk", types.SimpleNamespace(line="Dining out", remember=False), object(), self.response)
        self.assertEqual({"tv": (None, "manual"), "bulk": ("Dining out", "manual")}, self.found(body))
        body = relay.put_budget_transaction_line("tv", types.SimpleNamespace(line=None, remember=False), object(), self.response)
        self.assertEqual(("Groceries", "auto"), self.found(body)["tv"])

    def test_remembering_a_shop_puts_its_other_purchases_in_the_line_and_forgetting_it_does_not_undo_the_tag(self):
        self.store(_txn("t1", 30.0, name="BIG BOX #1", merchant="Big Box"), _txn("t2", 12.0, name="BIG BOX #2", merchant="Big Box"))
        body = relay.put_budget_transaction_line("t1", types.SimpleNamespace(line="Groceries", remember=True), object(), self.response)
        self.assertEqual({"t1": ("Groceries", "manual"), "t2": ("Groceries", "rule")}, self.found(body))
        self.assertTrue(all(t["line_remembered"] for t in body["transactions"]))
        self.assertEqual([{"merchant": "big box", "line": "Groceries"}], body["config"]["line_rules"])
        # Whose a purchase is and which line it is in are two things: the one doesn't touch the other.
        self.assertEqual([None, None], [t["bucket"] for t in body["transactions"]])
        body = relay.delete_budget_line_rule("big box", object(), self.response)
        self.assertEqual({"t1": ("Groceries", "manual"), "t2": (None, "none")}, self.found(body))

    def test_a_shop_can_be_said_to_never_be_in_a_line(self):
        self.store(_txn("t1", 300.0), _txn("t2", 200.0, name="COSTCO WHSE #0456"))
        body = relay.put_budget_transaction_line("t1", types.SimpleNamespace(line="", remember=True), object(), self.response)
        self.assertEqual({"t1": (None, "manual"), "t2": (None, "rule")}, self.found(body))
        self.assertEqual([{"merchant": "costco", "line": None}], body["config"]["line_rules"])

    def test_a_line_s_tag_passes_from_the_pending_purchase_to_the_posted_one(self):
        self.store(_txn("p1", 54.2, pending=True))
        relay.put_budget_transaction_line("p1", types.SimpleNamespace(line="Dining out", remember=False), object(), self.response)
        self.store(_txn("t1", 56.0, pending_transaction_id="p1"), removed=["p1"], cursor="c2")
        self.assertEqual({"t1": ("Dining out", "manual")}, self.found())

    def test_a_line_usually_comes_to_the_average_of_the_months_before_that_spent_anything(self):
        self.store(_txn("aug", 700.0, date="2026-08-10"), _txn("sep", 500.0, date="2026-09-10"), _txn("oct", 100.0),
                   _txn("jul-gas", 40.0, date="2026-07-02", name="SHELL OIL", merchant="Shell", category="TRANSPORTATION"))
        # July spent only on gas, so it counts as a month the Groceries line spent nothing in.
        self.assertEqual((100.0, round((0 + 700 + 500) / 3, 2)), (self.line("Groceries")["spent"], self.line("Groceries")["usual"]))
        self.assertIsNone(self.line("Groceries", "2026-08")["usual"])

    def test_a_month_whose_refunds_cancelled_its_purchases_still_counts_toward_the_usual(self):
        self.store(_txn("aug", 700.0, date="2026-08-10"), _txn("aug-back", -700.0, date="2026-08-12", name="SHELL OIL", merchant="Shell", category="TRANSPORTATION"),
                   _txn("sep", 500.0, date="2026-09-10"))
        self.assertEqual(600.0, self.line("Groceries")["usual"])

    def test_a_line_that_is_not_paid_by_card_or_a_tag_on_one_that_was_is_refused_or_forgotten(self):
        self.store(_txn("t1", 54.2))
        with self.assertRaises(relay.HTTPException) as bad:
            relay.put_budget_transaction_line("t1", types.SimpleNamespace(line="Mortgage", remember=False), object(), self.response)
        self.assertEqual((400, "bad_line"), (bad.exception.status_code, bad.exception.detail["error"]))
        with self.assertRaises(relay.HTTPException) as gone:
            relay.put_budget_transaction_line("nope", types.SimpleNamespace(line="Groceries", remember=False), object(), self.response)
        self.assertEqual(404, gone.exception.status_code)
        relay.put_budget_transaction_line("t1", types.SimpleNamespace(line="Dining out", remember=False), object(), self.response)
        relay.budget_config_save({"card_paid_lines": ["Groceries"]}, self.NOW)
        self.assertEqual({"t1": ("Groceries", "auto")}, self.found())

    def test_the_sheet_s_lines_are_amounts(self):
        self.assertEqual({"Groceries": 600.0, "Dining out": 250.0, "Amazon": 100.0}, relay.budget_config()["sheet"]["lines"])
        with self.assertRaises(relay.HTTPException) as refused:
            relay.budget_config_save({"sheet": {"take_home": 1, "bills_off_card": 1, "lines": {"Groceries": "lots"}}}, self.NOW)
        self.assertEqual("bad_budget", refused.exception.detail["error"])
        # A sheet reported before there were lines, as an older app does, has none.
        relay.budget_config_save({"sheet": {"take_home": 1, "bills_off_card": 1}}, self.NOW)
        self.assertEqual({}, relay.budget_config()["sheet"]["lines"])
        self.assertIsNone(self.line("Groceries")["planned"])

    def test_a_line_guesses_by_its_name(self):
        guess = relay.budget_line_guess
        self.assertEqual("Food", guess(["Food"], "safeway", "FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES"))
        self.assertEqual("Gas", guess(["Gas", "Groceries"], "chevron", "TRANSPORTATION", "TRANSPORTATION_GAS"))
        self.assertEqual("Meal kits", guess(["Meal kits"], "hellofresh", "FOOD_AND_DRINK", "FOOD_AND_DRINK_OTHER_FOOD_AND_DRINK"))
        self.assertEqual("Streaming", guess(["Streaming"], "netflix", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES"))
        # Fuel from a grocer is fuel when there is a line for it, and groceries when there isn't.
        self.assertEqual("Gas", guess(["Gas", "Groceries"], "costco gas", "TRANSPORTATION", "TRANSPORTATION_GAS"))
        self.assertEqual("Groceries", guess(["Groceries"], "costco gas", "TRANSPORTATION", "TRANSPORTATION_GAS"))
        self.assertEqual("Groceries", guess(["Gas", "Groceries"], "costco", "GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_SUPERSTORES"))
        self.assertIsNone(guess(["Gasoline savings"], "chevron", "TRANSPORTATION", "TRANSPORTATION_GAS"))
        self.assertIsNone(guess([], "safeway", "FOOD_AND_DRINK", "FOOD_AND_DRINK_GROCERIES"))
        # A line named for a shop has that shop, but not one that merely starts with the same letters.
        self.assertEqual("Target", guess(["Target", "Groceries"], "target", "GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_SUPERSTORES"))
        self.assertIsNone(guess(["Target"], "targeted ads", None, None))


class BudgetAlertsTest(_Budget):
    """Telling the phones: which lines, which phones, and each only once."""

    def setUp(self):
        super().setUp()
        relay.budget_config_save({"limits": {"total": 1000, "family": 300, "people": {"Andrew": 200}}, "sheet": {"take_home": 5000, "bills_off_card": 3800}}, self.NOW)
        self.pushed = []

    def push(self, token, title, body, data):
        self.pushed.append((token, data["budget_kind"]))
        return "sent"

    def spend(self, amount, txn_id="t1", **more):
        self.store(_txn(txn_id, amount, **more), cursor=txn_id)

    def check(self, now=None):
        return relay.budget_check(self.NOW if now is None else now, push=self.push)

    def test_nobody_is_told_anything_until_an_alert_is_switched_on(self):
        self.phone("a")
        self.spend(5000.0)
        self.assertEqual([], self.check())
        self.assertEqual([], self.pushed)

    def test_each_phone_hears_once_that_the_month_is_nearly_spent_and_once_that_it_is(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("a")
        self.spend(799.0)
        self.assertEqual([], self.check())
        self.spend(2.0, "t2")
        self.assertEqual(["total_80"], self.check())
        self.assertEqual([], self.check(self.NOW + 3600))
        self.spend(300.0, "t3")
        self.assertEqual(["total_100"], self.check(self.NOW + 7200))
        self.assertEqual([], self.check(self.NOW + 10800))
        self.assertEqual([("token-a", "total_80"), ("token-a", "total_100")], self.pushed)
        self.assertEqual(["total_100", "total_80"], self.month()["alerts_sent"])

    def test_a_month_already_over_when_the_alerts_come_on_says_so_once(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("a")
        self.spend(1500.0)
        self.assertEqual(["total_100"], self.check())
        self.assertEqual([], self.check(self.NOW + 3600))
        self.assertEqual([("token-a", "total_100")], self.pushed)

    def test_only_the_phones_of_people_who_may_see_the_finances_are_told(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("admin")
        self.phone("listed", user="sam", role="viewer")
        self.phone("guest", user="kid", role="viewer")
        self.phone("unknown", user=None, role=None)
        self.phone("ipad", token=None)
        self.spend(1500.0)
        self.check()
        self.assertEqual({"token-admin", "token-listed"}, {token for token, _ in self.pushed})

    def test_a_phone_in_its_quiet_hours_hears_after_them_and_one_that_wants_only_away_alerts_still_hears(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("asleep", quiet_start=11 * 60, quiet_end=13 * 60, utc_offset=0)
        self.phone("away", only_away=1)
        self.spend(1500.0)
        self.check()
        self.assertEqual([("token-away", "total_100")], self.pushed)
        self.assertEqual(["total_100"], self.check(self.NOW + 3600))
        self.assertEqual([("token-away", "total_100"), ("token-asleep", "total_100")], self.pushed)

    def test_a_push_that_did_not_arrive_is_tried_again(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("a")
        self.spend(1500.0)
        self.assertEqual([], relay.budget_check(self.NOW, push=lambda *args: "failed"))
        self.assertEqual(["total_100"], self.check())

    def test_each_person_and_the_family_card_have_their_own_line_and_so_do_the_savings(self):
        relay.budget_config_save({"alerts": {"buckets": True, "savings": True}}, self.NOW)
        self.phone("a")
        self.spend(250.0, account_owner="ANDREW GLOSE")
        self.assertEqual(["person:Andrew_100"], self.check())
        self.store(_txn("t2", 301.0, account="platinum"), item="amex")
        self.assertEqual(["family_100"], self.check())
        self.spend(700.0, "t3")
        self.assertEqual(["savings"], self.check())
        self.assertEqual(["person_100", "family_100", "savings"], [kind for _, kind in self.pushed])

    def test_a_budget_line_going_over_its_plan_is_told_once_and_only_when_switched_on(self):
        relay.budget_config_save({"card_paid_lines": ["Groceries", "Dining out"],
                                  "sheet": {"take_home": 5000, "bills_off_card": 3800, "lines": {"Groceries": 600, "Dining out": 250}}}, self.NOW)
        self.phone("a")
        self.spend(650.0)
        self.assertEqual([], self.check())
        relay.budget_config_save({"alerts": {"lines": True}}, self.NOW)
        self.assertEqual(["line:Groceries_100"], self.check())
        self.assertEqual([], self.check(self.NOW + 3600))
        # Reaching the plan exactly is on it, not over it.
        self.spend(250.0, "t2", name="LUNA PIZZERIA", merchant="Luna Pizzeria", category="FOOD_AND_DRINK", detailed="FOOD_AND_DRINK_RESTAURANT")
        self.assertEqual([], self.check(self.NOW + 7200))
        self.spend(0.01, "t3", name="LUNA PIZZERIA", merchant="Luna Pizzeria", category="FOOD_AND_DRINK", detailed="FOOD_AND_DRINK_RESTAURANT")
        self.assertEqual(["line:Dining out_100"], self.check(self.NOW + 10800))
        self.assertEqual(["line_100", "line_100"], [kind for _, kind in self.pushed])

    def test_a_line_s_push_names_the_line(self):
        relay.budget_config_save({"card_paid_lines": ["Groceries"], "alerts": {"lines": True},
                                  "sheet": {"take_home": 5000, "bills_off_card": 3800, "lines": {"Groceries": 600}}}, self.NOW)
        self.phone("a")
        self.spend(642.0)
        sent = []
        relay.budget_check(self.NOW, push=lambda token, title, body, data: sent.append((title, body, data)) or "sent")
        title, body, data = sent[0]
        self.assertEqual(("Groceries is over budget", "$642 spent of the $600 the budget sheet plans, with 24 days left this month."), (title, body))
        self.assertEqual(("line_100", "Groceries", "", "600.00", "budget-2026-10-line:Groceries_100"),
                         (data["budget_kind"], data["line"], data["person"], data["limit"], data["notif_id"]))

    def test_a_line_the_sheet_plans_nothing_for_is_never_over(self):
        relay.budget_config_save({"card_paid_lines": ["Groceries"], "alerts": {"lines": True}}, self.NOW)
        self.phone("a")
        self.spend(5000.0)
        self.assertEqual([], self.check())

    def test_a_new_month_starts_the_telling_over(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("a")
        self.spend(1500.0)
        self.check()
        self.spend(1200.0, "nov", date="2026-11-02")
        self.assertEqual(["total_100"], self.check(self.NOW + 30 * 86400))
        self.assertEqual("2026-11", relay.state_get(relay.BUDGET_ALERTS_KEY)["month"])

    def test_the_push_says_what_the_app_needs_to_word_it_itself(self):
        relay.budget_config_save({"alerts": {"buckets": True}}, self.NOW)
        self.phone("a")
        self.spend(250.0, account_owner="ANDREW GLOSE")
        sent = []
        relay.budget_check(self.NOW, push=lambda token, title, body, data: sent.append((title, body, data)) or "sent")
        title, body, data = sent[0]
        self.assertEqual(("Andrew is over budget", "$250 spent of $200, with 24 days left this month."), (title, body))
        self.assertEqual({"budget": "1", "budget_kind": "person_100", "month": "2026-10", "spent": "250.00", "limit": "200.00", "person": "Andrew", "line": "",
                          "days_left": "24", "notif_id": "budget-2026-10-person:Andrew_100"}, data)

    def test_a_dead_token_is_dropped_like_any_other_push_s(self):
        relay.budget_config_save({"alerts": {"total": True}}, self.NOW)
        self.phone("a")
        self.spend(1500.0)
        relay.send_push = lambda token, title, body, data, away=False: (False, "404 UNREGISTERED")
        self.assertEqual([], relay.budget_check(self.NOW))
        self.assertEqual(0, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]))


class BudgetRoutesTest(_Budget):
    """The budget routes: who gets in, tagging and remembering, and asking the cards now."""

    def test_someone_who_may_not_see_the_finances_gets_nowhere(self):
        def refuse(request):
            raise relay.HTTPException(403, {"error": "not_allowed"})

        relay.require_finance_user = refuse
        self.store(_txn("t1", 54.2))
        for route in (lambda: relay.get_budget(object(), self.response), lambda: relay.post_budget_sync(object(), self.response),
                      lambda: relay.put_budget_config(types.SimpleNamespace(people=["Kid"]), object(), self.response),
                      lambda: relay.put_budget_transaction("t1", types.SimpleNamespace(bucket="family", remember=True), object(), self.response),
                      lambda: relay.delete_budget_rule("costco", object(), self.response)):
            with self.assertRaises(relay.HTTPException) as refused:
                route()
            self.assertEqual(403, refused.exception.status_code)
        self.assertEqual(["Andrew", "Sarah"], relay.budget_config()["people"])
        self.assertEqual({}, relay.budget_rules())

    def test_the_month_answers_without_plaid_set_up_and_is_never_cached(self):
        relay.PLAID_CLIENT_ID = ""
        body = relay.get_budget(object(), self.response)
        self.assertFalse(body["configured"])
        self.assertEqual("private, no-store", self.response.headers["Cache-Control"])
        with self.assertRaises(relay.HTTPException) as off:
            relay.post_budget_sync(object(), self.response)
        self.assertEqual((503, "not_configured"), (off.exception.status_code, off.exception.detail["error"]))

    def test_remembering_a_shop_sorts_its_other_purchases_and_forgetting_it_leaves_the_tagged_one(self):
        self.store(_txn("t1", 54.2), _txn("t2", 20.0, name="COSTCO WHSE #0456"), _txn("t3", 9.0, name="SHELL OIL", merchant="Shell"))
        body = relay.put_budget_transaction("t1", types.SimpleNamespace(bucket="person:Sarah", remember=True), object(), self.response)
        self.assertEqual({"t1": ("person:Sarah", "manual", True), "t2": ("person:Sarah", "rule", True), "t3": (None, "none", False)},
                         {t["id"]: (t["bucket"], t["source"], t["remembered"]) for t in body["transactions"]})
        self.assertEqual([{"merchant": "costco", "bucket": "person:Sarah"}], body["config"]["rules"])
        body = relay.delete_budget_rule("costco", object(), self.response)
        self.assertEqual({"t1": "person:Sarah", "t2": None, "t3": None}, {t["id"]: t["bucket"] for t in body["transactions"]})

    def test_a_tag_can_be_taken_off_and_the_answer_is_the_purchase_s_own_month(self):
        self.store(_txn("old", 54.2, date="2026-09-12"))
        relay.put_budget_transaction("old", types.SimpleNamespace(bucket="family", remember=True), object(), self.response)
        body = relay.put_budget_transaction("old", types.SimpleNamespace(bucket=None, remember=True), object(), self.response)
        self.assertEqual(("2026-09", [None]), (body["month"], [t["bucket"] for t in body["transactions"]]))
        self.assertEqual({}, relay.budget_rules())

    def test_a_purchase_that_is_gone_or_a_bucket_nobody_has_is_refused(self):
        self.store(_txn("t1", 54.2))
        with self.assertRaises(relay.HTTPException) as unknown:
            relay.put_budget_transaction("nope", types.SimpleNamespace(bucket="family", remember=False), object(), self.response)
        self.assertEqual(404, unknown.exception.status_code)
        with self.assertRaises(relay.HTTPException) as bad:
            relay.put_budget_transaction("t1", types.SimpleNamespace(bucket="person:Nobody", remember=False), object(), self.response)
        self.assertEqual((400, "bad_bucket"), (bad.exception.status_code, bad.exception.detail["error"]))

    def test_changing_the_settings_answers_the_month_and_looks_at_the_alerts(self):
        body = relay.put_budget_config(types.SimpleNamespace(limits={"total": 4500}), object(), self.response)
        self.assertEqual(4500.0, body["config"]["limits"]["total"])
        self.assertEqual(["check"], self.background)

    def test_sync_now_asks_the_cards_once_and_not_again_within_the_minute(self):
        self.assertTrue(relay.post_budget_sync(object(), self.response)["syncing"])
        relay.post_budget_sync(object(), self.response)
        self.assertEqual(["budget"], self.background)
        relay._txn_asked["at"] = 0.0
        relay.state_set(relay.PLAID_TXN_SYNCED_KEY, {"at": time.time()})
        self.assertFalse(relay.post_budget_sync(object(), self.response)["syncing"])
        self.assertEqual(["budget"], self.background)

    def test_unlinking_a_card_forgets_what_was_bought_on_it(self):
        relay.plaid_post = _FakePlaid()
        self.store(_txn("t1", 54.2))
        relay.delete_bank_institution("cap", object(), self.response)
        self.assertEqual(0, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_transactions").fetchone()[0]))
        self.assertEqual([self.PLATINUM], [card["key"] for card in self.month()["cards"]])


# Made-up Amazon emails in the shapes Amazon sends: an order's plain text, the same order as HTML,
# and a shipment's.
AMAZON_ORDERED_TEXT = """Amazon.com
Hello Sam,
Thanks for your order. We'll let you know once your items have shipped.
Order #
112-1234567-7654321
Arriving Friday, October 9
Ship to
Sam Rivera
Kirkland Signature Paper Towels, 12 Big Rolls
Quantity: 1
$24.99
LEGO Star Wars X-Wing Starfighter Building Set
Quantity: 2
$39.99
Item(s) Subtotal: $104.97
Shipping & Handling: $0.00
Total before tax: $104.97
Estimated tax to be collected: $8.66
Grand Total:
$113.63
"""
AMAZON_ORDERED_HTML = """<html><head><style>td { color: #111 }</style></head><body><table>
<tr><td>Order #</td></tr><tr><td><a href="https://www.amazon.com/gp/your-account/order-details?orderID=113-7654321-1234567">113-7654321-1234567</a></td></tr>
<tr><td><img src="x.jpg"></td><td><a href="https://www.amazon.com/dp/B0">Purina Pro Plan Adult Dog Food, 35 lb</a><br>Quantity:&nbsp;1</td><td>$64.98</td></tr>
<tr><td>Order Total:</td><td>$70.34</td></tr>
</table></body></html>"""
AMAZON_SHIPPED_TEXT = """Your package was shipped!
Order #114-1111111-2222222
Bluey Family Home Playset
Quantity: 1
Shipment total: $32.47
"""


# The shapes of Amazon's emails since 2026, which name no items: Amazon's category, how many, and
# the total. The order numbers are made up; the marks round them are Amazon's own.
AMAZON_2026_ORDERED_PLAIN = "Sam, thanks for your order!\nArriving tomorrow\nOrder #\n113-0000000-0000003\nGrand Total:\n19.80 USD\n"
AMAZON_2026_ORDERED_HTML = ("<html><body><table><tr><td>Sam, thanks for your order!</td></tr><tr><td>Arriving tomorrow</td></tr>"
                            "<tr><td>1 Kitchen item</td></tr><tr><td>Order # \u202b113-0000000-0000003</td></tr>"
                            "<tr><td>Grand Total:</td></tr><tr><td>$19.80</td></tr></table></body></html>")
AMAZON_2026_SHIPPED_HTML = ("<html><body><div>Sam, your package was shipped!</div><div>2 items: 1 Kitchen, 1 Coffee</div>"
                            "<div>Order # \u202b113-0000000-0000004</div><div>Track package</div><div>Total</div><div>$41.50</div></body></html>")


class AmazonEmailTest(unittest.TestCase):
    def test_a_2026_order_email_gives_amazon_s_category_and_its_grand_total(self):
        parsed = relay.parse_amazon_email("Ordered 1 item: Kitchen", relay.html_text(AMAZON_2026_ORDERED_HTML))
        self.assertEqual({"kind": "ordered", "orders": ["113-0000000-0000003"], "total": 19.80, "shipment_total": None,
                          "items": [{"title": "Kitchen", "quantity": 1, "price": None}]}, parsed)
        # Its plain part names nothing, and writes the total in dollars after it.
        plain = relay.parse_amazon_email("Ordered 1 item: Kitchen", AMAZON_2026_ORDERED_PLAIN)
        self.assertEqual((19.80, [{"title": "Kitchen", "quantity": 1, "price": None}]), (plain["total"], plain["items"]))

    def test_a_2026_shipment_email_counts_each_category_and_gives_its_own_total(self):
        parsed = relay.parse_amazon_email("Shipped 2 items: Kitchen, Coffee", relay.html_text(AMAZON_2026_SHIPPED_HTML))
        self.assertEqual(("shipped", ["113-0000000-0000004"], 41.50), (parsed["kind"], parsed["orders"], parsed["shipment_total"]))
        self.assertEqual([{"title": "Kitchen", "quantity": 1, "price": None}, {"title": "Coffee", "quantity": 1, "price": None}], parsed["items"])

    def test_an_email_about_several_orders_gives_each_its_own_categories_and_total(self):
        text = relay.html_text(
            "<div>Thanks for your order!</div><div>Arriving Friday</div><div>1 item: Pasta</div><div>Order # \u202b111-0000000-0000001</div>"
            "<div>Grand Total:</div><div>$27.10</div><div>Arriving tomorrow</div><div>6 items: 1 Cereal, 2 Beverages, and 3 others</div>"
            "<div>Order # \u202b111-0000000-0000002</div><div>Grand Total:</div><div>$118.40</div>"
            "<div>2 items: 1 Garden Supplies, 1 Tools</div><div>Order # 111-0000000-0000003</div><div>View or edit order</div>"
            "<div>8 items: 2 Party Supplies, 2 Socks, and 4 others</div><div>Order # 111-0000000-0000003</div><div>Total before tax:</div><div>$150.00</div>"
            "<div>Grand Total:</div><div>$164.25</div>")
        parsed = relay.parse_amazon_email("Ordered 17 items: Pasta, Cereal, and more", text)
        blocks = {order_id: ([(i["title"], i["quantity"]) for i in b["items"]], b["total"]) for order_id, b in parsed["per_order"].items()}
        self.assertEqual({"111-0000000-0000001": ([("Pasta", 1)], 27.10),
                          "111-0000000-0000002": ([("Cereal", 1), ("Beverages", 2)], 118.40),
                          "111-0000000-0000003": ([("Garden Supplies", 1), ("Tools", 1), ("Party Supplies", 2), ("Socks", 2)], 164.25)}, blocks)

    def test_categories_are_read_however_amazon_lists_them(self):
        self.assertEqual(["Kitchen", "Dining"], [i["title"] for i in relay.amazon_categories("Ordered", ["2 Kitchen and Dining items"])])
        self.assertEqual([("Electronics", 1), ("Tools", 1)], [(i["title"], i["quantity"]) for i in relay.amazon_categories("Ordered", ["5 Electronics, Tools, and other items"])])
        self.assertEqual([("Electronics", 3)], [(i["title"], i["quantity"]) for i in relay.amazon_categories("Ordered", ["3 Electronics items"])])
        self.assertEqual(["Camping Gear", "Outdoor Cooking"], [i["title"] for i in relay.amazon_categories("Ordered 2 items: Camping Gear, Outdoor Cooking, and more", [])])
        self.assertEqual([("Coffee", 2)], [(i["title"], i["quantity"]) for i in relay.amazon_categories("Shipped", ["3 items: 2 Coffee, 1 other"])])
        self.assertEqual([("Shoes", 1)], [(i["title"], i["quantity"]) for i in relay.amazon_categories("Shipped: 1 Shoes item", [])])
        self.assertEqual("discretionary", relay.amazon_need_by_words("Home Décor"))

    def test_only_an_order_s_or_a_shipment_s_own_email_is_read(self):
        for subject in ("Included with your Amazon order", "Delivery estimate update for your Amazon order \u200e#113-0000000-0000003\u200e"):
            self.assertIsNone(relay.parse_amazon_email(subject, "Order # 113-0000000-0000003"), subject)

    def test_amazon_s_categories_sort_themselves_where_they_say_enough(self):
        self.assertEqual({"Beverages": "essential", "Hair Care": "essential", "Baby Products": "essential", "Pet Supplies": "essential",
                          "Toys & Games": "discretionary", "Electronics": "discretionary", "Musical Instruments": "discretionary", "Hardware": None},
                         {c: relay.amazon_need_by_words(c) for c in ("Beverages", "Hair Care", "Baby Products", "Pet Supplies", "Toys & Games",
                                                                       "Electronics", "Musical Instruments", "Hardware")})

    """Reading Amazon's emails: what was ordered, what it cost, and the emails that are about nothing to keep."""

    def test_an_order_email_gives_its_items_prices_and_grand_total(self):
        parsed = relay.parse_amazon_email('Ordered: "Kirkland Signature Paper Towels..." and 1 more item', AMAZON_ORDERED_TEXT)
        self.assertEqual({"kind": "ordered", "orders": ["112-1234567-7654321"], "total": 113.63, "shipment_total": None,
                          "items": [{"title": "Kirkland Signature Paper Towels, 12 Big Rolls", "quantity": 1, "price": 24.99},
                                    {"title": "LEGO Star Wars X-Wing Starfighter Building Set", "quantity": 2, "price": 39.99}]}, parsed)

    def test_an_html_order_email_reads_the_same_once_its_tags_are_gone(self):
        text = relay.html_text(AMAZON_ORDERED_HTML)
        self.assertNotIn("color", text)
        parsed = relay.parse_amazon_email("Your Amazon.com order #113-7654321-1234567", text)
        self.assertEqual((["113-7654321-1234567"], 70.34), (parsed["orders"], parsed["total"]))
        self.assertEqual([{"title": "Purina Pro Plan Adult Dog Food, 35 lb", "quantity": 1, "price": 64.98}], parsed["items"])

    def test_a_shipment_email_gives_the_shipment_s_total_not_the_order_s(self):
        parsed = relay.parse_amazon_email('Shipped: "Bluey Family Home Playset"', AMAZON_SHIPPED_TEXT)
        self.assertEqual(("shipped", None, 32.47), (parsed["kind"], parsed["total"], parsed["shipment_total"]))
        self.assertEqual(["Bluey Family Home Playset"], [i["title"] for i in parsed["items"]])

    def test_without_a_quantity_the_subject_s_title_is_the_item(self):
        parsed = relay.parse_amazon_email('Ordered: "Gorilla Glue Clear, 1.75 oz..."', "Order #112-0000000-0000001\nArriving tomorrow")
        self.assertEqual([{"title": "Gorilla Glue Clear, 1.75 oz...", "quantity": 1, "price": None}], parsed["items"])
        self.assertIsNone(parsed["total"])

    def test_deliveries_refunds_and_emails_without_an_order_are_not_kept(self):
        self.assertIsNone(relay.parse_amazon_email("Delivered: your package", "Order #112-1234567-7654321"))
        self.assertIsNone(relay.parse_amazon_email("Your refund for Bluey Playset", "Order #112-1234567-7654321\nRefund total: $32.47"))
        self.assertIsNone(relay.parse_amazon_email('Ordered: "Something"', "Deals picked for you"))
        # Wherever the subject says so, not only at its start...
        self.assertIsNone(relay.parse_amazon_email("Your Amazon.com order #112-1234567-7654321 has been delivered", "Order #112-1234567-7654321"))
        self.assertIsNone(relay.parse_amazon_email("Your Amazon.com order #112-1234567-7654321 was cancelled", "Order #112-1234567-7654321"))
        # ...but an item called that is still an order.
        self.assertEqual("ordered", relay.parse_amazon_email('Ordered: "Return to Oz (Blu-ray)"', "Order #112-0000000-0000009")["kind"])

    def test_which_charges_are_orders(self):
        self.assertTrue(relay.is_amazon_order_charge("AMZN Mktp US*2K1AB3CD4", "Amazon"))
        self.assertTrue(relay.is_amazon_order_charge("Amazon.com*RT4YU8", None))
        for name in ("Amazon Prime*1A2B3", "Prime Video*Channels", "AMAZON WEB SERVICES", "Audible*AB12", "Kindle Unltd*12", "Kindle Svcs*AB12", "COSTCO WHSE #0123"):
            self.assertFalse(relay.is_amazon_order_charge(name, None), name)

    def test_the_word_lists_sort_what_they_know_and_leave_the_rest(self):
        self.assertEqual("essential", relay.amazon_need_by_words("Charmin Ultra Soft Toilet Paper, 24 Mega Rolls"))
        self.assertEqual("discretionary", relay.amazon_need_by_words("LEGO Star Wars X-Wing Starfighter"))
        self.assertEqual("essential", relay.amazon_need_by_words("Purina Pro Plan Adult Dog Food, 35 lb"))
        self.assertIsNone(relay.amazon_need_by_words("Men's Crew Neck T-Shirt, 3 Pack"))


class AmazonMatchTest(unittest.TestCase):
    """Which order each Amazon charge paid for."""

    def order(self, order_id, date, total=None, shipments=()):
        return {"id": order_id, "date": date, "total": total, "shipments": list(shipments)}

    def test_a_charge_of_the_order_s_total_pays_for_it_and_the_nearest_wins(self):
        found = relay.amazon_match([("early", "2026-10-03", 113.63), ("late", "2026-10-20", 113.63)], [self.order("A", "2026-10-02", 113.63)])
        self.assertEqual({"early": {"order": "A", "match": "order"}}, found)

    def test_a_shipment_s_total_finds_its_charge_before_any_order_does(self):
        found = relay.amazon_match([("c", "2026-10-04", 32.47)], [self.order("A", "2026-10-01", 32.47), self.order("B", "2026-10-01", None, [("2026-10-03", 32.47)])])
        self.assertEqual({"c": {"order": "B", "match": "shipment"}}, found)

    def test_an_order_charged_shipment_by_shipment_is_found_by_its_charges_adding_up(self):
        charges = [("one", "2026-10-02", 20.0), ("two", "2026-10-06", 30.0), ("other", "2026-10-04", 7.0)]
        found = relay.amazon_match(charges, [self.order("A", "2026-10-01", 50.0)])
        self.assertEqual({"one": {"order": "A", "match": "split"}, "two": {"order": "A", "match": "split"}}, found)

    def test_a_charge_long_after_the_order_or_before_it_is_not_it(self):
        found = relay.amazon_match([("before", "2026-09-20", 10.0), ("after", "2026-11-15", 10.0)], [self.order("A", "2026-10-01", 10.0)])
        self.assertEqual({}, found)

    def test_each_charge_pays_for_one_order(self):
        found = relay.amazon_match([("c", "2026-10-02", 10.0)], [self.order("A", "2026-10-01", 10.0), self.order("B", "2026-10-02", 10.0)])
        self.assertEqual(1, len(found))

    def test_a_charge_goes_to_the_shipment_nearest_it_whichever_order_comes_first(self):
        orders = [self.order("A", "2026-10-01", None, [("2026-10-02", 10.0)]), self.order("B", "2026-10-03", None, [("2026-10-05", 10.0)])]
        self.assertEqual({"c": {"order": "B", "match": "shipment"}}, relay.amazon_match([("c", "2026-10-05", 10.0)], orders))
        orders = [self.order("A", "2026-10-01", 10.0), self.order("B", "2026-10-09", 10.0)]
        self.assertEqual({"c": {"order": "B", "match": "order"}}, relay.amazon_match([("c", "2026-10-10", 10.0)], orders))

    def test_one_charge_for_several_shipments_is_matched_to_their_sum(self):
        orders = [self.order("A", "2026-10-25", None, [("2026-10-27", 88.20), ("2026-10-27", 7.35)]), self.order("B", "2026-10-27", None, [("2026-10-27", 41.50)])]
        self.assertEqual({"c": {"order": "A", "match": "shipment", "parts": {"A": 9555}}}, relay.amazon_match([("c", "2026-10-27", 95.55)], orders))
        # Across orders when it must, the order paid most toward named first.
        found = relay.amazon_match([("c", "2026-10-27", 129.70)], orders)
        self.assertEqual(("A", {"A": 8820, "B": 4150}), (found["c"]["order"], found["c"]["parts"]))

    def test_shipments_that_add_up_go_to_the_charge_nearest_them(self):
        orders = [self.order("A", "2026-10-01", None, [("2026-10-05", 10.0), ("2026-10-05", 20.0)])]
        found = relay.amazon_match([("early", "2026-10-03", 30.0), ("same", "2026-10-05", 30.0)], orders)
        self.assertEqual(["same"], list(found))

    def test_one_order_s_three_shipments_beat_two_orders_two(self):
        orders = [self.order("A", "2026-10-01", None, [("2026-10-05", 7.0), ("2026-10-05", 8.0), ("2026-10-05", 9.0)]),
                  self.order("B", "2026-10-01", None, [("2026-10-05", 11.0)]), self.order("C", "2026-10-01", None, [("2026-10-05", 13.0)])]
        self.assertEqual({"A": 2400}, relay.amazon_match([("c", "2026-10-05", 24.0)], orders)["c"]["parts"])

    def test_what_a_matched_shipment_leaves_owing_is_matched_too(self):
        charges = [("first", "2026-10-02", 20.0), ("rest", "2026-10-06", 30.0)]
        found = relay.amazon_match(charges, [self.order("A", "2026-10-01", 50.0, [("2026-10-02", 20.0)])])
        self.assertEqual({"first": {"order": "A", "match": "shipment"}, "rest": {"order": "A", "match": "split"}}, found)
        charges = [("first", "2026-10-02", 20.0), ("two", "2026-10-06", 10.0), ("three", "2026-10-07", 20.0)]
        found = relay.amazon_match(charges, [self.order("A", "2026-10-01", 50.0, [("2026-10-02", 20.0)])])
        self.assertEqual({"first": "shipment", "two": "split", "three": "split"}, {k: v["match"] for k, v in found.items()})

    def test_the_shares_add_up_to_the_charge_and_an_item_without_a_price_still_weighs(self):
        thirds = relay.amazon_split(1.0, [{"price": 1.0, "quantity": 1, "need": n} for n in ("essential", "discretionary", None)])
        self.assertEqual(1.0, round(sum(thirds.values()), 2))
        self.assertEqual([0.33, 0.33, 0.34], sorted(thirds.values()))
        # The unpriced want weighs what the priced need does, so it isn't all essential.
        self.assertEqual({"essential": 5.0, "discretionary": 5.0, "unsorted": 0.0},
                         relay.amazon_split(10.0, [{"price": 10.0, "quantity": 1, "need": "essential"}, {"price": None, "quantity": 1, "need": "discretionary"}]))

    def test_a_charge_is_shared_out_by_its_order_s_item_prices(self):
        items = [{"price": 24.99, "quantity": 1, "need": "essential"}, {"price": 39.99, "quantity": 2, "need": "discretionary"}]
        self.assertEqual({"essential": 27.05, "discretionary": 86.58, "unsorted": 0.0}, relay.amazon_split(113.63, items))
        self.assertEqual({"essential": 5.0, "discretionary": 0.0, "unsorted": 5.0},
                         relay.amazon_split(10.0, [{"price": None, "quantity": 1, "need": "essential"}, {"price": None, "quantity": 1, "need": None}]))
        self.assertEqual({"essential": 0.0, "discretionary": 0.0, "unsorted": 12.0}, relay.amazon_split(12.0, []))


def _gmail_message(message_id, subject, plain=None, markup=None, at="2026-10-03T15:00:00+00:00"):
    import base64

    def part(mime, text):
        return {"mimeType": mime, "body": {"data": base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")}}

    parts = [part("text/plain", plain)] if plain is not None else []
    parts += [part("text/html", markup)] if markup is not None else []
    return {"id": message_id, "internalDate": str(int(relay.datetime.fromisoformat(at).timestamp() * 1000)),
            "payload": {"mimeType": "multipart/alternative", "headers": [{"name": "Subject", "value": subject}], "parts": parts}}


class AmazonBudgetTest(_Budget):
    """Amazon's emails read from Gmail, kept, sorted, and set beside the month's Amazon charges."""

    NAMES = _Budget.NAMES + ("gmail_get", "AMAZON_GMAIL_CLIENT_ID", "AMAZON_GMAIL_CLIENT_SECRET", "AMAZON_GMAIL_TOKENS", "amazon_ask_model", "AMAZON_FETCH_GAP")

    def setUp(self):
        super().setUp()
        relay.AMAZON_FETCH_GAP = 0
        relay.AMAZON_GMAIL_CLIENT_ID, relay.AMAZON_GMAIL_CLIENT_SECRET, relay.AMAZON_GMAIL_TOKENS = "client", "secret", {"sam": "refresh"}
        self.mail = {"o1": _gmail_message("o1", 'Ordered: "Kirkland Signature Paper Towels..." and 1 more item', plain=AMAZON_ORDERED_TEXT),
                     "o2": _gmail_message("o2", "Your Amazon.com order #113-7654321-1234567", markup=AMAZON_ORDERED_HTML, at="2026-10-01T15:00:00+00:00"),
                     "s1": _gmail_message("s1", 'Shipped: "Bluey Family Home Playset"', plain=AMAZON_SHIPPED_TEXT, at="2026-10-04T15:00:00+00:00"),
                     "d1": _gmail_message("d1", "Delivered: your package", plain="Order #112-1234567-7654321")}
        self.fetched = []

        def gmail(mailbox, path, now, **params):
            if path == "/messages":
                return {"messages": [{"id": m} for m in self.mail]}
            self.fetched.append(path.rsplit("/", 1)[1])
            return self.mail[path.rsplit("/", 1)[1]]

        relay.gmail_get = gmail
        self.asked = []
        relay.amazon_ask_model = lambda titles: (self.asked.append(titles), ["discretionary"] * len(titles))[1]

    def amazon(self):
        return {t["id"]: t.get("amazon", "absent") for t in self.month()["transactions"]}

    def test_each_email_is_read_once_and_its_items_sorted(self):
        relay.amazon_sync(self.NOW)
        self.assertEqual(["o1", "o2", "s1", "d1"], self.fetched)
        relay.amazon_sync(self.NOW)
        self.assertEqual(4, len(self.fetched))
        items = relay.with_db(lambda c: c.execute("SELECT order_id, title, need, need_by FROM amazon_items ORDER BY order_id, pos").fetchall())
        self.assertEqual([("112-1234567-7654321", "Kirkland Signature Paper Towels, 12 Big Rolls", "essential", "rule"),
                          ("112-1234567-7654321", "LEGO Star Wars X-Wing Starfighter Building Set", "discretionary", "rule"),
                          ("113-7654321-1234567", "Purina Pro Plan Adult Dog Food, 35 lb", "essential", "rule"),
                          ("114-1111111-2222222", "Bluey Family Home Playset", "discretionary", "model")], items)
        self.assertEqual([["Bluey Family Home Playset"]], self.asked)
        self.assertEqual([("114-1111111-2222222", "2026-10-04", 32.47)], relay.with_db(lambda c: c.execute("SELECT order_id, shipped, total FROM amazon_shipments").fetchall()))

    def test_amazon_s_charges_say_what_was_ordered_and_how_much_was_essential(self):
        relay.amazon_sync(self.NOW)
        self.store(_txn("big", 113.63, date="2026-10-05", name="AMZN Mktp US*2K1AB3CD4", merchant="Amazon"),
                   _txn("dog", 70.34, date="2026-10-02", name="Amazon.com*RT4YU8", merchant="Amazon"),
                   _txn("toy", 32.47, date="2026-10-05", name="AMZN Mktp US*9Z8Y7X", merchant="Amazon"),
                   _txn("mystery", 15.0, date="2026-10-06", name="AMZN Mktp US*111", merchant="Amazon"),
                   _txn("prime", 14.99, date="2026-10-01", name="Amazon Prime*1A2B3", merchant="Amazon Prime"),
                   _txn("costco", 80.0))
        found = self.amazon()
        self.assertEqual(("absent", "absent", None), (found["prime"], found["costco"], found["mystery"]))
        self.assertEqual(("112-1234567-7654321", "order", 27.05, 86.58), tuple(found["big"][k] for k in ("order", "match", "essential", "discretionary")))
        self.assertEqual(("order", 70.34), (found["dog"]["match"], found["dog"]["essential"]))
        self.assertEqual(("shipment", 32.47), (found["toy"]["match"], found["toy"]["discretionary"]))
        summary = self.month()["amazon"]
        self.assertEqual({"configured": True, "read_at": self.NOW, "failed": [], "charges": 4, "essential": 97.39, "discretionary": 119.05, "unsorted": 0.0, "unmatched": 15.0}, summary)

    def test_an_item_said_by_hand_stays_said_and_taking_it_back_sorts_it_again(self):
        relay.amazon_sync(self.NOW)
        self.store(_txn("big", 113.63, date="2026-10-05", name="AMZN Mktp US*2K1AB3CD4", merchant="Amazon"))
        relay.put_amazon_item("112-1234567-7654321", 1, types.SimpleNamespace(need="essential", title=None), object(), self.response)
        self.assertEqual((113.63, "user:alex"), (self.amazon()["big"]["essential"], self.amazon()["big"]["items"][1]["need_by"]))
        # A later email about the same order keeps it.
        relay.amazon_store("sam", "o1-again", "2026-10-03", relay.parse_amazon_email("Ordered", AMAZON_ORDERED_TEXT), self.NOW)
        self.assertEqual("user:alex", self.amazon()["big"]["items"][1]["need_by"])
        relay.put_amazon_item("112-1234567-7654321", 1, types.SimpleNamespace(need=None, title="LEGO Star Wars X-Wing Starfighter Building Set"), object(), self.response)
        relay.amazon_sort_items()
        self.assertEqual(("discretionary", "rule"), tuple(self.amazon()["big"]["items"][1][k] for k in ("need", "need_by")))
        with self.assertRaises(relay.HTTPException) as bad:
            relay.put_amazon_item("112-1234567-7654321", 1, types.SimpleNamespace(need="fun", title=None), object(), self.response)
        self.assertEqual(400, bad.exception.status_code)
        with self.assertRaises(relay.HTTPException) as missing:
            relay.put_amazon_item("112-1234567-7654321", 9, types.SimpleNamespace(need="essential", title=None), object(), self.response)
        self.assertEqual(404, missing.exception.status_code)

    def test_a_mailbox_that_fails_is_named_and_the_model_being_away_leaves_items_unsorted(self):
        def broken(mailbox, path, now, **params):
            raise RuntimeError("Gmail answered 401 for messages")

        relay.gmail_get = broken
        relay.amazon_sync(self.NOW)
        self.assertEqual(["sam"], self.month()["amazon"]["failed"])
        relay.amazon_ask_model = lambda titles: None
        relay.amazon_store("sam", "s1", "2026-10-04", relay.parse_amazon_email('Shipped: "Bluey Family Home Playset"', AMAZON_SHIPPED_TEXT), self.NOW)
        relay.amazon_sort_items()
        self.assertEqual([(None, None)], relay.with_db(lambda c: c.execute("SELECT need, need_by FROM amazon_items").fetchall()))

    def test_a_new_way_of_reading_reads_every_email_again_and_keeps_what_someone_said(self):
        relay.amazon_sync(self.NOW)
        relay.with_db(lambda c: (c.execute("UPDATE amazon_items SET need='essential', need_by='user:alex' WHERE title LIKE 'LEGO%'"), c.commit()))
        relay.state_set(relay.AMAZON_PARSER_KEY, relay.AMAZON_PARSER - 1)
        relay.amazon_sync(self.NOW + 3600)
        self.assertEqual(8, len(self.fetched))
        self.assertEqual([("essential", "user:alex")], relay.with_db(lambda c: c.execute("SELECT need, need_by FROM amazon_items WHERE title LIKE 'LEGO%'").fetchall()))
        relay.amazon_sync(self.NOW + 7200)
        self.assertEqual(8, len(self.fetched))

    def test_a_charge_for_two_orders_lists_both_and_shares_out_by_each(self):
        relay.amazon_store("sam", "o1", "2026-10-03", relay.parse_amazon_email("Ordered", AMAZON_ORDERED_TEXT), self.NOW)
        relay.amazon_store("sam", "s1", "2026-10-04", relay.parse_amazon_email('Shipped: "Bluey Family Home Playset"', AMAZON_SHIPPED_TEXT), self.NOW)
        relay.amazon_store("sam", "s2", "2026-10-04", relay.parse_amazon_email("Shipped", AMAZON_SHIPPED_TEXT.replace("114-1111111-2222222", "112-1234567-7654321").replace("Bluey Family Home Playset", "Kirkland Signature Paper Towels, 12 Big Rolls").replace("32.47", "50.00")), self.NOW)
        relay.amazon_sort_items()
        self.store(_txn("both", 82.47, date="2026-10-05", name="AMZN Mktp US*2K1AB3CD4", merchant="Amazon"))
        found = self.amazon()["both"]
        self.assertEqual((["112-1234567-7654321", "114-1111111-2222222"], "112-1234567-7654321"), (found["orders"], found["order"]))
        self.assertEqual({"112-1234567-7654321", "114-1111111-2222222"}, {i["order"] for i in found["items"]})
        self.assertEqual(82.47, round(found["essential"] + found["discretionary"] + found["unsorted"], 2))
        self.assertEqual(32.47, round(found["discretionary"] - 50.0 * 2 * 39.99 / 104.97, 2))

    def test_the_model_is_asked_one_at_a_time_when_it_miscounts_a_batch(self):
        relay.amazon_store("sam", "s1", "2026-10-04", relay.parse_amazon_email("Shipped 2 items: Plumbing, Tools", "Order #114-1111111-2222222\n2 items: 1 Plumbing, 1 Tools\nTotal\n$20.00"), self.NOW)
        asked = []

        def ask(titles):
            asked.append(list(titles))
            return None if len(titles) > 1 else ["essential"]

        relay.amazon_sort_items(ask)
        self.assertEqual([["Plumbing", "Tools"], ["Plumbing"], ["Tools"]], asked)
        self.assertEqual([("essential", "model")] * 2, relay.with_db(lambda c: c.execute("SELECT need, need_by FROM amazon_items").fetchall()))

    def test_off_without_a_mailbox(self):
        relay.AMAZON_GMAIL_TOKENS = {}
        relay.amazon_sync(self.NOW)
        self.assertEqual([], self.fetched)
        self.store(_txn("big", 113.63, date="2026-10-05", name="AMZN Mktp US*2K1AB3CD4", merchant="Amazon"))
        self.assertEqual({"big": "absent"}, self.amazon())
        self.assertEqual((False, 0), (self.month()["amazon"]["configured"], self.month()["amazon"]["charges"]))

    def test_shipment_emails_add_their_items_until_the_order_s_own_email_is_read(self):
        one = relay.parse_amazon_email('Shipped: "Bluey Family Home Playset"', AMAZON_SHIPPED_TEXT)
        two = relay.parse_amazon_email('Shipped: "Crayons"', AMAZON_SHIPPED_TEXT.replace("Bluey Family Home Playset", "Crayola Crayons, 64 Count"))
        relay.amazon_store("sam", "s1", "2026-10-04", one, self.NOW)
        relay.amazon_store("sam", "s2", "2026-10-05", two, self.NOW)
        titles = lambda: [row[0] for row in relay.with_db(lambda c: c.execute("SELECT title FROM amazon_items ORDER BY pos").fetchall())]
        self.assertEqual(["Bluey Family Home Playset", "Crayola Crayons, 64 Count"], titles())
        ordered = relay.parse_amazon_email("Ordered", AMAZON_ORDERED_TEXT.replace("112-1234567-7654321", "114-1111111-2222222"))
        relay.amazon_store("sam", "o1", "2026-10-03", ordered, self.NOW)
        relay.amazon_store("sam", "s3", "2026-10-06", relay.parse_amazon_email('Shipped: "Late"', AMAZON_SHIPPED_TEXT.replace("Bluey Family Home Playset", "Something Else")), self.NOW)
        self.assertEqual(["Kirkland Signature Paper Towels, 12 Big Rolls", "LEGO Star Wars X-Wing Starfighter Building Set"], titles())

    def test_a_mailbox_with_more_than_a_read_s_worth_is_read_over_several(self):
        relay.AMAZON_MAX_EMAILS, saved = 2, relay.AMAZON_MAX_EMAILS
        try:
            pages = {None: (["o1", "o2"], "p2"), "p2": (["s1", "d1"], None)}

            def gmail(mailbox, path, now, **params):
                if path == "/messages":
                    ids, token = pages[params.get("pageToken")]
                    return {"messages": [{"id": m} for m in ids], **({"nextPageToken": token} if token else {})}
                self.fetched.append(path.rsplit("/", 1)[1])
                return self.mail[path.rsplit("/", 1)[1]]

            relay.gmail_get = gmail
            relay.amazon_sync(self.NOW)
            relay.amazon_sync(self.NOW + 3600)
            self.assertEqual(["o1", "o2", "s1", "d1"], self.fetched)
        finally:
            relay.AMAZON_MAX_EMAILS = saved

    def test_a_verdict_made_for_an_item_isn_t_kept_for_another_put_in_its_place(self):
        relay.amazon_store("sam", "s1", "2026-10-04", relay.parse_amazon_email('Shipped: "Bluey Family Home Playset"', AMAZON_SHIPPED_TEXT), self.NOW)

        def ask(titles):
            # While the model thinks, the order's own email arrives and puts another item first.
            relay.amazon_store("sam", "o9", "2026-10-03", relay.parse_amazon_email('Ordered: "Men\'s Crew Neck T-Shirt"', "Order #114-1111111-2222222\nMen's Crew Neck T-Shirt\nQuantity: 1\n$12.00"), self.NOW)
            return ["discretionary"] * len(titles)

        relay.amazon_sort_items(ask)
        self.assertEqual([("Men's Crew Neck T-Shirt", None)], relay.with_db(lambda c: c.execute("SELECT title, need FROM amazon_items").fetchall()))

    def test_an_order_whose_charges_straddle_the_month_is_matched_from_either_side(self):
        relay.amazon_store("sam", "o1", "2026-09-29", relay.parse_amazon_email("Ordered", AMAZON_ORDERED_TEXT), self.NOW)
        self.store(_txn("sep", 50.0, date="2026-09-30", name="AMZN Mktp US*1", merchant="Amazon"), _txn("oct", 63.63, date="2026-10-01", name="AMZN Mktp US*2", merchant="Amazon"))
        self.assertEqual("split", next(t for t in relay.budget_month("2026-09", self.NOW)["transactions"] if t["id"] == "sep")["amazon"]["match"])
        self.assertEqual("split", self.amazon()["oct"]["match"])

    def test_an_item_said_about_is_the_one_the_phone_saw(self):
        relay.amazon_sync(self.NOW)
        self.store(_txn("big", 113.63, date="2026-10-05", name="AMZN Mktp US*2K1AB3CD4", merchant="Amazon"))
        with self.assertRaises(relay.HTTPException) as moved:
            relay.put_amazon_item("112-1234567-7654321", 1, types.SimpleNamespace(need="essential", title="Kirkland Signature Paper Towels, 12 Big Rolls"), object(), self.response)
        self.assertEqual((409, "item_changed"), (moved.exception.status_code, moved.exception.detail["error"]))
        self.assertEqual("rule", self.amazon()["big"]["items"][1]["need_by"])


class DeviceOwnerTest(_ScratchDb):
    """Whose phone a registration says it is: what a push about the money goes by."""

    NAMES = _ScratchDb.NAMES + ("FINANCE_USERS",)

    def setUp(self):
        super().setUp()
        self._get = relay.requests.get
        self.profile = {"username": "alex", "role": "admin"}
        relay.requests.get = lambda url, headers=None, timeout=None: _Response(200, self.profile)

    def tearDown(self):
        relay.requests.get = self._get
        super().tearDown()

    def register(self, **headers):
        device = types.SimpleNamespace(device_id="d1", token="token-1", platform="android", name="Pixel", quiet_familiar=False, build="release",
                                       quiet_start=None, quiet_end=None, only_away=False, tz="America/Los_Angeles", utc_offset=-420,
                                       hardware_id="hw-1", emulator=False)
        return relay.register(device, types.SimpleNamespace(headers=headers))

    def owner(self):
        return relay.with_db(lambda c: c.execute("SELECT user, role FROM devices WHERE device_id='d1'").fetchone())

    def test_a_registration_with_a_session_says_whose_phone_it_is(self):
        self.register(cookie="frigate_token=abc")
        self.assertEqual(("alex", "admin"), self.owner())
        self.assertEqual([("d1", "token-1")], relay.budget_recipients(time.time()))

    def test_one_made_with_the_install_s_own_secret_leaves_that_as_it_stood(self):
        secret = self.register(cookie="frigate_token=abc")["secret"]
        self.profile = None
        self.register(authorization=f"Bearer {secret}")
        self.assertEqual(("alex", "admin"), self.owner())

    def test_a_session_is_asked_of_frigate_once_a_registration(self):
        asked = []
        relay.requests.get = lambda url, headers=None, timeout=None: asked.append(url) or _Response(200, self.profile)
        secret = self.register(cookie="frigate_token=abc")["secret"]
        self.register(cookie="frigate_token=abc", authorization=f"Bearer {secret}")
        self.assertEqual(2, len(asked))

    def test_a_registration_nobody_vouches_for_is_refused(self):
        relay.requests.get = lambda url, headers=None, timeout=None: _Response(401, {})
        with self.assertRaises(relay.HTTPException) as refused:
            self.register(cookie="frigate_token=stale")
        self.assertEqual(401, refused.exception.status_code)
        with self.assertRaises(relay.HTTPException):
            self.register(authorization="Bearer made-up")
        self.assertIsNone(relay.find_device("d1"))

    def test_someone_else_signing_in_on_the_phone_takes_it_over(self):
        self.register(cookie="frigate_token=abc")
        self.profile = {"username": "kid", "role": "viewer"}
        self.register(cookie="frigate_token=def")
        self.assertEqual(("kid", "viewer"), self.owner())
        self.assertEqual([], relay.budget_recipients(time.time()))


def _iso(epoch, offset_minutes=0):
    """An export's timestamp: the wall clock [offset_minutes] ahead of UTC, with that offset written out."""
    from datetime import datetime, timedelta, timezone

    return datetime.fromtimestamp(epoch, timezone(timedelta(minutes=offset_minutes))).isoformat(timespec="milliseconds")


# Made-up places: nobody's home is at 40, -89.
HOME, WORK, CAFE = (40.0, -89.0), (40.05, -89.02), (40.01, -89.005)
DAY1 = 1_700_006_400  # 2023-11-15 00:00 UTC
CST = -360


def android_visit(start, end, at, place, kind="UNKNOWN", level=0, offset=CST):
    return {
        "startTime": _iso(start, offset), "endTime": _iso(end, offset), "startTimeTimezoneUtcOffsetMinutes": offset,
        "visit": {"hierarchyLevel": level, "probability": 0.8, "topCandidate": {
            "placeId": place, "semanticType": kind, "probability": 0.7, "placeLocation": {"latLng": f"{at[0]}°, {at[1]}°"}}},
    }


def android_activity(start, end, a, b, kind="IN_PASSENGER_VEHICLE", metres=6000.0, offset=CST):
    return {
        "startTime": _iso(start, offset), "endTime": _iso(end, offset),
        "activity": {"start": {"latLng": f"{a[0]}°, {a[1]}°"}, "end": {"latLng": f"{b[0]}°, {b[1]}°"}, "distanceMeters": metres, "topCandidate": {"type": kind, "probability": 0.9}},
    }


def android_path(start, points, offset=CST):
    return {
        "startTime": _iso(start, offset), "endTime": _iso(start + 7200, offset),
        "timelinePath": [{"point": f"{lat}°, {lon}°", "time": _iso(start + seconds, offset)} for seconds, lat, lon in points],
    }


def android_export(day=DAY1, cafe="ChIJcafe"):
    """One made-up day: home, a drive to work with its path, work, a walk to a café, the café."""
    t = day + 6 * 3600
    return {
        "semanticSegments": [
            android_visit(t, t + 3600, HOME, "ChIJhome", "INFERRED_HOME"),
            android_activity(t + 3600, t + 5400, HOME, WORK),
            android_path(t + 3600, [(0, *HOME), (600, 40.02, -89.01), (600, 40.02, -89.01), (1800, *WORK)]),
            android_visit(t + 5400, t + 30000, WORK, "ChIJwork", "WORK"),
            android_visit(t + 6000, t + 7000, WORK, "ChIJdesk", "UNKNOWN", level=1),
            android_activity(t + 30000, t + 30600, WORK, CAFE, "WALKING", 0),
            android_visit(t + 30600, t + 33000, CAFE, cafe),
        ],
        "rawSignals": [{"position": {"LatLng": "40.0°, -89.0°", "accuracyMeters": 13, "timestamp": _iso(t)}}],
        "userLocationProfile": {"frequentPlaces": [{"placeId": "ChIJhome", "placeLocation": "40.0°, -89.0°", "label": "HOME"}]},
    }


class _Text:
    """A file that hands its text over a few characters at a time, so every value straddles a read."""

    def __init__(self, text, size):
        self.text, self.size, self.at = text, size, 0

    def read(self, _=None):
        piece = self.text[self.at:self.at + self.size]
        self.at += self.size
        return piece


class TimelineReading(unittest.TestCase):
    def test_a_document_is_read_a_piece_at_a_time_whatever_the_read_size(self):
        document = {"a": [{"x": 1.25}, {"y": "]}\"", "z": [1, 2]}], "n": 1234567, "t": "text", "o": {"k": [1]}, "b": [], "c": [true := True, None, 12]}
        del true
        want = [("a", {"x": 1.25}), ("a", {"y": "]}\"", "z": [1, 2]}), ("n", 1234567), ("t", "text"), ("o", {"k": [1]}), ("c", True), ("c", None), ("c", 12)]
        for size in (1, 2, 3, 7, 64, 1 << 20):
            self.assertEqual(want, list(relay.json_items(_Text(json.dumps(document), size))), size)
            self.assertEqual(want, list(relay.json_items(_Text(json.dumps(document, indent=2), size))), size)

    def test_a_bare_array_and_an_empty_file(self):
        self.assertEqual([(None, {"a": 1}), (None, 2)], list(relay.json_items(_Text('[{"a": 1}, 2]', 3))))
        self.assertEqual([], list(relay.json_items(_Text("", 3))))
        self.assertEqual([], list(relay.json_items(_Text("{}", 3))))

    def test_a_file_cut_short_raises_rather_than_passing_for_whole(self):
        with self.assertRaises(ValueError):
            list(relay.json_items(_Text('{"a": [{"x": 1}, {"y": ', 4)))

    def test_the_polyline_is_googles(self):
        # Google's own worked example.
        self.assertEqual("_p~iF~ps|U_ulLnnqC_mqNvxq`@", relay.polyline([(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]))
        self.assertEqual("", relay.polyline([]))
        self.assertEqual("?_@}@~@", relay.polyline_numbers([0, 16, 47, 15]))

    def test_a_point_however_it_was_written(self):
        self.assertEqual((41.88, -87.63), relay.timeline_point("41.88°, -87.63°"))
        self.assertEqual((41.88, -87.63), relay.timeline_point("geo:41.88,-87.63"))
        self.assertEqual((41.88, -87.63), relay.timeline_point({"latLng": "41.88°, -87.63°"}))
        self.assertEqual((41.88, -87.63), relay.timeline_point({"latitudeE7": 418800000, "longitudeE7": -876300000}))
        self.assertEqual((41.88, -87.63), relay.timeline_point({"latE7": 418800000, "lngE7": -876300000}))
        for nowhere in (None, "", "somewhere", "0°, 0°", "91°, 10°", {"latitudeE7": None, "longitudeE7": None}, {}, 12):
            self.assertIsNone(relay.timeline_point(nowhere), nowhere)

    def test_a_time_keeps_the_offset_the_export_wrote(self):
        self.assertEqual((DAY1, -360), relay.timeline_time("2023-11-14T18:00:00.000-06:00"))
        self.assertEqual((DAY1, 0), relay.timeline_time("2023-11-15T00:00:00.000+00:00", relay.ZoneInfo("America/Chicago")))

    def test_a_time_with_no_offset_takes_the_households(self):
        chicago = relay.ZoneInfo("America/Chicago")
        self.assertEqual((DAY1, -360), relay.timeline_time("2023-11-15T00:00:00Z", chicago))
        self.assertEqual((DAY1, -360), relay.timeline_time(str(DAY1 * 1000), chicago))
        self.assertEqual((DAY1, 0), relay.timeline_time("2023-11-15T00:00:00Z"))
        # Summer: the same zone, an hour nearer UTC.
        self.assertEqual(-300, relay.timeline_time("2023-07-01T12:00:00Z", chicago)[1])
        self.assertIsNone(relay.timeline_time("yesterday"))
        self.assertIsNone(relay.timeline_time(None))

    def test_modes_and_kinds_across_the_exports(self):
        for written, mode in (("IN_PASSENGER_VEHICLE", "drive"), ("in passenger vehicle", "drive"), ("WALKING", "walk"), ("walking", "walk"),
                              ("IN_TRAIN", "train"), ("FLYING", "fly"), ("CYCLING", "cycle"), ("IN_BUS", "bus"), ("UNKNOWN_ACTIVITY_TYPE", "other"), (None, "other")):
            self.assertEqual(mode, relay.timeline_mode(written), written)
        for written, kind in (("HOME", "home"), ("INFERRED_HOME", "home"), ("TYPE_WORK", "work"), ("Inferred_Work", "work"), ("UNKNOWN", "other"), ("SEARCHED_ADDRESS", "other"), (None, "other")):
            self.assertEqual(kind, relay.timeline_kind(written), written)


class _TimelineDb(_ScratchDb):
    NAMES = _ScratchDb.NAMES + ("TIMELINE_DB", "TIMELINE_PLACES_KEY", "TIMELINE_PLACES_MONTHLY", "TIMELINE_DRIVE_FOLDER", "TIMELINE_USERS", "timeline_place_lookup", "timeline_google", "frigate_profile")

    def setUp(self):
        super().setUp()
        relay.TIMELINE_DB = os.path.join(self._dir.name, "timeline.db")
        self.conn = relay.timeline_db()

    def tearDown(self):
        self.conn.close()
        super().tearDown()

    def write(self, name, document):
        path = os.path.join(self._dir.name, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(document, f)
        return path

    def read(self, document, zone=None):
        stage = relay.TimelineStage(self.conn, zone)
        relay.timeline_read(_Text(json.dumps(document), 97), stage)
        return relay.timeline_ingest(self.conn, stage, now=1000.0)

    def rows(self, sql):
        return self.conn.execute(sql).fetchall()


class TimelineExports(_TimelineDb):
    def test_the_phones_export_becomes_visits_moves_and_a_path(self):
        summary = self.read(android_export())
        t = DAY1 + 6 * 3600
        self.assertEqual({"visits": 3, "moves": 2, "paths": 1, "points": 3, "days": 1, "years_changed": [2023]}, summary)
        self.assertEqual(
            [(t, t + 3600, -360, 40.0, -89.0, "ChIJhome", "home"), (t + 5400, t + 30000, -360, 40.05, -89.02, "ChIJwork", "work"), (t + 30600, t + 33000, -360, 40.01, -89.005, "ChIJcafe", "other")],
            self.rows("SELECT started, ended, utc_offset, lat, lon, place, kind FROM visits ORDER BY started"),
        )
        drive, walk = self.rows("SELECT mode, metres FROM moves ORDER BY started")
        self.assertEqual(("drive", 6000.0), drive)
        # A move the export gave no distance for is as the crow flies.
        self.assertEqual("walk", walk[0])
        self.assertAlmostEqual(relay.haversine_metres(WORK, CAFE), walk[1], delta=0.1)
        (started, points, times, n), = self.rows("SELECT started, points, times, n FROM paths")
        self.assertEqual((t + 3600, 3), (started, n))
        self.assertEqual(relay.polyline([HOME, (40.02, -89.01), WORK]), points)
        self.assertEqual(relay.polyline_numbers([0, 600, 1800]), times)

    def test_the_clock_on_the_wall_is_the_offset_field_not_the_one_the_time_is_written_in(self):
        # Lived on Eastern time, exported from a phone now on Pacific: every time is written at
        # -07:00, and the field says where the clock was.
        t = DAY1 + 6 * 3600
        eastern, pacific = -240, -420
        visit = android_visit(t, t + 3600, HOME, "ChIJhome", "HOME", offset=pacific)
        visit["startTimeTimezoneUtcOffsetMinutes"] = visit["endTimeTimezoneUtcOffsetMinutes"] = eastern
        drive = android_activity(t + 3600, t + 5400, HOME, WORK, offset=pacific)
        drive["startTimeTimezoneUtcOffsetMinutes"] = eastern
        self.read({"semanticSegments": [visit, drive, android_path(t + 3600, [(0, *HOME), (1800, *WORK)], offset=pacific)]})
        self.assertEqual([(t, eastern)], self.rows("SELECT started, utc_offset FROM visits"))
        self.assertEqual([(t + 3600, eastern)], self.rows("SELECT started, utc_offset FROM moves"))
        # A stretch of path says nothing of its own: it takes the journey's it lies beside.
        self.assertEqual([(t + 3600, eastern)], self.rows("SELECT started, utc_offset FROM paths"))

    def test_the_iphones_export_is_a_bare_array_of_the_same(self):
        t = DAY1 + 6 * 3600
        export = [
            {"startTime": _iso(t, CST), "endTime": _iso(t + 3600, CST), "visit": {"hierarchyLevel": "0", "probability": "0.8", "topCandidate": {
                "placeID": "ChIJhome", "semanticType": "Home", "probability": "0.9", "placeLocation": "geo:40.000000,-89.000000"}}},
            {"startTime": _iso(t + 3600, CST), "endTime": _iso(t + 5400, CST), "activity": {
                "start": "geo:40.0,-89.0", "end": "geo:40.05,-89.02", "distanceMeters": "6000.5", "topCandidate": {"type": "in passenger vehicle", "probability": "0.9"}}},
            {"startTime": _iso(t + 3600, CST), "endTime": _iso(t + 5400, CST), "timelinePath": [
                {"point": "geo:40.0,-89.0", "durationMinutesOffsetFromStartTime": "0"}, {"point": "geo:40.05,-89.02", "durationMinutesOffsetFromStartTime": "30"}]},
        ]
        self.read(export)
        self.assertEqual([(t, "ChIJhome", "home", 0.9)], self.rows("SELECT started, place, kind, p FROM visits"))
        self.assertEqual([("drive", 6000.5)], self.rows("SELECT mode, metres FROM moves"))
        self.assertEqual([(t + 3600, t + 5400, 2)], self.rows("SELECT started, ended, n FROM paths"))

    def test_takeouts_monthly_file_brings_its_own_names(self):
        t = DAY1 + 12 * 3600
        export = {"timelineObjects": [
            {"placeVisit": {"location": {"latitudeE7": 400000000, "longitudeE7": -890000000, "placeId": "ChIJhome", "name": "The Old Mill", "address": "1 Mill Lane\nSpringfield", "semanticType": "TYPE_HOME"},
                            "duration": {"startTimestamp": _iso(t).replace("+00:00", "Z"), "endTimestamp": _iso(t + 3600).replace("+00:00", "Z")}, "placeConfidence": "HIGH_CONFIDENCE"}},
            {"activitySegment": {"startLocation": {"latitudeE7": 400000000, "longitudeE7": -890000000}, "endLocation": {"latitudeE7": 400500000, "longitudeE7": -890200000},
                                 "duration": {"startTimestampMs": str((t + 3600) * 1000), "endTimestampMs": str((t + 5400) * 1000)}, "distance": 6100, "activityType": "IN_BUS",
                                 "waypointPath": {"waypoints": [{"latE7": 400200000, "lngE7": -890100000}]}}},
        ]}
        self.read(export, relay.ZoneInfo("America/Chicago"))
        self.assertEqual([(t, -360, "home", 0.9)], self.rows("SELECT started, utc_offset, kind, p FROM visits"))
        self.assertEqual([("bus", 6100.0)], self.rows("SELECT mode, metres FROM moves"))
        # No times on the waypoints: the one between the ends lands halfway.
        (points, times), = self.rows("SELECT points, times FROM paths")
        self.assertEqual(relay.polyline([HOME, (40.02, -89.01), WORK]), points)
        self.assertEqual(relay.polyline_numbers([0, 900, 1800]), times)
        # A name to go by, and still to be asked of Google for its town and state.
        self.assertEqual([("The Old Mill", "1 Mill Lane, Springfield", None, "home")], self.rows("SELECT name, address, asked, kind FROM places"))

    def test_raw_fixes_are_cut_into_stays_and_the_trips_between(self):
        t = DAY1

        def fix(seconds, at, accuracy=10):
            return {"latitudeE7": round(at[0] * 1e7), "longitudeE7": round(at[1] * 1e7), "accuracy": accuracy, "timestamp": _iso(t + seconds).replace("+00:00", "Z")}

        fixes = [fix(i * 120, HOME) for i in range(10)]                                       # 18 minutes at home
        fixes += [fix(1200 + i * 60, (40.0 + 0.005 * (i + 1), -89.0 - 0.002 * (i + 1))) for i in range(9)]  # on the way
        fixes += [fix(1800 + i * 120, WORK) for i in range(10)]                                # 18 minutes at work
        fixes.insert(3, fix(300, (48.0, 2.0), accuracy=5000))                                 # a wild fix, not believed
        self.read({"locations": fixes})
        visits = self.rows("SELECT started, ended, lat, lon, place FROM visits ORDER BY started")
        self.assertEqual([(t, t + 1080, 40.0, -89.0, None), (t + 1800, t + 2880, 40.05, -89.02, None)], visits)
        (started, ended, mode, metres), = self.rows("SELECT started, ended, mode, metres FROM moves")
        self.assertEqual((t + 1080, t + 1800, "other"), (started, ended, mode))
        self.assertGreater(metres, 5000)
        self.assertEqual([(11,)], self.rows("SELECT n FROM paths"))

    def test_a_takeout_zip_is_read_whole_and_what_isnt_timeline_adds_nothing(self):
        import zipfile

        path = os.path.join(self._dir.name, "takeout.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Takeout/Timeline/Timeline.json", json.dumps(android_export()))
            archive.writestr("Takeout/Timeline/Settings.json", json.dumps({"createdTime": "2014", "devices": [{"tag": 1}]}))
            archive.writestr("Takeout/Timeline/Timeline Edits.json", json.dumps({"timelineEdits": [{"deviceId": "1"}]}))
            archive.writestr("Takeout/archive_browser.html", "<html>")
        summary = relay.timeline_import(path)
        self.assertEqual((3, 2, 1), (summary["visits"], summary["moves"], summary["paths"]))
        self.assertEqual("takeout.zip", relay.timeline_meta(self.conn, "file")["name"])

    def _fixes(self):
        def fix(seconds, at):
            return {"latitudeE7": round(at[0] * 1e7), "longitudeE7": round(at[1] * 1e7), "accuracy": 10, "timestamp": _iso(DAY1 + seconds).replace("+00:00", "Z")}

        return {"locations": [fix(i * 120, HOME) for i in range(10)] + [fix(1800 + i * 120, WORK) for i in range(10)]}

    def test_a_takeout_zip_with_the_days_twice_over_counts_them_once(self):
        import zipfile

        # The same day as Google's own visits and trips, and again as the raw fixes they were made from.
        path = os.path.join(self._dir.name, "takeout.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Takeout/Location History/Records.json", json.dumps(self._fixes()))
            archive.writestr("Takeout/Location History/Semantic Location History/2023/2023_NOVEMBER.json", json.dumps(android_export()))
        summary = relay.timeline_import(path)
        self.assertEqual((3, 2, 1), (summary["visits"], summary["moves"], summary["paths"]))
        self.assertEqual(0, self.rows("SELECT COUNT(*) FROM visits WHERE place IS NULL")[0][0])

    def test_a_takeout_zip_of_raw_fixes_alone_is_cut_into_stays(self):
        import zipfile

        path = os.path.join(self._dir.name, "takeout.zip")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Takeout/Location History/Records.json", json.dumps(self._fixes()))
            archive.writestr("Takeout/Location History/Settings.json", json.dumps({"createdTime": "2014"}))
        summary = relay.timeline_import(path)
        self.assertEqual((2, 1), (summary["visits"], summary["moves"]))


class TimelineHistory(_TimelineDb):
    def test_a_newer_export_rewrites_the_days_it_mentions_and_leaves_the_rest(self):
        self.read(android_export(DAY1))
        self.read(android_export(DAY1 + 86400))
        self.assertEqual(6, self.rows("SELECT COUNT(*) FROM visits")[0][0])
        # The first day again, the café corrected in Maps to the bakery next door; the second day isn't in this file.
        summary = self.read(android_export(DAY1, cafe="ChIJbakery"))
        self.assertEqual(1, summary["days"])
        self.assertEqual(6, self.rows("SELECT COUNT(*) FROM visits")[0][0])
        self.assertEqual(4, self.rows("SELECT COUNT(*) FROM moves")[0][0])
        self.assertEqual(2, self.rows("SELECT COUNT(*) FROM paths")[0][0])
        self.assertEqual(["ChIJbakery", "ChIJcafe", "ChIJhome", "ChIJwork"], [r[0] for r in self.rows("SELECT place FROM places ORDER BY place")])

    def test_a_place_no_visit_goes_to_any_more_is_forgotten(self):
        self.read(android_export(DAY1))
        self.read(android_export(DAY1, cafe="ChIJbakery"))
        self.assertEqual(["ChIJbakery", "ChIJhome", "ChIJwork"], [r[0] for r in self.rows("SELECT place FROM places ORDER BY place")])

    def test_an_export_cut_short_changes_nothing(self):
        self.read(android_export(DAY1))
        stage = relay.TimelineStage(self.conn)
        with self.assertRaises(ValueError):
            relay.timeline_read(_Text(json.dumps(android_export(DAY1, cafe="ChIJbakery"))[:-40], 97), stage)
        self.assertEqual([("ChIJcafe",)], self.rows("SELECT place FROM visits WHERE kind = 'other'"))

    def test_home_is_what_the_exports_profile_says(self):
        export = android_export()
        export["semanticSegments"][0]["visit"]["topCandidate"]["semanticType"] = "UNKNOWN"
        self.read(export)
        self.assertEqual([("home",)], self.rows("SELECT kind FROM places WHERE place = 'ChIJhome'"))

    def test_a_year_is_columns_with_places_by_index(self):
        self.read(android_export())
        body = relay.timeline_year_body(self.conn, 2023)
        self.assertEqual(["ChIJcafe", "ChIJhome", "ChIJwork"], body["places"])
        self.assertEqual([1, 2, 0], body["visits"]["place"])
        self.assertEqual(["home", "work", "other"], body["visits"]["kind"])
        self.assertEqual(["drive", "walk"], body["moves"]["mode"])
        self.assertEqual([3], body["paths"]["count"])
        self.assertEqual({"started": [], "ended": [], "offset": [], "lat": [], "lon": [], "place": [], "kind": [], "p": [], "was": [], "why": []}, relay.timeline_year_body(self.conn, 2022)["visits"])

    def test_a_years_etag_moves_only_when_the_year_does(self):
        self.read(android_export(DAY1))
        before = dict(self.rows("SELECT year, etag FROM years"))
        other_year = DAY1 + 60 * 86400
        self.assertEqual([2024], self.read(android_export(other_year))["years_changed"])
        self.assertEqual(before[2023], dict(self.rows("SELECT year, etag FROM years"))[2023])
        self.assertEqual([2023], self.read(android_export(DAY1, cafe="ChIJbakery"))["years_changed"])
        self.assertNotEqual(before[2023], dict(self.rows("SELECT year, etag FROM years"))[2023])

    def test_the_manifest_lists_the_years_and_the_span(self):
        relay.timeline_import(self.write("Timeline.json", android_export()))
        manifest = relay.timeline_manifest(self.conn, now=5000.0)
        (year,) = manifest["years"]
        self.assertEqual((2023, 3, 2, 3), (year["year"], year["visits"], year["moves"], year["points"]))
        self.assertGreater(year["bytes"], 0)
        self.assertEqual((DAY1 + 6 * 3600, DAY1 + 6 * 3600 + 33000), (manifest["first"], manifest["last"]))
        self.assertEqual({"version": 1, "total": 3, "named": 0, "gone": 0}, manifest["places"])
        self.assertEqual(("Timeline.json", 3), (manifest["file"]["name"], manifest["file"]["visits"]))
        self.assertFalse(manifest["drive"]["configured"])
        self.assertFalse(manifest["naming"]["configured"])


class _Asker:
    def __init__(self, cookie="frigate_token=abc", accept=None):
        self.headers = {"cookie": cookie}
        if accept:
            self.headers["accept-encoding"] = accept


class TimelineRoutes(_TimelineDb):
    def setUp(self):
        super().setUp()
        self.profile = {"username": "alex", "role": "admin"}
        relay.frigate_profile = lambda request: self.profile
        relay.TIMELINE_USERS = set()
        self.response = types.SimpleNamespace(headers={})
        relay.timeline_import(self.write("Timeline.json", android_export()))

    def test_a_year_comes_gzipped_to_a_caller_that_reads_gzip(self):
        import gzip

        plain = relay.get_timeline_year(2023, _Asker())
        packed = relay.get_timeline_year(2023, _Asker(accept="gzip, deflate"))
        self.assertEqual(json.loads(plain.body), json.loads(gzip.decompress(packed.body)))
        self.assertEqual(relay.timeline_year_body(self.conn, 2023), json.loads(plain.body))
        with self.assertRaises(relay.HTTPException) as missing:
            relay.get_timeline_year(1999, _Asker())
        self.assertEqual(404, missing.exception.status_code)

    def test_the_manifest_says_who_asked(self):
        self.assertEqual("alex", relay.get_timeline(_Asker(), self.response)["user"])
        self.assertEqual("alex", relay.post_timeline_sync(_Asker(), self.response)["user"])

    def test_the_manifest_and_places_are_not_to_be_cached(self):
        self.assertEqual(1, len(relay.get_timeline(_Asker(), self.response)["years"]))
        self.assertEqual("private, no-store", self.response.headers["Cache-Control"])
        places = relay.get_timeline_places(_Asker(), self.response)
        self.assertEqual(1, places["version"])
        self.assertEqual({"id": "ChIJhome", "lat": 40.0, "lon": -89.0, "kind": "home", "name": None, "address": None, "type": None, "city": None, "region": None, "country": None}, places["places"][1])

    def test_only_an_admin_reads_it_until_users_are_listed_and_then_only_they_do(self):
        self.profile = {"username": "sam", "role": "viewer"}
        for call in (lambda: relay.get_timeline(_Asker(), self.response), lambda: relay.get_timeline_year(2023, _Asker()),
                     lambda: relay.get_timeline_places(_Asker(), self.response), lambda: relay.post_timeline_sync(_Asker(), self.response)):
            with self.assertRaises(relay.HTTPException) as refused:
                call()
            self.assertEqual(403, refused.exception.status_code)
        relay.TIMELINE_USERS = {"sam"}
        self.assertEqual(1, len(relay.get_timeline(_Asker(), self.response)["years"]))
        self.profile = {"username": "alex", "role": "admin"}
        with self.assertRaises(relay.HTTPException):
            relay.get_timeline(_Asker(), self.response)

    def test_nobody_signed_in_is_refused(self):
        self.profile = None
        with self.assertRaises(relay.HTTPException) as refused:
            relay.get_timeline(_Asker(), self.response)
        self.assertEqual(401, refused.exception.status_code)


class TimelineNames(_TimelineDb):
    def setUp(self):
        super().setUp()
        relay.TIMELINE_PLACES_KEY = "key"
        relay.TIMELINE_PLACES_MONTHLY = 4000
        self.asked = []
        self.answers = {}
        relay.timeline_place_lookup = self.lookup
        self.read(android_export())

    def lookup(self, place):
        self.asked.append(place)
        return self.answers.get(place, (200, {
            "displayName": {"text": place[4:].title()}, "shortFormattedAddress": "1 Main St", "primaryType": "cafe", "types": ["cafe", "food"],
            "addressComponents": [
                {"longText": "1", "shortText": "1", "types": ["street_number"]},
                {"longText": "Springfield", "shortText": "Springfield", "types": ["locality", "political"]},
                {"longText": "Illinois", "shortText": "IL", "types": ["administrative_area_level_1", "political"]},
                {"longText": "United States", "shortText": "US", "types": ["country", "political"]},
            ],
        }))

    def names(self):
        return {row[0]: row[1:] for row in self.rows("SELECT place, name, address, type, status FROM places")}

    def test_places_are_named_most_time_spent_first(self):
        self.assertEqual(3, relay.timeline_name_places(now=DAY1, pause=0))
        self.assertEqual(["ChIJwork", "ChIJhome", "ChIJcafe"], self.asked)
        self.assertEqual(("Cafe", "1 Main St", "cafe", "ok"), self.names()["ChIJcafe"])
        self.assertEqual([("Springfield", "IL", "US")], self.rows("SELECT city, region, country FROM places WHERE place = 'ChIJcafe'"))

    def test_a_place_with_no_town_goes_by_the_next_thing_down(self):
        self.assertEqual(("Hackney", None, "GB"), relay.place_locality([
            {"longText": "Hackney", "shortText": "Hackney", "types": ["postal_town"]}, {"longText": "United Kingdom", "shortText": "GB", "types": ["country", "political"]}]))
        self.assertEqual((None, None, None), relay.place_locality(None))

    def test_a_name_the_export_gave_outlives_a_retired_id(self):
        self.conn.execute("UPDATE places SET name = 'The Old Mill' WHERE place = 'ChIJwork'")
        self.conn.commit()
        self.answers["ChIJwork"] = (404, {})
        relay.timeline_name_places(now=DAY1, pause=0)
        self.assertEqual(("The Old Mill", "gone"), (self.names()["ChIJwork"][0], self.names()["ChIJwork"][3]))
        self.assertEqual(2, relay.timeline_meta(self.conn, "places_version"))

    def test_a_place_is_asked_once(self):
        relay.timeline_name_places(now=DAY1, pause=0)
        self.assertEqual(0, relay.timeline_name_places(now=DAY1 + 3600, pause=0))
        self.assertEqual(3, len(self.asked))

    def test_a_place_google_has_retired_is_marked_gone_and_not_asked_again(self):
        self.answers["ChIJwork"] = (404, {"error": {"message": "Place ID is no longer valid"}})
        self.assertEqual(2, relay.timeline_name_places(now=DAY1, pause=0))
        self.assertEqual((None, None, None, "gone"), self.names()["ChIJwork"])
        relay.timeline_name_places(now=DAY1 + 3600, pause=0)
        self.assertEqual(3, len(self.asked))
        self.assertEqual(1, relay.timeline_manifest(self.conn, now=DAY1)["places"]["gone"])

    def test_the_months_allowance_is_never_passed_and_comes_back_next_month(self):
        relay.TIMELINE_PLACES_MONTHLY = 2
        self.assertEqual(2, relay.timeline_name_places(now=DAY1, pause=0))
        self.assertEqual(0, relay.timeline_name_places(now=DAY1 + 86400, pause=0))
        self.assertEqual(2, relay.timeline_manifest(self.conn, now=DAY1)["naming"]["asked_this_month"])
        self.assertEqual(1, relay.timeline_name_places(now=DAY1 + 40 * 86400, pause=0))
        self.assertEqual(3, len(self.asked))

    def test_a_refusal_stops_the_run_and_is_said(self):
        self.answers["ChIJwork"] = (403, {"error": {"message": "Places API (New) has not been used in project 1 before or it is disabled."}})
        self.assertEqual(0, relay.timeline_name_places(now=DAY1, pause=0))
        self.assertEqual(["ChIJwork"], self.asked)
        naming = relay.timeline_manifest(self.conn, now=DAY1)["naming"]
        self.assertEqual("api_disabled", naming["error"])
        self.assertIn("disabled", naming["message"])
        # Still unasked, so it is tried again once the key works.
        del self.answers["ChIJwork"]
        self.assertEqual(3, relay.timeline_name_places(now=DAY1 + 3600, pause=0))
        self.assertIsNone(relay.timeline_manifest(self.conn, now=DAY1)["naming"]["error"])

    def test_no_key_asks_nothing(self):
        relay.TIMELINE_PLACES_KEY = ""
        self.assertEqual(0, relay.timeline_name_places(now=DAY1, pause=0))
        self.assertEqual([], self.asked)


def _local(year, month, day, hour=0.0):
    """Epoch seconds of a time on the wall clock in Central Standard Time; an hour past 24 is the next day."""
    import calendar

    return calendar.timegm((year, month, day, 0, 0, 0)) + int(hour * 3600) - CST * 60


def _off(at, north=0.0, east=0.0):
    """A spot [north] and [east] metres from [at]."""
    import math

    return (round(at[0] + north / 111_195, 6), round(at[1] + east / (111_195 * math.cos(math.radians(at[0]))), 6))


def _stay(start, end, at, place, p=0.9):
    segment = android_visit(start, end, at, place)
    segment["visit"]["topCandidate"]["probability"] = p
    return segment


def _life(year, month, days, home=HOME, place="ChIJhome", skip=(), rest=()):
    """For each of [days]: the night at home from 22:00 (not the [skip] ones), then the next day at work from 09:00 to 17:00 (not after the [rest] ones)."""
    out = []
    for day in days:
        if day not in skip:
            out.append(_stay(_local(year, month, day, 22), _local(year, month, day, 31), home, place))
        if day not in rest:
            out.append(_stay(_local(year, month, day, 33), _local(year, month, day, 41), WORK, "ChIJwork"))
    return out


class TimelineRefining(_TimelineDb):
    def refine(self, segments, types=None):
        self.read({"semanticSegments": segments})
        for place, kind_of in (types or {}).items():
            self.conn.execute("UPDATE places SET type=? WHERE place=?", (kind_of, place))
        self.conn.commit()
        summary = relay.timeline_refine(self.conn, now=2000.0)
        relay.timeline_rebuild_years(self.conn)
        return summary

    def stay_at(self, when):
        """The refined visit that covers [when]: (place, was, why), or None."""
        found = self.rows(f"SELECT place, was, why FROM refined_visits WHERE started <= {when} AND ended > {when}")
        return found[0] if found else None

    def test_what_the_export_said_is_kept_and_the_app_is_given_the_refined_copy(self):
        flat, cafe = (40.1, -89.1), _off((40.1, -89.1), east=30)
        self.refine(_life(2024, 1, range(2, 20), flat, "ChIJflat") + [_stay(_local(2024, 1, 20, 22), _local(2024, 1, 20, 31), cafe, "ChIJbeans")],
                    {"ChIJflat": "premise", "ChIJbeans": "coffee_shop"})
        self.assertEqual([("ChIJbeans",)], self.rows(f"SELECT place FROM visits WHERE started = {_local(2024, 1, 20, 22)}"))
        body = relay.timeline_year_body(self.conn, 2024)
        moved = body["visits"]["started"].index(_local(2024, 1, 20, 22))
        self.assertEqual("ChIJflat", body["places"][body["visits"]["place"][moved]])
        self.assertEqual("ChIJbeans", body["places"][body["visits"]["was"][moved]])
        self.assertEqual("night", body["visits"]["why"][moved])
        self.assertEqual({-1}, {was for i, was in enumerate(body["visits"]["was"]) if i != moved})

    def test_nobody_sleeps_at_the_coffee_shop_under_the_flat(self):
        flat, cafe = (40.1, -89.1), _off((40.1, -89.1), east=30)
        summary = self.refine(
            _life(2024, 1, range(2, 20), flat, "ChIJflat")
            + [_stay(_local(2024, 1, 20, 22), _local(2024, 1, 20, 31), cafe, "ChIJbeans"), _stay(_local(2024, 1, 22, 10), _local(2024, 1, 22, 11), cafe, "ChIJbeans")],
            {"ChIJflat": "premise", "ChIJbeans": "coffee_shop"},
        )
        self.assertEqual(("ChIJflat", "ChIJbeans", "night"), self.stay_at(_local(2024, 1, 21, 3)))
        # A coffee in the morning is a coffee.
        self.assertEqual(("ChIJbeans", None, None), self.stay_at(_local(2024, 1, 22, 10.5)))
        self.assertEqual(1, summary["moved"]["night"])

    def test_a_night_at_a_shop_with_nowhere_slept_nearby_stays_put(self):
        self.refine(_life(2024, 1, range(2, 20)) + [_stay(_local(2024, 1, 20, 22), _local(2024, 1, 20, 31), (40.2, -89.3), "ChIJmall")], {"ChIJmall": "shopping_mall"})
        self.assertEqual(("ChIJmall", None, None), self.stay_at(_local(2024, 1, 21, 3)))

    def test_one_building_under_two_ids_is_one_place_and_the_house_down_the_street_is_not(self):
        unit, building, neighbour = (40.1, -89.1), _off((40.1, -89.1), north=10), _off((40.1, -89.1), east=40)
        segments = _life(2024, 1, range(2, 16), unit, "ChIJunit") + _life(2024, 1, range(16, 19), building, "ChIJbuilding") + _life(2024, 1, [19], neighbour, "ChIJneighbour")
        self.refine(segments, {"ChIJunit": "subpremise", "ChIJbuilding": "premise", "ChIJneighbour": "premise"})
        self.assertEqual(("ChIJunit", "ChIJbuilding", "building"), self.stay_at(_local(2024, 1, 17, 3)))
        self.assertEqual(("ChIJneighbour", None, None), self.stay_at(_local(2024, 1, 20, 3)))

    def test_a_visit_google_was_unsure_of_goes_where_the_time_usually_went(self):
        corner = _off(HOME, north=60)
        lunch = (_local(2024, 1, 21, 12), _local(2024, 1, 21, 14))
        self.refine(_life(2024, 1, range(2, 20)) + [_stay(*lunch, corner, "ChIJcorner", p=0.3)], {"ChIJcorner": "restaurant"})
        self.assertEqual(("ChIJhome", "ChIJcorner", "unsure"), self.stay_at(lunch[0] + 60))
        # The same lunch, Google sure of it: a lunch at the corner.
        self.conn.execute("UPDATE visits SET p = 0.9 WHERE place = 'ChIJcorner'")
        relay.timeline_refine(self.conn)
        self.assertEqual(("ChIJcorner", None, None), self.stay_at(lunch[0] + 60))

    def test_a_thin_records_nights_go_to_the_home_of_the_months_beside_it(self):
        old, hotel, friend = _off(HOME, north=1200), _off(HOME, east=1000), _off(HOME, north=-1000)
        december = [_stay(_local(2023, 12, d, 22), _local(2023, 12, d, 31), old, "ChIJold", p=0.4) for d in (5, 12, 19)]
        december += [
            _stay(_local(2023, 12, 13, 12), _local(2023, 12, 13, 13), old, "ChIJold", p=0.4),
            _stay(_local(2023, 12, 20, 22), _local(2023, 12, 20, 31), hotel, "ChIJhotel"),
            _stay(_local(2023, 12, 21, 22), _local(2023, 12, 21, 31), friend, "ChIJfriend"),
        ]
        friend_nights = (10, 17, 24)
        well = _life(2024, 1, range(1, 32), skip=friend_nights) + _life(2024, 2, range(1, 29))
        well += [_stay(_local(2024, 1, d, 22), _local(2024, 1, d, 31), friend, "ChIJfriend") for d in friend_nights]
        summary = self.refine(december + well, {"ChIJold": "subpremise", "ChIJhotel": "hotel", "ChIJfriend": "premise", "ChIJhome": "premise"})
        for d in (5, 12, 19):
            self.assertEqual(("ChIJhome", "ChIJold", "thin"), self.stay_at(_local(2023, 12, d + 1, 3)), d)
        # Where the nights went, the days went too: the place was a bad fix of home.
        self.assertEqual(("ChIJhome", "ChIJold", "thin"), self.stay_at(_local(2023, 12, 13, 12.5)))
        self.assertEqual(("ChIJhotel", None, None), self.stay_at(_local(2023, 12, 21, 3)))
        # Somewhere nights are spent in a good record too is somewhere real.
        self.assertEqual(("ChIJfriend", None, None), self.stay_at(_local(2023, 12, 22, 3)))
        self.assertEqual(4, summary["moved"]["thin"])
        # A good record is left as it was.
        self.assertEqual(("ChIJfriend", None, None), self.stay_at(_local(2024, 1, 11, 3)))

    def test_a_night_nothing_was_recorded_in_between_an_evening_and_a_morning_near_home_was_at_home(self):
        bar, cafe = _off(HOME, north=1000), _off(HOME, east=500)
        evening, morning = _local(2024, 1, 15, 23.5), _local(2024, 1, 16, 8.5)
        segments = _life(2024, 1, range(1, 32), skip=(15,)) + [
            _stay(_local(2024, 1, 15, 20), evening, bar, "ChIJbar"), _stay(morning, _local(2024, 1, 16, 8.9), cafe, "ChIJcafe"),
        ]
        summary = self.refine(segments)
        self.assertEqual(1, summary["inferred"])
        (began, ended, place, was), = self.rows("SELECT started, ended, place, was FROM refined_visits WHERE why = 'inferred'")
        self.assertEqual(("ChIJhome", None), (place, was))
        # Home once a kilometre's walk from the bar is done, until half a kilometre's to the café.
        self.assertAlmostEqual(evening + 250, began, delta=2)
        self.assertAlmostEqual(morning - 125, ended, delta=2)

    def test_a_morning_far_from_home_says_the_night_was_somewhere_else(self):
        segments = _life(2024, 1, range(1, 32), skip=(15,)) + [
            _stay(_local(2024, 1, 15, 20), _local(2024, 1, 15, 23.5), _off(HOME, north=1000), "ChIJbar"),
            _stay(_local(2024, 1, 16, 8.5), _local(2024, 1, 16, 9), _off(HOME, north=50_000), "ChIJdiner"),
        ]
        self.assertEqual(0, self.refine(segments)["inferred"])

    def test_a_gap_at_home_is_time_at_home_and_a_gap_at_work_is_not(self):
        segments = _life(2024, 1, range(1, 32), rest=(20, 26, 27)) + [
            # A Sunday at home with a gap in the record each side of the late morning: one stay, all weekend.
            _stay(_local(2024, 1, 21, 10), _local(2024, 1, 21, 12), HOME, "ChIJhome"),
            # An afternoon at work in two pieces, three hours apart, and two half an hour apart.
            _stay(_local(2024, 1, 27, 9), _local(2024, 1, 27, 12), WORK, "ChIJwork"), _stay(_local(2024, 1, 27, 15), _local(2024, 1, 27, 17), WORK, "ChIJwork"),
            _stay(_local(2024, 1, 28, 9), _local(2024, 1, 28, 12), WORK, "ChIJwork"), _stay(_local(2024, 1, 28, 12.5), _local(2024, 1, 28, 17), WORK, "ChIJwork"),
        ]
        self.refine(segments)
        self.assertEqual([(_local(2024, 1, 20, 22), _local(2024, 1, 22, 7))], self.rows(f"SELECT started, ended FROM refined_visits WHERE started = {_local(2024, 1, 20, 22)}"))
        self.assertEqual(2, self.rows(f"SELECT COUNT(*) FROM refined_visits WHERE place = 'ChIJwork' AND started >= {_local(2024, 1, 27, 9)} AND ended <= {_local(2024, 1, 27, 17)}")[0][0])
        self.assertEqual(1, self.rows(f"SELECT COUNT(*) FROM refined_visits WHERE place = 'ChIJwork' AND started >= {_local(2024, 1, 28, 9)} AND ended <= {_local(2024, 1, 28, 17)}")[0][0])

    def test_a_walk_round_the_block_between_two_stays_at_home_keeps_them_two(self):
        t = _local(2024, 1, 21, 10)
        loop = [(0, *HOME), (600, *_off(HOME, north=800)), (1200, *_off(HOME, east=800)), (1800, *HOME)]
        segments = _life(2024, 1, range(1, 32), rest=(20,)) + [_stay(t + 1800, t + 7200, HOME, "ChIJhome"), android_path(_local(2024, 1, 21, 7), [(s + 3 * 3600, a, b) for s, a, b in loop])]
        self.refine(segments)
        self.assertEqual(2, self.rows(f"SELECT COUNT(*) FROM refined_visits WHERE place = 'ChIJhome' AND started >= {_local(2024, 1, 20, 22)} AND started < {_local(2024, 1, 21, 23)}")[0][0])

    def test_a_point_during_a_stay_is_at_the_place_and_a_spike_is_dropped(self):
        leave = _local(2024, 1, 11, 7)
        # The phone on the nightstand wanders, then the drive to work, one fix of which is miles off.
        night = [(-7200, *_off(HOME, north=220)), (-3600, *_off(HOME, east=-180)), (-1800, *_off(HOME, north=90))]
        drive = [(300, *_off(HOME, north=900)), (600, *_off(HOME, north=1800)), (630, *_off(HOME, north=1800, east=9000)), (660, *_off(HOME, north=2700)), (7200 - 5400, *WORK)]
        summary = self.refine(_life(2024, 1, range(1, 32)) + [android_path(leave - 7200, [(s + 7200, a, b) for s, a, b in night + drive])])
        (points, times), = self.rows("SELECT points, times FROM refined_paths")
        want = [HOME, HOME, _off(HOME, north=900), _off(HOME, north=1800), _off(HOME, north=2700), WORK]
        self.assertEqual([(round(a, 5), round(b, 5)) for a, b in want], relay.unpolyline(points))
        self.assertEqual([0, 5400, 7500, 7800, 7860, 9000], relay.unpolyline_numbers(times))
        self.assertEqual((3, 1), (summary["points_settled"], summary["points_dropped"]))

    def trip(self, day, spots, minutes=1.0, hour=10):
        """A drive on a day of January 2024 through [spots], [minutes] apart, as the phone's path."""
        start = _local(2024, 1, day, hour)
        return android_path(start, [(round(i * minutes * 60), *spot) for i, spot in enumerate(spots)])

    def path_points(self, day, hour=10):
        start = _local(2024, 1, day, hour)
        return [relay.unpolyline(points) for (points,) in self.rows(f"SELECT points FROM refined_paths WHERE started >= {start - 3600} AND started < {start + 3 * 3600}")][0]

    def test_a_street_driven_many_times_is_drawn_down_its_middle(self):
        road = [_off((40.3, -89.3), east=500 * j) for j in range(9)]
        # Six drives along one street, each fix a street's width off to one side or the other.
        trips = [self.trip(day, [_off(spot, north=15 if (j + day) % 2 else -15) for j, spot in enumerate(road)]) for day in range(1, 7)]
        summary = self.refine(trips)
        for lat, _ in self.path_points(1)[1:-1]:
            self.assertLess(abs(lat - road[0][0]) * 111_195, 6)
        self.assertGreater(summary["points_snapped"], 30)

    def test_a_fix_out_and_straight_back_where_nobody_has_been_goes_whatever_its_speed(self):
        road = [_off((40.5, -89.5), east=500 * j) for j in range(6)]
        off_road = _off(road[2], north=700)
        self.refine([self.trip(20, road[:3] + [off_road, _off(road[2], east=100)] + road[3:], minutes=2)])
        self.assertNotIn((round(off_road[0], 5), round(off_road[1], 5)), self.path_points(20))

    def test_the_same_turn_where_other_trips_have_been_is_kept(self):
        road = [_off((40.5, -89.5), east=500 * j) for j in range(6)]
        off_road = _off(road[2], north=700)
        across = [self.trip(day, [_off(off_road, north=-1000 + 500 * j) for j in range(5)]) for day in (21, 22, 23)]
        self.refine([self.trip(20, road[:3] + [off_road, _off(road[2], east=100)] + road[3:], minutes=2)] + across)
        self.assertTrue(any(relay._metres(*point, *off_road) < 30 for point in self.path_points(20)))

    def test_a_fix_that_jumps_away_while_arriving_home_goes(self):
        arrive = _local(2024, 1, 20, 18)
        jump = _off(HOME, north=-800)
        # Others have driven past where the fix jumped to, so only the arriving says it is wrong.
        across = [self.trip(day, [_off(jump, east=-1000 + 500 * j) for j in range(5)]) for day in (21, 22, 23)]
        drive = [(-1200, *_off(HOME, east=-3000)), (-900, *_off(HOME, east=-1500)), (-600, *_off(HOME, east=-120)), (-300, *jump), (-120, *_off(HOME, east=-60))]
        self.refine([_stay(arrive, arrive + 5 * 3600, HOME, "ChIJhome"), android_path(arrive - 1200, [(s + 1200, a, b) for s, a, b in drive])] + across)
        self.assertFalse(any(relay._metres(*point, *jump) < 30 for point in self.path_points(20, hour=17)))

    def test_a_straight_line_between_two_far_fixes_follows_the_road_earlier_trips_took(self):
        corner = _off((40.3, -89.3), east=2000)
        road = [_off((40.3, -89.3), east=500 * j) for j in range(5)] + [_off(corner, north=500 * j) for j in range(1, 5)]
        summary = self.refine([self.trip(day, road) for day in (1, 2, 3)] + [self.trip(10, [road[0], road[-1]], minutes=8)])
        later = self.path_points(10)
        self.assertTrue(any(relay._metres(*point, *corner) < 30 for point in later), later)
        self.assertEqual(9, len(later))
        self.assertEqual(7, summary["points_filled"])

    def test_a_stay_across_the_turn_of_a_month_is_a_home_to_both(self):
        b, c = HOME, _off(HOME, east=5000)
        stays = [relay._Stay(_local(2024, 1, 31, 22), _local(2024, 2, 20, 7), CST, *b, "ChIJb", "other", 0.9),
                 relay._Stay(_local(2024, 2, 22, 22), _local(2024, 2, 23, 7), CST, *c, "ChIJc", "other", 0.9)]
        homes = relay._NightHomes(stays)
        self.assertEqual("ChIJb", homes.homes[2024 * 12 + 1])
        self.assertNotIn(2024 * 12 + 1, homes.thin)

    def test_an_inferred_night_is_not_joined_into_the_evening_at_home_before_it(self):
        segments = _life(2024, 1, range(1, 32), skip=(15,)) + [
            _stay(_local(2024, 1, 15, 18), _local(2024, 1, 15, 22), HOME, "ChIJhome"),
            _stay(_local(2024, 1, 16, 8.5), _local(2024, 1, 16, 8.9), _off(HOME, east=500), "ChIJcafe"),
        ]
        self.refine(segments)
        self.assertEqual([(_local(2024, 1, 15, 22), "inferred")], self.rows("SELECT started, why FROM refined_visits WHERE why = 'inferred'"))
        self.assertEqual(("ChIJhome", None, None), self.stay_at(_local(2024, 1, 15, 20)))

    def test_a_first_ever_stop_out_and_back_keeps_the_fix_at_the_stop(self):
        road = [_off((40.5, -89.5), east=500 * j) for j in range(3)]
        shop = _off(road[2], north=700)
        # A three-minute stop whose one fix is when it began, too near its edges to be settled there.
        stop = _local(2024, 1, 20, 10) + 6 * 60
        self.refine([_stay(stop, stop + 180, shop, "ChIJshop"), self.trip(20, road + [shop, _off(road[2], east=100)], minutes=2)])
        self.assertTrue(any(relay._metres(*point, *shop) < 30 for point in self.path_points(20)))

    def test_a_flights_fixes_are_left_as_they_are(self):
        up = _local(2024, 1, 20, 10)
        gate, turn = (40.5, -89.5), _off((40.5, -89.5), north=50_000)
        self.refine([android_activity(up, up + 3 * 3600, gate, (41.5, -88.0), "FLYING", 200_000),
                     android_path(up, [(0, *gate), (60, *turn), (120, *_off(gate, east=500)), (3600, 41.0, -88.7)])])
        self.assertTrue(any(relay._metres(*point, *turn) < 30 for point in self.path_points(20)))

    def test_a_move_leaves_from_where_its_stay_was_moved_to(self):
        old = _off(HOME, north=1200)
        december = [_stay(_local(2023, 12, d, 22), _local(2023, 12, d, 31), old, "ChIJold", p=0.4) for d in (5, 12, 19)]
        leave = _local(2023, 12, 6, 7)
        december.append(android_activity(leave, leave + 1800, old, WORK))
        self.refine(december + _life(2024, 1, range(1, 32)) + _life(2024, 2, range(1, 29)), {"ChIJold": "subpremise", "ChIJhome": "premise"})
        self.assertEqual([HOME], [(lat, lon) for lat, lon in self.rows(f"SELECT lat, lon FROM refined_moves WHERE started = {leave}")])

    def test_a_relay_stopped_before_the_years_were_written_writes_them_when_it_starts(self):
        self.read(android_export())
        self.conn.execute("DELETE FROM years")
        self.conn.commit()
        rebuild = relay.timeline_rebuild_years

        def stopped(conn):
            raise RuntimeError("stopped")

        relay.timeline_rebuild_years = stopped
        try:
            with self.assertRaises(RuntimeError):
                relay.timeline_publish(self.conn)
        finally:
            relay.timeline_rebuild_years = rebuild
        self.assertEqual(relay.TIMELINE_REFINE_VERSION, relay.timeline_meta(self.conn, "refined")["version"])
        relay.timeline_refresh()
        self.assertEqual([(2023,)], self.rows("SELECT year FROM years"))
        self.assertFalse(relay.timeline_meta(self.conn, "refining"))

    def test_a_history_of_paths_alone_is_refined_again_too(self):
        self.read({"semanticSegments": [android_path(DAY1, [(0, *HOME), (600, *WORK)])]})
        relay.timeline_meta_set(self.conn, "refined", {"version": relay.TIMELINE_REFINE_VERSION - 1})
        relay.timeline_refresh()
        self.assertEqual(relay.TIMELINE_REFINE_VERSION, relay.timeline_meta(self.conn, "refined")["version"])

    def test_a_path_wholly_inside_a_stay_is_dropped(self):
        jitter = [(0, *_off(HOME, north=120)), (900, *_off(HOME, east=150)), (1800, *_off(HOME, north=-60))]
        summary = self.refine(_life(2024, 1, range(1, 32)) + [android_path(_local(2024, 1, 11, 1), jitter)])
        self.assertEqual((0, 1), (self.rows("SELECT COUNT(*) FROM refined_paths")[0][0], summary["paths_dropped"]))

    def test_a_move_starts_where_the_stay_before_it_was(self):
        leave = _local(2024, 1, 11, 7)
        self.refine(_life(2024, 1, range(1, 32)) + [android_activity(leave + 60, leave + 7200, _off(HOME, north=100), _off(WORK, east=200))])
        self.assertEqual([(*HOME, *WORK)], self.rows(f"SELECT lat, lon, to_lat, to_lon FROM refined_moves WHERE started = {leave + 60}"))

    def test_refining_starts_from_what_the_exports_said_every_time(self):
        flat, cafe = (40.1, -89.1), _off((40.1, -89.1), east=30)
        self.refine(_life(2024, 1, range(2, 20), flat, "ChIJflat") + [_stay(_local(2024, 1, 20, 22), _local(2024, 1, 20, 31), cafe, "ChIJbeans")],
                    {"ChIJflat": "premise", "ChIJbeans": "coffee_shop"})
        first = self.rows("SELECT * FROM refined_visits ORDER BY started")
        relay.timeline_refine(self.conn)
        self.assertEqual(first, self.rows("SELECT * FROM refined_visits ORDER BY started"))
        # A later export, of other days: the coffee-shop night is still worked out from what Google said of it.
        self.read({"semanticSegments": _life(2024, 2, range(1, 10), flat, "ChIJflat")})
        self.assertEqual(("ChIJflat", "ChIJbeans", "night"), self.stay_at(_local(2024, 1, 21, 3)))
        self.assertEqual(1000.0, relay.timeline_meta(self.conn, "refined")["at"])

    def test_a_new_way_of_refining_is_applied_once_when_the_relay_starts(self):
        self.read(android_export())
        relay.timeline_meta_set(self.conn, "refined", {"version": relay.TIMELINE_REFINE_VERSION - 1})
        relay.timeline_refresh()
        done = relay.timeline_meta(self.conn, "refined")
        self.assertEqual(relay.TIMELINE_REFINE_VERSION, done["version"])
        relay.timeline_refresh()
        self.assertEqual(done["at"], relay.timeline_meta(self.conn, "refined")["at"])

    def test_the_manifest_says_what_refining_changed(self):
        relay.timeline_import(self.write("Timeline.json", android_export()))
        refined = relay.timeline_manifest(self.conn, now=5000.0)["refined"]
        self.assertEqual(relay.TIMELINE_REFINE_VERSION, refined["version"])
        self.assertEqual({"building": 0, "night": 0, "unsure": 0, "thin": 0}, refined["moved"])

    def test_the_polyline_undone(self):
        points = [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]
        self.assertEqual(points, relay.unpolyline(relay.polyline(points)))
        self.assertEqual([0, 16, 47, 15], relay.unpolyline_numbers(relay.polyline_numbers([0, 16, 47, 15])))

    def test_the_deep_of_the_night_on_the_wall_clock(self):
        self.assertEqual(3 * 3600, relay.night_seconds(_local(2024, 1, 1, 22), _local(2024, 1, 1, 31), CST))
        self.assertEqual(3600, relay.night_seconds(_local(2024, 1, 1, 22), _local(2024, 1, 1, 27), CST))
        self.assertEqual(0, relay.night_seconds(_local(2024, 1, 1, 9), _local(2024, 1, 1, 17), CST))
        # Three nights' worth over two days and a bit.
        self.assertEqual(3 * 3 * 3600, relay.night_seconds(_local(2024, 1, 1, 0), _local(2024, 1, 3, 12), CST))


class _DriveAnswer(_GoogleResponse):
    def __init__(self, status, body, content=b""):
        super().__init__(status, body)
        self.content = content

    def iter_content(self, size):
        for at in range(0, len(self.content), size):
            yield self.content[at:at + size]


class _FakeDrive:
    """Stands in for the Drive session: the folder's listing, the folder itself, and a file's bytes."""

    def __init__(self, files, content=b"", folder=200, listing=200):
        self.files, self.content, self.folder, self.listing, self.downloads = files, content, folder, listing, 0

    def get(self, url, params=None, stream=False, timeout=None):
        if (params or {}).get("alt") == "media":
            self.downloads += 1
            return _DriveAnswer(200, {}, self.content)
        if url == relay.DRIVE_API:
            return _DriveAnswer(self.listing, {"files": self.files} if self.listing == 200 else {"error": {"message": "Google Drive API has not been used in project 1 before or it is disabled.", "errors": [{"reason": "accessNotConfigured"}]}})
        return _DriveAnswer(self.folder, {"id": "folder"} if self.folder == 200 else {"error": {"message": "File not found: folder."}})


class TimelinePull(_TimelineDb):
    FILE = {"id": "f1", "name": "Timeline.json", "size": "1234", "md5Checksum": "abc", "modifiedTime": "2026-10-01T10:00:00.000Z"}

    def setUp(self):
        super().setUp()
        relay.TIMELINE_DRIVE_FOLDER = "folder"
        relay._timeline_google["account"] = "relay@example.iam.gserviceaccount.com"
        self.drive = _FakeDrive([{"id": "n1", "name": "notes.txt"}, self.FILE], json.dumps(android_export()).encode())
        relay.timeline_google = lambda: self.drive

    def status(self):
        return relay.timeline_manifest(self.conn, now=5000.0)["drive"]

    def test_the_newest_export_is_read_once(self):
        summary = relay.timeline_pull(now=4000.0)
        self.assertEqual(3, summary["visits"])
        self.assertEqual(3, self.rows("SELECT COUNT(*) FROM visits")[0][0])
        self.assertIsNone(relay.timeline_pull(now=4100.0))
        self.assertEqual(1, self.drive.downloads)
        self.assertEqual((4100.0, None), (self.status()["checked_at"], self.status()["error"]))
        self.assertFalse(os.path.exists(os.path.join(self._dir.name, "timeline-incoming")))
        self.assertEqual("2026-10-01T10:00:00.000Z", relay.timeline_manifest(self.conn)["file"]["modified"])

    def test_a_changed_file_is_read_again(self):
        relay.timeline_pull(now=4000.0)
        self.drive.files = [{**self.FILE, "md5Checksum": "def", "modifiedTime": "2026-10-08T10:00:00.000Z"}]
        self.assertIsNotNone(relay.timeline_pull(now=4100.0))
        self.assertEqual(2, self.drive.downloads)

    def test_a_file_still_being_sent_is_not_read_until_it_has_arrived(self):
        # Drive lists the file as soon as the phone starts sending it, with nothing in it yet.
        self.drive.files = [{**self.FILE, "size": "0", "md5Checksum": "empty"}]
        self.assertIsNone(relay.timeline_pull(now=4000.0))
        self.assertEqual((0, "empty"), (self.drive.downloads, self.status()["error"]))
        self.drive.files = [self.FILE]
        self.assertEqual(3, relay.timeline_pull(now=4100.0)["visits"])

    def test_the_same_export_can_be_read_again_when_asked(self):
        relay.timeline_pull(now=4000.0)
        self.assertIsNone(relay.timeline_pull(now=4100.0))
        again = relay.timeline_pull(now=4200.0, again=True)
        self.assertEqual((3, 2), (again["visits"], self.drive.downloads))
        self.assertEqual(3, self.rows("SELECT COUNT(*) FROM visits")[0][0])

    def test_an_empty_folder_says_so(self):
        self.drive.files = [{"id": "n1", "name": "notes.txt"}]
        self.assertIsNone(relay.timeline_pull(now=4000.0))
        self.assertEqual("empty", self.status()["error"])

    def test_a_folder_the_key_cant_see_is_not_shared(self):
        self.drive.files, self.drive.folder = [], 404
        relay.timeline_pull(now=4000.0)
        status = self.status()
        self.assertEqual("not_shared", status["error"])
        self.assertEqual("relay@example.iam.gserviceaccount.com", status["service_account"])

    def test_the_drive_api_switched_off_says_so(self):
        self.drive.listing = 403
        relay.timeline_pull(now=4000.0)
        self.assertEqual("api_disabled", self.status()["error"])

    def test_a_file_that_wont_read_is_reported_and_tried_again(self):
        self.drive.content = b'{"semanticSegments": [{"startTime": '
        self.assertIsNone(relay.timeline_pull(now=4000.0))
        self.assertEqual("failed", self.status()["error"])
        self.drive.content = json.dumps(android_export()).encode()
        self.assertIsNotNone(relay.timeline_pull(now=4100.0))
        self.assertIsNone(self.status()["error"])

    def test_no_folder_set_does_nothing(self):
        relay.TIMELINE_DRIVE_FOLDER = ""
        self.assertIsNone(relay.timeline_pull(now=4000.0))
        self.assertEqual(0, self.drive.downloads)

    def test_a_check_is_the_pull_and_the_naming_by_one_caller_at_a_time(self):
        relay.TIMELINE_PLACES_KEY = "key"
        asked = []
        relay.timeline_place_lookup = lambda place: asked.append(place) or (200, {"displayName": {"text": "Somewhere"}})
        # Someone else's check is under way: this one does nothing at all, the naming included.
        self.assertTrue(relay._timeline_check_lock.acquire(blocking=False))
        try:
            self.assertIsNone(relay.timeline_check())
            self.assertTrue(relay.timeline_manifest(self.conn)["syncing"])
        finally:
            relay._timeline_check_lock.release()
        self.assertEqual(([], 0), (asked, self.drive.downloads))
        done = relay.timeline_check()
        self.assertEqual((3, 3), (done["read"]["visits"], done["named"]))
        self.assertEqual(3, len(asked))
        self.assertFalse(relay.timeline_manifest(self.conn)["syncing"])


# Zigbee2MQTT's device list as it reads with the coordinator, a ThirdReality contact sensor and a
# smart plug paired (shapes from Zigbee2MQTT 2.x).
Z2M_DEVICES = [
    {"ieee_address": "0x00124b0000000000", "type": "Coordinator", "friendly_name": "Coordinator"},
    {
        "ieee_address": "0x282c02bfffe6aa01", "type": "EndDevice", "friendly_name": "Front door",
        "definition": {"vendor": "Third Reality", "model": "3RDS17BZ", "exposes": [
            {"type": "binary", "name": "contact", "property": "contact", "value_on": False, "value_off": True},
            {"type": "numeric", "name": "battery", "property": "battery"},
            {"type": "numeric", "name": "linkquality", "property": "linkquality"},
        ]},
    },
    {
        "ieee_address": "0x282c02bfffe6aa02", "type": "Router", "friendly_name": "Hall plug",
        "definition": {"vendor": "Third Reality", "model": "3RSP02028BZ", "exposes": [
            {"type": "switch", "features": [{"type": "binary", "name": "state", "property": "state"}]},
        ]},
    },
]
FRONT = "0x282c02bfffe6aa01"


class FakeZigbee2Mqtt:
    """The broker with Zigbee2MQTT behind it: answers each request on its response topic, or as [refuse] says."""

    def __init__(self):
        self.requests = []
        self.refuse = None
        self.silent = False

    def publish(self, topic, payload):
        what = topic.split("/bridge/request/", 1)[1]
        body = json.loads(payload)
        self.requests.append((what, {k: v for k, v in body.items() if k != "transaction"}))
        if self.silent:
            return
        answer = {"data": {}, "status": "error", "error": self.refuse} if self.refuse else {"data": body, "status": "ok"}
        relay.door_receive(f"zigbee2mqtt/bridge/response/{what}", json.dumps({**answer, "transaction": body["transaction"]}).encode(), time.time())


class DoorsTest(_ScratchDb):
    """The door sensors: what Zigbee2MQTT says, what the relay keeps of it, and which openings push."""

    NAMES = _ScratchDb.NAMES + ("away_since", "require_frigate_session", "FLOORPLAN_FILE", "authenticate", "DOORS_LINK", "DOORS_MQTT", "DOOR_PAIRING")

    def setUp(self):
        super().setUp()
        relay.FLOORPLAN_FILE = os.path.join(self._dir.name, "floorplan.json")
        self.away = False
        relay.away_since = lambda: 1.0 if self.away else None
        relay.require_frigate_session = lambda request: "alex"
        self.pushes = []
        self.T = 1_800_000_000.0
        relay.DOORS_LINK = {"broker": True, "bridge": True}
        relay.DOOR_PAIRING = relay.DoorPairing()
        self.zigbee = FakeZigbee2Mqtt()
        relay.DOORS_MQTT = {"client": self.zigbee}

    def push(self, title, body, data, away=False):
        self.pushes.append((title, body, data, away))
        return {"sent": 1}

    def receive(self, topic, payload, now):
        raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        message = relay.door_message(topic, raw)
        if message and message[0] == "state":
            change = relay.door_state(message[1], message[2], now)
            if change:
                relay.door_announce(change, now, self.away, push=self.push)
            return change
        relay.door_receive(topic, raw, now)

    def paired(self):
        self.receive("zigbee2mqtt/bridge/devices", Z2M_DEVICES, self.T)

    def door(self):
        return relay.doors_snapshot(self.T + 10_000)["doors"][0]

    def test_only_contact_sensors_are_doors(self):
        self.assertEqual({FRONT: {"name": "Front door", "model": "Third Reality 3RDS17BZ"}}, relay.contact_sensors(Z2M_DEVICES))
        self.assertEqual({}, relay.contact_sensors({"not": "a list"}))

    def test_topics(self):
        self.assertEqual(("state", "Front door", {"contact": False}), relay.door_message("zigbee2mqtt/Front door", b'{"contact": false}'))
        self.assertEqual(("state", "Down/Back door", {"contact": True}), relay.door_message("zigbee2mqtt/Down/Back door", b'{"contact": true}'))
        self.assertEqual(("availability", "Front door", "offline"), relay.door_message("zigbee2mqtt/Front door/availability", b'{"state": "offline"}'))
        self.assertEqual(("availability", "Front door", "online"), relay.door_message("zigbee2mqtt/Front door/availability", b"online"))
        self.assertEqual(("bridge", None, "online"), relay.door_message("zigbee2mqtt/bridge/state", b'{"state": "online"}'))
        self.assertEqual("devices", relay.door_message("zigbee2mqtt/bridge/devices", json.dumps(Z2M_DEVICES).encode())[0])
        self.assertIsNone(relay.door_message("zigbee2mqtt/bridge/logging", b'{"level": "info"}'))
        self.assertIsNone(relay.door_message("frigate/events", b"{}"))
        self.assertIsNone(relay.door_message("zigbee2mqtt/Front door", b"not json"))

    def test_the_first_reading_is_where_a_door_starts_and_a_repeat_is_nothing(self):
        self.paired()
        self.assertIsNone(self.receive("zigbee2mqtt/Front door", {"contact": True, "battery": 97, "linkquality": 120}, self.T))
        self.assertIsNone(self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T + 60))
        door = self.door()
        self.assertEqual((False, 97, 120, True), (door["open"], door["battery"], door["link"], door["online"]))
        self.assertEqual([], relay.doors_snapshot(self.T + 100)["events"])

    def test_an_opening_and_a_closing_are_kept(self):
        self.paired()
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T)
        change = self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 5)
        self.assertEqual((FRONT, "Front door", True, "away"), (change["id"], change["name"], change["open"], change["notify"]))
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T + 30)
        events = relay.doors_snapshot(self.T + 100)["events"]
        self.assertEqual([(False, self.T + 30), (True, self.T + 5)], [(e["open"], e["at"]) for e in events])
        self.assertEqual((False, self.T + 30), (self.door()["open"], self.door()["changed"]))

    def test_when_an_opening_pushes(self):
        self.assertEqual("away", relay.door_route("away", True))
        self.assertIsNone(relay.door_route("away", False))
        self.assertEqual("away", relay.door_route("always", True))
        self.assertEqual("home", relay.door_route("always", False))
        self.assertIsNone(relay.door_route("never", True))
        self.assertIsNone(relay.door_route("never", False))

    def test_by_default_a_door_pushes_only_while_everyone_is_away(self):
        self.paired()
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T)
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 5)
        self.assertEqual([], self.pushes)
        self.away = True
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T + 10)
        self.assertEqual([], self.pushes, "a closing never pushes")
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 500)
        title, body, data, away = self.pushes[0]
        self.assertTrue(away)
        self.assertEqual("Front door opened", title)
        self.assertEqual({"door": FRONT, "door_name": "Front door", "door_event": "opened", "away": "1", "notif_id": f"door-{FRONT}"},
                         {k: data[k] for k in ("door", "door_name", "door_event", "away", "notif_id")})
        self.assertNotIn("silent", data)
        self.assertEqual("away", relay.doors_snapshot(self.T + 600)["events"][0]["pushed"])

    def test_a_door_set_to_always_pushes_while_someone_is_home_too(self):
        self.paired()
        relay.set_door(FRONT, types.SimpleNamespace(notify="always"), types.SimpleNamespace(headers={}))
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T)
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 5)
        title, _, data, away = self.pushes[0]
        self.assertEqual(("Front door opened", False), (title, away))
        self.assertNotIn("away", data)

    def test_a_door_set_to_never_does_not_push_even_when_away(self):
        self.paired()
        relay.set_door(FRONT, types.SimpleNamespace(notify="never"), types.SimpleNamespace(headers={}))
        self.away = True
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T)
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 5)
        self.assertEqual([], self.pushes)
        self.assertEqual(1, len(relay.doors_snapshot(self.T + 10)["events"]))

    def test_opened_again_soon_after_updates_the_notification_quietly(self):
        self.paired()
        self.away = True
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T)
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 5)
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T + 20)
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 60)
        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T + 70)
        self.receive("zigbee2mqtt/Front door", {"contact": False}, self.T + 60 + relay.DOOR_QUIET_SECONDS + 1)
        self.assertEqual([None, "1", None], [data.get("silent") for _, _, data, _ in self.pushes])

    def test_a_push_no_phone_got_is_not_counted(self):
        """Quiet hours (or "only when away") kept an Always door's push from every phone."""
        self.paired()
        relay.set_door(FRONT, types.SimpleNamespace(notify="always"), types.SimpleNamespace(headers={}))
        delivered = []

        def nobody(title, body, data, away=False):
            delivered.append(data)
            return {"sent": 0, "skipped_quiet": 2} if not away else {"sent": 2}

        self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T)
        change = relay.door_state("Front door", {"contact": False}, self.T + 5)
        self.assertIsNone(relay.door_announce(change, self.T + 5, False, push=nobody))
        self.assertIsNone(relay.doors_snapshot(self.T + 10)["events"][0]["pushed"])
        relay.door_state("Front door", {"contact": True}, self.T + 20)
        change = relay.door_state("Front door", {"contact": False}, self.T + 60)
        self.assertEqual("away", relay.door_announce(change, self.T + 60, True, push=nobody))
        self.assertNotIn("silent", delivered[-1], "nothing reached a phone before, so this one sounds")

    def test_a_renamed_sensor_keeps_its_setting_and_a_removed_one_is_hidden(self):
        self.paired()
        relay.set_door(FRONT, types.SimpleNamespace(notify="always"), types.SimpleNamespace(headers={}))
        renamed = [dict(d, friendly_name="Porch door") if d["ieee_address"] == FRONT else d for d in Z2M_DEVICES]
        self.receive("zigbee2mqtt/bridge/devices", renamed, self.T)
        self.assertEqual(("Porch door", "always"), (self.door()["name"], self.door()["notify"]))
        self.assertIsNone(self.receive("zigbee2mqtt/Front door", {"contact": True}, self.T), "the old name is no one now")
        self.receive("zigbee2mqtt/bridge/devices", [d for d in Z2M_DEVICES if d["ieee_address"] != FRONT], self.T)
        self.assertEqual([], relay.doors_snapshot(self.T)["doors"])

    def test_availability_marks_a_sensor_offline(self):
        self.paired()
        self.receive("zigbee2mqtt/Front door/availability", {"state": "offline"}, self.T)
        self.assertFalse(self.door()["online"])

    def test_the_reading_zigbee2mqtt_resends_on_restart_keeps_a_silent_sensor_offline(self):
        self.paired()
        self.receive("zigbee2mqtt/Front door", {"contact": False, "linkquality": 132}, self.T)
        self.receive("zigbee2mqtt/Front door/availability", {"state": "offline"}, self.T + 60)
        self.receive("zigbee2mqtt/Front door", {"contact": False, "battery": 100}, self.T + 61)
        door = self.door()
        self.assertEqual((False, self.T, 100), (door["online"], door["last_seen"], door["battery"]))
        self.receive("zigbee2mqtt/Front door", {"contact": True, "linkquality": 90}, self.T + 120)
        self.assertEqual((True, self.T + 120), (self.door()["online"], self.door()["last_seen"]))

    def version(self):
        return relay.doors_snapshot(self.T + 10_000)["version"]

    def test_the_version_moves_when_a_door_changes_and_not_on_a_repeat(self):
        self.paired()
        paired = self.version()
        relay.door_receive("zigbee2mqtt/Front door", b'{"contact": true}', self.T)
        start = self.version()
        self.assertNotEqual(paired, start, "a first reading draws the door: closed, not unknown")
        relay.door_receive("zigbee2mqtt/Front door", b'{"contact": true, "battery": 90}', self.T + 1)
        self.assertEqual(start, self.version())
        relay.door_receive("zigbee2mqtt/Front door", b'{"contact": false}', self.T + 2)
        opened = self.version()
        self.assertNotEqual(start, opened)
        relay.door_receive("zigbee2mqtt/Front door/availability", b'{"state": "offline"}', self.T + 3)
        offline = self.version()
        self.assertNotEqual(opened, offline)
        relay.door_receive("zigbee2mqtt/Front door", b'{"contact": false, "linkquality": 80}', self.T + 4)
        self.assertNotEqual(offline, self.version(), "a reading brings it back online, though it's still open")

    def test_a_push_that_reached_a_phone_moves_the_version_again(self):
        self.paired()
        relay.door_receive("zigbee2mqtt/Front door", b'{"contact": true}', self.T)
        relay.with_db(lambda c: (c.execute("UPDATE doors SET notify='always'"), c.commit()))
        bumps = []
        saved = relay.DOORS_CHANGES.bump, relay.broadcast
        relay.DOORS_CHANGES.bump = lambda: bumps.append(1)
        try:
            relay.broadcast = lambda *args, **kwargs: {"sent": 1}
            relay.door_receive("zigbee2mqtt/Front door", b'{"contact": false}', self.T + 1)
            self.assertEqual(2, len(bumps), "once for the opening, once it's marked notified")
            self.assertEqual("home", relay.doors_snapshot(self.T + 10)["events"][0]["pushed"])
            relay.broadcast = lambda *args, **kwargs: {"sent": 0}
            relay.door_receive("zigbee2mqtt/Front door", b'{"contact": true}', self.T + 2)
            relay.door_receive("zigbee2mqtt/Front door", b'{"contact": false}', self.T + 200)
            self.assertEqual(4, len(bumps), "a push no phone got marks nothing")
        finally:
            relay.DOORS_CHANGES.bump, relay.broadcast = saved

    def test_a_wait_returns_at_once_on_an_old_version_and_wakes_on_a_change(self):
        import asyncio

        changes = relay.DoorsChanges()
        old = changes.version

        async def waits():
            changes.bump()
            started = time.monotonic()
            await changes.wait(old, 5)  # the app's version is already behind
            behind = time.monotonic() - started
            current = changes.version
            started = time.monotonic()
            # A door changes on the MQTT thread while the request waits.
            threading.Timer(0.05, changes.bump).start()
            await changes.wait(current, 5)
            woken = time.monotonic() - started
            started = time.monotonic()
            await changes.wait(changes.version, 0.1)  # nothing changes: the wait runs out
            return behind, woken, time.monotonic() - started

        behind, woken, quiet = asyncio.run(waits())
        self.assertLess(behind, 0.01)
        self.assertGreaterEqual(woken, 0.04)
        self.assertLess(woken, 1)
        self.assertGreaterEqual(quiet, 0.09)

    def test_a_slow_database_call_is_logged_with_its_caller(self):
        saved = relay.DB_SLOW_SECONDS
        relay.DB_SLOW_SECONDS = 0.01
        try:
            def slow_one():
                relay.with_db(lambda c: time.sleep(0.03))

            with self.assertLogs(relay.log, "WARNING") as logged:
                slow_one()
            self.assertIn("by slow_one", logged.output[0])
        finally:
            relay.DB_SLOW_SECONDS = saved

    def test_every_waiting_request_wakes_on_one_change(self):
        import asyncio

        changes = relay.DoorsChanges()

        async def waits():
            current = changes.version
            asyncio.get_running_loop().call_later(0.05, changes.bump)
            started = time.monotonic()
            await asyncio.gather(*(changes.wait(current, 5) for _ in range(3)))
            return time.monotonic() - started

        self.assertLess(asyncio.run(waits()), 1)

    # A made-up two-floor plan: the real one lives only on the box.
    PLAN = {"floors": [
        {"id": "ground", "name": "Ground floor", "outline": [[0, 0], [30, 0], [30, 40], [0, 40]], "rooms": [{"name": "Kitchen", "x": 8, "y": 10}]},
        {"id": "upper", "name": "Upper floor", "outline": [[0, 0], [30, 0], [30, 25], [0, 25]]},
    ]}

    def write_plan(self, plan):
        with open(relay.FLOORPLAN_FILE, "w") as f:
            f.write(plan if isinstance(plan, str) else json.dumps(plan))

    def test_the_floor_plan_is_served_only_when_the_box_has_one(self):
        request = types.SimpleNamespace(headers={})
        relay.authenticate = lambda request, device=None: "alex"
        with self.assertRaises(relay.HTTPException) as missing:
            relay.get_floorplan(request)
        self.assertEqual(404, missing.exception.status_code)
        self.write_plan("not json")
        with self.assertRaises(relay.HTTPException):
            relay.get_floorplan(request)
        self.write_plan({"floors": [{"name": "no id or outline"}]})
        with self.assertRaises(relay.HTTPException):
            relay.get_floorplan(request)
        self.write_plan(self.PLAN)
        self.assertEqual(self.PLAN, relay.get_floorplan(request))

    def test_the_editor_saves_a_tidied_plan_and_keeps_the_one_it_replaced(self):
        self.write_plan(self.PLAN)
        edited = {"floors": [{"id": "ground", "name": "  Ground floor ", "outline": [[0, 0], [30.004, 0], [30, 40], [0, 40]],
                              "rooms": [{"name": "Kitchen", "x": 8, "y": 10}], "walls": [[0, 13, 14, 13]], "extra": "dropped"}]}
        saved = relay.put_floorplan(edited, types.SimpleNamespace(headers={}))
        self.assertEqual({"id": "ground", "name": "Ground floor", "outline": [[0.0, 0.0], [30.0, 0.0], [30.0, 40.0], [0.0, 40.0]],
                          "rooms": [{"name": "Kitchen", "x": 8.0, "y": 10.0}], "stairs": [], "walls": [[0.0, 13.0, 14.0, 13.0]], "doors": []}, saved["floors"][0])
        self.assertEqual(saved, relay.floorplan())
        history = os.path.join(self._dir.name, "floorplan-history")
        kept = os.listdir(history)
        self.assertEqual(1, len(kept))
        with open(os.path.join(history, kept[0])) as f:
            self.assertEqual(self.PLAN, json.load(f))

    def test_a_doorway_linked_to_a_sensor_puts_the_sensor_at_its_middle(self):
        self.paired()
        plan = {"floors": [{"id": "ground", "outline": [[0, 0], [30, 0], [30, 40], [0, 40]],
                            "doors": [{"name": "Front door", "from": [10, 40], "to": [13, 40], "swing": -1, "sensor": FRONT},
                                      {"from": [0, 5], "to": [0, 8], "swing": 7, "sensor": "0xnot-paired-yet"}]}]}
        saved = relay.put_floorplan(plan, types.SimpleNamespace(headers={}))
        self.assertEqual([{"from": [10.0, 40.0], "to": [13.0, 40.0], "swing": -1, "name": "Front door", "sensor": FRONT},
                          {"from": [0.0, 5.0], "to": [0.0, 8.0], "swing": 1, "sensor": "0xnot-paired-yet"}], saved["floors"][0]["doors"])
        self.assertEqual({"floor": "ground", "x": 11.5, "y": 40.0}, self.door()["spot"])

    def test_a_sensor_links_to_one_doorway_and_a_doorway_has_width(self):
        request = types.SimpleNamespace(headers={})
        outline = [[0, 0], [30, 0], [30, 40], [0, 40]]
        for doors in (
            [{"from": [0, 0], "to": [3, 0], "sensor": FRONT}, {"from": [5, 0], "to": [8, 0], "sensor": FRONT}],
            [{"from": [0, 0], "to": [0.2, 0]}],
            [{"from": [0, 0]}],
        ):
            with self.assertRaises(relay.HTTPException) as refused:
                relay.put_floorplan({"floors": [{"id": "ground", "outline": outline, "doors": doors}]}, request)
            self.assertEqual(400, refused.exception.status_code)

    def test_stairs_say_which_way_they_go_and_which_floor_they_join(self):
        outline = [[0, 0], [30, 0], [30, 40], [0, 40]]
        plan = {"street": " Elm St ", "floors": [
            {"id": "ground", "outline": outline, "stairs": [{"x": 14, "y": 20, "direction": "down", "facing": "w", "flight": "cellar"}, [5, 5]]},
            {"id": "cellar", "outline": outline, "stairs": [{"x": 14, "y": 20, "direction": "up", "facing": "sideways", "flight": "cellar"}]},
        ]}
        saved = relay.put_floorplan(plan, types.SimpleNamespace(headers={}))
        self.assertEqual("Elm St", saved["street"])
        self.assertEqual([{"x": 14.0, "y": 20.0, "direction": "down", "facing": "w", "flight": "cellar"}, {"x": 5.0, "y": 5.0, "facing": "n"}], saved["floors"][0]["stairs"])
        self.assertEqual({"x": 14.0, "y": 20.0, "direction": "up", "facing": "n", "flight": "cellar"}, saved["floors"][1]["stairs"][0])

    def test_linked_stairs_join_two_floors_going_opposite_ways(self):
        request = types.SimpleNamespace(headers={})
        outline = [[0, 0], [30, 0], [30, 40], [0, 40]]
        up = {"x": 1, "y": 1, "direction": "up", "flight": "f"}
        down = {"x": 1, "y": 1, "direction": "down", "flight": "f"}
        for floors in (
            [{"id": "a", "outline": outline, "stairs": [up]}, {"id": "b", "outline": outline, "stairs": [dict(up)]}],
            [{"id": "a", "outline": outline, "stairs": [up, down]}],
            [{"id": "a", "outline": outline, "stairs": [up]}, {"id": "b", "outline": outline, "stairs": [down]}, {"id": "c", "outline": outline, "stairs": [dict(down)]}],
            [{"id": "a", "outline": outline, "stairs": [{"x": 1, "y": 1, "flight": "f"}]}, {"id": "b", "outline": outline, "stairs": [down]}],
        ):
            with self.assertRaises(relay.HTTPException, msg=str(floors)) as refused:
                relay.put_floorplan({"floors": floors}, request)
            self.assertEqual(400, refused.exception.status_code)

    def test_a_plan_the_editor_got_wrong_is_refused(self):
        request = types.SimpleNamespace(headers={})
        bad = [
            {},
            {"floors": []},
            {"floors": [{"id": "a", "outline": [[0, 0], [1, 0]]}]},
            {"floors": [{"id": "a", "outline": [[0, 0], [1, 0], [1, 1]]}, {"id": "a", "outline": [[0, 0], [1, 0], [1, 1]]}]},
            {"floors": [{"id": "a", "outline": [[0, 0], [1, 0], [1, "x"]]}]},
            {"floors": [{"id": "a", "outline": [[0, 0], [1, 0], [1, 1]], "walls": [[0, 0, 1]]}]},
            {"floors": [{"id": "a", "outline": [[0, 0], [1, 0], [1, 1]], "rooms": [{"name": " ", "x": 0, "y": 0}]}]},
            {"floors": [{"id": "a", "outline": [[0, 0], [1, 0], [1, float("inf")]]}]},
        ]
        for plan in bad:
            with self.assertRaises(relay.HTTPException, msg=str(plan)) as refused:
                relay.put_floorplan(plan, request)
            self.assertEqual(400, refused.exception.status_code)
        self.assertFalse(os.path.exists(relay.FLOORPLAN_FILE), "nothing written")

    def test_only_the_newest_replaced_plans_are_kept(self):
        self.write_plan(self.PLAN)
        for n in range(relay.FLOORPLAN_HISTORY + 5):
            relay.save_floorplan(self.PLAN, 1_800_000_000 + n)
        self.assertEqual(relay.FLOORPLAN_HISTORY, len(os.listdir(os.path.join(self._dir.name, "floorplan-history"))))

    def test_the_editor_page_is_served(self):
        page = relay.floorplan_editor()
        self.assertIn("Floor plan editor", page.body)
        self.assertEqual("text/html", page.media_type)

    def test_a_door_is_placed_on_a_floor_and_taken_off_again(self):
        self.paired()
        self.write_plan(self.PLAN)
        request = types.SimpleNamespace(headers={})
        self.assertIsNone(self.door()["spot"])
        placed = relay.set_door_spot(FRONT, types.SimpleNamespace(floor="ground", x=12.5, y=39.0), request)
        self.assertEqual({"floor": "ground", "x": 12.5, "y": 39.0}, placed["doors"][0]["spot"])
        self.assertIsNone(relay.clear_door_spot(FRONT, request)["doors"][0]["spot"])

    def test_a_spot_must_be_on_a_floor_the_plan_has(self):
        self.paired()
        request = types.SimpleNamespace(headers={})
        with self.assertRaises(relay.HTTPException) as no_plan:
            relay.set_door_spot(FRONT, types.SimpleNamespace(floor="ground", x=1.0, y=1.0), request)
        self.assertEqual(400, no_plan.exception.status_code)
        self.write_plan(self.PLAN)
        with self.assertRaises(relay.HTTPException) as attic:
            relay.set_door_spot(FRONT, types.SimpleNamespace(floor="attic", x=1.0, y=1.0), request)
        self.assertEqual(400, attic.exception.status_code)
        with self.assertRaises(relay.HTTPException) as nan:
            relay.set_door_spot(FRONT, types.SimpleNamespace(floor="ground", x=float("nan"), y=1.0), request)
        self.assertEqual(400, nan.exception.status_code)
        with self.assertRaises(relay.HTTPException) as nobody:
            relay.set_door_spot("0xnobody", types.SimpleNamespace(floor="ground", x=1.0, y=1.0), request)
        self.assertEqual(404, nobody.exception.status_code)

    def test_topics_for_pairing(self):
        self.assertEqual(("info", None, {"permit_join": True}), relay.door_message("zigbee2mqtt/bridge/info", b'{"permit_join": true}'))
        self.assertEqual("event", relay.door_message("zigbee2mqtt/bridge/event", b'{"type": "device_joined", "data": {}}')[0])
        self.assertEqual(("response", "device/rename", {"status": "ok"}), relay.door_message("zigbee2mqtt/bridge/response/device/rename", b'{"status": "ok"}'))

    def pairing(self):
        return relay.doors_snapshot(self.T)["pairing"]

    def test_pairing_opens_zigbee2mqtts_window_and_closes_it(self):
        request = types.SimpleNamespace(headers={})
        opened = relay.set_door_pairing(types.SimpleNamespace(seconds=1000), request)
        self.assertEqual(("permit_join", {"time": relay.DOOR_PAIRING_SECONDS}), self.zigbee.requests[-1], "Zigbee's window is 254 s at most")
        self.assertTrue(opened["pairing"]["open"])
        self.assertAlmostEqual(time.time() + relay.DOOR_PAIRING_SECONDS, opened["pairing"]["until"], delta=5)
        closed = relay.set_door_pairing(types.SimpleNamespace(seconds=0), request)
        self.assertEqual(("permit_join", {"time": 0}), self.zigbee.requests[-1])
        self.assertEqual({"open": False, "until": None, "joined": []}, closed["pairing"])

    def test_pairing_says_why_when_zigbee2mqtt_cant(self):
        request = types.SimpleNamespace(headers={})
        self.zigbee.refuse = "Coordinator busy"
        with self.assertRaises(relay.HTTPException) as refused:
            relay.set_door_pairing(types.SimpleNamespace(seconds=254), request)
        self.assertEqual((502, "Zigbee2MQTT: Coordinator busy"), (refused.exception.status_code, refused.exception.detail))
        self.assertFalse(self.pairing()["open"])
        relay.DOORS_LINK["bridge"] = False
        with self.assertRaises(relay.HTTPException) as down:
            relay.set_door_pairing(types.SimpleNamespace(seconds=254), request)
        self.assertEqual(503, down.exception.status_code)
        relay.DOORS_LINK["bridge"] = True
        self.zigbee.refuse, self.zigbee.silent = None, True
        saved = relay.Z2M_ANSWER_SECONDS
        relay.Z2M_ANSWER_SECONDS = 0.05
        try:
            with self.assertRaises(relay.HTTPException) as quiet:
                relay.set_door_pairing(types.SimpleNamespace(seconds=254), request)
        finally:
            relay.Z2M_ANSWER_SECONDS = saved
        self.assertEqual(504, quiet.exception.status_code)

    def test_the_window_follows_zigbee2mqtts_info(self):
        version = self.version()
        relay.door_receive("zigbee2mqtt/bridge/info", json.dumps({"permit_join": True, "permit_join_end": (self.T + 200) * 1000}).encode(), self.T)
        self.assertEqual((True, self.T + 200), (self.pairing()["open"], self.pairing()["until"]))
        moved = self.version()
        self.assertNotEqual(version, moved)
        relay.door_receive("zigbee2mqtt/bridge/info", json.dumps({"permit_join": True, "permit_join_end": (self.T + 200) * 1000, "version": "2.6.0"}).encode(), self.T + 1)
        self.assertEqual(moved, self.version(), "info says the same again")
        relay.door_receive("zigbee2mqtt/bridge/info", json.dumps({"permit_join": True, "permit_join_timeout": 100}).encode(), self.T)
        self.assertEqual(self.T + 100, self.pairing()["until"], "Zigbee2MQTT 1.x counts the seconds left")
        relay.door_receive("zigbee2mqtt/bridge/info", b'{"permit_join": false, "permit_join_end": null}', self.T + 250)
        self.assertEqual((False, None), (self.pairing()["open"], self.pairing()["until"]))
        relay.door_receive("zigbee2mqtt/bridge/info", b'{"permit_join": true}', self.T)
        relay.door_receive("zigbee2mqtt/bridge/state", b'{"state": "offline"}', self.T)
        self.assertFalse(self.pairing()["open"], "a window Zigbee2MQTT took down with it")

    def event(self, kind, now, **data):
        relay.door_receive("zigbee2mqtt/bridge/event", json.dumps({"type": kind, "data": data}).encode(), now)

    def test_what_joins_while_pairing_is_listed_with_how_far_it_got(self):
        new, back = "0x282c02bfffe6aa09", FRONT
        self.event("device_joined", self.T, ieee_address=new, friendly_name=new)
        self.assertEqual([], self.pairing()["joined"], "nothing was asked to pair")
        relay.door_receive("zigbee2mqtt/bridge/info", b'{"permit_join": true, "permit_join_end": 1}', self.T)
        self.event("device_joined", self.T + 1, ieee_address=back, friendly_name="Front door")
        self.event("device_joined", self.T + 2, ieee_address=new, friendly_name=new)
        self.event("device_interview", self.T + 3, ieee_address=new, friendly_name=new, status="started")
        self.event("device_announce", self.T + 3, ieee_address=new, friendly_name=new)
        self.assertEqual([(back, "Front door", "joined"), (new, new, "interviewing")], [(j["id"], j["name"], j["status"]) for j in self.pairing()["joined"]])
        relay.door_receive("zigbee2mqtt/bridge/info", b'{"permit_join": false}', self.T + 4)
        self.event("device_interview", self.T + 5, ieee_address=new, friendly_name=new, status="successful")
        self.assertEqual("paired", self.pairing()["joined"][1]["status"], "an interview begun while pairing finishes after")
        relay.door_receive("zigbee2mqtt/bridge/info", b'{"permit_join": true}', self.T + 60)
        self.assertEqual([], self.pairing()["joined"], "pairing again starts afresh")

    def test_a_door_is_renamed_in_zigbee2mqtt(self):
        self.paired()
        request = types.SimpleNamespace(headers={})
        renamed = relay.rename_door(FRONT, types.SimpleNamespace(name="  Porch   door "), request)
        self.assertEqual(("device/rename", {"from": FRONT, "to": "Porch door", "homeassistant_rename": False}), self.zigbee.requests[-1])
        self.assertEqual("Porch door", renamed["doors"][0]["name"])
        relay.rename_door(FRONT, types.SimpleNamespace(name="Porch door"), request)
        self.assertEqual(1, len(self.zigbee.requests), "the same name again asks nothing")
        for bad in ("", "   ", "Up/Down", "Door #2", "x" * 65):
            with self.assertRaises(relay.HTTPException) as refused:
                relay.rename_door(FRONT, types.SimpleNamespace(name=bad), request)
            self.assertEqual(400, refused.exception.status_code, bad)
        with self.assertRaises(relay.HTTPException) as missing:
            relay.rename_door("0xnobody", types.SimpleNamespace(name="Shed"), request)
        self.assertEqual(404, missing.exception.status_code)
        self.zigbee.refuse = "friendly_name 'Back door' is already in use"
        with self.assertRaises(relay.HTTPException) as taken:
            relay.rename_door(FRONT, types.SimpleNamespace(name="Back door"), request)
        self.assertEqual(502, taken.exception.status_code)
        self.assertEqual("Porch door", self.door()["name"])

    def test_setting_a_door_checks_the_mode_and_the_door(self):
        self.paired()
        request = types.SimpleNamespace(headers={})
        with self.assertRaises(relay.HTTPException) as bad:
            relay.set_door(FRONT, types.SimpleNamespace(notify="sometimes"), request)
        self.assertEqual(400, bad.exception.status_code)
        with self.assertRaises(relay.HTTPException) as missing:
            relay.set_door("0xnobody", types.SimpleNamespace(notify="always"), request)
        self.assertEqual(404, missing.exception.status_code)
        self.assertEqual("always", relay.set_door(FRONT, types.SimpleNamespace(notify="always"), request)["doors"][0]["notify"])


if __name__ == "__main__":
    unittest.main()


class NestPrimerTest(unittest.TestCase):
    def test_only_streams_pulling_a_nest_camera_are_held(self):
        streams = {
            "hikvision_1_sub": {"producers": [{"url": "ffmpeg:rtsp://x/102#video=copy"}]},
            "living_room": {"producers": [{"url": "nest:?client_id=a&device_id=d1"}]},
            "living_room_sub": {"producers": [{"url": "ffmpeg:living_room#video=h264#width=640"}]},
            "kitchen": {"producers": [{"id": 3, "url": "nest:?device_id=d2", "medias": ["video"]}]},
            "empty": {"producers": None},
            "odd": None,
        }
        self.assertEqual(["kitchen", "living_room"], relay.nest_stream_names(streams))

    def test_no_streams_no_nest_cameras(self):
        self.assertEqual([], relay.nest_stream_names({}))
        self.assertEqual([], relay.nest_stream_names(None))

    def test_a_stream_that_keeps_failing_is_asked_for_less_often(self):
        self.assertEqual([5, 5, 10, 20, 40, 80, 160, 300, 300], [relay.nest_primer_delay(n) for n in range(0, 9)])

    def stream(self, *receivers):
        """go2rtc's `GET /api/streams?src=` for one stream, its producer's receivers as (codec type, bytes)."""
        return {
            "producers": [{"url": "nest:?client_secret=never-printed", "receivers": [{"codec": {"codec_type": kind}, "bytes": n} for kind, n in receivers]}],
            "consumers": [{"format_name": "mp4"}],
        }

    def fake_go2rtc(self, *readings):
        """go2rtc's stream reads answer [readings] in turn, the last one again once they run out; returns the `src`s asked."""
        asked, answers = [], list(readings)
        get = relay.requests.get
        self.addCleanup(setattr, relay.requests, "get", get)

        def read(url, params=None, timeout=None):
            asked.append(params["src"])
            answer = answers.pop(0) if len(answers) > 1 else answers[0]
            if isinstance(answer, Exception):
                raise answer
            return types.SimpleNamespace(json=lambda: answer)

        relay.requests.get = read
        return asked

    def test_a_stream_s_video_bytes_are_its_video_receivers_added_up(self):
        asked = self.fake_go2rtc(self.stream(("video", 100), ("audio", 7), ("video", 20)), self.stream(("audio", 7)), self.stream(), {}, None)
        self.assertEqual(120, relay.nest_video_bytes("outdoor_doorbell_sub"))
        self.assertIsNone(relay.nest_video_bytes("outdoor_doorbell_sub"))  # audio only
        self.assertIsNone(relay.nest_video_bytes("outdoor_doorbell_sub"))  # the stalled producer: no receivers
        self.assertIsNone(relay.nest_video_bytes("outdoor_doorbell_sub"))
        self.assertIsNone(relay.nest_video_bytes("outdoor_doorbell_sub"))
        self.assertEqual(["outdoor_doorbell_sub"] * 5, asked)

    def test_a_session_flows_once_a_video_receiver_has_bytes(self):
        self.fake_go2rtc(self.stream(("video", 0), ("audio", 50)), self.stream(("video", 1)), {})
        self.assertFalse(relay.nest_session_flowing("kitchen"))
        self.assertTrue(relay.nest_session_flowing("kitchen"))
        self.assertFalse(relay.nest_session_flowing("kitchen"))

    def watched(self, readings, started=1000.0):
        """[readings] as (seconds into the hold, bytes) fed to a new hold's watch; whether each found it stalled."""
        watch = relay.NestPrimerWatch(started)
        return watch, [watch.stalled(started + at, received) for at, received in readings]

    def test_a_flowing_stream_is_never_stalled(self):
        watch, stalled = self.watched([(15 * i, 1000 * i) for i in range(1, 40)])
        self.assertEqual([False] * 39, stalled)
        self.assertTrue(watch.flowed)

    def test_a_session_google_starts_slowly_is_given_the_first_minute(self):
        # The hold's own viewer gets nothing on purpose; the receivers have nothing until Google answers.
        watch, stalled = self.watched([(15, None), (30, 0), (45, 4000), (60, 90_000), (75, 180_000)])
        self.assertEqual([False] * 5, stalled)
        self.assertTrue(watch.flowed)

    def test_a_stream_whose_bytes_stop_is_stalled_a_minute_after_they_last_moved(self):
        # 2026-10-18: the Nest session ended and the producer's counts stood still.
        watch, stalled = self.watched([(15, 5000), (30, 9000), (45, 9000), (60, 9000), (75, 9000), (90, 9000)])
        self.assertEqual([False, False, False, False, False, True], stalled)
        self.assertTrue(watch.flowed)

    def test_a_stream_left_with_no_receivers_is_stalled(self):
        watch, stalled = self.watched([(15, 5000), (30, 9000), (45, None), (60, None), (75, None), (90, None)])
        self.assertEqual([False, False, False, False, False, True], stalled)
        watch, stalled = self.watched([(15, None), (30, None), (45, None), (60, None)])
        self.assertEqual([False, False, False, True], stalled)
        self.assertFalse(watch.flowed)

    def test_a_stream_whose_receivers_start_at_nothing_flowed_once_they_move(self):
        watch, stalled = self.watched([(15, 0), (30, 9000), (45, 9000), (90, 9000)])
        self.assertEqual([False, False, False, True], stalled)
        self.assertTrue(watch.flowed)
        watch.dropped = True
        self.assertTrue(relay.nest_primer_held(watch, 90))

    def test_a_stream_stuck_from_the_start_never_flowed(self):
        # A first reading is only a baseline: a producer stuck before the hold began has bytes.
        watch, stalled = self.watched([(15, 9000), (30, 9000), (45, 9000), (60, 9000), (75, 9000)])
        self.assertEqual([False, False, False, False, True], stalled)
        self.assertFalse(watch.flowed)

    def test_a_reopened_producer_s_counts_starting_again_are_arriving(self):
        watch, stalled = self.watched([(15, 50_000), (60, 50_000), (70, 2000), (85, 30_000)])
        self.assertEqual([False, False, False, False], stalled)

    def test_a_hold_the_watchdog_dropped_was_working_only_if_its_video_moved(self):
        flowed, dried_up = relay.NestPrimerWatch(0), relay.NestPrimerWatch(0)
        flowed.dropped = dried_up.dropped = True
        flowed.stalled(15, 100), flowed.stalled(30, 200)
        dried_up.stalled(15, 100)
        self.assertTrue(relay.nest_primer_held(flowed, 75))
        self.assertFalse(relay.nest_primer_held(dried_up, 75))
        self.assertFalse(relay.nest_primer_held(dried_up, 3600))
        # Ended by go2rtc, not the watchdog: by how long it held, as before.
        self.assertTrue(relay.nest_primer_held(relay.NestPrimerWatch(0), 60))
        self.assertFalse(relay.nest_primer_held(relay.NestPrimerWatch(0), 59))

    def quick_watchdog(self):
        for name, seconds in (("NEST_PRIMER_WATCH_SECONDS", 0.01), ("NEST_PRIMER_STALL_SECONDS", 0.05)):
            self.addCleanup(setattr, relay, name, getattr(relay, name))
            setattr(relay, name, seconds)

    def held_socket(self):
        """A connected socket pair: the hold's end, and go2rtc's."""
        held, far = socket.socketpair()
        self.addCleanup(held.close)
        self.addCleanup(far.close)
        return held, far

    def test_the_watchdog_shuts_a_stalled_hold_s_socket_and_wakes_its_read(self):
        self.quick_watchdog()
        asked = self.fake_go2rtc(self.stream(("video", 9000)), self.stream(), ConnectionError("go2rtc restarting"))
        held, _ = self.held_socket()
        read = []
        reader = threading.Thread(target=lambda: read.append(held.recv(1024)), daemon=True)
        reader.start()
        watch, done = relay.NestPrimerWatch(time.monotonic()), threading.Event()
        relay.nest_primer_watchdog("living_room", held, watch, done)
        reader.join(timeout=5)
        self.assertFalse(reader.is_alive(), "the hold's blocked read was never woken")
        self.assertEqual([b""], read)
        self.assertTrue(watch.dropped)
        self.assertEqual("living_room", asked[0])

    def test_the_watchdog_leaves_a_flowing_hold_and_stops_with_it(self):
        self.quick_watchdog()
        counts = iter(range(1000, 10**9, 1000))
        get = relay.requests.get
        self.addCleanup(setattr, relay.requests, "get", get)
        relay.requests.get = lambda url, params=None, timeout=None: types.SimpleNamespace(json=lambda c=next(counts): self.stream(("video", c)))
        held, far = self.held_socket()
        watch, done = relay.NestPrimerWatch(time.monotonic()), threading.Event()
        watchdog = threading.Thread(target=relay.nest_primer_watchdog, args=("kitchen", held, watch, done), daemon=True)
        watchdog.start()
        time.sleep(0.3)
        done.set()
        watchdog.join(timeout=5)
        self.assertFalse(watchdog.is_alive())
        self.assertFalse(watch.dropped)
        self.assertTrue(watch.flowed)
        far.setblocking(False)
        with self.assertRaises(BlockingIOError):  # the held end is still open: nothing, not end-of-stream
            far.recv(1)

    def fake_stream_mp4(self, answer: bytes):
        """
        go2rtc's `stream.mp4` on a local port, answering each viewer with [answer] and then nothing
        until the test ends, or closing at once when [answer] ends the response; returns the
        request lines it was sent. Points relay.GO2RTC at it.
        """
        server = socket.create_server(("127.0.0.1", 0))
        finished, asked = threading.Event(), []
        self.addCleanup(server.close)
        self.addCleanup(finished.set)
        self.addCleanup(setattr, relay, "GO2RTC", relay.GO2RTC)
        relay.GO2RTC = f"http://127.0.0.1:{server.getsockname()[1]}"

        def serve():
            viewer, _ = server.accept()
            with viewer:
                asked.append(viewer.recv(4096).split(b"\r\n")[0].decode())
                viewer.sendall(answer)
                if b"Connection: close" not in answer:
                    finished.wait(10)

        threading.Thread(target=serve, daemon=True).start()
        return asked

    def hold(self, name):
        """Runs nest_primer_hold([name]) to its end (5 s at most); its watch and what it raised, if anything."""
        watch, raised = relay.NestPrimerWatch(time.monotonic()), []

        def run():
            try:
                relay.nest_primer_hold(name, watch)
            except Exception as e:  # noqa: BLE001 - what the retry loop would log
                raised.append(e)

        holder = threading.Thread(target=run, daemon=True)
        holder.start()
        holder.join(timeout=5)
        self.assertFalse(holder.is_alive(), "the hold never ended")
        return watch, raised

    def test_a_hold_go2rtc_never_answers_is_dropped(self):
        # The viewer that starts a Nest session may never be answered, headers included.
        self.quick_watchdog()
        self.fake_go2rtc(self.stream())
        asked = self.fake_stream_mp4(b"")
        watch, _ = self.hold("outdoor_doorbell")
        self.assertTrue(watch.dropped)
        self.assertEqual(["GET /api/stream.mp4?src=outdoor_doorbell HTTP/1.1"], asked)

    def test_a_hold_whose_video_stops_mid_stream_is_dropped(self):
        self.quick_watchdog()
        self.fake_go2rtc(self.stream(("video", 9000)))
        self.fake_stream_mp4(b"HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nftyp\r\n")
        watch, _ = self.hold("outdoor_doorbell_sub")
        self.assertTrue(watch.dropped)
        self.assertFalse(watch.flowed)

    def test_a_hold_go2rtc_ends_is_not_the_watchdog_s(self):
        self.fake_go2rtc(self.stream(("video", 9000)))
        self.fake_stream_mp4(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nftyp")
        watch, raised = self.hold("kitchen")
        self.assertEqual([], raised)
        self.assertFalse(watch.dropped)

    def test_a_stream_go2rtc_refuses_is_not_held(self):
        self.fake_go2rtc(self.stream())
        self.fake_stream_mp4(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        watch, raised = self.hold("gone")
        self.assertEqual(["HTTP 404"], [str(e) for e in raised])
        self.assertFalse(watch.dropped)

    def test_each_nest_camera_s_grid_stream_is_held_after_its_session(self):
        streams = {
            "living_room": {"producers": [{"url": "nest:?device_id=d1"}]},
            "living_room_sub": {"producers": [{"url": "ffmpeg:living_room#video=h264#width=640"}]},
            "kitchen": {"producers": [{"url": "nest:?device_id=d2"}]},
            "hikvision_1_sub": {"producers": [{"url": "rtsp://x/102"}]},
        }
        self.assertEqual({"living_room_sub": "living_room"}, relay.nest_grid_streams(streams))

    @staticmethod
    def ended(dropped=False, flowed=False, session=True):
        """A hold's watch as it ended: dropped by the watchdog or not, its video having moved or not, go2rtc with a session or none."""
        watch = relay.NestPrimerWatch(0)
        watch.dropped, watch.flowed, watch.session = dropped, flowed, session
        return watch

    def test_go2rtc_is_restarted_when_a_dead_session_outlives_dropping_the_hold(self):
        # 2026-10-19: the session died, the hold was dropped, and the hold taken again joined the
        # same dead session, kept by the doorbell's leaked frame.jpeg viewer.
        restarts = relay.NestGo2rtcRestarts()
        self.assertFalse(restarts.hold_ended("outdoor_doorbell", self.ended(dropped=True, flowed=True), 100))
        self.assertTrue(restarts.hold_ended("outdoor_doorbell", self.ended(dropped=True), 170))

    def test_a_relay_starting_on_a_dead_session_restarts_go2rtc_after_two_drops(self):
        restarts = relay.NestGo2rtcRestarts()
        self.assertFalse(restarts.hold_ended("kids_room", self.ended(dropped=True), 60))
        self.assertTrue(restarts.hold_ended("kids_room", self.ended(dropped=True), 130))

    def test_a_drop_that_heals_the_stream_never_restarts_go2rtc(self):
        # Kitchen on 2026-10-19: no receivers, the hold its only viewer; held again, it flows.
        restarts = relay.NestGo2rtcRestarts()
        for at in range(0, 3600 * 5, 600):
            self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True, flowed=True), at))

    def test_a_camera_google_won_t_stream_never_restarts_go2rtc(self):
        # 2026-10-20: "The camera is not available for streaming" for the doorbell all day. go2rtc
        # never opened a session, so each hold dialed Google afresh; a restart couldn't help.
        restarts = relay.NestGo2rtcRestarts()
        for at in range(0, 3600 * 24, 360):
            self.assertFalse(restarts.hold_ended("outdoor_doorbell", self.ended(dropped=True, session=False), at))

    def test_a_session_gone_before_the_drop_starts_the_count_over(self):
        # Kitchen on 2026-10-19: no receivers left, the hold its last viewer, so dropping it was enough.
        restarts = relay.NestGo2rtcRestarts()
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True), 60))
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True, session=False), 130))
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True), 200))

    def test_a_watch_knows_whether_go2rtc_had_a_session_at_its_last_reading(self):
        watch, _ = self.watched([(15, 9000), (30, 9000)])
        self.assertTrue(watch.session)
        watch, _ = self.watched([(15, 9000), (30, None)])
        self.assertFalse(watch.session)
        watch, _ = self.watched([(15, None), (30, 0)])
        self.assertTrue(watch.session)

    def test_a_hold_go2rtc_ended_starts_the_count_over(self):
        # Google refusing the camera, or go2rtc closing the viewer, means it let go of the session.
        restarts = relay.NestGo2rtcRestarts()
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True), 60))
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(), 70))
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True), 140))
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 210))

    def test_each_stream_counts_its_own_drops(self):
        restarts = relay.NestGo2rtcRestarts()
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True), 60))
        self.assertFalse(restarts.hold_ended("living_room", self.ended(dropped=True), 61))
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 130))
        # The restart ended every stream's hold: living_room starts over too.
        self.assertFalse(restarts.hold_ended("living_room", self.ended(dropped=True), 200))

    def test_restarts_wait_out_a_cooldown_that_doubles_while_they_don_t_help(self):
        cooldown = relay.NEST_PRIMER_RESTART_COOLDOWN_SECONDS
        restarts = relay.NestGo2rtcRestarts()
        dead = lambda at: restarts.hold_ended("kitchen", self.ended(dropped=True), at)  # noqa: E731
        self.assertTrue(dead(0) or dead(1))
        self.assertFalse(dead(100) or dead(cooldown - 1))
        self.assertTrue(dead(cooldown + 1))
        # The first restart didn't bring kitchen back: the next waits twice as long.
        self.assertFalse(dead(cooldown + 100) or dead(3 * cooldown))
        self.assertTrue(dead(3 * cooldown + 2))
        # Capped.
        at = 3 * cooldown + 2
        for _ in range(10):
            at += relay.NEST_PRIMER_MAX_RESTART_COOLDOWN_SECONDS + 1
            self.assertTrue(dead(at - 1) or dead(at))

    def test_a_restart_that_brought_the_stream_back_resets_the_cooldown(self):
        cooldown = relay.NEST_PRIMER_RESTART_COOLDOWN_SECONDS
        restarts = relay.NestGo2rtcRestarts()
        restarts.hold_ended("kitchen", self.ended(dropped=True), 0)
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 1))
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(flowed=True), 2000))
        # Hours later it dies again: one cooldown, not two.
        restarts.hold_ended("kitchen", self.ended(dropped=True), 9000)
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 9070))
        restarts.hold_ended("kitchen", self.ended(dropped=True), 9070 + cooldown)
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 9071 + cooldown))

    def test_recovering_during_a_doubled_cooldown_shortens_the_wait_from_the_last_restart(self):
        cooldown = relay.NEST_PRIMER_RESTART_COOLDOWN_SECONDS
        restarts = relay.NestGo2rtcRestarts()
        dead = lambda at: restarts.hold_ended("kitchen", self.ended(dropped=True), at)  # noqa: E731
        self.assertTrue(dead(0) or dead(1))
        self.assertTrue(dead(cooldown + 1) or dead(cooldown + 2))  # the wait after it: two cooldowns
        last = cooldown + 2
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(flowed=True), last + 100))
        # Back, then dead again before the doubled wait ran out: one cooldown from the last restart.
        self.assertFalse(dead(last + cooldown - 2) or dead(last + cooldown - 1))
        self.assertTrue(dead(last + cooldown))

    def test_two_cameras_dead_by_turns_back_off_like_one(self):
        cooldown = relay.NEST_PRIMER_RESTART_COOLDOWN_SECONDS
        restarts = relay.NestGo2rtcRestarts()
        at, waits, last = 0, [], None
        for name in ["kitchen", "kids_room"] * 4:
            # Each camera stalls again until its own restart comes.
            while not (restarts.hold_ended(name, self.ended(dropped=True), at) or restarts.hold_ended(name, self.ended(dropped=True), at)):
                at += 60
            if last is not None:
                waits.append(at - last)
            last = at
        self.assertEqual([1, 2, 4, 8, 8, 8, 8], [round(wait / cooldown) for wait in waits])

    def test_the_cooldown_resets_only_once_every_restarted_stream_is_back(self):
        cooldown = relay.NEST_PRIMER_RESTART_COOLDOWN_SECONDS
        restarts = relay.NestGo2rtcRestarts()
        restarts.hold_ended("kitchen", self.ended(dropped=True), 0)
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 1))
        restarts.hold_ended("kids_room", self.ended(dropped=True), cooldown + 1)
        self.assertTrue(restarts.hold_ended("kids_room", self.ended(dropped=True), cooldown + 2))
        # kids_room comes back but kitchen doesn't: the doubled wait stands.
        restarts.hold_ended("kids_room", self.ended(flowed=True), cooldown + 100)
        restarts.hold_ended("kitchen", self.ended(dropped=True), 2 * cooldown + 2)
        self.assertFalse(restarts.hold_ended("kitchen", self.ended(dropped=True), 3 * cooldown))
        self.assertTrue(restarts.hold_ended("kitchen", self.ended(dropped=True), 3 * cooldown + 2))

    def test_go2rtc_is_restarted_through_its_api(self):
        posted = []
        post = relay.requests.post
        self.addCleanup(setattr, relay.requests, "post", post)
        relay.requests.post = lambda url, timeout=None: (posted.append(url), types.SimpleNamespace(raise_for_status=lambda: None))[1]
        relay.go2rtc_restart("outdoor_doorbell")
        self.assertEqual([f"{relay.GO2RTC}/api/restart"], posted)

    def test_a_go2rtc_that_can_t_be_restarted_is_only_logged(self):
        post = relay.requests.post
        self.addCleanup(setattr, relay.requests, "post", post)

        def refused(url, timeout=None):
            raise ConnectionError("go2rtc down")

        relay.requests.post = refused
        relay.go2rtc_restart("outdoor_doorbell")


class NestOutageTest(unittest.TestCase):
    """Telling the household when Google won't stream a Nest camera, once, and when it's back."""

    HOUR = 3600
    # A camera's `nest:` source in go2rtc: every value in it is a secret that must never be logged.
    SECRETS = {"client_id": "id-sekrit", "client_secret": "secret-sekrit", "refresh_token": "token-sekrit", "project_id": "project-sekrit", "device_id": "device-sekrit"}

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._path, self._conn, self._outages = relay.DB_PATH, relay.CONN, relay.NEST_OUTAGES
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()
        relay.NEST_OUTAGES = relay.NestOutages()
        self.pushes = []

    def tearDown(self):
        relay.CONN.close()
        relay.DB_PATH, relay.CONN, relay.NEST_OUTAGES = self._path, self._conn, self._outages
        self._dir.cleanup()

    @staticmethod
    def ended(dropped=True, flowed=False, session=False):
        """A hold's watch as it ended; by default one Google refused: dropped with no session."""
        watch = relay.NestPrimerWatch(0)
        watch.dropped, watch.flowed, watch.session = dropped, flowed, session
        return watch

    def push(self, title, body, data):
        self.pushes.append((title, body, data))
        return {"sent": 1}

    def refused_every_six_minutes(self, name, start, hours, check=lambda name: "refused", push=None):
        """[hours] of holds of [name] Google refused, one every 6 minutes from [start]: how often Google was asked."""
        asked = []

        def asking(n):
            asked.append(n)
            return check(n)

        for at in range(int(start), int(start + hours * self.HOUR), 360):
            relay.nest_outage_hold_ended(name, self.ended(), at, check=asking, push=push or self.push)
        return asked

    def test_a_camera_google_refuses_for_an_hour_is_pushed_once_naming_it(self):
        # 2026-10-20: the doorbell refused from 07:00 all day.
        asked = self.refused_every_six_minutes("outdoor_doorbell", 1_792_400_000, 24)
        self.assertEqual(["outdoor_doorbell"], asked)
        self.assertEqual(1, len(self.pushes))
        title, body, data = self.pushes[0]
        self.assertEqual("Outdoor Doorbell isn't streaming", title)
        self.assertIn("Google says the camera isn't available for streaming", body)
        self.assertIn("Google Home app", body)
        self.assertEqual({"system": "nest", "notif_id": "nest-outdoor_doorbell", "camera": "outdoor_doorbell", "nest_camera_name": "Outdoor Doorbell",
                          "nest_event": "unavailable", "nest_reason": "refused", "nest_since": "1792400000"}, data)
        self.assertEqual(["outdoor_doorbell"], relay.state_get(relay.NEST_OUTAGE_STATE_KEY))

    def test_nothing_is_pushed_in_the_first_hour(self):
        self.assertEqual([], self.refused_every_six_minutes("kitchen", 0, 0.99))
        self.assertEqual([], self.pushes)
        relay.nest_outage_hold_ended("kitchen", self.ended(), self.HOUR, check=lambda n: "refused", push=self.push)
        self.assertEqual(1, len(self.pushes))

    def test_a_hold_with_a_session_ends_the_outage_and_its_hour(self):
        # Video moving, or a session go2rtc kept dead (the primer's restarts deal with that one).
        for working in (self.ended(flowed=True, session=True), self.ended(session=True), self.ended(dropped=False, flowed=True)):
            relay.NEST_OUTAGES = relay.NestOutages()
            self.refused_every_six_minutes("kitchen", 0, 0.9)
            relay.nest_outage_hold_ended("kitchen", working, 0.9 * self.HOUR, check=lambda n: "refused", push=self.push)
            self.refused_every_six_minutes("kitchen", 0.95 * self.HOUR, 0.9)
            self.assertEqual([], self.pushes)

    def test_a_hold_that_fails_before_any_session_counts_too(self):
        for at in range(0, self.HOUR + 1, 360):
            relay.nest_outage_hold_ended("kitchen", self.ended(dropped=False), at, check=lambda n: "refused", push=self.push)
        self.assertEqual(1, len(self.pushes))

    def test_a_camera_google_does_stream_isn_t_pushed_and_its_hour_starts_over(self):
        # Google answered with a session: the trouble is go2rtc's or the network's, not the Google Home app's.
        asked = self.refused_every_six_minutes("kitchen", 0, 2.5, check=lambda n: None)
        self.assertEqual(["kitchen", "kitchen"], asked)
        self.assertEqual([], self.pushes)
        self.assertIsNone(relay.state_get(relay.NEST_OUTAGE_STATE_KEY))

    def test_a_push_no_phone_got_is_tried_again_without_asking_google_again(self):
        sent = []
        quiet = lambda title, body, data: (sent.append(title), {"sent": 0, "skipped_quiet": 2})[1]  # noqa: E731
        asked = self.refused_every_six_minutes("kitchen", 0, 1.2, push=quiet)
        self.assertEqual(["kitchen"], asked)
        self.assertEqual(2, len(sent), "the hour's hold and the one after it")
        # Quiet hours over: the next hold's push reaches a phone, and that's the last.
        asked += self.refused_every_six_minutes("kitchen", 1.2 * self.HOUR, 5)
        self.assertEqual(["kitchen"], asked)
        self.assertEqual(1, len(self.pushes))
        self.assertIn("Google says", self.pushes[0][1])

    def test_an_unconfirmed_outage_is_pushed_in_other_words(self):
        self.refused_every_six_minutes("kitchen", 0, 2, check=lambda n: "unconfirmed")
        self.assertEqual(1, len(self.pushes))
        title, body, data = self.pushes[0]
        self.assertEqual("Kitchen isn't streaming", title)
        self.assertTrue(body.startswith("No video from Google since "), body)
        self.assertEqual("unconfirmed", data["nest_reason"])

    def test_the_camera_coming_back_is_pushed_once(self):
        self.refused_every_six_minutes("outdoor_doorbell", 0, 2)
        for _ in range(5):
            relay.nest_outage_streaming("outdoor_doorbell", push=self.push)
        self.assertEqual(2, len(self.pushes))
        title, body, data = self.pushes[1]
        self.assertEqual("Outdoor Doorbell is streaming again", title)
        self.assertEqual({"system": "nest", "notif_id": "nest-outdoor_doorbell", "camera": "outdoor_doorbell", "nest_camera_name": "Outdoor Doorbell", "nest_event": "streaming"}, data)
        self.assertIsNone(relay.state_get(relay.NEST_OUTAGE_STATE_KEY))

    def test_a_camera_that_was_never_pushed_coming_back_says_nothing(self):
        self.refused_every_six_minutes("kitchen", 0, 0.5)
        relay.nest_outage_streaming("kitchen", push=self.push)
        self.assertEqual([], self.pushes)
        # Its hour started over.
        self.refused_every_six_minutes("kitchen", 0.6 * self.HOUR, 0.9)
        self.assertEqual([], self.pushes)

    def test_the_next_outage_is_pushed_again(self):
        self.refused_every_six_minutes("kitchen", 0, 2)
        relay.nest_outage_streaming("kitchen", push=self.push)
        self.refused_every_six_minutes("kitchen", 10 * self.HOUR, 2)
        self.assertEqual(["Kitchen isn't streaming", "Kitchen is streaming again", "Kitchen isn't streaming"], [p[0] for p in self.pushes])

    def test_each_camera_has_its_own_outage(self):
        self.refused_every_six_minutes("kitchen", 0, 0.5)
        self.refused_every_six_minutes("kids_room", 0, 2)
        self.assertEqual(["Kids Room isn't streaming"], [p[0] for p in self.pushes])

    def test_a_relay_restarted_mid_outage_doesn_t_push_it_again_but_does_its_end(self):
        self.refused_every_six_minutes("outdoor_doorbell", 0, 2)
        relay.NEST_OUTAGES = relay.NestOutages()  # the relay restarts
        relay.nest_outage_load()
        self.refused_every_six_minutes("outdoor_doorbell", 2 * self.HOUR, 5)
        relay.nest_outage_streaming("outdoor_doorbell", push=self.push)
        self.assertEqual(["Outdoor Doorbell isn't streaming", "Outdoor Doorbell is streaming again"], [p[0] for p in self.pushes])

    def test_a_return_no_phone_got_stays_owed_and_is_tried_again_later_not_at_every_reading(self):
        self.refused_every_six_minutes("outdoor_doorbell", 0, 2)
        tries = []
        quiet = lambda title, body, data: (tries.append(title), {"sent": 0, "skipped_quiet": 2})[1]  # noqa: E731
        back = 3 * self.HOUR
        for at in range(back, back + 299, 15):  # the watchdog's readings for five minutes
            relay.nest_outage_streaming("outdoor_doorbell", now=at, push=quiet)
        self.assertEqual(1, len(tries))
        self.assertEqual(["outdoor_doorbell"], relay.state_get(relay.NEST_OUTAGE_STATE_KEY), "still owed across a restart")
        self.assertTrue(relay.NEST_OUTAGES.owed("outdoor_doorbell"))
        # Quiet hours over: the next try reaches a phone, and that's the last.
        for at in range(back + 300, back + 3600, 15):
            relay.nest_outage_streaming("outdoor_doorbell", now=at, push=self.push)
        self.assertEqual(["Outdoor Doorbell isn't streaming", "Outdoor Doorbell is streaming again"], [p[0] for p in self.pushes])
        self.assertFalse(relay.NEST_OUTAGES.owed("outdoor_doorbell"))
        self.assertIsNone(relay.state_get(relay.NEST_OUTAGE_STATE_KEY))

    def test_a_return_whose_push_failed_is_tried_again(self):
        self.refused_every_six_minutes("kitchen", 0, 2)

        def failing(title, body, data):
            raise ConnectionError("fcm down")

        with self.assertLogs("relay", level="WARNING"):
            relay.nest_outage_streaming("kitchen", now=3 * self.HOUR, push=failing)
        self.assertFalse(relay.NEST_OUTAGES.busy, "a failed push leaves nothing in flight")
        relay.nest_outage_streaming("kitchen", now=3 * self.HOUR + 300, push=self.push)
        self.assertEqual(["Kitchen isn't streaming", "Kitchen is streaming again"], [p[0] for p in self.pushes])

    def test_a_return_being_pushed_isn_t_pushed_twice(self):
        outages = relay.NestOutages({"kitchen"})
        self.assertTrue(outages.streaming("kitchen", 0))
        self.assertFalse(outages.streaming("kitchen", 15), "the first push is still in flight")
        outages.recovered("kitchen", True, 20)
        self.assertFalse(outages.streaming("kitchen", 30))

    def test_out_again_before_a_phone_heard_it_was_back_is_still_the_one_outage(self):
        self.refused_every_six_minutes("kitchen", 0, 2)
        quiet = lambda title, body, data: {"sent": 0, "skipped_quiet": 2}  # noqa: E731
        relay.nest_outage_streaming("kitchen", now=3 * self.HOUR, push=quiet)
        # Refused again all night: the phones still show the outage, so it isn't pushed again.
        self.refused_every_six_minutes("kitchen", 3 * self.HOUR, 10)
        relay.nest_outage_streaming("kitchen", now=14 * self.HOUR, push=self.push)
        self.assertEqual(["Kitchen isn't streaming", "Kitchen is streaming again"], [p[0] for p in self.pushes])

    def test_a_relay_restarted_before_a_return_was_told_still_tells_it(self):
        self.refused_every_six_minutes("kitchen", 0, 2)
        relay.nest_outage_streaming("kitchen", now=3 * self.HOUR, push=lambda title, body, data: {"sent": 0})
        relay.NEST_OUTAGES = relay.NestOutages()  # the relay restarts
        relay.nest_outage_load()
        relay.nest_outage_streaming("kitchen", now=3 * self.HOUR + 60, push=self.push)
        self.assertEqual(["Kitchen isn't streaming", "Kitchen is streaming again"], [p[0] for p in self.pushes])

    def test_a_save_that_failed_is_saved_at_the_next_hold_without_pushing_again(self):
        state_set = relay.state_set
        self.addCleanup(setattr, relay, "state_set", state_set)

        def locked(key, value):
            raise relay.sqlite3.OperationalError("database is locked")

        relay.state_set = locked
        with self.assertLogs("relay", level="WARNING"):
            self.refused_every_six_minutes("outdoor_doorbell", 0, 1.05)
        self.assertTrue(relay.NEST_OUTAGES.dirty)
        relay.state_set = state_set
        self.refused_every_six_minutes("outdoor_doorbell", 1.1 * self.HOUR, 0.1)
        self.assertFalse(relay.NEST_OUTAGES.dirty)
        self.assertEqual(["outdoor_doorbell"], relay.state_get(relay.NEST_OUTAGE_STATE_KEY))
        self.assertEqual(1, len(self.pushes))

    def test_saves_land_in_the_order_they_read_the_state(self):
        # A save that read only kitchen must not land after one that read kitchen and the doorbell.
        state_set = relay.state_set
        self.addCleanup(setattr, relay, "state_set", state_set)
        first_writing, release, written = threading.Event(), threading.Event(), []

        def slow(key, value):
            if not written:
                first_writing.set()
                release.wait(5)
            written.append(value)
            state_set(key, value)

        relay.state_set = slow
        relay.NEST_OUTAGES.alerted.add("kitchen")
        first = threading.Thread(target=relay.nest_outage_save, daemon=True)
        first.start()
        self.assertTrue(first_writing.wait(5))
        relay.NEST_OUTAGES.alerted.add("outdoor_doorbell")
        second = threading.Thread(target=relay.nest_outage_save, daemon=True)
        second.start()
        time.sleep(0.05)
        release.set()
        first.join(5)
        second.join(5)
        self.assertEqual([["kitchen"], ["kitchen", "outdoor_doorbell"]], written)
        self.assertEqual(["kitchen", "outdoor_doorbell"], relay.state_get(relay.NEST_OUTAGE_STATE_KEY))

    def test_a_check_already_running_isn_t_started_twice(self):
        outages = relay.NestOutages()
        self.assertFalse(outages.hold_ended("kitchen", self.ended(), 0))
        self.assertTrue(outages.hold_ended("kitchen", self.ended(), self.HOUR))
        self.assertFalse(outages.hold_ended("kitchen", self.ended(), self.HOUR + 1))

    def test_the_push_is_an_ordinary_alert_through_broadcast(self):
        calls = []
        broadcast = relay.broadcast
        self.addCleanup(setattr, relay, "broadcast", broadcast)
        relay.broadcast = lambda title, body, data, **kw: (calls.append(kw), {"sent": 1})[1]
        relay.nest_outage_push("kitchen", "refused", 0.0)
        self.assertEqual([{}], calls, "not away: each phone's quiet hours and only-away apply")

    # ------------------------------------------------ asking Google

    def nest_stream(self, secrets=None):
        query = "&".join(f"{k}={v}" for k, v in (self.SECRETS if secrets is None else secrets).items())
        return {"producers": [{"url": f"nest:?{query}", "receivers": []}]}

    def fake_google(self, stream, *answers):
        """go2rtc answers [stream] (or raises it), and Google's token and executeCommand calls [answers] in turn; returns the posts."""
        posts, answers = [], list(answers)
        for name in ("get", "post"):
            self.addCleanup(setattr, relay.requests, name, getattr(relay.requests, name))

        def get(url, params=None, timeout=None):
            if isinstance(stream, Exception):
                raise stream
            return types.SimpleNamespace(json=lambda: stream)

        def post(url, data=None, json=None, headers=None, timeout=None):
            posts.append((url, data, json, headers))
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer
            status, body = answer
            return types.SimpleNamespace(ok=status < 400, status_code=status, json=lambda: body)

        relay.requests.get, relay.requests.post = get, post
        return posts

    TOKEN = (200, {"access_token": "access-sekrit", "expires_in": 3599})
    REFUSED = (400, {"error": {"code": 400, "message": "The camera is not available for streaming.", "status": "FAILED_PRECONDITION"}})

    def checked(self, name="outdoor_doorbell"):
        """nest_google_check([name]) and everything it logged, which must carry none of the secrets."""
        with self.assertLogs("relay", level="INFO") as logs:
            relay.log.info("checking")
            result = relay.nest_google_check(name)
        text = "\n".join(logs.output)
        for secret in [*self.SECRETS.values(), "access-sekrit"]:
            self.assertNotIn(secret, text)
        return result, text

    def test_google_saying_the_camera_isn_t_available_confirms_the_outage(self):
        posts = self.fake_google(self.nest_stream(), self.TOKEN, self.REFUSED)
        result, text = self.checked()
        self.assertEqual("refused", result)
        self.assertIn("The camera is not available for streaming", text)
        (token_url, form, _, _), (command, _, body, headers) = posts
        self.assertEqual(relay.NEST_TOKEN_URL, token_url)
        self.assertEqual({"client_id": "id-sekrit", "client_secret": "secret-sekrit", "refresh_token": "token-sekrit", "grant_type": "refresh_token"}, form)
        self.assertEqual(f"{relay.NEST_SDM_URL}/enterprises/project-sekrit/devices/device-sekrit:executeCommand", command)
        self.assertEqual({"Authorization": "Bearer access-sekrit"}, headers)
        self.assertEqual("sdm.devices.commands.CameraLiveStream.GenerateWebRtcStream", body["command"])

    def test_a_camera_google_streams_has_its_session_stopped_and_nothing_to_tell(self):
        started = (200, {"results": {"answerSdp": "v=0", "expiresAt": "2026-10-20T12:05:00Z", "mediaSessionId": "session-1"}})
        posts = self.fake_google(self.nest_stream(), self.TOKEN, started, (200, {}))
        result, _ = self.checked()
        self.assertIsNone(result)
        self.assertEqual({"command": "sdm.devices.commands.CameraLiveStream.StopWebRtcStream", "params": {"mediaSessionId": "session-1"}}, posts[2][2])

    def test_any_other_answer_leaves_the_outage_unconfirmed(self):
        cases = [
            (self.TOKEN, (400, {"error": {"code": 400, "message": "Invalid SDP for project-sekrit/device-sekrit", "status": "INVALID_ARGUMENT"}})),
            (self.TOKEN, (503, "not json")),
            (self.TOKEN, (500, {"error": "backend sekrit-free"})),
            ((401, {"error": "invalid_grant"}),),
            (ConnectionError("https://oauth2.googleapis.com/token?refresh_token=token-sekrit"),),
            (self.TOKEN, TimeoutError("device-sekrit")),
        ]
        for answers in cases:
            self.fake_google(self.nest_stream(), *answers)
            self.assertEqual("unconfirmed", self.checked()[0], answers)
        self.fake_google(self.nest_stream({"device_id": "device-sekrit"}))
        self.assertEqual("unconfirmed", self.checked()[0], "a source without the credentials")

    def test_without_go2rtc_s_nest_source_there_is_nothing_to_tell(self):
        for stream in (ConnectionError("go2rtc restarting"), {}, {"producers": [{"url": "ffmpeg:kitchen"}]}):
            self.fake_google(stream)
            with self.assertLogs("relay", level="INFO"):
                relay.log.info("checking")
                self.assertIsNone(relay.nest_google_check("kitchen"), stream)

    def test_the_probe_offer_is_audio_then_video_then_a_data_channel(self):
        offer = relay.nest_probe_offer()
        self.assertTrue(offer.endswith("\r\n"))
        media = [line.split()[0] for line in offer.split("\r\n") if line.startswith("m=")]
        self.assertEqual(["m=audio", "m=video", "m=application"], media)
        self.assertIn("a=rtpmap:96 H264/90000", offer)
        self.assertNotEqual(offer, relay.nest_probe_offer(), "made up afresh each time")

    def test_the_watchdog_pushes_the_end_of_an_outage_once_the_video_moves(self):
        for name, seconds in (("NEST_PRIMER_WATCH_SECONDS", 0.01), ("NEST_PRIMER_STALL_SECONDS", 5)):
            self.addCleanup(setattr, relay, name, getattr(relay, name))
            setattr(relay, name, seconds)
        counts = iter(range(1000, 10**9, 1000))
        broadcast, get = relay.broadcast, relay.requests.get
        self.addCleanup(setattr, relay, "broadcast", broadcast)
        self.addCleanup(setattr, relay.requests, "get", get)
        pushed = threading.Event()
        relay.broadcast = lambda title, body, data, **kw: (self.pushes.append(title), pushed.set(), {"sent": 1})[2]
        relay.requests.get = lambda url, params=None, timeout=None: types.SimpleNamespace(
            json=lambda c=next(counts): {"producers": [{"receivers": [{"codec": {"codec_type": "video"}, "bytes": c}]}]}
        )
        relay.NEST_OUTAGES.alerted.add("outdoor_doorbell")
        held, far = socket.socketpair()
        self.addCleanup(held.close)
        self.addCleanup(far.close)
        watch, done = relay.NestPrimerWatch(time.monotonic()), threading.Event()
        watchdog = threading.Thread(target=relay.nest_primer_watchdog, args=("outdoor_doorbell", held, watch, done), daemon=True)
        watchdog.start()
        self.assertTrue(pushed.wait(5), "the camera's return was never pushed")
        time.sleep(0.2)
        done.set()
        watchdog.join(timeout=5)
        self.assertEqual(["Outdoor Doorbell is streaming again"], self.pushes)


def _metres(origin, east, north):
    """The point `east` and `north` metres from `origin` (lat, lng)."""
    import math

    return origin[0] + north / 111_195.0, origin[1] + east / (111_195.0 * math.cos(math.radians(origin[0])))


class ParkingTest(_FakeFrigate):
    """
    Which street and side a Tesla is parked on, and telling its drivers before it must move. Made-up
    streets: Elm St runs east-west, Oak St north-south across it, and the house is north of Elm
    and west of Oak.
    """

    ZONE = "America/Los_Angeles"
    CROSSING = (37.7700, -122.2580)
    ELM = [list(_metres(CROSSING, -200, 0)), list(_metres(CROSSING, 200, 0))]
    OAK = [list(_metres(CROSSING, 0, -150)), list(_metres(CROSSING, 0, 150))]
    HOME = dict(zip(("lat", "lng"), _metres(CROSSING, -40, 25)), radius_m=150)
    SWEEPING = {"kind": "no_parking", "days": ["thu"], "weeks": [1, 3], "start": "08:00", "end": "11:00", "note": "Street sweeping"}
    # Wednesday 2026-11-04, 7 PM, the evening before the month's first Thursday.
    WED_7PM = 1_793_847_600.0

    def setUp(self):
        super().setUp()
        self._parking = {n: getattr(relay, n) for n in (
            "TESLA_CLIENT_ID", "TESLA_CLIENT_SECRET", "TESLA_PUBLIC_URL", "TESLA_CARS", "PARKING_FILE", "tesla_location")}
        relay.TESLA_CLIENT_ID, relay.TESLA_CLIENT_SECRET, relay.TESLA_PUBLIC_URL = "client", "secret", "https://box.example.ts.net"
        relay.TESLA_CARS = {"andrews_tesla": "VIN_A", "sarahs_car": "VIN_S"}
        relay.PARKING_FILE = os.path.join(self._dir.name, "parking.json")
        relay._parking_file.update(mtime=None, config={})
        relay._tesla_answers.clear()
        relay._tesla_live.clear()
        relay.state_set(relay.HOME_KEY, self.HOME)
        self.where = {}  # vin -> tesla_location's answer
        self.asked = []
        relay.tesla_location = lambda vin: (self.asked.append(vin), self.where.get(vin))[1]
        self.pushes = []  # (token, title, body, data)
        self.config({
            "tz": self.ZONE,
            "streets": [
                {"street": "Elm St", "line": self.ELM, "sides": {"north": [self.SWEEPING], "south": [
                    {"kind": "no_parking", "days": ["fri"], "weeks": [2, 4], "start": "10:00", "end": "13:00", "note": "Street sweeping"}]}},
                {"street": "Oak St", "line": self.OAK, "only": {"side": "north", "of": "Elm St"}, "sides": {
                    "home": [{"kind": "no_parking", "days": ["fri"], "start": "10:00", "end": "13:00"}],
                    "across": [{"kind": "no_parking", "days": ["thu"], "start": "08:00", "end": "11:00"}]}},
            ],
        })
        self.phone("pixel", "t-pixel", user="andrew")

    def tearDown(self):
        for name, value in self._parking.items():
            setattr(relay, name, value)
        relay._tesla_answers.clear()
        relay._tesla_live.clear()
        relay._parking_file.update(mtime=None, config={})
        super().tearDown()

    def config(self, config):
        with open(relay.PARKING_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f)
        relay._parking_file["mtime"] = None

    def phone(self, device_id, token, user=None, quiet=(None, None)):
        relay.with_db(lambda c: (c.execute(
            "INSERT INTO devices (device_id, token, platform, name, user, quiet_start, quiet_end, tz) VALUES (?,?,?,?,?,?,?,?)",
            (device_id, token, "android", device_id, user, quiet[0], quiet[1], self.ZONE)), c.commit()))

    def fix(self, east, north, heading, at, **more):
        lat, lng = _metres(self.CROSSING, east, north)
        return {"lat": lat, "lng": lng, "heading": heading, "at": at, "shift": None, "speed": None, **more}

    def match(self, east, north, heading):
        stretches = relay.parking_stretches(relay.parking_config(), self.HOME, self.WED_7PM)
        found = relay.parking_match(self.fix(east, north, heading, 0), stretches)
        return found and (found["street"], found["side"], [r.get("days") for r in found["rules"]])

    def push(self, token, title, body, data, away=False):
        self.pushes.append((token, title, body, data))
        return "sent"

    def tick(self, at):
        relay._tesla_answers.clear()  # every tick a fresh answer, as if car presence had just asked
        return [(p["event"], p["title"], p["body"], p["to"]) for p in relay.parking_tick(at, push=self.push)]

    def test_the_side_is_the_one_whose_traffic_the_car_faces(self):
        self.assertEqual(("Elm St", "north", [["thu"]]), self.match(60, 5, 270), "facing west keeps right on the north side")
        self.assertEqual(("Elm St", "south", [["fri"]]), self.match(60, -5, 90))
        self.assertEqual(("Elm St", "north", [["thu"]]), self.match(60, -2, 265), "a fix a little south, but facing the north side's way")

    def test_without_a_heading_the_fix_decides_and_on_the_middle_every_side_counts(self):
        self.assertEqual(("Elm St", "south", [["fri"]]), self.match(60, -5, None))
        self.assertEqual(("Elm St", None, [["thu"], ["fri"]]), self.match(60, 0.5, None))

    def test_facing_across_the_street_or_off_it_is_not_parked_at_the_curb(self):
        self.assertIsNone(self.match(-60, 9, 180), "nosed into a driveway")
        self.assertIsNone(self.match(-60, 40, 270), "well off the street")
        self.assertIsNone(self.match(500, 0, 90), "past the end of the stretch")

    def test_oak_counts_only_north_of_elm_and_its_sides_are_the_houses_and_across(self):
        self.assertEqual(("Oak St", "west", [["fri"]]), self.match(-4, 60, 180), "the house's side: west")
        self.assertEqual(("Oak St", "east", [["thu"]]), self.match(4, 60, 0))
        self.assertIsNone(self.match(-4, -60, 180), "south of Elm, Oak has no signs")

    def test_within_keeps_only_the_blocks_near_home(self):
        config = relay.parking_config()
        config["streets"][0]["within_m"] = 100
        self.config(config)
        self.assertEqual(("Elm St", "north", [["thu"]]), self.match(-60, 5, 270))
        self.assertIsNone(self.match(150, 5, 270), "the next block east: its signs weren't read")

    def test_a_one_way_street_goes_by_the_fix(self):
        config = relay.parking_config()
        config["streets"][0]["one_way"] = True
        self.config(config)
        self.assertEqual(("Elm St", "south", [["fri"]]), self.match(60, -5, 270))

    def test_first_and_third_thursday_mornings(self):
        zone = relay.ZoneInfo(self.ZONE)
        found = relay.rule_deadline(self.SWEEPING, self.WED_7PM, self.WED_7PM, zone)
        self.assertEqual(self.WED_7PM + 13 * 3600, found["at"], "Thursday 8 AM")
        self.assertEqual(self.WED_7PM + 16 * 3600, found["until"])
        after = relay.rule_deadline(self.SWEEPING, self.WED_7PM, found["until"], zone)
        self.assertEqual(found["at"] + 14 * 86400, after["at"], "then the third Thursday, not the second")
        last = {"kind": "no_parking", "days": ["thu"], "weeks": [-1], "start": "08:00", "end": "11:00"}
        self.assertEqual(found["at"] + 21 * 86400, relay.rule_deadline(last, self.WED_7PM, self.WED_7PM, zone)["at"], "the 26th, the last")

    def test_a_time_limit_counts_from_parking_or_the_window_opening(self):
        zone = relay.ZoneInfo(self.ZONE)
        two_hours = {"kind": "limit", "minutes": 120, "days": ["mon", "tue", "wed", "thu", "fri"], "start": "08:00", "end": "18:00"}
        thursday_7am = self.WED_7PM + 12 * 3600
        self.assertEqual(thursday_7am + 3 * 3600, relay.rule_deadline(two_hours, thursday_7am, thursday_7am, zone)["at"], "10 AM")
        thursday_9am = thursday_7am + 2 * 3600
        self.assertEqual(thursday_9am + 2 * 3600, relay.rule_deadline(two_hours, thursday_9am, thursday_9am, zone)["at"])
        thursday_5pm = thursday_7am + 10 * 3600
        self.assertEqual(thursday_7am + 27 * 3600, relay.rule_deadline(two_hours, thursday_5pm, thursday_5pm, zone)["at"], "the window closes first: Friday 10 AM")
        permit = dict(two_hours, permit="B")
        self.assertIsNone(relay.parking_deadline([permit], thursday_9am, thursday_9am, zone, {"B"}))

    def test_told_the_evening_before_shortly_before_and_then_that_it_moved(self):
        self.where["VIN_A"] = self.fix(60, 5, 270, self.WED_7PM - 600)
        self.assertEqual([], self.tick(self.WED_7PM), "an 8 AM sweeping waits for the evening")
        self.assertEqual([("due", "Move Andrew's Tesla by Thu 8:00 AM", "North side of Elm St · Street sweeping", ["pixel"])],
                         self.tick(self.WED_7PM + 3600))
        data = self.pushes[-1][3]
        self.assertEqual(("1", "parking-andrews_tesla", "north", "Elm St"), (data["parking"], data["notif_id"], data["parking_side"], data["parking_street"]))
        spot = self.where["VIN_A"]
        self.assertEqual((f"{spot['lat']:.5f}", f"{spot['lng']:.5f}", "270"), (data["parking_lat"], data["parking_lng"], data["parking_heading"]), "where, for the phone's map")
        self.assertEqual([], self.tick(self.WED_7PM + 3700), "once")
        self.where["VIN_A"] = {"asleep": True}
        self.assertEqual([("due", "Move Andrew's Tesla by 8:00 AM", "North side of Elm St · Street sweeping", ["pixel"])],
                         self.tick(self.WED_7PM + 12.5 * 3600), "asleep, it is still there: half an hour before")
        self.assertEqual([("now", "Move Andrew's Tesla now", "North side of Elm St · Street sweeping", ["pixel"])],
                         self.tick(self.WED_7PM + 13 * 3600))
        self.where["VIN_A"] = self.fix(-500, 300, 10, self.WED_7PM + 13.1 * 3600, shift="D", speed=25.0)
        self.assertEqual([("moved", "Andrew's Tesla has moved", "No longer parked on Elm St", ["pixel"])], self.tick(self.WED_7PM + 13.1 * 3600))
        self.assertEqual("1", self.pushes[-1][3]["silent"])
        self.assertEqual([], self.tick(self.WED_7PM + 14 * 3600))

    def test_the_status_says_where_each_car_was_last_seen_wherever_that_is(self):
        self.where = {"VIN_A": self.fix(60, 5, 270, self.WED_7PM + 3600), "VIN_S": self.fix(-40, 25, 90, self.WED_7PM + 3600)}
        self.tick(self.WED_7PM + 3600)
        cars = {c["car"]: c for c in relay.parking_status(self.WED_7PM + 3700)["cars"]}
        andrew, sarah = cars["andrews_tesla"], cars["sarahs_car"]
        self.assertEqual(("Elm St", "north"), (andrew["street"], andrew["side"]))
        self.assertEqual(("Street sweeping", andrew["move_by"], andrew["move_by"] + 3 * 3600),
                         (andrew["rule"]["note"], andrew["window"]["from"], andrew["window"]["until"]), "Thursday 8 to 11")
        self.assertEqual((self.where["VIN_A"]["lat"], self.where["VIN_A"]["lng"], 270, False, True),
                         tuple(andrew["last"][k] for k in ("lat", "lng", "heading", "moving", "home")), "out front: at the curb, and home")
        self.assertIsNone(sarah["street"], "in the driveway, on no watched street")
        self.assertEqual((True, False), (sarah["last"]["home"], sarah["last"]["moving"]))
        self.assertEqual((False, False), (andrew["asleep"], sarah["asleep"]))

        self.where = {"VIN_A": {"asleep": True}, "VIN_S": self.fix(-2000, 900, 10, self.WED_7PM + 7200, shift="D", speed=30.0)}
        self.tick(self.WED_7PM + 7200)
        cars = {c["car"]: c for c in relay.parking_status(self.WED_7PM + 7300)["cars"]}
        andrew, sarah = cars["andrews_tesla"], cars["sarahs_car"]
        self.assertTrue(andrew["asleep"])
        self.assertEqual((_metres(self.CROSSING, 60, 5), "Elm St"), ((andrew["last"]["lat"], andrew["last"]["lng"]), andrew["street"]), "asleep, it is where it was")
        self.assertEqual((True, False, self.WED_7PM + 7200), (sarah["last"]["moving"], sarah["last"]["home"], sarah["last"]["at"]), "driving, away from home")

    def test_the_status_draws_each_curb_with_its_signs_and_when_they_next_hold(self):
        streets = relay.parking_status(self.WED_7PM)["streets"]
        elm, oak = streets
        self.assertEqual(("Elm St", 2, relay.PARKING_WIDTH_METRES), (elm["street"], len(elm["line"]), elm["width_m"]))
        north, south = elm["sides"]
        self.assertEqual(("north", "left"), (north["side"], north["offset"]), "Elm runs east: its north curb is on the left")
        self.assertEqual(("south", "right"), (south["side"], south["offset"]))
        self.assertEqual("Street sweeping", north["rules"][0]["note"])
        self.assertEqual({"from": self.WED_7PM + 13 * 3600, "until": self.WED_7PM + 16 * 3600}, north["rules"][0]["next"], "the 1st Thursday, 8 to 11")
        self.assertEqual(self.WED_7PM + (8 * 24 + 15) * 3600, south["rules"][0]["next"]["from"], "Friday the 13th, the month's 2nd, at 10")
        self.assertFalse(north["rules"][0]["lifted"])
        west, east = oak["sides"]
        self.assertEqual(("west", "left", ["fri"]), (west["side"], west["offset"], west["rules"][0]["days"]), "the house's side, west of Oak")
        self.assertEqual(("east", ["thu"]), (east["side"], east["rules"][0]["days"]), "across")
        self.assertEqual(self.WED_7PM + 13 * 3600, east["rules"][0]["next"]["from"], "every Thursday")

        self.config({"tz": self.ZONE, "permits": ["B"], "streets": [{"street": "Elm St", "line": self.ELM, "sides": {
            "both": [{"kind": "limit", "minutes": 120, "start": "08:00", "end": "18:00", "permit": "B"}]}}]})
        both = relay.parking_status(self.WED_7PM)["streets"][0]["sides"]
        self.assertEqual([("north", True), ("south", True)], [(s["side"], s["rules"][0]["lifted"]) for s in both], "the household's permit lifts it")
        self.assertEqual(self.WED_7PM + 13 * 3600, both[0]["rules"][0]["next"]["from"], "8 AM tomorrow")

    def test_a_car_that_streams_is_parked_from_when_it_said_so_and_shown_live(self):
        lat, lng = _metres(self.CROSSING, 60, 5)
        at = self.WED_7PM + 3600

        def stream(when, **fields):
            for field, value in fields.items():
                relay.tesla_stream_receive(f"tesla/VIN_S/v/{field}", json.dumps(value).encode(), when)

        stream(at - 120, Location={"latitude": lat + 0.01, "longitude": lng}, Gear="ShiftStateD", VehicleSpeed=20.0, Soc=64)
        stream(at, Location={"latitude": lat, "longitude": lng}, GpsHeading=270, VehicleSpeed=0.0)
        stream(at + 5, Gear="ShiftStateP")
        stream(at + 30, GpsHeading=268)
        relay.parking_tick(at + 50, push=self.push)
        spot = (relay.state_get(relay.PARKING_KEY) or {})["sarahs_car"]["spot"]
        self.assertEqual(("Elm St", "north", at + 5), (spot["street"], spot["side"], spot["since"]), "parked as it shifted into P, not up to hours before")
        self.assertNotIn("VIN_S", self.asked, "the stream said where it is")
        sarah = next(c for c in relay.parking_status(at + 60)["cars"] if c["car"] == "sarahs_car")
        self.assertEqual((False, 64.0, True), (sarah["last"]["moving"], sarah["live"]["battery"], sarah["live"]["connected"]))

    def test_a_car_first_heard_already_parked_keeps_the_cautious_since(self):
        lat, lng = _metres(self.CROSSING, 60, 5)
        at = self.WED_7PM + 3600
        for field, value in {"Location": {"latitude": lat, "longitude": lng}, "GpsHeading": 270, "Gear": "ShiftStateP"}.items():
            relay.tesla_stream_receive(f"tesla/VIN_S/v/{field}", json.dumps(value).encode(), at)
        relay.tesla_stream_receive("tesla/VIN_S/v/GpsHeading", b"271", at + 40)
        relay.parking_tick(at + 50, push=self.push)
        spot = (relay.state_get(relay.PARKING_KEY) or {})["sarahs_car"]["spot"]
        self.assertEqual(at + 40 - max(relay.TESLA_CHECK_SECONDS, relay.TESLA_ASLEEP_CHECK_SECONDS, relay.TESLA_STREAM_ASLEEP_CHECK_SECONDS), spot["since"],
                         "never seen shifting into P: it may have been there a while")

    def test_with_no_watched_streets_the_cars_are_still_kept_where_presence_last_heard_them(self):
        self.config({"tz": self.ZONE, "streets": []})
        relay._tesla_answers["andrews_tesla"] = (self.WED_7PM + 60, self.fix(-2000, 900, 10, self.WED_7PM, shift="D", speed=30.0))
        relay._tesla_answers["sarahs_car"] = (self.WED_7PM + 60, {"asleep": True})
        self.assertEqual([], relay.parking_tick(self.WED_7PM + 120, push=self.push))
        self.assertEqual([], self.asked, "Tesla isn't asked for it")
        cars = {c["car"]: c for c in relay.parking_status(self.WED_7PM + 180)["cars"]}
        self.assertEqual((True, False, self.WED_7PM), (cars["andrews_tesla"]["last"]["moving"], cars["andrews_tesla"]["last"]["home"], cars["andrews_tesla"]["last"]["at"]))
        self.assertIsNone(cars["andrews_tesla"]["street"])
        self.assertTrue(cars["sarahs_car"]["asleep"])
        self.assertIsNone(cars["sarahs_car"]["last"], "asleep since before it was ever seen")
        # An older fix than the one kept doesn't replace it.
        relay._tesla_answers["andrews_tesla"] = (self.WED_7PM + 300, self.fix(0, 0, 0, self.WED_7PM - 200))  # fresh when given, but older
        relay.parking_tick(self.WED_7PM + 360, push=self.push)
        self.assertEqual(self.WED_7PM, relay.parking_status(self.WED_7PM + 400)["cars"][0]["last"]["at"])

    def test_a_car_the_camera_saw_arrive_after_teslas_last_fix_is_home(self):
        far = {**self.fix(-2000, 8000, 330, self.WED_7PM), "moving": False}
        relay.state_set(relay.PARKING_KEY, {"andrews_tesla": {"last": far, "asleep": True}, "sarahs_car": {"last": far, "asleep": True}})
        relay.state_set(relay.CAR_PRESENCE_KEY, {
            "andrews_tesla": {"here": False, "since": self.WED_7PM + 600, "known": True},
            "sarahs_car": {"here": True, "since": self.WED_7PM + 600, "known": True},
        })
        cars = {c["car"]: c for c in relay.parking_status(self.WED_7PM + 900)["cars"]}
        sarah = cars["sarahs_car"]["last"]
        self.assertEqual((self.HOME["lat"], self.HOME["lng"], self.WED_7PM + 600, False, True),
                         (sarah["lat"], sarah["lng"], sarah["at"], sarah["moving"], sarah["home"]), "at the house, since it arrived")
        self.assertEqual((self.WED_7PM, False), (cars["andrews_tesla"]["last"]["at"], cars["andrews_tesla"]["last"]["home"]), "away by the camera too")

    def test_an_arrival_before_teslas_last_fix_or_a_fix_at_home_keeps_the_fix(self):
        far = {**self.fix(-2000, 8000, 330, self.WED_7PM), "moving": True}
        curb = {**self.fix(60, 5, 270, self.WED_7PM + 900), "moving": False}
        relay.state_set(relay.PARKING_KEY, {"andrews_tesla": {"last": curb}, "sarahs_car": {"last": far}})
        relay.state_set(relay.CAR_PRESENCE_KEY, {
            "andrews_tesla": {"here": True, "since": self.WED_7PM + 1200, "known": True},
            "sarahs_car": {"here": True, "since": self.WED_7PM - 600, "known": True},
        })
        cars = {c["car"]: c for c in relay.parking_status(self.WED_7PM + 1500)["cars"]}
        self.assertEqual((self.WED_7PM + 900, True), (cars["andrews_tesla"]["last"]["at"], cars["andrews_tesla"]["last"]["home"]),
                         "Tesla had it home, at its own spot")
        self.assertEqual((self.WED_7PM, True, False), (cars["sarahs_car"]["last"]["at"], cars["sarahs_car"]["last"]["moving"],
                                                       cars["sarahs_car"]["last"]["home"]), "driven off since the camera saw it home")

    def test_a_starting_state_home_is_no_arrival(self):
        far = {**self.fix(-2000, 8000, 330, self.WED_7PM), "moving": False}
        relay.state_set(relay.PARKING_KEY, {"sarahs_car": {"last": far, "asleep": True}})
        # Started home from a sighting from before the fix: `since` is when presence started, not when it arrived.
        relay.state_set(relay.CAR_PRESENCE_KEY, {"sarahs_car": {"here": True, "since": self.WED_7PM + 600, "known": False}})
        sarah = next(c for c in relay.parking_status(self.WED_7PM + 900)["cars"] if c["car"] == "sarahs_car")
        self.assertEqual((self.WED_7PM, False), (sarah["last"]["at"], sarah["last"]["home"]))

    def test_a_car_known_only_by_its_spot_is_last_seen_there(self):
        saved = {"andrews_tesla": {"spot": {**self.fix(60, 5, 270, self.WED_7PM), "since": self.WED_7PM, "street": "Elm St", "side": "north"}}}
        relay.state_set(relay.PARKING_KEY, saved)
        andrew = relay.parking_status(self.WED_7PM + 60)["cars"][0]
        self.assertEqual((self.WED_7PM, False, True), (andrew["last"]["at"], andrew["last"]["moving"], andrew["last"]["home"]))
        self.assertIsNone(relay.parking_status(self.WED_7PM + 60)["cars"][1]["last"], "never seen")

    def test_a_car_gone_before_the_reminder_is_not_reminded_about(self):
        self.where["VIN_A"] = self.fix(60, 5, 270, self.WED_7PM + 3600)
        self.tick(self.WED_7PM + 3600)
        relay._tesla_answers["andrews_tesla"] = (self.WED_7PM + 3600, self.where["VIN_A"])  # nobody has asked since the evening
        self.where["VIN_A"] = self.fix(-60, 30, 180, self.WED_7PM + 12.5 * 3600 - 60)  # back in the driveway
        self.assertEqual(["moved"], [p["event"] for p in relay.parking_tick(self.WED_7PM + 12.5 * 3600, push=self.push)])
        self.assertEqual(2, self.asked.count("VIN_A"), "asked again before sending anyone out")

    def test_a_phone_in_its_quiet_hours_hears_once_they_are_over(self):
        relay.with_db(lambda c: (c.execute("UPDATE devices SET quiet_start=?, quiet_end=?", (19 * 60, 7 * 60)), c.commit()))
        self.where["VIN_A"] = self.fix(60, 5, 270, self.WED_7PM + 3600)
        self.assertEqual([], self.tick(self.WED_7PM + 3600))
        self.assertEqual([("due", "Move Andrew's Tesla by 8:00 AM", "North side of Elm St · Street sweeping", ["pixel"])], self.tick(self.WED_7PM + 12 * 3600 + 60))

    def test_only_the_cars_drivers_hear_when_their_phones_are_registered(self):
        self.phone("iphone", "t-iphone", user="sarah")
        config = relay.parking_config()
        config["drivers"] = {"andrews_tesla": ["andrew"], "sarahs_car": ["sarah"]}
        self.config(config)
        self.where = {"VIN_A": self.fix(60, 5, 270, self.WED_7PM + 3600), "VIN_S": self.fix(4, 60, 0, self.WED_7PM + 3600)}
        told = {title: to for _, title, _, to in self.tick(self.WED_7PM + 3600)}
        self.assertEqual({"Move Andrew's Tesla by Thu 8:00 AM": ["pixel"], "Move Sarah's Car by Thu 8:00 AM": ["iphone"]}, told)

    def test_a_car_in_the_driveway_or_away_says_nothing(self):
        self.where = {"VIN_A": self.fix(-60, 30, 180, self.WED_7PM + 12.5 * 3600), "VIN_S": self.fix(-5000, 0, 90, self.WED_7PM + 12.5 * 3600)}
        self.assertEqual([], self.tick(self.WED_7PM + 12.5 * 3600))

    def test_found_at_the_curb_with_nothing_before_counts_from_as_long_ago_as_answers_can_be_apart(self):
        car = {}
        stretches = relay.parking_stretches(relay.parking_config(), self.HOME, self.WED_7PM)
        relay.parking_read(car, (self.WED_7PM, self.fix(60, 5, 270, self.WED_7PM)), stretches)
        self.assertEqual(self.WED_7PM - relay.TESLA_ASLEEP_CHECK_SECONDS, car["spot"]["since"], "asleep, it may not have been asked for two hours")

    def test_parked_between_two_answers_counts_from_the_earlier(self):
        car = {}
        stretches = relay.parking_stretches(relay.parking_config(), self.HOME, self.WED_7PM)
        relay.parking_read(car, (self.WED_7PM, self.fix(-60, 30, 180, self.WED_7PM)), stretches)
        relay.parking_read(car, (self.WED_7PM + 1200, self.fix(60, 5, 270, self.WED_7PM + 1200)), stretches)
        self.assertEqual((self.WED_7PM, "Elm St", "north"), (car["spot"]["since"], car["spot"]["street"], car["spot"]["side"]))

    def test_streets_by_name_are_looked_up_in_openstreetmap_once(self):
        self.config({"streets": [{"street": "Elm St", "sides": {"north": [self.SWEEPING]}}]})
        asked = []
        geometry = [{"lat": lat, "lon": lng} for lat, lng in self.ELM]
        relay.requests.post = lambda url, data=None, headers=None, timeout=None: (asked.append(data["data"]), _Response(200, {"elements": [
            {"type": "way", "tags": {"highway": "residential", "name": "Elm Street"}, "geometry": geometry},
            {"type": "way", "tags": {"highway": "footway", "name": "Elm Street"}, "geometry": geometry},
        ]}))[1]
        stretches = relay.parking_stretches(relay.parking_config(), self.HOME, self.WED_7PM)
        relay.parking_stretches(relay.parking_config(), self.HOME, self.WED_7PM + 60)
        self.assertEqual(1, len(asked))
        self.assertIn('"name"="Elm Street"', asked[0])
        self.assertEqual([("Elm St", "left")], [(s["street"], s["home_side"]) for s in stretches])

    def test_an_all_day_limit_keeps_counting_past_midnight(self):
        zone = relay.ZoneInfo(self.ZONE)
        all_day = {"kind": "limit", "minutes": 120}
        eleven_pm = self.WED_7PM + 4 * 3600
        self.assertEqual(eleven_pm + 2 * 3600, relay.rule_deadline(all_day, eleven_pm, eleven_pm, zone)["at"], "1 AM, not 2 AM")
        self.assertEqual(eleven_pm + 2 * 3600, relay.rule_deadline(all_day, eleven_pm, eleven_pm + 3 * 3600, zone)["at"], "and still 1 AM, asked after midnight")

    def test_across_to_the_other_curb_is_a_new_spot_even_close_by(self):
        car = {}
        stretches = relay.parking_stretches(relay.parking_config(), self.HOME, self.WED_7PM)
        relay.parking_read(car, (self.WED_7PM, self.fix(60, 5, 270, self.WED_7PM)), stretches)
        relay.parking_read(car, (self.WED_7PM + 1800, self.fix(62, -5, 90, self.WED_7PM + 1800)), stretches)
        self.assertEqual(("south", self.WED_7PM), (car["spot"]["side"], car["spot"]["since"]), "parked there after the last answer")

    def test_parked_again_further_along_the_curb_is_warned_afresh(self):
        self.where["VIN_A"] = self.fix(60, 5, 270, self.WED_7PM + 3600)
        self.assertEqual(["due"], [e for e, *_ in self.tick(self.WED_7PM + 3600)])
        self.where["VIN_A"] = self.fix(-60, 5, 270, self.WED_7PM + 3700)
        self.assertEqual(["due"], [e for e, *_ in self.tick(self.WED_7PM + 3700)], "still on the north curb: told again, not 'moved'")
        self.assertEqual("Move Andrew's Tesla by Thu 8:00 AM", self.pushes[-1][1])

    def test_a_moved_push_that_failed_is_tried_again(self):
        self.where["VIN_A"] = self.fix(60, 5, 270, self.WED_7PM + 3600)
        self.tick(self.WED_7PM + 3600)
        self.where["VIN_A"] = self.fix(-500, 300, 10, self.WED_7PM + 3700, shift="D", speed=25.0)
        failing = lambda token, title, body, data, away=False: "failed"
        self.assertEqual([], relay.parking_tick(self.WED_7PM + 3700, push=failing))
        self.assertEqual([("moved", "Andrew's Tesla has moved", "No longer parked on Elm St", ["pixel"])], self.tick(self.WED_7PM + 3800))
        self.assertEqual([], self.tick(self.WED_7PM + 3900), "once it got through")

    def test_even_a_day_ahead_tesla_is_asked_first(self):
        afternoon = {"kind": "no_parking", "days": ["fri"], "start": "14:00", "end": "16:00"}
        config = relay.parking_config()
        config["streets"][0]["sides"]["north"] = [afternoon]
        self.config(config)
        self.where["VIN_A"] = self.fix(60, 5, 270, self.WED_7PM)
        relay._tesla_answers["andrews_tesla"] = (self.WED_7PM, self.where["VIN_A"])
        self.assertEqual([], relay.parking_tick(self.WED_7PM, push=self.push), "found at the curb; Friday 2 PM is more than a day off")
        self.where["VIN_A"] = self.fix(-60, 30, 180, self.WED_7PM + 20 * 3600)  # since gone home
        relay._tesla_answers["andrews_tesla"] = (self.WED_7PM + 20 * 3600 - 900, relay._tesla_answers["andrews_tesla"][1])
        self.assertEqual([], relay.parking_tick(self.WED_7PM + 20 * 3600, push=self.push), "a day ahead now, but asked first, and it had gone")
        self.assertEqual([], self.pushes)


class VaultTest(_ScratchDb):
    """The household key vault: only sealed blobs go in, and only a member holding the household key can grant it or change the secrets."""

    NAMES = _ScratchDb.NAMES + ("require_frigate_session",)

    def setUp(self):
        super().setUp()
        self.user = "andrew"
        relay.require_frigate_session = lambda request: self.user

    def as_(self, user):
        self.user = user
        return self

    def member(self, public="pubA", private="sealedA", replaces=None):
        return relay.put_vault_member(types.SimpleNamespace(public=public, private=private, replaces=replaces), None)

    def start(self, household="h1", recipient="pubA"):
        return relay.start_vault_household(types.SimpleNamespace(household=household, recipient=recipient, ephemeral="e", box="dek-for-me"), None)

    def test_a_first_member_starts_the_household_and_stores_sealed_secrets(self):
        view = self.member()
        self.assertEqual({"public": "pubA", "private": "sealedA", "grant": None}, view["me"])
        view = self.start()
        self.assertEqual({"ephemeral": "e", "box": "dek-for-me"}, view["me"]["grant"])
        view = relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"google_maps": "box1", "anthropic": "box2"}), None)
        self.assertEqual({"google_maps": "box1", "anthropic": "box2"}, view["secrets"])
        view = relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"anthropic": None}), None)
        self.assertEqual({"google_maps": "box1"}, view["secrets"], "null removes")

    def test_a_new_member_waits_until_someone_inside_grants_them(self):
        self.member()
        self.start()
        relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"google_maps": "box1"}), None)
        view = self.as_("sarah").member("pubS", "sealedS")
        self.assertIsNone(view["me"]["grant"])
        self.assertEqual([("andrew", True), ("sarah", False)], [(m["user"], m["granted"]) for m in view["members"]])
        with self.assertRaises(relay.HTTPException) as refused:
            relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"google_maps": "mine"}), None)
        self.assertEqual(409, refused.exception.status_code, "no household key, no writing")
        with self.assertRaises(relay.HTTPException):
            self.start("h2", recipient="pubS")
        self.as_("andrew")
        relay.put_vault_grant("sarah", types.SimpleNamespace(household="h1", recipient="pubS", ephemeral="e2", box="dek-for-sarah"), None)
        view = self.as_("sarah") and relay.get_vault(None)
        self.assertEqual({"ephemeral": "e2", "box": "dek-for-sarah"}, view["me"]["grant"])
        self.assertEqual({"google_maps": "box1"}, view["secrets"])

    def test_a_new_password_drops_the_grant_and_an_empty_household_starts_again(self):
        self.member()
        self.start()
        relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"anthropic": "box"}), None)
        view = self.member("pubA2", "sealedA2", replaces="pubA")
        self.assertIsNone(view["me"]["grant"], "the grant was sealed to the old key pair")
        with self.assertRaises(relay.HTTPException):
            relay.put_vault_grant("andrew", types.SimpleNamespace(household="h1", recipient="pubA2", ephemeral="e", box="b"), None)
        view = self.start("h2", recipient="pubA2")
        self.assertEqual("h2", view["household"])
        self.assertEqual({}, view["secrets"], "the old secrets can't be opened any more")

    def test_nothing_but_sealed_blobs_is_accepted(self):
        with self.assertRaises(relay.HTTPException):
            self.member(public="")
        self.member()
        self.start()
        with self.assertRaises(relay.HTTPException):
            relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"Bad Name": "box"}), None)
        with self.assertRaises(relay.HTTPException):
            relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"anthropic": "x" * (relay.VAULT_BLOB_MAX + 1)}), None)

    def test_a_second_phone_signing_in_at_once_looks_again_instead_of_replacing_the_first(self):
        self.member()
        self.start()
        relay.put_vault_secrets(types.SimpleNamespace(household="h1", secrets={"anthropic": "box"}), None)
        with self.assertRaises(relay.HTTPException) as refused:
            self.member("pubA-other-phone", "sealedA-other", replaces=None)
        self.assertEqual(409, refused.exception.status_code, "it saw no key pair, but the first phone has set one since")
        view = relay.get_vault(None)
        self.assertEqual("pubA", view["me"]["public"])
        self.assertIsNotNone(view["me"]["grant"], "the first phone keeps its grant")
        self.assertEqual({"anthropic": "box"}, view["secrets"])

    def test_a_grant_sealed_to_a_key_pair_since_replaced_is_refused(self):
        self.member()
        self.start()
        self.as_("sarah").member("pubS", "sealedS")
        self.as_("sarah").member("pubS2", "sealedS2", replaces="pubS")
        with self.assertRaises(relay.HTTPException) as refused:
            self.as_("andrew") and relay.put_vault_grant("sarah", types.SimpleNamespace(household="h1", recipient="pubS", ephemeral="e", box="b"), None)
        self.assertEqual(409, refused.exception.status_code)
        self.assertFalse(relay.get_vault(None)["members"][1]["granted"])


class ApnsTest(unittest.TestCase):
    """Pushing straight to an iPhone through Apple's push service, with Apple and the key faked."""

    TOKEN = "a1" * 32

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._saved = {n: getattr(relay, n) for n in ("DB_PATH", "CONN", "APNS_KEY_ID", "APNS_KEY_FILE", "apns_post", "apns_sign")}
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()
        relay.APNS_KEY_FILE = os.path.join(self._dir.name, "apns-key.p8")
        with open(relay.APNS_KEY_FILE, "w") as f:
            f.write("not read: apns_sign is faked")
        relay.APNS_KEY_ID = "KEY123"
        relay.apns_sign = lambda now: f"jwt-{int(now)}"
        relay._apns.update(jwt=None, at=0.0)
        self.posts = []  # (url, headers, payload)
        self.answers = {}  # host -> (status, reason)
        relay.apns_post = self.post

    def tearDown(self):
        relay.CONN.close()
        for name, value in self._saved.items():
            setattr(relay, name, value)
        relay._apns.update(jwt=None, at=0.0)
        self._dir.cleanup()

    def post(self, url, headers, payload):
        self.posts.append((url, headers, payload))
        host = "sandbox" if "sandbox" in url else "production"
        return self.answers.get(host, (200, ""))

    def test_an_iphone_token_goes_to_apple_and_an_fcm_one_does_not(self):
        self.assertTrue(relay.apns_token(self.TOKEN))
        self.assertFalse(relay.apns_token("cXy0:APA91bH" + "x" * 140), "an FCM token")
        self.assertFalse(relay.apns_token(None))
        self.assertEqual((True, ""), relay.send_push(self.TOKEN, "Move Andrew's Tesla now", "West side of Fountain St", {"parking": "1", "notif_id": "parking-andrews_tesla"}))
        url, headers, payload = self.posts[0]
        self.assertEqual(f"https://api.push.apple.com/3/device/{self.TOKEN}", url)
        self.assertEqual(("bearer jwt-", "com.meticulouscreations.homesafe.HomeSafe", "alert", "parking-andrews_tesla"),
                         (headers["authorization"][:11], headers["apns-topic"], headers["apns-push-type"], headers["apns-collapse-id"]))
        self.assertEqual({"title": "Move Andrew's Tesla now", "body": "West side of Fountain St"}, payload["aps"]["alert"])
        self.assertEqual("1", payload["parking"], "the app's data travels beside aps")

    def test_a_token_xcode_installed_falls_to_the_sandbox_which_is_then_tried_first(self):
        self.answers["production"] = (400, "BadDeviceToken")
        self.assertEqual((True, ""), relay.apns_send(self.TOKEN, "t", "b", {}))
        self.assertEqual(["production", "sandbox"], ["sandbox" if "sandbox" in u else "production" for u, _, _ in self.posts])
        self.posts.clear()
        relay.apns_send(self.TOKEN, "t", "b", {})
        self.assertEqual(1, len(self.posts))
        self.assertIn("sandbox", self.posts[0][0])

    def test_an_app_apple_says_is_gone_is_forgotten(self):
        relay.with_db(lambda c: (c.execute("INSERT INTO devices (device_id, token, platform, name) VALUES ('ios1', ?, 'ios', 'iPhone')", (self.TOKEN,)), c.commit()))
        self.answers["production"] = (410, "Unregistered")
        self.assertEqual("dropped", relay.deliver(self.TOKEN, "t", "b", {}))
        self.assertIsNone(relay.with_db(lambda c: c.execute("SELECT 1 FROM devices WHERE device_id='ios1'").fetchone()))

    def test_a_refusal_that_is_no_token_s_fault_keeps_the_phone(self):
        relay.with_db(lambda c: (c.execute("INSERT INTO devices (device_id, token, platform, name) VALUES ('ios1', ?, 'ios', 'iPhone')", (self.TOKEN,)), c.commit()))
        self.answers["production"] = (403, "InvalidProviderToken")
        self.assertEqual("failed", relay.deliver(self.TOKEN, "t", "b", {}))
        self.assertEqual(1, len(self.posts), "no point trying the sandbox with a bad key")
        self.assertIsNotNone(relay.with_db(lambda c: c.execute("SELECT 1 FROM devices WHERE device_id='ios1'").fetchone()))

    def test_without_a_key_an_iphone_is_told_it_gets_no_pushes(self):
        self.assertTrue(relay.can_push(self.TOKEN))
        self.assertTrue(relay.can_push("cXy0:APA91bH" + "x" * 140))
        self.assertFalse(relay.can_push(None))
        relay.APNS_KEY_ID = ""
        self.assertFalse(relay.can_push(self.TOKEN))
        self.assertTrue(relay.can_push("cXy0:APA91bH" + "x" * 140), "Android's go through FCM whatever")
        self.assertEqual((False, "apns not set up (APNS_KEY_ID, APNS_KEY_FILE)"), relay.apns_send(self.TOKEN, "t", "b", {}))
        self.assertEqual([], self.posts)

    def test_the_provider_token_is_made_anew_only_every_45_minutes(self):
        self.assertEqual("jwt-1000", relay.apns_jwt(1000.0))
        self.assertEqual("jwt-1000", relay.apns_jwt(1000.0 + 44 * 60))
        self.assertEqual("jwt-3700", relay.apns_jwt(3700.0))

    def test_apple_out_of_reach_fails_this_phone_and_the_broadcast_goes_on(self):
        relay.with_db(lambda c: (c.executemany("INSERT INTO devices (device_id, token, platform, name) VALUES (?,?,?,?)",
                                               [("ios1", self.TOKEN, "ios", "iPhone"), ("and1", "fcm-token-1", "android", "Pixel")]), c.commit()))

        def unreachable(url, headers, payload):
            raise TimeoutError("read timed out")

        relay.apns_post = unreachable
        fcm = []
        send_push = relay.send_push
        relay.send_push = lambda token, *a, **k: send_push(token, *a, **k) if relay.apns_token(token) else (fcm.append(token), (True, ""))[1]
        try:
            result = relay.broadcast("Front Door", "Person", {}, away=True)
        finally:
            relay.send_push = send_push
        self.assertEqual((1, 1), (result["sent"], result["failed"]), "the Pixel after the iPhone was still pushed to")
        self.assertEqual(["fcm-token-1"], fcm)

    def test_a_key_that_wont_load_fails_the_push_not_the_relay(self):
        def broken(now):
            raise ValueError("Could not deserialize key data")

        relay.apns_sign = broken
        ok, err = relay.apns_send(self.TOKEN, "t", "b", {})
        self.assertFalse(ok)
        self.assertIn("Could not deserialize key data", err)
