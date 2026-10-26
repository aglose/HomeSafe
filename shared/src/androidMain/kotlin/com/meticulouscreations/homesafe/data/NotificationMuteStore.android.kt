package com.meticulouscreations.homesafe.data

import android.content.Context
import android.content.SharedPreferences
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The switch in a small preferences file of its own. The app graph is built per Activity, so
 * more than one of these can be alive at once; each follows the file, so a change made through
 * one is seen by the others.
 */
private class SharedPreferencesNotificationMute(context: Context) : NotificationMute {
    private val preferences = NotificationMuteStore.preferences(context)
    private val state = MutableStateFlow(preferences.getBoolean(NotificationMuteStore.KEY_MUTED, false))

    // Held here: SharedPreferences keeps its listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == NotificationMuteStore.KEY_MUTED) state.value = prefs.getBoolean(NotificationMuteStore.KEY_MUTED, false)
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val muted: StateFlow<Boolean> = state.asStateFlow()

    override fun setMuted(muted: Boolean) {
        state.value = muted
        preferences.edit().putBoolean(NotificationMuteStore.KEY_MUTED, muted).apply()
    }
}

/** Where the switch is kept, for the code that posts a push with no app graph to ask (`HomeSafeMessagingService`). */
object NotificationMuteStore {
    private const val PREFS_NAME = "notification_mute"
    internal const val KEY_MUTED = "muted"

    internal fun preferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isMuted(context: Context): Boolean = preferences(context).getBoolean(KEY_MUTED, false)
}

actual fun createNotificationMute(platformContext: PlatformContext): NotificationMute =
    SharedPreferencesNotificationMute(platformContext.context)
