package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A non-persistent [ConnectionHistoryDao] for platforms without a working SQLite driver yet. */
class InMemoryConnectionHistoryDao : ConnectionHistoryDao {
    private val mostRecent = MutableStateFlow<ConnectionHistoryEntity?>(null)
    private var nextId = 1L

    /** How many entries have been inserted; handy for tests. */
    var insertCount: Int = 0
        private set

    override suspend fun insert(entry: ConnectionHistoryEntity) {
        mostRecent.value = entry.copy(id = nextId++)
        insertCount++
    }

    override fun mostRecentAsFlow(): Flow<ConnectionHistoryEntity?> = mostRecent
}
