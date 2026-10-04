package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeRange

/**
 * The server's uptime record (see [ServerUptime]), kept by the push relay on the box and read
 * over whichever address the app is using, so it answers on the home network as well as over
 * Tailscale. The relay owns it; nothing is cached here.
 */
interface UptimeRepository {
    suspend fun getUptime(range: UptimeRange): Result<ServerUptime>
}
