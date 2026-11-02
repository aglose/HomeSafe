package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.text.TextLoader
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeLedger
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherNotifier
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferencesRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNotice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNoticePlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNoticePush
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * A skiing notification the relay pushed ("ski notices" in relay/relay.py, docs/weather.md) arriving
 * on this phone. [SkiNoticePlanner] words it in the reader's language and units, and the switches
 * and what was already said have their say.
 */
@Inject
@SingleIn(AppScope::class)
class SkiNoticeCheck(
    private val repository: SkiRepository,
    private val preferences: WeatherPreferencesRepository,
    private val ledger: WeatherNoticeLedger,
    private val notifier: WeatherNotifier,
    private val textLoader: TextLoader,
    private val clock: Clock,
    private val mute: NotificationMute,
) {
    /**
     * [shown] says the platform has drawn the push's own banner already (iOS, which only sometimes
     * wakes the app to get here): what is posted takes its place, without alerting again. What this
     * build can't read goes out as the relay worded it, [fallbackTitle] and [fallbackBody], unless
     * that's already up. Never throws.
     */
    suspend fun onRelayPush(payload: String, key: String, fallbackTitle: String, fallbackBody: String, shown: Boolean): Boolean = runCatching {
        if (!notifier.isSupported) return@runCatching false
        val now = clock.now().epochSeconds
        val sent = ledger.sent(now)
        if (shown) ledger.record(key, now)
        val prefs = preferences.observe().first()
        if (!prefs.notices.enabled || mute.muted.value || !notifier.isAllowed()) return@runCatching false
        val push = SkiNoticePush.read(payload)
        val notification = if (push != null) {
            val plan = repository.plan().first()
            SkiNoticePlanner.notice(push, key, prefs.notices, plan.alerts, prefs.units, sent)?.toNotification() ?: return@runCatching false
        } else {
            if (shown || fallbackTitle.isEmpty() || key in sent) return@runCatching false
            WeatherNotification(key, fallbackTitle, fallbackBody, WeatherNoticeArt(WeatherNoticeIcon.SNOW), link = WeatherDeepLink(WeatherDeepLink.View.SKI))
        }
        val posted = notifier.notify(notification.copy(replacesPush = shown))
        if (posted) ledger.record(key, now)
        posted
    }.getOrDefault(false)

    private suspend fun SkiNotice.toNotification() = WeatherNotification(
        id = key,
        title = textLoader.load(title),
        body = textLoader.load(body),
        art = WeatherNoticeArt(
            icon = icon,
            caption = caption?.let { textLoader.load(it) },
            headline = headline?.let { textLoader.load(it) },
        ),
        detail = detail?.let { textLoader.load(it) },
        expiresEpochSeconds = expiresEpochSeconds,
        link = WeatherDeepLink(view = WeatherDeepLink.View.SKI, day = day, resort = resort),
    )
}
