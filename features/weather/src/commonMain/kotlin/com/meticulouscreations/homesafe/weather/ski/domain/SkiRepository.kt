package com.meticulouscreations.homesafe.weather.ski.domain

import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import kotlinx.coroutines.flow.Flow

/** A place on the map a drive starts from. [city] is its name when it's a city the household added, not home or where the device is. */
data class SkiPoint(val latitude: Double, val longitude: Double, val city: String? = null)

/**
 * The Ski tab's data, each from the service that has it (docs/weather.md says which). Everything
 * is kept on the device: an answer is only asked for again once it's old, and a service that
 * doesn't answer gets its last answer shown, which says how old it is. A failure means nothing
 * has ever been fetched.
 */
interface SkiRepository {
    /** The household's plan as it changes. */
    fun plan(): Flow<SkiPlan>

    suspend fun updatePlan(change: (SkiPlan) -> SkiPlan)

    /** The reports of every resort in a region or on a pass's list. */
    suspend fun reports(regionSlug: String, force: Boolean = false): Result<List<SkiReport>>

    /** Listed ticket prices in a region, by resort slug. */
    suspend fun prices(regionSlug: String): Result<Map<String, SkiTicketPrice>>

    /** A resort's own page. */
    suspend fun resortPage(ref: SkiResortRef, force: Boolean = false): Result<SkiResortPage>

    suspend fun forecast(ref: SkiResortRef, facts: SkiResortFacts, force: Boolean = false): Result<SkiForecast>

    /** Weather warnings in force at the resort, which only the US has a service for. Empty when it can't say. */
    suspend fun alerts(facts: SkiResortFacts): List<WeatherAlert>

    /** The drive between two places: leaving now, or at [departEpochSeconds]. */
    suspend fun drive(from: SkiPoint, to: SkiPoint, departEpochSeconds: Long? = null): Result<SkiDrive>

    /** The chain controls in force along a road (a [SkiDrive.path]). Empty where no one publishes them. */
    suspend fun chainControls(path: String): List<ChainControl>

    /** What the resort's own site says about its snow, or null where the app can't read one, or it said nothing. */
    suspend fun resortNews(slug: String, force: Boolean = false): SkiResortNews?

    /** Whether the app can read this resort's own site. */
    fun hasResortSite(slug: String): Boolean

    /**
     * The resort's own ticket prices, for these days at least where it sells by the date. Null
     * where it publishes none the app can read, or its shop wouldn't answer.
     */
    suspend fun resortPrices(slug: String, epochDays: List<Int>): SkiResortPrices?

    /** The slugs of the resorts a pass gets into. */
    suspend fun passResorts(pass: SkiPass): Result<Set<String>>
}

/** Where a drive starts from, as far as the device knows. */
interface SkiStartingPoint {
    /** Whether this device can tell where it is. A computer can't, so "where I am now" isn't offered there. */
    val canLocate: Boolean get() = true

    suspend fun of(origin: SkiOrigin): SkiPoint?
}
