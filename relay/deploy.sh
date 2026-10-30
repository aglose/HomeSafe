#!/usr/bin/env bash
# Deploys the relay in this checkout's HEAD to the box, in order.
#
#   relay/deploy.sh --check   # say what would happen, change nothing
#   relay/deploy.sh           # deploy
#
# Deploys go in sequence: each one must contain everything deployed before it, so a change that
# went to the box from a branch, or one merged to main since, can't be dropped by the next deploy.
# It refuses when:
#   - the relay files in the checkout have uncommitted changes (what runs must be a commit);
#   - HEAD doesn't contain origin/main (merge main first: its relay changes would be dropped), or
#     isn't pushed (the next deploy, from any checkout, must be able to find it);
#   - HEAD doesn't contain the commit the box runs. That commit is read from the record this script
#     leaves on the box (DEPLOYED), or, the first time, found by matching the running relay.py
#     against the relay.py of every commit in the repository. A branch deployed and then
#     squash-merged counts as contained once HEAD has a commit with the same relay/ folder as it, or
#     as a later commit of its branch (the squash takes the branch's last relay/);
#   - a relay file on the box is one no commit has had (edited by hand: find out why first);
#   - another deploy holds the lock.
# Then it backs the box's files up, copies the new ones, rebuilds only the relay container, waits
# for /health, checks the container runs the new relay.py, and records the deploy. If the relay
# doesn't come back healthy, it restores the backup and rebuilds that.
#
# The box is $HOMESAFE_BOX (default andrew@100.99.163.71, its Tailscale address; the wired LAN
# address is andrew@192.168.68.65), and the relay's folder on it $HOMESAFE_RELAY_DIR (default
# surveillance/relay, from andrew's home; a cloud session signs in as `claude`, in andrew's group,
# and passes /home/andrew/surveillance/relay).
set -euo pipefail

BOX=${HOMESAFE_BOX:-andrew@100.99.163.71}
DIR=${HOMESAFE_RELAY_DIR:-surveillance/relay}
FILES=(relay.py floorplan_editor.html requirements.txt Dockerfile docker-compose.yml tesla-telemetry.json)
CHECK=0
for arg in "$@"; do
  case $arg in
    --check) CHECK=1 ;;
    *) echo "usage: relay/deploy.sh [--check]" >&2; exit 2 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"
say() { printf '%s\n' "$*"; }
fail() { printf 'refused: %s\n' "$*" >&2; exit 1; }
box() { ssh -o ConnectTimeout=10 -o BatchMode=yes "$BOX" "$@"; }
if command -v md5 >/dev/null; then hash() { md5 -q; }; else hash() { md5sum | cut -c1-32; }; fi
# The md5 of relay/$2 at commit $1 ("missing" when it has none).
local_md5() { if git cat-file -e "$1:relay/$2" 2>/dev/null; then git show "$1:relay/$2" | hash; else echo missing; fi; }
# The md5 of $1 on the box, from the listing read below.
box_md5() { awk -v f="$1" '$2 == f { print $1; found = 1 } END { if (!found) print "missing" }' <<<"$box_sums"; }

# What is to be deployed.
git diff --quiet HEAD -- relay/ || fail "relay/ has uncommitted changes; commit them first."
HEAD_SHA=$(git rev-parse HEAD)
git fetch -q origin main
git merge-base --is-ancestor origin/main HEAD || fail "HEAD doesn't contain origin/main. Merge main first, so its relay changes go out with this one."
[ -n "$(git branch -r --contains HEAD)" ] || fail "HEAD isn't pushed. Push it first: the next deploy, from any checkout, has to be able to find what the box runs."

# What the box runs.
running=$(box "docker exec homesafe-relay md5sum /app/relay.py" | cut -c1-32)
box_sums=$(box "cd $DIR && md5sum ${FILES[*]} 2>/dev/null || true")
record=$(box "cat $DIR/DEPLOYED 2>/dev/null || true")
deployed=$(sed -n 's/^commit=//p' <<<"$record")
if [ -n "$deployed" ]; then
  git cat-file -e "$deployed^{commit}" 2>/dev/null || git fetch -q origin || true
  git cat-file -e "$deployed^{commit}" 2>/dev/null || fail "the box runs $deployed (from $(sed -n 's/^branch=//p' <<<"$record")), which this checkout doesn't have. Fetch or merge that branch."
  say "box runs $deployed, as recorded ($(sed -n 's/^at=//p' <<<"$record"))"
else
  # No record yet: find the commit whose relay.py the box runs.
  for sha in $(git log --all --format=%H -- relay/relay.py); do
    if [ "$(local_md5 "$sha" relay.py)" = "$running" ]; then deployed=$sha; break; fi
  done
  [ -n "$deployed" ] || fail "the running relay.py ($running) matches no commit in this repository. Find where it came from before replacing it."
  say "box runs $deployed ($(git log -1 --format='%h %s' "$deployed")), matched by its relay.py (no deploy record yet)"
fi
if ! git merge-base --is-ancestor "$deployed" HEAD; then
  # Deployed from a branch that was squash-merged since: main has its relay/ under another commit.
  # The branch may have gone on after the deploy, so the squash holds a later relay/: any commit
  # that contains $deployed (the branch's tip, while this checkout still has it) will do.
  squashed=""
  later=$(for d in "$deployed" $(git rev-list --all --ancestry-path "$deployed..") ; do git rev-parse "$d:relay" 2>/dev/null; done | sort -u)
  for sha in $(git log --format=%H HEAD -- relay/); do
    if grep -qx "$(git rev-parse "$sha:relay")" <<<"$later"; then squashed=$sha; break; fi
  done
  [ -n "$squashed" ] || fail "HEAD doesn't contain $deployed, which the box runs ($(git log -1 --format='%h %s' "$deployed")). Merge it first, so its changes aren't dropped."
  say "HEAD has the box's relay/ as $(git log -1 --format='%h %s' "$squashed")"
fi
[ "$running" = "$(local_md5 "$deployed" relay.py)" ] || fail "the running relay.py ($running) isn't $deployed's. It was changed outside this script."
# Every relay file on the box must be one the repository has had: $deployed's, or an earlier one
# (only relay.py used to be copied over, so the rest can be behind). One that matches no commit
# was edited on the box.
for f in "${FILES[@]}"; do
  [ "$(box_md5 "$f")" = "$(local_md5 "$deployed" "$f")" ] && continue
  from=""
  for sha in $(git log --format=%H "$deployed" -- "relay/$f"); do
    if [ "$(local_md5 "$sha" "$f")" = "$(box_md5 "$f")" ]; then from=$sha; break; fi
  done
  [ -n "$from" ] || fail "the box's $f matches no commit (edited by hand?). Bring the change into the repository, or put the file back, first."
  say "the box's $f is behind: $(git log -1 --format='%h %s' "$from")'s"
done

changed=()
for f in "${FILES[@]}"; do [ "$(box_md5 "$f")" = "$(local_md5 HEAD "$f")" ] || changed+=("$f"); done
say "deploying $(git log -1 --format='%h %s' HEAD)"
say "relay commits going out:"
git log --format='  %h %s' "$deployed..HEAD" -- relay/ | sed -n '1,40p'
if [ ${#changed[@]} -eq 0 ]; then say "nothing to deploy"; exit 0; fi
say "files changing: ${changed[*]}"
[ $CHECK -eq 0 ] || { say "(--check: nothing changed)"; exit 0; }

# One deploy at a time.
box "mkdir $DIR/.deploy-lock" 2>/dev/null || fail "another deploy holds $BOX:$DIR/.deploy-lock (rmdir it if that deploy is dead)."
trap 'box "rmdir $DIR/.deploy-lock" >/dev/null 2>&1 || true' EXIT

stamp=$(date +%Y%m%d-%H%M%S)
backup="$DIR/deploys/$stamp-${deployed:0:8}"
# A file this deploy adds (one the box never had) has nothing to back up.
box "mkdir -p $backup && for f in ${FILES[*]} DEPLOYED; do if [ -e $DIR/\$f ]; then cp $DIR/\$f $backup/ || exit 1; fi; done"
say "backed up to $BOX:$backup"

tmp=$(mktemp -d)
for f in "${changed[@]}"; do git show "HEAD:relay/$f" > "$tmp/$f"; done
scp -q -o BatchMode=yes "${changed[@]/#/$tmp/}" "$BOX:$DIR/"
rm -rf "$tmp"

healthy() {
  box "cd $DIR && docker compose up -d --build --no-deps homesafe-relay" >/dev/null 2>&1 || return 1
  for _ in $(seq 1 30); do
    sleep 3
    box "curl -sf -o /dev/null http://127.0.0.1:8787/health" && return 0
  done
  return 1
}

if ! healthy || [ "$(box "docker exec homesafe-relay md5sum /app/relay.py" | cut -c1-32)" != "$(local_md5 HEAD relay.py)" ]; then
  say "the new relay didn't come up healthy; restoring $backup"
  # Each file as it was, and a file the failed deploy added gone again.
  box "for f in ${FILES[*]}; do if [ -e $backup/\$f ]; then cp $backup/\$f $DIR/; else rm -f $DIR/\$f; fi; done && cd $DIR && docker compose up -d --build --no-deps homesafe-relay" >/dev/null 2>&1
  box "docker logs --since 5m homesafe-relay 2>&1 | tail -30" || true
  fail "rolled back to $deployed"
fi

# Tesla's telemetry server (docs/tesla.md, "Telemetry") runs beside the relay once its certificate
# has been made on the box; it reads its config only when it starts.
if box "test -e $DIR/data/telemetry/server.pem"; then
  restart=""
  [[ " ${changed[*]} " == *" tesla-telemetry.json "* ]] && restart="&& docker compose restart tesla-telemetry"
  box "cd $DIR && docker compose up -d --no-deps tesla-telemetry $restart" >/dev/null 2>&1 \
    || say "the telemetry server didn't start; see: docker logs tesla-telemetry"
fi

branch=$(git rev-parse --abbrev-ref HEAD)
box "cat > $DIR/DEPLOYED <<EOF
commit=$HEAD_SHA
branch=$branch
at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
by=$(git config user.name)
previous=$deployed
EOF
printf '%s %s %s %s\n' '$(date -u +%Y-%m-%dT%H:%M:%SZ)' '$HEAD_SHA' '$branch' 'from ${deployed:0:12}' >> $DIR/deploys/log"
say "deployed ${HEAD_SHA:0:12} ($branch); the record is $BOX:$DIR/DEPLOYED, the history $BOX:$DIR/deploys/log"
