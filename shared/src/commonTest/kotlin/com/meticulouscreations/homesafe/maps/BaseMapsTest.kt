package com.meticulouscreations.homesafe.maps

import com.meticulouscreations.homesafe.data.InMemoryApiKeyStore
import com.meticulouscreations.homesafe.vault.HouseholdKeys
import com.meticulouscreations.homesafe.vault.InMemoryHouseholdKeys
import com.meticulouscreations.homesafe.vault.VaultState
import com.meticulouscreations.homesafe.vault.note
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** [BaseMaps]: the provider picked and Google's key turned into tiles to fetch, and back to OpenStreetMap when Google won't. */
class BaseMapsTest {

    private val clock = object : Clock {
        override fun now() = Instant.fromEpochSeconds(NOW)
    }

    /** Google's Map Tiles API, answering sessions for [GOOD_KEY] and refusing any other key; [sessions] counts what was asked. */
    private var sessions = 0

    /** Sessions Google refused. Updated from the HTTP engine's threads, so waited on as a flow. */
    private val refused = MutableStateFlow(0)
    private val google = GoogleMapTiles(
        HttpClient(
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    request.url.encodedPath.endsWith("/createSession") && request.url.parameters["key"] == GOOD_KEY -> {
                        sessions++
                        respond("""{"session": "session-$sessions", "expiry": "${NOW + 14 * 86_400}", "tileWidth": 512}""", HttpStatusCode.OK, json)
                    }

                    request.url.encodedPath.endsWith("/createSession") -> {
                        refused.value++
                        respond("""{"error": {"code": 403, "message": "Map Tiles API has not been used in project 1"}}""", HttpStatusCode.Forbidden, json)
                    }

                    request.url.encodedPath.endsWith("/viewport") -> respond("""{"copyright": "Map data ©2026 Google"}""", HttpStatusCode.OK, json)

                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ),
    )

    /** What [BaseMaps.current] comes to once it has worked [wanted] out. */
    private suspend fun BaseMaps.settled(wanted: (BaseMap?) -> Boolean): BaseMap? = withContext(Dispatchers.Default) { withTimeout(5_000) { current.first(wanted) } }

    @Test
    fun openStreetMapUntilGoogleIsPicked() = runTest {
        val maps = BaseMaps(InMemoryApiKeyStore(), InMemoryHouseholdKeys(), google, clock)
        assertEquals(BaseMap.OpenStreetMap, maps.current.value, "known at once: nothing to ask")
        assertEquals(MapSettings(), maps.settings.first())
        maps.setProvider(MapProvider.GOOGLE)
        assertEquals(BaseMap.OpenStreetMap, maps.settled { it != null }, "Google picked but no key yet")
        assertEquals(0, sessions)
    }

    @Test
    fun aGoodKeyDrawsGooglesTilesUnderOneSession() = runTest {
        val store = InMemoryApiKeyStore()
        val keys = InMemoryHouseholdKeys()
        val maps = BaseMaps(store, keys, google, clock)
        maps.setProvider(MapProvider.GOOGLE)
        assertTrue(maps.setGoogleKey("  $GOOD_KEY ").isSuccess)
        assertEquals(GOOD_KEY, keys.current(HouseholdKeys.GOOGLE_MAPS), "kept trimmed, in the household's vault")
        assertNull(store.apiKey(), "and not on the phone")
        maps.check()
        assertEquals(MapSettings(MapProvider.GOOGLE, hasGoogleKey = true, google = GoogleMapsStatus.Working), maps.settings.first())
        val map = assertIs<BaseMap.Google>(maps.settled { it is BaseMap.Google })
        assertEquals("https://tile.googleapis.com/v1/2dtiles/12/654/1583?session=session-1&key=$GOOD_KEY", map.url(12, 654, 1583))
        assertEquals(false, map.keep, "Google's tiles are never saved on the phone")
        assertTrue(GOOD_KEY !in map.toString(), "the key stays out of logs")
        assertEquals(1, sessions, "Settings' check and the map share the session")
        assertEquals("Map data ©2026 Google", maps.copyright(map, 12, 41.0, 40.0, -88.0, -89.0))

        // A new instance (the app started again) reads the choice and the key back.
        assertEquals(MapProvider.GOOGLE, BaseMaps(store, keys, google, clock).settings.first().provider)
    }

    @Test
    fun aRefusedKeyFallsBackToOpenStreetMapAndSaysWhy() = runTest {
        val maps = BaseMaps(InMemoryApiKeyStore(), InMemoryHouseholdKeys(), google, clock)
        maps.setProvider(MapProvider.GOOGLE)
        maps.setGoogleKey("not-a-key")
        maps.check()
        assertIs<GoogleMapsStatus.Failed>(maps.settings.first().google)
        // The map asks Google again for itself, is refused, and settles on OpenStreetMap. (Only what it
        // settles on is checked: a StateFlow may skip the moment of nothing while it asks.)
        val drawn = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                coroutineScope {
                    val watching = launch { maps.current.collect {} }
                    refused.first { it >= 2 }
                    maps.current.first { it != null }.also { watching.cancel() }
                }
            }
        }
        assertEquals(BaseMap.OpenStreetMap, drawn)

        maps.setGoogleKey(null)
        assertEquals(MapSettings(MapProvider.GOOGLE, hasGoogleKey = false, google = GoogleMapsStatus.Unchecked), maps.settings.first(), "forgetting the key clears what was said about it")
    }

    @Test
    fun aKeyForgottenWhileGoogleIsAskedLeavesNothingSaidAboutIt() = runTest {
        // Google holds its answer back until the key has been forgotten, then refuses it. A second
        // check waits its turn behind the first meanwhile.
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val slowGoogle = GoogleMapTiles(
            HttpClient(
                MockEngine {
                    asked.complete(Unit)
                    answer.await()
                    respond("""{"error": {"code": 403, "message": "API key not valid"}}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )
        val maps = BaseMaps(InMemoryApiKeyStore(), InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to "not-a-key"), slowGoogle, clock)
        maps.setProvider(MapProvider.GOOGLE)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                coroutineScope {
                    val checking = launch { maps.check() }
                    asked.await()
                    assertEquals(GoogleMapsStatus.Checking, maps.settings.first().google)
                    // Runs until it waits for the first check, the key it checks already read.
                    val queued = launch(start = CoroutineStart.UNDISPATCHED) { maps.check() }
                    maps.setGoogleKey(null)
                    answer.complete(Unit)
                    checking.join()
                    queued.join()
                }
            }
        }
        assertEquals(GoogleMapsStatus.Unchecked, maps.settings.first().google, "what was said about the forgotten key stays cleared")
    }

    @Test
    fun googleRefusingItsTilesMovesTheMapsToOpenStreetMap() = runTest {
        val maps = BaseMaps(InMemoryApiKeyStore(), InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to GOOD_KEY), google, clock)
        maps.setProvider(MapProvider.GOOGLE)
        val map = assertIs<BaseMap.Google>(maps.settled { it is BaseMap.Google })

        maps.tilesRefused("https://tile.openstreetmap.org/12/654/1583.png", 403)
        maps.tilesRefused(map.url(12, 654, 1583), 404)
        assertEquals(GoogleMapsStatus.Working, maps.settings.first().google, "only Google refusing its own tiles counts")

        maps.tilesRefused(map.url(12, 654, 1583), 403)
        assertEquals(BaseMap.OpenStreetMap, maps.settled { it == BaseMap.OpenStreetMap })
        assertIs<GoogleMapsStatus.Failed>(maps.settings.first().google)
    }

    @Test
    fun aClosedVaultKeepsTheMapsOnOpenStreetMapAndSaysWhy() = runTest {
        val keys = InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to GOOD_KEY).apply { set(VaultState.Waiting) }
        val maps = BaseMaps(InMemoryApiKeyStore(), keys, google, clock)
        maps.setProvider(MapProvider.GOOGLE)
        assertTrue(maps.setGoogleKey(GOOD_KEY).isFailure)
        val settings = maps.settings.first()
        assertEquals(false, settings.hasGoogleKey)
        assertEquals(VaultState.Waiting.note(), settings.keysNote)
        assertEquals(BaseMap.OpenStreetMap, maps.settled { it != null })
        assertEquals(0, sessions)
    }

    private companion object {
        const val NOW = 1_793_894_400L
        const val GOOD_KEY = "AIza-test-key"
    }
}
