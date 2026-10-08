"""
Offline checks for the relay's pure decisions, and for its device table against a scratch SQLite
database. Runs with nothing but the standard library:

    python3 -m unittest relay/test_relay.py

The web framework, Firebase and HTTP client are stubbed so `relay` imports without its
requirements; every test here calls a function that touches none of them.
"""

import json
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
    """The presence snapshot and device removal, against a scratch database."""

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self._path, self._conn, self._auth = relay.DB_PATH, relay.CONN, relay.authenticate
        relay.DB_PATH = os.path.join(self._dir.name, "relay.db")
        relay.CONN = relay.db()
        # Stands in for the cookie: a signed-in user, who may act on any device.
        relay.authenticate = lambda request, device=None: "andrew"

    def tearDown(self):
        relay.CONN.close()
        relay.DB_PATH, relay.CONN, relay.authenticate = self._path, self._conn, self._auth
        self._dir.cleanup()

    def add(self, device_id, name, build="release", last_seen=1000.0):
        relay.with_db(lambda c: (c.execute(
            "INSERT INTO devices (device_id, platform, name, created, last_seen, build) VALUES (?,?,?,?,?,?)",
            (device_id, "android", name, last_seen, last_seen, build),
        ), c.commit()))

    def test_the_snapshot_names_each_device_and_when_it_was_last_seen(self):
        self.add("pixel", "Google Pixel 10 Pro XL", last_seen=1000.0)
        self.add("emulator", "Google sdk_gphone64_arm64", build="debug", last_seen=2000.0)
        devices = relay.presence_snapshot("pixel")["devices"]
        self.assertEqual(["pixel", "emulator"], [d["id"] for d in devices])
        self.assertEqual([1000.0, 2000.0], [d["last_seen"] for d in devices])
        self.assertEqual([True, False], [d["this_device"] for d in devices])

    def test_a_phone_reading_presence_is_seen_and_the_others_are_not(self):
        self.add("pixel", "Google Pixel 10 Pro XL", last_seen=1000.0)
        self.add("old-pixel", "Google Pixel 10 Pro XL", last_seen=1000.0)
        snapshot = relay.get_presence(request=None, device="pixel")
        seen = {d["id"]: d["last_seen"] for d in snapshot["devices"]}
        self.assertGreater(seen["pixel"], time.time() - 60)
        self.assertEqual(1000.0, seen["old-pixel"])

    def test_a_signed_in_user_can_remove_another_device(self):
        self.add("pixel", "Google Pixel 10 Pro XL")
        self.add("old-iphone", "Apple iPhone")
        self.assertEqual({"ok": True}, relay.unregister("old-iphone", request=None))
        self.assertEqual(["pixel"], [d["id"] for d in relay.presence_snapshot("pixel")["devices"]])


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


    def test_a_visits_follow_up_is_quiet_and_lands_on_the_same_notification(self):
        message = relay.push_message("tok", "Front Yard", "Person in the driveway · 2 alerts", {**self.DATA, "notif_id": "r0", "silent": "1"})["message"]
        self.assertEqual("1", message["data"]["silent"], "the app posts it without a sound from this")
        self.assertEqual("r0", message["data"]["notif_id"], "the app tags the notification with this")
        self.assertNotIn("notification", message, "still data only")
        aps = message["apns"]["payload"]["aps"]
        self.assertNotIn("sound", aps)
        self.assertEqual("passive", aps["interruption-level"])
        self.assertEqual("r0", message["apns"]["headers"]["apns-collapse-id"])

    def test_a_sounding_push_collapses_by_its_own_review_without_a_visit(self):
        message = relay.push_message("tok", "Front Yard", "Person in the driveway", self.DATA)["message"]
        self.assertEqual("default", message["apns"]["payload"]["aps"]["sound"])
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


class PresenceAuthorityTest(Devices):
    """One phone decides whether the house is empty; the rest are listed but have no say."""

    def setUp(self):
        super().setUp()
        self._env, relay.PRESENCE_DEVICE = relay.PRESENCE_DEVICE, ""

    def tearDown(self):
        relay.PRESENCE_DEVICE = self._env
        super().tearDown()

    def away(self, device_id, away=True, at=5000.0):
        relay.with_db(lambda c: (c.execute("UPDATE devices SET away=?, away_updated=? WHERE device_id=?", (int(away), at, device_id)), c.commit()))

    def household(self):
        # 2026-09-29: the Pixel, a release build on the emulator and an old iPhone debug install, all voting.
        self.add("pixel", "Google Pixel 10 Pro XL")
        self.add("emulator", "Google sdk_gphone64_arm64")
        relay.with_db(lambda c: (c.execute(
            "INSERT INTO devices (device_id, platform, name, created, last_seen, build) VALUES ('iphone','ios','Apple iPhone',1,1,'debug')"
        ), c.commit()))

    def test_without_an_authority_a_stale_install_holds_the_house_occupied(self):
        self.household()
        self.away("pixel")
        self.assertFalse(relay.presence_snapshot()["everyone_away"])
        self.assertIsNone(relay.away_since())

    def test_the_authority_alone_decides(self):
        self.household()
        relay.set_presence_authority(types.SimpleNamespace(device_id="pixel"), request=None)
        self.assertEqual("pixel", relay.presence_authority())
        snapshot = relay.presence_snapshot("pixel")
        self.assertEqual({"pixel": True, "emulator": False, "iphone": False}, {d["id"]: d["counts"] for d in snapshot["devices"]})
        self.assertFalse(snapshot["everyone_away"])
        self.away("pixel", at=5000.0)
        self.assertTrue(relay.presence_snapshot()["everyone_away"])
        self.assertEqual(5000.0, relay.away_since())
        self.away("emulator", away=False)
        self.assertEqual(5000.0, relay.away_since(), "the others have no say")

    def test_the_environment_names_it_until_the_app_does(self):
        self.household()
        relay.PRESENCE_DEVICE = "emulator"
        self.assertEqual("emulator", relay.presence_authority())
        relay.state_set(relay.PRESENCE_KEY, "pixel")
        self.assertEqual("pixel", relay.presence_authority())
        relay.clear_presence_authority(request=None)
        self.assertEqual("emulator", relay.presence_authority())

    def test_an_authority_that_is_not_registered_is_refused(self):
        with self.assertRaises(relay.HTTPException) as refused:
            relay.set_presence_authority(types.SimpleNamespace(device_id="nobody"), request=None)
        self.assertEqual(404, refused.exception.status_code)

    def test_a_missing_authority_means_home(self):
        self.household()
        relay.state_set(relay.PRESENCE_KEY, "gone")
        self.away("pixel")
        self.away("emulator")
        self.assertIsNone(relay.away_since())


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
        super().tearDown()

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
        self.saved = (relay.FINANCE_SHEET_ID, relay.require_finance_user, relay.finance_google, dict(relay._finance_cache))
        relay.FINANCE_SHEET_ID = "sheet123"
        relay.require_finance_user = lambda request: "alex"
        relay._finance_cache.update(at=0.0, body=None, failed_at=0.0, failure=None)
        self.sheets = _FakeSheets((200, self.META), (200, self.VALUES))
        relay.finance_google = lambda: self.sheets
        relay._finance_google["account"] = "relay@example.iam.gserviceaccount.com"

    def tearDown(self):
        relay.FINANCE_SHEET_ID, relay.require_finance_user, relay.finance_google, cache = self.saved
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
        self.assertEqual(2, len(self.sheets.calls))
        relay.get_finance_sheet(object(), self.response, refresh=True)
        self.assertEqual(2, len(self.sheets.calls), "a refresh within the minimum interval is served from memory")
        relay._finance_cache["at"] -= relay.FINANCE_MIN_REFRESH_SECONDS + 1
        relay.get_finance_sheet(object(), self.response, refresh=True)
        self.assertEqual(4, len(self.sheets.calls))

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
        self.assertEqual(3, len(self.sheets.calls))
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
        # 192.0.2.0/24 is reserved for documentation: nothing answers.
        self.assertFalse(relay.tcp_reachable("192.0.2.1", 9, timeout=0.3))

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

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "plaid_sync_all", "feed_google", "FINANCE_FEED_SHEET_ID", "PLAID_CLIENT_ID", "PLAID_SECRET",
                                "PLAID_REDIRECT_URI", "BANK_SYNC_HOUR")
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

    def collecting(self):
        """What the relay's own look for uncollected links read afterwards: one entry for each read of the institutions."""
        reads = []
        relay.plaid_sync_all = lambda now=None, wait=False: reads.append(wait) or True
        relay._collect_looked["at"] = 0.0
        return reads

    FINISHED = {"link_sessions": [{"finished_at": "2026-10-05T12:00:00Z", "results": {"item_add_results": [
        {"public_token": "public-1", "institution": {"institution_id": "ins_128026", "name": "Capital One"}}]}}]}

    def test_a_link_finished_with_nobody_asking_is_collected_by_the_relay_itself(self):
        # The app was restarted while the person was at their bank: it never asks about link-1 again.
        reads = self.collecting()
        self.start()
        plaid = self.plaid(link_token_get=self.FINISHED, item_public_token_exchange={"access_token": "access-item1", "item_id": "item1"})
        self.assertEqual(["Capital One"], relay.plaid_collect_links(now=self.T + 120))
        self.assertEqual([("item1", "Capital One", "alex")], relay.with_db(lambda c: c.execute("SELECT item_id, institution, linked_by FROM plaid_items").fetchall()))
        # Read by the look itself, waiting its turn; nothing queued besides.
        self.assertEqual(([True], []), (reads, self.background))
        self.assertFalse(relay._collect_lock.locked())
        # Collected once: the next look asks Plaid nothing, and the app asking late is told it is linked.
        asked = len(plaid.calls)
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 420))
        self.assertEqual(asked, len(plaid.calls))
        self.assertEqual({"status": "linked", "institutions": ["Capital One"]}, relay.plaid_link_finish("link-1", now=self.T + 430))

    def test_several_links_collected_in_one_look_are_read_once(self):
        reads = self.collecting()
        for n in (1, 2, 3):
            self.plaid(link_token_create={"link_token": f"link-{n}", "hosted_link_url": "https://secure.plaid.com/hl/abc"})
            relay.plaid_link_start("alex", "bank", now=self.T + n)
        exchanged = iter(("item1", "item2", "item3"))

        def plaid(path, body):
            if path == "/link/token/get":
                return self.FINISHED
            item = next(exchanged)
            return {"access_token": f"access-{item}", "item_id": item}

        relay.plaid_post = plaid
        self.assertEqual(["Capital One"] * 3, relay.plaid_collect_links(now=self.T + 120))
        self.assertEqual(3, relay.with_db(lambda c: c.execute("SELECT COUNT(*) FROM plaid_items").fetchone()[0]))
        self.assertEqual(([True], []), (reads, self.background))

    def test_a_link_still_open_or_refused_is_left_as_it_was_and_looked_at_again(self):
        reads = self.collecting()
        self.start()
        self.plaid(link_token_get={"link_sessions": []})
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 60))
        self.plaid(link_token_get=relay.PlaidError("INVALID_LINK_TOKEN"))
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 360))
        self.assertIsNone(relay.state_get(relay.PLAID_LINKS_KEY)["link-1"].get("done"))
        self.assertEqual(([], []), (reads, self.background))
        # Finished just before Plaid's token for it lapses, and found a check later: still collected.
        self.plaid(link_token_get=self.FINISHED, item_public_token_exchange={"access_token": "access-item1", "item_id": "item1"})
        self.assertEqual(["Capital One"], relay.plaid_collect_links(now=self.T + relay.PLAID_LINK_SECONDS + 240))

    def test_a_link_past_its_end_is_not_dropped_by_the_next_one_begun(self):
        reads = self.collecting()
        self.start()
        # Someone else starts a link after link-1's own half hour, inside the half hour its sign-in can still be collected in.
        self.plaid(link_token_create={"link_token": "link-2", "hosted_link_url": "https://secure.plaid.com/hl/def"})
        relay.plaid_link_start("sam", "bank", now=self.T + relay.PLAID_LINK_SECONDS + 60)
        self.assertEqual({"link-1", "link-2"}, set(relay.state_get(relay.PLAID_LINKS_KEY)))

        def plaid(path, body):
            if path == "/link/token/get":
                return self.FINISHED if body["link_token"] == "link-1" else {"link_sessions": []}
            return {"access_token": "access-item1", "item_id": "item1"}

        relay.plaid_post = plaid
        self.assertEqual(["Capital One"], relay.plaid_collect_links(now=self.T + relay.PLAID_LINK_SECONDS + 120))
        self.assertEqual([True], reads)
        # Once that grace is over too, the next link begun does drop it.
        self.plaid(link_token_create={"link_token": "link-3", "hosted_link_url": "https://secure.plaid.com/hl/ghi"})
        relay.plaid_link_start("sam", "bank", now=self.T + 2 * relay.PLAID_LINK_SECONDS + 1)
        self.assertNotIn("link-1", relay.state_get(relay.PLAID_LINKS_KEY))

    def test_a_link_long_dead_is_not_asked_about_for_ever(self):
        self.collecting()
        self.start()
        plaid = self.plaid(link_token_get={"link_sessions": []})
        self.assertEqual([], relay.plaid_collect_links(now=self.T + 2 * relay.PLAID_LINK_SECONDS + 1))
        self.assertEqual([], plaid.calls)

    def test_a_look_already_under_way_is_left_to_it(self):
        self.collecting()
        self.start()
        plaid = self.plaid(link_token_get=self.FINISHED, item_public_token_exchange={"access_token": "access-item1", "item_id": "item1"})
        with relay._collect_lock:
            self.assertEqual([], relay.plaid_collect_links(now=self.T + 120))
            self.assertTrue(relay.bank_status(self.T + 120)["syncing"], "the page shows a look under way as syncing")
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

    NAMES = _ScratchDb.NAMES + ("plaid_post", "bank_sync_in_background", "plaid_collect_in_background", "require_finance_user", "write_bank_feed", "PLAID_CLIENT_ID",
                                "PLAID_SECRET", "FINANCE_FEED_SHEET_ID", "BANK_UNLINK_WAIT_SECONDS")

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

    def test_opening_the_page_starts_a_look_for_a_link_the_app_lost_track_of_without_waiting_on_plaid(self):
        looks = []
        relay.plaid_collect_in_background = lambda: looks.append(True)
        relay._collect_looked["at"] = 0.0
        relay.plaid_post = _FakePlaid(link_token_create={"link_token": "link-9", "hosted_link_url": "https://secure.plaid.com/hl/abc"})
        relay.post_bank_link(types.SimpleNamespace(kind="bank", institution=None), object(), self.response)
        asked = len(relay.plaid_post.calls)
        body = relay.get_bank(object(), self.response)
        # Answered at once, as syncing, with the look left to a thread of its own: not a word to Plaid on the request.
        self.assertTrue(body["syncing"])
        self.assertEqual(([True], asked), (looks, len(relay.plaid_post.calls)))
        # The page asks again every few seconds while it is told syncing: a look that found nothing isn't made again at each ask.
        self.assertFalse(relay.get_bank(object(), self.response)["syncing"])
        self.assertEqual([True], looks)
        relay._collect_looked["at"] -= relay.PLAID_COLLECT_SECONDS
        self.assertTrue(relay.get_bank(object(), self.response)["syncing"])
        self.assertEqual([True, True], looks)

    def test_with_no_link_waiting_the_page_starts_no_look(self):
        looks = []
        relay.plaid_collect_in_background = lambda: looks.append(True)
        relay._collect_looked["at"] = 0.0
        self.assertFalse(relay.get_bank(object(), self.response)["syncing"])
        self.assertEqual([], looks)

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


def _txn(txn_id, amount, date="2026-10-05", account="venture", name="COSTCO WHSE #0123", merchant="Costco", category="GENERAL_MERCHANDISE", **more):
    return {"transaction_id": txn_id, "account_id": account, "amount": amount, "date": date, "authorized_date": date, "name": name, "merchant_name": merchant,
            "iso_currency_code": "USD", "pending": False, "personal_finance_category": {"primary": category, "detailed": f"{category}_OTHER"}, **more}


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
        self.assertEqual({"total": True, "savings": False, "buckets": False}, config["alerts"])
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
        self.assertEqual({"budget": "1", "budget_kind": "person_100", "month": "2026-10", "spent": "250.00", "limit": "200.00", "person": "Andrew", "days_left": "24",
                          "notif_id": "budget-2026-10-person:Andrew_100"}, data)

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
                                       quiet_start=None, quiet_end=None, only_away=False, tz="America/Los_Angeles", utc_offset=-420)
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


if __name__ == "__main__":
    unittest.main()
