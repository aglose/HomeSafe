# Weather

PercySafe has a third app inside it, beside the cameras and [Finance](finance.md): **Weather**,
opened from the drawer behind the top bar's menu button. Its background is the sky over the place
you're looking at, drawn live by shaders; on it are the forecast, the radar and the notifications
that tell you rain is coming.

| Tab | What it shows |
|---|---|
| **Today** | The temperature and what the day is doing, written straight on the sky. Then cards: any government warning in force, the next two hours by the quarter hour (only when rain is near), the next day hour by hour, the best time to be outside, a still of the radar, and the details: UV, wind, sun, moon, humidity, pressure, precipitation, air quality, visibility |
| **Forecast** | The ten days as one picture (highs and lows as two lines, rain as bars), then the list: each day's sky, chance of rain and range against the week's. A day opens in place into its own sky, its hours and its numbers |
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
  OpenStreetMap asks of an app.
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
services are asked directly, by a client of their own that never carries Frigate's cookies.

| What | From | Notes |
|---|---|---|
| Forecast (now, 15-minute, hourly, ten days), air quality, city search | [Open-Meteo](https://open-meteo.com) | CC BY 4.0, free for non-commercial use. Asked for in metric; the app converts |
| Warnings and the name of where you are, in the US | [National Weather Service](https://www.weather.gov/documentation/services-web-api) | Public domain. Elsewhere there are no warnings and the place is "My location" |
| Radar over the contiguous US | NOAA MRMS (the past two hours, every ten minutes) and the HRRR model's forecast of it (the next two, every fifteen), as tiles from the [Iowa Environmental Mesonet](https://mesonet.agron.iastate.edu) | Public domain |
| Radar elsewhere | [RainViewer](https://www.rainviewer.com/api.html) | The past two hours, coarser (zoom 7), no forecast |
| Map | © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors | The app identifies itself and caches tiles, per the tile usage policy |
| Sun, moon, twilight, golden hour | Worked out on the device | `Astronomy`: Meeus's low-precision formulas |

A forecast is kept for ten minutes in memory and its last copy stays in the database, so the app
opens on what it had and refreshes behind it.

## Places

The first place is wherever the phone is, once the app may see its location (the same permission
the home geofence uses; weather only ever needs "while using the app"). Without a fix it is where
the phone last was, or failing that the household's home. Cities are added from Places, by search
or from the suggestions, and can be reordered and removed with *Edit*.

## Notifications

On by default; each kind has its own switch in Weather's Settings, and they arrive on channels of
their own (**Weather**, **Severe weather**) so Android can silence them apart from the cameras'.
A tap opens the weather app.

`WeatherNoticePlanner` decides, from the forecast and the clock alone. The aim is the handful that
change what you do with your day, each said once:

| Notification | When |
|---|---|
| **Rain (snow, storms) starting in about N min** | It is dry now, and the quarter-hour forecast has it starting within 75 minutes: two wet quarter-hours in a row, or one of 1 mm/h or more. One per shower: nothing more for three hours |
| **Rain today** | Once, between 6 and 11 in the morning, when the rest of the day has a spell of at least a millimetre (or a centimetre of snow): when, and about how much |
| **Rain tomorrow** | Once, between 6 and 10 in the evening, the same for tomorrow, with its high |
| Added to those two with *Wind, heat, cold, sun and air* | Gusts of 40 mph or more, feeling like 100 °F or hotter, feeling like 0 °F or colder, a UV index of 8 or more, an air quality index of 151 or more, a first freeze, a high 14 °F or more above or below today's |
| **A government warning** | Severe or extreme ones, whatever the hour |

Nothing but warnings is sent between 10 PM and 6 AM. What has been sent is remembered for five
days by what it was about (this warning, this morning, this shower), which is what stops repeats.

They are for where the phone is. The check runs:

- **Android**: every half hour or so as WorkManager work (`WeatherCheckWorker`), needing only a
  network; it survives the app being closed and the phone restarting. It takes a fix if the app
  has "all the time" location (as automatic presence asks for), and otherwise forecasts for where
  the phone last was when the app was open.
- **iOS**: as a background app refresh, when iOS grants one, and whenever the weather app is
  opened. iOS decides how often that is, from how the app is used.
- Whenever the weather app is opened, on any platform with notifications.

## Tests

- `weather/domain`: the astronomy against known sunrises and lunations, the forecast's sentences,
  every notification rule, the map's projection and tiling.
- `weather/data`: the services' real answer shapes through a mock engine, the radar loop's frames
  and fallbacks, the tile decoder against the published tables, caching.
- `WeatherShadersCompileTest` (JVM) compiles every shader with Skia; `WeatherShadersTest` (Android
  device) compiles them as AGSL on a real Android, where AGSL's own compiler gets the final say.
- `WeatherAppUiTest` drives the whole app from fixtures.
