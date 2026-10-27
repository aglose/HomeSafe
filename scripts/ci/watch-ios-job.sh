#!/usr/bin/env bash
# Watches this run's "iOS tests and build" job from a GitHub-hosted runner, so ci-green never waits
# on the self-hosted Mac itself (see the ios-watch job in .github/workflows/ci.yml). Writes
# result=passed|failed|fallback to $GITHUB_OUTPUT:
#
#   passed    the job succeeded.
#   failed    the job failed on its own account (a test, the build); exits 1, so "Re-run failed
#             jobs" runs this watcher again along with the job.
#   fallback  the Mac never got to the end: the job sat queued past START_TIMEOUT_S while the Mac
#             was offline (or its status couldn't be read), or past QUEUE_LIMIT_S even with the
#             Mac online, or its runner went away partway through (the job ends with a step still
#             marked in progress and no log). The ios-hosted job then runs the same work on a
#             GitHub-hosted Mac.
#
# A job queued behind other runs' iOS jobs on an online Mac is waited for: the Mac takes one job
# at a time, and several pull requests pushed together line up there. Whether it is online comes
# from the repository's runner list, read with RUNNERS_TOKEN (the RUNNER_STATUS_TOKEN secret the
# ios-runner job uses); without it, the job falls back at START_TIMEOUT_S as if the Mac were away.
#
# Only a job sent to the Mac (ON_MAC=true) falls back; one already on a hosted runner just passes
# or fails.
#
# Environment: GH_TOKEN (actions: read), RUNNERS_TOKEN (optional), GITHUB_REPOSITORY, RUN_ID,
# RUN_ATTEMPT, JOB_NAME, ON_MAC, GITHUB_OUTPUT; START_TIMEOUT_S (default 900), QUEUE_LIMIT_S
# (default 14400) and POLL_S (default 30) for tests.
set -euo pipefail

start_timeout=${START_TIMEOUT_S:-900}
# The longest an online Mac may keep the job queued: under the watcher job's own limit, with room
# for the hosted run that follows.
queue_limit=${QUEUE_LIMIT_S:-14400}
poll=${POLL_S:-30}
# How long the job may take to appear in this attempt at all (it is created beside this one).
appear_timeout=${APPEAR_TIMEOUT_S:-300}
started=$(date +%s)
api_failures=0

result() {
    echo "result=$1" >> "$GITHUB_OUTPUT"
    echo "iOS job: $1"
}

# The Mac couldn't finish: on a job sent there, hand it to a hosted Mac; anywhere else, a failure.
fall_back() {
    if [ "$ON_MAC" = "true" ]; then
        echo "::notice::$1 It runs again on a GitHub-hosted Mac (the ios-hosted job)."
        result fallback
        exit 0
    fi
    echo "::error::$1"
    result failed
    exit 1
}

# Succeeds when the repository's runner list has a self-hosted macOS runner online.
mac_online() {
    local online
    [ -n "${RUNNERS_TOKEN:-}" ] || return 1
    online=$(GH_TOKEN=$RUNNERS_TOKEN gh api "repos/$GITHUB_REPOSITORY/actions/runners" --paginate \
        --jq '[.runners[] | select(.status == "online" and any(.labels[]; .name == "self-hosted") and any(.labels[]; .name == "macOS"))] | length' \
        | awk '{ n += $1 } END { print n + 0 }') || return 1
    [ "$online" -gt 0 ]
}
said_queued=false

while :; do
    if ! jobs=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$RUN_ID/jobs?filter=latest&per_page=100"); then
        api_failures=$((api_failures + 1))
        if [ "$api_failures" -ge 10 ]; then
            echo "::error::Couldn't read this run's jobs ten times running."
            result failed
            exit 1
        fi
        sleep "$poll"
        continue
    fi
    api_failures=0
    job=$(jq -c --arg name "$JOB_NAME" --argjson attempt "$RUN_ATTEMPT" \
        '[.jobs[] | select(.name == $name and .run_attempt == $attempt)] | first // empty' <<< "$jobs")

    if [ -z "$job" ]; then
        if [ $(( $(date +%s) - started )) -ge "$appear_timeout" ]; then
            echo "::error::No \"$JOB_NAME\" job in attempt $RUN_ATTEMPT of this run."
            result failed
            exit 1
        fi
        sleep "$poll"
        continue
    fi

    status=$(jq -r '.status' <<< "$job")
    runner=$(jq -r '.runner_name // ""' <<< "$job")
    case "$status" in
        completed)
            conclusion=$(jq -r '.conclusion' <<< "$job")
            if [ "$conclusion" = "success" ]; then
                result passed
                exit 0
            fi
            # A runner that drops off leaves the job ended with its steps still in progress or
            # pending; a job that failed by itself has every step completed.
            if [ "$(jq '([.steps[]? | select(.status != "completed")] | length > 0) or ((.runner_name // "") == "")' <<< "$job")" = "true" ]; then
                fall_back "The iOS job's runner (${runner:-none}) went away before it finished."
            fi
            echo "::error::The iOS job ended $conclusion on ${runner:-its runner}; its log says why."
            result failed
            exit 1
            ;;
        in_progress)
            ;;
        *)
            created=$(date -d "$(jq -r '.created_at' <<< "$job")" +%s)
            waited=$(( $(date +%s) - created ))
            if [ "$ON_MAC" = "true" ] && [ "$waited" -ge "$start_timeout" ]; then
                if ! mac_online; then
                    fall_back "The iOS job waited ${waited}s for the self-hosted Mac without starting, and the Mac is offline (or its status couldn't be read)."
                fi
                if [ "$waited" -ge "$queue_limit" ]; then
                    fall_back "The iOS job waited ${waited}s for the self-hosted Mac without starting, although the Mac is online."
                fi
                if [ "$said_queued" = false ]; then
                    echo "::notice::The iOS job has waited ${waited}s for the self-hosted Mac, which is online, probably busy with another run's iOS job; still waiting."
                    said_queued=true
                fi
            fi
            ;;
    esac
    sleep "$poll"
done
