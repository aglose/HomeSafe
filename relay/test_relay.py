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


class CarCheckAgainstFrigate(unittest.TestCase):
    """The car check's rounds against a fake Frigate: what it files, writes off, retries and leaves alone."""

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
                and ("min_length" not in p or (v["end_time"] is not None and v["end_time"] - v["start_time"] >= p["min_length"]))
            ])
        found = self.events.get(url.rsplit("/", 1)[1], 404)
        return _Response(found) if isinstance(found, int) else _Response(200, found)

    def post(self, url, json=None, timeout=None):
        self.posts.append((url, json))
        return _Response(400 if any(part in url for part in self.refuse) else 200, {})

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

    def verdict(self, event_id, kind="street"):
        row = relay.with_db(lambda c: c.execute("SELECT verdict FROM car_checks WHERE event_id=? AND kind=?", (event_id, kind)).fetchone())
        return row and row[0]

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

    def sub_labels(self):
        return [body for url, body in self.posts if url.endswith("/sub_label")]

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


if __name__ == "__main__":
    unittest.main()
