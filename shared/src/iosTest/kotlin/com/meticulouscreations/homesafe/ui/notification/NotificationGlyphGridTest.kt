package com.meticulouscreations.homesafe.ui.notification

import androidx.compose.ui.graphics.vector.PathParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every [NotificationGlyph] stays inside its grid, stroke included. Here rather than beside
 * NotificationGlyphsTest in the Android host tests, because measuring a path takes a real one:
 * the host tests have only Android's stubs, and iOS has Skia.
 */
class NotificationGlyphGridTest {
    @Test
    fun everyGlyphStaysOnItsGrid() {
        NotificationGlyph.entries.forEach { glyph ->
            glyph.layers.forEach { layer ->
                val bounds = PathParser().parsePathString(layer.path).toPath().getBounds()
                val reach = layer.stroke / 2
                assertTrue(
                    bounds.left - reach >= 0f && bounds.top - reach >= 0f && bounds.right + reach <= GLYPH_GRID && bounds.bottom + reach <= GLYPH_GRID,
                    "${glyph.name} draws outside the grid: $bounds with a stroke of ${layer.stroke}",
                )
            }
        }
    }
}
