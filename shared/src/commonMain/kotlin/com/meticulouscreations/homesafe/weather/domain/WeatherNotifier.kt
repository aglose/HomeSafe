package com.meticulouscreations.homesafe.weather.domain

/** One weather notification, already in the reader's language. [id] replaces an earlier post of the same thing. */
data class WeatherNotification(val id: String, val title: String, val body: String, val urgent: Boolean = false)

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
