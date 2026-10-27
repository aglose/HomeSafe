package com.meticulouscreations.homesafe.maps

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.intl.Locale
import com.meticulouscreations.homesafe.data.ApiKeyStore
import com.meticulouscreations.homesafe.data.MAPS_KEYS
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.maps_google_unreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How Google's map is getting on, for Settings to say. */
sealed interface GoogleMapsStatus {
    /** Not asked yet: nothing has wanted a map since the key was given. */
    data object Unchecked : GoogleMapsStatus

    data object Checking : GoogleMapsStatus

    /** Google gave a session: the maps are drawn from its tiles. */
    data object Working : GoogleMapsStatus

    /** Google refused, or couldn't be reached; the maps are on OpenStreetMap meanwhile. */
    data class Failed(val reason: UiText) : GoogleMapsStatus
}

/** The maps' part of Settings: the provider picked, whether there is a Google key to use it with, and how that is going. */
@Immutable
data class MapSettings(
    val provider: MapProvider = MapProvider.DEFAULT,
    val hasGoogleKey: Boolean = false,
    val google: GoogleMapsStatus = GoogleMapsStatus.Unchecked,
)

/**
 * Which map the app's maps are drawn on. The person picks a provider in Settings, and for Google
 * pastes in a Google Maps Platform key of their own (the Map Tiles API enabled on it), kept with
 * the platform's secrets. [current] turns that into tiles to fetch: Google's under a session it
 * renews before it runs out, or OpenStreetMap's when OpenStreetMap is picked, when there is no
 * key, or while Google refuses, so a map is never left blank by a bad key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(AppScope::class)
class BaseMaps(
    @Named(MAPS_KEYS) private val store: ApiKeyStore,
    private val google: GoogleMapTiles,
    private val clock: Clock,
) {
    private class Choice(val provider: MapProvider, val key: String?)

    private val choice = MutableStateFlow(Choice(MapProvider.fromName(store.string(PREF_PROVIDER)), store.apiKey()))
    private val status = MutableStateFlow<GoogleMapsStatus>(GoogleMapsStatus.Unchecked)
    private val sessionLock = Mutex()
    private var session: Pair<String, GoogleMapSession>? = null

    // Lives as long as the app: [current] is shared by every map on screen and outlasts each.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: Flow<MapSettings> = combine(choice, status) { choice, status -> MapSettings(choice.provider, choice.key != null, status) }

    /** The map to draw now; null only while a Google session is on its way. */
    val current: StateFlow<BaseMap?> = choice
        .transformLatest { choice ->
            val key = choice.key
            if (choice.provider != MapProvider.GOOGLE || key == null) {
                emit(BaseMap.OpenStreetMap)
                return@transformLatest
            }
            // Nothing to draw while a session is on its way, rather than OpenStreetMap's tiles to be thrown away.
            if (!hasSession(key)) emit(null)
            while (true) {
                val session = session(key)
                if (session == null) {
                    emit(BaseMap.OpenStreetMap)
                    delay(RETRY)
                    continue
                }
                emit(BaseMap.Google(session.token, key))
                // A new session a while before this one runs out, so no tile is asked for under a dead one.
                delay((session.expiresAtEpochSeconds - clock.now().epochSeconds - RENEW_AHEAD_SECONDS).coerceAtLeast(60L).seconds)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial(choice.value))

    fun setProvider(provider: MapProvider) {
        store.saveString(PREF_PROVIDER, provider.name)
        choice.update { Choice(provider, it.key) }
    }

    /** Keeps [key] (blank forgets it) and drops the session made with the last one. */
    fun setGoogleKey(key: String?) {
        val kept = key?.trim()?.takeIf { it.isNotEmpty() }
        store.saveApiKey(kept)
        session = null
        status.value = GoogleMapsStatus.Unchecked
        choice.update { Choice(it.provider, kept) }
    }

    /** Asks Google for a session now if Google is picked, so Settings can say whether the key works before a map is opened. */
    suspend fun check() {
        val choice = choice.value
        if (choice.provider == MapProvider.GOOGLE && choice.key != null) session(choice.key)
    }

    /** The copyright [map] must show over the given view (see [GoogleMapTiles.copyright]); null when Google can't say just now. */
    suspend fun copyright(map: BaseMap.Google, zoom: Int, north: Double, south: Double, east: Double, west: Double): String? = try {
        google.copyright(map, zoom, north, south, east, west).takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun hasSession(key: String): Boolean = session?.let { (madeWith, kept) -> madeWith == key && kept.expiresAtEpochSeconds - clock.now().epochSeconds > RENEW_AHEAD_SECONDS } == true

    private suspend fun session(key: String): GoogleMapSession? = sessionLock.withLock {
        if (hasSession(key)) return@withLock session?.second
        status.value = GoogleMapsStatus.Checking
        val locale = Locale.current
        try {
            google.createSession(key, locale.toLanguageTag(), locale.region.takeIf { it.length == 2 } ?: FALLBACK_REGION).also {
                session = key to it
                status.value = GoogleMapsStatus.Working
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalizedException) {
            status.value = GoogleMapsStatus.Failed(e.text)
            null
        } catch (_: Exception) {
            status.value = GoogleMapsStatus.Failed(UiText.of(Res.string.maps_google_unreachable))
            null
        }
    }

    private companion object {
        const val PREF_PROVIDER = "provider"
        const val FALLBACK_REGION = "US"
        const val RENEW_AHEAD_SECONDS = 3_600L
        val RETRY = 5.minutes

        /** OpenStreetMap at once when that is what will be drawn; null for Google, whose tiles need a session first. */
        fun initial(choice: Choice): BaseMap? = if (choice.provider == MapProvider.GOOGLE && choice.key != null) null else BaseMap.OpenStreetMap
    }
}
