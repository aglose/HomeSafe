#!/usr/bin/env python3
"""Compares the nightly emulator run of the device tests with apksim's run of the same commit.

Usage: scripts/ci/compare-device-tests.py EMULATOR_DIR APKSIM_DIR

EMULATOR_DIR holds the emulator job's reports (every JUnit XML under an androidTest-results folder
is read); APKSIM_DIR holds scripts/ci/apksim-device-tests.py's output (results.json, or its JUnit
XML files). Pull requests are gated on apksim alone, so this is where a difference shows up:

- a test the emulator failed (whatever apksim said: if apksim passed it, apksim let a bug through);
- a test apksim failed that the emulator passed;
- a test that only passed on apksim when it was run again on its own (flaky there);
- a test that ran on one side only (the emulator is API 34, apksim API 36, so an SDK-suppressed
  test can be one of these; a test apksim never found is a gap in what pull requests check).

A test that skipped itself on apksim and ran on the emulator is listed too, but is no difference:
the test says apksim can't run it yet (the clip journeys, which need a download that is fetched
and a video decoder), and the emulator is where it is checked.

Prints a markdown report (also to the job summary) and exits 1 if it lists a difference.
"""
import glob, json, os, sys
import xml.etree.ElementTree as ET


def junit(paths):
    """{"class#method": passed | failed | skipped} from JUnit XML files (a failure anywhere wins)."""
    results = {}
    for path in paths:
        try:
            tree = ET.parse(path)
        except (OSError, ET.ParseError) as e:
            print(f"::warning::Couldn't read {path}: {e}", file=sys.stderr)
            continue
        for case in tree.iter("testcase"):
            key = f"{case.get('classname')}#{case.get('name')}"
            if case.find("failure") is not None or case.find("error") is not None:
                status = "failed"
            elif case.find("skipped") is not None:
                status = "skipped"
            else:
                status = "passed"
            if results.get(key) != "failed":
                results[key] = status
    return results


def apksim_results(folder):
    path = os.path.join(folder, "results.json")
    if os.path.exists(path):
        return json.load(open(path))
    # An older run, without results.json: its suites' XML, a rerun's pass making the test flaky.
    first = junit(p for p in glob.glob(os.path.join(folder, "*.xml")) if not p.endswith("-rerun.xml"))
    rerun = junit(glob.glob(os.path.join(folder, "*-rerun.xml")))
    return {k: ("flaky" if s == "failed" and rerun.get(k) == "passed" else s) for k, s in first.items()}


def main():
    emulator = junit(glob.glob(os.path.join(sys.argv[1], "**", "androidTest-results", "**", "*.xml"), recursive=True))
    apksim = apksim_results(sys.argv[2])
    if not emulator or not apksim:
        print(f"::error::Nothing to compare: {len(emulator)} emulator results, {len(apksim)} apksim results.")
        sys.exit(1)

    def short(key):
        cls, method = key.split("#", 1)
        return f"`{cls.rsplit('.', 1)[-1]}#{method}`"

    ran = lambda s: s in ("passed", "failed", "flaky")
    sections = [
        ("Failed on the emulator", [f"{short(k)}: apksim {apksim.get(k, 'did not run it')}"
                                    for k, s in sorted(emulator.items()) if s == "failed"]),
        ("Failed on apksim, passed on the emulator", [short(k) for k, s in sorted(apksim.items())
                                                      if s == "failed" and emulator.get(k) == "passed"]),
        ("Failed on apksim, not run on the emulator", [short(k) for k, s in sorted(apksim.items())
                                                       if s == "failed" and k not in emulator]),
        ("Flaky on apksim (failed, then passed in a process of its own)",
         [f"{short(k)}: emulator {emulator.get(k, 'did not run it')}" for k, s in sorted(apksim.items()) if s == "flaky"]),
        ("Ran on the emulator only", [short(k) for k, s in sorted(emulator.items())
                                      if ran(s) and not ran(apksim.get(k, "")) and apksim.get(k) != "skipped"]),
        ("Ran on apksim only", [short(k) for k, s in sorted(apksim.items())
                                if ran(s) and not ran(emulator.get(k, ""))]),
    ]
    count = lambda results, status: sum(1 for s in results.values() if s == status)
    lines = ["### Device tests: emulator (API 34) vs apksim (API 36)", "",
             "| | Tests | Passed | Failed | Flaky | Skipped |", "|---|---|---|---|---|---|",
             f"| emulator | {len(emulator)} | {count(emulator, 'passed')} | {count(emulator, 'failed')} | | {count(emulator, 'skipped')} |",
             f"| apksim | {len(apksim)} | {count(apksim, 'passed')} | {count(apksim, 'failed')} | {count(apksim, 'flaky')} | {count(apksim, 'skipped')} |"]
    found = False
    for title, items in sections:
        if items:
            found = True
            lines += ["", f"**{title}** ({len(items)})", ""]
            lines += [f"- {item}" for item in items[:50]]
            if len(items) > 50:
                lines.append(f"- … and {len(items) - 50} more")
    if not found:
        lines += ["", "They agree: every test that ran on both passed on both."]
    skipped = [f"{short(k)}: emulator {s}" for k, s in sorted(emulator.items()) if ran(s) and apksim.get(k) == "skipped"]
    if skipped:
        lines += ["", f"**Skipped itself on apksim, checked on the emulator only** ({len(skipped)})", ""]
        lines += [f"- {item}" for item in skipped]
    report = "\n".join(lines) + "\n"
    print(report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(report)
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
