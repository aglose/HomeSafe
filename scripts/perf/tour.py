#!/usr/bin/env python3
"""The heavy screens, one after another, with a step mark either side of each for the trace.

    scripts/perf/tour.py <package>                    find every target on screen, and remember where it was
    scripts/perf/tour.py <package> --replay           tap where they were, with fixed waits (what to measure)
    scripts/perf/tour.py <package> --steps home,weather,finance,fitness,atlas

Starts the app cold, signs in with the build's test credentials (benchmarkRelease with
local.credentials.properties), then: Home scrolled; Weather, Finance (every tab), Fitness (every
tab) and Atlas (every list tab) opened from the drawer and scrolled; back to Home between apps.
Each step is logged as `HomeSafeJourney <step>:start` / `:end`, which the trace keeps.

Why two modes: finding a node by `uiautomator dump` (or `android layout`) waits for the UI to go
idle and walks the whole tree, and a screen with live video or an animated shader never goes idle,
so a dump inside a step measures the dump. The first run records each target's centre in
build/perf/coords.json; --replay taps those and waits a fixed time instead. Same device, same
screen size: record again after either changes.
"""
import json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = sys.argv[1]
args = sys.argv[2:]
STEPS = (args[args.index("--steps") + 1] if "--steps" in args else "home,weather,finance,fitness,atlas").split(",")
REPLAY = "--replay" in args
COORDS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "build", "perf", "coords.json")
CACHE = json.load(open(COORDS)) if REPLAY and os.path.exists(COORDS) else {}
# Looked up live even when replaying: sign-in comes before any measured step, and what it shows varies.
ALWAYS_LIVE = ("home_feed", "Autofill test credentials", "Connect", "Not now")


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout


def mark(step):
    adb("shell", "log", "-t", "HomeSafeJourney", step)


def nodes():
    out = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    if "<?xml" not in out:
        return []
    try:
        root = ET.fromstring(out[out.find("<?xml"):out.rfind(">") + 1])
    except ET.ParseError:
        return []
    found = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            found.append((n.get("resource-id", ""), n.get("text", ""), ((x1 + x2) // 2, (y1 + y2) // 2)))
    return found


def wait(tag=None, text=None, timeout=20.0):
    key = tag or text
    if REPLAY and key in CACHE and key not in ALWAYS_LIVE:
        # A screen's own tag stands for "this screen is up": give it time to arrive.
        time.sleep(1.5 if key.endswith(("_app", "_today", "_map")) else 0.3)
        return tuple(CACHE[key])
    end = time.time() + timeout
    while time.time() < end:
        for rid, t, c in nodes():
            if (tag and (rid == tag or rid.endswith(":id/" + tag))) or (text and t == text):
                if not REPLAY:
                    CACHE[key] = c
                    os.makedirs(os.path.dirname(COORDS), exist_ok=True)
                    json.dump(CACHE, open(COORDS, "w"))
                return c
        time.sleep(0.2)
    return None


def tap_at(c):
    adb("shell", "input", "tap", str(c[0]), str(c[1]))


def tap(tag=None, text=None, timeout=10.0):
    c = wait(tag, text, timeout)
    if not c:
        raise SystemExit(f"not found: {tag or text}")
    tap_at(c)


def swipe(down=True, ms=120, settle=0.9):
    x, top, bottom = 540, 720, 1680
    y1, y2 = (bottom, top) if down else (top, bottom)
    adb("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), str(ms))
    time.sleep(settle)


def step(name, fn):
    mark(name + ":start")
    started = time.time()
    fn()
    mark(name + ":end")
    print(f"{name}\t{time.time() - started:.2f}s", flush=True)


def scroll_page():
    swipe(True, 400)
    for _ in range(3):
        swipe(True)
    for _ in range(3):
        swipe(False)


def back():
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(1.0)


def open_drawer_app(tag, target, timeout=30.0):
    tap("shell_menu")
    time.sleep(0.6)
    tap(tag)
    if not wait(target, timeout=timeout):
        raise SystemExit(f"{target} did not show")


def visit_tabs(prefix, tabs, settle=1.5):
    for tab in tabs:
        def visit(tab=tab):
            c = wait(f"{prefix}_tab_{tab}", timeout=5)
            if not c:
                print(f"  no {prefix}_tab_{tab}", flush=True)
                return
            tap_at(c)
            time.sleep(settle)
            scroll_page()
        step(f"{prefix}_{tab}", visit)


def sign_in():
    c = wait(text="Autofill test credentials", timeout=20)
    if c:
        tap_at(c)
        time.sleep(0.5)
        # The keyboard covers Connect. BACK only while it is up: otherwise BACK leaves the app.
        if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
            adb("shell", "input", "keyevent", "BACK")
        tap(text="Connect")
    end = time.time() + 40
    while time.time() < end:
        found = nodes()
        if any(rid.endswith("home_feed") for rid, _, _ in found):
            return
        for _, t, c in found:
            if t == "Not now":  # the offer to sign in with a fingerprint next time
                tap_at(c)
        time.sleep(0.3)
    raise SystemExit("home_feed did not show")


adb("shell", "am", "force-stop", PKG)
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
time.sleep(1)
mark("launch:start")
adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.meticulouscreations.homesafe.MainActivity")
step("signin_to_home", sign_in)

if "home" in STEPS:
    time.sleep(2)
    step("home_scroll", scroll_page)

if "weather" in STEPS:
    step("weather_open", lambda: open_drawer_app("drawer_weather", "weather_today"))
    time.sleep(2)
    step("weather_scroll", scroll_page)
    back()

if "finance" in STEPS:
    step("finance_open", lambda: open_drawer_app("drawer_finance", "finance_app"))
    time.sleep(2)
    visit_tabs("finance", ("markets", "budget", "wallet", "economy", "risk"))
    back()

if "fitness" in STEPS:
    step("fitness_open", lambda: open_drawer_app("drawer_fitness", "fitness_app"))
    time.sleep(2)
    visit_tabs("fitness", ("today", "lifts", "history", "progress"))
    back()

if "atlas" in STEPS:
    step("atlas_open", lambda: open_drawer_app("drawer_atlas", "atlas_tab_map", timeout=240))
    time.sleep(3)
    visit_tabs("atlas", ("days", "places", "trips", "patterns"), settle=1.0)
    back()

mark("tour:end")
