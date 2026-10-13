"""
Lays pictures out side by side as one, to look several over at once.

    blender --background --python tools/movement-render/contact.py -- <out.png> <columns> <picture>...
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pictures  # noqa: E402

arguments = sys.argv[sys.argv.index("--") + 1:]
frames = [pictures.load(path)[..., :3] for path in arguments[2:] if os.path.exists(path)]
columns = int(arguments[1])
while len(frames) % columns:
    frames.append(np.zeros_like(frames[0]))
pictures.save(pictures.sheet(frames, columns), arguments[0])
