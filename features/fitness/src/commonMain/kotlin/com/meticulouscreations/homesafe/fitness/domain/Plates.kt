package com.meticulouscreations.homesafe.fitness.domain

/**
 * What goes on each side of a barbell to make a weight, in pounds: the plates a gym has, the
 * heaviest first, as few of them as it takes.
 */
object Plates {
    /** The bar on its own: an Olympic bar. */
    const val BAR_POUNDS = 45.0

    /** The plates there are, heaviest first. */
    private val SIZES = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)

    /**
     * The plates a side for [total] pounds on a [bar], heaviest first; empty when it is the bar
     * alone. Null when it can't be loaded: lighter than the bar, or not a weight the plates make.
     */
    fun perSide(total: Double, bar: Double = BAR_POUNDS): List<Double>? {
        var left = (total - bar) / 2
        if (left < -EPSILON) return null
        val plates = ArrayList<Double>()
        for (size in SIZES) {
            while (left >= size - EPSILON) {
                plates += size
                left -= size
            }
        }
        return if (left > EPSILON) null else plates
    }

    private const val EPSILON = 1e-6
}
