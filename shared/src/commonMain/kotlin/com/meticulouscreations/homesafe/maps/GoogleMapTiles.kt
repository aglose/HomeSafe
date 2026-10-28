package com.meticulouscreations.homesafe.maps

import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.maps_google_rejected
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The client for Google's Map Tiles API. Its own instance, as the weather and assistant's are:
 * Frigate's session cookies never ride along to a third party. The tiles themselves are fetched
 * by the weather app's tile store, with the rest of the map.
 */
@ContributesTo(AppScope::class)
interface MapsProviders {
    @Named(MAPS_CLIENT)
    @SingleIn(AppScope::class)
    @Provides
    fun provideMapsHttpClient(): HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }
}

const val MAPS_CLIENT = "maps"

/** A Map Tiles API session: every tile asked for names it, until [expiresAtEpochSeconds] (about two weeks on). */
class GoogleMapSession(val token: String, val expiresAtEpochSeconds: Long)

/** Google answered, but not with what was asked: the key is wrong, or the Map Tiles API isn't enabled for it. */
class GoogleMapsException(val status: Int, technical: String) : LocalizedException(UiText.of(Res.string.maps_google_rejected, status), technical)

/**
 * Google's Map Tiles API (https://developers.google.com/maps/documentation/tile): the sessions
 * its 2D tiles are fetched under, and the copyright line a view of them has to show. Billed to
 * the key; a session costs nothing, a tile is a fraction of a cent past the monthly free ones.
 */
@Inject
@SingleIn(AppScope::class)
class GoogleMapTiles(@Named(MAPS_CLIENT) private val httpClient: HttpClient) {

    /** A new roadmap session for [key], labelled in [language] (an IETF tag) and drawn for [region] (a CLDR region, e.g. "US"). */
    suspend fun createSession(key: String, language: String, region: String): GoogleMapSession {
        val request = SessionRequest(
            mapType = "roadmap",
            language = language,
            region = region,
            // Twice the pixels for the same patch of ground: the tiles are drawn on dense screens.
            scale = "scaleFactor2x",
            highDpi = true,
        )
        val response = httpClient.post("$BASE/v1/createSession") {
            parameter("key", key)
            setBody(TextContent(json.encodeToString(SessionRequest.serializer(), request), ContentType.Application.Json))
        }
        val text = response.checked()
        val session = json.decodeFromString(SessionResponse.serializer(), text)
        return GoogleMapSession(session.session, session.expiry.toLongOrNull() ?: 0L)
    }

    /**
     * The copyright line for a view of [map] at [zoom] over the given box, in degrees: "Map data
     * ©2026 Google", and whoever else's data is in view. Data, not copy; Google words it.
     */
    suspend fun copyright(map: BaseMap.Google, zoom: Int, north: Double, south: Double, east: Double, west: Double): String {
        val response = httpClient.get("$BASE/tile/v1/viewport") {
            parameter("session", map.session)
            parameter("key", map.key)
            parameter("zoom", zoom)
            parameter("north", north)
            parameter("south", south)
            parameter("east", east)
            parameter("west", west)
        }
        return json.decodeFromString(ViewportResponse.serializer(), response.checked()).copyright
    }

    private suspend fun HttpResponse.checked(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            val message = runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error.message }.getOrNull()
            throw GoogleMapsException(status.value, "HTTP ${status.value}: ${message.orEmpty()}")
        }
        return text
    }

    companion object {
        const val BASE = "https://tile.googleapis.com"

        /** Where the 2D tiles are: what [BaseMaps.tilesRefused] recognises a Google tile by. */
        const val TILES = "$BASE/v1/2dtiles/"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

@Serializable
private class SessionRequest(val mapType: String, val language: String, val region: String, val scale: String, val highDpi: Boolean)

@Serializable
private class SessionResponse(val session: String, val expiry: String = "")

@Serializable
private class ViewportResponse(val copyright: String = "")

@Serializable
private class ErrorResponse(val error: ErrorBody = ErrorBody())

@Serializable
private class ErrorBody(val message: String = "")
