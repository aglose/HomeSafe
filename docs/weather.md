# Weather

PercySafe has a third app inside it, beside the cameras and [Finance](finance.md): **Weather**,
opened from the drawer behind the top bar's menu button. Its background is the sky over the place
you're looking at, drawn live by shaders; on it are the forecast, the radar and the notifications
that tell you rain is coming.

| Tab | What it shows |
|---|---|
| **Today** | The temperature and what the day is doing, written straight on the sky. Then cards: any government warning in force, the next two hours by the quarter hour (only when rain is near), the next day hour by hour, the best time to be outside, a still of the radar, and the details: UV, wind, sun, moon, humidity, pressure, precipitation, air quality, visibility |
| **Forecast** | The ten days as a strip to scroll along, a column a day: its date and sky, its high and low as figures on the week's two curves, what falls and how hard it blows. Then the list: each day's sky, chance of rain and range against the week's. A day opens in place into its own sky, its hours and its numbers; tapping one in the strip opens it in the list and sets it in a blob of clear jelly that slides, stretches and wobbles from day to day |
| **Radar** | A map the height of the screen with the radar loop on it: the past two hours, and over the US the next two as a model's forecast. Play, scrub, pinch, double-tap |

Swipe sideways on Today or Forecast to move between places. The top bar's name opens **Places**
(search, your cities under their own skies, a tap to add a popular one); the sliders open
**Settings** (units, notifications, a still sky).

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

## Notifications

On by default; each kind has its own switch in Weather's Settings, and they arrive on channels of
their own (**Weather**, **Severe weather**) so Android can silence them apart from the cameras'.

### What one looks like

A notification is more than its two lines. Each carries what it is about as a picture, the place
it is for, when what it tells of happens and when it stops being news, and the forecast behind
it; each platform shows as much of that as it has a place for.

| | Android | iOS |
|---|---|---|
| **Which kind, at a glance** | Its own icon in the status bar and its own accent colour: a cloud with rain in blue, with a bolt in violet, a thermometer in orange, a funnel in red. The same glyph on a coloured tile is the large icon | The tile is the banner's thumbnail |
| **The place** | Beside the app's name | The subtitle |
| **Opened out** | A card: the tile, the one thing to read large (when the rain starts, the day's high and low), the numbers as small marks (how much rain, the gusts, the UV index), and a chart: the next two hours of rain by the quarter hour, or the day by the hour with the present marked. A government warning opens into the agency's words instead, what to do first: an excerpt of up to 700 characters, cut at a word and marked "…" where the agency ran longer | The same card on a long press; the same excerpt of a warning's words as the body |
| **Its time** | When it happens: a shower reads "in 25 min" and counts down by itself | When it was posted |
| **Taken down** | By itself, when the shower has passed, the day is over, the warning has lapsed. A warning goes the moment it lapses; the others stay at least ten minutes from when they went up, so one posted late can still be read | At the next check after that (iOS can't give a notification an end). Every check sweeps, whatever the switches say; a sample is also swept ten minutes on while the app is running |
| **A tap** | Opens the weather app on what it was about: Today for rain on the way and today's outlook, the Forecast for tomorrow's, the warning in full for a warning still in force (Today if it has lapsed); on the place it was for | The same |
| **A button** | **Radar** | **Radar** (long press) |
| **Stacking** | In a group of their own, never bundled with the cameras' | In a thread of their own |
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
| **Rain (snow, storms) starting in about N min** | It is dry now, and the quarter-hour forecast has it starting within 75 minutes: two wet quarter-hours in a row, or one of 1 mm/h or more. One per shower: nothing more for three hours |
| **Rain today** | Once, between 6 and 11 in the morning, when the rest of the day has a spell of at least a millimetre (or a centimetre of snow): when, and about how much |
| **Rain tomorrow** | Once, between 6 and 10 in the evening, the same for tomorrow, with its high |
| Added to those two with *Wind, heat, cold, sun and air* | Gusts of 40 mph or more, feeling like 100 °F or hotter, feeling like 0 °F or colder, an air quality index of 151 or more, a first freeze, a high 8 °C (14 °F) or more above or below today's. Any of these is also reason enough for the notification by itself. A UV index of 8 or more is mentioned when there is one, but never sends one alone: in summer that would be every clear morning |
| **A government warning** | Severe or extreme ones, whatever the hour, once each: an update to a warning already announced is the same warning (told apart by its VTEC event number, not its message id) |

Nothing but warnings is sent between 10 PM and 6 AM. What has been sent is remembered for five
days by what it was about (this warning, this morning, this shower), which is what stops repeats.

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
watches for rain too (`relay.py`, "rain soon"), and pushes **Rain starting** itself. The phone's own
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
  with **Home** as its subtitle, in the weather's stack with the **Radar** button, and a tap that
  lands on Today as any weather notification's does. `content-available` also wakes the app, if iOS
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
- `relay/test_relay.py` (stdlib only): the relay's reading of a forecast against the same cases as
  the planner's, who is told, when the forecast is asked for, and one shower being said once
  from either end. `WeatherAlertCheckTest` has the other side of that, and reads the same payload.
