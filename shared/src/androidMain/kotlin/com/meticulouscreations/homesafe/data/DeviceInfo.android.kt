package com.meticulouscreations.homesafe.data

import android.content.pm.ApplicationInfo
import android.os.Build
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo

private class AndroidDeviceInfo(debuggable: Boolean) : DeviceInfo {
    override val platform = "android"
    override val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * Read off the installed app rather than BuildConfig, which this module doesn't have: the
     * debug variant is debuggable, the release variant isn't. Debug installs (emulators, the
     * .debug app beside the real one) register for push but don't count towards away mode — the
     * relay decides that from this field.
     */
    override val build = if (debuggable) "debug" else "release"
}

actual fun createDeviceInfo(platformContext: PlatformContext): DeviceInfo {
    val flags = platformContext.context.applicationInfo.flags
    return AndroidDeviceInfo(debuggable = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
}
