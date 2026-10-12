"""
A look at a body from another file under this studio's light: front, back and three-quarter.

    blender --background --disable-autoexec --python tools/movement-render/look_model.py -- <file.blend> <object name> <output directory> [how much to deepen the relief]
"""

import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import figure  # noqa: E402
import pictures  # noqa: E402

path, name, out = sys.argv[sys.argv.index("--") + 1:][:3]
os.makedirs(out, exist_ok=True)
figure.clear_scene()
with bpy.data.libraries.load(path, link=False) as (source, target):
    target.objects = [name]
body = target.objects[0]
bpy.context.scene.collection.objects.link(body)
body.location = (0.0, 0.0, 0.0)
# With a fourth argument, the sculpt's own relief is deepened by that much: every vertex moved further from where a
# smoothed copy of the body has it, which is what stands the muscles out without changing the build.
deepen = float(sys.argv[sys.argv.index("--") + 4]) if len(sys.argv) > sys.argv.index("--") + 4 else 0.0
if deepen > 0.0:
    import numpy as np
    for modifier in body.modifiers:
        if modifier.type == "MULTIRES":
            modifier.levels = 2
    bpy.context.view_layer.objects.active = body
    with bpy.context.temp_override(object=body, active_object=body, selected_objects=[body]):
        for modifier in list(body.modifiers):
            bpy.ops.object.modifier_apply(modifier=modifier.name)
    mesh = body.data
    count = len(mesh.vertices)
    co = np.empty(count * 3)
    mesh.vertices.foreach_get("co", co)
    co = co.reshape((count, 3))
    edges = np.empty(len(mesh.edges) * 2, dtype=np.int64)
    mesh.edges.foreach_get("vertices", edges)
    edges = edges.reshape((-1, 2))
    both = np.concatenate([edges, edges[:, ::-1]])
    degree = np.maximum(np.bincount(both[:, 0], minlength=count), 1)[:, None]
    smooth = co.copy()
    for _ in range(40):
        around = np.stack([np.bincount(both[:, 0], weights=smooth[both[:, 1], axis], minlength=count) for axis in range(3)], axis=1) / degree
        smooth = 0.5 * smooth + 0.5 * around
    # The extremities keep their own shape: only the relief between the neck, the wrists and the ankles is deepened.
    height = co[:, 2].max()
    keep = np.clip((co[:, 2] - 0.07 * height) / (0.05 * height), 0.0, 1.0) * np.clip((0.86 * height - co[:, 2]) / (0.03 * height), 0.0, 1.0)
    keep *= np.clip((0.36 - np.abs(co[:, 0] - co[:, 0].mean())) / 0.04, 0.0, 1.0)
    mesh.vertices.foreach_set("co", (co + (co - smooth) * deepen * keep[:, None]).ravel())
    mesh.update()
for polygon in body.data.polygons:
    polygon.use_smooth = True
body.data.materials.clear()
body.data.materials.append(figure.stone())
figure.studio()
figure.render_settings(720, 1080, samples=64)
height = body.dimensions.z
for view, location in (("front", (0.0, -4.4, height * 0.6)), ("back", (0.0, 4.4, height * 0.6)), ("quarter", (-2.4, -3.7, height * 0.65))):
    camera = figure.camera(location, (0.0, 0.0, height * 0.5), lens=74.0)
    figure.render_to(os.path.join(out, "model-" + view + ".png"))
    lit = pictures.load(os.path.join(out, "model-" + view + ".png"))
    pictures.save(lit[..., :3] * lit[..., 3:4] + pictures.GROUND * (1.0 - lit[..., 3:4]), os.path.join(out, "model-" + view + ".webp"))
    bpy.data.objects.remove(camera)
