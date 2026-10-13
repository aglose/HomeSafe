# Dependency updates

Once a week the dependencies are brought up to date with nobody doing it. Dependabot opens the
pull requests, CI tests them, and the **Dependency updates** workflow merges the ones that are
nothing but version numbers. An update that needs code changed is never merged for you: it stays
open, labelled `dependencies: needs a human`, with a comment saying what it needs.

The pieces:

- [`.github/dependabot.yml`](../.github/dependabot.yml): what is updated, and when.
- [`.github/workflows/dependency-updates.yml`](../.github/workflows/dependency-updates.yml): what
  happens to each pull request once CI has finished on it.
- [`scripts/ci/dependency_gate.py`](../scripts/ci/dependency_gate.py): the script that decides
  whether a pull request is "only version numbers".

## What gets updated

Early every Monday (03:00 Pacific), Dependabot looks at:

| What | Where | Pull requests |
| --- | --- | --- |
| The app's libraries, Gradle plugins and Gradle itself | `gradle/libs.versions.toml`, `settings.gradle.kts`, the Gradle wrapper | minor and patch updates together in one; each major version by itself |
| The actions the workflows use | `.github/workflows/*.yml` | all together in one |
| The relay's Python packages | `relay/requirements.txt` | minor and patch together in one; each major by itself |
| The relay's base image | `relay/Dockerfile` | one |

A version is only proposed once it has been out for a week (`cooldown`), which is long enough for
most broken or malicious releases to have been noticed and pulled.

Not updated, because Dependabot can't read them: the go2rtc build CI downloads
(`scripts/ci/go2rtc-testsrc.sh`, pinned with a checksum), the iOS app's WebRTC Swift package (it
is in the Xcode project, with no `Package.swift`), and anything installed at `latest`.

One library is left out on purpose (`ignore` in `dependabot.yml`): `material3` is never proposed a
new minor version. It is numbered after the Compose it is built for and is still an alpha, so the
next one is on offer while `composeMultiplatform` stays on the stable release, and taking it
brings that whole alpha Compose in over the pinned version (1.13.0-alpha01 did, and broke the iOS
weather notification's painting). Move it by hand when `composeMultiplatform` moves.

A relay update that merges changes nothing on the box until the relay is next deployed. CI's
**Relay and CI scripts** job is what tries it first: it builds the image and imports `relay.py`
against the packages that were installed.

## What happens to a pull request

Each time CI finishes on a Dependabot branch, the workflow looks at the pull request:

1. **CI is green and the diff is only version numbers.** If there is an agent key (below), an
   agent reads the release notes Dependabot put in the description and checks them against what
   the app uses. Unless it finds something a person has to act on, the pull request is
   squash-merged, and CI is started on `main`, so the merge is built and sent to Play internal
   testing like any other.
2. **CI is green but the diff has more in it than version numbers.** Somebody pushed a code change
   to the branch (you, or the repair job below). It is labelled and left for you to review and
   merge.
3. **CI is red.** With an agent key, the repair job reads the failed logs and works out which
   update broke the build. It **holds that update back**, putting its version line back to what
   `main` has, so the others in the pull request still merge; CI runs again on the result. What
   was held back, and why, is written on the pull request and collected in an issue titled
   "Dependency updates that were held back". For a pull request with a single update there is
   nothing to hold back, so the agent may fix the code instead, and that lands in case 2: yours to
   review. If it decides the failure had nothing to do with the update, it reruns the failed jobs.
   Without a key, the failed jobs are rerun once in case it was a flake, and a second failure is
   labelled for you.

The repair job tries at most twice per pull request.

## What counts as only version numbers

[`dependency_gate.py`](../scripts/ci/dependency_gate.py) reads the difference between `main` and
the pull request's head, and says yes only if both of these hold:

- Every changed file is a manifest it knows, and every changed line is a version in it: a
  `[versions]` entry in the catalog, the tag after `uses: some/action@`, a `name==version` line in
  `requirements.txt`, the tag on a Dockerfile's `FROM`, a plugin or library version in a Gradle
  script, the Gradle version and checksum in `gradle-wrapper.properties` (along with the wrapper
  jar and scripts Gradle regenerates with it). No file added or removed, no line added or removed,
  nothing renamed, no dependency swapped for another.
- If the branch has commits that aren't Dependabot's, each line is either what `main` has or what
  Dependabot proposed. So the repair job can take an update out, but it cannot pick a different
  version or slip in an update of its own and still have the pull request merge.

Every commit the script takes for Dependabot's has to be one GitHub itself attributes to
Dependabot and marks as signed. The Gradle wrapper's jar and scripts count only when the Gradle
version itself changed, not beside a new checksum alone. The script runs from `main` and only reads the pull request out of git, so a branch cannot change the
rules it is judged by. Its tests are `scripts/ci/test_dependency_gate.py`, which CI runs.

## The agent key

Merging updates that pass needs no setup at all. Reading the release notes and repairing a red
pull request need Claude Code, with one repository secret (Settings → Secrets and variables →
Actions):

- `ANTHROPIC_API_KEY`, the same secret the Android CLI journeys use, or
- `CLAUDE_CODE_OAUTH_TOKEN`, from `claude setup-token`, to use a Claude subscription instead.

Without either, a green update still merges, a red one is retried once and then left for you.

The agent is kept away from anything it could do harm with:

- Both agent jobs run with a read-only token and no shell: they read and, for the repair, edit
  files. Nothing from the pull request is built or run in a job that holds the key, and Claude
  Code itself is installed in a step with no secret in its environment. The repair job hands its
  change over as a patch, and a separate job with no agent in it commits that to the branch.
- The agent can only ever stop a merge, never cause one. Whatever it changes goes back through CI
  and the gate above, which is a script, not a judgement.
- The repair job cannot change workflows, `.claude/` or the Gradle wrapper; a patch that touches
  one, renames included, is refused whole.
- CI started on a Dependabot branch after a repair gets no secrets, the same as the run GitHub
  starts for a Dependabot pull request.
- A Dependabot branch's iOS job runs on a GitHub-hosted macOS runner, not the self-hosted Mac that
  other branches use: the new versions' build code runs on a machine that is thrown away. It is
  slower there, since nothing is cached between runs.

Because it cannot build anything, the repair job works from the failed log alone, and CI is what
tells whether it was right. Holding an update back always builds, since `main` does.

## When something needs you

Look for the `dependencies: needs a human` label. Each labelled pull request has a comment saying
why: what in the diff isn't a version number, what the review found in the release notes, or what
failed and what the repair job made of it. Review it, push to it or merge it like any other pull
request. Once you have pushed to a Dependabot branch, Dependabot stops rebasing it.

To look at a pull request again after rerunning CI by hand, run the **Dependency updates** workflow
(Actions → Dependency updates → Run workflow) with its number.

To stop a dependency being proposed, add an `ignore` entry for it to `dependabot.yml`, or comment
on its pull request: `@dependabot ignore this major version` (or `this minor version`,
`this dependency`) where it is the only update, `@dependabot ignore <dependency name>` in a
grouped one. To pause everything, set `open-pull-requests-limit: 0` on an ecosystem there.

## Why it is built this way

- **`workflow_run`, not a job in CI.** A workflow run that a Dependabot pull request starts gets a
  read-only token and no secrets. A `workflow_run` workflow is always the copy on `main`, with its
  own token, and it runs when CI is done, which is the moment to decide.
- **Merged by the workflow, not by GitHub's auto-merge.** Auto-merge is switched on when a pull
  request opens and stays on for whatever is pushed later. Here the decision is made about the
  exact commit CI just passed, and the merge names that commit.
- **CI is started by hand in two places.** GitHub starts no `push` or `pull_request` run for
  something done with a workflow's own token. So after the repair job's commit is pushed, the
  workflow starts CI on the branch, and after a merge it starts CI on `main`; that is why `ci.yml`
  has a `workflow_dispatch` trigger and publishes for one on `main`.
- **`DEPENDENCY_UPDATES_TOKEN`** is an optional secret the merge uses instead of the workflow's
  token when it is set. It is only there in case GitHub refuses a merge with the workflow's token
  (which is not allowed to push changes to workflow files); the comment on the pull request shows
  GitHub's error if that happens. A fine-grained token for this repository with Contents, Pull
  requests and Workflows: read and write.
