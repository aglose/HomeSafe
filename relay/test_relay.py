"""
Offline checks for the relay's pure decisions. Runs with nothing but the standard library:

    python3 -m unittest relay/test_relay.py

The web framework, Firebase and HTTP client are stubbed so `relay` imports without its
requirements; every test here calls a function that touches none of them.
"""

import os
import sys
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


_stub("fastapi", FastAPI=_App, HTTPException=Exception, Request=object, Response=object)
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

    def test_away_is_marked_for_both(self):
        message = relay.push_message("tok", "Away: Person", "Front Yard · nobody home", {**self.DATA, "away": "1"}, away=True)["message"]
        self.assertEqual("1", message["data"]["away"], "the app picks the loud channel from this")
        self.assertEqual("time-sensitive", message["apns"]["payload"]["aps"]["interruption-level"])


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


if __name__ == "__main__":
    unittest.main()
