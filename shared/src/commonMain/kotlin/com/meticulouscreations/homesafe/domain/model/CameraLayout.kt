package com.meticulouscreations.homesafe.domain.model

/**
 * Which way up a camera's picture is shown: [quarterTurns] clockwise (a camera mounted on its
 * side, or upside down), then a fine [tiltDegrees] for one mounted a little crooked, positive
 * clockwise, within ±[MAX_TILT_DEGREES] (so [degrees] alone always says which quarter turn it is).
 */
data class CameraOrientation(val quarterTurns: Int = 0, val tiltDegrees: Float = 0f) {
    /** The whole turn, clockwise, in degrees. */
    val degrees: Float get() = quarterTurns * 90f + tiltDegrees

    val isUpright: Boolean get() = quarterTurns == 0 && tiltDegrees == 0f

    /** A quarter turn more ([clockwise]) or less, keeping the tilt. */
    fun turned(clockwise: Boolean): CameraOrientation = copy(quarterTurns = (quarterTurns + if (clockwise) 1 else -1).mod(4))

    fun tilted(degrees: Float): CameraOrientation = copy(tiltDegrees = degrees.coerceIn(-MAX_TILT_DEGREES, MAX_TILT_DEGREES))

    companion object {
        val UPRIGHT = CameraOrientation()

        /**
         * The fine tilt's reach. Short of 45° on purpose: the whole turn is drawn from one angle
         * ([degrees]), and at exactly 45° that angle would be as near the next quarter turn as this
         * one, so a level camera tilted the last degree would be stood on its side.
         */
        const val MAX_TILT_DEGREES = 44f
    }
}

/**
 * How much of a camera's picture is shown, once it is turned the right way up: magnified [zoom]
 * times about the point ([centerX], [centerY]) that sits in the middle of the frame, as fractions
 * of the picture across and down. The whole picture (1x, centred) until it is cropped.
 *
 * In fractions of the picture rather than pixels or of the frame, so one crop frames a card, the
 * quick look and a camera's page alike, whatever their size and shape.
 *
 * The frame and the picture needn't be the same shape: a 4:3 picture shown whole in a 16:9 frame
 * has bars beside it. The spans say how far the frame reaches across the picture at 1x on each
 * axis (frame over picture: 4/3 across for that 4:3 picture, 1 down), so that panning stops where
 * the picture ends rather than where the frame would, and a picture narrower than the frame stays
 * centred on that axis until it is zoomed past the frame. Equal shapes are spans of 1.
 */
data class CameraCrop(val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f) {
    val isWhole: Boolean get() = zoom <= 1f

    /**
     * Zoomed by [factor] about [focusX], [focusY] (where the fingers are, as fractions of the
     * frame: 0.5 is its middle), the picture under them staying put, then dragged by [panX],
     * [panY] (fractions of the frame, positive right and down). Never past [MAX_ZOOM], never
     * smaller than the frame, and never dragged far enough to show past the picture's edge.
     */
    fun transformed(
        factor: Float,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
        panX: Float = 0f,
        panY: Float = 0f,
        spanX: Float = 1f,
        spanY: Float = 1f,
    ): CameraCrop {
        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val shift = 1f / zoom - 1f / newZoom
        return CameraCrop(
            zoom = newZoom,
            centerX = centerX + (focusX - 0.5f) * spanX * shift - panX * spanX / newZoom,
            centerY = centerY + (focusY - 0.5f) * spanY * shift - panY * spanY / newZoom,
        ).clamped(spanX, spanY)
    }

    /** At [newZoom] about the same middle, as far as the picture's edges allow. */
    fun zoomedTo(newZoom: Float, spanX: Float = 1f, spanY: Float = 1f): CameraCrop =
        copy(zoom = newZoom.coerceIn(1f, MAX_ZOOM)).clamped(spanX, spanY)

    /**
     * Within bounds, in a frame spanning [spanX] by [spanY] of the picture at 1x: [zoom] between
     * 1x and [MAX_ZOOM], and the middle where the frame stays on the picture, or centred on an axis
     * where the picture doesn't yet fill the frame.
     */
    fun clamped(spanX: Float = 1f, spanY: Float = 1f): CameraCrop {
        val zoom = zoom.coerceIn(1f, MAX_ZOOM)
        return CameraCrop(zoom, centerX.within(0.5f * spanX / zoom), centerY.within(0.5f * spanY / zoom))
    }

    /** This middle, kept [half] (the frame's half-width, as a fraction of the picture) from the picture's edges. */
    private fun Float.within(half: Float): Float = if (half >= 0.5f) 0.5f else coerceIn(half, 1f - half)

    companion object {
        val WHOLE = CameraCrop()

        /** As far as the camera pages' own pinch goes: past it a camera's picture is all blur. */
        const val MAX_ZOOM = 5f
    }
}

/** How a camera's picture is framed: which way up ([orientation]), and how much of it shows ([crop]). */
data class CameraFraming(val orientation: CameraOrientation = CameraOrientation.UPRIGHT, val crop: CameraCrop = CameraCrop.WHOLE) {
    val isUntouched: Boolean get() = orientation.isUpright && crop.isWhole

    companion object {
        val UNTOUCHED = CameraFraming()
    }
}

/**
 * How one camera sits on the Home page: where in its list ([position], null until arranged), which
 * way up ([orientation]), how much of the picture shows ([crop]), and whether it is left off the
 * page altogether ([hidden]; it still shows in Arrange mode, where it is brought back). Orientation
 * and crop are its framing, and apply wherever its video plays.
 */
data class CameraLayout(
    val position: Int? = null,
    val orientation: CameraOrientation = CameraOrientation.UPRIGHT,
    val crop: CameraCrop = CameraCrop.WHOLE,
    val hidden: Boolean = false,
) {
    val framing: CameraFraming get() = CameraFraming(orientation, crop)
}
