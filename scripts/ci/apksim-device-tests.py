#!/usr/bin/env python3
"""Runs every module's device tests, and the end-to-end tests, on apksim
(https://github.com/aglose/apksim) instead of an emulator: Android's framework on the JVM, in
apksim's Linux image. The same APKs an emulator would install, built beforehand with

    ./gradlew assembleAndroidDeviceTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest

Usage: scripts/ci/apksim-device-tests.py APKSIM_CHECKOUT IMAGE OUT

Each module's test APK instruments itself, so it is passed as both APKs. The whole-app journeys
run in one process (2-3 minutes; one process per test takes over ten), and the end-to-end tests
one process per test, four at a time, as the Orchestrator does.

A test that fails is run once more in a process of its own. If it passes then, it counts as
flaky: the run still passes, and the job summary and a warning name it, so it is seen and fixed
rather than retried forever. (Long single-process journey runs time out now and then on apksim,
which is why.) A test that fails twice, or a suite that crashes, fails the run.

Writes to OUT a JUnit XML and a log per suite (and per rerun), results.json (every test's outcome,
for scripts/ci/compare-device-tests.py), and summary.md, which also goes to the job summary.
"""
import glob, json, os, subprocess, sys, time
import xml.etree.ElementTree as ET

JOURNEYS = "com.meticulouscreations.homesafe.integration"
# More failures than this in one suite is not a flake: don't spend minutes rerunning them.
MAX_RERUNS = 10


def main():
    apksim_dir, image, out = sys.argv[1:4]
    apksim_dir = os.path.abspath(apksim_dir)
    os.makedirs(out, exist_ok=True)
    out = os.path.abspath(out)
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    os.chdir(root)
    # apksim's runner starts the image once per process, with the repository as its working directory.
    # APKSIM_LAUNCH runs another apksim instead, such as the Mac's (`APKSIM_LAUNCH=apksim`, IMAGE unused).
    launch = os.environ.get("APKSIM_LAUNCH") or f"docker run --rm -v {root}:/work {image}"

    suites = []  # (name, app, test, instrument.py arguments)
    apk = "shared/build/outputs/apk/androidTest/shared-androidTest.apk"
    suites.append(("shared", apk, apk, []))
    for module in sorted(glob.glob("features/*/")):
        name = os.path.basename(module.rstrip("/"))
        apk = f"features/{name}/build/outputs/apk/androidTest/{name}-androidTest.apk"
        if os.path.exists(apk):
            suites.append((name, apk, apk, []))
    apk = "app/build/outputs/apk/androidTest/app-androidTest.apk"
    suites.append(("app", apk, apk, ["-e", "notPackage", JOURNEYS]))
    suites.append(("journeys", apk, apk, ["-e", "package", JOURNEYS]))
    suites.append(("e2e", "androidApp/build/outputs/apk/debug/androidApp-debug.apk",
                   "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk",
                   ["--isolate", "-j", "4"]))

    def instrument(name, app, test, args):
        start = time.time()
        # An earlier run's report in a reused OUT must not stand in for one this run never wrote.
        if os.path.exists(f"{out}/{name}.xml"):
            os.remove(f"{out}/{name}.xml")
        with open(f"{out}/{name}.log", "w") as log:
            subprocess.run([sys.executable, f"{apksim_dir}/scripts/instrument.py", "--apksim", launch, app, test,
                            "--xml", f"{out}/{name}.xml", *args], stdout=log, stderr=subprocess.STDOUT)
        return read_xml(f"{out}/{name}.xml"), time.time() - start

    outcomes = {}  # "class#method" -> passed | failed | flaky | skipped
    rows, failures, flaky = [], [], []
    total = time.time()
    for name, app, test, args in suites:
        results, seconds = instrument(name, app, test, args)
        if not results:
            results = {("(run)", name): ("failed", "no results: see " + name + ".log")}
        failed = [k for k, (s, _) in results.items() if s == "failed"]
        rerun = {}
        crashed = any(c.startswith("(") for c, _ in failed)
        if failed and not crashed and len(failed) <= MAX_RERUNS:
            # By name, so the suite's package filter and isolation give way to these alone.
            only = ",".join(f"{c}#{m}" for c, m in failed)
            rerun, more = instrument(f"{name}-rerun", app, test, ["--isolate", "-j", "4", "-e", "class", only])
            seconds += more
            # A rerun that crashed (instrument.py's "(listing)"/"(run)" entries, or no results at all)
            # proves nothing: keep its crash as a failure of its own and count no test as flaky.
            broken = {k: v for k, v in rerun.items() if k[0].startswith("(")}
            if broken or not rerun:
                results.update({(f"(rerun) {c}", m): v for (c, m), v in broken.items()}
                               or {("(rerun)", name): ("failed", f"the rerun gave no results: see {name}-rerun.log")})
                rerun = {}
        counts = {"passed": 0, "failed": 0, "flaky": 0, "skipped": 0}
        for (c, m), (status, message) in results.items():
            if status == "failed" and rerun.get((c, m), ("failed",))[0] == "passed":
                status = "flaky"
                flaky.append((name, c, m, message))
            elif status == "failed":
                failures.append((name, c, m, rerun.get((c, m), (None, message))[1] or message))
            counts[status] += 1
            outcomes[f"{c}#{m}"] = status
        rows.append((name, len(results), counts, seconds))
        print(f"{name}: {len(results)} tests, {counts['passed']} passed, {counts['failed']} failed, "
              f"{counts['flaky']} flaky, {counts['skipped']} skipped [{seconds:.0f} s]", flush=True)
    total = time.time() - total

    lines = ["### Device tests on apksim", "",
             "| Suite | Tests | Passed | Failed | Flaky (passed on a rerun) | Skipped | Wall time |",
             "|---|---|---|---|---|---|---|"]
    for name, n, c, s in rows:
        lines.append(f"| {name} | {n} | {c['passed']} | {c['failed']} | {c['flaky']} | {c['skipped']} | {s:.0f} s |")
    lines.append(f"| **all** | {sum(r[1] for r in rows)} | | {len(failures)} | {len(flaky)} | | {total:.0f} s |")
    for title, items in (("Failed", failures), ("Flaky: failed, then passed in a process of its own", flaky)):
        if items:
            lines += ["", f"**{title}**", ""]
            lines += [f"- `{c.rsplit('.', 1)[-1]}#{m}` ({suite}): {first_line(msg)}" for suite, c, m, msg in items]
    summary = "\n".join(lines) + "\n"
    open(f"{out}/summary.md", "w").write(summary)
    json.dump(outcomes, open(f"{out}/results.json", "w"), indent=0, sort_keys=True)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(summary)
    print(f"all suites: {total:.0f} s")

    for suite, c, m, msg in flaky:
        print(f"::warning title=Flaky on apksim::{c}#{m} failed, then passed when run again on its own: {first_line(msg)}")
    # Each failure, with its message and stack, in the log.
    for suite, c, m, msg in failures:
        print(f"\nFAILED {c}#{m} ({suite})\n{msg[:4000]}")
    sys.exit(1 if failures else 0)


def read_xml(path):
    """{(class, method): (status, message)} from instrument.py's JUnit XML; empty if it wrote none."""
    try:
        tree = ET.parse(path)
    except (OSError, ET.ParseError):
        return {}
    results = {}
    for case in tree.iter("testcase"):
        failure = case.find("failure")
        if failure is not None:
            results[(case.get("classname"), case.get("name"))] = ("failed", failure.text or failure.get("message") or "")
        elif case.find("skipped") is not None:
            results[(case.get("classname"), case.get("name"))] = ("skipped", "")
        else:
            results[(case.get("classname"), case.get("name"))] = ("passed", "")
    return results


def first_line(text):
    line = next((l for l in (text or "").splitlines() if l.strip()), "")
    return line.replace("|", "\\|")[:200]


if __name__ == "__main__":
    main()
