package com.meticulouscreations.homesafe.domain.model

import androidx.compose.runtime.Immutable

/**
 * Where a camera has been dropped on the property plan, as a fraction of the plan's box:
 * [x] 0 at the street edge and 1 at the back fence, [y] 0 at the top edge and 1 at the bottom.
 *
 * Fractions rather than metres so the same placement lands in the same spot whatever size the
 * plan is drawn at — a phone card, a tablet, or the desktop window — and so a later revision of
 * the plan's bounds moves every marker with the drawing instead of stranding them off-canvas.
 */
@Immutable
data class CameraPlacement(val cameraName: String, val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "placement out of bounds: $x, $y" }
    }
}

/** Which way the Home tab draws its cameras. */
enum class HomeLayout {
    /** The original: one full-width card per camera, stacked. */
    LIST,

    /** The property plan, with each placed camera playing where it actually is. */
    MAP,
    ;

    companion object {
        val DEFAULT = LIST

        /** Falls back to the default for a value this build doesn't know, rather than throwing. */
        fun of(name: String?): HomeLayout = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
