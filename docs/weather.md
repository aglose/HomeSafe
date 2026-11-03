# Weather

PercySafe has a third app inside it, beside the cameras and [Finance](finance.md): **Weather**,
opened from the drawer behind the top bar's menu button. Its background is the sky over the place
you're looking at, drawn live by shaders; on it are the forecast, the radar and the notifications
that tell you rain is coming.

| Tab | What it shows |
|---|---|
| **Today** | The temperature and what the day is doing, written straight on the sky. Then cards: any government warning in force, today and tomorrow as two tiles (only when either has weather worth planning around), the next wet day after that, the next two hours by the quarter hour (only when rain is near), the next day hour by hour, the best time to be outside, a still of the radar, and the details: UV, wind, sun, moon, humidity, pressure, precipitation, air quality, visibility |
| **Forecast** | The ten days as a strip to scroll along, a column a day: its date and sky, its high and low as figures on the week's two curves, what falls and how hard it blows, with a wet day lit from top to bottom. Then the list: each day's sky and range against the week's, and for a wet day a lane of its hours showing when it falls, with how much and how likely. A day opens in place into its own sky, its hours and its numbers; tapping one in the strip opens it in the list and sets it in a blob of clear jelly that slides, stretches and wobbles from day to day |
| **Radar** | A map the height of the screen with the radar loop on it: the past two hours, and over the US the next two as a model's forecast. Play, scrub, pinch, double-tap |
| **Ski** | Planning a day on the mountain: the resorts you follow compared for the day you pick, by their own snow reports, the weather, the drive and what tickets cost that day. See [The Ski tab](#the-ski-tab) |

Swipe sideways on Today or Forecast to move between places. The top bar's name opens **Places**
(search, your cities under their own skies, a tap to add a popular one); the sliders open
**Settings** (units, notifications, a still sky).

## Today and tomorrow

Two tiles under the temperature, each a word you can read at a glance: *Rain*, *Snow*, *Wintry mix*, *Storms*, or, when nothing falls, *Windy*, *Very hot*, *Bitterly cold*. A day with one of those is a solid tile of its colour, saying when, how soon and how much; a dry day beside it stays dark glass. `WeatherStory.outlooks` reads them from the forecast, with the notifications' own thresholds, so the screen and the shade agree: a spell of at least a millimetre (or a centimetre of snow), gusts of 40 mph, feeling like 100 °F or 0 °F. Today only counts what is still ahead.

With two dry days there are no tiles and the day's one sentence stays under the temperature. Below the tiles comes the next day hour by hour, unless rain is within two hours, when the quarter-hour chart goes between them.

## The sky

Four runtime shaders, stacked back to front (`weather/ui/shader/SkyShaders.kt`, driven by
`WeatherSky`). They are AGSL on Android and the same source as a Skia runtime effect on iOS, the
desktop and the web.

1. **Celestial**: the air's gradient, the sun (disc, glare, slow rays) and the light it pools
   along the horizon, the moon as a lit sphere in its true phase, three depths of stars and the
   band of the galaxy.
2. **Clouds**: fbm cloud on a ceiling that recedes toward a horizon below the screen, pushed
   about by a second noise so it billows, lit from where the sun is, with cirrus above when the
   sky is partly open, lightning flaring inside it, and fog. Drawn at half size and stretched:
   nothing in it has an edge, and it is most of the work.
3. **Precipitation**: rain as four sheets of streaks at four depths, snow as six sheets of flakes
   from sharp and far to large and out of focus, and the lightning bolt itself.
4. **Glass**: a render effect over the other three once it's properly raining. Drops bead on the
   glass and run down it, each one a small lens on the sky behind.

There is no shader per kind of weather. Each takes numbers that vary smoothly (cloud cover, how
hard it rains, how much fog), which `SkyScene.of` works out from the forecast, and `SkyPalette.of`
picks the colours from the sun's real height at that place and minute (`Astronomy`, computed on
the device). So one sky turns into another by easing numbers: dusk comes on, a shower blows in.

**Hold and slide along the hourly strip** and the whole sky turns to the hour under your finger:
where the sun will be, the cloud, the rain.

The moon tile's moon is the same lit sphere on its own (`MOON_SHADER`). The sky stops when the app
is in the background, and *Hold the sky still* in Settings shows one frame of the same scene
instead.

## The radar

Tiles, in three layers (`weather/ui/radar/RadarMap.kt`):

- **The map** is OpenStreetMap's standard tiles, which are drawn pale for paper. `MAP_NIGHT_SHADER`
  turns them over: lightness inverted, hue turned back, most of the colour taken out and the rest
  cooled, so there is a dark quiet map for the radar to sit on. Tiles are kept in the database
  (500 of them at most) so the map isn't downloaded again each time, which is also what
  OpenStreetMap asks of an app. With Google Maps picked in Settings the tiles are Google's
  instead, held in memory only (see [maps.md](maps.md)).
- **The radar** arrives as pictures in each provider's own colours, and one loop mixes three
  providers' tables. `RadarDecoder` looks every pixel up in the table it came from
  (`RadarPalettes`, the published tables) and rewrites the tile as *strength*: a grey for how hard
  it is raining. `RADAR_SHADER` then draws all of it one way: smoothed (the tiles are several times
  coarser than the screen), coloured on one ramp whose lightness never doubles back, translucent
  where the rain is light, with a faint line along the top of each band, a glow on storm cores and
  a grain drifting down through the echoes. Because the layer holds strengths, a frame fading into
  the next is the rain between them, not two palettes double-exposed.
- **A mark** where the place is.

Frames the radars saw are solid on the scrubber; a forecast frame is dashed there, labelled
*Forecast*, and drawn paler and hatched on the map, so nobody takes a model's guess for an
observation.

## Where the data comes from

Nothing here needs a key or an account, and none of it goes through the relay. The weather
services are asked directly, by a client of their own that never carries Frigate's cookies. The
relay does ask Open-Meteo one thing on its own account, about home, to push rain on the way (see
[below](#rain-soon-from-the-relay)).

| What | From | Notes |
|---|---|---|
| Forecast (now, 15-minute, hourly, ten days), air quality, city search | [Open-Meteo](https://open-meteo.com) | CC BY 4.0, free for non-commercial use. Asked for in metric; the app converts |
| Warnings and the name of where you are, in the US | [National Weather Service](https://www.weather.gov/documentation/services-web-api) | Public domain. Elsewhere there are no warnings and the place is "My location" |
| Radar over the contiguous US | NOAA MRMS (the past two hours, every ten minutes) and the HRRR model's forecast of it (the next two, every fifteen), as tiles from the [Iowa Environmental Mesonet](https://mesonet.agron.iastate.edu) | Public domain |
| Radar elsewhere | [RainViewer](https://www.rainviewer.com/api.html) | The past two hours, coarser (zoom 7), no forecast |
| Map | © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, or Google Maps when picked in Settings ([maps.md](maps.md)) | The app identifies itself and caches OpenStreetMap's tiles, per the tile usage policy |
| Sun, moon, twilight, golden hour | Worked out on the device | `Astronomy`: Meeus's low-precision formulas |

A forecast is kept for ten minutes in memory and its last copy stays in the database, so the app
opens on what it had and refreshes behind it. An old copy only shows what is still ahead: hours,
quarter-hours and days that are over are dropped, so a forecast from last week opens empty rather
than passing its last day off as today.

The services are told where to forecast for to the hundredth of a degree, about a kilometre:
their grids are no finer, and the phone's exact position stays on the phone.

Times are told by the place's own clock (its time zone comes with the forecast), so a sunrise or
the end of a warning on the far side of a daylight-saving change reads as the clock will then.
The days themselves are the service's, which cuts all ten with the offset in force today.

## Places

The first place is wherever the phone is, once the app may see its location (the same permission
the home geofence uses; weather only ever needs "while using the app"). Without a fix it is where
the phone last was, or failing that the household's home. Cities are added from Places, by search
or from the suggestions, and can be reordered and removed with *Edit*.

The last position is kept on the phone (so there is somewhere to forecast for when a fix can't be
had) and goes nowhere but, rounded as above, to Open-Meteo and the National Weather Service. The
permission prompts on iOS say so. That includes the relay: it forecasts for home, which it already
holds for the geofences, and is never told where a phone is.

## The Ski tab

Pick a day from the strip (it starts on the coming Saturday; each day's column is the most snow any
followed resort expects then) and the followed resorts are compared for it, or for that day and the
ones after it, on a trip of several days. The best-looking comes
first with the reasons; under it the rest, each with the last three days' snow, what the day will
have by then, the drive and the cost; then the regions being watched. A resort that won't be running
that day goes to the bottom, the first to open first. Pull to refresh, or on a wide window use the
refresh button in the title bar, as a mouse can't pull.

The day picked is kept, and the relay watches it for big snow as it does the coming weekend, until it
has gone by. See [Ski notifications](#ski-notifications).

The search icon looks through regions and passes, so a resort anywhere can be starred onto the tab
and a whole region watched. The sliders hold who's going, how many days, what a night and a mile cost, which passes they hold and
where drives start. A resort's own page has the report, the hours of the chosen day and the ten days ahead, the
road, the tickets, the cameras and the mountain's numbers.

`SkiPlanner` is the comparison, and pure: snow in centimetres over the three days up to the trip
(capped at 50), less for rain at the base, gusts that close lifts, hours on the road, chain controls
and ticket price. It's a sort order and nothing more; the numbers behind it are all on the card.

### Where its data comes from

| What | From | Notes |
|---|---|---|
| **Snow totals, depths, lifts and runs open** | **The resort's own website**, for the 41 in `ResortSite.ALL` | Each family of sites is read its own way (`ResortPages`): Vail's write theirs into the page as `FR.snowReportData`; Alterra's come from the feed their pages draw on (`v4.mtnfeed.com` for the token, fetched each time, then `mtnpowder.com`); POWDR's from a small API; the rest from their plain pages. Kept 15 minutes. The card says which site it's from |
| Snow on the ground, where a resort gives no depth | [SNOTEL](https://www.nrcs.usda.gov/resources/data-and-reports/snow-and-water-interactive-map), the US government's automatic snow stations | Until they open, Vail's sites say a base depth of 0 whatever has fallen (Kirkwood, Northstar and Heavenly all did on 2 November 2026, with up to 8" reported as new snow). So when a current report has no depth above 0, the card adds what the nearest station has measured: within 25 km and within 300 m of the height of the slopes, in the American West only, and named so it's never taken for the resort's word. Under 5 cm a sensor is only wandering around nothing, so isn't shown. The list of stations is kept a month, a reading an hour |
| **Ticket prices** | **The resort's own shop or rate table** | See below. Kept a day |
| Parking | The resort's own parking page, for the six followed by default | `ResortParking`, written down by hand in November 2026, so it dates: the least a car pays on an ordinary day, and a line on the lots and reservations. The household can set its own figure for any resort |
| Everything else about a resort, and any resort not in that list | [OnTheSnow](https://www.onthesnow.com) | Opening dates, cameras, elevations, coordinates, and for other resorts its report and listed prices, read from its pages (`OnTheSnowPages`, `NextFlightRows`), since it has no API. The card says when a figure is only a listing |
| Mountain forecast | [Open-Meteo](https://open-meteo.com) | Asked at the summit's and the base's own heights, from the blend and from ECMWF and GFS, whose spread is the range shown. Rain at the base and the freezing level come from here too. Kept an hour |
| Drive times | [OSRM](https://project-osrm.org) over OpenStreetMap, or [Google Routes](https://developers.google.com/maps/documentation/routes) with the household's Google key (see [key-vault.md](key-vault.md)) | OSRM knows no traffic and runs long over mountain roads, so its time is labelled a rough estimate. Google is used when the key is in the vault and the Routes API is enabled for it, and is what makes the 7 AM out and 4 PM back figures possible, by the resort's own clock on that date. A key refused falls back for the session; any other failure falls back for that drive only. Both are told the start to the hundredth of a degree |
| Chain controls and closed roads | [Caltrans](https://cwwp2.dot.ca.gov/documentation/cc/cc.htm), and [Nevada's road service](https://www.nvroads.com) around Tahoe | Caltrans publishes one file per district, and only those a route runs through are read. A checkpoint counts if the route passes within 1.5 km of it; a road marked closed, such as a pass shut for the winter, within 500 m, since its gate is on the road. Nevada's list names stretches of road with no coordinates, so `NevadaRoadsApi.STRETCHES` says where the ones that matter are (Mt. Rose Highway, SR-28, Kingsbury Grade, US-50, I-80, I-580, US-395), and what it reports goes through in its own words. A source that can't be read is never an all-clear: the last answer stays, and the card says it may be out of date. Elsewhere the card says it doesn't know. Caltrans's lane closure files aren't read: they run to megabytes a district, and their full closures are overnight road work |
| Warnings at the resort | National Weather Service | As for any place |

### Ticket prices

Most big mountains price by the date, so what a ticket costs depends on the day being planned for.
The cost on a card is, in order: nothing if a pass held covers it; what the household set for that
resort; **the resort's price for that date**; **its own rate table**; and only then OnTheSnow's
listing, which can be a season old and is labelled as listed.

| Sites | How | Gives |
|---|---|---|
| Vail's (Heavenly, Northstar, Kirkwood, and so on) | `SkiBrowser`, `ResortScripts.vail` | Online and window prices, adult and child, for each day asked about (the ten that can be chosen, and the first on sale), and its tickets of two to seven days from each |
| Mammoth and June | A plain request to their Inntopia calendar (`ResortSiteApi.calendar`), no browser | The lowest online price with any left, adult and child, and the length of ticket the trip wants. No window price. June says children ski free |
| Mt. Shasta | A plain request to its Entabeni shop | The adult's online price: a child's isn't available this way, and the cost says it left them out |
| The shop platform of Alterra's and POWDR's mountains (Palisades, Winter Park, Boreal) | `SkiBrowser`, `ResortScripts.shop` | The season's calendar at once, adult and child, online and base price, and where the shop sells them, tickets such as "2 out of 3 Days" (Winter Park; Palisades' calendar has no longer tickets) |
| Sugar Bowl, Sierra-at-Tahoe, China Peak, Donner Ski Ranch, Boreal's starting price | A plain page | Rates by kind of day. Sierra's own peak dates are read from its page; a table with peak rates and no dates would take a weekend as peak. Sugar Bowl's table has Sunday with its cheaper days. Donner Ski Ranch's says which season it's for, which the card repeats: as of November 2026 still 2025-26. Sugar Bowl's, China Peak's and Boreal's online figures are where prices *start*, so a cost from them reads "From" |

Those shops turn away anything but a page of their own: Vail's sit behind Akamai and a Queue-it
waiting room, and the others behind a web application firewall. So **`SkiBrowser`** opens the shop in
a real browser engine out of sight (a `WebView` on Android, a `WKWebView` on iOS and on the Mac),
under the engine's own identity, and runs the same requests the page itself makes. It never pretends
to be anything else: if a site says no, there's no price from it and the card says so. One page at a
time, and what comes back is kept a day. Anywhere it can't run, or can't finish, the resort's other
prices stand in, and the card links to the shop.

On a Mac the engine is a small program of its own, `desktopApp/macos/SkiBrowser.swift`, which the app
carries in its resources as `HomeSafeBrowser` and runs once a page (`SkiBrowser.desktop.kt`): the JVM
has no web engine, and this one is under 100 KB where bundling one is far more (JavaFX's WebKit is a
33 MB download before its other modules, and Chromium through JCEF is larger again, with helper apps
of its own to sign). Being a process apart, anything that goes wrong in it is only a missing price.

The page's window matters. WebKit stops a page's timers while its window can't be seen, and the
firewall's check in front of Palisades' and Winter Park's shops never finishes without them, though
Vail's shops don't mind. So the Mac's page is in a real window of a couple of points in the corner
of the screen, all but transparent, that the mouse goes through, as the iPhone's is. With the Mac's
screen off or locked it can't be seen either, those shops give nothing, and they're asked again later.

Homewood, Diamond Peak, Dodge Ridge, Bear Valley and Tahoe Donner hadn't published 2026-27 day prices as
of 1 November 2026 (they say "TBD", "TBA" or "mid-November"), so they show OnTheSnow's listing where it has
one and always link to their own page. They'll each want a reader once they post. Mt. Rose has
published its prices, but its shop gives them only inside a session of its own; reading them would mean
reusing that session's tokens, which isn't done, so it stays on the listing and the link.

### The whole trip

A resort's page adds up the trip (`SkiPlanner.trip`): tickets, lodging for its nights, parking and the
drive there and back. Tickets are one ticket for all the days where the shop sells that, otherwise each
day's own added up, with any day that has no price left out and said to be. Lodging and gas or charging
are what the household sets in the setup (15 cents a mile until it does), not looked up. Anything not
known is said to be, and the total then reads "At least".

### What it can't see

Bear Valley's site disagrees with itself (0" in one place, 227" in another) and Badger Pass publishes
no snow figures at all; Mt. Shasta's published sheet is empty until its season. Those show OnTheSnow's
listing. A site that changes shape falls back the same way, and says so.

A resort's site that fails for three days while others are being read has probably changed, and the tab
says so, naming it in the setup (`SkiSiteHealth`). Every site failing at once is the connection, and says
nothing.

Followed resorts are brought up to date in the background every few hours, along with the weather's own
background check (`SkiBackgroundRefresh`), which only runs on a device with weather notifications on:
reports, forecast and the drive, one resort at a time and spaced out, picking up where the last check ran out of time, as the resorts' bot protection doesn't take long to stop answering a household's address. Prices wait for the tab, as a shop's browser is a whole page and needs a window. The Mac has no background check, so its tab catches up when it opens.

A report older than a week counts as no report: out of season the numbers on a resort's page are
last spring's. A site that puts no date on its report goes by when it was last read, so one that has
stopped answering ages out too. An "open" from an old report doesn't count as running, and the card
says there's no recent report rather than open or closed. Its opening date is kept, and a date already past says nothing about a closed resort.

Everything is kept in `ski.db` (`SkiDatabase`, one table of JSON by key), including the plan. A
service that stops answering leaves its last answer on screen; every one of them shows its date.

Drives start from home if the household has one, otherwise from where the device is or last was. A
device that has known neither, like a Mac nobody has signed in on, goes by the first city added to the
weather, and says so on the drive and in the setup. A computer can't tell where it is, so its setup
doesn't offer "Where I am now".

## Notifications

On by default; each kind has its own switch in Weather's Settings, and they arrive on channels of
their own (**Weather**, **Rain on the way**, **Severe weather**) so Android can silence them apart from the cameras'.
The Ski tab has three of its own: see [Ski notifications](#ski-notifications).

### What one looks like

A notification is more than its two lines. Each carries what it is about as a picture, the place
it is for, when what it tells of happens and when it stops being news, and the forecast behind
it; each platform shows as much of that as it has a place for.

| | Android | iOS |
|---|---|---|
| **Which kind, at a glance** | Its own icon in the status bar and its own colour, the Today tab's for the same weather: a cloud with rain in blue, snow in white, a bolt in violet, wind, heat and cold in amber, a funnel in red. The same glyph, nearly black on a tile of that colour, is the large icon | The tile is the banner's thumbnail |
| **The place** | Beside the app's name | The subtitle |
| **Opened out** | A card: the tile, the one thing to read large (when the rain starts or falls, or the day's high and low when nothing does), the numbers as small marks (how long, how much, when it is heaviest, the gusts, the UV index), and a chart: the next two hours of rain by the quarter hour, or the day by the hour with the present marked. A government warning opens into the agency's words instead, what to do first: an excerpt of up to 700 characters, cut at a word and marked "…" where the agency ran longer | The same card on a long press; the same excerpt of a warning's words as the body |
| **Its time** | When it happens: a shower reads "in 25 min" and counts down by itself | When it was posted |
| **Taken down** | By itself, when the shower has passed, the day is over, the warning has lapsed. A warning goes the moment it lapses; the others stay at least ten minutes from when they went up, so one posted late can still be read | At the next check after that (iOS can't give a notification an end). Every check sweeps, whatever the switches say; a sample is also swept ten minutes on while the app is running |
| **A tap** | Opens the weather app on what it was about: Today for rain on the way and today's outlook, the Forecast for tomorrow's, the warning in full for a warning still in force (Today if it has lapsed); on the place it was for | The same |
| **A button** | **Radar**; on rain about to start, with half an hour or more to go, **Remind me at 2:45 PM** too. It takes the notification down, and fifteen minutes before the rain it comes back from a fresh forecast, if the rain is still coming | **Radar** and **Remind me 15 min before** (long press). iOS can't ask the forecast at that moment, so the reminder repeats what was said; turning rain off or muting calls it off |
| **Stacking** | In a group of their own, never bundled with the cameras' | In a thread of their own |
| **Coming up over the screen** | Rain about to start, on the Rain on the way channel | As any notification |
| **A warning** | Heads-up, on the Severe weather channel | Time-sensitive (breaks through a Focus once the app has that entitlement) |

There is one drawing of each icon. `NotificationGlyph` (`ui/notification/NotificationGlyphs.kt`)
holds them as paths on a 24-unit grid; Android's status-bar drawables are written from it (see
[notifications.md](notifications.md#icons)) and the tiles and the marks on the card are drawn from
it too, so the small icon and the large one are plainly the same thing. `WeatherNoticeLook` gives
each kind its colours. The card is painted by `WeatherNoticePainter`, in shared code and with no
composition (the check runs in a background job with no screen): Android takes the bitmap,
iOS a PNG of it. `scripts/render-ui-previews.sh` (Layoutlib; see docs/ui-previews.md) draws every icon, every
tile and a card of each sort.

Which picture a government warning wears is read from its VTEC phenomenon where it has one (`TO`
is a tornado whatever the message is titled), and otherwise from the agency's name for the event.

### When one is sent

`WeatherNoticePlanner` decides, from the forecast and the clock alone. The aim is the handful that
change what you do with your day, each said once:

| Notification | When |
|---|---|
| **Rain (snow, storms) at 3 PM** | Says for about how long and how much. It is dry now, and the quarter-hour forecast has it starting within 75 minutes: two wet quarter-hours in a row, or one of 1 mm/h or more. One per shower: nothing more for three hours |
| **Rain this afternoon** (this morning, this evening, tonight; today when it's all day or on and off) | Once, between 6 and 11 in the morning, when the rest of the day has a spell of at least a millimetre (or a centimetre of snow): "3 PM to 6 PM, around 0.22 in", the same words as the Today tab's tiles |
| **Rain tomorrow morning** (and so on) | Once, between 6 and 10 in the evening, the same for tomorrow, with its high |
| Added to those two with *Wind, heat, cold, sun and air* | Gusts of 40 mph or more, feeling like 100 °F or hotter, feeling like 0 °F or colder, an air quality index of 151 or more, a first freeze, a high 8 °C (14 °F) or more above or below today's. Any of these is also reason enough for the notification by itself. A UV index of 8 or more is mentioned when there is one, but never sends one alone: in summer that would be every clear morning |
| **A government warning** | Severe or extreme ones, whatever the hour, once each: an update to a warning already announced is the same warning (told apart by its VTEC event number, not its message id) |

Nothing but warnings is sent between 10 PM and 6 AM. What has been sent is remembered for five
days by what it was about (this warning, this morning, this shower), which is what stops repeats.

**Send a sample**, in Weather's Settings, sends rain on the way as it would arrive, whatever the
hour or the switches: the forecast's own shower when one is coming, and otherwise one made up from
now (half an hour off, for an hour and a quarter), with "Sample" leading its text and no reminder to set.

They are for where the phone is; with no position to go on at all, for the first city on the list.
Nothing is fetched or marked as sent while the OS has notifications switched off for the app, and
a notification the OS refuses isn't marked as sent either, so the next check tries it again. The
check runs:

- **Android**: every half hour or so as WorkManager work (`WeatherCheckWorker`), needing only a
  network; it survives the app being closed and the phone restarting. It takes a fix if the app
  has "all the time" location (as automatic presence asks for), and otherwise forecasts for where
  the phone last was when the app was open.
- **iOS**: as a background app refresh, when iOS grants one, and whenever the weather app is
  opened. iOS decides how often that is, from how the app is used.
- Whenever the weather app is opened, on any platform with notifications.

### Rain soon, from the relay

A shower is only worth announcing before it arrives, and neither of those schedules promises that:
Android's job drifts when the phone dozes, and iOS may not grant a refresh for hours. So the relay
watches for rain too (`relay.py`, "rain soon"), and pushes **Rain at 3 PM** itself. The phone's own
check stays, for when the phone is somewhere else and for when the relay is down. The rest of the
notifications are the phone's alone.

**Where.** Home, only for a phone the relay has at home: one of the two tracked phones
([away-mode.md](away-mode.md)), not away and not on its way out, with the app's switches on and a
push token. Home is what the relay already holds for the geofences; it goes to Open-Meteo rounded
to the hundredth of a degree, the same as a phone's position does. A phone's position never goes to
the relay, so this gives up rain where you are when you're out: away from home a phone has only
its own check, as it always did. The notification says **Home**, so it can't be read as being about
anywhere else.

A debug build isn't told, since nothing says where it is; nor is a phone with its cameras' switch
off, which registers no token (see [notifications.md](notifications.md)).

**How often.** Every 15 minutes, which is as fast as those quarter-hours change, and only from 6 AM
to 10 PM on the household's clock and while there is a phone to tell. One call a time, of nine
variables: at most 64 a day, against Open-Meteo's free 10,000. Told to slow down (HTTP 429), it waits an hour.

**Once per shower.** Each phone's row on the relay says what it was last told of, and when. Whoever
is about to say so takes that first (`POST /devices/{id}/rain-soon`, for the phone), and
it holds the other off for the three hours the planner would:

| | What stops the second one |
|---|---|
| The relay pushed first | The phone's check asks, is told no, and posts nothing, and marks nothing as said: a push still on its way can land, and if that push fails the relay lets go, so the next check asks again and says it. An Android phone that got the push first needn't ask: it went through the ledger |
| The phone's check said first | The relay finds it taken and doesn't push |
| The relay was out of reach when the phone asked | The phone goes ahead: better twice than never. An Android phone drops the later push against its ledger. On an iPhone the push is drawn by iOS, so it can be seen a second time, on the same notification. The same goes for an iPhone told no at one check, whose push iOS never woke the app for, that can't reach the relay at the next |

**What it looks like.** The push is a small forecast (`weather_soon`, read by `WeatherSoonPush`), not
a finished notification. `WeatherNoticePlanner` plans from it as from any other, so the words, the
card, the channel, the switches and the quiet hours are all the app's, and a shower that has
already begun by the time it lands says nothing.

- **Android** does that at once, from the high-priority data message; no network is needed.
- **iOS** draws the push's own text first: English, from the relay, a time rather than a countdown,
  with **Home** as its subtitle, in the weather's stack with the same buttons (**Radar**, and **Remind
  me 15 min before** with half an hour or more to go), and a tap that lands on Today as any weather
  notification's does. `content-available` also wakes the app, if iOS
  allows, and the same planning puts the app's card in its place without alerting again. iOS
  won't wake an app swiped away from the switcher, and throttles these as it likes: then the text
  is all there is, until the next check takes it down.

Neither service delivers one once its shower has started (`push_until`).

Rain can't be summoned, so to see it work, on the box:

```sh
docker exec homesafe-relay python relay.py rain-soon-test
```

That pushes a made-up shower to every phone that wants these, at home or not; the phone still plans
from it, so a real shower in the last three hours or the quiet hours will hold it back on Android.

### Ski notifications

Three, each with its own switch under **Skiing** in Weather's Settings, on to begin with like the
others, and under the weather's master switch. They're about the resorts on the Ski tab, so they
start only once that tab has been opened on the device: a phone that has never looked at it isn't
told about the six mountains it follows by default.

| Notification | When | Says it again |
|---|---|---|
| **Big snow by Saturday** | A foot or more (30 cm) at a followed resort's summit by the coming Saturday, or Sunday if it's only there by then, or by a day picked on the strip up to a week off. Added up as the tab's card does: the blend over that day and the two before. The least hopeful model has to reach half that (15 cm), since a wider gap isn't a forecast yet, and the resort has to be running that day, by a report from the last week or the dates it gave | Never for that weekend or day, whatever the models do afterwards |
| **Fresh snow at Sugar Bowl** | Six inches (15 cm) or more in a day, or overnight, on a resort's own site, from 7 AM so most have reported. The resort has to be running today, and a dated report no more than 36 hours old | A resort once a day, and never the same reading twice, which is all a site with no date can show of being out of date. One push every six hours at most, so a resort that reports late has its own |
| **Chains required on I-80** | A Caltrans control in force within 1.5 km of the road from home to a followed resort. California only, and only with a home set | Only if it asks for more (R-1 to R-2). Easing, and a storm's flicker between levels, say nothing. A road's slate is wiped when Caltrans has shown its controls gone for 12 hours; a file that can't be read doesn't count towards that |

Several resorts or roads at once come as one notification. Nothing goes out between 10 PM and 6 AM
on the phone's clock. Each carries the Ski tab as its tap, on the day it's about, or the resort's page
if it's about one resort, and has no **Radar** button. They're on the **Weather** channel, not coming up
over the screen. A big-snow notification comes down when its day ends, a fresh-snow one when today does,
and a road's after six hours. Chains wear a glyph of their own (`NotificationGlyph.CHAINS`).

**Where it runs.** On the relay (`relay.py`, "ski notices"), for the reason rain is: a storm is only
worth hearing of in time, and a phone can't be counted on to wake. Unlike rain there's no second
check on the phone. None of this depends on where the phone is, so the relay can tell a phone
wherever it is, and one place deciding means no claiming between two. So a device that doesn't hear
from the relay gets none: signed out, cameras' switch off (it registers no token, as with rain), or
a Mac, which has no way to receive a push and doesn't offer the switches.

**What the relay is told** (`PUT /devices/{id}/ski`, `SkiNoticeSync`): each phone's own list, since
the plan is per device, with each resort's slug, name, coordinates and summit height off its public
page, what's known of its season and how old that is (an "open" over a week old doesn't count), the switches, the units and the day picked. When any of that changes, and
at least daily from the background check, after the weather's own check and for at most 15 seconds. Turning
notifications off ends that check, so a relay out of reach just then is asked again for as long as the app
runs, at the next launch, and whenever a push arrives that shouldn't have. It is **not** told where the phone is or where its drives
start: `SkiPlan.origin` stays home, and a phone's route, which may start where the phone is, is
never sent. The relay asks OSRM for the road itself, from home, which it already holds for the
geofences, to the hundredth of a degree, and keeps it on the box for a fortnight. That can differ
from the Google route the tab shows.

**What it reads.** The resorts' own sites for the 32 in `ResortSite.ALL`, read a second time
in Python since the relay can't run the app's readers (`SKI_SITES`, `ski_read_*`): only the snow and
whether it's running, not the prices or the terrain page. A test holds the two lists of resorts
to each other, and `SkiSiteReadingTest` reads the same cut-down pages as `ResortPagesTest`, so **a site
that changes shape needs both readers changing**. A resort not on that list has no fresh-snow
notification: OnTheSnow's listing is only ever a listing.

**How often.** A tick every 15 minutes while any phone is awake and following something. Caltrans's
files of the districts the roads run through, on each. A resort's site hourly, not for one opening
more than a day off, one page and a couple of seconds apart: the relay shares the household's address with
its phones, Vail's Akamai stops answering an address that leans on it (it did so to a few requests in
quick succession on 1 November 2026), and it answers a challenge page with a 200, which reads as no report. Open-Meteo every three hours, one call
a resort. Told to slow down (HTTP 429), it waits an hour.

**What it looks like.** As with rain, the push (`ski_notice`, read by `SkiNoticePush`) is what was decided
and not its words. `SkiNoticePlanner` words it in the reader's language and units, and the switches and
the phone's own ledger have the last say; it's the relay's rules (`ski_big_snow`, `ski_fresh_snow`, `ski_chains_news`)
that decide. Android does that at once. iOS draws the push's own English first, then the app's words in its
place if iOS wakes the app.

To see one, on the box (`snow`, `fresh` or `chains`, about the first resort each phone follows):

```sh
docker exec homesafe-relay python relay.py ski-notice-test snow
```

## Tests

- `weather/domain`: the astronomy against known sunrises and lunations, the forecast's sentences,
  every notification rule and what each notification's picture is given to show, the map's
  projection and tiling.
- `WeatherNoticePainterTest` (JVM) paints every kind's badge and card straight to a bitmap, as the
  background job does; `WeatherNotificationBuildTest` (Android device) builds the notification on
  a real Android and reads its icon, picture, time and button back.
- `weather/data`: the services' real answer shapes through a mock engine, the radar loop's frames
  and fallbacks, the tile decoder against the published tables, caching.
- `WeatherShadersCompileTest` (JVM) compiles every shader with Skia; `WeatherShadersTest` (Android
  device) compiles them as AGSL on a real Android, where AGSL's own compiler gets the final say.
- `WeatherAppUiTest` drives the whole app from fixtures.
- `weather/ski`: the resorts' own sites as they really send them (`ResortPagesTest`), OnTheSnow's
  page stream, the models' fields and the chain files (`SkiPagesTest`), the comparison and the costs
  (`SkiPlannerTest`), caching, fallback and what's told to whom (`SkiRepositoryImplTest`), where
  drives start (`SkiStartingPointTest`), the tab's state (`SkiViewModelTest`), and the Mac's browser
  against a stand-in for its program (`MacSkiBrowserTest`, desktop only).
  What the relay's decisions read as (`SkiNoticePlannerTest`), a push landing and the phone telling the
  relay what it wants (`SkiNoticeCheckTest`).
- `relay/test_relay.py` (stdlib only): the relay's reading of a forecast against the same cases as
  the planner's, who is told, when the forecast is asked for, and one shower being said once
  from either end. `WeatherAlertCheckTest` has the other side of that, and reads the same payload.
  For skiing: each site's page, the forecast's totals, the three decisions, the push's shape, and who is
  told what and how often (`SkiSiteReadingTest`, `SkiForecastTest`, `SkiDecisionTest`, `SkiPushShapeTest`, `SkiTickTest`).
