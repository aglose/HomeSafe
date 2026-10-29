#!/usr/bin/env python3
"""Out of the finance app and back to Home, over and over: how soon Home's cameras move again.

    JOURNEY=scripts/perf/back_to_home.py scripts/perf/record.sh build/perf/back [--rounds 5] [--away 12]

Expects the app signed in and on Home, and build/perf/coords.json from tour.py. Each round opens
Finance from the drawer, stays `--away` seconds (past the players' 5 s grace, so their receive
streams have stopped), then goes back: by the back button on even rounds and by a back swipe from
the screen's left edge on odd ones, marked `back_button:<n>` / `back_swipe:<n>`, and stays on Home
six seconds. `trace_report.py back` reads the result.
"""
import json, os, subprocess, sys, time

PKG = sys.argv[1]
args = sys.argv[2:]
ROUNDS = int(args[args.index("--rounds") + 1]) if "--rounds" in args else 5
AWAY = float(args[args.index("--away") + 1]) if "--away" in args else 12.0
COORDS = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "build", "perf", "coords.json")))


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout


def tap(key):
    adb("shell", "input", "tap", str(COORDS[key][0]), str(COORDS[key][1]))


time.sleep(6)
for n in range(ROUNDS * 2):
    tap("shell_menu")
    time.sleep(0.8)
    tap("drawer_finance")
    time.sleep(AWAY)
    how = "button" if n % 2 == 0 else "swipe"
    adb("shell", "log", "-t", "HomeSafeJourney", f"back_{how}:{n}")
    if how == "button":
        adb("shell", "input", "keyevent", "BACK")
    else:
        # From the very edge: the system's back gesture, which the app sees as a predictive back.
        adb("shell", "input", "swipe", "1", "1200", "700", "1200", "350")
    time.sleep(6)
    print(f"round {n} ({how})", flush=True)
