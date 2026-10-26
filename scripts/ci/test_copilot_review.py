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


def check(status="completed", conclusion="success", completed=NOW - timedelta(minutes=10), started=None):
    return {
        "name": COPILOT_CHECK,
        "started_at": (started or completed - timedelta(minutes=3)).isoformat().replace("+00:00", "Z"),
        "status": status,
        "conclusion": conclusion if status == "completed" else None,
        "completed_at": completed.isoformat().replace("+00:00", "Z") if status == "completed" else None,
    }


def run(reviews=(), comments=None, check_runs=(), requested=False, waited=timedelta(0), asked_at=None):
    comments = comments or {}
    return judge(
        sha=SHA,
        reviews=list(reviews),
        comments_of=lambda review_id: comments.get(review_id, []),
        check_runs=list(check_runs),
        requested=requested,
        now=NOW,
        started=NOW - waited,
        asked_at=asked_at,
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


class AskedAgainTest(unittest.TestCase):
    """Once Copilot is asked again, what its earlier attempt left behind no longer decides."""

    ASKED = NOW - timedelta(minutes=1)

    def test_an_old_finished_check_does_not_end_the_wait(self):
        found = run(check_runs=[check(conclusion="failure")], requested=True, asked_at=self.ASKED)
        self.assertEqual(found.state, "pending")

    def test_an_old_could_not_review_answer_does_not_end_the_wait(self):
        old = review(1, body="Copilot wasn't able to review any files.", at="2026-10-26T21:00:00Z")
        self.assertEqual(run(reviews=[old], requested=True, asked_at=self.ASKED).state, "pending")

    def test_the_new_attempt_counts(self):
        new = review(2, body="Looks good.", at="2026-10-26T21:59:30Z")
        found = run(reviews=[new], check_runs=[check(conclusion="failure")], asked_at=self.ASKED)
        self.assertEqual(found.state, "reviewed")

    def test_a_new_check_that_finished_without_a_review_is_unavailable(self):
        new_check = check(conclusion="failure", started=NOW - timedelta(seconds=50), completed=NOW - timedelta(seconds=40))
        self.assertEqual(run(check_runs=[new_check], asked_at=NOW - timedelta(minutes=5)).state, "pending")
        self.assertEqual(
            judge(
                sha=SHA, reviews=[], comments_of=lambda _: [], check_runs=[new_check], requested=True,
                now=NOW + timedelta(minutes=5), started=NOW, asked_at=NOW - timedelta(minutes=5),
            ).state,
            "unavailable",
        )


def thread(id, login="copilot-pull-request-reviewer", resolved=False, comment_id=100, path="a.kt", line=3):
    return {
        "id": id, "isResolved": resolved, "path": path, "line": line, "originalLine": line,
        "comments": {"nodes": [{"databaseId": comment_id, "body": f"Finding {id}", "author": {"login": login}}]},
    }


class OpenThreadsTest(unittest.TestCase):
    def test_keeps_copilots_unresolved_threads(self):
        ids, earlier = copilot_review.open_threads(
            [thread("T1"), thread("T2", resolved=True), thread("T3", login="aglose"), thread("T4", comment_id=7)],
            head_comment_ids={7},
        )
        self.assertEqual(ids, ["T1", "T4"])
        # T4 started on the head commit's review, so it is among that review's comments already.
        self.assertEqual([c["body"] for c in earlier], ["Finding T1"])

    def test_a_thread_with_no_comments_is_skipped(self):
        self.assertEqual(copilot_review.open_threads([{"id": "T", "isResolved": False, "comments": {"nodes": []}}], set()), ([], []))


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
        text = copilot_review.as_markdown(found, SHA)
        self.assertIn(f"# Copilot reviewed {SHA}", text)
        self.assertIn("Overview", text)
        self.assertIn("### a.kt:12 (comment 7)", text)
        self.assertIn("### b.kt:3-5 (comment 8)", text)
        self.assertIn("### c.kt:4 (comment 9)", text)



class MarkdownWithoutAReviewTest(unittest.TestCase):
    def test_says_who_reviews_and_lists_earlier_comments(self):
        found = copilot_review.Copilot(
            "unavailable", "No review from Copilot after 15 minutes.",
            earlier=[{"id": 5, "path": "d.kt", "line": 9, "body": "Still racy"}],
        )
        text = copilot_review.as_markdown(found, SHA)
        self.assertIn(f"# Copilot did not review {SHA}", text)
        self.assertIn("You are the reviewer.", text)
        self.assertIn("still open", text)
        self.assertIn("### d.kt:9 (comment 5)", text)


if __name__ == "__main__":
    unittest.main()
