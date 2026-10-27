"""
Checks for already-tested.sh, the CI step that lets a run on main skip the emulator and iOS jobs
when its pull request already passed ci-green on exactly the same tree. Each test hands the script
a fake `gh` that answers each API path from a fixture (applying --jq the way gh does) and reads
what it decided.

    python3 -m unittest scripts/ci/test_already_tested.py

Standard library only; needs bash and jq on the path (as the hosted Ubuntu runner has).
"""

import json
import os
import stat
import subprocess
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "already-tested.sh")
MAIN_SHA = "a" * 40
HEAD_SHA = "b" * 40
TREE = "c" * 40
OTHER_TREE = "d" * 40
BOT = "github-actions[bot]"

# Answers `gh api [--paginate] <path> --jq <filter>` from $FIX/<path with / as _>.json; a fixture
# holding FAIL, or none at all, fails the call.
FAKE_GH = """#!/usr/bin/env bash
path= filter=.
while [ $# -gt 0 ]; do
  case $1 in
    api|--paginate) ;;
    --jq) filter=$2; shift ;;
    *) path=$1 ;;
  esac
  shift
done
f="$FIX/$(echo "${path%%\\?*}" | tr / _).json"
[ -f "$f" ] && [ "$(cat "$f")" != FAIL ] || exit 1
jq -r "$filter" "$f"
"""


def pull(number=12, merged=True, base="main", head=HEAD_SHA):
    return {
        "number": number,
        "merged_at": "2026-10-27T01:00:00Z" if merged else None,
        "base": {"ref": base},
        "head": {"sha": head},
    }


def status(tree, state="success", context="ci-green tree", creator=BOT):
    return {"context": context, "state": state, "description": f"tree {tree}", "creator": {"login": creator}}


class AlreadyTestedTest(unittest.TestCase):
    def decide(self, pulls=None, statuses=None, commit=None):
        """Runs the script against these API answers; returns (exit code, {output: value})."""
        fixtures = {
            f"repos/owner/repo/git/commits/{MAIN_SHA}": {"tree": {"sha": TREE}} if commit is None else commit,
            f"repos/owner/repo/commits/{MAIN_SHA}/pulls": [pull()] if pulls is None else pulls,
            f"repos/owner/repo/commits/{HEAD_SHA}/statuses": [status(TREE)] if statuses is None else statuses,
        }
        with tempfile.TemporaryDirectory() as tmp:
            bin_dir = os.path.join(tmp, "bin")
            os.mkdir(bin_dir)
            gh = os.path.join(bin_dir, "gh")
            with open(gh, "w") as f:
                f.write(FAKE_GH)
            os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)
            for path, answer in fixtures.items():
                with open(os.path.join(tmp, path.replace("/", "_") + ".json"), "w") as f:
                    f.write(answer if isinstance(answer, str) else json.dumps(answer))
            output = os.path.join(tmp, "output")
            open(output, "w").close()
            env = dict(
                os.environ,
                PATH=bin_dir + os.pathsep + os.environ["PATH"],
                FIX=tmp,
                GITHUB_REPOSITORY="owner/repo",
                GITHUB_SHA=MAIN_SHA,
                GITHUB_OUTPUT=output,
            )
            done = subprocess.run(["bash", SCRIPT], env=env, capture_output=True, text=True, timeout=30)
            with open(output) as f:
                return done.returncode, dict(line.split("=", 1) for line in f.read().split())

    def test_skips_when_the_pull_request_passed_on_this_tree(self):
        self.assertEqual(self.decide(), (0, {"skip": "true", "pr": "12"}))

    def test_skips_when_any_of_several_green_runs_matches(self):
        self.assertEqual(
            self.decide(statuses=[status(OTHER_TREE), status(TREE), status(OTHER_TREE)]),
            (0, {"skip": "true", "pr": "12"}),
        )

    def test_runs_when_main_moved_after_the_pull_requests_ci(self):
        self.assertEqual(self.decide(statuses=[status(OTHER_TREE)]), (0, {"skip": "false", "pr": "12"}))

    def test_runs_when_nothing_was_recorded(self):
        self.assertEqual(self.decide(statuses=[]), (0, {"skip": "false", "pr": "12"}))

    def test_ignores_a_status_that_is_not_ci_greens_own(self):
        for impostor in (
            status(TREE, creator="someone"),
            status(TREE, context="other"),
            status(TREE, state="failure"),
            status(TREE, state="pending"),
        ):
            with self.subTest(impostor=impostor):
                self.assertEqual(self.decide(statuses=[impostor]), (0, {"skip": "false", "pr": "12"}))

    def test_runs_for_a_commit_no_pull_request_merged(self):
        self.assertEqual(self.decide(pulls=[]), (0, {"skip": "false"}))

    def test_only_a_pull_request_merged_into_main_counts(self):
        self.assertEqual(
            self.decide(pulls=[pull(number=3, merged=False), pull(number=4, base="release")]),
            (0, {"skip": "false"}),
        )

    def test_picks_the_merged_pull_request_among_others(self):
        other = pull(number=3, merged=False, head="e" * 40)
        self.assertEqual(self.decide(pulls=[other, pull()]), (0, {"skip": "true", "pr": "12"}))

    def test_runs_everything_when_the_api_fails(self):
        self.assertEqual(self.decide(commit="FAIL"), (0, {"skip": "false"}))
        self.assertEqual(self.decide(pulls="FAIL"), (0, {"skip": "false"}))
        self.assertEqual(self.decide(statuses="FAIL"), (0, {"skip": "false", "pr": "12"}))


if __name__ == "__main__":
    unittest.main()
