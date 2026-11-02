# The cars in 3D

The household's two Teslas as real 3D models, drawn live: the 2023 Model Y Performance in Deep Blue
(Überturbines, red calipers, carbon lip) and the 2022 Model Y Long Range in Red Multi-Coat (black
Induction wheels), both with black cabins. The Cars page shows each one on the map, turned the way it faces, and a tap on
its picture opens it full-page to turn and zoom.

`:car3d` (`features/car3d`) is the app's half: a small renderer of its own over OpenGL. The rest is
in `tools/car-model`, which makes the files it draws.

## Where the model comes from

The body starts from a mesh and is rebuilt from there:

- **Base mesh:** ["2021 Tesla Model Y"](https://sketchfab.com/3d-models/2021-tesla-model-y-59e2ead369984b1a85c800ff6cf6789d)
  by tonielpro520, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The credit has to stay
  with it: the viewer's page shows it and links there. `pack.sh` fetches it from a public copy, so
  nobody needs a Sketchfab account. It is a community upload, so whether its author modelled it from
  scratch can't be checked from here. Tesla's names and the T badge are theirs, and it implies no
  endorsement.
- **Ours:** real dimensions, every material, the windows and lenses split into separate parts, the
  paint's surface normals, the wheels, tyres, discs and calipers (`wheels.py`, from numbers), the
  lettering, badges, parking sensors and carbon lip (`details.py`), ambient occlusion, the ground
  shadow, the levels of detail and the studio it is lit by.
- **The paid CGTrader model** the work was compared with isn't used: its licence doesn't allow the
  file in a public repository.

## Making the files

```bash
tools/car-model/pack.sh
```

That needs Blender and a Python with `numpy` and `pillow` (`PYTHON=…` names one). It writes
`shared/src/commonMain/composeResources/files/car/` (the phones' meshes, lighting, posters and map frames)
and `features/car3d/src/desktopMain/resources/car/` (the Mac's), about 4 MB and 12 MB.

| Step | File | What it does |
| --- | --- | --- |
| Build | `build.py`, `materials.py`, `wheels.py`, `details.py` | One `.blend` per car: Tesla's dimensions, materials by part, fitted paint, its own wheels and badges. Both cars draw the blue one's body |
| Export | `export.py` | Bakes occlusion into the vertices, makes the tier's level of detail, writes `.car` files |
| Studio | `envmap.py` | Authors the studio, prefilters its reflection cube and works out the diffuse light: `.env` files |
| Pictures | `CarPictureDump`, `pack_pictures.py` | Each car's poster and its 72 map frames, from the renderer itself |

| | The Mac (`high`) | The phones (`mobile`) |
| --- | --- | --- |
| Triangles | about 700,000 | about 220,000 |
| Reflection cube | 256 a side | 128 a side |
| Antialiasing | 4× multisampling at twice the pixels | 4× multisampling |
| Longest edge shown | 2800 px | 1800 px |

A car's paint shows every facet in its reflections: the base mesh's uneven triangles turned a sharp
line in the sky into a zigzag. So `refine_surfaces` in `build.py` cuts the paint finer and gives every
point the normal of a quadratic surface fitted to its panel, evened out over its neighbours. It
never moves the points (that dents the creases), and folds keep their own normals. `even_out_panels`
then averages them over a longer reach, longest on nearly flat panels like the tailgate, as those
magnify any error, and gives way to the surface at character lines. The phones' body
keeps these normals through decimation (`DATA_TRANSFER` in `export.py`). Their wheels aren't decimated
but built again from the same numbers with fewer steps.

The headlamps are the base's reflectors in a dark silver housing, not a black one, with a daytime
light's thin line along the lens's lower edge and a projector behind it (`head_lamps` in
`details.py`). The rear valance is a matte moulding, not the base's piano black.

The wheels are measured off photographs: the Induction from a head-on picture of the part, with ten
spokes of nearly even width, a crest down one side, and wedge-shaped windows. Each wheel is the same
part on both sides, so `CarScene` turns the right-hand ones round rather than mirroring them.

## Comparing against the photographs

`fit_camera.py` recovers where each reference photograph was taken from, out of a few hand-matched
points (`cameras.json`). `look.py` renders from those cameras in Cycles, and `compare.py` puts the
render beside the photo and over it, so any difference left is the model's.

The app's own renderer can do the same, without Blender:

```bash
CAR_DUMP_DIR=/tmp/cars CAR_PACK_DIR=<work dir>/pack CAR_TIER=high ./gradlew :car3d:desktopTest --tests '*CarRenderDump*'
```

The photographs themselves aren't in the repository.

## How it draws

`CarScene` is the same code on every platform, over the small `Gl` interface: GL ES 3 on Android, GL
4.1 on the Mac, and Metal on iOS. The first two share `CarShaders`; iOS has the same shaders in Metal's
language (`CAR_SHADERS_METAL`), and they have to be changed together. Vertices are 12 bytes, every
attribute on a 4-byte boundary, which Apple's GPUs require.

- **Android:** a `TextureView` with its own GL thread, so Compose can draw over and clip it.
- **iOS:** an `MTKView`, drawn on demand. Not OpenGL ES: Apple has deprecated it, and the simulator's
  software renderer crashes on it. Compose cuts a hole for a UIKit view, so the view clears to the page's
  colour itself (`CarBackdrop`).
- **Mac:** no window of its own. An offscreen CGL context (LWJGL) is drawn into and read back as a
  picture for Compose, because a heavyweight panel would sit over the whole UI. Measured in a real
  window, the newest draw can stay off the screen until later ones push it out, so it keeps drawing
  for a moment after each settled frame (`SETTLE_REDRAW_MILLIS`). The cause wasn't found. Other
  desktops show the poster.

A car that has settled costs nothing: each surface draws when the camera changes and then stops.

Windows are drawn twice, with no sorting of triangles: faces seen from inside first, so a far pane always shows through, then those seen from outside, nearest pane first and writing depth, so the edge of a further pane can't paint over a near pane's reflection.

Besides dragging, the viewer takes the arrow keys and plus and minus, and offers a screen reader the same moves as actions (`CarOrbitState.onKey`, `customActions` in `CarViewer`).

The shading is image-based. Paint is a coloured metal base with flakes under a clear coat, each with
its own reflection (softer on phones, whose cube is smaller); glass is a reflection over a tint, on a pane's outer face only, as its inner face would mirror the dark cabin; everything is darkened by the baked occlusion, the coat only under a real overhang, as the bake's small variations would blotch its reflections.
The tone curve is Khronos PBR Neutral, the same one the Cycles renders use. The lighting turns with
the viewer (`uSkyTurn`), as if the car were on a turntable under fixed lights, so its near side is
never in shadow. It is an authored dark studio (`studio` in `envmap.py`), to go with the dark page:
neutral, as a blue sky turned the lamps blue and the bonnet purple with nothing around to explain it.
A clear coat returns about 4% of what it faces, so small, very bright lights draw the coat's lines
and broad dim ones light the paint under it. Straight up stays dim so the windscreen shows the cabin.

Unreal Engine with Lumen was looked at for this in November 2026 and turned down. Epic documents Lumen
as not working on iOS and only experimental on high-end Android with the desktop renderer, so the
phones would keep these renderers anyway, and there is no documented way to embed it in a Compose
window. What had looked wrong was the lighting and the base mesh's surfaces, which it wouldn't change.

## On the map

A map can have several cars on it and shouldn't hold a GL context for each, so `CarMapMarker` draws a
frame from a sheet of 72 headings, drawn ahead of time by the same renderer. It takes the heading
Tesla reports (`CarFix.headingDegrees`), on the north-up maps the app has. Which model a car is comes
from its name (`carModelFor`), as the relay doesn't say.
