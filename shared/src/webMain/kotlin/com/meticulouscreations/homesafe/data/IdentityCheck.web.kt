package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck

actual fun createIdentityCheck(context: PlatformContext): IdentityCheck = NoIdentityCheck
