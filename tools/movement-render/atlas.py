"""
The figure standing, from the front, the back and one side: once as the bare sculpted stone, and
once with every muscle head painted on it in a colour of its own.

    blender --background --python tools/movement-render/atlas.py -- <output directory>

Also writes `atlas.json`: each head's colour, and where its middle is in each picture (as shares
of the picture's width and height), for a page to label them from.
"""

import json
import os
import sys

import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector

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
anchors = muscles.anchors(skin, head_fields)
figure.studio()
figure.render_settings(720, 1080, samples=64)
stone = body.data.materials[0]
paint = pictures.emission("Atlas", attribute="atlas")

height = max(vertex.co.z for vertex in body.data.vertices) + rig.location.z
scene = bpy.context.scene
placed = {}
for name, location in (("front", (0.0, -4.4, height * 0.6)), ("back", (0.0, 4.4, height * 0.6)), ("side", (4.4, 0.0, height * 0.6))):
    camera = figure.camera(location, (0.0, 0.0, height * 0.5), lens=74.0)
    pictures.swap_material(body, stone)
    scene.cycles.samples = 64
    scene.cycles.use_denoising = True
    figure.render_to(os.path.join(out, "body-" + name + ".png"))
    pictures.swap_material(body, paint)
    scene.cycles.samples = 12
    scene.cycles.use_denoising = False
    figure.render_to(os.path.join(out, "paint-" + name + ".png"))
    lit = pictures.load(os.path.join(out, "body-" + name + ".png"))
    pictures.save(lit[..., :3] * lit[..., 3:4] + pictures.GROUND * (1.0 - lit[..., 3:4]), os.path.join(out, "stone-" + name + ".webp"))
    pictures.save(pictures.tinted(lit, pictures.load(os.path.join(out, "paint-" + name + ".png"))), os.path.join(out, "heads-" + name + ".webp"))
    # Which heads face this camera: those whose middle is on the near side of the body.
    toward = (Vector(location) - Vector((0.0, 0.0, height * 0.5))).normalized()
    seen = []
    for anchor in anchors:
        at = rig.matrix_world @ Vector(anchor["rest"])
        from_middle = Vector((at.x, at.y, 0.0))
        if from_middle.length > 0.0 and from_middle.normalized().dot(Vector((toward.x, toward.y, 0.0)).normalized()) > 0.25:
            x, y, _ = world_to_camera_view(scene, camera, at)
            seen.append({"head": anchor["head"], "side": anchor["side"], "x": round(x, 4), "y": round(1.0 - y, 4)})
    placed[name] = seen
    bpy.data.objects.remove(camera)

with open(os.path.join(out, "atlas.json"), "w") as file:
    json.dump({"colors": {name: [round(c, 3) for c in muscles.head_color(name)] for name in muscles.HEADS}, "views": placed}, file)
