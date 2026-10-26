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

It prints `state=reviewed|unavailable|moved` and `findings=<n>` (for $GITHUB_OUTPUT), and writes
<dir>/copilot.json and, when there is anything of Copilot's to check, <dir>/copilot-review.md for
the agent that fixes what it found. `moved` means the pull request has a newer commit than --sha,
whose own CI run will bring the workflow back.

The findings are the comments on Copilot's review of this commit, and also any of Copilot's
threads from earlier commits that nobody has resolved: a review of a new push looks at what
changed, and does not say whether what it found before still holds. copilot.json lists the ids of
all those threads, so that once the agent has checked them (or there were none) exactly those, and
no thread that arrives later, are resolved. The exact words Copilot leaves when it is out of budget are not documented, so
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
    state: str  # "reviewed", "pending", "unavailable" or "moved"
    reason: str
    review: dict | None = None
    comments: list[dict] = field(default_factory=list)
    # Copilot's unresolved threads from earlier commits, and the ids of every unresolved one.
    earlier: list[dict] = field(default_factory=list)
    threads: list[str] = field(default_factory=list)


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
    asked_at: datetime | None = None,
) -> Copilot:
    """What Copilot has done about `sha` so far. Pure apart from `comments_of(review_id)`.

    `asked_at` is when this script asked Copilot again: reviews and check runs from before then are
    the attempt that came to nothing, and only the new one counts.
    """

    def since_asked(stamp: str | None) -> bool:
        return asked_at is None or (_time(stamp) or now) >= asked_at

    mine = [
        r for r in reviews
        if is_copilot(r.get("user")) and r.get("commit_id") == sha and since_asked(r.get("submitted_at"))
    ]
    if mine:
        review = max(mine, key=lambda r: r.get("submitted_at") or "")
        comments = comments_of(review["id"])
        if not comments and DID_NOT_REVIEW.search(review.get("body") or ""):
            return Copilot("unavailable", "Copilot left a review saying it could not review this commit.", review)
        return Copilot("reviewed", f"Copilot reviewed this commit with {len(comments)} comment(s).", review, comments)

    runs = [r for r in check_runs if r.get("name") == COPILOT_CHECK and since_asked(r.get("started_at"))]
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


def open_threads(nodes: list[dict], head_comment_ids: set[int]) -> tuple[list[str], list[dict]]:
    """Copilot's unresolved review threads: the ids of all of them, and the ones that did not start
    on the head commit's review, as comments (path, line, body)."""
    ids, earlier = [], []
    for node in nodes:
        first = ((node.get("comments") or {}).get("nodes") or [{}])[0]
        author = ((first.get("author") or {}).get("login") or "")
        if node.get("isResolved") or not author.startswith(COPILOT_CHECK):
            continue
        ids.append(node["id"])
        if first.get("databaseId") not in head_comment_ids:
            earlier.append({
                "id": first.get("databaseId"),
                "path": node.get("path"),
                "line": node.get("line"),
                "original_line": node.get("originalLine"),
                "body": first.get("body"),
            })
    return ids, earlier


def _where(comment: dict) -> str:
    where = comment.get("path") or "?"
    line = comment.get("line") or comment.get("original_line")
    if line:
        start = comment.get("start_line")
        where += f":{start}-{line}" if start and start != line else f":{line}"
    return where


def _comments(comments: list[dict]) -> list[str]:
    lines = []
    for comment in comments:
        lines += [f"### {_where(comment)} (comment {comment.get('id')})", "", (comment.get("body") or "").strip(), ""]
    return lines


def as_markdown(found: Copilot, sha: str) -> str:
    if found.state == "reviewed":
        review = found.review or {}
        lines = [f"# Copilot reviewed {sha}", "", (review.get("body") or "").strip(), ""]
        if found.comments:
            lines += ["## Its comments on the code", ""] + _comments(found.comments)
    else:
        lines = [f"# Copilot did not review {sha}", "", f"{found.reason} You are the reviewer.", ""]
    if found.earlier:
        lines += [
            "## Its comments on earlier commits that are still open",
            "",
            "Check whether each still applies to the code as it is now.",
            "",
        ] + _comments(found.earlier)
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


THREADS_QUERY = """
query($owner: String!, $name: String!, $pr: Int!, $after: String) {
  repository(owner: $owner, name: $name) { pullRequest(number: $pr) {
    reviewThreads(first: 100, after: $after) {
      pageInfo { hasNextPage endCursor }
      nodes { id isResolved path line originalLine
              comments(first: 1) { nodes { databaseId body author { login } } } }
    }
  } }
}"""


def thread_nodes(repo: str, pr: int) -> list[dict]:
    owner, name = repo.split("/", 1)
    nodes, after = [], None
    while True:
        args = ["graphql", "-F", f"owner={owner}", "-F", f"name={name}", "-F", f"pr={pr}", "-f", f"query={THREADS_QUERY}"]
        if after:
            args += ["-f", f"after={after}"]
        threads = gh(*args)["data"]["repository"]["pullRequest"]["reviewThreads"]
        nodes += threads["nodes"]
        if not threads["pageInfo"]["hasNextPage"]:
            return nodes
        after = threads["pageInfo"]["endCursor"]


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
    asked_at = None

    def comments_of(review_id: int) -> list[dict]:
        return gh_list(f"repos/{repo}/pulls/{pr}/reviews/{review_id}/comments?per_page=100")

    while True:
        pull = gh(f"repos/{repo}/pulls/{pr}")
        if pull["head"]["sha"] != sha:
            found = Copilot("moved", f"#{pr} has moved on from {sha[:7]} to {pull['head']['sha'][:7]}.")
            print(found.reason, file=sys.stderr)
            break
        requested = any(is_copilot(u) or (u or {}).get("login") == "Copilot" for u in pull.get("requested_reviewers", []))
        found = judge(
            sha=sha,
            reviews=gh_list(f"repos/{repo}/pulls/{pr}/reviews?per_page=100"),
            comments_of=comments_of,
            check_runs=gh_list(f"repos/{repo}/commits/{sha}/check-runs?check_name={COPILOT_CHECK}&per_page=100"),
            requested=requested or asked,
            now=datetime.now(timezone.utc),
            started=started,
            asked_at=asked_at,
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
                asked_at = started = datetime.now(timezone.utc)
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

    if found.state != "moved":
        found.threads, found.earlier = open_threads(thread_nodes(repo, pr), {c.get("id") for c in found.comments})

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "copilot.json"), "w") as f:
        json.dump(
            {"state": found.state, "reason": found.reason, "review": found.review,
             "comments": found.comments, "earlier": found.earlier, "threads": found.threads},
            f, indent=2,
        )
    if found.state == "reviewed" or found.earlier:
        with open(os.path.join(args.out, "copilot-review.md"), "w") as f:
            f.write(as_markdown(found, sha))
    print(f"state={found.state}")
    print(f"findings={len(found.comments) + len(found.earlier)}")
    print(f"reason={found.reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
