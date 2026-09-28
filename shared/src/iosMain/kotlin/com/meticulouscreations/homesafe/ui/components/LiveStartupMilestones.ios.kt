package com.meticulouscreations.homesafe.ui.components

import platform.Foundation.NSLock
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo

/**
 * Logs each [LiveStartupMilestones] name once, as `HomeSafeTTFP <name> +<ms>`, through NSLog so
 * `xcrun simctl spawn booted log stream` (or Console) can read it. The baseline is the moment the
 * recorder was installed at launch rather than the process start, which is close enough: the
 * stages are compared with each other, and against sign-in.
 */
internal fun installLiveStartupMilestones() {
    if (LiveStartupMilestones.recorder != null) return
    val start = NSProcessInfo.processInfo.systemUptime
    val seen = HashSet<String>()
    val lock = NSLock()
    LiveStartupMilestones.recorder = { name ->
        lock.lock()
        val first = try {
            seen.add(name)
        } finally {
            lock.unlock()
        }
        if (first) {
            val ms = ((NSProcessInfo.processInfo.systemUptime - start) * 1000).toLong()
            // The whole line as the format, no varargs: Kotlin/Native doesn't bridge a Kotlin
            // String passed to NSLog's `%@`, and doing so crashes (seen on the first sign-in).
            NSLog("HomeSafeTTFP $name +${ms}ms".replace("%", "%%"))
        }
    }
}
