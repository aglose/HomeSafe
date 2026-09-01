package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A non-persistent [CameraDao] for platforms without a working SQLite driver yet. */
class InMemoryCameraDao : CameraDao {
    private val camerasByServer = MutableStateFlow<Map<String, List<CameraEntity>>>(emptyMap())

    override fun observeByServer(serverUrl: String): Flow<List<CameraEntity>> =
        camerasByServer.map { it[serverUrl].orEmpty() }

    override suspend fun deleteByServer(serverUrl: String) {
        camerasByServer.value = camerasByServer.value - serverUrl
    }

    override suspend fun insertAll(cameras: List<CameraEntity>) {
        val byServer = cameras.groupBy { it.serverUrl }
        camerasByServer.value = camerasByServer.value + byServer
    }
}
