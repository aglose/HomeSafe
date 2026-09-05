package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ServerOverview
import kotlinx.coroutines.flow.Flow

/** Live facts about the connected Frigate server, and the two per-camera switches it lets the app flip. */
interface ServerStatusRepository {
    /** Null until connected and first loaded; re-polled on a short interval while observed. */
    fun observeOverview(): Flow<ServerOverview?>

    /** Whether the last poll failed — so the tab can say so instead of showing stale numbers silently. */
    fun observeError(): Flow<String?>

    suspend fun refresh()

    /** Turns a camera's object detection on or off, live on the server, then re-reads its state. */
    suspend fun setCameraDetection(cameraName: String, enabled: Boolean): Result<Unit>

    /** Turns a camera's motion detection on or off, live on the server, then re-reads its state. */
    suspend fun setCameraMotion(cameraName: String, enabled: Boolean): Result<Unit>
}
