#!/usr/bin/env python3
"""Every heavy screen opened and then left alone: what each one costs with nobody touching it.

    JOURNEY=scripts/perf/idle.py scripts/perf/record.sh build/perf/idle

Expects the app signed in and on Home (run `scripts/perf/tour.py <package> --replay --steps none`
first), and the tap targets tour.py recorded in build/perf/coords.json. Each screen gets a few
seconds to settle, then an `idle_<screen>` step of six seconds with no input at all. A screen that
draws in those six seconds is animating something (or something under it is); trace_report.py's
`instants` names the clock behind it in a tracing build.
"""
import json, os, subprocess, sys, time

PKG = sys.argv[1]
COORDS = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "build", "perf", "coords.json")))


def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True, text=True).stdout


def mark(step):
    adb("shell", "log", "-t", "HomeSafeJourney", step)


def tap(key):
    adb("shell", "input", "tap", str(COORDS[key][0]), str(COORDS[key][1]))


def idle(name, settle=4.0, seconds=6.0):
    time.sleep(settle)
    mark(f"idle_{name}:start")
    time.sleep(seconds)
    mark(f"idle_{name}:end")
    print("idle", name, flush=True)


def back():
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(1.5)


def drawer(tag):
    tap("shell_menu")
    time.sleep(0.8)
    tap(tag)


idle("home", settle=8.0)
drawer("drawer_weather")
idle("weather")
back()
drawer("drawer_finance")
time.sleep(2)
for tab in ("markets", "budget", "wallet", "economy", "risk"):
    tap(f"finance_tab_{tab}")
    idle(f"finance_{tab}")
back()
drawer("drawer_fitness")
time.sleep(2)
for tab in ("today", "lifts", "history", "progress"):
    tap(f"fitness_tab_{tab}")
    idle(f"fitness_{tab}")
back()
drawer("drawer_atlas")
time.sleep(3)
for tab in ("map", "days", "places", "trips", "patterns"):
    tap(f"atlas_tab_{tab}")
    idle(f"atlas_{tab}")
back()
