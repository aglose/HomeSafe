package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice
import kotlin.experimental.ExperimentalNativeApi

private class IosDeviceInfo : DeviceInfo {
    override val platform = "ios"

    /** iOS 16+ hides the user's device name behind an entitlement, so this is "Apple iPhone". */
    override val name = "Apple ${UIDevice.currentDevice.model}"

    /**
     * Whether this binary was built for debugging. Note the relay counts iOS towards away mode on
     * *either* build for now — there's no iOS release channel yet — see `docs/away-mode.md`.
     */
    @OptIn(ExperimentalNativeApi::class)
    override val build = if (kotlin.native.Platform.isDebugBinary) "debug" else "release"

    /** MARKETING_VERSION and CURRENT_PROJECT_VERSION from `iosApp/Configuration/Config.xcconfig`. */
    override val appVersion = run {
        val info = NSBundle.mainBundle
        "${info.objectForInfoDictionaryKey("CFBundleShortVersionString")} (${info.objectForInfoDictionaryKey("CFBundleVersion")})"
    }
}

actual fun createDeviceInfo(platformContext: PlatformContext): DeviceInfo = IosDeviceInfo()
