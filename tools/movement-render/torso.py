"""
The trunk close to, front and back, to judge the chest's and the back's muscles by: once as bare
sculpted stone and once with every head painted in a colour of its own.

    blender --background --python tools/movement-render/torso.py -- <output directory>
"""

import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import figure  # noqa: E402
import muscles  # noqa: E402
import pictures  # noqa: E402

out = sys.argv[sys.argv.index("--") + 1]
os.makedirs(out, exist_ok=True)

figure.clear_scene()
body, rig = figure.build_figure()
skin = muscles.Skin(body, rig)
head_fields = muscles.fields(skin)
if not figure.is_sculpted(body):
    muscles.sculpt(skin, head_fields)
muscles.paint_atlas(body, head_fields)
figure.studio()
figure.render_settings(900, 900, samples=64)
stone = body.data.materials[0]
paint = pictures.emission("Atlas", attribute="atlas")
scene = bpy.context.scene
high = rig.location.z
# What is looked at (the trunk unless a second argument names another part): its middle, the lens, and where from.
PARTS = {
    "torso": ((0.0, 0.0, high + 0.42), 135.0, (("front", (-0.5, -3.2, high + 0.7)), ("back", (0.5, 3.2, high + 0.7)))),
    "legs": ((0.0, 0.0, high - 0.36), 110.0, (("front", (-0.3, -3.6, high - 0.2)), ("back", (0.3, 3.6, high - 0.2)), ("side", (3.6, -0.3, high - 0.2)))),
    "arm": ((0.31, -0.02, high + 0.3), 150.0, (("front", (0.6, -3.4, high + 0.4)), ("back", (0.6, 3.4, high + 0.4)), ("side", (3.4, 0.3, high + 0.4)), ("inner", (-3.2, -1.2, high + 0.35)))),
}
part = sys.argv[sys.argv.index("--") + 2] if len(sys.argv) > sys.argv.index("--") + 2 else "torso"
middle, lens, views = PARTS[part]
for name, location in views:
    camera = figure.camera(location, middle, lens=lens)
    body.data.materials[0] = stone
    scene.cycles.samples = 64
    scene.cycles.use_denoising = True
    figure.render_to(os.path.join(out, part + "-body-" + name + ".png"))
    body.data.materials[0] = paint
    scene.cycles.samples = 12
    scene.cycles.use_denoising = False
    figure.render_to(os.path.join(out, part + "-paint-" + name + ".png"))
    lit = pictures.load(os.path.join(out, part + "-body-" + name + ".png"))
    pictures.save(lit[..., :3] * lit[..., 3:4] + pictures.GROUND * (1.0 - lit[..., 3:4]), os.path.join(out, part + "-stone-" + name + ".webp"))
    pictures.save(pictures.tinted(lit, pictures.load(os.path.join(out, part + "-paint-" + name + ".png"))), os.path.join(out, part + "-heads-" + name + ".webp"))
    bpy.data.objects.remove(camera)
