package com.meticulouscreations.homesafe.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter

/**
 * One of the app's icons ([art], square and full-bleed, as the phones' are) on the Mac's icon
 * grid: a rounded square 824 of 1024 across, with the corner Apple's template has, centred in a
 * clear canvas. What the Dock shows of the icon chosen in Settings, and the packaged icon
 * (desktopApp/icons) is the classic one drawn the same way.
 */
@Composable
fun rememberMacIconPainter(art: Painter): Painter = remember(art) { MacIconPainter(art) }

private class MacIconPainter(private val art: Painter) : Painter() {
    override val intrinsicSize: Size = Size(1024f, 1024f)

    override fun DrawScope.onDraw() {
        val side = size.minDimension
        val body = side * BODY
        val inset = (side - body) / 2
        val corner = side * CORNER
        val shape = Path().apply { addRoundRect(RoundRect(inset, inset, inset + body, inset + body, CornerRadius(corner))) }
        clipPath(shape) {
            translate(inset, inset) {
                with(art) { draw(Size(body, body)) }
            }
        }
    }

    private companion object {
        const val BODY = 824f / 1024f
        const val CORNER = 185f / 1024f
    }
}
