package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import kotlinx.coroutines.flow.Flow

/**
 * The indoor cameras: Google Nest cameras the connected server's go2rtc pulls from Google on
 * demand (see [CameraPlacement.Indoor]). Separate from [CameraRepository], whose cameras are
 * Frigate's and are what the rest of the app (Moments, recordings, alerts) is built on.
 */
interface IndoorCameraRepository {
    /**
     * The indoor cameras, every one [CameraPlacement.Indoor]. Null until the server has answered
     * for the first time (or failed to), empty when signed out or when there are none. The first
     * collection for a server asks it; after that it holds what the server last said.
     */
    fun observeIndoorCameras(): Flow<List<Camera>?>

    /** Asks the server again now. A failure keeps the list it had. */
    suspend fun refresh()
}
