package com.meticulouscreations.homesafe.ui.components

import android.os.Process
import android.os.SystemClock
import android.util.Log

private const val MILESTONE_TAG = "HomeSafeTTFP"

/**
 * Logs each [LiveStartupMilestones] name once, as `<name> +<ms since process start>`, under
 * [MILESTONE_TAG]. The process's own start time is the baseline, so the numbers need no clock
 * shared with whatever reads them.
 */
internal fun installLiveStartupMilestones() {
    if (LiveStartupMilestones.recorder != null) return
    val processStart = Process.getStartElapsedRealtime()
    val seen = HashSet<String>()
    LiveStartupMilestones.recorder = { name ->
        val first = synchronized(seen) { seen.add(name) }
        if (first) Log.i(MILESTONE_TAG, "$name +${SystemClock.elapsedRealtime() - processStart}ms")
    }
}
