"""
A look at the figure standing: front, back and three-quarter stills, and a list of its bones and
vertex groups, to work from.

    blender --background --python tools/movement-render/look.py -- <output directory>
"""

import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import figure  # noqa: E402

out = sys.argv[sys.argv.index("--") + 1]
os.makedirs(out, exist_ok=True)

figure.clear_scene()
body, rig = figure.build_figure()
figure.studio()
figure.render_settings(720, 960)

height = max((body.matrix_world @ v.co).z for v in body.data.vertices)
print("FIGURE height", round(height, 3), "rig at", tuple(rig.matrix_world.translation), "body at", tuple(body.matrix_world.translation), "lowest", min((body.matrix_world @ v.co).z for v in body.data.vertices))
print("FIGURE bones", [(bone.name, bone.parent.name if bone.parent else None, [round(c, 3) for c in bone.head_local], [round(c, 3) for c in bone.tail_local]) for bone in rig.data.bones if "_0" not in bone.name or "spine" in bone.name or "neck" in bone.name])
print("FIGURE groups", [group.name for group in body.vertex_groups])

middle = (0.0, 0.0, height * 0.52)
for name, location in (("front", (0.0, -3.7, height * 0.62)), ("back", (0.0, 3.7, height * 0.62)), ("quarter", (-2.0, -3.1, height * 0.7))):
    cam = figure.camera(location, middle)
    figure.render_to(os.path.join(out, "look-" + name + ".png"))
    bpy.data.objects.remove(cam)
