#!/usr/bin/env python3
"""Times cold start -> first live pixel on the Home grid, stage by stage.

Each iteration force-stops the app, launches it, signs in with the debug/benchmark "Autofill test
credentials" button, and waits for every Home camera to draw its first live video frame. The app
logs each LiveStartupMilestones name once per process under the HomeSafeTTFP tag, as ms since
process start, so the numbers need no clock shared with this machine.

  scripts/bench-first-live-pixel.py --serial emulator-5554 --runs 10 --label baseline

Build and install a variant that carries the test credentials first (debug, or benchmarkRelease
for release bytecode: `./gradlew :androidApp:installBenchmarkRelease`). --serial is required so a
run never drives a phone that happens to be plugged in. Results are appended as JSON lines to
build/bench/first-live-pixel.jsonl; --compare A B prints two labels side by side.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")), "platform-tools", "adb")
OUT = os.path.join(os.path.dirname(__file__), "..", "build", "bench", "first-live-pixel.jsonl")
LINE = re.compile(r"HomeSafeTTFP\s*:\s*(.+?) \+(\d+)ms")


def adb(serial, *args, check=True):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, check=check).stdout


def find_bounds(serial, text, timeout_s):
    """Centre of the first node whose text is [text], polling uiautomator until it appears."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        xml = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        xml = xml[: xml.rfind(">") + 1]
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            continue
        for node in root.iter("node"):
            if node.get("text") == text:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(0.2)
    raise TimeoutError(f"'{text}' not on screen after {timeout_s}s")


def wait_for_idle_go2rtc(go2rtc, ignore_agent=None, timeout_s=90):
    """Blocks until go2rtc has no consumers from off the server, so every run joins cold streams.

    A force-stopped app's WebRTC consumers linger on the server until their connections time
    out, keeping the camera's RTSP session (the producer) up; a run that starts then joins a warm
    stream, which is not what opening the app after a while looks like. Returns the seconds waited.
    """
    started = time.monotonic()
    while time.monotonic() - started < timeout_s:
        with urllib.request.urlopen(f"{go2rtc}/api/streams", timeout=5) as r:
            streams = json.load(r)
        # Consumers on the server itself are Frigate's own restream readers (recording, detection),
        # there for good; only the app's are in question.
        remote = [
            c for v in streams.values() for c in v.get("consumers") or []
            if not c.get("remote_addr", "").startswith("127.0.0.1")
            and not (ignore_agent and ignore_agent in c.get("user_agent", ""))
        ]
        if not remote:
            return time.monotonic() - started
        time.sleep(1)
    raise TimeoutError(f"go2rtc still has consumers after {timeout_s}s")


def milestones(serial):
    found = {}
    for line in adb(serial, "logcat", "-d", "-s", "HomeSafeTTFP:I").splitlines():
        m = LINE.search(line)
        if m:
            found.setdefault(m.group(1), int(m.group(2)))
    return found


def run_once(serial, package, cameras, timeout_s, settle_s, taps):
    adb(serial, "shell", "am", "force-stop", package)
    time.sleep(1)
    adb(serial, "logcat", "-c")
    launch = adb(serial, "shell", "am", "start", "-W", "-n", f"{package}/com.meticulouscreations.homesafe.MainActivity")
    total = re.search(r"TotalTime: (\d+)", launch)
    if "autofill" not in taps:
        taps["autofill"] = find_bounds(serial, "Autofill test credentials", 15)
    else:
        find_bounds(serial, "Autofill test credentials", 15)
    adb(serial, "shell", "input", "tap", *map(str, taps["autofill"]))
    if "connect" not in taps:
        taps["connect"] = find_bounds(serial, "Connect", 5)
    adb(serial, "shell", "input", "tap", *map(str, taps["connect"]))
    deadline = time.monotonic() + timeout_s
    marks = {}
    while time.monotonic() < deadline:
        marks = milestones(serial)
        if sum(1 for k in marks if re.fullmatch(r"pixel [^ ]+", k)) >= cameras:
            break
        time.sleep(0.25)
    # Later milestones (a WebRTC join finishing after HLS drew) are part of the story too.
    time.sleep(settle_s)
    marks = milestones(serial)
    marks["launch.totalTime"] = int(total.group(1)) if total else None
    return marks


def derived(marks):
    """Durations worth comparing across runs: everything from sign-in submit, where the user's part ends."""
    submit = marks.get("signin.submit")
    out = {}
    if submit is None:
        return out
    for name, at in marks.items():
        if name.startswith(("pixel", "rtc.", "hls.", "live.", "signin.")) and name != "signin.submit" and at is not None:
            out[f"submit->{name}"] = at - submit
    return out


def summarise(rows, label):
    keys = sorted({k for r in rows for k in r["derived"]})
    print(f"\n== {label}: {len(rows)} runs (ms after sign-in submit; median / min / max)")
    for k in keys:
        vals = [r["derived"][k] for r in rows if k in r["derived"]]
        print(f"  {k:48s} {statistics.median(vals):7.0f} {min(vals):7d} {max(vals):7d}   n={len(vals)}")


def load(label):
    with open(OUT) as f:
        return [r for r in map(json.loads, f) if r["label"] == label]


def compare(a, b):
    ra, rb = load(a), load(b)
    keys = sorted({k for r in ra + rb for k in r["derived"]})
    print(f"{'ms after sign-in submit (median)':48s} {a:>10s} {b:>10s} {'delta':>8s}")
    for k in keys:
        va = [r["derived"][k] for r in ra if k in r["derived"]]
        vb = [r["derived"][k] for r in rb if k in r["derived"]]
        if va and vb:
            ma, mb = statistics.median(va), statistics.median(vb)
            print(f"{k:48s} {ma:10.0f} {mb:10.0f} {mb - ma:+8.0f}")


# Consecutive stages of one camera's cold start. {c} is the camera (the player key), {s} its grid
# stream as the WebRTC join names it (the camera's name plus a suffix, or the name itself).
STAGES = [
    ("signin.submit", "signin.ok"),
    ("signin.ok", "live.start {c}"),
    ("live.start {c}", "rtc.offer {s}"),
    ("rtc.offer {s}", "rtc.answer {s}"),
    ("rtc.answer {s}", "rtc.ice {s}"),
    ("rtc.ice {s}", "rtc.frame {s}"),
    ("rtc.frame {s}", "pixel.rtc {c}"),
    ("signin.submit", "pixel {c}"),
]


def stage_gaps(rows):
    """Each stage's duration, per run and per camera — medians of differences, not differences of medians."""
    gaps = {}
    for r in rows:
        marks = r["marks"]
        cameras = [k.split(" ", 1)[1] for k in marks if k.startswith("pixel ")]
        for c in cameras:
            streams = [k.split(" ", 1)[1] for k in marks if k.startswith("rtc.start ")]
            s = next((x for x in streams if x == c or x.startswith(c + "_")), c)
            for a, b in STAGES:
                ka, kb = a.format(c=c, s=s), b.format(c=c, s=s)
                if ka in marks and kb in marks:
                    gaps.setdefault(f"{a.split()[0]} -> {b.split()[0]}", []).append(marks[kb] - marks[ka])
    return gaps


def stages(labels):
    per_label = {label: stage_gaps(load(label)) for label in labels}
    names = [f"{a.split()[0]} -> {b.split()[0]}" for a, b in STAGES]
    print(f"{'stage (median per camera-run, ms)':34s}" + "".join(f"{label:>16s}" for label in labels))
    for name in names:
        cells = []
        for label in labels:
            vals = per_label[label].get(name)
            cells.append(f"{statistics.median(vals):8.0f} n={len(vals):<4d}" if vals else f"{'-':>16s}")
        print(f"{name:34s}" + "".join(f"{c:>16s}" for c in cells))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--serial", help="adb serial of the device to drive (required for a run)")
    p.add_argument("--package", default="com.meticulouscreations.homesafe", help="add .debug for a debug build")
    p.add_argument("--runs", type=int, default=10)
    p.add_argument("--cameras", type=int, default=3, help="Home cameras expected to draw a frame")
    p.add_argument("--timeout", type=float, default=20, help="seconds to wait for every camera's first pixel")
    p.add_argument("--settle", type=float, default=4, help="seconds to keep collecting milestones after every camera drew")
    p.add_argument("--pause", type=float, default=4, help="seconds between runs, for go2rtc to drop the last run's consumers")
    p.add_argument("--go2rtc", help="e.g. http://192.168.68.65:1984: before each run, wait until it has no consumers (cold streams)")
    p.add_argument("--go2rtc-ignore-agent", help="consumers whose user agent contains this don't count (e.g. curl holding streams warm on purpose)")
    p.add_argument("--idle-extra", type=float, default=3, help="seconds to wait after go2rtc went idle, for it to close the camera sessions")
    p.add_argument("--label", default="run")
    p.add_argument("--compare", nargs=2, metavar=("A", "B"))
    p.add_argument("--stages", nargs="+", metavar="LABEL", help="per-stage durations for these labels, side by side")
    args = p.parse_args()
    if args.compare:
        compare(*args.compare)
        return
    if args.stages:
        stages(args.stages)
        return
    if not args.serial:
        sys.exit("--serial is required")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    rows, taps = [], {}
    for i in range(args.runs):
        if args.go2rtc:
            adb(args.serial, "shell", "am", "force-stop", args.package)
            waited = wait_for_idle_go2rtc(args.go2rtc, args.go2rtc_ignore_agent)
            if waited > 0.5:
                print(f"  (waited {waited:.0f}s for go2rtc to drop the last run's consumers)", flush=True)
            time.sleep(args.idle_extra)
        marks = run_once(args.serial, args.package, args.cameras, args.timeout, args.settle, taps)
        row = {"label": args.label, "run": i, "marks": marks, "derived": derived(marks)}
        rows.append(row)
        with open(OUT, "a") as f:
            f.write(json.dumps(row) + "\n")
        first = row["derived"].get("submit->pixel.first")
        print(f"run {i + 1}/{args.runs}: first live pixel {first} ms after submit", flush=True)
        time.sleep(args.pause)
    adb(args.serial, "shell", "am", "force-stop", args.package)
    summarise(rows, args.label)


if __name__ == "__main__":
    main()
