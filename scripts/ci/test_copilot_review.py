"""
Checks for how the PR autopilot tells whether Copilot reviewed a commit or has run out.

    python3 -m unittest scripts/ci/test_copilot_review.py

Standard library only.
"""

import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import copilot_review  # noqa: E402
from copilot_review import COPILOT_CHECK, COPILOT_REVIEWER, judge  # noqa: E402

SHA = "a" * 40
NOW = datetime(2026, 10, 26, 22, 0, tzinfo=timezone.utc)


def review(id, sha=SHA, body="", login=COPILOT_REVIEWER, at="2026-10-26T21:50:00Z"):
    return {"id": id, "commit_id": sha, "body": body, "user": {"login": login}, "submitted_at": at}


def check(status="completed", conclusion="success", completed=NOW - timedelta(minutes=10)):
    return {
        "name": COPILOT_CHECK,
        "status": status,
        "conclusion": conclusion if status == "completed" else None,
        "completed_at": completed.isoformat().replace("+00:00", "Z") if status == "completed" else None,
    }


def run(reviews=(), comments=None, check_runs=(), requested=False, waited=timedelta(0)):
    comments = comments or {}
    return judge(
        sha=SHA,
        reviews=list(reviews),
        comments_of=lambda review_id: comments.get(review_id, []),
        check_runs=list(check_runs),
        requested=requested,
        now=NOW,
        started=NOW - waited,
    )


class JudgeTest(unittest.TestCase):
    def test_a_review_with_comments_is_reviewed(self):
        found = run(reviews=[review(1, body="Changes recommended")], comments={1: [{"id": 9, "path": "a.kt", "line": 3}]})
        self.assertEqual(found.state, "reviewed")
        self.assertEqual(len(found.comments), 1)

    def test_a_clean_review_is_reviewed_with_no_findings(self):
        found = run(reviews=[review(1, body="Looks good. No issues found.")], check_runs=[check()])
        self.assertEqual(found.state, "reviewed")
        self.assertEqual(found.comments, [])

    def test_the_newest_review_of_the_commit_counts(self):
        found = run(
            reviews=[review(1, at="2026-10-26T21:00:00Z"), review(2, at="2026-10-26T21:30:00Z")],
            comments={1: [{"id": 9}], 2: []},
        )
        self.assertEqual(found.review["id"], 2)
        self.assertEqual(found.comments, [])

    def test_a_review_of_an_older_commit_does_not_count(self):
        found = run(reviews=[review(1, sha="b" * 40)], comments={1: [{"id": 9}]}, waited=timedelta(minutes=5))
        self.assertEqual(found.state, "unavailable")

    def test_someone_elses_review_does_not_count(self):
        found = run(reviews=[review(1, login="aglose")], check_runs=[check(status="in_progress")])
        self.assertEqual(found.state, "pending")

    def test_out_of_quota_is_unavailable(self):
        body = "Copilot could not review this pull request: you have reached your monthly premium request limit."
        self.assertEqual(run(reviews=[review(1, body=body)]).state, "unavailable")

    def test_could_not_review_any_files_is_unavailable(self):
        body = "Copilot wasn't able to review any files in this pull request."
        self.assertEqual(run(reviews=[review(1, body=body)]).state, "unavailable")

    def test_words_about_limits_beside_real_comments_are_still_a_review(self):
        found = run(reviews=[review(1, body="Rate limit handling looks off")], comments={1: [{"id": 9}]})
        self.assertEqual(found.state, "reviewed")

    def test_a_running_check_is_pending(self):
        self.assertEqual(run(check_runs=[check(status="in_progress")], waited=timedelta(hours=1)).state, "pending")

    def test_a_check_that_just_finished_is_pending(self):
        found = run(check_runs=[check(completed=NOW - timedelta(seconds=30))])
        self.assertEqual(found.state, "pending")

    def test_a_check_that_finished_long_ago_without_a_review_is_unavailable(self):
        found = run(check_runs=[check(conclusion="failure")])
        self.assertEqual(found.state, "unavailable")
        self.assertIn("failure", found.reason)

    def test_a_pending_request_is_pending(self):
        self.assertEqual(run(requested=True, waited=timedelta(minutes=10)).state, "pending")

    def test_no_sign_of_copilot_is_pending_at_first_then_unavailable(self):
        self.assertEqual(run(waited=timedelta(seconds=30)).state, "pending")
        self.assertEqual(run(waited=timedelta(minutes=5)).state, "unavailable")


class MarkdownTest(unittest.TestCase):
    def test_lists_each_comment_with_where_it_is(self):
        found = copilot_review.Copilot(
            "reviewed",
            "",
            review(1, body="Overview"),
            [
                {"id": 7, "path": "a.kt", "line": 12, "body": "Null here"},
                {"id": 8, "path": "b.kt", "start_line": 3, "line": 5, "body": "Off by one"},
                {"id": 9, "path": "c.kt", "line": None, "original_line": 4, "body": "Outdated"},
            ],
        )
        text = copilot_review.as_markdown(found)
        self.assertIn("Overview", text)
        self.assertIn("### a.kt:12 (comment 7)", text)
        self.assertIn("### b.kt:3-5 (comment 8)", text)
        self.assertIn("### c.kt:4 (comment 9)", text)


if __name__ == "__main__":
    unittest.main()
