# PR autopilot

Every pull request merges itself once CI is green and its code review is done. Nobody has to press
the button. The **PR autopilot** workflow
([`.github/workflows/pr-autopilot.yml`](../.github/workflows/pr-autopilot.yml)) runs each time CI
finishes on a pull request's branch, and does this:

1. **CI has to be green** on the pull request's newest commit. A red run is left alone: the author
   (usually the Claude Code session that opened it, and is watching it) fixes it, and the next
   green run brings the autopilot back.
2. **Copilot reviews first.** The repository's ruleset asks Copilot to review every pull request,
   and the autopilot asks again when it has a token to do it with. It waits up to 15 minutes after
   CI for Copilot's review of that exact commit. Copilot is usually done well before CI is.
3. **Claude reviews when Copilot can't.** Copilot's reviews come out of a monthly budget of premium
   requests. Once that's spent, or if no review arrives, Claude (Sonnet, set by `REVIEW_MODEL` in
   the workflow) reviews the change instead.
   [`scripts/ci/copilot_review.py`](../scripts/ci/copilot_review.py) decides which.
4. **Findings are fixed automatically.** If Copilot left comments (on this commit, or on earlier
   ones and still unresolved), Claude checks each one against the code and fixes the real ones. If Claude reviewed, it fixes what it found. The fix is pushed
   to the branch as a commit ending in `PR-Autopilot: fix`, and CI and the review run again on it.
   After two such fixes, a third one goes to a person instead of looping.
5. **Then it merges** (squash, the way the repository merges) the exact commit CI passed and was
   reviewed, or nothing. Copilot's threads are resolved first, and CI is started on main so the
   merge is built and published to Play internal testing like any other.

Prompt: [`scripts/ci/pr-review-prompt.md`](../scripts/ci/pr-review-prompt.md). The bar is real bugs
and breaks of [`AGENTS.md`](../AGENTS.md), not taste: style, naming and "could be cleaner" don't
hold a merge up.

## What it never merges

- Drafts. Mark a pull request ready for review and it is picked up on the next green CI run.
- Pull requests labelled **`autopilot: hold`**. Add the label to keep one open. To hand it back,
  take the label off and run the **PR autopilot** workflow with the pull request's number (or push).
- Pull requests where a person's newest review asks for changes.
- Forks' pull requests, and Dependabot's (the [dependency updates](dependency-updates.md) have
  their own rules).

What it can't finish by itself (the fix doesn't apply, the review gives up or wants a third round,
the branch conflicts with main, the merge is refused) is labelled **`autopilot: needs a human`**
and gets a comment saying why. It is tried again on the next green CI run (after a push), or when
the workflow is run by hand with the pull request's number; the label comes off when it merges.

If branch protection requires the branch to be up to date, a pull request that is behind main has
main merged into it, and CI goes round again.

## iOS: the self-hosted Mac first, then GitHub's

CI's iOS job runs on the self-hosted Mac, which has everything built already and takes a few
minutes. When the Mac is offline, the `ios-runner` job sends it to a GitHub-hosted Mac instead, and
when it doesn't start within 15 minutes or the Mac drops off partway, `ios-watch` runs it there
(`ios-hosted`). So an asleep Mac costs a pull request at most about 15 minutes, and never blocks
the merge. How it works: [When CI goes red on main](testing.md#when-ci-goes-red-on-main).

## Setup

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | What for | Without it |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` | Claude's review and fixes (already set for the dependency updates) | A pull request merges only when Copilot reviewed it and found nothing |
| `BASELINE_PROFILE_TOKEN` (already set for the [Baseline Profile](testing.md) workflow) | The owner's fine-grained token with **Contents** and **Pull requests**, read and write. It asks Copilot for reviews (`GITHUB_TOKEN` can't), and its pushes and merges start CI by themselves | No re-asking Copilot; CI is started by hand after each push and merge |
| `PR_AUTOPILOT_TOKEN` (optional) | Used instead of the Baseline Profile token when set. With **Workflows: read and write** as well, it also merges pull requests that change `.github/workflows/`, which neither `GITHUB_TOKEN` nor the Baseline Profile token may do | Those pull requests are left for a person, with GitHub's error in the comment |
| `RUNNER_STATUS_TOKEN` | A fine-grained token for this repository with **Administration: read-only** and nothing else. Only an admin token can see whether the Mac is online, so an offline Mac is skipped straight away | iOS tries the Mac first, and `ios-watch` moves it to a GitHub-hosted Mac after 15 minutes |

Copilot's own setting: a repository ruleset for `main` with **Automatically request Copilot code
review** and **Review new pushes** turned on. The autopilot works without it, but every review
then falls to Claude.
