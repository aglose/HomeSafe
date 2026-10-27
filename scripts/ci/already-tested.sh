#!/usr/bin/env bash
# Decides whether a run on main may skip the slow test jobs (the emulators and iOS) because the
# exact code it would test has already passed ci-green on its pull request (see the
# already-tested job in .github/workflows/ci.yml). Writes skip=true|false and pr=<number> to
# $GITHUB_OUTPUT.
#
# A pull request's CI tests its merge with main as main was then, and ci-green records the tree
# of that merge as a commit status on the pull request's head ("tree <sha>", context
# "ci-green tree"). A squash merge of that head onto the same main has the very same tree. If
# main moved in between, the trees differ and everything runs, as it does when anything here
# can't be read: this only ever skips on a match.
#
# Environment: GH_TOKEN (contents: read, pull-requests: read, statuses: read), GITHUB_REPOSITORY,
# GITHUB_SHA, GITHUB_OUTPUT; DEFAULT_BRANCH (default main).
set -euo pipefail

branch=${DEFAULT_BRANCH:-main}
context="ci-green tree"

decide() {
    echo "skip=$1" >> "$GITHUB_OUTPUT"
    [ -n "${2:-}" ] && echo "pr=$2" >> "$GITHUB_OUTPUT"
    exit 0
}

# Anything that can't be read runs the tests rather than failing the run.
run_everything() {
    echo "::notice::$1 The test jobs run."
    decide false "${2:-}"
}

tree=$(gh api "repos/$GITHUB_REPOSITORY/git/commits/$GITHUB_SHA" --jq .tree.sha) ||
    run_everything "Couldn't read the tree of ${GITHUB_SHA:0:7}."

merged=$(gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/pulls" \
    --jq "[.[] | select(.merged_at != null and .base.ref == \"$branch\")][0] // empty | \"\(.number) \(.head.sha)\"") ||
    run_everything "Couldn't look up the pull request ${GITHUB_SHA:0:7} merged."
[ -n "$merged" ] || run_everything "${GITHUB_SHA:0:7} merged no pull request."
read -r pr head <<< "$merged"

# Only ci-green's own statuses count (github-actions[bot] posts them with this workflow's token);
# every green run on that head left one, one per main it was tested against.
trees=$(gh api --paginate "repos/$GITHUB_REPOSITORY/commits/$head/statuses?per_page=100" \
    --jq ".[] | select(.context == \"$context\" and .state == \"success\" and .creator.login == \"github-actions[bot]\") | .description") ||
    run_everything "Couldn't read the statuses of #$pr's head ${head:0:7}." "$pr"

if grep -qxF "tree $tree" <<< "$trees"; then
    echo "::notice::#$pr passed ci-green on exactly this tree (${tree:0:7}), so the emulator and iOS jobs are skipped on main."
    decide true "$pr"
fi
if [ -z "$trees" ]; then
    run_everything "#$pr's head ${head:0:7} has no ci-green tree recorded." "$pr"
fi
run_everything "main moved after #$pr's CI ran, so it passed on other trees than this one (${tree:0:7})." "$pr"
