package com.meticulouscreations.homesafe.data

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo

private class AndroidDeviceInfo(debuggable: Boolean, override val hardwareId: String, override val appVersion: String) : DeviceInfo {
    override val platform = "android"
    override val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * Read off the installed app rather than BuildConfig, which this module doesn't have: the
     * debug variant is debuggable, the release variant isn't. Debug installs (emulators, the
     * .debug app beside the real one) register for push but are never one of away mode's tracked
     * phones — the relay decides that from this field.
     */
    override val build = if (debuggable) "debug" else "release"

    /** What the stock emulator images say about themselves; a real phone says none of it. */
    override val emulator = Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("emulator") ||
        Build.HARDWARE == "goldfish" ||
        Build.HARDWARE == "ranchu" ||
        Build.PRODUCT.contains("sdk")
}

// ANDROID_ID is the point here: one per phone and signing key, kept across reinstalls, which is
// what the relay knows the household's phone by. It is never used for ads or shared off the box.
@SuppressLint("HardwareIds")
actual fun createDeviceInfo(platformContext: PlatformContext): DeviceInfo {
    val context = platformContext.context
    val flags = context.applicationInfo.flags
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    return AndroidDeviceInfo(
        debuggable = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        hardwareId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty(),
        appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
    )
}
