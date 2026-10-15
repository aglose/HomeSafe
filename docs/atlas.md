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
- **Where the clock was.** Android's export writes every time on the clock the phone keeps
  *now*, and says where the clock on the wall was at the time in a field of its own
  (`startTimeTimezoneUtcOffsetMinutes`); that field is the one used, so years lived in another
  time zone keep their own mornings and nights. Takeout's files write UTC and say nothing of
  where the clock was; those take the household's time zone, which is right at home and an hour
  or more out on a trip elsewhere.
- A file Drive lists with nothing in it is one the phone is still sending, and is left for the
  next look.

### Place names

The export names no places, only Google's ids. Each is asked of **Places API (New)** once, the
places most time was spent at first, for its name, address, kind, town, state and country. A name
is the "Pro" price, free up to a monthly allowance; the relay stops at `TIMELINE_PLACES_MONTHLY`
(4,000) a month and carries on the next. An id Google has retired is marked gone and not asked
again. A stay Google matched to no place at all goes by the town of the nearest place that has
one ("Near Fairview").

### Refining what Google wrote

The export is Google's reading of radio fixes, one stay at a time. With the whole history in view,
some of it is plainly wrong: nights "at" the coffee shop under a flat, one building under two
ids, a year of nights scattered a kilometre round the flat the record later settles on (the early
years, when a phone asleep fixed itself by cell tower), points that wander off while the phone sat
on a table. So the relay keeps what the exports said as it was and works out a corrected copy beside
it (`refined_visits`, `refined_moves`, `refined_paths` in `timeline.db`), and that copy is what the
app is given. It is worked out again from the start, over the whole history, after every import
and every round of place names, with whatever more the history knows by then: a new export can
correct a year it never touched. A decade takes a few seconds.

Each step is a reason a person could check (`relay/relay.py`, "timeline: refining", has the
numbers). "The months around" a stay are half a year either side; "a night" is a stay through two
of the hours between 02:00 and 05:00 on the wall clock.

1. **One building, two ids.** Google names the flat one night and the building the next, or the
   house next door in a terrace. A stay at a residential place goes to whichever residential place
   within 25 m the months around spent most time at.
2. **Nobody sleeps at the coffee shop.** A night at a place of a kind nobody sleeps at (Google's
   type for it, once named) goes to the place within 150 m where the months around spent their
   nights.
3. **Unsure visits go where the time went.** A visit Google gave under even odds goes to a place
   within 120 m that the months around spent five times as long at, nights against nights.
4. **A thin record's nights go home.** A month with a night on record for fewer than half its days
   is a phone fixing itself coarsely, and its nights land on whatever is near. Such a night within
   1.5 km of the home of the nearest well-recorded months goes there, unless it landed at a hotel or
   at somewhere nights were spent in well-recorded months too (a friend's, a partner's). A place
   seen only in thin months whose nights went home was that home, days too.
5. **An unrecorded night near home was at home.** When nothing was recorded through a night, and
   the evening's last stay, the morning's first and anything between were within 3 km of the home
   of the time, a stay at home is put in, marked *inferred*.
6. **One stay, not several.** Two stays at one place with nothing between them straying more than
   150 m are one, and the jitter Google took for moves goes: within the hour anywhere, and up to two
   days at home in a well-recorded month. A gap at home is time at home; a gap at work isn't, and a
   gap in a thin record says nothing. A stay refining moved or put in is joined only to one moved
   for the same reason, so what the app is told of each stay stays true.
7. **Paths are smoothed by everywhere else the history has been.**
   - A point during a stay is put at the place (within 2 km of it). A path wholly inside a stay
     goes with it.
   - A point a path goes out to and straight back from is a bad fix and goes: faster than
     40 m/s anywhere; at any speed where fewer than two other trips have been within 60 m of it;
     and at any speed in the quarter-hour of arriving somewhere or leaving, when the path either
     side of it is at the place (the phone going indoors and out). A turn at a place visited is
     a visit, and stays.
   - A point within 40 m of where three or more other trips went the same way moves to the middle
     of them, twice over: one trip's fix wanders a street's width either side, and the middle of
     many is the street.
   - Two fixes 400 m or more apart are a straight line across whatever was between. When an
     earlier trip went from near the one to near the other the same way in about the same time,
     its points between are put in, so the line follows the road it took.

   Flights are left alone. The export writes a path's times only to the minute, so every speed
   allows each leg the minute it may have taken.
8. **A move starts where the stay before it was** and ends where the next one is, wherever
   refining has since put that stay.

A year's visits say which place Google had given one refining moved (`was`) and why (`why`:
`building`, `night`, `unsure`, `thin`, or `inferred` for a stay Google never wrote). The manifest's
`refined` says how many of each, and how many points were settled. A change to how refining works
bumps `TIMELINE_REFINE_VERSION`, and the relay refines again when it starts (as it also does when
it was stopped after refining and before the years were written: `refined` is marked last);
`docker exec homesafe-relay python relay.py timeline-refine` does it by hand.

Refining only ever moves a stay to somewhere the history itself vouches for. What it can't do is
know a place the record never saw: a flat no night was ever recorded at stays unknown. Telling the
app a place is wrong is still to come (below).

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
`timeline-pull` looks in the folder and names some places right now, and `timeline-reread` reads
the export again after a change to how it is read.

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
3. **The glow.** A picture of how often each spot was gone through, made off the main thread
   and kept in squares the way the base map is kept in tiles (`AtlasHeat`, `HeatRaster`). Far
   out a square is drawn from `AtlasDensity`: the world cut into squares at eleven sizes, each
   holding how many paths crossed it and in which years. Close in, where the finest of those
   would show as squares, from the paths themselves. The bloom is worked into the picture as it
   is made, and `ATLAS_HEAT_SHADER` only colours it: a ramp from violet through ember and amber
   to white, one look at each pixel.
4. **What is picked out**: places, flights as arcs, and a day's or trip's own route with a point
   of light at its head.

A frame of the map is therefore a few dozen pictures put down, however much history is on it.
A drag asks for the squares coming in at the edge; a pinch stretches the squares it has until
those for the new scale arrive, a few frames on (four scales to each doubling, so none is ever
stretched far); a glide prepares the squares for where it will land. A flick runs on, and the
zoom buttons and a double tap travel rather than jump (`AtlasMapState`). The map's own light
(the night's drift, the glow's shimmer) steps thirty times a second, not on every frame. How this
came about, and what it measured, is in `docs/perf-audit-2026-10-12-atlas.md`.

The paths are packed as Google's encoded polylines on the relay and decoded once on the device
(`Polyline`, `Track`). Cards and the heads of screens are drawn on drifting contour lines
(`ATLAS_CONTOUR_SHADER`), kept as a layer on Android so that a list scrolled over them copies
the picture instead of shading it again.

## Not yet

- Names you give a place yourself, and telling the app a place is wrong.
- Recording new days in HomeSafe itself, rather than waiting for the next export.
- Kilometres (`LocalAtlasMetric` is there; nothing sets it).
- Borders: states and countries are counted from the places' addresses, not drawn.
