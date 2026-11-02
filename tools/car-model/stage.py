# The light and ground the car is judged on: Cycles on the GPU, a Poly Haven sky (CC0) and tarmac.
import os

import bpy

HDRI_DIR = os.environ.get("CAR_HDRI_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache", "hdri"))
LIGHTS = {
    "day": ("kloofendal_48d_partly_cloudy_puresky.hdr", 1.0, 2.2),
    "afternoon": ("qwantani_afternoon_puresky.hdr", 1.0, 0.6),
    "studio": ("studio_small_08.hdr", 1.1, 0.0),
    "garage": ("parking_garage.hdr", 1.2, 0.0),
}


def prepare_cycles(samples):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    prefs = bpy.context.preferences.addons["cycles"].preferences
    prefs.compute_device_type = "METAL"
    prefs.get_devices()
    for device in prefs.devices:
        device.use = True
    scene.cycles.device = "GPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 10
    scene.cycles.glossy_bounces = 6
    scene.cycles.transmission_bounces = 8
    scene.view_settings.view_transform = "Khronos PBR Neutral"
    scene.view_settings.look = "None"
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False


def world(light):
    name, strength, rotation = LIGHTS[light]
    w = bpy.data.worlds.new(light)
    bpy.context.scene.world = w
    w.use_nodes = True
    nodes, links = w.node_tree.nodes, w.node_tree.links
    nodes.clear()
    out = nodes.new("ShaderNodeOutputWorld")
    background = nodes.new("ShaderNodeBackground")
    environment = nodes.new("ShaderNodeTexEnvironment")
    coords = nodes.new("ShaderNodeTexCoord")
    mapping = nodes.new("ShaderNodeMapping")
    environment.image = bpy.data.images.load(os.path.join(HDRI_DIR, name), check_existing=True)
    mapping.inputs["Rotation"].default_value[2] = rotation
    background.inputs["Strength"].default_value = strength
    links.new(coords.outputs["Generated"], mapping.inputs["Vector"])
    links.new(mapping.outputs["Vector"], environment.inputs["Vector"])
    links.new(environment.outputs["Color"], background.inputs["Color"])
    links.new(background.outputs["Background"], out.inputs["Surface"])


def ground():
    if "ground" in bpy.data.objects:
        return
    mesh = bpy.data.meshes.new("ground")
    s = 60.0
    mesh.from_pydata([(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0)], [], [(0, 1, 2, 3)])
    obj = bpy.data.objects.new("ground", mesh)
    bpy.context.scene.collection.objects.link(obj)
    mat = bpy.data.materials.new("tarmac")
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    noise = nodes.new("ShaderNodeTexNoise")
    coords = nodes.new("ShaderNodeTexCoord")
    noise.inputs["Scale"].default_value = 300.0
    noise.inputs["Detail"].default_value = 3.0
    links.new(coords.outputs["Object"], noise.inputs["Vector"])
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.35
    ramp.color_ramp.elements[0].color = (0.10, 0.10, 0.10, 1)
    ramp.color_ramp.elements[1].position = 0.7
    ramp.color_ramp.elements[1].color = (0.20, 0.20, 0.20, 1)
    links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.85
    mesh.materials.append(mat)
