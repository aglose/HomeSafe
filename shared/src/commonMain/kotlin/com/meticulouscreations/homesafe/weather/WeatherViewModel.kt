package com.meticulouscreations.homesafe.weather

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import com.meticulouscreations.homesafe.weather.data.MapTileStore
import com.meticulouscreations.homesafe.weather.data.WeatherAlertCheck
import com.meticulouscreations.homesafe.weather.data.WeatherLocator
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherPlacesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferences
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_error_forecast
import homesafe.shared.generated.resources.weather_error_radar
import homesafe.shared.generated.resources.weather_error_search
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.time.Clock

/** One place on the weather app's pager: its forecast once there is one, and how fetching it is going. */
@Immutable
data class PlaceWeather(
    val place: Place,
    val report: WeatherReport? = null,
    val loading: Boolean = false,
    /** Why the last fetch failed. With a [report] still in hand it is an old one, shown with this beside it. */
    val error: UiText? = null,
)

/** The add-a-city search: what was typed and what the geocoder matched it with. */
@Immutable
data class PlaceSearch(val query: String = "", val results: List<Place> = emptyList(), val loading: Boolean = false, val error: UiText? = null)

/** The radar loop for the place in view. */
@Immutable
data class RadarLoad(val timeline: RadarTimeline? = null, val loading: Boolean = false, val error: UiText? = null)

@Immutable
data class WeatherUiState(
    /** Where the phone is (when it's known) first, then the saved cities in their order. */
    val places: List<PlaceWeather> = emptyList(),
    val selectedId: String? = null,
    val preferences: WeatherPreferences = WeatherPreferences(),
    val locationSupported: Boolean = false,
    val locationAccess: LocationAccess = LocationAccess.UNAVAILABLE,
    /** A fix is being taken. */
    val locating: Boolean = false,
    val refreshing: Boolean = false,
    val search: PlaceSearch = PlaceSearch(),
    val radar: RadarLoad = RadarLoad(),
    /** The clock the screens read "now" from, moved on once a minute while the app is up. */
    val nowEpochSeconds: Long = 0,
    /** Whether the OS lets the app notify, or null where it has no notifications at all. */
    val notificationPermission: NotificationPermission? = null,
    /** The first look for places and a position is over: an empty [places] now means there are none. */
    val settled: Boolean = false,
) {
    val selected: PlaceWeather? get() = places.firstOrNull { it.place.id == selectedId } ?: places.firstOrNull()

    val selectedIndex: Int get() = places.indexOfFirst { it.place.id == selected?.place?.id }.coerceAtLeast(0)

    val units: WeatherUnits get() = preferences.units

    fun isSaved(place: Place): Boolean = places.any { it.place.id == place.id }
}

/**
 * The weather app's state: a forecast for wherever the phone is and for each saved city, the
 * radar loop, the city search and the preferences.
 *
 * Scoped to the activity, as the finance app's is, so the drawer's card and the full app share
 * what has been loaded. Nothing is fetched until [setActive] says the drawer or the app is on
 * screen; while the app is up the forecasts are refreshed as they age and the clock is moved on
 * each minute.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class WeatherViewModel(
    private val repository: WeatherRepository,
    private val placesRepository: WeatherPlacesRepository,
    private val preferences: WeatherPreferencesRepository,
    private val locator: WeatherLocator,
    private val alertCheck: WeatherAlertCheck,
    private val alertNotifier: AlertNotifier,
    private val clock: Clock,
    /** The radar map's tiles, for the map to draw from. */
    val tiles: MapTileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeatherUiState(nowEpochSeconds = now(), locationSupported = locator.isSupported))
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private var here: Place? = null
    private var saved: List<Place> = emptyList()
    private var savedLoaded = false
    private var hereLoaded = false
    private var active = false
    private var full = false
    private var pollJob: Job? = null
    private var locateJob: Job? = null
    private var locatedAt = 0L
    private var searchJob: Job? = null
    private var radarJob: Job? = null
    private val fetches = HashMap<String, Job>()

    private fun now(): Long = clock.now().epochSeconds

    init {
        viewModelScope.launch {
            preferences.observe().collect { prefs ->
                _uiState.update { it.copy(preferences = prefs, selectedId = it.selectedId ?: prefs.selectedPlaceId) }
            }
        }
        viewModelScope.launch {
            placesRepository.observe().collect { list ->
                saved = list
                savedLoaded = true
                rebuild()
                if (active) loadForecasts(MAX_AGE_SECONDS)
            }
        }
        viewModelScope.launch {
            // Where the phone last was (or home): something to show before a fix comes back.
            if (here == null) here = locator.remembered()
            hereLoaded = true
            rebuild()
            if (active) loadForecasts(MAX_AGE_SECONDS)
        }
        viewModelScope.launch {
            locator.access.collect { access ->
                val before = _uiState.value.locationAccess
                _uiState.update { it.copy(locationAccess = access) }
                val granted = access == LocationAccess.WHILE_IN_USE || access == LocationAccess.ALWAYS
                val was = before == LocationAccess.WHILE_IN_USE || before == LocationAccess.ALWAYS
                if (granted && !was && active) locate(force = true)
            }
        }
    }

    /** The list on screen: the phone's place, then the saved ones, each keeping the forecast it already had. */
    private fun rebuild() {
        _uiState.update { state ->
            val held = state.places.associateBy { it.place.id }
            val all = listOfNotNull(here) + saved
            val rebuilt = all.map { place ->
                val before = held[place.id]
                // The phone has moved on: the forecast it had was for somewhere else.
                if (before != null && sameSpot(before.place, place)) before.copy(place = place) else PlaceWeather(place)
            }
            val selected = state.selectedId?.takeIf { id -> rebuilt.any { it.place.id == id } } ?: rebuilt.firstOrNull()?.place?.id
            state.copy(places = rebuilt, selectedId = selected, settled = savedLoaded && hereLoaded)
        }
    }

    /** The drawer ([full] false) or the app itself came on screen; [active] false when both are gone. */
    fun setActive(active: Boolean, full: Boolean = true) {
        val wasFull = this.active && this.full
        this.active = active
        this.full = active && full
        pollJob?.cancel()
        pollJob = null
        if (!active) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update { it.copy(nowEpochSeconds = now()) }
                loadForecasts(MAX_AGE_SECONDS)
                delay(TICK_MS)
            }
        }
        locate()
        if (this.full && !wasFull) {
            viewModelScope.launch { _uiState.update { it.copy(notificationPermission = if (alertNotifier.isSupported) alertNotifier.permissionStatus() else null) } }
            // Opening the app is as good a moment as the background job's to see if something is due.
            viewModelScope.launch { alertCheck.run() }
        }
    }

    /**
     * Takes a fix, if the app may, and makes it the first place. Not more often than every few
     * minutes unless [force]d: the drawer opening is no reason to wake the GPS each time.
     */
    private fun locate(force: Boolean = false) {
        if (locateJob?.isActive == true) return
        val access = locator.access.value
        val allowed = locator.isSupported && (access == LocationAccess.WHILE_IN_USE || access == LocationAccess.ALWAYS)
        if (allowed && !force && now() - locatedAt < RELOCATE_SECONDS) return
        locateJob = viewModelScope.launch {
            if (allowed) {
                _uiState.update { it.copy(locating = true) }
                val found = runCatching { locator.locate() }.getOrNull()
                _uiState.update { it.copy(locating = false) }
                if (found != null) {
                    locatedAt = now()
                    here = found
                    rebuild()
                    load(found, MAX_AGE_SECONDS)
                    return@launch
                }
            }
            // No fix to be had: the remembered place at least gets its name, once.
            val unnamed = here?.takeIf { it.name.isBlank() } ?: return@launch
            val named = runCatching { locator.named(unnamed) }.getOrNull() ?: return@launch
            if (named.name.isNotBlank() && here == unnamed) {
                here = named
                rebuild()
            }
        }
    }

    /** Asks the OS for the phone's location, then uses it. */
    fun requestLocation() {
        viewModelScope.launch {
            locator.requestAccess()
            locate(force = true)
        }
    }

    /** Every place when the app is up; only the one in view when it's just the drawer's card. */
    private fun loadForecasts(maxAgeSeconds: Long) {
        val state = _uiState.value
        val wanted = if (full) state.places.map { it.place } else listOfNotNull(state.selected?.place)
        wanted.forEach { load(it, maxAgeSeconds) }
    }

    private fun load(place: Place, maxAgeSeconds: Long) {
        if (fetches[place.id]?.isActive == true) return
        fetches[place.id] = viewModelScope.launch {
            if (entry(place)?.report == null) {
                update(place) { it.copy(loading = true) }
                // What was on the device from last time, while the network answers.
                repository.lastReport(place)?.let { last -> update(place) { if (it.report == null) it.copy(report = last) else it } }
            }
            repository.report(place, maxAgeSeconds)
                .onSuccess { report -> update(place) { it.copy(report = report, loading = false, error = null) } }
                .onFailure { e -> update(place) { it.copy(loading = false, error = e.orFallback(Res.string.weather_error_forecast)) } }
        }
    }

    private fun entry(place: Place): PlaceWeather? = _uiState.value.places.firstOrNull { it.place.id == place.id && sameSpot(it.place, place) }

    private fun update(place: Place, change: (PlaceWeather) -> PlaceWeather) {
        _uiState.update { state ->
            state.copy(places = state.places.map { if (it.place.id == place.id && sameSpot(it.place, place)) change(it) else it })
        }
    }

    /** Pull to refresh: a new fix and every forecast again, whatever its age. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, nowEpochSeconds = now()) }
            locate(force = true)
            locateJob?.join()
            // Anything already on its way finishes first, so the forced fetch isn't skipped for it.
            fetches.values.toList().forEach { it.join() }
            _uiState.value.places.forEach { load(it.place, maxAgeSeconds = 0) }
            fetches.values.toList().forEach { it.join() }
            if (_uiState.value.radar.timeline != null) loadRadar(force = true)
            _uiState.update { it.copy(refreshing = false) }
        }
    }

    fun select(placeId: String) {
        if (_uiState.value.places.none { it.place.id == placeId }) return
        _uiState.update { it.copy(selectedId = placeId, radar = RadarLoad()) }
        viewModelScope.launch { preferences.update { it.copy(selectedPlaceId = placeId) } }
        _uiState.value.selected?.place?.let { load(it, MAX_AGE_SECONDS) }
    }

    // ---- Places ---------------------------------------------------------------------------------

    fun search(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _uiState.update { it.copy(search = PlaceSearch(query = query)) }
            return
        }
        _uiState.update { it.copy(search = it.search.copy(query = query, loading = true, error = null)) }
        searchJob = viewModelScope.launch {
            // Wait out the typing: one request for the word, not one a letter.
            delay(SEARCH_DEBOUNCE_MS)
            repository.searchPlaces(query)
                .onSuccess { found -> _uiState.update { if (it.search.query == query) it.copy(search = PlaceSearch(query, found)) else it } }
                .onFailure { e -> _uiState.update { if (it.search.query == query) it.copy(search = PlaceSearch(query, error = e.orFallback(Res.string.weather_error_search))) else it } }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(search = PlaceSearch()) }
    }

    /** Saves [place] and turns to it. */
    fun addPlace(place: Place) {
        viewModelScope.launch {
            placesRepository.add(place)
            _uiState.update { it.copy(selectedId = place.id, radar = RadarLoad()) }
            preferences.update { it.copy(selectedPlaceId = place.id) }
        }
    }

    fun removePlace(placeId: String) {
        viewModelScope.launch { placesRepository.remove(placeId) }
    }

    /** Moves a saved place one step up or down its list. */
    fun movePlace(placeId: String, up: Boolean) {
        val index = saved.indexOfFirst { it.id == placeId }
        if (index < 0) return
        viewModelScope.launch { placesRepository.move(placeId, if (up) index - 1 else index + 1) }
    }

    // ---- Radar ----------------------------------------------------------------------------------

    /** The radar screen is up ([visible]) for the place in view: fetch its loop and keep it current. */
    fun setRadarVisible(visible: Boolean) {
        radarJob?.cancel()
        radarJob = null
        if (!visible) return
        radarJob = viewModelScope.launch {
            while (isActive) {
                loadRadar(force = false)
                delay(RADAR_REFRESH_MS)
            }
        }
    }

    private suspend fun loadRadar(force: Boolean) {
        val place = _uiState.value.selected?.place ?: return
        if (_uiState.value.radar.timeline == null || force) _uiState.update { it.copy(radar = it.radar.copy(loading = true, error = null)) }
        repository.radar(place.latitude, place.longitude)
            .onSuccess { timeline -> _uiState.update { it.copy(radar = RadarLoad(timeline)) } }
            .onFailure { e -> _uiState.update { it.copy(radar = it.radar.copy(loading = false, error = e.orFallback(Res.string.weather_error_radar))) } }
    }

    // ---- Preferences ----------------------------------------------------------------------------

    fun setUnits(units: WeatherUnits) {
        viewModelScope.launch { preferences.update { it.copy(units = units) } }
    }

    fun setStillSky(still: Boolean) {
        viewModelScope.launch { preferences.update { it.copy(stillSky = still) } }
    }

    /** Changes which notifications are wanted; turning them on asks the OS for permission if it hasn't been given. */
    fun setNotices(settings: WeatherNoticeSettings) {
        viewModelScope.launch {
            preferences.update { it.copy(notices = settings) }
            alertCheck.syncSchedule()
            if (settings.enabled && alertNotifier.isSupported && alertNotifier.permissionStatus() != NotificationPermission.GRANTED) {
                alertNotifier.requestPermission()
            }
            _uiState.update { it.copy(notificationPermission = if (alertNotifier.isSupported) alertNotifier.permissionStatus() else null) }
            if (settings.enabled) alertCheck.run()
        }
    }

    fun requestNotificationPermission() {
        viewModelScope.launch {
            if (!alertNotifier.isSupported) return@launch
            if (alertNotifier.permissionStatus() == NotificationPermission.DENIED) alertNotifier.openSystemSettings() else alertNotifier.requestPermission()
            _uiState.update { it.copy(notificationPermission = alertNotifier.permissionStatus()) }
        }
    }

    private fun sameSpot(a: Place, b: Place) = abs(a.latitude - b.latitude) <= SAME_SPOT_DEGREES && abs(a.longitude - b.longitude) <= SAME_SPOT_DEGREES

    private companion object {
        /** A forecast older than this is asked for again; the model itself moves every quarter hour. */
        const val MAX_AGE_SECONDS = 600L

        /** How often the clock on screen moves, and the forecasts' ages are looked at. */
        const val TICK_MS = 60_000L
        const val RADAR_REFRESH_MS = 150_000L
        const val SEARCH_DEBOUNCE_MS = 320L
        const val SAME_SPOT_DEGREES = 0.02

        /** How long a fix is good for before the app opening again takes another. */
        const val RELOCATE_SECONDS = 300L

        /**
         * What a service said went wrong, when it said; [fallback] otherwise. A dropped connection's
         * own message is the platform's, in English and of no use to the person holding the phone.
         */
        private fun Throwable.orFallback(fallback: StringResource): UiText =
            if (this is LocalizedException) userMessage() else UiText.of(fallback)
    }
}
