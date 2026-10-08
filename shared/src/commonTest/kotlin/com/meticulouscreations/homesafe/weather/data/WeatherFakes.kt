package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** A [WeatherRepository] that answers from lambdas the test sets, and remembers what it was asked. */
internal class FakeWeatherRepository : WeatherRepository {
    /** The place and maximum age of each forecast asked for. */
    val reportRequests = mutableListOf<Pair<Place, Long>>()
    val nameRequests = mutableListOf<Pair<Double, Double>>()
    val searches = mutableListOf<String>()
    val radarRequests = mutableListOf<Pair<Double, Double>>()
    val radarCalls: Int get() = radarRequests.size

    /** When set, every forecast request waits on it: a slow network. */
    var gate: CompletableDeferred<Unit>? = null

    var onReport: (Place) -> Result<WeatherReport> = { Result.failure(IllegalStateException("no forecast set up")) }
    var onName: (Double, Double) -> Pair<String, String>? = { _, _ -> null }
    var onSearch: (String) -> Result<List<Place>> = { Result.success(emptyList()) }
    var onRadar: () -> Result<RadarTimeline> = { Result.failure(IllegalStateException("no radar set up")) }
    var stored: (Place) -> WeatherReport? = { null }

    override suspend fun report(place: Place, maxAgeSeconds: Long): Result<WeatherReport> {
        reportRequests += place to maxAgeSeconds
        gate?.await()
        return onReport(place)
    }

    override suspend fun lastReport(place: Place): WeatherReport? = stored(place)

    override suspend fun searchPlaces(query: String): Result<List<Place>> {
        searches += query
        return onSearch(query)
    }

    override suspend fun nameOf(latitude: Double, longitude: Double): Pair<String, String>? {
        nameRequests += latitude to longitude
        return onName(latitude, longitude)
    }

    override suspend fun radar(latitude: Double, longitude: Double): Result<RadarTimeline> {
        radarRequests += latitude to longitude
        return onRadar()
    }
}

/** A phone: a location the test moves about, and a permission it can change. */
internal class FakeGeofence(access: LocationAccess = LocationAccess.WHILE_IN_USE) : GeofenceMonitor {
    override var isSupported = true
    override val access = MutableStateFlow(access)
    var here: GeoPoint? = GeoPoint(45.5234, -122.6762)
    var failure: Throwable? = null
    var accessRequests = 0
    var fixes = 0

    override suspend fun requestAccess() {
        accessRequests++
    }

    override suspend fun currentLocation(): GeoPoint? {
        fixes++
        failure?.let { throw it }
        // The real monitor has nothing to say without permission.
        val granted = access.value == LocationAccess.WHILE_IN_USE || access.value == LocationAccess.ALWAYS
        return if (granted) here else null
    }

    override fun watch(home: HomeLocation?) = Unit
}

internal class FakeWeatherNotifier(override var isSupported: Boolean = true) : WeatherNotifier {
    val posted = mutableListOf<WeatherNotification>()

    /** What the OS says about the notification permission. */
    var allowed = true

    override suspend fun isAllowed(): Boolean = allowed

    override fun notify(notification: WeatherNotification) {
        posted += notification
    }
}

internal class FakeWeatherCheckScheduler : WeatherCheckScheduler {
    val calls = mutableListOf<Boolean>()

    override fun schedule(enabled: Boolean) {
        calls += enabled
    }
}

/** The phone's notification permission: what it is now, and what asking for it leads to. */
internal class FakeAlertNotifier(
    override var isSupported: Boolean = true,
    var permission: NotificationPermission = NotificationPermission.GRANTED,
    /** What the permission becomes if the person says yes when asked. */
    var onAsk: NotificationPermission = NotificationPermission.GRANTED,
) : AlertNotifier {
    var asked = 0
    var settingsOpened = 0

    override suspend fun permissionStatus(): NotificationPermission = permission

    override suspend fun requestPermission(): Boolean {
        asked++
        permission = onAsk
        return permission == NotificationPermission.GRANTED
    }

    override fun openSystemSettings() {
        settingsOpened++
    }

    override fun notify(notification: AlertNotification) = Unit
}
