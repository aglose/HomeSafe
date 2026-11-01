# Tesla: where the household cars really are

The camera guesses when a household car arrives or leaves (see [notifications.md](notifications.md),
"Car presence"). Both cars are Teslas, and Tesla's Fleet API knows where each one is, so the relay
asks Tesla at exactly those moments:

- **Departure:** 10 minutes after the camera saw the car go, near home turns the departure down,
  and well away confirms it without the parking-spot check.
- **Arrival:** near home confirms it at once, without waiting for the vision model. Well away
  means the classifier gave the name to some other car, so the arrival is turned down.
- **Asleep at a departure:** the departure is turned down. A car that drove off 10 minutes ago is
  awake, still driving or only just parked, and a Tesla takes longer than that to sleep. On
  2026-09-30 the camera made up five departures of the parked Tesla, and Tesla had it asleep at
  every one.
- **Asleep at an arrival, unlinked, or out of calls:** the camera decides, exactly as before. The
  relay never wakes a car.

- **Every half hour, whatever the camera saw:** the relay asks about each car once. A car recorded
  home that Tesla has well away has left; one recorded away that Tesla has near home is home. This
  catches the curb: a car parked in the street comes and goes outside the camera's driveway zone,
  so only Tesla sees it. Those notifications say "Seen by Tesla" in place of the camera, and can be
  up to half an hour late. `TESLA_CHECK_SECONDS` sets the interval (default 1800). An answer
  already in hand from the last half hour (a departure's, the parking job's) stands in for it, and
  once Tesla has said a car is **asleep** it is asked about again only every 2 hours
  (`TESLA_ASLEEP_CHECK_SECONDS`, default 7200): a sleeping car hasn't moved, and asked again it
  says the same. A car parked at the curb that wakes and drives off is then seen up to 2 hours
  late, or never: on 2026-10-28 Sarah's car drove off from the curb and was asleep again
  between two checks, so the Cars page kept it on the curb. The box has had
  `TESLA_ASLEEP_CHECK_SECONDS=1800` in `tesla.env` since, until the cars stream (below).
- **Whenever a car streams (Fleet Telemetry):** an awake car tells the relay where it is as it
  moves, so a car is seen to leave or come home within 15 seconds of passing the line, a car that
  parks is placed from the moment it shifts into P, and the Cars page follows a drive within a
  minute. Asking stays the backstop for a stream that has stopped; a sleeping car that streams is
  asked about every 6 hours (`TESLA_STREAM_ASLEEP_CHECK_SECONDS`), since it says itself when it
  wakes. See "Telemetry" below.

"Near" is within the home radius (Settings → Home) plus 50 m; "away" is more than 250 m past it.

The same answers say which street and which side a car parked at the curb is on, and the relay
tells its drivers before the signs there say it must move: see [parking.md](parking.md).

## Cost

Personal accounts get a $10 monthly credit, and a location request costs $0.002 (500 per $1). The
relay asks at most once a minute per car when the camera saw something, plus once per car every half
hour (every 2 hours while it sleeps), and never more than `TESLA_CALLS_PER_DAY` (200) times a day.
For two cars that sleep most of the night, that's around 60 calls a day: under $4 a month.

On 2026-10-27 the relay had made 188 calls by 21:30 UTC, near the cap and on course for about $11
a month, with too little logged to say why. Since then every call is logged with why it was made
and the day's count, and the day's calls are tallied by reason:

```bash
docker logs homesafe-relay 2>&1 | grep "tesla: asked"
```
```
tesla: asked about sarahs_car (check): asleep · 37 calls today
```
```bash
docker exec homesafe-relay python -c "import sqlite3; print(sqlite3.connect('/data/relay.db').execute(\"SELECT value FROM state WHERE key='tesla_calls'\").fetchone()[0])"
```
```
{"2026-10-28": 61, "why": {"check": 48, "departure": 3, "parking": 2, ...}}
```

The reasons are `check` (the half-hourly check), `arrival` and `departure` (the camera saw one),
`parking` (the parking job, when nothing else has asked for a while), `parking: still there?` (just
before telling someone to move a car), `parking-check` (the command) and `link` (signing in).

Streaming is billed per value sent: Tesla's own example is $0.006 an hour of driving, and the
fields here come to about $0.01. For two cars driving an hour or two a day that's well under $1 a
month, and the calls it saves (no half-hourly checks while a car drives, fewer while it sleeps)
pay for it.

## Setup (once)

Everything below happens on the Frigate box unless it says otherwise. `<host>` is the box's Funnel
name, `debian-surveillance.tail4c441a.ts.net`.

1. **Developer app.** At [developer.tesla.com](https://developer.tesla.com), signed in with a Tesla
   account that has MFA on, create an application:
   - Allowed origin: `https://<host>`
   - Redirect URI: `https://<host>/tesla/callback`
   - Scopes: *Vehicle Information*, *Vehicle Location* and *Vehicle Charging Management* (for the
     Supercharger receipts, see [charging.md](charging.md)). Profile and openid are added automatically.
   - Add a payment method under billing; the monthly credit covers this use.

   Note the **client id** and **client secret**.
2. **Key pair.** Tesla requires the app's public key on its domain. The relay only needs the public
   half; keep the private one somewhere safe, off the box.
   ```bash
   openssl ecparam -name prime256v1 -genkey -noout -out ~/tesla-private-key.pem
   ```
   ```bash
   openssl ec -in ~/tesla-private-key.pem -pubout -out ~/surveillance/relay/data/tesla-public-key.pem
   ```
3. **Settings.** Create `~/surveillance/relay/tesla.env` (gitignored; the repo is public):
   ```
   TESLA_CLIENT_ID=...
   TESLA_CLIENT_SECRET=...
   TESLA_CARS={"andrews_tesla": "<VIN>", "sarahs_car": "<VIN>"}
   ```
   The VINs are in the Tesla app under the car's software screen. The names are the classifier's.
4. **Funnel.** Publish the two paths Tesla needs. Only these and `/google` are public.
   ```bash
   sudo tailscale funnel --bg --set-path /tesla http://127.0.0.1:8787/tesla
   ```
   ```bash
   sudo tailscale funnel --bg --set-path /.well-known/appspecific http://127.0.0.1:8787/.well-known/appspecific
   ```
   Check with `curl https://<host>/.well-known/appspecific/com.tesla.3p.public-key.pem`.
5. **Deploy and register** the domain with the Fleet API:
   ```bash
   docker compose up -d --build homesafe-relay
   ```
   ```bash
   docker exec homesafe-relay python relay.py tesla-register
   ```
6. **Link each Tesla account that owns a car.** This prints a one-time link, good for 15 minutes.
   Open it on a phone or laptop and sign in to Tesla:
   ```bash
   docker exec homesafe-relay python relay.py tesla-link
   ```
   If Sarah's car is on her own Tesla account, either she opens a second link, or she adds Andrew
   as a driver in the Tesla app (then Andrew's account sees both cars).

The page after sign-in names the cars the relay can now see. From then on, the relay log shows
lines like `tesla: sarahs_car is 42 m from home -> home` whenever it asks.

## Telemetry

With Fleet Telemetry each car, whenever it is awake, opens a mutual-TLS connection to a server of
the application's and streams the fields it was told to (relay.py, "Tesla telemetry",
`TESLA_TELEMETRY_FIELDS`: where it is, its heading, speed and gear, battery and range, charging and
the power it draws, locks, Sentry Mode, odometer and energy used, the route's destination and time
to it, the temperatures; what the charging fields are for is in [charging.md](charging.md)). That
server is Tesla's own [fleet-telemetry](https://github.com/teslamotors/fleet-telemetry), the
`tesla-telemetry` container beside the relay, which publishes each value to the Mosquitto broker
the door sensors use (`tesla/<VIN>/v/<field>`), where the relay listens. The relay takes in only
TESLA_CARS' VINs. What the stream last said is in `/parking` as each car's `live`.

Once, on the box:

1. **Certificate.** The cars trust the CA the configuration names, so the server's certificate
   comes from a CA of our own (ten years; nothing to renew):
   ```bash
   cd ~/surveillance/relay && mkdir -p data/telemetry && cd data/telemetry
   ```
   ```bash
   openssl ecparam -name prime256v1 -genkey -noout -out ca.key && openssl req -x509 -new -key ca.key -sha256 -days 3650 -subj "/CN=HomeSafe telemetry CA" -out ca.pem
   ```
   ```bash
   openssl ecparam -name prime256v1 -genkey -noout -out server.key && openssl req -new -key server.key -subj "/CN=<host>" -out server.csr && printf "subjectAltName=DNS:<host>\nextendedKeyUsage=serverAuth\n" > server.ext && openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -sha256 -days 3650 -extfile server.ext -out server.pem
   ```
   ```bash
   chmod 600 ca.key server.key
   ```
2. **Funnel.** Port 8443 goes through to the server untouched, since the TLS has to end there:
   ```bash
   sudo tailscale funnel --bg --tcp 8443 tcp://127.0.0.1:4443
   ```
3. **Start it.** `relay/deploy.sh` brings it up with the relay from then on.
   ```bash
   cd ~/surveillance/relay && docker compose up -d tesla-telemetry
   ```
   ```bash
   docker logs tesla-telemetry 2>&1 | tail
   ```

Then:

4. **Virtual key, on each car.** Tesla only lets a car stream to an application whose key it
   holds. On a phone that is a key for the car, standing by the car, open
   `https://tesla.com/_ak/<host>` and approve it in the Tesla app.
5. **Tell the cars,** from the Mac that holds the private key:
   ```bash
   scripts/tesla-telemetry.sh
   ```
   Each car Tesla took the configuration for is under `updated_vehicles`; a car under
   `skipped_vehicles` as `missing_key` needs step 4. A car takes it up the next time it wakes:
   ```bash
   scripts/tesla-telemetry.sh status
   ```
   says `synced: true` once it has, and lists anything the car reported going wrong (a
   certificate it didn't trust, a server it couldn't reach). The relay log then says
   `tesla: sarahs_car's stream connected` when it wakes and `streaming, 42 m from home`.

The configuration lapses after 350 days: run step 5 again before then, and after changing
`TESLA_TELEMETRY_FIELDS`.

## If Tesla refuses

- **401 on every call:** the refresh token lapsed (they last three months unused). Run `tesla-link` again.
- **403 mentioning a key:** some cars want the app's key paired even to read data. Open
  `https://tesla.com/_ak/<host>` on the phone with the Tesla app, and approve it on the car.
- **`tesla-register` fails on the domain:** the allowed origin in step 1 must be exactly `https://<host>`.
