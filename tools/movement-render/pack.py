"""
Packs rendered lifts into what the app ships, under shared/src/commonMain/composeResources/files/movement/<lift>/:

    blender --background --python tools/movement-render/pack.py -- <rendered lifts directory> <composeResources/files/movement>

- body.webp: the lit figure, 25 frames from the easy end of the rep to the hard one, five to a row, each 400 px, on a clear ground.
- muscle.webp: the same frames at 200 px, the working muscles only: red the prime movers, green the synergists, blue
  the stabilizers, each already filled out to its belly's edge and with a soft light spilling past it, so that the app
  only has to tint a channel and lay it over.
- badge.webp: a small still of the hard end of the rep, for a row in a list.
- anchors.json: for each frame, where each working head's middle is, as shares of the frame from its top left.
"""

import json
import os
import shutil
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pictures  # noqa: E402

source, target = sys.argv[sys.argv.index("--") + 1:][:2]
COLUMNS = 5
done = []
for name in sorted(os.listdir(source)):
    folder = os.path.join(source, name)
    if not os.path.isfile(os.path.join(folder, "body.webp")) or "-" not in name:
        continue
    lift, view = name.rsplit("-", 1)
    # One view of each lift goes to the app (a second view of the squat is only to look at).
    if lift in done or name in ("squat-back",):
        continue
    done.append(lift)
    out = os.path.join(target, lift)
    os.makedirs(out, exist_ok=True)
    shutil.copy(os.path.join(folder, "body.webp"), os.path.join(out, "body.webp"))
    shutil.copy(os.path.join(folder, "badge.webp"), os.path.join(out, "badge.webp"))
    sheet = pictures.load(os.path.join(folder, "muscle.webp"))[..., :3]
    tile = sheet.shape[0] // COLUMNS
    baked = np.zeros_like(sheet)
    for row in range(COLUMNS):
        for column in range(COLUMNS):
            frame = sheet[row * tile:(row + 1) * tile, column * tile:(column + 1) * tile]
            for channel in range(3):
                filled = np.clip(pictures.blur(frame[..., channel] ** 1.2, 0.8), 0.0, 1.0)
                spill = np.clip(pictures.blur(filled, tile * 0.022), 0.0, 1.0)
                baked[row * tile:(row + 1) * tile, column * tile:(column + 1) * tile, channel] = np.maximum(filled, spill * 0.55)
    pictures.save(baked, os.path.join(out, "muscle.webp"), quality=80)
    shutil.copy(os.path.join(folder, "anchors.json"), os.path.join(out, "anchors.json"))
print("PACKED", len(done), done)
