# The real materials of the car, as Cycles shaders. The in-app shaders are matched to these.
import bpy


def _principled(name):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = next(n for n in nodes if n.type == "BSDF_PRINCIPLED")
    return mat, nodes, mat.node_tree.links, bsdf


def simple(name, color, roughness=0.5, metallic=0.0, coat=0.0, coat_roughness=0.03, specular=0.5):
    mat, _, _, bsdf = _principled(name)
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Coat Weight"].default_value = coat
    bsdf.inputs["Coat Roughness"].default_value = coat_roughness
    bsdf.inputs["Specular IOR Level"].default_value = specular
    return mat


def paint(name, color, flake_color, flake_amount=0.25, roughness=0.34):
    """Metallic paint: a coloured metal base with flakes under a mirror clear coat."""
    mat, nodes, links, bsdf = _principled(name)
    bsdf.inputs["Metallic"].default_value = 0.55
    bsdf.inputs["Coat Weight"].default_value = 1.0
    bsdf.inputs["Coat Roughness"].default_value = 0.02
    bsdf.inputs["Coat IOR"].default_value = 1.5
    bsdf.inputs["Specular IOR Level"].default_value = 0.5

    coords = nodes.new("ShaderNodeTexCoord")
    flakes = nodes.new("ShaderNodeTexVoronoi")
    flakes.inputs["Scale"].default_value = 3500.0
    flakes.feature = "F1"
    flakes.distance = "EUCLIDEAN"
    links.new(coords.outputs["Object"], flakes.inputs["Vector"])

    # Each cell gets a random brightness: some flakes catch the light, most don't.
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.45
    ramp.color_ramp.elements[0].color = (*color, 1)
    ramp.color_ramp.elements[1].position = 0.95
    ramp.color_ramp.elements[1].color = (*flake_color, 1)
    separate = nodes.new("ShaderNodeSeparateColor")
    links.new(flakes.outputs["Color"], separate.inputs["Color"])
    links.new(separate.outputs["Red"], ramp.inputs["Fac"])

    mix = nodes.new("ShaderNodeMix")
    mix.data_type = "RGBA"
    mix.inputs["Factor"].default_value = flake_amount
    mix.inputs["A"].default_value = (*color, 1)
    links.new(ramp.outputs["Color"], mix.inputs["B"])
    links.new(mix.outputs["Result"], bsdf.inputs["Base Color"])

    # Flakes also scatter the base layer a little.
    rough_ramp = nodes.new("ShaderNodeMapRange")
    rough_ramp.inputs["To Min"].default_value = roughness - 0.1
    rough_ramp.inputs["To Max"].default_value = roughness + 0.1
    links.new(separate.outputs["Green"], rough_ramp.inputs["Value"])
    links.new(rough_ramp.outputs["Result"], bsdf.inputs["Roughness"])
    return mat


def glass(name, tint, transmission_roughness=0.0, ior=1.5):
    """Thin automotive glass: tinted transmission, no refraction offset (a single sheet)."""
    mat, nodes, links, bsdf = _principled(name)
    bsdf.inputs["Base Color"].default_value = (*tint, 1)
    bsdf.inputs["Roughness"].default_value = transmission_roughness
    bsdf.inputs["Transmission Weight"].default_value = 1.0
    bsdf.inputs["IOR"].default_value = ior
    bsdf.inputs["Specular IOR Level"].default_value = 0.5
    mat.use_transparent_shadow = True

    # Thin sheet: a light path sees the sheet's tint without bending.
    path = nodes.new("ShaderNodeLightPath")
    transparent = nodes.new("ShaderNodeBsdfTransparent")
    transparent.inputs["Color"].default_value = (*tint, 1)
    fresnel = nodes.new("ShaderNodeFresnel")
    fresnel.inputs["IOR"].default_value = ior
    glossy = nodes.new("ShaderNodeBsdfAnisotropic")
    glossy.inputs["Roughness"].default_value = 0.0
    glossy.inputs["Color"].default_value = (1, 1, 1, 1)
    mix = nodes.new("ShaderNodeMixShader")
    links.new(fresnel.outputs["Fac"], mix.inputs["Fac"])
    links.new(transparent.outputs["BSDF"], mix.inputs[1])
    links.new(glossy.outputs["BSDF"], mix.inputs[2])
    output = next(n for n in nodes if n.type == "OUTPUT_MATERIAL")
    links.new(mix.outputs["Shader"], output.inputs["Surface"])
    del path
    return mat
