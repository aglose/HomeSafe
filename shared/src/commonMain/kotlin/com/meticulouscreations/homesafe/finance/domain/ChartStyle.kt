package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.chart_style_haptics_crisp
import homesafe.shared.generated.resources.chart_style_haptics_crisp_blurb
import homesafe.shared.generated.resources.chart_style_haptics_light
import homesafe.shared.generated.resources.chart_style_haptics_light_blurb
import homesafe.shared.generated.resources.chart_style_haptics_off
import homesafe.shared.generated.resources.chart_style_haptics_off_blurb
import homesafe.shared.generated.resources.chart_style_haptics_strong
import homesafe.shared.generated.resources.chart_style_haptics_strong_blurb
import homesafe.shared.generated.resources.chart_style_shader_aurora
import homesafe.shared.generated.resources.chart_style_shader_aurora_blurb
import homesafe.shared.generated.resources.chart_style_shader_flat
import homesafe.shared.generated.resources.chart_style_shader_flat_blurb
import homesafe.shared.generated.resources.chart_style_shader_glow
import homesafe.shared.generated.resources.chart_style_shader_glow_blurb
import homesafe.shared.generated.resources.chart_style_shader_halftone
import homesafe.shared.generated.resources.chart_style_shader_halftone_blurb
import homesafe.shared.generated.resources.chart_style_shader_neon
import homesafe.shared.generated.resources.chart_style_shader_neon_blurb
import homesafe.shared.generated.resources.chart_style_sharpness_points
import homesafe.shared.generated.resources.chart_style_sharpness_points_blurb
import homesafe.shared.generated.resources.chart_style_sharpness_sharp
import homesafe.shared.generated.resources.chart_style_sharpness_sharp_blurb
import homesafe.shared.generated.resources.chart_style_sharpness_smooth
import homesafe.shared.generated.resources.chart_style_sharpness_smooth_blurb
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource

/** How a line chart is lit: the light on the line and what fills beneath it. */
enum class ChartShader(val label: StringResource, val blurb: StringResource) {
    /** The original: a soft halo round the line and a wash fading down from it. */
    GLOW(Res.string.chart_style_shader_glow, Res.string.chart_style_shader_glow_blurb),

    /** Northern lights drifting under the line, always moving. */
    AURORA(Res.string.chart_style_shader_aurora, Res.string.chart_style_shader_aurora_blurb),

    /** A dot screen under the line that swells where the finger is. */
    HALFTONE(Res.string.chart_style_shader_halftone, Res.string.chart_style_shader_halftone_blurb),

    /** A white-hot core in a wide bloom, like a neon tube. */
    NEON(Res.string.chart_style_shader_neon, Res.string.chart_style_shader_neon_blurb),

    /** Just the line. */
    FLAT(Res.string.chart_style_shader_flat, Res.string.chart_style_shader_flat_blurb),
}

/** How faithfully the line follows the data. */
enum class LineSharpness(val label: StringResource, val blurb: StringResource) {
    /** A gentle curve through a thinned-out series: pretty, and calm on a noisy day. */
    SMOOTH(Res.string.chart_style_sharpness_smooth, Res.string.chart_style_sharpness_smooth_blurb),

    /** Straight from point to point, every real high and low kept. */
    SHARP(Res.string.chart_style_sharpness_sharp, Res.string.chart_style_sharpness_sharp_blurb),

    /** Sharp, with each reading marked by a dot where there's room. */
    POINTS(Res.string.chart_style_sharpness_points, Res.string.chart_style_sharpness_points_blurb),
}

/** What a finger scrubbing a chart feels. */
enum class ChartHaptics(val label: StringResource, val blurb: StringResource) {
    OFF(Res.string.chart_style_haptics_off, Res.string.chart_style_haptics_off_blurb),
    LIGHT(Res.string.chart_style_haptics_light, Res.string.chart_style_haptics_light_blurb),
    CRISP(Res.string.chart_style_haptics_crisp, Res.string.chart_style_haptics_crisp_blurb),
    STRONG(Res.string.chart_style_haptics_strong, Res.string.chart_style_haptics_strong_blurb),
}

/** The look and feel every finance chart shares, chosen on the chart settings page. */
@Immutable
data class ChartStyle(
    val shader: ChartShader = ChartShader.GLOW,
    val sharpness: LineSharpness = LineSharpness.SMOOTH,
    val haptics: ChartHaptics = ChartHaptics.CRISP,
) {
    companion object {
        val DEFAULT = ChartStyle()
    }
}

/** The chart style on this device, persisted across sessions. */
interface ChartStyleRepository {
    fun observe(): Flow<ChartStyle>

    suspend fun update(style: ChartStyle)
}
