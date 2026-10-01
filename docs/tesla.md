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
  up to half an hour late. `TESLA_CHECK_SECONDS` sets the interval (default 1800).

"Near" is within the home radius (Settings → Home) plus 50 m; "away" is more than 250 m past it.

## Cost

Personal accounts get a $10 monthly credit, and a location request costs $0.002 (500 per $1). The
relay asks at most once a minute per car when the camera saw something, plus once per car every half
hour, and never more than `TESLA_CALLS_PER_DAY` (200) times a day. For two cars that's about 100
half-hourly calls and a few dozen more a day: roughly $6–7 a month, inside the $10 credit.

## Setup (once)

Everything below happens on the Frigate box unless it says otherwise. `<host>` is the box's Funnel
name, `debian-surveillance.tail4c441a.ts.net`.

1. **Developer app.** At [developer.tesla.com](https://developer.tesla.com), signed in with a Tesla
   account that has MFA on, create an application:
   - Allowed origin: `https://<host>`
   - Redirect URI: `https://<host>/tesla/callback`
   - Scopes: *Vehicle Information* and *Vehicle Location* (profile/openid are added automatically).
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

## If Tesla refuses

- **401 on every call:** the refresh token lapsed (they last three months unused). Run `tesla-link` again.
- **403 mentioning a key:** some cars want the app's key paired even to read data. Open
  `https://tesla.com/_ak/<host>` on the phone with the Tesla app, and approve it on the car.
- **`tesla-register` fails on the domain:** the allowed origin in step 1 must be exactly `https://<host>`.
