package com.meticulouscreations.homesafe.domain.model

import kotlin.math.max

/**
 * Roughly how often each place/category rule would have fired lately, so the alert settings can
 * point at the noisy ones before the phone starts buzzing ("Vehicles · ~40/day" on a street zone).
 * [perDay] holds a rate only for pairs that saw anything; [days] is how much history it's based
 * on, which is less than asked for when the sample ran out before the window did.
 */
data class AlertVolume(val perDay: Map<Pair<AlertZone, MomentCategory>, Double>, val days: Double) {
    fun perDay(place: AlertZone, category: MomentCategory): Double = perDay[place to category] ?: 0.0

    companion object {
        /** A rule firing at least this often a day earns a warning next to its switch. */
        const val NOISY_PER_DAY = 10.0

        /**
         * Counts [events] the way the alert poller would judge them: a vehicle that never moved
         * is dropped (a parked car is not news), [inZones] decides where the rest went and drops
         * what no zone wanted, and each remaining detection counts once for every place it
         * touched — its zones, or the camera's "anywhere else" when it touched none. What it
         * doesn't model is the rules themselves (the point is to see what each would cost) and
         * the poller's folding of one car's re-detections into a single visit, so a busy vehicle
         * rule reads a little high.
         *
         * [events] are the newest [sampleLimit] detections since [windowStartEpochSeconds]. If the
         * sample is full it probably doesn't reach back that far, so the rate is taken over the
         * span it does cover — from its oldest detection to [nowEpochSeconds] — and never less
         * than an hour of it, so one busy minute can't read as a thousand a day.
         */
        fun estimate(
            events: List<MomentEvent>,
            zonesByCamera: Map<String, List<DetectionZone>>,
            windowStartEpochSeconds: Double,
            nowEpochSeconds: Double,
            sampleLimit: Int,
        ): AlertVolume {
            val from = if (events.size >= sampleLimit) {
                events.minOfOrNull { it.startEpochSeconds } ?: windowStartEpochSeconds
            } else {
                windowStartEpochSeconds
            }
            val days = max(nowEpochSeconds - from, SECONDS_PER_HOUR) / SECONDS_PER_DAY
            val counts = HashMap<Pair<AlertZone, MomentCategory>, Int>()
            events.forEach { event ->
                val category = event.category
                if (category == MomentCategory.ALL) return@forEach
                if (category == MomentCategory.VEHICLES && event.isStill()) return@forEach
                val placed = event.inZones(zonesByCamera[event.cameraName].orEmpty()) ?: return@forEach
                val places = placed.zones.filter { it.isNotBlank() }.distinct().map { AlertZone(event.cameraName, it) }
                    .ifEmpty { listOf(AlertZone(event.cameraName, null)) }
                places.forEach { place -> counts[place to category] = (counts[place to category] ?: 0) + 1 }
            }
            return AlertVolume(counts.mapValues { (_, count) -> count / days }, days)
        }

        private const val SECONDS_PER_HOUR = 3_600.0
        private const val SECONDS_PER_DAY = 86_400.0
    }
}
