"""
Links one Gmail mailbox for the relay's Amazon orders (relay.py, "Amazon orders"; docs/budget.md).
Run it on a computer with a browser, not on the box, with nothing but the standard library:

    python3 relay/gmail_link.py --client client_secret.json --mailbox andrew --out amazon.env

`client_secret.json` is the Desktop OAuth client's download from Google Cloud. The browser asks the
mailbox's owner to let the relay read their mail; Google hands back a refresh token, which is
written into `--out` (made readable only by you) beside any mailboxes linked before, never printed.
Copy that file to the relay's folder on the box and restart the relay.
"""

import argparse
import base64
import hashlib
import http.server
import json
import os
import secrets
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"


def read_env(path: str) -> dict[str, str]:
    values = {}
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                key, _, value = line.rstrip("\n").partition("=")
                if key and not key.startswith("#"):
                    values[key] = value
    return values


def write_env(path: str, values: dict[str, str]) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.writelines(f"{key}={value}\n" for key, value in values.items())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--client", required=True, help="the Desktop OAuth client's JSON from Google Cloud")
    parser.add_argument("--mailbox", required=True, help="a short name for this mailbox, e.g. andrew")
    parser.add_argument("--out", default="amazon.env", help="the env file to add it to (default amazon.env)")
    args = parser.parse_args()

    with open(args.client) as f:
        client = json.load(f).get("installed") or {}
    if not client.get("client_id") or not client.get("client_secret"):
        print("That isn't a Desktop OAuth client's JSON (no installed.client_id).", file=sys.stderr)
        return 1

    verifier = secrets.token_urlsafe(64)
    state = secrets.token_urlsafe(16)
    answer: dict[str, str] = {}
    done = threading.Event()

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            query = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(self.path).query))
            if "code" not in query and "error" not in query:
                self.send_response(404)
                self.end_headers()
                return
            answer.update(query)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Done. You can close this tab and go back to the terminal.")
            done.set()

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Callback)
    redirect = f"http://127.0.0.1:{server.server_port}/"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    url = AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client["client_id"], "redirect_uri": redirect, "response_type": "code", "scope": SCOPE,
        "access_type": "offline", "prompt": "consent", "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
    })
    print(f"Opening the browser to sign in to the mailbox for {args.mailbox!r}. If it doesn't open:\n{url}\n")
    webbrowser.open(url)
    done.wait()
    server.shutdown()
    if answer.get("state") != state or "code" not in answer:
        print(f"Google didn't link it: {answer.get('error') or 'the answer was not for this sign-in'}", file=sys.stderr)
        return 1

    body = urllib.parse.urlencode({
        "client_id": client["client_id"], "client_secret": client["client_secret"], "code": answer["code"],
        "code_verifier": verifier, "redirect_uri": redirect, "grant_type": "authorization_code",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=body), timeout=20) as r:
        tokens = json.load(r)
    if not tokens.get("refresh_token"):
        print("Google gave no refresh token. Remove the app's access at myaccount.google.com/permissions and run this again.", file=sys.stderr)
        return 1

    values = read_env(args.out)
    mailboxes = json.loads(values.get("AMAZON_GMAIL_TOKENS") or "{}")
    mailboxes[args.mailbox] = tokens["refresh_token"]
    values.update({"AMAZON_GMAIL_CLIENT_ID": client["client_id"], "AMAZON_GMAIL_CLIENT_SECRET": client["client_secret"],
                   "AMAZON_GMAIL_TOKENS": json.dumps(mailboxes, separators=(",", ":"))})
    write_env(args.out, values)
    print(f"Linked {args.mailbox!r}. {args.out} now has {len(mailboxes)} mailbox(es): {', '.join(sorted(mailboxes))}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
