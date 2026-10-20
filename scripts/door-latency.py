#!/usr/bin/env python3
"""
Measures how long a door opening or closing takes to reach the app, hop by hop.

    scripts/door-latency.py [--minutes 5] [--box andrew@100.99.163.71]

Then open and close doors. For each change Zigbee2MQTT publishes, it prints how long after that
publish (stamped on the box by mosquitto_sub) each reader saw it over the tailnet, as the phone
would:

  poll    reads /doors every 15 s, as the app did before the relay could hold a read open
  wait    holds /doors?since=<version>&wait=25 open, as the app does now (needs a relay with it)
  relay   when the relay recorded the change (its door_events `at`), from the snapshot

The box's clock is compared with this machine's over ssh first and the offset taken off. What
it can't see is the sensor's own radio hop (door moving to Zigbee2MQTT hearing it), a fraction
of a second for a ThirdReality sensor, and the app drawing it (one frame).

Signs in to Frigate with the test account in local.credentials.properties (the main checkout's,
found from this file's repository), which it reads itself and never prints. Standard library only.
"""

import argparse
import http.cookiejar
import json
import os
import random
import ssl
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

POLL_SECONDS = 15
WAIT_SECONDS = 25


def credentials() -> dict[str, str]:
    here = os.path.dirname(os.path.abspath(__file__))
    candidates = [os.path.join(here, "..", "local.credentials.properties")]
    # A worktree under .claude/worktrees/<name>: the file lives only in the main checkout.
    parts = os.path.abspath(here).split(os.sep)
    if ".claude" in parts:
        candidates.append(os.sep.join(parts[: parts.index(".claude")] + ["local.credentials.properties"]))
    for path in candidates:
        if os.path.exists(path):
            values = {}
            for line in open(path):
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.strip().split("=", 1)
                    values[key.strip()] = value.strip()
            return values
    sys.exit("No local.credentials.properties found")


class Relay:
    def __init__(self, server_url: str, username: str, password: str):
        parsed = urllib.parse.urlsplit(server_url)
        self.frigate = server_url.rstrip("/")
        self.relay = f"http://{parsed.hostname}:8787"
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # the box's own certificate, on the tailnet
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), urllib.request.HTTPSHandler(context=context))
        body = json.dumps({"user": username, "password": password}).encode()
        try:
            request = urllib.request.Request(f"{self.frigate}/api/login", data=body, headers={"Content-Type": "application/json"})
            self.opener.open(request, timeout=10).read()
        except urllib.error.URLError as e:
            if not isinstance(e.reason, ssl.SSLError):
                raise
            # Saved as https, served as plain HTTP (the box's Frigate has TLS off): as the app, try the other.
            self.frigate = "http://" + self.frigate.split("://", 1)[1]
            request = urllib.request.Request(f"{self.frigate}/api/login", data=body, headers={"Content-Type": "application/json"})
            self.opener.open(request, timeout=10).read()
        token = next((c.value for c in self.jar if c.name == "frigate_token"), None)
        if token is None:
            sys.exit("Frigate signed in but set no frigate_token cookie")
        self.cookie = f"frigate_token={token}"

    def doors(self, since: int | None = None, wait: int = 0) -> dict:
        query = {"days": 1}
        if since is not None:
            query.update(since=since, wait=wait)
        request = urllib.request.Request(f"{self.relay}/doors?{urllib.parse.urlencode(query)}", headers={"Cookie": self.cookie})
        with urllib.request.urlopen(request, timeout=wait + 15) as response:
            return json.load(response)


def clock_offset(box: str) -> tuple[float, float]:
    """The box's clock minus this machine's, in seconds, from the ssh round trip with the least delay; and that round trip."""
    # One session, asked many times: a fresh ssh per reading would put its handshake in the round trip.
    shell = subprocess.Popen(["ssh", box, "while read _; do date +%s.%N; done"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
    best = None
    for _ in range(25):
        before = time.time()
        shell.stdin.write("\n")
        shell.stdin.flush()
        out = shell.stdout.readline()
        after = time.time()
        trip = after - before
        offset = float(out.strip()) - (before + after) / 2
        if best is None or trip < best[1]:
            best = (offset, trip)
    shell.terminate()
    return best


class Seen:
    """When each reader first saw each door at each state: {(reader, door name, open): this machine's clock}."""

    def __init__(self):
        self.lock = threading.Lock()
        self.at: dict[tuple[str, str, bool], float] = {}
        self.relay_at: dict[tuple[str, bool], list[float]] = {}

    def snapshot(self, reader: str, body: dict, now: float) -> None:
        with self.lock:
            for door in body.get("doors", []):
                if door.get("open") is None:
                    continue
                key = (reader, door["name"], bool(door["open"]))
                last = self.at.get(key)
                # A state seen again after the other one is a new sighting.
                other = self.at.get((reader, door["name"], not door["open"]))
                if last is None or (other is not None and other > last):
                    self.at[key] = now
            names = {d["id"]: d["name"] for d in body.get("doors", [])}
            for event in body.get("events", []):
                self.relay_at.setdefault((names.get(event["door"], event["door"]), bool(event["open"])), [])
                if event["at"] not in self.relay_at[(names.get(event["door"], event["door"]), bool(event["open"]))]:
                    self.relay_at[(names.get(event["door"], event["door"]), bool(event["open"]))].append(event["at"])

    def first_after(self, reader: str, name: str, is_open: bool, since: float) -> float | None:
        with self.lock:
            at = self.at.get((reader, name, is_open))
            return at if at is not None and at >= since else None


def poll(relay: Relay, seen: Seen, stop: threading.Event) -> None:
    stop.wait(random.uniform(0, POLL_SECONDS))  # the app's phase is anyone's guess
    while not stop.is_set():
        try:
            seen.snapshot("poll", relay.doors(), time.time())
        except Exception as e:
            print(f"  poll failed: {e}", file=sys.stderr)
        stop.wait(POLL_SECONDS)


def wait(relay: Relay, seen: Seen, stop: threading.Event) -> None:
    version = None
    while not stop.is_set():
        try:
            body = relay.doors(since=version, wait=WAIT_SECONDS if version is not None else 0)
            seen.snapshot("wait", body, time.time())
            version = body.get("version")
            if version is None:
                print("  this relay can't hold a read open: no `version` in /doors; the wait reader is off", file=sys.stderr)
                return
        except Exception as e:
            print(f"  wait failed: {e}", file=sys.stderr)
            version = None
            stop.wait(1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--box", default="andrew@100.99.163.71")
    parser.add_argument("--minutes", type=float, default=5)
    parser.add_argument("--json", help="also write every change's numbers here")
    args = parser.parse_args()

    creds = credentials()
    relay = Relay(creds["test.serverUrl"], creds["test.username"], creds["test.password"])
    offset, trip = clock_offset(args.box)
    print(f"box clock {offset * 1000:+.0f} ms from this machine's (ssh round trip {trip * 1000:.0f} ms)")

    seen, stop = Seen(), threading.Event()
    for reader in (poll, wait):
        threading.Thread(target=reader, args=(relay, seen, stop), daemon=True).start()

    # %U is the box's Unix time with nanoseconds as the broker handed the message over.
    sub = subprocess.Popen(
        ["ssh", args.box, "docker exec mosquitto mosquitto_sub -h 127.0.0.1 -v -t 'zigbee2mqtt/+' -F '%U\t%t\t%p'"],
        stdout=subprocess.PIPE, text=True, bufsize=1,
    )
    contact: dict[str, bool] = {}
    changes: list[tuple[str, bool, float]] = []

    def listen():
        for line in sub.stdout:
            try:
                stamp, topic, payload = line.rstrip("\n").split("\t", 2)
                reading = json.loads(payload)
            except ValueError:
                continue
            if not isinstance(reading, dict) or not isinstance(reading.get("contact"), bool):
                continue
            name = topic.split("/", 1)[1]
            is_open = not reading["contact"]
            if contact.get(name) is not None and contact[name] != is_open:
                at = float(stamp) - offset  # on this machine's clock
                changes.append((name, is_open, at))
                print(f"{time.strftime('%H:%M:%S')}  {name} {'opened' if is_open else 'closed'}")
            contact[name] = is_open

    threading.Thread(target=listen, daemon=True).start()
    print(f"Listening for {args.minutes:g} min: open and close doors (each change needs ~{POLL_SECONDS + 5} s before the next for the poll reader to count it)")
    try:
        time.sleep(args.minutes * 60)
    except KeyboardInterrupt:
        pass
    time.sleep(POLL_SECONDS + 2)  # the poll reader's last look
    stop.set()
    sub.terminate()

    rows = []
    for i, (name, is_open, at) in enumerate(changes):
        nxt = changes[i + 1][2] if i + 1 < len(changes) else float("inf")
        row = {"door": name, "open": is_open, "mqtt": at}
        for reader in ("poll", "wait"):
            t = seen.first_after(reader, name, is_open, at)
            row[reader] = None if t is None or t > nxt + POLL_SECONDS else round(t - at, 3)
        relay_ats = [a - offset for a in seen.relay_at.get((name, is_open), []) if abs(a - offset - at) < 5]
        row["relay"] = round(min(relay_ats, key=lambda a: abs(a - at)) - at, 3) if relay_ats else None
        rows.append(row)

    print(f"\n{'door':<22}{'':8}{'relay':>8}{'wait':>8}{'poll':>8}   (seconds after Zigbee2MQTT published)")
    for r in rows:
        cells = "".join(f"{'-' if r[k] is None else format(r[k], '.2f'):>8}" for k in ("relay", "wait", "poll"))
        print(f"{r['door']:<22}{'opened' if r['open'] else 'closed':<8}{cells}")
    print()
    for reader in ("relay", "wait", "poll"):
        values = sorted(r[reader] for r in rows if r[reader] is not None)
        if values:
            p95 = values[min(len(values) - 1, int(round(0.95 * (len(values) - 1))))]
            print(f"{reader:<6} n={len(values):<3} median {statistics.median(values):6.2f} s   p95 {p95:6.2f} s   max {values[-1]:6.2f} s")
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"offset": offset, "trip": trip, "changes": rows}, f, indent=1)


if __name__ == "__main__":
    main()
