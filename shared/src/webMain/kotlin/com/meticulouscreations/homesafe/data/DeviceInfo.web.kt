package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo

private class WebDeviceInfo : DeviceInfo {
    override val platform = "web"
    override val name = "HomeSafe web"

    /** Never a phone in anyone's pocket, so never one that decides the house is empty. */
    override val build = "debug"
}

actual fun createDeviceInfo(platformContext: PlatformContext): DeviceInfo = WebDeviceInfo()
