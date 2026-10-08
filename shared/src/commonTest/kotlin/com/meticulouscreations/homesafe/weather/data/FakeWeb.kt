package com.meticulouscreations.homesafe.weather.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The weather services on the other end of a [MockEngine]: routes are matched by a piece of the
 * request's URL, in the order they were added (a later [route] for the same piece replaces the
 * earlier), and anything unrouted is a 404. Every request is kept in [requests].
 */
internal class FakeWeb {
    class Reply(val status: HttpStatusCode = HttpStatusCode.OK, val body: String = "", val failure: Throwable? = null)

    private val routes = LinkedHashMap<String, () -> Reply>()
    private val lock = Mutex()
    private val seen = mutableListOf<HttpRequestData>()

    /** Every request made so far, oldest first. */
    val requests: List<HttpRequestData> get() = seen.toList()

    fun route(urlPart: String, reply: () -> Reply): FakeWeb = apply { routes[urlPart] = reply }

    fun route(urlPart: String, body: String, status: HttpStatusCode = HttpStatusCode.OK): FakeWeb = route(urlPart) { Reply(status, body) }

    fun fail(urlPart: String, status: HttpStatusCode): FakeWeb = route(urlPart) { Reply(status) }

    fun count(urlPart: String): Int = requests.count { it.url.toString().contains(urlPart) }

    fun requestsTo(urlPart: String): List<HttpRequestData> = requests.filter { it.url.toString().contains(urlPart) }

    val client: HttpClient = HttpClient(
        MockEngine { request ->
            lock.withLock { seen += request }
            val reply = routes.entries.firstOrNull { request.url.toString().contains(it.key) }?.value?.invoke() ?: Reply(HttpStatusCode.NotFound)
            reply.failure?.let { throw it }
            respond(reply.body, reply.status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
}

/** A clock the test moves by hand. */
internal class MutableClock(var seconds: Long) : kotlin.time.Clock {
    override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochSeconds(seconds)
}

/** Bodies the services answer with, small but whole enough for the clients to read. */
internal object WeatherPayloads {
    /** Midnight on 9 October 2025 in Portland. */
    const val MIDNIGHT = 1_759_993_200L

    /** A forecast with [temperatureC] now, one hour and one day. */
    fun forecast(temperatureC: Double = 15.0, currentEpoch: Long = MIDNIGHT + 15 * 3_600L): String = """
        {
          "utc_offset_seconds": -25200, "timezone": "America/Los_Angeles",
          "current": {"time": $currentEpoch, "temperature_2m": $temperatureC, "apparent_temperature": $temperatureC, "weather_code": 3, "is_day": 1},
          "minutely_15": {"time": [$currentEpoch], "precipitation": [0.0], "snowfall": [0.0]},
          "hourly": {"time": [$currentEpoch], "temperature_2m": [$temperatureC]},
          "daily": {"time": [$MIDNIGHT], "temperature_2m_max": [${temperatureC + 3}], "temperature_2m_min": [${temperatureC - 5}]}
        }
    """.trimIndent()

    fun air(aqi: Int = 42): String = """{"current": {"us_aqi": $aqi}}"""

    const val ALERTS = """
        {"features": [{"properties": {"id": "urn:1", "event": "Wind Advisory", "headline": "Wind Advisory until 4 AM", "severity": "Moderate"}}]}
    """

    const val POINT = """{"properties": {"relativeLocation": {"properties": {"city": "Portland", "state": "OR"}}}}"""

    const val SEARCH = """
        {"results": [{"name": "Seattle", "latitude": 47.6062, "longitude": -122.3321, "country": "United States", "admin1": "Washington"}]}
    """

    const val MRMS_INDEX = """{"meta": {"end_valid": "2025-10-09T22:07:00Z"}}"""
    const val HRRR_INDEX = """{"model_init_utc": "2025-10-09T20:00:00Z"}"""
    const val RAINVIEWER_INDEX = """{"host": "https://tilecache.rainviewer.com", "radar": {"past": [{"time": 1759999800, "path": "/v2/radar/1759999800"}]}}"""

    /** Every service up, answering with the bodies above. */
    fun everythingUp(temperatureC: Double = 15.0): FakeWeb = FakeWeb()
        .route("/v1/forecast", forecast(temperatureC))
        .route("/v1/air-quality", air())
        .route("/alerts/active", ALERTS)
        .route("/points/", POINT)
        .route("/v1/search", SEARCH)
        .route("mrms/lcref.json", MRMS_INDEX)
        .route("hrrr/refd_0000.json", HRRR_INDEX)
        .route("weather-maps.json", RAINVIEWER_INDEX)
}
