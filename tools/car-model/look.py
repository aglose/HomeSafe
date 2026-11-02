# Renders a scene file from the cameras the reference photographs were taken from.
#
#   blender -b --python tools/car-model/look.py -- <scene.blend> <out dir> <car> [--views a,b] [--size 1200] [--samples 96]
#
# The cameras are tuned by hand against the photographs in the comments, so a render can sit next
# to the photo (compare.py) and any difference is the model's, not the framing.
import json
import os
import sys

import bpy
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import stage  # noqa: E402

# Car: front towards -Y, its right side is -X, centred, ground at z=0.
# The photographs' own cameras come from fit_camera.py (cameras.json).
# name: (camera position, look-at, focal length mm on a 36 mm back, aspect w/h, light)
VIEWS = {
    "front": ((0.0, -8.0, 0.8), (0.0, 0.0, 0.8), 70, 1.3, "studio"),
    "rear": ((0.0, 8.0, 1.0), (0.0, 0.0, 0.8), 70, 1.3, "studio"),
    "side": ((-8.0, 0.0, 0.8), (0.0, 0.0, 0.75), 60, 1.6, "studio"),
    "top": ((0.0, 0.0, 9.0), (0.0, 0.001, 0.0), 60, 0.7, "studio"),
    "wheel": ((-3.0, -1.6, 0.42), (-0.9, -1.445, 0.36), 60, 1.0, "studio"),
    "wheel_rear": ((-3.0, 1.2, 0.42), (-0.9, 1.445, 0.36), 60, 1.0, "studio"),
    "headlight": ((-2.0, -3.7, 1.1), (-0.62, -1.9, 0.82), 62, 1.4, "studio"),
    "taillight": ((-1.8, 3.7, 1.25), (-0.5, 2.15, 1.02), 62, 1.4, "day"),
    "cabin": ((-2.1, -3.6, 1.45), (0.0, -0.6, 1.1), 50, 1.4, "day"),
}
LIGHT_FOR_PHOTO = {"red2": "day", "blue3": "afternoon", "red1": "day", "red3": "studio", "blue1": "afternoon"}


def photo_views():
    with open(os.path.join(HERE, "cameras.json")) as f:
        cams = json.load(f)
    return {
        name: (c["position"], c["target"], c["lens"], c["aspect"], LIGHT_FOR_PHOTO[name], c["roll"])
        for name, c in cams.items()
    }


def arg(flag, default):
    argv = sys.argv
    return argv[argv.index(flag) + 1] if flag in argv else default


def aim(camera, target, roll=0.0):
    direction = Vector(target) - Vector(camera.location)
    camera.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    camera.rotation_euler.rotate_axis("Z", roll)


def main():
    argv = sys.argv[sys.argv.index("--") + 1 :]
    scene_path, out_dir, car = argv[0], argv[1], argv[2]
    views = arg("--views", "red2,red1,blue1").split(",")
    size = int(arg("--size", "1200"))
    samples = int(arg("--samples", "96"))
    os.makedirs(out_dir, exist_ok=True)

    bpy.ops.wm.open_mainfile(filepath=scene_path)
    stage.prepare_cycles(samples)
    scene = bpy.context.scene
    camera = bpy.data.objects.new("look", bpy.data.cameras.new("look"))
    scene.collection.objects.link(camera)
    scene.camera = camera
    stage.ground()
    table = {**VIEWS, **photo_views()}

    for name in views:
        entry = table[name]
        position, target, lens, aspect, light = entry[:5]
        camera.location = position
        aim(camera, target, entry[5] if len(entry) > 5 else 0.0)
        camera.data.lens = lens
        camera.data.sensor_width = 36
        scene.render.resolution_x = size
        scene.render.resolution_y = round(size / aspect)
        stage.world(light)
        scene.render.filepath = os.path.join(out_dir, f"{car}_{name}.png")
        bpy.ops.render.render(write_still=True)


if __name__ == "__main__":
    main()
