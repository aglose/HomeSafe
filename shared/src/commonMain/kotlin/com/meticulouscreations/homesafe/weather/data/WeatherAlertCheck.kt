package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.text.TextLoader
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeLedger
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticePlanner
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import com.meticulouscreations.homesafe.weather.domain.WeatherPlacesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * One look at the forecast for notifications: fetch it for where the phone is, ask
 * [WeatherNoticePlanner] what's worth saying, post what hasn't been said. Run from the periodic
 * background job (Android's `WeatherCheckWorker`, iOS's background refresh) and whenever the
 * weather app is opened.
 *
 * The place is wherever the phone is if a fix can be had, else where it last was, else the
 * household's home, else the first city on the list: the weather that affects your day is the
 * weather where you are.
 */
@Inject
@SingleIn(AppScope::class)
class WeatherAlertCheck(
    private val repository: WeatherRepository,
    private val locator: WeatherLocator,
    private val places: WeatherPlacesRepository,
    private val preferences: WeatherPreferencesRepository,
    private val ledger: WeatherNoticeLedger,
    private val notifier: WeatherNotifier,
    private val scheduler: WeatherCheckScheduler,
    private val textLoader: TextLoader,
    private val clock: Clock,
    private val appScope: CoroutineScope,
) {
    private val running = Mutex()

    /** Called once at launch: makes the background job match the notification switch. */
    fun start() {
        appScope.launch { runCatching { syncSchedule() } }
    }

    /** Starts or stops the background check to match the notification switch. Call at launch and when the switch changes. */
    suspend fun syncSchedule() {
        val wanted = notifier.isSupported && preferences.observe().first().notices.enabled
        scheduler.schedule(wanted)
    }

    /** Checks now; returns how many notifications were posted. Never throws: a failed check is tried again next time. */
    suspend fun run(): Int = running.withLock {
        runCatching { check() }.getOrDefault(0)
    }

    private suspend fun check(): Int {
        if (!notifier.isSupported) return 0
        val prefs = preferences.observe().first()
        if (!prefs.notices.enabled) return 0
        val place = place() ?: return 0
        val report = repository.report(place, maxAgeSeconds = MAX_AGE_SECONDS).getOrNull() ?: return 0
        val now = clock.now().epochSeconds
        val notices = WeatherNoticePlanner.plan(report, place.name.takeIf { it.isNotBlank() }, prefs.notices, prefs.units, now, ledger.sent(now))
        notices.forEach { notice ->
            notifier.notify(WeatherNotification(notice.key, textLoader.load(notice.title), textLoader.load(notice.body), notice.urgent))
            ledger.record(notice.key, now)
        }
        return notices.size
    }

    private suspend fun place(): Place? =
        runCatching { locator.locate() }.getOrNull()
            ?: locator.remembered()?.let { remembered -> runCatching { locator.named(remembered) }.getOrDefault(remembered) }
            ?: places.observe().first().firstOrNull()

    private companion object {
        /** The forecast's own quarter-hour step: a check never reasons from anything older. */
        const val MAX_AGE_SECONDS = 300L
    }
}
