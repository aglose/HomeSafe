"""
Looks for equipment sunk into the body: for every lift named (all of them when none is), through
the rep, how far each bar, dumbbell and handle reaches inside the skin, and where.

    blender --background --python tools/movement-render/clip.py -- [lift ...]

A hand's own grip is left out (the fingers are meant to lie on the bar). Prints one line per
piece that is in the skin by more than a millimetre at some frame, and `clear` for a lift with none.
"""

import os
import sys

import bpy
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import figure  # noqa: E402
import lifts  # noqa: E402
import muscles  # noqa: E402

names = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
figure.clear_scene()
body, rig = figure.build_figure()
skin = muscles.Skin(body, rig)
lifts.spots(skin)
lifts.SKIN = skin
materials = {"steel": lifts.metal("Steel", (0.2, 0.2, 0.2), 0.4, 1.0), "pad": lifts.metal("Pad", (0.0, 0.0, 0.0), 0.7, 0.0)}
for name in names or list(lifts.LIFTS):
    move, build, _ = lifts.LIFTS[name]
    things = build(materials)
    pieces = [piece for thing in things.values() for piece in [thing] + list(thing.children_recursive) if "rod" in piece]
    worst = {}
    for frame in range(0, lifts.KEY_FRAMES, 3):
        move(rig, frame / (lifts.KEY_FRAMES - 1), things)
        bpy.context.view_layer.update()
        probe = lifts.Probe(rig, arms=True)
        for piece in pieces:
            depth, where = probe.sunk(lifts.rod_points(rig, piece), named=True)
            if depth > worst.get(piece.name, (0.001,))[0]:
                worst[piece.name] = (depth, frame, where)
    for piece, (depth, frame, where) in sorted(worst.items()):
        print("CLIP %-18s %-12s %5.1f mm into %s at frame %d" % (name, piece, depth * 1000.0, where, frame))
    if not worst:
        print("CLIP %-18s clear" % name)
    for thing in things.values():
        for piece in [thing] + list(thing.children_recursive):
            bpy.data.objects.remove(piece)
