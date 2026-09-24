#!/usr/bin/env python3
"""Prints every failed or errored test case in the JUnit XML files under the given directories.

Connected (emulator) test tasks only print a pass/fail count and a path to an HTML report, and a
CI run's artifacts aren't always at hand when reading its log; this puts each failure's message
and the top of its stack trace in the log itself. The integration journeys' failure messages carry
the screen's semantics tree and the fake server's request log, so that is usually all it takes.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ElementTree

MAX_LINES = 150

roots = sys.argv[1:] or ["."]
failures = 0
total = 0
for root in roots:
    for path in sorted(glob.glob(os.path.join(root, "**", "*.xml"), recursive=True)):
        try:
            tree = ElementTree.parse(path)
        except ElementTree.ParseError:
            continue
        for case in tree.iter("testcase"):
            total += 1
            for kind in ("failure", "error"):
                for problem in case.findall(kind):
                    failures += 1
                    name = f"{case.get('classname')}.{case.get('name')}"
                    print(f"::group::{kind.upper()}: {name}")
                    text = (problem.text or problem.get("message") or "").splitlines()
                    kept = [line for line in text if not line.strip().startswith(("at androidx.test", "at org.junit", "at java.lang.reflect", "at android.app.Instrumentation"))]
                    print("\n".join(kept[:MAX_LINES]))
                    if len(kept) > MAX_LINES:
                        print(f"... {len(kept) - MAX_LINES} more lines")
                    print("::endgroup::")
print(f"{failures} failed of {total} test cases in {', '.join(roots)}")
