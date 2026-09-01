package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.Camera
import kotlinx.coroutines.flow.Flow

/** Exposes the cameras reported by the currently connected Frigate server, cached locally. */
interface CameraRepository {
    fun observeCameras(): Flow<List<Camera>>
}
