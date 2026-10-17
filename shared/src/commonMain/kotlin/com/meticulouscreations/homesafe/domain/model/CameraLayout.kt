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

/** How one camera sits on the Home page: where in its list ([position], null until arranged), and which way up. */
data class CameraLayout(val position: Int? = null, val orientation: CameraOrientation = CameraOrientation.UPRIGHT)
