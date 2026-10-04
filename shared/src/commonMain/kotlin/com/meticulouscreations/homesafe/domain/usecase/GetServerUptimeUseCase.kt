package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeRange
import com.meticulouscreations.homesafe.domain.repository.UptimeRepository
import dev.zacsweers.metro.Inject

/** The server's uptime record over [UptimeRange], read from the relay now. */
@Inject
class GetServerUptimeUseCase(private val uptimeRepository: UptimeRepository) {
    suspend operator fun invoke(range: UptimeRange): Result<ServerUptime> = uptimeRepository.getUptime(range)
}
