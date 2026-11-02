package com.meticulouscreations.homesafe.car3d

import androidx.compose.runtime.Immutable

/** A surface: the numbers [CarShaders.SURFACE] takes, in linear colour. */
@Immutable
internal data class Surface(
    val base: Rgb,
    val metal: Float = 0f,
    val roughness: Float = 0.5f,
    val coat: Float = 0f,
    val coatRoughness: Float = 0.03f,
    val flake: Rgb = Rgb(0f, 0f, 0f),
    val flakeAmount: Float = 0f,
    val emissive: Rgb = Rgb(0f, 0f, 0f),
    /** Below 1, a part the wheel arch would otherwise put in the dark still shows its shape. */
    val occlusionPower: Float = 1f,
)

@Immutable
internal data class Rgb(val r: Float, val g: Float, val b: Float)

private fun rgb(r: Double, g: Double, b: Double) = Rgb(r.toFloat(), g.toFloat(), b.toFloat())

private fun grey(v: Double) = rgb(v, v, v)

/** Which of the household's two cars. What differs between them is colour, wheels and height. */
enum class CarModel(internal val look: CarLook) {
    /** 2023 Model Y Performance in Deep Blue Metallic: Überturbines, red calipers, black cabin. */
    BLUE_PERFORMANCE(
        CarLook(
            wheels = "uberturbine",
            rearWheelsDiffer = true,
            rideHeight = -0.015f,
            spoiler = true,
            badgeUnderline = true,
            paint = Surface(
                base = rgb(0.0022, 0.0085, 0.072),
                metal = 0.45f,
                roughness = 0.30f,
                coat = 1f,
                coatRoughness = 0.03f,
                flake = rgb(0.022, 0.07, 0.36),
                flakeAmount = 0.15f,
            ),
            rim = Surface(base = rgb(0.085, 0.085, 0.09), metal = 0.5f, roughness = 0.42f, occlusionPower = 0.4f),
            hub = Surface(base = rgb(0.07, 0.07, 0.074), metal = 0.5f, roughness = 0.4f, occlusionPower = 0.4f),
            caliper = Surface(base = rgb(0.55, 0.012, 0.018), roughness = 0.3f, coat = 1f, occlusionPower = 0.35f),
            seat = Surface(base = grey(0.02), roughness = 0.55f, occlusionPower = 0.3f),
        ),
    ),

    /** 2022 Model Y Long Range in Red Multi-Coat: black Induction wheels, black cabin. */
    RED_LONG_RANGE(
        CarLook(
            wheels = "induction",
            rearWheelsDiffer = false,
            rideHeight = 0f,
            spoiler = false,
            badgeUnderline = false,
            paint = Surface(
                base = rgb(0.23, 0.0018, 0.0055),
                metal = 0.45f,
                roughness = 0.30f,
                coat = 1f,
                coatRoughness = 0.03f,
                flake = rgb(0.58, 0.022, 0.024),
                flakeAmount = 0.18f,
            ),
            rim = Surface(base = grey(0.022), metal = 0.2f, roughness = 0.38f, occlusionPower = 0.4f),
            hub = Surface(base = grey(0.018), metal = 0.5f, roughness = 0.4f, occlusionPower = 0.4f),
            caliper = Surface(base = grey(0.03), roughness = 0.4f),
            seat = Surface(base = grey(0.02), roughness = 0.55f, occlusionPower = 0.3f),
        ),
    ),
}

/** Which model a household car is. The relay only knows names: Andrew's is the blue one. */
fun carModelFor(id: String, name: String): CarModel =
    if ("andrew" in id.lowercase() || "andrew" in name.lowercase()) CarModel.BLUE_PERFORMANCE else CarModel.RED_LONG_RANGE

@Immutable
internal data class CarLook(
    val wheels: String,
    val rearWheelsDiffer: Boolean,
    val rideHeight: Float,
    val spoiler: Boolean,
    val badgeUnderline: Boolean,
    val paint: Surface,
    val rim: Surface,
    val hub: Surface,
    val caliper: Surface,
    val seat: Surface,
)

/** A window or lens: how much of each colour it lets through. */
internal val GLASS_TINTS: Map<String, Rgb> = mapOf(
    "windshield" to rgb(0.70, 0.76, 0.74),
    "front_door_glass" to rgb(0.36, 0.39, 0.38),
    "rear_door_glass" to rgb(0.10, 0.11, 0.11),
    "rear_side_glass" to rgb(0.10, 0.11, 0.11),
    "rear_glass" to rgb(0.07, 0.075, 0.075),
    "head_lens" to rgb(0.97, 0.97, 0.97),
    "fog_lens" to rgb(0.45, 0.45, 0.45),
    "tail_outer_lens" to rgb(0.92, 0.80, 0.80),
    "tail_lens" to rgb(0.95, 0.62, 0.62),
)

/** Everything but the paint, which is each car's own. Names are the materials in tools/car-model/build.py. */
internal fun CarLook.surface(part: String): Surface = when (part) {
    "paint" -> paint
    "rim" -> rim
    "hub" -> hub
    "caliper" -> caliper
    "seat" -> seat
    "badge" -> Surface(grey(0.9), metal = 1f, roughness = 0.08f)
    "badge_underline" -> Surface(rgb(0.6, 0.012, 0.018), roughness = 0.2f, coat = 1f)
    "sensor_ring" -> Surface(grey(0.01), roughness = 0.5f)
    "tyre_lettering" -> Surface(grey(0.03), roughness = 0.42f, occlusionPower = 0.5f)
    "spoiler" -> Surface(grey(0.012), metal = 0.2f, roughness = 0.16f, coat = 1f)
    "black_metal" -> Surface(grey(0.02), metal = 0.2f, roughness = 0.4f)
    "plastic" -> Surface(grey(0.012), roughness = 0.62f)
    "gloss_black", "gloss_black_trim" -> Surface(grey(0.005), roughness = 0.08f, coat = 1f)
    "mirror" -> Surface(grey(0.9), metal = 1f, roughness = 0.02f)
    "chrome" -> Surface(grey(0.9), metal = 1f, roughness = 0.05f)
    "lamp_chrome" -> Surface(rgb(0.42, 0.42, 0.44), metal = 1f, roughness = 0.14f, occlusionPower = 0.25f)
    "lamp_silver" -> Surface(rgb(0.2, 0.2, 0.21), metal = 1f, roughness = 0.3f, occlusionPower = 0.25f)
    "lamp_housing" -> Surface(rgb(0.09, 0.09, 0.095), metal = 0.6f, roughness = 0.35f, occlusionPower = 0.3f)
    "drl" -> Surface(grey(0.8), roughness = 0.4f, emissive = grey(0.9), occlusionPower = 0.3f)
    "projector" -> Surface(grey(0.01), roughness = 0.03f, coat = 1f, occlusionPower = 0.3f)
    "projector_ring" -> Surface(rgb(0.55, 0.55, 0.57), metal = 1f, roughness = 0.18f, occlusionPower = 0.3f)
    "valance" -> Surface(grey(0.022), roughness = 0.55f, occlusionPower = 0.35f)
    "cabin" -> Surface(grey(0.018), roughness = 0.6f, occlusionPower = 0.3f)
    "tail_red", "tail_red_2" -> Surface(rgb(0.62, 0.012, 0.016), roughness = 0.14f, coat = 1f, emissive = rgb(0.035, 0.0, 0.0), occlusionPower = 0.4f)
    "reflector" -> Surface(rgb(0.42, 0.012, 0.012), roughness = 0.15f, coat = 1f, occlusionPower = 0.5f)
    "tail_dark" -> Surface(rgb(0.05, 0.004, 0.006), roughness = 0.15f, coat = 1f)
    "rubber" -> Surface(grey(0.022), roughness = 0.68f, occlusionPower = 0.5f)
    "disc" -> Surface(rgb(0.3, 0.3, 0.31), metal = 1f, roughness = 0.45f, occlusionPower = 0.6f)
    "rim_lip" -> Surface(grey(0.012), roughness = 0.25f, coat = 0.5f, occlusionPower = 0.4f)
    else -> Surface(grey(0.03), roughness = 0.5f)
}
