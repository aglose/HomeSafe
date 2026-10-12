"""
The lifter the movement renders are made from, in pale stone under studio light: Blender Studio's
sculpted male where its file is at hand (sculpted.py), and otherwise a muscular male from
MakeHuman (through the MPFB add-on for Blender), on its game-engine rig.

Meant to be imported by the render scripts beside it, inside Blender:

    blender --background --python tools/movement-render/<script>.py -- <arguments>

Both bodies are CC0 (Blender Studio's Human Base Meshes; MakeHuman's base mesh and targets), so
what is rendered from them carries no licence of its own.
"""

import math

import bpy
from mathutils import Vector

import sculpted

# How the figure is built: a grown man, as muscular as the model goes, of ideal proportions.
MACROS = {
    "gender": 1.0,
    "age": 0.5,
    "muscle": 1.0,
    "weight": 0.5,
    "proportions": 0.85,
    "height": 0.5,
    "cupsize": 0.5,
    "firmness": 0.5,
    "race": {"asian": 0.33, "caucasian": 0.34, "african": 0.33},
}


# The model's own muscle shapes on top of that, each as far as it goes or a little past: a lifter's build.
DETAILS = {
    "torso-muscle-pectoral-incr": 1.2,
    "torso-muscle-dorsi-incr": 1.2,
    "torso-vshape-incr": 1.0,
    "measure-shoulder-dist-incr": 0.45,
    "measure-waist-circ-decr": 0.35,
    "measure-neck-circ-incr": 0.5,
    "measure-upperarm-circ-incr": 0.4,
    "measure-calf-circ-incr": 0.4,
    "stomach-tone-incr": 1.2,
    "pelvis-tone-incr": 0.8,
    "buttocks-volume-incr": 0.5,
    "l-upperarm-shoulder-muscle-incr": 1.2,
    "r-upperarm-shoulder-muscle-incr": 1.2,
    "l-upperarm-muscle-incr": 1.1,
    "r-upperarm-muscle-incr": 1.1,
    "l-lowerarm-muscle-incr": 0.9,
    "r-lowerarm-muscle-incr": 0.9,
    "l-upperleg-muscle-incr": 1.2,
    "r-upperleg-muscle-incr": 1.2,
    "l-lowerleg-muscle-incr": 1.0,
    "r-lowerleg-muscle-incr": 1.0,
}


def clear_scene():
    """An empty scene, whatever the startup file had in it."""
    for collection in (bpy.data.objects, bpy.data.meshes, bpy.data.armatures, bpy.data.lights, bpy.data.cameras, bpy.data.materials):
        for item in list(collection):
            collection.remove(item)


def build_figure():
    """The lifter, standing in the rig's rest pose: returns the body and its armature."""
    if sculpted.available():
        return sculpted.build(stone)
    return makehuman()


def is_sculpted(body):
    """Whether the body is an artist's sculpt, whose own relief is to be left as it is, and not one for muscles.sculpt to shape."""
    return "body" not in body.vertex_groups


def makehuman():
    """The MakeHuman lifter and its rig."""
    from bl_ext.blender_org.mpfb.services.humanservice import HumanService
    from bl_ext.blender_org.mpfb.services.targetservice import TargetService

    # Left where the model's own origin puts it, at the hips: the rig is fitted there, and both are stood on the floor together below.
    # With the model's joint markers kept (detailed_helpers): the skeleton is fitted to them, and without them it is
    # laid out for the model's default build and sits outside a broader body's arms.
    body = HumanService.create_human(mask_helpers=True, detailed_helpers=True, extra_vertex_groups=True, feet_on_ground=False, scale=0.1, macro_detail_dict=MACROS)
    for name, weight in DETAILS.items():
        TargetService.load_target(body, TargetService.target_full_path(name), weight=min(weight, 1.0), name=name)
        key = body.data.shape_keys.key_blocks[name]
        key.slider_max = max(weight, 1.0)
        key.value = weight
    # The shapes become the mesh itself before the skeleton is fitted: the fitting reads the mesh's own vertices,
    # and with the shapes still only laid over them it puts the bones where a slighter body's joints would be.
    TargetService.bake_targets(body)
    rig = HumanService.add_builtin_rig(body, "game_engine", import_weights=True)
    # And the mesh is made four times finer: the muscles are sculpted into it vertex by vertex (muscles.sculpt),
    # and the model's own vertices are too far apart to hold an abdominal's edge.
    finer = body.modifiers.new("Finer", "SUBSURF")
    finer.levels = 1
    with bpy.context.temp_override(object=body, active_object=body, selected_objects=[body]):
        bpy.ops.object.modifier_move_to_index(modifier="Finer", index=0)
        bpy.ops.object.modifier_apply(modifier="Finer")
    rig.location.z = -min(v.co.z for v in body.data.vertices if any(g.group == body.vertex_groups["body"].index for g in v.groups))
    bpy.context.view_layer.update()
    for polygon in body.data.polygons:
        polygon.use_smooth = True
    smooth = body.modifiers.new("Smooth", "SUBSURF")
    smooth.levels = 0
    smooth.render_levels = 1
    # Out of the working view: posing asks where the bones are many times a frame, and would otherwise re-skin the
    # whole mesh each time. A render still sees it.
    body.hide_viewport = True
    body.data.materials.clear()
    body.data.materials.append(stone())
    return body, rig


def stone():
    """Pale stone: a little rough, a little light under the surface, no colour to speak of."""
    material = bpy.data.materials.new("Stone")
    material.use_nodes = True
    shader = material.node_tree.nodes["Principled BSDF"]
    shader.inputs["Base Color"].default_value = (0.84, 0.86, 0.9, 1.0)
    shader.inputs["Roughness"].default_value = 0.5
    shader.inputs["Subsurface Weight"].default_value = 0.15
    shader.inputs["Subsurface Radius"].default_value = (0.02, 0.02, 0.03)
    return material


def light(name, kind, location, energy, color, size=1.0):
    data = bpy.data.lights.new(name, kind)
    data.energy = energy
    data.color = color
    if kind == "AREA":
        data.size = size
    lamp = bpy.data.objects.new(name, data)
    bpy.context.scene.collection.objects.link(lamp)
    lamp.location = location
    aim(lamp, Vector((0.0, 0.0, 1.0)))
    return lamp


def aim(thing, target):
    """Turns a light or camera to look at a point."""
    thing.rotation_euler = (target - Vector(thing.location)).to_track_quat("-Z", "Y").to_euler()


def studio():
    """A dark room: a soft key from the front and above, and a hard blue light behind each shoulder for the rim."""
    light("Key", "AREA", (-2.6, -2.3, 3.3), 125.0, (1.0, 0.97, 0.94), size=1.1)
    light("Fill", "AREA", (2.6, -2.8, 1.0), 12.0, (0.82, 0.88, 1.0), size=3.0)
    light("RimLeft", "AREA", (-2.2, 2.6, 2.2), 110.0, (0.25, 0.42, 1.0), size=1.2)
    light("RimRight", "AREA", (2.2, 2.6, 2.2), 110.0, (0.25, 0.42, 1.0), size=1.2)
    # And a soft one in front of the far side, so that a view from behind is not all shadow.
    light("Back", "AREA", (1.4, 3.4, 2.6), 60.0, (1.0, 0.97, 0.94), size=2.0)
    world = bpy.data.worlds.new("Room") if not bpy.data.worlds else bpy.data.worlds[0]
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.012, 0.014, 0.02, 1.0)
    bpy.context.scene.world = world


def camera(location, target, lens=58.0):
    data = bpy.data.cameras.new("Camera")
    data.lens = lens
    cam = bpy.data.objects.new("Camera", data)
    bpy.context.scene.collection.objects.link(cam)
    cam.location = location
    aim(cam, Vector(target))
    bpy.context.scene.camera = cam
    return cam


def render_settings(width, height, samples=48):
    """Cycles on the GPU where there is one: it renders without a window, which the other engine can't be relied on to."""
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    preferences = bpy.context.preferences.addons["cycles"].preferences
    try:
        preferences.compute_device_type = "METAL"
        preferences.get_devices()
        for device in preferences.devices:
            device.use = True
        scene.cycles.device = "GPU"
    except (TypeError, AttributeError):
        scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.render.resolution_x = width
    scene.render.resolution_y = height
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "Standard"


def render_to(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def degrees(*angles):
    return tuple(math.radians(a) for a in angles)
