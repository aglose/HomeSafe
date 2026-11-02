package com.meticulouscreations.homesafe.weather.ski

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import com.meticulouscreations.homesafe.weather.data.WEATHER_CLIENT
import com.meticulouscreations.homesafe.weather.ski.domain.SkiAlerts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiEntry
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOrigin
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiStartingPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_error_drive
import homesafe.shared.generated.resources.ski_error_report
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

/** The reports and prices of one region, for looking through its resorts. */
@Immutable
data class SkiRegionLoad(
    val reports: List<SkiReport> = emptyList(),
    val prices: Map<String, SkiTicketPrice> = emptyMap(),
    /** What the resorts' own sites say, as each answers: it goes over the listing in [reports]. */
    val news: Map<String, SkiResortNews> = emptyMap(),
    val loading: Boolean = false,
    val error: UiText? = null,
)

@Immutable
data class SkiUiState(
    val plan: SkiPlan = SkiPlan(),
    /** The followed resorts and any other that's been opened, by slug. */
    val entries: Map<String, SkiEntry> = emptyMap(),
    val regions: Map<String, SkiRegionLoad> = emptyMap(),
    val nowEpochSeconds: Long = 0,
    val todayEpochDay: Int = 0,
    /** The day being planned for. */
    val tripEpochDay: Int = 0,
    val refreshing: Boolean = false,
    /** Where drives start is known. Without it there's nothing to time. */
    val hasStart: Boolean = true,
    /** The city drives start from, when that's what they go by because there's no home or position to use. */
    val startCity: String? = null,
    /** This device can tell where it is. */
    val canLocate: Boolean = true,
    /** Resorts' sites that have stopped being readable, and since when, by slug. */
    val siteTrouble: Map<String, Long> = emptyMap(),
) {
    val followed: List<SkiEntry> get() = plan.resorts.map { entries[it.slug] ?: SkiEntry(it) }
}

/**
 * The Ski tab's state: the followed resorts and everything known about each, the day being planned
 * for, and whichever region is being looked through. Nothing is fetched until the tab is up
 * ([setActive]); what comes back arrives a piece at a time, so a slow service holds up only its
 * own part of a card.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class SkiViewModel(
    private val repository: SkiRepository,
    private val start: SkiStartingPoint,
    private val clock: Clock,
    /** The weather client, for the mountain cameras' pictures: Frigate's cookies stay out of it. */
    @Named(WEATHER_CLIENT) val pictureClient: HttpClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(today(SkiUiState(canLocate = start.canLocate)))
    val uiState: StateFlow<SkiUiState> = _uiState.asStateFlow()

    private var active = false
    private var pollJob: Job? = null
    private var loadJob: Job? = null
    private var dayChosen = false
    private var startingPoint: SkiPoint? = null
    private var startingFor: SkiOrigin? = null

    private fun now(): Long = clock.now().epochSeconds

    /** The clock moved on, and with it the day: a trip day that has gone by moves up to the next Saturday. */
    private fun today(state: SkiUiState): SkiUiState {
        val now = now()
        val instant = Instant.fromEpochSeconds(now)
        val today = (now + TimeZone.currentSystemDefault().offsetAt(instant).totalSeconds).floorDiv(SkiForecast.DAY_SECONDS).toInt()
        val trip = if (dayChosen && state.tripEpochDay >= today) state.tripEpochDay else SkiPlanner.nextSaturday(today)
        return state.copy(nowEpochSeconds = now, todayEpochDay = today, tripEpochDay = trip)
    }

    init {
        viewModelScope.launch {
            repository.plan().collect { plan ->
                val before = _uiState.value.plan
                _uiState.update { state ->
                    // The day picked last time, if it hasn't gone by and nothing has been picked since.
                    val saved = plan.tripEpochDay?.takeIf { !dayChosen && it >= state.todayEpochDay }
                    if (saved != null) dayChosen = true
                    state.copy(plan = plan, tripEpochDay = saved ?: state.tripEpochDay)
                }
                // Whatever was on its way was for the old plan. What it already fetched is kept, so this costs only what's new.
                if (active && (before.resorts != plan.resorts || before.regions != plan.regions || before.origin != plan.origin || before.passes != plan.passes || before.tripDays != plan.tripDays)) load(force = false, restart = true)
            }
        }
        viewModelScope.launch {
            repository.siteHealth().collect { health ->
                val trouble = health.trouble(now())
                if (trouble != _uiState.value.siteTrouble) _uiState.update { it.copy(siteTrouble = trouble) }
            }
        }
    }

    /** The tab came on screen, or went. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        pollJob?.cancel()
        pollJob = null
        if (!active) return
        // From here the resorts it follows are ones someone has seen: its notifications can start.
        viewModelScope.launch { repository.updatePlan { if (it.tabOpened) it else it.copy(tabOpened = true) } }
        pollJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update(::today)
                load(force = false)
                delay(TICK_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            startingFor = null
            load(force = true)
            loadJob?.join()
            _uiState.update { it.copy(refreshing = false) }
        }
    }

    fun pickDay(epochDay: Int) {
        dayChosen = true
        _uiState.update { it.copy(tripEpochDay = epochDay) }
        // Kept, as the big-snow notification is about the day picked as well as the weekend.
        viewModelScope.launch { repository.updatePlan { it.copy(tripEpochDay = epochDay) } }
    }

    private fun load(force: Boolean, restart: Boolean = false) {
        if (loadJob?.isActive == true && !force && !restart) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val plan = _uiState.value.plan
            val from = startingPoint(plan.origin)
            _uiState.update { it.copy(hasStart = from != null, startCity = from?.city) }
            coroutineScope {
                launch { loadReports(plan.resorts, force) }
                launch { loadPasses(plan.passes) }
                plan.regions.forEach { slug -> launch { loadRegion(slug) } }
                plan.resorts.map { ref -> async { loadResort(ref, from, force) } }.awaitAll()
            }
        }
    }

    private suspend fun startingPoint(origin: SkiOrigin): SkiPoint? {
        // Nowhere to start from, or only a city to go by, can be better by the next look: a home set, a city added.
        if (startingFor == origin && startingPoint != null && startingPoint?.city == null) return startingPoint
        return start.of(origin).also {
            // Drives from somewhere else aren't drives from here, whether or not new ones can be had.
            if (it.rounded() != startingPoint.rounded()) {
                _uiState.update { state ->
                    state.copy(entries = state.entries.mapValues { (_, entry) -> entry.copy(drive = null, driveOut = null, driveBack = null, chains = emptyList(), chainsUnavailable = false) })
                }
            }
            startingPoint = it
            startingFor = origin
        }
    }

    /** Where a drive starts, as near as it's ever told: a position moving a few yards isn't a new start. */
    private fun SkiPoint?.rounded(): Pair<Int, Int>? = this?.let { (it.latitude * 100).roundToInt() to (it.longitude * 100).roundToInt() }

    /** One page a region answers for all its resorts at once, so there's one fetch however many are followed. */
    private suspend fun loadReports(refs: List<SkiResortRef>, force: Boolean) = coroutineScope {
        refs.groupBy { it.regionSlug }.forEach { (region, inRegion) ->
            launch {
                val reports = repository.reports(region, force)
                reports.getOrNull()?.let { list ->
                    val bySlug = list.associateBy { it.slug }
                    inRegion.forEach { ref -> bySlug[ref.slug]?.let { report -> change(ref) { it.copy(report = report) } } }
                }
                reports.exceptionOrNull()?.let { e -> inRegion.forEach { ref -> change(ref) { entry -> if (entry.report == null) entry.copy(error = e.orFallback(Res.string.ski_error_report)) else entry } } }
            }
            launch {
                repository.prices(region).getOrNull()?.let { prices ->
                    inRegion.forEach { ref -> prices[ref.slug]?.let { price -> change(ref) { it.copy(price = price) } } }
                }
            }
        }
    }

    private suspend fun loadPasses(passes: Set<SkiPass>) {
        val members = passes.associateWith { repository.passResorts(it).getOrNull().orEmpty() }
        _uiState.update { state ->
            state.copy(entries = state.entries.mapValues { (slug, entry) -> entry.copy(passes = members.filterValues { slug in it }.keys) })
        }
        passSets = members
    }

    private var passSets: Map<SkiPass, Set<String>> = emptyMap()

    /** A resort's page first, as it says where the resort is; then what hangs off that, each in its own time. */
    private suspend fun loadResort(ref: SkiResortRef, from: SkiPoint?, force: Boolean, includeCams: Boolean = false) = coroutineScope {
        change(ref) { it.copy(loading = true, parking = repository.parking(ref.slug)) }
        // The resort's own word on its snow and its prices don't wait for anything else, and need nothing else.
        launch { repository.resortNews(ref.slug, force)?.let { news -> change(ref) { it.copy(news = news) } } }
        launch {
            val state = _uiState.value
            val days = (0 until DAYS_PRICED).map { state.todayEpochDay + it }
            val trip = (0 until state.plan.tripDays.coerceAtLeast(1)).map { state.tripEpochDay + it }
            repository.resortPrices(ref.slug, (days + trip).distinct(), state.plan.tripDays.coerceAtLeast(1))?.let { prices -> change(ref) { it.copy(prices = prices) } }
        }
        val page = repository.resortPage(ref, force = force || includeCams)
        page.getOrNull()?.let { loaded -> change(ref) { it.copy(page = loaded, report = it.report ?: loaded.report, error = null) } }
        page.exceptionOrNull()?.let { e -> change(ref) { it.copy(error = e.orFallback(Res.string.ski_error_report)) } }
        val facts = page.getOrNull()?.facts
        if (facts == null) {
            change(ref) { it.copy(loading = false) }
            return@coroutineScope
        }
        launch {
            repository.forecast(ref, facts, force).getOrNull()?.let { forecast -> change(ref) { it.copy(forecast = forecast) } }
            change(ref) { it.copy(loading = false) }
            // An open page's drives go by the mountain's clock, so they follow its forecast, and needn't wait on anything slower.
            if (includeCams) planDrives(ref, from)
        }
        launch {
            val alerts = repository.alerts(facts)
            change(ref) { it.copy(alerts = alerts) }
        }
        if (from != null) {
            launch {
                val to = SkiPoint(facts.latitude, facts.longitude)
                val drive = repository.drive(from, to)
                drive.getOrNull()?.let { road ->
                    // Published together: a drive shown before its chain check is in would read as "no chain controls".
                    val chains = repository.chainControls(road.path)
                    if (from.rounded() != startingPoint.rounded()) return@launch
                    change(ref) { it.copy(drive = road, chains = chains.controls, chainsUnavailable = !chains.complete) }
                }
                drive.exceptionOrNull()?.let { e -> change(ref) { entry -> if (entry.drive == null) entry.copy(error = e.orFallback(Res.string.ski_error_drive)) else entry } }
            }
        }
    }

    private fun change(ref: SkiResortRef, how: (SkiEntry) -> SkiEntry) {
        _uiState.update { state ->
            val before = state.entries[ref.slug] ?: SkiEntry(ref, passes = passSets.filterValues { ref.slug in it }.keys)
            state.copy(entries = state.entries + (ref.slug to how(before)))
        }
    }

    // ---- A resort's page ----------------------------------------------------------------------

    /**
     * A resort's page came up, on the day being planned for: its cameras as they are now, whatever
     * isn't in hand if it's not one the household follows, and the drive there and back at the
     * trip's own hours, if anything knows the traffic ahead of time.
     */
    fun openResort(ref: SkiResortRef) {
        viewModelScope.launch {
            val plan = _uiState.value.plan
            val from = startingPoint(plan.origin)
            coroutineScope {
                launch {
                    if (_uiState.value.entries[ref.slug]?.report == null) {
                        repository.reports(ref.regionSlug).getOrNull()?.firstOrNull { it.slug == ref.slug }?.let { report -> change(ref) { it.copy(report = report) } }
                    }
                    repository.prices(ref.regionSlug).getOrNull()?.get(ref.slug)?.let { price -> change(ref) { it.copy(price = price) } }
                }
                launch { loadResort(ref, from, force = false, includeCams = true) }
            }
        }
    }

    /** The day being planned for changed under an open resort page. */
    fun planTrip(ref: SkiResortRef) {
        viewModelScope.launch { planDrives(ref, startingPoint(_uiState.value.plan.origin)) }
    }

    /** The trip's drives: out at seven in the morning on its first day and back at four on its last, by the resort's clock. */
    private suspend fun planDrives(ref: SkiResortRef, from: SkiPoint?) {
        val state = _uiState.value
        val entry = state.entries[ref.slug] ?: return
        val facts = entry.page?.facts ?: return
        val forecast = entry.forecast ?: return
        if (from == null) return
        val day = state.tripEpochDay
        val out = forecast.epochSecondsAt(day, DEPART_HOUR)
        val back = forecast.epochSecondsAt(day + state.plan.tripDays.coerceAtLeast(1) - 1, RETURN_HOUR)
        val now = now()
        val to = SkiPoint(facts.latitude, facts.longitude)
        // Another day's times aren't this day's, while these are on their way.
        change(ref) { it.copy(driveOut = it.driveOut?.takeIf { drive -> drive.departEpochSeconds == out }, driveBack = it.driveBack?.takeIf { drive -> drive.departEpochSeconds == back }) }
        // A departure in the past can't be asked about.
        val going = if (out > now) repository.drive(from, to, out).getOrNull() else null
        val coming = if (back > now) repository.drive(to, from, back).getOrNull() else null
        // The day or the start changed while these were on their way: the answers for that are coming, and may already be here.
        val latest = _uiState.value
        if (latest.tripEpochDay != day || latest.plan.origin != state.plan.origin || latest.plan.tripDays != state.plan.tripDays || from.rounded() != startingPoint.rounded()) return
        change(ref) { it.copy(driveOut = going, driveBack = coming) }
    }

    // ---- Regions --------------------------------------------------------------------------------

    /** A region's page came up: the listing, then each resort's own word on it as it answers, a few at a time. */
    fun openRegion(slug: String) {
        viewModelScope.launch {
            loadRegion(slug)
            val gate = Semaphore(REGION_SITES_AT_ONCE)
            coroutineScope {
                _uiState.value.regions[slug]?.reports.orEmpty().filter { repository.hasResortSite(it.slug) }.forEach { report ->
                    launch {
                        gate.withPermit { repository.resortNews(report.slug) }?.let { news ->
                            _uiState.update { state ->
                                val load = state.regions[slug] ?: return@update state
                                state.copy(regions = state.regions + (slug to load.copy(news = load.news + (report.slug to news))))
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadRegion(slug: String) {
        _uiState.update { it.copy(regions = it.regions + (slug to (it.regions[slug] ?: SkiRegionLoad()).copy(loading = true, error = null))) }
        val reports = repository.reports(slug)
        // A pass's list has no prices of its own: they're by region.
        val prices = if (SkiPass.entries.any { it.slug == slug }) emptyMap() else repository.prices(slug).getOrNull().orEmpty()
        _uiState.update { state ->
            val before = state.regions[slug] ?: SkiRegionLoad()
            val load = reports.fold(
                onSuccess = { before.copy(reports = it, prices = prices, loading = false, error = null) },
                onFailure = { before.copy(loading = false, error = it.orFallback(Res.string.ski_error_report)) },
            )
            state.copy(regions = state.regions + (slug to load))
        }
    }

    // ---- The plan -------------------------------------------------------------------------------

    fun follow(ref: SkiResortRef, follow: Boolean) {
        viewModelScope.launch {
            repository.updatePlan { plan ->
                when {
                    follow && !plan.follows(ref.slug) -> plan.copy(resorts = plan.resorts + ref)
                    !follow -> plan.copy(resorts = plan.resorts.filterNot { it.slug == ref.slug })
                    else -> plan
                }
            }
        }
    }

    fun followRegion(slug: String, follow: Boolean) {
        viewModelScope.launch {
            repository.updatePlan { plan ->
                plan.copy(regions = if (follow) (plan.regions + slug).distinct() else plan.regions - slug)
            }
        }
    }

    fun setParty(adults: Int, children: Int) {
        viewModelScope.launch {
            repository.updatePlan { it.copy(adults = adults.coerceIn(0, MAX_PARTY), children = children.coerceIn(0, MAX_PARTY)) }
        }
    }

    fun setPass(pass: SkiPass, held: Boolean) {
        viewModelScope.launch {
            repository.updatePlan { it.copy(passes = if (held) it.passes + pass else it.passes - pass) }
        }
    }

    fun setOrigin(origin: SkiOrigin) {
        viewModelScope.launch {
            // Choosing it is the moment to ask: nothing else will, and without it this would quietly go on starting from home.
            if (origin == SkiOrigin.HERE) start.requestAccess()
            startingFor = null
            repository.updatePlan { it.copy(origin = origin) }
        }
    }

    fun setAlerts(alerts: SkiAlerts) {
        viewModelScope.launch { repository.updatePlan { it.copy(alerts = alerts) } }
    }

    /** What the household pays a day at a resort; null goes back to the listed price. */
    fun setOwnPrice(slug: String, price: Int?) {
        viewModelScope.launch {
            repository.updatePlan { it.copy(ownPrices = if (price != null && price >= 0) it.ownPrices + (slug to price) else it.ownPrices - slug) }
        }
    }

    /** How long the trip is and what a night and a mile come to. */
    fun setTrip(days: Int, lodgingPerNight: Int, drivingCentsPerMile: Int) {
        viewModelScope.launch {
            repository.updatePlan {
                it.copy(tripDays = days.coerceIn(1, SkiPlan.MAX_TRIP_DAYS), lodgingPerNight = lodgingPerNight.coerceAtLeast(0), drivingCentsPerMile = drivingCentsPerMile.coerceAtLeast(0))
            }
        }
    }

    /** What parking costs a day at a resort; null goes back to what the resort publishes, if it does. */
    fun setOwnParking(slug: String, price: Int?) {
        viewModelScope.launch {
            repository.updatePlan { it.copy(ownParking = if (price != null && price >= 0) it.ownParking + (slug to price) else it.ownParking - slug) }
        }
    }

    private companion object {
        /** How often the tab looks at how old its answers are. Each is only asked for again once it's past its own age. */
        const val TICK_MS = 5 * 60_000L
        const val DEPART_HOUR = 7
        const val RETURN_HOUR = 16
        const val MAX_PARTY = 12

        /** The days ahead a dated shop is asked about: as far as there's a day to choose. */
        const val DAYS_PRICED = 10
        const val REGION_SITES_AT_ONCE = 3

        private fun Throwable.orFallback(fallback: StringResource): UiText =
            if (this is LocalizedException) userMessage() else UiText.of(fallback)
    }
}
