package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.weather.domain.Precipitation
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SunGold = Color(0xFFFFCB52)
private val MoonPale = Color(0xFFDCE6FF)
private val CloudWhite = Color(0xFFF2F6FB)
private val CloudGrey = Color(0xFFB4BFCD)
private val RainBlue = Color(0xFF5EB6FF)
private val BoltYellow = Color(0xFFFFDD55)

/**
 * A small picture of the sky for [kind], drawn rather than taken from an icon font so it can be
 * in colour: a gold sun, a pale moon, a white cloud that greys as the weather turns, blue rain,
 * white snow, a yellow bolt. Decorative: whatever shows it also says the kind in words.
 */
@Composable
internal fun WeatherGlyph(kind: WeatherKind, isDay: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp)) {
        val s = size.minDimension
        val origin = Offset((size.width - s) / 2f, (size.height - s) / 2f)
        fun at(x: Float, y: Float) = Offset(origin.x + x * s, origin.y + y * s)
        when (kind) {
            WeatherKind.CLEAR -> orb(at(0.5f, 0.5f), 0.24f * s, isDay, s)

            WeatherKind.MOSTLY_CLEAR, WeatherKind.PARTLY_CLOUDY -> {
                orb(at(0.36f, 0.36f), 0.18f * s, isDay, s)
                cloud(at(0.58f, 0.64f), 0.62f * s, CloudWhite)
            }

            WeatherKind.OVERCAST -> {
                cloud(at(0.4f, 0.42f), 0.5f * s, CloudGrey)
                cloud(at(0.55f, 0.6f), 0.7f * s, CloudWhite)
            }

            WeatherKind.FOG -> {
                cloud(at(0.5f, 0.36f), 0.62f * s, CloudWhite)
                for (i in 0..2) {
                    val y = 0.62f + i * 0.13f
                    val inset = 0.14f + 0.06f * i
                    drawLine(CloudGrey, at(inset, y), at(1f - inset, y), 0.07f * s, StrokeCap.Round)
                }
            }

            else -> {
                val heavy = kind.intensity >= 0.85f
                val stormy = kind.isStorm
                cloud(at(0.5f, 0.38f), 0.72f * s, if (stormy || heavy) CloudGrey else CloudWhite)
                when (kind.precipitation) {
                    Precipitation.SNOW -> flakes(::at, s, if (heavy) 4 else 3)

                    Precipitation.MIX -> {
                        drops(::at, s, 2, long = false, shift = -0.1f)
                        drawCircle(Color.White, 0.05f * s, at(0.68f, 0.82f))
                    }

                    Precipitation.STORM -> {
                        bolt(::at)
                        drops(::at, s, 2, long = true, shift = 0.22f)
                    }

                    else -> drops(
                        ::at,
                        s,
                        if (kind.intensity < 0.3f) {
                            2
                        } else if (heavy) {
                            4
                        } else {
                            3
                        },
                        long = kind.intensity >= 0.5f,
                        shift = 0f,
                    )
                }
            }
        }
    }
}

/** The sun with its rays, or the moon as a crescent. */
private fun DrawScope.orb(center: Offset, radius: Float, isDay: Boolean, s: Float) {
    if (isDay) {
        drawCircle(SunGold, radius, center)
        for (i in 0 until 8) {
            val a = i * PI / 4
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(SunGold, center + dir * (radius * 1.38f), center + dir * (radius * 1.82f), 0.06f * s, StrokeCap.Round)
        }
    } else {
        val crescent = Path().apply { addOval(Rect(center, radius * 1.15f)) }
        val bite = Path().apply { addOval(Rect(center + Offset(radius * 0.55f, -radius * 0.3f), radius * 1.0f)) }
        drawPath(Path.combine(PathOperation.Difference, crescent, bite), MoonPale)
    }
}

/** A cloud [width] wide, centred on [center]: a flat base with three humps along it. */
private fun DrawScope.cloud(center: Offset, width: Float, color: Color) {
    val h = width * 0.3f
    val left = center.x - width / 2
    val base = center.y + h * 0.55f
    drawRoundRect(color, Offset(left, base - h), Size(width, h), CornerRadius(h / 2))
    drawCircle(color, width * 0.2f, Offset(left + width * 0.3f, base - h * 0.95f))
    drawCircle(color, width * 0.27f, Offset(left + width * 0.56f, base - h * 1.2f))
    drawCircle(color, width * 0.16f, Offset(left + width * 0.78f, base - h * 0.8f))
}

private fun DrawScope.drops(at: (Float, Float) -> Offset, s: Float, count: Int, long: Boolean, shift: Float) {
    val length = if (long) 0.2f else 0.11f
    for (i in 0 until count) {
        val x = 0.5f + shift + (i - (count - 1) / 2f) * 0.17f
        val y = 0.66f + (i % 2) * 0.05f
        drawLine(RainBlue, at(x + 0.03f, y), at(x - 0.04f, y + length), 0.065f * s, StrokeCap.Round)
    }
}

private fun DrawScope.flakes(at: (Float, Float) -> Offset, s: Float, count: Int) {
    for (i in 0 until count) {
        val x = 0.5f + (i - (count - 1) / 2f) * 0.18f
        val y = 0.72f + (i % 2) * 0.12f
        drawCircle(Color.White, 0.05f * s, at(x, y))
    }
}

private fun DrawScope.bolt(at: (Float, Float) -> Offset) {
    val path = Path().apply {
        val points = listOf(at(0.5f, 0.5f), at(0.3f, 0.76f), at(0.43f, 0.76f), at(0.34f, 0.98f), at(0.62f, 0.68f), at(0.48f, 0.68f), at(0.58f, 0.5f))
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, BoltYellow)
}
