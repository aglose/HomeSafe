#!/usr/bin/env python3
"""Turn a Google Device Access project into go2rtc streams for the Nest cameras.

Walks through Google's sign-in for the Smart Device Management (SDM) API, lists the cameras
the account shares with the project, and writes:

  * the Google credentials, as FRIGATE_NEST_* lines for Frigate's frigate.env, to a file only
    you can read (they are never printed), and
  * the go2rtc `streams:` entries for Frigate's config.yml, which name those variables rather
    than contain them, printed to the terminal.

HomeSafe finds the cameras by those streams: every go2rtc stream with a `nest:` source shows up
under Indoor on the Home page. See docs/nest-cameras.md for the whole setup.

Standard library only:

    python3 tools/nest-setup/nest_setup.py                 # sign in, list cameras, write nest-secrets.env
    python3 tools/nest-setup/nest_setup.py --reuse FILE    # list cameras again with a saved refresh token
"""

import argparse
import getpass
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

SCOPE = "https://www.googleapis.com/auth/sdm.service"
# Google's own Device Access guide signs in this way: after consent the browser lands on
# google.com with the code in the address bar, and you paste that address here.
REDIRECT_URI = "https://www.google.com"
TOKEN_URL = "https://oauth2.googleapis.com/token"
SDM_URL = "https://smartdevicemanagement.googleapis.com/v1"

ENV_KEYS = {
    "client_id": "FRIGATE_NEST_CLIENT_ID",
    "client_secret": "FRIGATE_NEST_CLIENT_SECRET",
    "refresh_token": "FRIGATE_NEST_REFRESH_TOKEN",
    "project_id": "FRIGATE_NEST_PROJECT_ID",
}


def post_form(url, fields):
    data = urllib.parse.urlencode(fields).encode()
    return request_json(urllib.request.Request(url, data=data, method="POST"))


def request_json(req):
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode(errors="replace")
        # Google's error bodies name what went wrong ("invalid_grant", "redirect_uri_mismatch")
        # and never echo the secrets sent, so they are safe to show.
        sys.exit(f"{req.full_url.split('?')[0]} answered HTTP {error.code}: {body}")


def ask(prompt, secret=False):
    value = (getpass.getpass if secret else input)(prompt).strip()
    if not value:
        sys.exit("Nothing entered; stopping.")
    return value


def sign_in(project_id, client_id, client_secret):
    params = {
        "redirect_uri": REDIRECT_URI,
        "access_type": "offline",
        "prompt": "consent",
        "client_id": client_id,
        "response_type": "code",
        "scope": SCOPE,
    }
    url = f"https://nestservices.google.com/partnerconnections/{project_id}/auth?" + urllib.parse.urlencode(params)
    print("\n1. Open this address in a browser signed in to the Google account that owns the Nest cameras:\n")
    print(f"   {url}\n")
    print("2. Allow access to the cameras (tick each one), then Google's own sign-in.")
    print("3. You end up on google.com. Copy the whole address from the address bar.\n")
    pasted = ask("Paste the google.com address (or just the code): ", secret=True)
    query = urllib.parse.parse_qs(urllib.parse.urlparse(pasted).query)
    code = query.get("code", [pasted])[0]
    tokens = post_form(TOKEN_URL, {
        "client_id": client_id,
        "client_secret": client_secret,
        "code": code,
        "grant_type": "authorization_code",
        "redirect_uri": REDIRECT_URI,
    })
    if "refresh_token" not in tokens:
        sys.exit("Google sent no refresh token. Remove the app's access at "
                 "https://myaccount.google.com/permissions and run this again.")
    return tokens["refresh_token"]


def access_token(client_id, client_secret, refresh_token):
    tokens = post_form(TOKEN_URL, {
        "client_id": client_id,
        "client_secret": client_secret,
        "refresh_token": refresh_token,
        "grant_type": "refresh_token",
    })
    return tokens["access_token"]


def list_cameras(project_id, token):
    req = urllib.request.Request(
        f"{SDM_URL}/enterprises/{project_id}/devices",
        headers={"Authorization": f"Bearer {token}"},
    )
    cameras = []
    for device in request_json(req).get("devices", []):
        traits = device.get("traits", {})
        live = traits.get("sdm.devices.traits.CameraLiveStream")
        if live is None:
            continue
        rooms = [r.get("displayName") for r in device.get("parentRelations", []) if r.get("displayName")]
        cameras.append({
            "device_id": device["name"].rsplit("/", 1)[-1],
            "type": device.get("type", "").rsplit(".", 1)[-1].title(),
            "name": traits.get("sdm.devices.traits.Info", {}).get("customName") or "",
            "room": rooms[0] if rooms else "",
            "protocols": live.get("supportedProtocols", []),
        })
    return cameras


def stream_name(camera, taken):
    """A go2rtc stream name from the camera's own name or its room: HomeSafe shows "living_room" as "Living Room"."""
    base = re.sub(r"[^a-z0-9]+", "_", (camera["name"] or camera["room"] or "nest_camera").lower()).strip("_") or "nest_camera"
    name, n = base, 2
    while name in taken:
        name, n = f"{base}_{n}", n + 1
    taken.add(name)
    return name


def read_env(path):
    values = {}
    with open(path) as f:
        for line in f:
            key, sep, value = line.strip().partition("=")
            if sep:
                values[key] = value
    return values


def write_env(path, values):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write("# Google Nest (Device Access) credentials for go2rtc's nest: streams. Append to Frigate's frigate.env.\n")
        for field, key in ENV_KEYS.items():
            f.write(f"{key}={values[field]}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default="nest-secrets.env", help="where to write the credentials (default: %(default)s)")
    parser.add_argument("--reuse", metavar="FILE", help="a file this script wrote before: list the cameras again without signing in")
    args = parser.parse_args()

    if args.reuse:
        env = read_env(args.reuse)
        values = {field: env.get(key, "") for field, key in ENV_KEYS.items()}
        if not all(values.values()):
            sys.exit(f"{args.reuse} is missing some of {', '.join(ENV_KEYS.values())}.")
    else:
        print("From the Device Access Console (https://console.nest.google.com/device-access) and the")
        print("Google Cloud OAuth client you made for it (see docs/nest-cameras.md):\n")
        values = {
            "project_id": ask("Device Access project ID: "),
            "client_id": ask("OAuth client ID: "),
            "client_secret": ask("OAuth client secret (not shown): ", secret=True),
        }
        values["refresh_token"] = sign_in(values["project_id"], values["client_id"], values["client_secret"])
        write_env(args.out, values)
        print(f"\nCredentials written to {args.out} (readable only by you).")

    token = access_token(values["client_id"], values["client_secret"], values["refresh_token"])
    cameras = list_cameras(values["project_id"], token)
    if not cameras:
        sys.exit("The project can see no cameras. Re-run the sign-in and tick the cameras on Google's \"Allow access\" page.")

    source = "nest:?client_id={FRIGATE_NEST_CLIENT_ID}&client_secret={FRIGATE_NEST_CLIENT_SECRET}" \
             "&refresh_token={FRIGATE_NEST_REFRESH_TOKEN}&project_id={FRIGATE_NEST_PROJECT_ID}"
    taken = set()
    print("\nAdd these under go2rtc: streams: in Frigate's config.yml (rename any stream you like;")
    print("HomeSafe shows the name with the underscores as spaces):\n")
    for camera in cameras:
        what = " · ".join(p for p in (camera["name"], camera["room"], camera["type"], "/".join(camera["protocols"])) if p)
        print(f"    # {what}")
        if "WEB_RTC" not in camera["protocols"]:
            print("    # RTSP-only (an older Nest Cam): go2rtc may not be able to play this one.")
        print(f"    {stream_name(camera, taken)}: \"{source}&device_id={camera['device_id']}\"")
    print()


if __name__ == "__main__":
    main()
