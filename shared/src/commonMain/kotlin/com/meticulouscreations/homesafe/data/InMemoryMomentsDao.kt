package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A non-persistent [MomentsDao] for platforms without a working SQLite driver yet, and the
 * stand-in the repository's tests read. It keeps nothing across a launch — on the web the feed is
 * simply the session's, as it was before there was a cache at all — but it answers every query
 * exactly as the Room DAO does, so the code above it takes one path everywhere.
 */
class InMemoryMomentsDao : MomentsDao {
    private val lock = Mutex()

    /** Keyed the way the table is: (serverUrl, id). */
    private val rows = LinkedHashMap<Pair<String, String>, MomentEventEntity>()

    override suspend fun page(
        serverUrl: String,
        beforeEpochSeconds: Double?,
        cameraName: String?,
        limit: Int,
    ): List<MomentEventEntity> = lock.withLock {
        rows.values
            .filter { row ->
                row.serverUrl == serverUrl &&
                    (beforeEpochSeconds == null || row.startEpochSeconds < beforeEpochSeconds) &&
                    (cameraName == null || row.cameraName == cameraName)
            }
            .sortedByDescending { it.startEpochSeconds }
            .take(limit)
    }

    override suspend fun insertAll(events: List<MomentEventEntity>) = lock.withLock {
        // REPLACE semantics per (serverUrl, id), like the Room DAO.
        events.forEach { rows[it.serverUrl to it.id] = it }
    }

    override suspend fun deleteMissingInRange(
        serverUrl: String,
        cameraName: String?,
        fromEpochSeconds: Double,
        beforeEpochSeconds: Double?,
        keptIds: List<String>,
    ) = lock.withLock {
        val kept = keptIds.toSet()
        delete { row ->
            row.serverUrl == serverUrl &&
                (cameraName == null || row.cameraName == cameraName) &&
                row.startEpochSeconds >= fromEpochSeconds &&
                (beforeEpochSeconds == null || row.startEpochSeconds < beforeEpochSeconds) &&
                row.id !in kept
        }
    }

    override suspend fun trimToNewest(serverUrl: String, keep: Int) = lock.withLock {
        val newest = rows.values
            .filter { it.serverUrl == serverUrl }
            .sortedByDescending { it.startEpochSeconds }
            .take(keep)
            .mapTo(HashSet()) { it.id }
        delete { it.serverUrl == serverUrl && it.id !in newest }
    }

    /** Under [lock]. Collected first, then removed by key: never mutates the map through a view it is reading. */
    private fun delete(doomed: (MomentEventEntity) -> Boolean) {
        rows.values.filter(doomed).map { it.serverUrl to it.id }.forEach { rows.remove(it) }
    }
}
