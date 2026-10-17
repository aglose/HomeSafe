package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
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
     * with the [OUTDOOR_STREAM_PREFIX]. Opens on the ones the server listed last time (kept on the
     * device, so they are there the moment the page is), and follows the server's answers after
     * that. Empty when signed out or when there are none; null only until the device's own record
     * has been read. The first collection for a server in a session also asks it again.
     */
    fun observeNestCameras(): Flow<List<Camera>?>

    /** Asks the connected server again now. A failure keeps the list it had. */
    suspend fun refresh()

    /**
     * Asks [connection]'s server, signed in or not yet (go2rtc takes no credentials): what the
     * launch does as soon as it knows which server it is about to land on.
     */
    suspend fun refresh(connection: ActiveConnection)
}
