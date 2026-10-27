package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.NotificationMute

expect fun createNotificationMute(platformContext: PlatformContext): NotificationMute
