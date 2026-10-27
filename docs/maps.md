# Maps: OpenStreetMap or Google

Every map in the app is drawn by the app itself from web-mercator tiles: the weather radar
(`weather/ui/radar/RadarMap.kt`), the Atlas (`atlas/ui/AtlasMap.kt`) and a parked car's spot
(`SpotMap`, opened from a parking notification: [parking.md](parking.md)). Settings, **Maps**,
picks whose tiles those are. Everything drawn on top (the radar, the Atlas's glow, the marks,
the night shader) is the same on either.

| | OpenStreetMap (the default) | Google Maps |
| --- | --- | --- |
| Tiles | `tile.openstreetmap.org` | Google's [Map Tiles API](https://developers.google.com/maps/documentation/tile), 2D roadmap, `scaleFactor2x` + `highDpi` (512 px tiles) |
| Key | none | the household's Google Maps Platform key, pasted into Settings and kept in the [key vault](key-vault.md) |
| Kept on the phone | in the database (500 tiles), as OpenStreetMap asks | in memory only: Google's terms forbid storing tiles |
| Credit | "Map © OpenStreetMap contributors" | "Google Maps · " and the copyright Google gives for the view in sight |
| Cost | free | billed to the key past the monthly free tile requests; a session costs nothing |

## The Google key

Make a key in the Google Cloud console for a project with billing, enable the **Map Tiles API**
on it, and restrict the key to that API. The key is the household's: it is kept like the
assistant's Anthropic key, in the key vault on the server, sealed with each member's Frigate login
([key-vault.md](key-vault.md)), and only ever in memory on a phone. Which provider is picked is no
secret and stays on the phone. The key is never in this repository, which is public.

`BaseMaps` (`shared/.../maps/`) turns the choice into tiles to fetch:

- With Google picked and a key, it asks `POST /v1/createSession` for a session (good for about two
  weeks), in the phone's language and region, and renews it an hour before it runs out.
  `MapTileStore` fetches `https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session=…&key=…`.
- When Google refuses (a wrong key, or the Map Tiles API not enabled) or can't be reached, the maps
  stay on OpenStreetMap and Settings says why. It asks again five minutes later, or as soon as the
  key or the choice changes.
- Settings asks for a session when it opens, so the Maps row can say whether the key works
  before any map is opened.
- Each map asks `GET /tile/v1/viewport` for the copyright of what it shows once the view has
  settled for 600 ms (`followCopyright`). The radar and Atlas map screens put it in their credit
  line; the smaller maps (the radar card, a day, a trip, a place, a parked car) wear it in a
  corner.
