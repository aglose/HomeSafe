package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.AlertVolume

/** How noisy each alert rule would have been lately, read from the server's recent detections. */
interface AlertVolumeRepository {
    suspend fun estimate(): Result<AlertVolume>
}
