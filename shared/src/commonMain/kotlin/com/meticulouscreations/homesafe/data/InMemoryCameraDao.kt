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

    override suspend fun deleteOthers(serverUrl: String, names: List<String>) {
        val kept = camerasByServer.value[serverUrl].orEmpty().filter { it.name in names }
        camerasByServer.value = camerasByServer.value + (serverUrl to kept)
    }

    override suspend fun insertAll(cameras: List<CameraEntity>) {
        // REPLACE semantics per (serverUrl, name), like the Room DAO: existing rows survive.
        val updated = camerasByServer.value.toMutableMap()
        cameras.groupBy { it.serverUrl }.forEach { (serverUrl, incoming) ->
            val incomingNames = incoming.map { it.name }.toSet()
            updated[serverUrl] = updated[serverUrl].orEmpty().filter { it.name !in incomingNames } + incoming
        }
        camerasByServer.value = updated
    }
}
