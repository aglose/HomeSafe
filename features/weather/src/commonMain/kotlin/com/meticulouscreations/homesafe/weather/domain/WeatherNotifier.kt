package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink

/**
 * The picture on a weather notification: which glyph it wears and under what sky, the words
 * beside it (already in the reader's language) and the forecast its chart draws. The platforms
 * paint it with `WeatherNoticePainter`; none of it is needed to read the notification.
 */
@Immutable
data class WeatherNoticeArt(
    val icon: WeatherNoticeIcon,
    val isDay: Boolean = true,
    /** A small line over [headline]: the day, or who issued a warning. */
    val caption: String? = null,
    /** The one thing to read at a glance: when the rain starts, the day's high and low, until when a warning runs. */
    val headline: String? = null,
    val facts: List<Fact> = emptyList(),
    val chart: WeatherNoticeChart? = null,
    /** [chart]'s ticks, in words. */
    val ticks: List<String> = emptyList(),
) {
    @Immutable
    data class Fact(val icon: WeatherNoticeIcon?, val text: String)
}

/**
 * One weather notification, already in the reader's language. [id] replaces an earlier post of
 * the same thing. Beyond the two lines every notification has: the [place] it is for (a line of
 * its own where the platform has one), [detail] to open out into, the moment it is about
 * ([eventEpochSeconds]) and the moment it should be taken down ([expiresEpochSeconds]), its
 * picture ([art]) and where a tap lands ([link]).
 */
@Immutable
data class WeatherNotification(
    val id: String,
    val title: String,
    val body: String,
    val art: WeatherNoticeArt,
    val urgent: Boolean = false,
    val place: String? = null,
    val detail: String? = null,
    val eventEpochSeconds: Long? = null,
    val expiresEpochSeconds: Long? = null,
    val link: WeatherDeepLink = WeatherDeepLink(),
    /** The same thing is already showing as a push the platform drew itself: take its place without alerting again. */
    val replacesPush: Boolean = false,
)

/**
 * Posts the weather app's notifications through the platform's own surface, on channels of
 * their own so they can be silenced apart from the cameras'. A tap opens the weather app.
 * Permission is the app's one notification permission, asked for through `AlertNotifier`.
 */
interface WeatherNotifier {
    /** False where the platform has no notifications this app can post (desktop, a browser tab). */
    val isSupported: Boolean

    /** Whether the OS would show one now: the permission is the app's one notification permission, asked for elsewhere. */
    suspend fun isAllowed(): Boolean

    /** Posts [notification], and says whether the OS took it: false without permission, or when it refused. */
    suspend fun notify(notification: WeatherNotification): Boolean

    /**
     * Takes down what has stopped being news by [nowEpochSeconds]: a shower that has passed, a
     * warning that has lapsed. A no-op where the platform does that by itself.
     */
    suspend fun sweep(nowEpochSeconds: Long) = Unit
}

/**
 * Keeps the forecast being checked while the app isn't open, so "rain in 20 minutes" can reach a
 * phone in a pocket: Android schedules a periodic job, iOS asks for background refreshes, and
 * the rest have nothing to schedule.
 */
interface WeatherCheckScheduler {
    /** Starts the periodic check ([enabled]) or cancels it. Safe to call on every launch. */
    fun schedule(enabled: Boolean)
}
