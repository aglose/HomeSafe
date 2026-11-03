package com.meticulouscreations.homesafe.weather.ski.domain

import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /** The chain controls in force along a road (a [SkiDrive.path]). Empty where no one publishes them; says so where a file that should have them couldn't be read. */
    suspend fun chainControls(path: String): SkiChainReport

    /** What the resort's own site says about its snow, or null where the app can't read one, or it said nothing. */
    suspend fun resortNews(slug: String, force: Boolean = false): SkiResortNews?

    /** The snow measured at the nearest station to the resort, or null where there's none, or it hasn't said lately. */
    suspend fun measuredSnow(ref: SkiResortRef, facts: SkiResortFacts, force: Boolean = false): SkiMeasuredSnow? = null

    /** Whether the app can read this resort's own site. */
    fun hasResortSite(slug: String): Boolean

    /**
     * The resort's own ticket prices, for these days at least where it sells by the date. Null
     * where it publishes none the app can read, or its shop wouldn't answer. [tripDays] is how long a
     * ticket is wanted, where each length has to be asked after.
     */
    suspend fun resortPrices(slug: String, epochDays: List<Int>, tripDays: Int = 1): SkiResortPrices?

    /** Which resorts' sites have stopped being readable. */
    fun siteHealth(): Flow<SkiSiteHealth> = flowOf(SkiSiteHealth())

    /** What the resort's own site says about parking, where the app knows. */
    fun parking(slug: String): SkiParking? = null

    /** The slugs of the resorts a pass gets into. */
    suspend fun passResorts(pass: SkiPass): Result<Set<String>>
}

/** Brings the tab's data up to date while it isn't open. */
fun interface SkiBackgroundRefresh {
    suspend fun refresh()
}

/** Where a drive starts from, as far as the device knows. */
interface SkiStartingPoint {
    /** Whether this device can tell where it is. A computer can't, so "where I am now" isn't offered there. */
    val canLocate: Boolean get() = true

    /** Asks the person for their location, if the device hasn't got leave to tell. */
    suspend fun requestAccess() = Unit

    suspend fun of(origin: SkiOrigin): SkiPoint?
}
