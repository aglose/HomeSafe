"""
Offline checks for the relay's pure decisions, and for its device table against a scratch SQLite
database. Runs with nothing but the standard library:

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
        self.assertEqual("Person and Andrews Tesla", relay.subject_for(["car", "car-verified", "person"], ["andrews_tesla"]))

    def test_named_cars_and_faces_and_plain_labels(self):
        self.assertEqual("Andrews Tesla", relay.subject_for(["car", "car-verified"], ["andrews_tesla"]))
        self.assertEqual("Andrews Tesla and Sarahs Car", relay.subject_for(["car-verified"], ["andrews_tesla", "sarahs_car"]))
        self.assertEqual("Sarah and car", relay.subject_for(["person", "car"], ["sarah"]))
        self.assertEqual("Person and car", relay.subject_for(["car", "person"], []), "people first")
        self.assertEqual("Person, bicycle and Andrews Tesla", relay.subject_for(["bicycle", "car", "person"], ["andrews_tesla"]))
        self.assertEqual("Car", relay.subject_for(["car"], ["none"]), "the classifier's none class is not a name")

    def test_a_household_car_is_not_a_familiar_face(self):
        self.assertFalse(relay.is_recognised_person(review("r", 0, ["car", "person"], ["andrews_tesla"])))
        self.assertTrue(relay.is_recognised_person(review("r", 0, ["car", "person"], ["andrews_tesla", "sarah"])))

    def test_kinds(self):
        self.assertEqual({"person", "car:andrews_tesla"}, relay.review_kinds(review("r", 0, ["car", "car-verified", "person"], ["andrews_tesla"])))
        self.assertEqual({"person:sarah", "car"}, relay.review_kinds(review("r", 0, ["car", "person"], ["sarah"])))
        self.assertEqual({"dog"}, relay.review_kinds(review("r", 0, ["dog"])))


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

    def test_a_person_with_a_household_car_still_sounds(self):
        self.judge(review("a", self.T, ["car-verified"], ["andrews_tesla"], end=self.T + 30))
        self.assertEqual(("b", True), self.judge(review("b", self.T + 1200, ["car-verified", "person"], ["andrews_tesla"])))

    def test_a_backlog_is_quiet(self):
        self.assertEqual(("a", False), self.judge(review("a", self.T, ["person"], end=self.T + 30), now=self.T + 600))
        self.assertEqual(("a", False), self.judge(review("b", self.T + 60, ["car"], end=self.T + 90), now=self.T + 700))


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
        "andrews_tesla": {"make": "tesla", "colour": ["blue", "black"]},
        "sarahs_car": {"make": "tesla", "colour": "red"},
        "in-laws_mercedes": {"make": "mercedes", "colour": "silver"},
        "yayas_car": {"colour": ["white", "silver"]},
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

    def test_zones_that_want_a_car(self):
        cam = {"zones": {"street": {"objects": ["bird"]}, "driveway": {"objects": ["person", "car", "dog"]}, "any": {"objects": []}, "lawn": {"objects": ["person"]}}}
        self.assertEqual(["driveway", "any"], relay.zones_wanting(cam, "car"))

    def test_a_red_car_called_andrews_tesla_is_renamed_to_the_one_red_tesla(self):
        matches = relay.household_matches({"colour": "red", "make": "tesla", "body": "suv"}, self.CARS)
        self.assertEqual(("relabel", "sarahs_car"), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS, "tesla"))

    def test_a_red_car_of_no_readable_make_only_loses_the_wrong_name(self):
        matches = relay.household_matches({"colour": "red", "make": "unknown"}, self.CARS)
        self.assertEqual(("clear", None), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS, "unknown"))

    def test_a_blue_toyota_called_andrews_tesla_loses_the_name(self):
        matches = relay.household_matches({"colour": "blue", "make": "toyota", "body": "sedan"}, self.CARS)
        self.assertEqual([], matches)
        self.assertEqual(("clear", None), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS))

    def test_a_dark_blue_tesla_called_andrews_tesla_keeps_it_even_when_it_reads_as_black(self):
        for colour in ("blue", "black"):
            matches = relay.household_matches({"colour": colour, "make": "tesla"}, self.CARS)
            self.assertEqual(("keep", "andrews_tesla"), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS), colour)
        matches = relay.household_matches({"colour": "blue", "make": "tesla"}, self.CARS)
        self.assertEqual(("keep", "andrews_tesla"), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS))

    def test_infrared_rules_out_nothing_by_colour(self):
        matches = relay.household_matches({"colour": "unknown", "make": "tesla"}, self.CARS)
        self.assertEqual(["andrews_tesla", "sarahs_car", "yayas_car"], matches)
        self.assertEqual(("keep", "andrews_tesla"), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS))

    def test_silver_and_grey_are_one_colour(self):
        self.assertEqual(["in-laws_mercedes", "yayas_car"], relay.household_matches({"colour": "grey", "make": "mercedes"}, self.CARS))

    def test_an_unknown_make_rules_out_nothing_by_make(self):
        self.assertEqual(["sarahs_car"], relay.household_matches({"colour": "red", "make": "unknown"}, self.CARS))

    def test_a_white_toyota_called_andrews_tesla_is_not_handed_to_the_white_car_with_no_make_on_file(self):
        matches = relay.household_matches({"colour": "white", "make": "toyota"}, self.CARS)
        self.assertEqual(["yayas_car"], matches)
        self.assertEqual(("clear", None), relay.second_opinion_verdict("andrews_tesla", matches, self.CARS, "toyota"))

    def test_never_names_a_car_the_classifier_left_unnamed(self):
        matches = relay.household_matches({"colour": "blue", "make": "tesla"}, self.CARS)
        for unnamed in (None, "none"):
            self.assertEqual("keep", relay.second_opinion_verdict(unnamed, matches, self.CARS)[0])

    def test_a_name_with_no_description_is_left_alone(self):
        self.assertEqual(("keep", "moms_car"), relay.second_opinion_verdict("moms_car", [], self.CARS))

    def test_a_persons_tag_is_final(self):
        tagged = {"label": "car", "zones": ["driveway"], "start_time": 0.0, "end_time": 5.0, "sub_label": "sarahs_car", "data": {"sub_label_score": 1.0}}
        self.assertFalse(relay.second_opinion_due(tagged, ["driveway"], 100.0))
        tagged["data"]["sub_label_score"] = 0.98
        self.assertTrue(relay.second_opinion_due(tagged, ["driveway"], 100.0))

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


class BootReportTest(unittest.TestCase):
    HEALTHY = {"frigate": True, "cameras": {"hikvision_1": 5.0, "hikvision_2": 5.0, "amcrest_1": 5.1}, "recording_mb": 3_700_000, "vlm": True}

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

    def test_clock_text(self):
        from zoneinfo import ZoneInfo
        self.assertEqual("5:33 PM", relay.clock_text(1790296380.0, ZoneInfo("America/Los_Angeles")))
        self.assertEqual("12:33 AM UTC", relay.clock_text(1790296380.0))


if __name__ == "__main__":
    unittest.main()
