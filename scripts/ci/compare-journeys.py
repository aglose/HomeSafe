#!/usr/bin/env python3
"""Compares the nightly emulator run of the Android CLI journeys with apksim's run of the same commit.

Usage: scripts/ci/compare-journeys.py EMULATOR_DIR APKSIM_DIR

Each directory holds scripts/run-android-cli-journeys.sh's output, whose summary.tsv has one line
per journey: name, pass|fail, actions passed, actions in the journey, seconds. Pull requests run the
journeys on apksim alone, so this is where a difference shows up: a journey one side failed and the
other passed, both failed, or one that ran on one side only (or a side with no results at all).

Prints a markdown table of both sides' results and times, then what differs, and exits 1 if
anything does.
"""
import os, sys


def read(folder):
    """{journey: (outcome, passed, expected, seconds)}, or None when the run left no journeys."""
    path = os.path.join(folder, "summary.tsv")
    if not os.path.exists(path):
        return None
    rows = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 5:
                name, outcome, passed, expected, seconds = parts
                rows[name] = (outcome, passed, expected, int(seconds))
    return rows or None


def cell(row):
    if row is None:
        return "—"
    outcome, passed, expected, seconds = row
    return f"{'✅' if outcome == 'pass' else '❌'} {passed}/{expected}, {seconds} s"


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    emulator, apksim = read(sys.argv[1]), read(sys.argv[2])
    problems = []
    if emulator is None:
        problems.append("The emulator run left no results (see its job).")
    if apksim is None:
        problems.append("The apksim run left no results (see its job).")
    emulator, apksim = emulator or {}, apksim or {}

    print("| Journey | Emulator | apksim |")
    print("|---|---|---|")
    for name in sorted(set(emulator) | set(apksim)):
        e, a = emulator.get(name), apksim.get(name)
        print(f"| {name} | {cell(e)} | {cell(a)} |")
        if e is None or a is None:
            problems.append(f"`{name}` ran on {'apksim' if e is None else 'the emulator'} only.")
        elif e[0] != a[0]:
            side = "apksim" if a[0] == "fail" else "the emulator"
            problems.append(f"`{name}` failed on {side} alone.")
        elif e[0] == "fail":
            problems.append(f"`{name}` failed on both.")
    both = [n for n in emulator if n in apksim]
    if both:
        e_total = sum(emulator[n][3] for n in both)
        a_total = sum(apksim[n][3] for n in both)
        print(f"| **{len(both)} on both** | {e_total} s | {a_total} s |")
    print()
    if problems:
        print("**Differences**")
        print()
        for p in problems:
            print(f"- {p}")
        sys.exit(1)
    print("The emulator and apksim agree on every journey.")


if __name__ == "__main__":
    main()
