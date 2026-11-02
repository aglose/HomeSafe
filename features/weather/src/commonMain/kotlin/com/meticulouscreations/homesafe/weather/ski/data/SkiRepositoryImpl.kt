package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.SkiDao
import com.meticulouscreations.homesafe.data.SkiKeptEntity
import com.meticulouscreations.homesafe.finance.data.suspendRunCatching
import com.meticulouscreations.homesafe.vault.HouseholdKeys
import com.meticulouscreations.homesafe.weather.data.NwsApi
import com.meticulouscreations.homesafe.weather.data.WeatherLocator
import com.meticulouscreations.homesafe.weather.data.weatherJson
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherPlacesRepository
import com.meticulouscreations.homesafe.weather.ski.domain.ChainControl
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDayPrice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDrive
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOrigin
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortFacts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPage
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPrices
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiStartingPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SkiRepositoryImpl(
    private val onTheSnow: OnTheSnowApi,
    private val forecasts: SkiForecastApi,
    private val routes: SkiRoutesApi,
    private val chains: CaltransChainApi,
    private val sites: ResortSiteApi,
    private val browser: SkiBrowser,
    private val nws: NwsApi,
    private val keys: HouseholdKeys,
    private val dao: SkiDao,
    private val clock: Clock,
) : SkiRepository {
    private fun now(): Long = clock.now().epochSeconds

    private val planLock = Mutex()
    private val locks = HashMap<String, Mutex>()
    private val guard = Mutex()
    private val districtsHeld = HashMap<Int, Pair<Long, List<ChainControl>>>()

    /** The key was refused routes this session (it may only be allowed map tiles): don't ask on every drive. */
    private var routesRefused = false

    override fun plan(): Flow<SkiPlan> = dao.observe(PLAN_KEY).map { row -> row?.let(::readPlan) ?: SkiPlan() }

    private fun readPlan(row: SkiKeptEntity): SkiPlan = runCatching { weatherJson.decodeFromString(SkiPlan.serializer(), row.json) }.getOrDefault(SkiPlan())

    override suspend fun updatePlan(change: (SkiPlan) -> SkiPlan) = planLock.withLock {
        val current = dao.kept(PLAN_KEY)?.let(::readPlan) ?: SkiPlan()
        val next = change(current)
        if (next != current) dao.keep(SkiKeptEntity(PLAN_KEY, weatherJson.encodeToString(SkiPlan.serializer(), next), now()))
    }

    /**
     * What's kept under [key] if it's younger than [maxAgeSeconds] (unless [force]d); otherwise the
     * service's answer, kept for next time. A service that fails gets the last answer stood in for
     * it, however old: every one of these says when it's from.
     */
    private suspend fun <T> cached(key: String, serializer: KSerializer<T>, maxAgeSeconds: Long, force: Boolean, fetch: suspend () -> Result<T>): Result<T> {
        val lock = guard.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val row = suspendRunCatching { dao.kept(key) }.getOrNull()
            val held = row?.let { kept -> withContext(Dispatchers.Default) { runCatching { weatherJson.decodeFromString(serializer, kept.json) }.getOrNull() } }
            if (!force && held != null && row.savedAtEpochSeconds + maxAgeSeconds > now()) return@withLock Result.success(held)
            val fetched = fetch()
            fetched.onSuccess { value ->
                suspendRunCatching {
                    val json = withContext(Dispatchers.Default) { weatherJson.encodeToString(serializer, value) }
                    dao.keep(SkiKeptEntity(key, json, now()))
                }
            }
            if (fetched.isFailure && held != null) Result.success(held) else fetched
        }
    }

    override suspend fun reports(regionSlug: String, force: Boolean): Result<List<SkiReport>> =
        cached("reports:$regionSlug", ListSerializer(SkiReport.serializer()), REPORT_MAX_AGE, force) { onTheSnow.reports(regionSlug) }

    override suspend fun prices(regionSlug: String): Result<Map<String, SkiTicketPrice>> =
        cached("prices:$regionSlug", MapSerializer(String.serializer(), SkiTicketPrice.serializer()), PRICE_MAX_AGE, force = false) { onTheSnow.prices(regionSlug) }

    override suspend fun resortPage(ref: SkiResortRef, force: Boolean): Result<SkiResortPage> =
        cached("resort:${ref.slug}", SkiResortPage.serializer(), RESORT_MAX_AGE, force) { onTheSnow.resortPage(ref, now()) }

    override suspend fun forecast(ref: SkiResortRef, facts: SkiResortFacts, force: Boolean): Result<SkiForecast> =
        cached("forecast:${ref.slug}", SkiForecast.serializer(), FORECAST_MAX_AGE, force) { forecasts.forecast(facts, now()) }

    override suspend fun alerts(facts: SkiResortFacts): List<WeatherAlert> {
        if (!NwsApi.covers(facts.latitude, facts.longitude)) return emptyList()
        return nws.alerts(facts.latitude, facts.longitude, ALERTS_TIMEOUT_MS).getOrNull().orEmpty()
    }

    override suspend fun drive(from: SkiPoint, to: SkiPoint, departEpochSeconds: Long?): Result<SkiDrive> {
        val trip = "${roundedKey(from)}>${roundedKey(to)}"
        val key = keys.current(HouseholdKeys.GOOGLE_MAPS)?.takeIf { !routesRefused }
        if (key != null) {
            val traffic = cached("drive:$trip:${departEpochSeconds ?: "now"}", SkiDrive.serializer(), TRAFFIC_MAX_AGE, force = false) {
                routes.withTraffic(from, to, departEpochSeconds, key)?.let { Result.success(it) } ?: Result.failure(RoutesRefused)
            }
            if (traffic.isSuccess) return traffic
            routesRefused = true
        }
        // An empty road is the same at any hour, and takes the same time for long enough to keep it.
        return cached("road:$trip", SkiDrive.serializer(), ROAD_MAX_AGE, force = false) { routes.freeFlow(from, to) }
    }

    private object RoutesRefused : RuntimeException("Routes refused")

    private fun roundedKey(point: SkiPoint) = "${(point.latitude * 100).toInt()},${(point.longitude * 100).toInt()}"

    override suspend fun chainControls(path: String): List<ChainControl> {
        if (path.isEmpty()) return emptyList()
        val road = withContext(Dispatchers.Default) { decodePolyline(path) }
        val box = boxAround(road) ?: return emptyList()
        val inForce = coroutineScope { chains.districtsAround(box).map { async { district(it) } }.awaitAll().flatten() }
        return withContext(Dispatchers.Default) { inForce.filter { passesNear(road, it.latitude, it.longitude) } }
    }

    /** One district's file, held for a while: several resorts' roads run through the same ones, and each file is a couple of hundred kilobytes. */
    private suspend fun district(number: Int): List<ChainControl> {
        val lock = guard.withLock { locks.getOrPut("district:$number") { Mutex() } }
        return lock.withLock {
            guard.withLock { districtsHeld[number] }?.takeIf { (at, _) -> now() - at < CHAINS_MAX_AGE }?.second
                ?: suspendRunCatching { chains.district(number) }.getOrDefault(emptyList()).also { fetched -> guard.withLock { districtsHeld[number] = now() to fetched } }
        }
    }

    override fun hasResortSite(slug: String): Boolean = slug in ResortSite.ALL

    override suspend fun resortNews(slug: String, force: Boolean): SkiResortNews? {
        if (!hasResortSite(slug)) return null
        return cached("news:$slug", SkiResortNews.serializer(), NEWS_MAX_AGE, force) {
            suspendRunCatching { sites.news(slug) ?: throw NoAnswer }
        }.getOrNull()
    }

    private object NoAnswer : RuntimeException("The resort's site said nothing")

    /**
     * Its rate table from a plain page, its dated prices from its shop through the browser. What the
     * shop gave is kept for a day and only asked for again if a day is wanted that isn't in it: a
     * trip of the browser is a whole page.
     */
    override suspend fun resortPrices(slug: String, epochDays: List<Int>): SkiResortPrices? {
        val site = ResortSite.ALL[slug] ?: return null
        val key = "site-prices:$slug"
        val lock = guard.withLock { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val row = suspendRunCatching { dao.kept(key) }.getOrNull()
            val held = row?.let { kept -> withContext(Dispatchers.Default) { runCatching { weatherJson.decodeFromString(SkiResortPrices.serializer(), kept.json) }.getOrNull() } }
            val fresh = held?.takeIf { row.savedAtEpochSeconds + SITE_PRICE_MAX_AGE > now() }
            val datedByDay = site is ResortSite.Vail
            if (fresh != null && (!datedByDay || epochDays.all { it in fresh.daysAsked })) return@withLock fresh
            val table = suspendRunCatching { sites.priceTable(slug) }.getOrDefault(emptyList())
            val dated = datedPrices(site, epochDays)
            if (table.isEmpty() && dated.isEmpty()) return@withLock held
            val prices = SkiResortPrices(
                pageUrl = site.ticketsUrl.orEmpty(),
                days = fresh?.days.orEmpty() + dated,
                tiers = table.ifEmpty { fresh?.tiers.orEmpty() },
                fetchedAtEpochSeconds = now(),
                // Only if the shop answered at all: a browser that got nowhere has learned nothing about these days.
                daysAsked = fresh?.daysAsked.orEmpty() + if (dated.isEmpty()) emptyList() else epochDays,
            )
            suspendRunCatching { dao.keep(SkiKeptEntity(key, weatherJson.encodeToString(SkiResortPrices.serializer(), prices), now())) }
            prices
        }
    }

    private suspend fun datedPrices(site: ResortSite, epochDays: List<Int>): Map<Int, SkiDayPrice> {
        if (!browser.isSupported) return emptyMap()
        val answer = when (site) {
            is ResortSite.Vail -> browser.read(site.ticketsUrl, ResortScripts.vail(epochDays.map { LocalDate.fromEpochDays(it).toString() }))
            is ResortSite.Feed -> site.shop?.let { browser.read(it.url, ResortScripts.shop(it.categorySlug, it.productPattern)) }
            is ResortSite.Powdr -> site.shop?.let { browser.read(it.url, ResortScripts.shop(it.categorySlug, it.productPattern)) }
            is ResortSite.Pages -> null
        }
        return answer?.let { withContext(Dispatchers.Default) { ResortPages.datedPrices(it) } }.orEmpty()
    }

    override suspend fun passResorts(pass: SkiPass): Result<Set<String>> =
        cached("pass:${pass.slug}", SetSerializer(String.serializer()), PASS_MAX_AGE, force = false) {
            onTheSnow.reports(pass.slug).map { reports -> reports.mapTo(HashSet()) { it.slug } }
        }

    private companion object {
        const val PLAN_KEY = "plan"
        const val REPORT_MAX_AGE = 20 * 60L
        const val FORECAST_MAX_AGE = 60 * 60L
        const val TRAFFIC_MAX_AGE = 15 * 60L

        /** A resort's height and whereabouts don't change; its cameras are refreshed by the page being opened, which forces this. */
        const val RESORT_MAX_AGE = 14 * 86_400L
        const val PRICE_MAX_AGE = 3 * 86_400L
        const val ROAD_MAX_AGE = 14 * 86_400L
        const val PASS_MAX_AGE = 30 * 86_400L

        /** Resorts change their reports in the morning and a few times through a storm. */
        const val NEWS_MAX_AGE = 15 * 60L

        /** Prices move as a date comes nearer, but a day at a time. */
        const val SITE_PRICE_MAX_AGE = 86_400L
        const val CHAINS_MAX_AGE = 10 * 60L
        const val ALERTS_TIMEOUT_MS = 4_000L
    }
}

/**
 * Home if the household has one set (its position from the relay), otherwise where the device is
 * or last was. A device that has never known either, like a Mac nobody has signed in on, goes by
 * the first city added to the weather, and says so.
 */
@Inject
@ContributesBinding(AppScope::class)
class SkiStartingPointImpl(
    private val identity: DeviceIdentityStore,
    private val locator: WeatherLocator,
    private val places: WeatherPlacesRepository,
) : SkiStartingPoint {
    override val canLocate: Boolean get() = locator.isSupported

    override suspend fun of(origin: SkiOrigin): SkiPoint? {
        if (origin == SkiOrigin.HERE) {
            suspendRunCatching { locator.locate() }.getOrNull()?.let { return SkiPoint(it.latitude, it.longitude) }
        }
        suspendRunCatching { identity.cachedHome() }.getOrNull()?.let { return SkiPoint(it.latitude, it.longitude) }
        suspendRunCatching { locator.remembered() }.getOrNull()?.let { return SkiPoint(it.latitude, it.longitude) }
        val city = suspendRunCatching { places.observe().first() }.getOrNull()?.firstOrNull { !it.isCurrentLocation } ?: return null
        return SkiPoint(city.latitude, city.longitude, city.name)
    }
}
