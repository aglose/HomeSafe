package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert

/**
 * Something the finance app fetched, kept as JSON so the app has it to open on when nothing can be
 * fetched: the budget sheet, a month of card spending, the linked banks, a quote, a price history,
 * an economic series. Added in schema 21. [id] says which (see `FinanceSaved`): `sheet`,
 * `budget:2026-10`, `quote:^GSPC`.
 */
@Entity
data class FinanceSavedEntity(
    @PrimaryKey val id: String,
    val json: String,
    val savedAtEpochSeconds: Long,
)

@Dao
interface FinanceSavedDao {
    @Query("SELECT * FROM FinanceSavedEntity WHERE id = :id")
    suspend fun saved(id: String): FinanceSavedEntity?

    @Upsert
    suspend fun upsert(entity: FinanceSavedEntity)

    @Query("DELETE FROM FinanceSavedEntity WHERE id = :id")
    suspend fun delete(id: String)

    /** Everything whose id starts with [prefix]. */
    @Query("DELETE FROM FinanceSavedEntity WHERE id LIKE :prefix || '%'")
    suspend fun deleteStartingWith(prefix: String)

    /** Everything whose id starts with [prefix] and was saved before [before]. */
    @Query("DELETE FROM FinanceSavedEntity WHERE id LIKE :prefix || '%' AND savedAtEpochSeconds < :before")
    suspend fun deleteStartingWithSavedBefore(prefix: String, before: Long)
}

/** [FinanceSavedDao] in memory: for the web target, which has no database, and for tests. */
class InMemoryFinanceSavedDao : FinanceSavedDao {
    private val rows = HashMap<String, FinanceSavedEntity>()

    override suspend fun saved(id: String): FinanceSavedEntity? = rows[id]

    override suspend fun upsert(entity: FinanceSavedEntity) {
        rows[entity.id] = entity
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun deleteStartingWith(prefix: String) {
        rows.keys.removeAll { it.startsWith(prefix) }
    }

    override suspend fun deleteStartingWithSavedBefore(prefix: String, before: Long) {
        rows.values.removeAll { it.id.startsWith(prefix) && it.savedAtEpochSeconds < before }
    }
}
