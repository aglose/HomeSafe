package com.meticulouscreations.homesafe.maps

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.intl.Locale
import com.meticulouscreations.homesafe.data.ApiKeyStore
import com.meticulouscreations.homesafe.data.MAPS_KEYS
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import com.meticulouscreations.homesafe.vault.HouseholdKeys
import com.meticulouscreations.homesafe.vault.note
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.maps_google_rejected
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
import kotlinx.coroutines.launch
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

/**
 * The maps' part of Settings: the provider picked, whether the household has a Google key to use
 * it with, and how that is going. [keysNote] says why the household's keys can't be read or saved
 * just now (signed out, waiting to be let in); [saveError] why the last save didn't take.
 */
@Immutable
data class MapSettings(
    val provider: MapProvider = MapProvider.DEFAULT,
    val hasGoogleKey: Boolean = false,
    val google: GoogleMapsStatus = GoogleMapsStatus.Unchecked,
    val keysNote: UiText? = null,
    val saveError: UiText? = null,
)

/**
 * Which map the app's maps are drawn on. The person picks a provider in Settings (kept on the
 * phone: it is no secret), and for Google pastes in a Google Maps Platform key (the Map Tiles API
 * enabled on it), which goes to the household's key vault on the server, never to the phone's
 * storage ([HouseholdKeys]). [current] turns that into tiles to fetch: Google's under a session it
 * renews before it runs out, or OpenStreetMap's when OpenStreetMap is picked, when there is no
 * key, or while Google refuses, so a map is never left blank by a bad key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(AppScope::class)
class BaseMaps(
    @Named(MAPS_KEYS) private val store: ApiKeyStore,
    private val keys: HouseholdKeys,
    private val google: GoogleMapTiles,
    private val clock: Clock,
) {
    private class Choice(val provider: MapProvider, val key: String?)

    private val provider = MutableStateFlow(MapProvider.fromName(store.string(PREF_PROVIDER)))

    /** Bumped when Google refuses tiles under a session it gave ([tilesRefused]): the maps then work out what to draw again. */
    private val refusals = MutableStateFlow(0)

    /** Until when Google's tiles aren't asked for after a refusal, in epoch seconds. */
    private val refusedUntil = MutableStateFlow(0L)

    private val choice: Flow<Choice> = combine(provider, keys.observe(HouseholdKeys.GOOGLE_MAPS), refusals) { provider, key, _ -> Choice(provider, key) }
    private val status = MutableStateFlow<GoogleMapsStatus>(GoogleMapsStatus.Unchecked)
    private val saveError = MutableStateFlow<UiText?>(null)
    private val sessionLock = Mutex()
    private var session: Pair<String, GoogleMapSession>? = null

    // Lives as long as the app: [current] is shared by every map on screen and outlasts each.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: Flow<MapSettings> = combine(choice, status, keys.state, saveError) { choice, status, vault, saveError ->
        MapSettings(choice.provider, choice.key != null, status, vault.note(), saveError)
    }

    init {
        // A key an early build kept on the phone: the vault has it now, or will once it's typed in again.
        store.saveApiKey(null)
    }

    /** The map to draw now; null only while a Google session is on its way. */
    val current: StateFlow<BaseMap?> = choice
        .transformLatest { choice ->
            val key = choice.key
            if (choice.provider != MapProvider.GOOGLE || key == null) {
                emit(BaseMap.OpenStreetMap)
                return@transformLatest
            }
            // Google refused its tiles a moment ago: OpenStreetMap until it is worth asking again.
            val refusedFor = refusedUntil.value - clock.now().epochSeconds
            if (refusedFor > 0) {
                emit(BaseMap.OpenStreetMap)
                delay(refusedFor.seconds)
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
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial(provider.value, keys.current(HouseholdKeys.GOOGLE_MAPS)))

    fun setProvider(provider: MapProvider) {
        store.saveString(PREF_PROVIDER, provider.name)
        this.provider.value = provider
    }

    /** Seals [key] (blank forgets it) into the household's vault, and drops the session made with the last one. */
    suspend fun setGoogleKey(key: String?): Result<Unit> {
        val kept = key?.trim()?.takeIf { it.isNotEmpty() }
        return keys.save(HouseholdKeys.GOOGLE_MAPS, kept)
            .onSuccess {
                session = null
                status.value = GoogleMapsStatus.Unchecked
                saveError.value = null
            }
            .onFailure { saveError.value = it.userMessage() }
    }

    /**
     * Google answered a tile request at [url] with [status]. A refusal (401, 403: the key revoked or
     * restricted; 429: over quota) under a session it gave means its tiles can't be drawn: the
     * session is dropped, the maps go to OpenStreetMap for a while, and Settings says why. Anything
     * else (a tile that isn't there, OpenStreetMap's own answers) is the tile store's business.
     */
    fun tilesRefused(url: String, status: Int) {
        if (!url.startsWith(GoogleMapTiles.TILES) || status !in REFUSALS) return
        val now = clock.now().epochSeconds
        if (refusedUntil.value > now) return
        refusedUntil.value = now + RETRY.inWholeSeconds
        scope.launch { sessionLock.withLock { session = null } }
        this.status.value = GoogleMapsStatus.Failed(UiText.of(Res.string.maps_google_rejected, status))
        refusals.value++
    }

    /** Asks Google for a session now if Google is picked, so Settings can say whether the key works before a map is opened. */
    suspend fun check() {
        val key = keys.current(HouseholdKeys.GOOGLE_MAPS)
        if (provider.value == MapProvider.GOOGLE && key != null) session(key)
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
        val REFUSALS = setOf(401, 403, 429)

        /** OpenStreetMap at once when that is what will be drawn; null for Google, whose tiles need a session first. */
        fun initial(provider: MapProvider, key: String?): BaseMap? = if (provider == MapProvider.GOOGLE && key != null) null else BaseMap.OpenStreetMap
    }
}
