package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.text.TextLoader
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.domain.WeatherNotice
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeKind
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeLedger
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticePlanner
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import com.meticulouscreations.homesafe.weather.domain.WeatherPlacesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherRepository
import com.meticulouscreations.homesafe.weather.domain.WeatherSoonClaims
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_notice_place_home
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One look at the forecast for notifications: fetch it for where the phone is, ask
 * [WeatherNoticePlanner] what's worth saying, post what hasn't been said. Run from the periodic
 * background job (Android's `WeatherCheckWorker`, iOS's background refresh) and whenever the
 * weather app is opened.
 *
 * The place is wherever the phone is if a fix can be had, else where it last was, else the
 * household's home, else the first city on the list: the weather that affects your day is the
 * weather where you are.
 *
 * The relay watches the forecast at home and pushes rain on the way too, which is what lets a
 * shower be announced in time when this hasn't run lately: [onRelayPush] is that push arriving.
 * [claims] keeps the two from saying the same shower twice.
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
    /** Settings → Notifications' "Mute all notifications": while it's on, a check posts nothing. */
    private val mute: NotificationMute,
    private val claims: WeatherSoonClaims,
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

    /**
     * Posts a sample notification (see [WeatherNoticePlanner.sample]) for where the phone is, and
     * says whether the OS took it. Asked for from Settings, so it goes out whatever the hour and
     * whatever the switches say, and the ledger never hears of it.
     */
    suspend fun sample(): Boolean = running.withLock {
        runCatching {
            if (!notifier.isSupported || !notifier.isAllowed()) return@runCatching false
            val place = place() ?: return@runCatching false
            val report = repository.report(place, maxAgeSeconds = SAMPLE_MAX_AGE_SECONDS).getOrNull() ?: return@runCatching false
            val units = preferences.observe().first().units
            val notice = WeatherNoticePlanner.sample(report, place.name.takeIf { it.isNotBlank() }, units, clock.now().epochSeconds) ?: return@runCatching false
            val posted = notifier.notify(notice.toNotification(place))
            if (posted) notice.expiresEpochSeconds?.let(::sweepAt)
            posted
        }.getOrDefault(false)
    }

    /**
     * A shower the relay pushed (see "rain soon" in relay/relay.py): the forecast it carries goes
     * through the same planner as a check's, so what is posted looks like any other, in the
     * reader's language, and the switches, the quiet hours and what was already said all still
     * have their say. For [key], at home, which is where the relay only ever looks. Never throws.
     *
     * [shown] says the platform has drawn the push's own banner already (iOS, which then only
     * sometimes wakes the app to get here): what is posted takes its place, without alerting
     * again. Android draws nothing itself; a forecast this build can't read goes out as the relay
     * worded it, [fallbackTitle] and [fallbackBody], with no card, under the same cooldown and the
     * same quiet hours, read from this phone's clock since the relay only tells a phone at home.
     *
     * Doesn't wait on [running]: a check might be fetching a forecast, longer than a push's
     * handler may run, and at worst posts the same id again, which alerts nobody twice.
     */
    suspend fun onRelayPush(payload: String, key: String, fallbackTitle: String, fallbackBody: String, shown: Boolean): Boolean = runCatching {
        if (!notifier.isSupported) return@runCatching false
        val now = clock.now().epochSeconds
        val sent = ledger.sent(now)
        // On the screen, whatever else happens here: the phone's own check mustn't say it again.
        if (shown) ledger.record(key, now)
        val prefs = preferences.observe().first()
        if (!prefs.notices.enabled || !prefs.notices.precipitationSoon || mute.muted.value || !notifier.isAllowed()) return@runCatching false
        val report = WeatherSoonPush.report(payload, now)
        val home = textLoader.load(UiText.of(Res.string.weather_notice_place_home))
        val notification = if (report != null) {
            val settings = prefs.notices.copy(dailyOutlook = false, severeAlerts = false)
            val notice = WeatherNoticePlanner.plan(report, null, settings, prefs.units, now, sent).firstOrNull() ?: return@runCatching false
            notice.copy(place = home).toNotification(HERE)
        } else {
            val hour = Instant.fromEpochSeconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour
            if (shown || WeatherNoticePlanner.holdsBackSoon(hour, now, sent)) return@runCatching false
            WeatherNotification(key, fallbackTitle, fallbackBody, WeatherNoticeArt(WeatherNoticeIcon.RAIN), place = home, link = WeatherDeepLink(placeId = Place.CURRENT_ID))
        }
        val posted = notifier.notify(notification.copy(replacesPush = shown))
        if (posted) ledger.record(notification.id, now)
        posted
    }.getOrDefault(false)

    /**
     * Takes down what is over at [epochSeconds], from here rather than from the next check. A
     * sample can go out with the notifications switched off, when no check is scheduled to come,
     * and where the platform can't give a notification an end (iOS) nothing else would take it
     * down. Good for as long as the app is running; every later check sweeps too, switch or no.
     */
    private fun sweepAt(epochSeconds: Long) {
        appScope.launch {
            delay((epochSeconds - clock.now().epochSeconds).coerceAtLeast(0).seconds)
            runCatching { notifier.sweep(clock.now().epochSeconds) }
        }
    }

    private suspend fun check(): Int {
        if (!notifier.isSupported) return 0
        val now = clock.now().epochSeconds
        // First, and whatever the switches say: what was said earlier and is over comes down. A
        // sample, or a notice posted before the switch went off, is still there to be taken down.
        runCatching { notifier.sweep(now) }
        val prefs = preferences.observe().first()
        if (!prefs.notices.enabled) return 0
        // Muted: nothing is fetched or marked as said either, so unmuting picks up where it was.
        if (mute.muted.value) return 0
        // Not allowed to notify: nothing is fetched, and nothing is marked as said, so the morning's
        // outlook is still there to send if permission comes later that morning.
        if (!notifier.isAllowed()) return 0
        val place = place() ?: return 0
        val report = repository.report(place, maxAgeSeconds = MAX_AGE_SECONDS).getOrNull() ?: return 0
        // What the relay said it told this phone counts here as said, though the push may not have landed yet.
        val sent = ledger.sent(now).mapKeys { (key, _) -> key.removePrefix(TOLD_PREFIX) }
        val notices = WeatherNoticePlanner.plan(report, place.name.takeIf { it.isNotBlank() }, prefs.notices, prefs.units, now, sent)
        // Marked as said only once it has been: one the OS wouldn't take is tried again next time.
        return notices.count { notice ->
            // Muted while the forecast was being fetched: held back, and not marked as said.
            if (mute.muted.value) return@count false
            // The relay told this phone first: this check stays quiet from now on, and the push, which
            // may still be on its way, is kept apart from what has been posted so it can still land.
            if (notice.kind == WeatherNoticeKind.PRECIPITATION_SOON) {
                val claim = claims.claim(notice.key)
                if (claim != null && !claim.granted) {
                    ledger.record(TOLD_PREFIX + notice.key, claim.atEpochSeconds ?: now)
                    return@count false
                }
            }
            val posted = notifier.notify(notice.toNotification(place))
            if (posted) ledger.record(notice.key, now)
            posted
        }
    }

    /** [this] in the reader's language, with where its tap lands: the part of the weather app it is about, on [place]. */
    private suspend fun WeatherNotice.toNotification(place: Place) = WeatherNotification(
        id = key,
        title = textLoader.load(title),
        body = textLoader.load(body),
        art = WeatherNoticeArt(
            icon = icon,
            isDay = isDay,
            caption = caption?.let { textLoader.load(it) },
            headline = headline?.let { textLoader.load(it) },
            facts = facts.map { WeatherNoticeArt.Fact(it.icon, textLoader.load(it.text)) },
            chart = chart,
            ticks = chart?.ticks.orEmpty().map { textLoader.load(it) },
        ),
        urgent = urgent,
        place = this.place,
        detail = detail?.let { textLoader.load(it) },
        eventEpochSeconds = eventEpochSeconds,
        expiresEpochSeconds = expiresEpochSeconds,
        link = WeatherDeepLink(
            view = if (kind == WeatherNoticeKind.TOMORROW) WeatherDeepLink.View.FORECAST else WeatherDeepLink.View.TODAY,
            placeId = place.id,
            alertKey = alertKey,
        ),
    )

    private suspend fun place(): Place? =
        runCatching { locator.locate() }.getOrNull()
            ?: locator.remembered()?.let { remembered -> runCatching { locator.named(remembered) }.getOrDefault(remembered) }
            ?: places.observe().first().firstOrNull()

    private companion object {
        /** In the ledger, ahead of a shower's key: the relay says it told this phone, and nothing here has seen it yet. */
        const val TOLD_PREFIX = "told:"

        /** Where a shower from the relay lands: the phone's own position, since the relay only tells a phone at home. Only its id is read. */
        val HERE = Place(Place.CURRENT_ID, name = "", latitude = 0.0, longitude = 0.0)

        /** The forecast's own quarter-hour step: a check never reasons from anything older. */
        const val MAX_AGE_SECONDS = 300L

        /** A sample is of how a notification looks, not of the last quarter hour: the forecast on screen will do. */
        const val SAMPLE_MAX_AGE_SECONDS = 1_800L
    }
}
