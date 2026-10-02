package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/** How a line chart is lit: the light on the line and what fills beneath it. */
enum class ChartShader(val label: String, val blurb: String) {
    /** The original: a soft halo round the line and a wash fading down from it. */
    GLOW("Glow", "A soft halo and a wash beneath"),

    /** Northern lights drifting under the line, always moving. */
    AURORA("Aurora", "Northern lights drift under the line"),

    /** A dot screen under the line that swells where the finger is. */
    HALFTONE("Halftone", "A dot screen that swells under your finger"),

    /** A white-hot core in a wide bloom, like a neon tube. */
    NEON("Neon", "A white-hot core in a wide bloom"),

    /** Just the line. */
    FLAT("Flat", "Just the line, nothing else"),
}

/** How faithfully the line follows the data. */
enum class LineSharpness(val label: String, val blurb: String) {
    /** A gentle curve through a thinned-out series: pretty, and calm on a noisy day. */
    SMOOTH("Smooth", "A gentle curve, easy on the eye"),

    /** Straight from point to point, every real high and low kept. */
    SHARP("Sharp", "Straight between points, every high and low kept"),

    /** Sharp, with each reading marked by a dot where there's room. */
    POINTS("Every point", "Sharp, with a dot on each reading"),
}

/** What a finger scrubbing a chart feels. */
enum class ChartHaptics(val label: String, val blurb: String) {
    OFF("Off", "No buzz"),
    LIGHT("Light", "A faint tick on each point"),
    CRISP("Crisp", "A clear tick on each point, a thud at the highs and lows"),
    STRONG("Strong", "A firm click on each point, a thud at the highs and lows"),
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
