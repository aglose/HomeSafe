package com.meticulouscreations.homesafe.ui.components

import android.content.Context

/** [LiveTransportMemory]'s proven record in a small preferences file: stream key to when it last joined. */
internal class SharedPreferencesTransportStore(context: Context) : LiveTransportMemory.Store {
    private val preferences = context.getSharedPreferences("live_transport", Context.MODE_PRIVATE)

    override fun load(): Map<String, Long> = preferences.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    override fun save(connectedAt: Map<String, Long>) {
        val editor = preferences.edit().clear()
        connectedAt.forEach { (key, at) -> editor.putLong(key, at) }
        editor.apply()
    }
}
