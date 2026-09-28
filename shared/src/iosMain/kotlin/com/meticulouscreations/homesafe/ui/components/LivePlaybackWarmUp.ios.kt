package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/**
 * The engine lives on the Swift side and is initialised by the bridge on its first peer; what's
 * left to do here is bring back which streams have proven WebRTC (see [LiveTransportMemory]).
 * Called on the main thread, like everything that touches the memory.
 */
actual fun warmUpLivePlayback(context: PlatformContext) {
    installLiveStartupMilestones()
    LiveTransportMemory.shared.restore(UserDefaultsTransportStore)
}

/** [LiveTransportMemory]'s proven record as one dictionary in the standard user defaults. */
private object UserDefaultsTransportStore : LiveTransportMemory.Store {
    private const val KEY = "live_transport_proven"

    override fun load(): Map<String, Long> {
        val stored = NSUserDefaults.standardUserDefaults.dictionaryForKey(KEY) ?: return emptyMap()
        return stored.entries.mapNotNull { (key, value) ->
            val stream = key as? String ?: return@mapNotNull null
            val at = (value as? NSNumber)?.longLongValue ?: return@mapNotNull null
            stream to at
        }.toMap()
    }

    override fun save(connectedAt: Map<String, Long>) {
        NSUserDefaults.standardUserDefaults.setObject(connectedAt.mapValues { NSNumber(longLong = it.value) }, KEY)
    }
}
