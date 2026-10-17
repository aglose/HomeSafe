package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.CameraSource
import com.meticulouscreations.homesafe.domain.model.OUTDOOR_STREAM_PREFIX
import kotlinx.coroutines.flow.Flow

/**
 * The Google Nest cameras the connected server's go2rtc pulls from Google on demand (see
 * [CameraSource.Nest]). Separate from [CameraRepository], whose cameras are Frigate's and are
 * what the rest of the app (Moments, recordings, alerts) is built on.
 */
interface NestCameraRepository {
    /**
     * The Nest cameras: [CameraPlacement.Indoor], or [CameraPlacement.Outdoor] for a stream named
     * with the [OUTDOOR_STREAM_PREFIX]. Null until the server has answered
     * for the first time (or failed to), empty when signed out or when there are none. The first
     * collection for a server asks it; after that it holds what the server last said.
     */
    fun observeNestCameras(): Flow<List<Camera>?>

    /** Asks the server again now. A failure keeps the list it had. */
    suspend fun refresh()
}
