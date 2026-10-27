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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
    private val google = GoogleMapTiles(
        HttpClient(
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    request.url.encodedPath.endsWith("/createSession") && request.url.parameters["key"] == GOOD_KEY -> {
                        sessions++
                        respond("""{"session": "session-$sessions", "expiry": "${NOW + 14 * 86_400}", "tileWidth": 512}""", HttpStatusCode.OK, json)
                    }

                    request.url.encodedPath.endsWith("/createSession") ->
                        respond("""{"error": {"code": 403, "message": "Map Tiles API has not been used in project 1"}}""", HttpStatusCode.Forbidden, json)

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
        // Past the value from before Google was picked: nothing while Google is asked, then OpenStreetMap once it has said no.
        val seen = withContext(Dispatchers.Default) { withTimeout(5_000) { maps.current.dropWhile { it != null }.take(2).toList() } }
        assertEquals(listOf(null, BaseMap.OpenStreetMap), seen)

        maps.setGoogleKey(null)
        assertEquals(MapSettings(MapProvider.GOOGLE, hasGoogleKey = false, google = GoogleMapsStatus.Unchecked), maps.settings.first(), "forgetting the key clears what was said about it")
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
