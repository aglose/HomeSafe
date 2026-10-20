#!/usr/bin/env python3
"""
Measures how long a door opening or closing takes to reach the app, hop by hop.

    scripts/door-latency.py [--minutes 5] [--box andrew@100.99.163.71]

Then open and close doors. For each change Zigbee2MQTT publishes, it prints how long after that
publish (stamped on the box by mosquitto_sub) each reader first had it in the relay's history
over the tailnet, as the phone would:

  probe   reads /doors every second: an upper bound on when the relay had it committed, and
          any read slower than a second is printed as it happens (the relay was stuck)
  wait    holds /doors?since=<version>&wait=25 open, as the app does now (needs a relay with it)
  poll    reads /doors every 15 s, as the app did before the relay could hold a read open

Each change is matched to its entry in the relay's history (its `at` is stamped as the MQTT
message arrives), so an opening shorter than a poll still counts, as the app's history shows it.

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
    """When each reader first had each history entry: {(reader, door name, open, at): this machine's clock}."""

    def __init__(self):
        self.lock = threading.Lock()
        self.at: dict[tuple[str, str, bool, float], float] = {}

    def snapshot(self, reader: str, body: dict, now: float) -> None:
        names = {d["id"]: d["name"] for d in body.get("doors", [])}
        with self.lock:
            for event in body.get("events", []):
                key = (reader, names.get(event["door"], event["door"]), bool(event["open"]), event["at"])
                self.at.setdefault(key, now)

    def first(self, reader: str, name: str, is_open: bool, box_at: float) -> float | None:
        """
        When [reader] first had the entry for this change: the one stamped nearest [box_at] on the
        box's clock (the relay stamps it as the message arrives, milliseconds after), within a
        second. The nearest, not the earliest: a door opened twice within a second has two
        entries, and each change must be timed by its own.
        """
        with self.lock:
            hits = [(abs(at - box_at), t) for (r, n, o, at), t in self.at.items() if r == reader and n == name and o == is_open and abs(at - box_at) < 1]
            return min(hits)[1] if hits else None


def every(seconds: float, reader: str, relay: Relay, seen: Seen, stop: threading.Event) -> None:
    stop.wait(random.uniform(0, seconds))  # the app's phase is anyone's guess
    while not stop.is_set():
        started = time.time()
        try:
            seen.snapshot(reader, relay.doors(), time.time())
            took = time.time() - started
            if reader == "probe" and took > 1:
                print(f"  {time.strftime('%H:%M:%S')} probe: /doors took {took:.1f} s (the relay was stuck)")
        except Exception as e:
            print(f"  {reader} failed: {e}", file=sys.stderr)
        stop.wait(max(0.0, seconds - (time.time() - started)))


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


READERS = ("probe", "wait", "poll")


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
    threading.Thread(target=every, args=(1, "probe", relay, seen, stop), daemon=True).start()
    threading.Thread(target=every, args=(POLL_SECONDS, "poll", relay, seen, stop), daemon=True).start()
    threading.Thread(target=wait, args=(relay, seen, stop), daemon=True).start()

    # %U is the box's Unix time with nanoseconds as the broker handed the message over.
    sub = subprocess.Popen(
        ["ssh", args.box, "docker exec mosquitto mosquitto_sub -h 127.0.0.1 -v -t 'zigbee2mqtt/+' -F '%U\t%t\t%p'"],
        stdout=subprocess.PIPE, text=True, bufsize=1,
    )
    contact: dict[str, bool] = {}
    changes: list[tuple[str, bool, float, float]] = []

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
                changes.append((name, is_open, at, float(stamp)))
                print(f"{time.strftime('%H:%M:%S')}  {name} {'opened' if is_open else 'closed'}")
            contact[name] = is_open

    threading.Thread(target=listen, daemon=True).start()
    print(f"Listening for {args.minutes:g} min: open and close doors")
    try:
        time.sleep(args.minutes * 60)
    except KeyboardInterrupt:
        pass
    time.sleep(POLL_SECONDS + 2)  # the poll reader's last look
    stop.set()
    sub.terminate()

    rows = []
    for name, is_open, at, box_at in changes:
        row = {"door": name, "open": is_open, "mqtt": at}
        for reader in READERS:
            t = seen.first(reader, name, is_open, box_at)
            row[reader] = None if t is None else round(t - at, 3)
        rows.append(row)

    print(f"\n{'door':<22}{'':8}" + "".join(f"{r:>8}" for r in READERS) + "   (seconds after Zigbee2MQTT published)")
    for r in rows:
        cells = "".join(f"{'-' if r[k] is None else format(r[k], '.2f'):>8}" for k in READERS)
        print(f"{r['door']:<22}{'opened' if r['open'] else 'closed':<8}{cells}")
    print()
    for reader in READERS:
        values = sorted(r[reader] for r in rows if r[reader] is not None)
        if values:
            p95 = values[min(len(values) - 1, int(round(0.95 * (len(values) - 1))))]
            print(f"{reader:<6} n={len(values):<3} median {statistics.median(values):6.2f} s   mean {statistics.mean(values):6.2f} s   p95 {p95:6.2f} s   max {values[-1]:6.2f} s")
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"offset": offset, "trip": trip, "changes": rows}, f, indent=1)


if __name__ == "__main__":
    main()
