"""
Checks for watch-ios-job.sh, the CI step that decides whether the iOS job passed, failed, or has to
run again on a GitHub-hosted Mac. Each test hands the script a fake `gh` that answers with this
run's jobs as the GitHub API would, one answer per poll, and reads what it decided.

    python3 -m unittest scripts/ci/test_watch_ios_job.py

Standard library only; needs bash, jq and GNU date on the path (as the hosted Ubuntu runner has).
"""

import datetime
import json
import os
import stat
import subprocess
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "watch-ios-job.sh")
NAME = "iOS tests and build"

# Serves $FIX/1.json, 2.json, … one per call, then keeps repeating the last; "FAIL" fails the call.
FAKE_GH = """#!/usr/bin/env bash
n=$(cat "$FIX/count" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$FIX/count"
f="$FIX/$n.json"; [ -f "$f" ] || f=$(ls "$FIX"/*.json | sort -V | tail -1)
[ "$(cat "$f")" = FAIL ] && exit 1
cat "$f"
"""


def stamp(minutes_ago=0):
    moment = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=minutes_ago)
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def job(status, conclusion=None, runner="homesafe-mac", steps=(), minutes_ago=0, attempt=1):
    return {
        "name": NAME,
        "run_attempt": attempt,
        "status": status,
        "conclusion": conclusion,
        "created_at": stamp(minutes_ago),
        "runner_name": runner,
        "steps": [{"status": s} for s in steps],
    }


class WatchIosJobTest(unittest.TestCase):
    def watch(self, *answers, on_mac=True):
        """Runs the script against these successive API answers; returns (exit code, result)."""
        with tempfile.TemporaryDirectory() as tmp:
            bin_dir = os.path.join(tmp, "bin")
            os.mkdir(bin_dir)
            gh = os.path.join(bin_dir, "gh")
            with open(gh, "w") as f:
                f.write(FAKE_GH)
            os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)
            for i, answer in enumerate(answers, start=1):
                with open(os.path.join(tmp, f"{i}.json"), "w") as f:
                    f.write(answer if isinstance(answer, str) else json.dumps({"jobs": answer}))
            output = os.path.join(tmp, "output")
            open(output, "w").close()
            env = dict(
                os.environ,
                PATH=bin_dir + os.pathsep + os.environ["PATH"],
                FIX=tmp,
                GITHUB_REPOSITORY="owner/repo",
                RUN_ID="1",
                RUN_ATTEMPT="1",
                JOB_NAME=NAME,
                ON_MAC="true" if on_mac else "false",
                GITHUB_OUTPUT=output,
                POLL_S="0",
                START_TIMEOUT_S="900",
                APPEAR_TIMEOUT_S="0",
            )
            done = subprocess.run(["bash", SCRIPT], env=env, capture_output=True, text=True, timeout=60)
            with open(output) as f:
                return done.returncode, f.read().strip()

    def test_a_job_that_starts_and_succeeds_passes(self):
        self.assertEqual(
            self.watch(
                [job("queued", runner=None)],
                [job("in_progress")],
                [job("completed", "success", steps=["completed", "completed"])],
            ),
            (0, "result=passed"),
        )

    def test_a_job_that_fails_by_itself_fails_the_watcher_too(self):
        self.assertEqual(
            self.watch([job("completed", "failure", steps=["completed", "completed"])]),
            (1, "result=failed"),
        )

    def test_a_runner_that_goes_away_partway_through_falls_back_to_a_hosted_mac(self):
        self.assertEqual(
            self.watch([job("completed", "failure", steps=["completed", "in_progress", "pending"])]),
            (0, "result=fallback"),
        )

    def test_a_hosted_runner_that_goes_away_is_just_a_failure(self):
        self.assertEqual(
            self.watch([job("completed", "failure", runner="GitHub Actions 7", steps=["in_progress"])], on_mac=False),
            (1, "result=failed"),
        )

    def test_a_job_queued_past_the_timeout_falls_back(self):
        self.assertEqual(self.watch([job("queued", runner=None, minutes_ago=20)]), (0, "result=fallback"))

    def test_a_job_only_just_queued_is_waited_for(self):
        self.assertEqual(
            self.watch([job("queued", runner=None)], [job("completed", "success", steps=["completed"])]),
            (0, "result=passed"),
        )

    def test_only_this_attempts_job_counts(self):
        self.assertEqual(self.watch([job("completed", "success", attempt=2)]), (1, "result=failed"))

    def test_a_failed_api_call_is_asked_again(self):
        self.assertEqual(
            self.watch("FAIL", "FAIL", [job("completed", "success", steps=["completed"])]),
            (0, "result=passed"),
        )


if __name__ == "__main__":
    unittest.main()
