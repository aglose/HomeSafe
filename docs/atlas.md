# Atlas

PercySafe has a fifth app inside it, beside the cameras, [Finance](finance.md),
[Weather](weather.md) and [Fitness](fitness.md): **Atlas**, opened from the drawer behind the top
bar's menu button. It is everywhere its owner has been, from their Google Maps Timeline: the whole
of it glowing on a map, any day replayed, every place and trip, and what the patterns in it say.

| Tab | What it shows |
|---|---|
| **Map** | The map at night, lit wherever a path has gone: dim where it went once, amber along the usual roads, white where every day runs. The places most time was spent at, flights as arcs. The years along the foot show the footprint up to any one of them, and the play button grows it year on year |
| **Days** | Every day on record as one square, a year to a block, lit by distance, places, or how far from home. This date in other years above them. A square opens its day |
| **Places** | Every place, by time spent, visits, how lately, or how long it has been. Search by name, town or address |
| **Trips** | How many countries, states and towns the record reaches; every stretch away from home, newest first; and the places that have been home |
| **Patterns** | What the record shows: sentences about it, the usual week hour by hour, the commute, how the miles were covered, each year against the others, and the records |

Pages pushed over the tabs: a **day** (its route on a map, drawn in the order it was driven when
played; a midnight-to-midnight ribbon to drag along; everything that happened, in order), a
**place** (when in the week it is gone to, visits by year, the latest visits), a **trip** (its
map, its stops, its days), and **Your history** (where the data comes from and how that is going).

## Where the data comes from

Google keeps Timeline on the phone now, not on its servers. The cloud backup Maps offers is an
encrypted copy only Maps can restore, with no API and nothing in Drive to read. The one way out is
the phone's own export:

> Settings → Location → Location services → Timeline → **Export Timeline data**

which writes one `Timeline.json` holding every visit and trip the phone has. If the phone doesn't
have the old years (a new phone, or a reset), import the backup in Maps first: profile → Your
Timeline → the cloud icon → the backup → Import. Auto-delete, if it was ever on, has already
removed what it removed.

From there:

1. The export is saved into a **Drive folder** shared with the relay's Google service account.
2. The **relay** on the box looks in that folder every hour (`relay/relay.py`, "timeline"), reads
   a new export into `data/timeline.db`, and asks Google's Places API what each place id is called.
3. The **app** asks the relay which years it has and fetches the ones it doesn't hold, into its
   own database. After that it needs no server: everything on screen is worked out on the device.

Google has no scheduled export, so step 1 is by hand, when a fresher history is wanted. Everything
after it is automatic.

### What the relay reads

Three shapes of file, loose or inside a Takeout zip: the phone's export (Android's
`semanticSegments`, the iPhone's bare array) and the two older Takeout ones (the monthly
`timelineObjects` files, and the raw `Records.json`, from which stays are found by where the fixes
stop moving). Whatever the shape, it becomes visits, moves and paths. A Takeout zip holds the same
years twice over, worked out and raw; the raw fixes are only gone to when it has nothing else.

- A file is read a piece at a time (`json_items`), so a 250 MB export costs under 200 MB of
  memory and about twenty seconds.
- **A newer export replaces an older one a day at a time.** Every day the new file mentions is
  rewritten from it; a day it doesn't mention is left alone. A phone that only remembers the last
  three months adds to the history rather than replacing it.
- A file that turns out to be cut short changes nothing.
- Takeout's files write times in UTC with no word of where the clock was; those take the
  household's time zone, which is right at home and an hour or more out on a trip elsewhere. The
  phone's export writes the offset, and is right everywhere.

### Place names

The export names no places, only Google's ids. Each is asked of **Places API (New)** once, the
places most time was spent at first, for its name, address, kind, town, state and country. A name
is the "Pro" price, free up to a monthly allowance; the relay stops at `TIMELINE_PLACES_MONTHLY`
(4,000) a month and carries on the next. An id Google has retired is marked gone and not asked
again. A stay Google matched to no place at all goes by the town of the nearest place that has
one ("Near Fairview").

### Who may see it

It is one person's whereabouts, so it is narrower than a Frigate sign-in: a Frigate **admin**, or,
once `TIMELINE_USERS` lists usernames, **only those**.

The app's copy belongs to the server it came from (by the address signed in to, whichever route
is in use) and the account the relay answered. Another server's, another account's, or one the
relay refuses is cleared before any of it is shown. And while signed in to a server that
answers, nothing the device holds is shown until the relay has answered *this* sign-in: the
phone's last user's whereabouts are not for whoever signs in after them. So the drawer's card
asks the relay one small question when it comes on screen.

With the server out of reach the app opens on its copy **behind a fingerprint**, exactly as
Finance does and by the same lock (`FinanceAccess`): there is no server to ask who this is.

## Setting it up

Once, on the box and in Google Cloud. `GET /timeline` (and the app's *Your history* page) says
which step is missing.

1. In the Cloud project the relay's key belongs to (the one that reads the budget sheet), switch
   on the **Google Drive API** and **Places API (New)**.
2. Make an API key for Places (APIs & Services → Credentials), restricted to Places API (New).
3. Make a folder in Drive, say *Timeline*, and share it with the key's `client_email` as a
   **Viewer**. The app shows that address until it is done.
4. On the box, in `relay/timeline.env` (gitignored; the repo is public):

   ```
   TIMELINE_DRIVE_FOLDER=<the folder's id, the last part of its URL>
   TIMELINE_PLACES_KEY=<the Places key>
   # Optional: only these Frigate usernames may read it. Without it, admins may.
   TIMELINE_USERS=andrew
   ```

5. Deploy the relay as usual (`relay.py` and `docker-compose.yml` to the box, then
   `docker compose up -d --build`).
6. On the phone, export Timeline (above) and save the file into the folder.

To load a file without Drive, copy it to `relay/data/` on the box and run
`docker exec homesafe-relay python relay.py timeline-import /data/Timeline.json`;
`timeline-pull` looks in the folder and names some places right now.

## What the app works out

All of it on the device, from the visits and moves (`atlas/domain/AtlasAnalysis.kt`), in about a
second for a decade:

- **Home is where the nights are.** For each month, the place most of the hours between midnight
  and six were spent; a run of months at one place is an *era*. A month away doesn't break one.
  Work is where an era's weekday working hours went, when a fifth of them went to one place.
- **A trip** is a run of stays more than 80 km from the home of the time, with a night in it or
  150 km to it.
- **The commute** is the weekday trips made from home to work in one go; a stop on the way makes
  it two trips and neither is the commute.
- **The usual week** is where each hour of the week was spent over the last 26 weeks, and its
  *regularity* is how much of that time was spent at the hour's usual place.
- **A year's time** is counted in the year it fell in: the night of New Year's Eve is part of
  both. A visit, a place and a trip's distance belong to the year they began in.
- **Range** is the radius of gyration: how far, on average, time was spent from the centre of
  where it was all spent. Whether life "turns round two places" is how much of the spread of the
  last year's local visits the two most visited account for.
- **Insights** are chosen from these, each a whole sentence: time at home, the commute's cost,
  the weekday that leaves late, the quarter-hour that makes the drive shorter, places that had a
  rhythm and have gone quiet, standing weekly dates, drives short enough to have walked.

## The map

Hand-rolled, like the radar's, and sharing its tile store and projection
(`atlas/ui/AtlasMap.kt`). Four layers:

1. **Night**, a shader (`ATLAS_NIGHT_SHADER`): dark with a graticule that moves with the map, so
   a pan with no tiles loaded still reads as moving over the Earth.
2. **OpenStreetMap's tiles**, turned to night by the radar's `MAP_NIGHT_SHADER`.
3. **The glow.** Far out it is drawn from `AtlasDensity`: the world cut into squares at eleven
   sizes, each square holding how many paths crossed it and in which years, a few thousand dots a
   frame however many million points the history has. Close in, where the finest squares would
   show as squares, from the paths in view themselves. Either way what is drawn is greys on
   black, and `ATLAS_HEAT_SHADER` makes light of it: a thirteen-tap bloom and a ramp from violet
   through ember and amber to white.
4. **What is picked out**: places, flights as arcs, and a day's or trip's own route with a point
   of light at its head.

The paths are packed as Google's encoded polylines on the relay and decoded once on the device
(`Polyline`, `Track`). Cards and the heads of screens are drawn on drifting contour lines
(`ATLAS_CONTOUR_SHADER`).

## Not yet

- Names you give a place yourself, and telling the app a place is wrong.
- Recording new days in HomeSafe itself, rather than waiting for the next export.
- Kilometres (`LocalAtlasMetric` is there; nothing sets it).
- Borders: states and countries are counted from the places' addresses, not drawn.
