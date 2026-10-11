package com.meticulouscreations.homesafe.ui.notification

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * One piece of a [NotificationGlyph]: a path on the 24-unit grid, in SVG path syntax (which is
 * also an Android vector's `pathData`). Filled, or drawn as a line [stroke] units wide with round
 * ends and corners. [accent] marks the part that carries the glyph's colour where it is drawn in
 * two (the drops under the cloud, the bolt, the mercury); in the status bar everything is one.
 */
class GlyphLayer(val path: String, val stroke: Float = 0f, val accent: Boolean = false, val evenOdd: Boolean = false)

/**
 * The app's notification icons, each drawn once, here. Android's status bar needs them as
 * single-colour vector resources ([resourceName] under `shared/src/androidMain/res/drawable`):
 * those files are written from these definitions and `NotificationGlyphsTest` fails when one has
 * drifted. The pictures on a weather notification ([drawGlyph]) are the same drawings, so the
 * small icon in the status bar and the large one in the shade are plainly the same thing.
 *
 * Shapes are kept bold and few: at status-bar size a glyph is some twenty pixels across.
 */
enum class NotificationGlyph(val resourceName: String, val layers: List<GlyphLayer>) {
    // ---- What the sky is doing ------------------------------------------------------------------
    RAIN("ic_notification_weather_rain", listOf(fill(CLOUD), line("M8,16.5l-1.5,4.5M12.5,16.5l-1.5,4.5M17,16.5l-1.5,4.5", accent = true))),
    SNOW(
        "ic_notification_weather_snow",
        listOf(fill(CLOUD), fill(dot(7.5f, 17.2f, 1.15f) + dot(12f, 17.2f, 1.15f) + dot(16.5f, 17.2f, 1.15f) + dot(9.75f, 20.7f, 1.15f) + dot(14.25f, 20.7f, 1.15f), accent = true)),
    ),
    MIX(
        "ic_notification_weather_mix",
        listOf(fill(CLOUD), line("M8,16.5l-1.5,4.5M17,16.5l-1.5,4.5", accent = true), fill(dot(12f, 18.8f, 1.35f), accent = true)),
    ),
    STORM(
        "ic_notification_weather_storm",
        listOf(fill(CLOUD), fill("M12.8,14.7L8.6,19h2.7l-1.3,3.4l5.6,-5.2h-2.8l1.4,-2.5z", accent = true), line("M6.3,16.5l-1.2,3.6M19.2,16.5l-1.2,3.6")),
    ),
    FOG("ic_notification_weather_fog", listOf(fill(CLOUD), line("M4,17h16M7,20.5h10", accent = true))),
    WIND("ic_notification_weather_wind", listOf(line("M3,8h9.5a2.5,2.5 0 1,0 -2.5,-2.5M3,12h15a3,3 0 1,1 -3,3M3,16h7a2.5,2.5 0 1,1 -2.5,2.5"))),
    SUN(
        "ic_notification_weather_sun",
        listOf(
            fill(dot(12f, 12f, 4f), accent = true),
            line("M12,2.5v2.5M12,19v2.5M2.5,12h2.5M19,12h2.5M5.3,5.3l1.75,1.75M16.95,16.95l1.75,1.75M5.3,18.7l1.75,-1.75M16.95,7.05l1.75,-1.75", accent = true),
        ),
    ),
    HEAT(
        "ic_notification_weather_heat",
        listOf(
            line(THERMOMETER),
            fill(dot(8f, 16f, 1.7f), accent = true),
            line("M8,16V6.5", accent = true),
            fill(dot(17f, 8f, 2.2f), accent = true),
            line("M17,3v1.3M17,11.7v1.3M12,8h1.3M20.7,8h1.3M14.38,5.38l-0.92,-0.92M19.62,10.62l0.92,0.92M14.38,10.62l-0.92,0.92M19.62,5.38l0.92,-0.92", width = 1.6f, accent = true),
        ),
    ),
    COLD(
        "ic_notification_weather_cold",
        listOf(
            line(THERMOMETER),
            fill(dot(8f, 16f, 1.7f), accent = true),
            line("M8,16v-2.5", accent = true),
            line("M17,3.5v9M13.1,5.75l7.8,4.5M13.1,10.25l7.8,-4.5", width = 1.6f, accent = true),
        ),
    ),
    FREEZE(
        "ic_notification_weather_freeze",
        listOf(
            line("M12,3v18M4.2,7.5l15.6,9M4.2,16.5l15.6,-9", width = 1.8f),
            line(
                "M9.5,5l2.5,2.5l2.5,-2.5M14.5,19l-2.5,-2.5l-2.5,2.5M16.81,6.34l-0.91,3.41l3.41,0.92M19.31,13.34l-3.41,0.91l0.91,3.42" +
                    "M7.19,6.34l0.91,3.41l-3.41,0.92M4.69,13.34l3.41,0.91l-0.91,3.42",
                width = 1.8f,
            ),
        ),
    ),
    WARMER(
        "ic_notification_weather_warmer",
        listOf(line(THERMOMETER), fill(dot(8f, 16f, 1.7f), accent = true), line("M8,16V9.5", accent = true), line("M17,13V4M13.5,7.5L17,4l3.5,3.5", accent = true)),
    ),
    COLDER(
        "ic_notification_weather_colder",
        listOf(line(THERMOMETER), fill(dot(8f, 16f, 1.7f), accent = true), line("M8,16v-2.5", accent = true), line("M17,4v9M13.5,9.5l3.5,3.5l3.5,-3.5", accent = true)),
    ),
    AIR(
        "ic_notification_weather_air",
        listOf(line("M3,7h6M13,7h8M3,12h11M18,12h3M3,17h4M11,17h10")),
    ),

    // ---- What a government warning is for -------------------------------------------------------
    TORNADO("ic_notification_weather_tornado", listOf(line("M3,4h18M5.5,8h13M8.5,12h9M11,16h6M13,20h3"))),
    FLOOD(
        "ic_notification_weather_flood",
        listOf(fill("M12,2l7,6h-2v4h-10v-4h-2z"), line("M3,16q2.25,-2.5 4.5,0t4.5,0t4.5,0t4.5,0M3,20.3q2.25,-2.5 4.5,0t4.5,0t4.5,0t4.5,0", accent = true)),
    ),
    HURRICANE(
        "ic_notification_weather_hurricane",
        listOf(line("M8.5,12a3.5,3.5 0 1,0 7,0a3.5,3.5 0 1,0 -7,0z"), line("M8.5,12C8.5,7 12,3.5 18.5,3M15.5,12C15.5,17 12,20.5 5.5,21")),
    ),
    FIRE(
        "ic_notification_weather_fire",
        listOf(fill("M12,2.5c1,3.5 6,6.5 6,12a6,6 0 0,1 -12,0c0,-2.5 1.2,-4.5 2.5,-5.8c0.3,1.8 1,2.8 2,3.2c-0.8,-3.4 0,-6.8 1.5,-9.4z")),
    ),
    WARNING(
        "ic_notification_weather_warning",
        listOf(line("M12,3.5L21.5,20H2.5z"), line("M12,10v4.5", accent = true), fill(dot(12f, 17.2f, 1.1f), accent = true)),
    ),

    // ---- The cameras, and the rest --------------------------------------------------------------
    CAMERA("ic_notification_detection", listOf(fill("M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"))),
    PERSON("ic_notification_person", listOf(fill(dot(12f, 7.5f, 3.5f) + "M5,20.5c0,-4 3,-6.5 7,-6.5s7,2.5 7,6.5z"))),
    CAR(
        "ic_notification_car",
        listOf(
            fill(
                "M5,11l1.6,-4.5c0.25,-0.9 1,-1.5 1.9,-1.5h7c0.9,0 1.65,0.6 1.9,1.5L19,11c1.1,0.2 2,1.1 2,2.3V18c0,0.55 -0.45,1 -1,1h-1" +
                    "c-0.55,0 -1,-0.45 -1,-1v-1H6v1c0,0.55 -0.45,1 -1,1H4c-0.55,0 -1,-0.45 -1,-1v-4.7c0,-1.2 0.9,-2.1 2,-2.3z" +
                    "M7.2,10.5l1.1,-3.3h7.4l1.1,3.3z" + dot(7f, 14f, 1.2f) + dot(17f, 14f, 1.2f),
                evenOdd = true,
            ),
        ),
    ),
    ANIMAL(
        "ic_notification_animal",
        listOf(
            fill(
                dot(5.8f, 11f, 1.9f) + dot(9.6f, 6.6f, 1.9f) + dot(14.4f, 6.6f, 1.9f) + dot(18.2f, 11f, 1.9f) +
                    "M12,11.5c-2.8,0 -5.5,3 -5.5,5.6c0,1.6 1.2,2.4 2.6,2.4c1.1,0 1.9,-0.5 2.9,-0.5s1.8,0.5 2.9,0.5c1.4,0 2.6,-0.8 2.6,-2.4c0,-2.6 -2.7,-5.6 -5.5,-5.6z",
            ),
        ),
    ),
    AWAY(
        "ic_notification_away",
        listOf(fill("M12,2.5l7.5,3v5.5c0,5 -3.2,8.9 -7.5,10.5c-4.3,-1.6 -7.5,-5.5 -7.5,-10.5V5.5zM11,7h2v6h-2zM11,15h2v2h-2z", evenOdd = true)),
    ),
    SUMMARY(
        "ic_notification_summary",
        listOf(fill(dot(4.5f, 7f, 1.3f) + dot(4.5f, 12f, 1.3f) + dot(4.5f, 17f, 1.3f)), line("M9,7h11.5M9,12h11.5M9,17h7")),
    ),
    BUDGET(
        "ic_notification_budget",
        listOf(
            line("M4.5,5.5h15a2,2 0 0,1 2,2v9a2,2 0 0,1 -2,2h-15a2,2 0 0,1 -2,-2v-9a2,2 0 0,1 2,-2z"),
            fill("M2.5,9h19v2.5h-19z"),
            line("M6,15h4", width = 1.6f),
        ),
    ),
    DONE("ic_notification_done", listOf(line("M5,12.5l4.5,4.5l9.5,-10", width = 2.4f))),
}

/** The grid every glyph is drawn on: Android's 24 dp icon. */
const val GLYPH_GRID = 24f

/**
 * Draws [glyph] in a square [size] across with its corner at [topLeft]: everything in [color],
 * but the parts that carry its colour in [accent] where one is given.
 */
fun DrawScope.drawGlyph(glyph: NotificationGlyph, topLeft: Offset, size: Float, color: Color, accent: Color = color) {
    val scale = size / GLYPH_GRID
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        glyph.layers.forEach { layer ->
            val path = PathParser().parsePathString(layer.path).toPath().apply {
                fillType = if (layer.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            }
            drawPath(
                path = path,
                color = if (layer.accent) accent else color,
                style = if (layer.stroke > 0f) Stroke(layer.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill,
            )
        }
    }
}

private fun fill(path: String, accent: Boolean = false, evenOdd: Boolean = false) = GlyphLayer(path, accent = accent, evenOdd = evenOdd)

private fun line(path: String, width: Float = 2f, accent: Boolean = false) = GlyphLayer(path, stroke = width, accent = accent)

/** A filled circle as a path: two half turns. */
private fun dot(cx: Float, cy: Float, r: Float): String = "M${trim(cx - r)},${trim(cy)}a${trim(r)},${trim(r)} 0 1,0 ${trim(2 * r)},0a${trim(r)},${trim(r)} 0 1,0 ${trim(-2 * r)},0z"

/** A coordinate as it is written in a path: to the hundredth, with no trailing zeros. */
private fun trim(value: Float): String {
    val hundredths = kotlin.math.round(value * 100).toInt()
    val whole = hundredths / 100
    val rest = kotlin.math.abs(hundredths % 100)
    val sign = if (hundredths < 0 && whole == 0) "-" else ""
    return when {
        rest == 0 -> "$sign$whole"
        rest % 10 == 0 -> "$sign$whole.${rest / 10}"
        else -> "$sign$whole.${rest.toString().padStart(2, '0')}"
    }
}

/** A cloud across the top of the grid, with room under it for what falls. */
private const val CLOUD =
    "M17.5,13.5H7c-2.21,0 -4,-1.79 -4,-4c0,-2.05 1.54,-3.74 3.53,-3.97C7.47,3.7 9.36,2.5 11.5,2.5c2.73,0 5,1.94 5.5,4.52C19.24,7.27 21,9.18 21,11.5C21,12.6 20.1,13.5 19,13.5H17.5z"

/** A thermometer's glass, on the left of the grid: what stands beside it says which way the mercury is going. */
private const val THERMOMETER = "M6,4.5a2,2 0 0,1 4,0v8a4,4 0 1,1 -4,0z"
