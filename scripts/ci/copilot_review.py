#!/usr/bin/env python3
"""
Waits for Copilot's review of a pull request's head commit, and says whether there is one.

The "PR autopilot" workflow merges a pull request once CI is green and its code review is done.
Copilot reviews first (the repository's ruleset asks it to on every pull request, and the
workflow asks again when it can). Copilot's reviews come out of a monthly budget of premium
requests, and once that is spent it reviews nothing until the month turns over. So whenever
Copilot has not reviewed the commit CI just passed, the workflow has Claude review it instead.

    python3 scripts/ci/copilot_review.py --repo owner/name --pr 123 --sha <head> --out <dir> \
        [--wait-minutes 15] [--request]

It prints `state=reviewed|unavailable` and `findings=<n>` (for $GITHUB_OUTPUT), and writes
<dir>/copilot.json and, when there is a review, <dir>/copilot-review.md for the agent that fixes
what it found. The exact words Copilot leaves when it is out of budget are not documented, so
"unavailable" is decided by what is missing rather than by what it says:

- No review of this commit, and Copilot's check run has finished without leaving one.
- No review, no check run and no pending request for one: nothing is coming.
- A review that says it could not review (out of quota, no files it could read) and has no
  comments on the code.
- Still nothing when the wait runs out.

Reads with GH_TOKEN. --request asks Copilot for a review with COPILOT_REQUEST_TOKEN, which has to
be a person's token (GitHub does not let GITHUB_TOKEN ask Copilot), and only when no review or
request is already on its way. Standard library only; needs gh on the path.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone

COPILOT_REVIEWER = "copilot-pull-request-reviewer[bot]"
COPILOT_CHECK = "copilot-pull-request-reviewer"

# How long after Copilot's check run finishes its review may take to show up.
CHECK_GRACE_SECONDS = 120
# How long to wait with no sign at all of Copilot (no check run, no request) before giving up.
NO_SIGN_SECONDS = 180

DID_NOT_REVIEW = re.compile(
    r"premium request|quota|usage limit|rate limit|reached (?:your |the |its )?(?:monthly )?limit"
    r"|(?:unable|wasn't able|was not able|could not|couldn't) (?:to )?review",
    re.IGNORECASE,
)


@dataclass
class Copilot:
    state: str  # "reviewed", "pending" or "unavailable"
    reason: str
    review: dict | None = None
    comments: list[dict] = field(default_factory=list)


def _time(stamp: str | None) -> datetime | None:
    if not stamp:
        return None
    return datetime.fromisoformat(stamp.replace("Z", "+00:00"))


def is_copilot(user: dict | None) -> bool:
    return (user or {}).get("login") == COPILOT_REVIEWER


def judge(
    *,
    sha: str,
    reviews: list[dict],
    comments_of: callable,
    check_runs: list[dict],
    requested: bool,
    now: datetime,
    started: datetime,
) -> Copilot:
    """What Copilot has done about `sha` so far. Pure apart from `comments_of(review_id)`."""
    mine = [r for r in reviews if is_copilot(r.get("user")) and r.get("commit_id") == sha]
    if mine:
        review = max(mine, key=lambda r: r.get("submitted_at") or "")
        comments = comments_of(review["id"])
        if not comments and DID_NOT_REVIEW.search(review.get("body") or ""):
            return Copilot("unavailable", "Copilot left a review saying it could not review this commit.", review)
        return Copilot("reviewed", f"Copilot reviewed this commit with {len(comments)} comment(s).", review, comments)

    runs = [r for r in check_runs if r.get("name") == COPILOT_CHECK]
    if any(r.get("status") != "completed" for r in runs):
        return Copilot("pending", "Copilot's review is running.")
    if runs:
        finished = max(_time(r.get("completed_at")) or now for r in runs)
        if (now - finished).total_seconds() >= CHECK_GRACE_SECONDS:
            conclusions = ", ".join(sorted({str(r.get("conclusion")) for r in runs}))
            return Copilot("unavailable", f"Copilot's check run finished ({conclusions}) without reviewing this commit.")
        return Copilot("pending", "Copilot's check run has just finished; waiting for its review.")
    if requested:
        return Copilot("pending", "Copilot has been asked for a review and has not started yet.")
    if (now - started).total_seconds() >= NO_SIGN_SECONDS:
        return Copilot("unavailable", "Copilot has not been asked to review this commit, and nothing is running.")
    return Copilot("pending", "No sign of Copilot yet.")


def as_markdown(found: Copilot) -> str:
    review = found.review or {}
    lines = [f"# Copilot's review of {review.get('commit_id', '')}", "", (review.get("body") or "").strip(), ""]
    if found.comments:
        lines += ["## Comments on the code", ""]
        for comment in found.comments:
            where = comment.get("path", "?")
            line = comment.get("line") or comment.get("original_line")
            if line:
                start = comment.get("start_line")
                where += f":{start}-{line}" if start and start != line else f":{line}"
            lines += [f"### {where} (comment {comment.get('id')})", "", (comment.get("body") or "").strip(), ""]
    return "\n".join(lines)


def gh(*args: str, token: str | None = None) -> object:
    env = dict(os.environ)
    if token:
        env["GH_TOKEN"] = token
    out = subprocess.run(["gh", "api", *args], check=True, capture_output=True, text=True, env=env).stdout
    return json.loads(out) if out.strip() else None


def gh_list(path: str) -> list[dict]:
    # --paginate --slurp gives one array per page.
    pages = gh("--paginate", "--slurp", path)
    return [item for page in pages or [] for item in (page if isinstance(page, list) else page.get("check_runs", []))]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--wait-minutes", type=float, default=15)
    parser.add_argument("--request", action="store_true")
    args = parser.parse_args()

    repo, pr, sha = args.repo, args.pr, args.sha
    request_token = os.environ.get("COPILOT_REQUEST_TOKEN") or None
    started = datetime.now(timezone.utc)
    deadline = time.monotonic() + args.wait_minutes * 60
    asked = False

    def comments_of(review_id: int) -> list[dict]:
        return gh_list(f"repos/{repo}/pulls/{pr}/reviews/{review_id}/comments?per_page=100")

    while True:
        pull = gh(f"repos/{repo}/pulls/{pr}")
        requested = any(is_copilot(u) or (u or {}).get("login") == "Copilot" for u in pull.get("requested_reviewers", []))
        found = judge(
            sha=sha,
            reviews=gh_list(f"repos/{repo}/pulls/{pr}/reviews?per_page=100"),
            comments_of=comments_of,
            check_runs=gh_list(f"repos/{repo}/commits/{sha}/check-runs?check_name={COPILOT_CHECK}&per_page=100"),
            requested=requested or asked,
            now=datetime.now(timezone.utc),
            started=started,
        )
        if found.state == "unavailable" and args.request and request_token and not asked:
            # Nothing on its way: ask once, and give it the rest of the wait.
            try:
                gh(
                    "--method", "POST", f"repos/{repo}/pulls/{pr}/requested_reviewers",
                    "-f", f"reviewers[]={COPILOT_REVIEWER}",
                    token=request_token,
                )
                print(f"Asked Copilot to review #{pr}.", file=sys.stderr)
                asked = True
                started = datetime.now(timezone.utc)
                continue
            except subprocess.CalledProcessError as error:
                print(f"::warning::Could not ask Copilot for a review: {error.stderr.strip()}", file=sys.stderr)
                asked = True
        print(f"{found.state}: {found.reason}", file=sys.stderr)
        if found.state != "pending":
            break
        if time.monotonic() >= deadline:
            found = Copilot("unavailable", f"No review from Copilot after {args.wait_minutes:g} minutes.")
            break
        time.sleep(30)

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "copilot.json"), "w") as f:
        json.dump({"state": found.state, "reason": found.reason, "review": found.review, "comments": found.comments}, f, indent=2)
    if found.state == "reviewed":
        with open(os.path.join(args.out, "copilot-review.md"), "w") as f:
            f.write(as_markdown(found))
    print(f"state={found.state}")
    print(f"findings={len(found.comments)}")
    print(f"reason={found.reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
