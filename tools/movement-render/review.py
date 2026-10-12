"""
Lays the start, middle and end of every rendered lift out to look over, four lifts to a picture.

    blender --background --python tools/movement-render/review.py -- <rendered lifts directory> <output directory>
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pictures  # noqa: E402

source, out = sys.argv[sys.argv.index("--") + 1:][:2]
os.makedirs(out, exist_ok=True)


def halved(picture):
    return (picture[0::2, 0::2] + picture[1::2, 0::2] + picture[0::2, 1::2] + picture[1::2, 1::2]) / 4.0


strips = []
for name in sorted(os.listdir(source)):
    folder = os.path.join(source, name)
    if not os.path.isfile(os.path.join(folder, "body-00.png")):
        continue
    for frame, effort in ((0, 0.0), (12, 0.5), (24, 1.0)):
        body = halved(pictures.load(os.path.join(folder, "body-%02d.png" % frame)))
        muscle = halved(pictures.load(os.path.join(folder, "muscle-%02d.png" % frame)))
        strips.append(pictures.glowing(body, muscle, effort))
    print("REVIEW", len(strips) // 3, name)
for index in range(0, len(strips), 12):
    pictures.save(pictures.sheet(strips[index:index + 12], 3), os.path.join(out, "review-%d.png" % (index // 12 + 1)))
