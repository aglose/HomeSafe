package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A non-persistent [CameraListDao], with the Room DAO's replace-by-key semantics. */
class InMemoryCameraListDao : CameraListDao {
    private val nest = MutableStateFlow<Map<String, List<NestCameraEntity>>>(emptyMap())
    private val layouts = MutableStateFlow<Map<String, List<CameraLayoutEntity>>>(emptyMap())

    override fun observeNestCameras(serverUrl: String): Flow<List<NestCameraEntity>> = nest.map { it[serverUrl].orEmpty() }

    override suspend fun deleteOtherNestCameras(serverUrl: String, names: List<String>) {
        nest.update { it + (serverUrl to it[serverUrl].orEmpty().filter { camera -> camera.name in names }) }
    }

    override suspend fun insertNestCameras(cameras: List<NestCameraEntity>) {
        nest.update { current -> replacing(current, cameras, NestCameraEntity::serverUrl, NestCameraEntity::name) }
    }

    override fun observeLayouts(serverUrl: String): Flow<List<CameraLayoutEntity>> = layouts.map { it[serverUrl].orEmpty() }

    override suspend fun upsertLayouts(layouts: List<CameraLayoutEntity>) {
        this.layouts.update { current -> replacing(current, layouts, CameraLayoutEntity::serverUrl, CameraLayoutEntity::name) }
    }

    private fun <T> replacing(current: Map<String, List<T>>, incoming: List<T>, server: (T) -> String, name: (T) -> String): Map<String, List<T>> {
        val updated = current.toMutableMap()
        incoming.groupBy(server).forEach { (serverUrl, rows) ->
            val names = rows.map(name).toSet()
            updated[serverUrl] = updated[serverUrl].orEmpty().filter { name(it) !in names } + rows
        }
        return updated
    }
}
