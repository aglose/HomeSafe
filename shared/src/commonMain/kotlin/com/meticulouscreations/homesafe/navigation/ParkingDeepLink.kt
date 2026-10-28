package com.meticulouscreations.homesafe.navigation

import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a tap on a parking notification goes: a map of the spot the car is parked in, with what
 * the sign says. It carries the push's own data ([values], the keys [ParkingAlert] reads), so the
 * map opens on exactly what the notification said, with the box out of reach too. On Android it
 * travels as `homesafe://parking?parking_car=…&parking_lat=…`; on iOS the notification's
 * `userInfo` already is that data.
 */
data class ParkingDeepLink(val values: Map<String, String>) {
    /** The alert the data spells out; null for one this build can't read, or a car that has moved. */
    val alert: ParkingAlert? get() = ParkingAlert.from(values::get)?.takeIf { it.latitude != null }

    fun toUri(): String = "$URI?" + values.entries.joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" }

    companion object {
        const val HOST = "parking"
        const val URI = "${MomentDeepLink.SCHEME}://$HOST"

        /** From a push's data or iOS `userInfo`; null unless it is a parking alert with a spot to show. */
        fun from(values: (String) -> String?): ParkingDeepLink? {
            val alert = ParkingAlert.from(values) ?: return null
            if (alert.latitude == null) return null
            return ParkingDeepLink(KEYS.mapNotNull { key -> values(key)?.let { key to it } }.toMap())
        }

        /** From a `homesafe://parking?…` URI; null for anything else. */
        fun fromUri(uri: String?): ParkingDeepLink? {
            val url = uri?.let { runCatching { Url(it) }.getOrNull() } ?: return null
            if (url.protocol.name != MomentDeepLink.SCHEME || url.host != HOST) return null
            return from { url.parameters[it] }
        }

        /** What of a push the map needs, and no more: the relay's English title and body stay behind. */
        private val KEYS = listOf(
            ParkingAlert.KEY_PARKING, ParkingAlert.KEY_ID, ParkingAlert.KEY_EVENT, ParkingAlert.KEY_CAR, ParkingAlert.KEY_CAR_NAME,
            ParkingAlert.KEY_STREET, ParkingAlert.KEY_SIDE, ParkingAlert.KEY_BY, ParkingAlert.KEY_NOTE, ParkingAlert.KEY_MINUTES,
            ParkingAlert.KEY_FROM, ParkingAlert.KEY_UNTIL, ParkingAlert.KEY_LAT, ParkingAlert.KEY_LNG, ParkingAlert.KEY_HEADING,
        )
    }
}

/**
 * The hand-off between a platform that heard a tap on a parking notification and the shell that
 * shows the map: a state rather than an event, for the reason [MomentDeepLinks] is one (a tap
 * that cold-starts the app arrives before the shell exists).
 */
object ParkingDeepLinks {
    private val _pending = MutableStateFlow<ParkingDeepLink?>(null)
    val pending: StateFlow<ParkingDeepLink?> = _pending.asStateFlow()

    fun open(link: ParkingDeepLink) {
        _pending.value = link
    }

    /** Clears [link] once the map has been closed; a newer one that arrived meanwhile is left showing. */
    fun consume(link: ParkingDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
