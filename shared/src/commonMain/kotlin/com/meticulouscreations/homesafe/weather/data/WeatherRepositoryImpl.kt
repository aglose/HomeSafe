package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.WeatherDao
import com.meticulouscreations.homesafe.data.WeatherNoticeEntity
import com.meticulouscreations.homesafe.data.WeatherPlaceEntity
import com.meticulouscreations.homesafe.data.WeatherPreferencesEntity
import com.meticulouscreations.homesafe.data.WeatherReportEntity
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeLedger
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherPlacesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferences
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WeatherRepositoryImpl(
    private val openMeteo: OpenMeteoApi,
    private val nws: NwsApi,
    private val radarApi: RadarApi,
    private val dao: WeatherDao,
    private val clock: Clock,
) : WeatherRepository {

    private class Held(val place: Place, val report: WeatherReport)

    private val reports = HashMap<String, Held>()
    private val fetching = HashMap<String, Mutex>()
    private val guard = Mutex()
    private val searches = HashMap<String, List<Place>>()
    private val names = HashMap<String, Pair<String, String>?>()
    private var radar: Pair<Long, RadarTimeline>? = null
    private var radarInUs: Boolean? = null

    private fun now(): Long = clock.now().epochSeconds

    override suspend fun report(place: Place, maxAgeSeconds: Long): Result<WeatherReport> {
        fresh(place, maxAgeSeconds)?.let { return Result.success(it) }
        val lock = guard.withLock { fetching.getOrPut(place.id) { Mutex() } }
        return lock.withLock {
            // Whoever held the lock may have just fetched it.
            fresh(place, maxAgeSeconds)?.let { return@withLock Result.success(it) }
            fetch(place).onSuccess { report ->
                guard.withLock { reports[place.id] = Held(place, report) }
                runCatching {
                    dao.upsertReport(WeatherReportEntity(place.id, weatherJson.encodeToString(WeatherReport.serializer(), report), report.fetchedAtEpochSeconds, place.latitude, place.longitude))
                }
            }
        }
    }

    private suspend fun fresh(place: Place, maxAgeSeconds: Long): WeatherReport? = guard.withLock {
        reports[place.id]?.takeIf { sameSpot(it.place, place) && now() - it.report.fetchedAtEpochSeconds <= maxAgeSeconds }?.report
    }

    /** The forecast is the report; air quality and alerts ride along when they answer, and are left off when they don't. */
    private suspend fun fetch(place: Place): Result<WeatherReport> = coroutineScope {
        val forecast = async { openMeteo.forecast(place.latitude, place.longitude, now()) }
        val air = async { openMeteo.airQuality(place.latitude, place.longitude) }
        val alerts = async {
            if (NwsApi.covers(place.latitude, place.longitude)) nws.alerts(place.latitude, place.longitude) else Result.success(emptyList())
        }
        val base = forecast.await()
        val airQuality = air.await().getOrNull()
        val inForce = alerts.await().getOrDefault(emptyList())
        base.map { it.copy(air = airQuality, alerts = inForce) }
    }

    override suspend fun lastReport(place: Place): WeatherReport? {
        guard.withLock { reports[place.id]?.takeIf { sameSpot(it.place, place) }?.report }?.let { return it }
        val row = runCatching { dao.report(place.id) }.getOrNull() ?: return null
        if (abs(row.latitude - place.latitude) > SAME_SPOT_DEGREES || abs(row.longitude - place.longitude) > SAME_SPOT_DEGREES) return null
        return runCatching { weatherJson.decodeFromString(WeatherReport.serializer(), row.json) }.getOrNull()
    }

    override suspend fun searchPlaces(query: String): Result<List<Place>> {
        val key = query.trim().lowercase()
        guard.withLock { searches[key] }?.let { return Result.success(it) }
        return openMeteo.search(key).onSuccess { found -> guard.withLock { searches[key] = found } }
    }

    override suspend fun nameOf(latitude: Double, longitude: Double): Pair<String, String>? {
        if (!NwsApi.covers(latitude, longitude)) return null
        val key = Place.idFor(latitude, longitude)
        guard.withLock { if (key in names) return names[key] }
        // A miss is remembered only for this run of the app: the service may simply have been down.
        return nws.nameOf(latitude, longitude).also { name -> guard.withLock { names[key] = name } }
    }

    override suspend fun radar(latitude: Double, longitude: Double): Result<RadarTimeline> {
        val inUs = RadarApi.inContiguousUs(latitude, longitude)
        guard.withLock { radar?.takeIf { radarInUs == inUs && now() - it.first < RADAR_MAX_AGE_SECONDS }?.second }?.let { return Result.success(it) }
        return radarApi.timeline(latitude, longitude).onSuccess { timeline ->
            guard.withLock {
                radar = now() to timeline
                radarInUs = inUs
            }
        }
    }

    private fun sameSpot(a: Place, b: Place) = abs(a.latitude - b.latitude) <= SAME_SPOT_DEGREES && abs(a.longitude - b.longitude) <= SAME_SPOT_DEGREES

    private companion object {
        /** About two kilometres: nearer than the forecast's own grid. */
        const val SAME_SPOT_DEGREES = 0.02

        /** A new radar frame is published every couple of minutes at the soonest. */
        const val RADAR_MAX_AGE_SECONDS = 120L
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WeatherPlacesRepositoryImpl(private val dao: WeatherDao) : WeatherPlacesRepository {
    private val mutex = Mutex()

    override fun observe(): Flow<List<Place>> = dao.observePlaces().map { rows -> rows.map { it.toPlace() } }

    override suspend fun add(place: Place) = mutex.withLock {
        val rows = dao.places()
        if (rows.any { it.id == place.id }) return@withLock
        dao.upsertPlaces(listOf(WeatherPlaceEntity(place.id, place.name, place.region, place.latitude, place.longitude, (rows.maxOfOrNull { it.position } ?: -1) + 1)))
    }

    override suspend fun remove(id: String) = mutex.withLock {
        dao.deletePlace(id)
        runCatching { dao.deleteReport(id) }
        Unit
    }

    override suspend fun move(id: String, toIndex: Int) = mutex.withLock {
        val rows = dao.places().toMutableList()
        val from = rows.indexOfFirst { it.id == id }
        if (from < 0) return@withLock
        val row = rows.removeAt(from)
        rows.add(toIndex.coerceIn(0, rows.size), row)
        dao.upsertPlaces(rows.mapIndexed { i, r -> r.copy(position = i) })
    }

    private fun WeatherPlaceEntity.toPlace() = Place(id, name, region, latitude, longitude)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WeatherPreferencesRepositoryImpl(private val dao: WeatherDao) : WeatherPreferencesRepository {
    private val mutex = Mutex()

    override fun observe(): Flow<WeatherPreferences> = dao.observePreferences().map { it.toDomain() }.distinctUntilChanged()

    override suspend fun update(change: (WeatherPreferences) -> WeatherPreferences) = mutex.withLock {
        val current = dao.observePreferences().first().toDomain()
        val next = change(current)
        if (next != current) dao.upsertPreferences(next.toEntity())
    }

    private fun WeatherPreferencesEntity?.toDomain(): WeatherPreferences {
        if (this == null) return WeatherPreferences()
        return WeatherPreferences(
            units = WeatherUnits(
                temperature = TemperatureUnit.entries.firstOrNull { it.name == temperatureUnit } ?: TemperatureUnit.FAHRENHEIT,
                measures = MeasureSystem.entries.firstOrNull { it.name == measures } ?: MeasureSystem.IMPERIAL,
            ),
            notices = WeatherNoticeSettings(
                enabled = noticesEnabled,
                precipitationSoon = noticePrecipitationSoon,
                dailyOutlook = noticeDailyOutlook,
                severeAlerts = noticeSevereAlerts,
                extremes = noticeExtremes,
            ),
            stillSky = stillSky,
            selectedPlaceId = selectedPlaceId,
            lastKnown = if (lastLatitude != null && lastLongitude != null) {
                Place(Place.CURRENT_ID, lastName.orEmpty(), lastRegion.orEmpty(), lastLatitude, lastLongitude)
            } else {
                null
            },
        )
    }

    private fun WeatherPreferences.toEntity() = WeatherPreferencesEntity(
        temperatureUnit = units.temperature.name,
        measures = units.measures.name,
        noticesEnabled = notices.enabled,
        noticePrecipitationSoon = notices.precipitationSoon,
        noticeDailyOutlook = notices.dailyOutlook,
        noticeSevereAlerts = notices.severeAlerts,
        noticeExtremes = notices.extremes,
        stillSky = stillSky,
        selectedPlaceId = selectedPlaceId,
        lastLatitude = lastKnown?.latitude,
        lastLongitude = lastKnown?.longitude,
        lastName = lastKnown?.name,
        lastRegion = lastKnown?.region,
    )
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WeatherNoticeLedgerImpl(private val dao: WeatherDao) : WeatherNoticeLedger {
    override suspend fun sent(nowEpochSeconds: Long): Map<String, Long> {
        dao.deleteNoticesBefore(nowEpochSeconds - REMEMBER_SECONDS)
        return dao.notices().associate { it.key to it.sentAtEpochSeconds }
    }

    override suspend fun record(key: String, atEpochSeconds: Long) = dao.upsertNotice(WeatherNoticeEntity(key, atEpochSeconds))

    private companion object {
        /** Long enough that yesterday's outlook and a two-day warning aren't announced again. */
        const val REMEMBER_SECONDS = 5 * 86_400L
    }
}

/**
 * Where the phone is, for the weather app: the same one-shot fix the home geofence is set from
 * ([GeofenceMonitor]), named by the weather service, and remembered so there is somewhere to
 * forecast for the next time a fix can't be had (the app in the background, location switched
 * off). Weather only ever needs "while using the app", so it never asks for more.
 */
@Inject
@SingleIn(AppScope::class)
class WeatherLocator(
    private val monitor: GeofenceMonitor,
    private val identity: DeviceIdentityStore,
    private val repository: WeatherRepository,
    private val preferences: WeatherPreferencesRepository,
) {
    val isSupported: Boolean get() = monitor.isSupported

    val access: StateFlow<LocationAccess> get() = monitor.access

    /** Shows the OS prompt for location, when it hasn't been granted. Granted already, it does nothing: the next step up is "always", which the geofence asks for, not this. */
    suspend fun requestAccess() {
        val current = monitor.access.value
        if (current == LocationAccess.NOT_ASKED || current == LocationAccess.DENIED) monitor.requestAccess()
    }

    /** Where the phone is now, as the app's "current" place, or null without permission or a fix. */
    suspend fun locate(): Place? {
        if (!monitor.isSupported) return null
        val point = monitor.currentLocation() ?: return null
        val known = preferences.observe().first().lastKnown
        // Not moved far enough to be somewhere with another name: keep the one in hand.
        val name = if (known != null && known.name.isNotBlank() && abs(known.latitude - point.latitude) < 0.03 && abs(known.longitude - point.longitude) < 0.03) {
            known.name to known.region
        } else {
            repository.nameOf(point.latitude, point.longitude) ?: ("" to "")
        }
        val place = Place(Place.CURRENT_ID, name.first, name.second, point.latitude, point.longitude)
        preferences.update { it.copy(lastKnown = place) }
        return place
    }

    /**
     * Where to forecast for when there is no fix: where the phone last was, else the household's
     * home (unnamed until [named] is asked). Reads the device only; nothing is fetched.
     */
    suspend fun remembered(): Place? {
        preferences.observe().first().lastKnown?.let { return it }
        val home = runCatching { identity.cachedHome() }.getOrNull() ?: return null
        return Place(Place.CURRENT_ID, "", "", home.latitude, home.longitude)
    }

    /** [place] with the name the weather service gives its position, when it has none yet and the service has one. */
    suspend fun named(place: Place): Place {
        if (place.name.isNotBlank()) return place
        val name = repository.nameOf(place.latitude, place.longitude) ?: return place
        return place.copy(name = name.first, region = name.second)
    }
}
