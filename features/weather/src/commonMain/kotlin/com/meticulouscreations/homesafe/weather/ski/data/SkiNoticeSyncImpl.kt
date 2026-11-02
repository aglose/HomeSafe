package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.SkiDao
import com.meticulouscreations.homesafe.data.SkiKeptEntity
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.data.suspendRunCatching
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.network.SkiResortWish
import com.meticulouscreations.homesafe.network.SkiWishes
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiEntry
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNoticeSync
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Where the relay is reached, and by what proof. Apart so a test can see what would be sent. */
interface SkiWishesRelay {
    /** Whether it took them. False with no relay to tell. */
    suspend fun tell(wishes: SkiWishes): Boolean
}

/** By this install's own secret: the check runs in the background, with no session. An install that has none yet has no relay to tell. */
@Inject
@ContributesBinding(AppScope::class)
class HttpSkiWishesRelay(
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
    private val connectionRepository: ConnectionRepository,
) : SkiWishesRelay {
    override suspend fun tell(wishes: SkiWishes): Boolean {
        val secret = identity.secret() ?: return false
        val url = connectionRepository.currentServerUrl.value
            ?: connectionRepository.mostRecentConnection.first()?.serverUrl
            ?: return false
        return relayApi.putSkiWishes(url, identity.deviceId(), secret, wishes).isSuccess
    }
}

/**
 * Tells the relay what the Ski tab wants to hear: when the plan or the switches change, and at
 * least daily from the background check, since what's known of a resort's season goes stale. Never
 * where the device is, or where its drives start: the plan's `origin` stays here.
 *
 * Nothing until the tab has been opened on this device: a phone that has never looked at it would
 * otherwise start hearing about the resorts it follows by default.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SkiNoticeSyncImpl(
    private val repository: SkiRepository,
    private val preferences: WeatherPreferencesRepository,
    private val relay: SkiWishesRelay,
    private val dao: SkiDao,
    private val clock: Clock,
    private val appScope: CoroutineScope,
) : SkiNoticeSync {
    private val telling = Mutex()
    private var watching = false

    override fun watch() {
        if (watching) return
        watching = true
        appScope.launch {
            combine(repository.plan(), preferences.observe()) { plan, prefs -> what(plan, prefs.notices.enabled, prefs.units.measures == MeasureSystem.IMPERIAL) }
                .distinctUntilChanged()
                .collectLatest {
                    // Picking through the days on the strip is one change, not ten.
                    delay(SETTLE_MS)
                    suspendRunCatching { sync() }
                }
        }
    }

    override suspend fun sync(): Unit = telling.withLock {
        val prefs = preferences.observe().first()
        val plan = repository.plan().first()
        if (!plan.tabOpened) return@withLock
        val enabled = prefs.notices.enabled
        val imperial = prefs.units.measures == MeasureSystem.IMPERIAL
        val told = dao.kept(TOLD_KEY)
        val now = clock.now().epochSeconds
        val what = what(plan, enabled, imperial)
        if (told != null && told.json == what && now - told.savedAtEpochSeconds < TELL_AGAIN_SECONDS) return@withLock
        val wanted = enabled && plan.alerts.any
        val today = (now / SkiForecast.DAY_SECONDS).toInt()
        val wishes = SkiWishes(
            snow = wanted && plan.alerts.bigSnow,
            fresh = wanted && plan.alerts.freshSnow,
            chains = wanted && plan.alerts.chains,
            imperial = imperial,
            tripDay = plan.tripEpochDay?.takeIf { it >= today - 1 },
            resorts = if (wanted) plan.resorts.mapNotNull { wish(it) } else emptyList(),
        )
        // A page that wouldn't answer just now leaves a resort out, so it's asked again soon rather than tomorrow.
        val complete = !wanted || wishes.resorts.size == plan.resorts.size
        if (relay.tell(wishes) && complete) dao.keep(SkiKeptEntity(TOLD_KEY, what, now))
    }

    /** Everything that would change what the relay is told, short of a resort's season, which [TELL_AGAIN_SECONDS] looks after. */
    private fun what(plan: SkiPlan, enabled: Boolean, imperial: Boolean): String =
        listOf(plan.tabOpened, enabled, imperial, plan.tripEpochDay, plan.alerts.bigSnow, plan.alerts.freshSnow, plan.alerts.chains, plan.resorts.map { it.slug }).joinToString("|")

    /** A resort's public facts, and what's known of its season: its listing with its own word over it, as the tab has it. */
    private suspend fun wish(ref: SkiResortRef): SkiResortWish? {
        val page = repository.resortPage(ref).getOrNull() ?: return null
        val listed = repository.reports(ref.regionSlug).getOrNull()?.firstOrNull { it.slug == ref.slug }
        val report = SkiEntry(ref, report = listed, page = page, news = repository.resortNews(ref.slug)).latest
        return SkiResortWish(
            slug = ref.slug,
            name = ref.name,
            lat = page.facts.latitude,
            lng = page.facts.longitude,
            summitM = page.facts.summitElevationM,
            state = report?.state?.name ?: "CLOSED",
            opens = report?.openingEpochDay,
            closes = report?.closingEpochDay,
        )
    }

    private companion object {
        const val TOLD_KEY = "relay-told"
        const val SETTLE_MS = 2_000L
        const val TELL_AGAIN_SECONDS = 86_400L
    }
}
