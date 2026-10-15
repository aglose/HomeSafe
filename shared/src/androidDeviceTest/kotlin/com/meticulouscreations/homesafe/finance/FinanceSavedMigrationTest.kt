package com.meticulouscreations.homesafe.finance

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.AppDatabase
import com.meticulouscreations.homesafe.data.FinanceSavedEntity
import com.meticulouscreations.homesafe.data.exportedSchema
import com.meticulouscreations.homesafe.data.testContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Schema 20 to 21, on a real SQLite file: the table the finance app keeps its last answers in
 * arrives, and what was already on the device (the watchlist, the training log) is still there.
 * The app's own builders fall back to wiping the database when a migration is missing, which
 * would take all of that with it; this one doesn't, so a missing or wrong migration fails here.
 */
class FinanceSavedMigrationTest {

    /** The database as version 20 created it, read from the schema Room exported then. */
    private fun createVersion20(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(20)).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 20")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun whatWasKeptUnderSchema20IsAllThereUnder21WithTheSavedTableBesideIt() = runBlocking {
        val file = File.createTempFile("homesafe-20-to-21", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion20(old)
            old.execSQL("INSERT INTO FitnessWorkoutEntity VALUES (7, 'LEGS', 1000, 4000)")
            old.execSQL("INSERT INTO FitnessBodyweightEntity VALUES (20000, 181.4)")
            old.execSQL("INSERT INTO WeatherPlaceEntity VALUES ('40.0,-100.0', 'Somewhere', 'Nebraska', 40.0, -100.0, 0)")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                assertEquals(4000L, database.fitnessDao().workout(7)?.finishedAtEpochSeconds)
                assertEquals(listOf(181.4), database.fitnessDao().observeBodyweights().first().map { it.pounds })
                assertEquals(listOf("Somewhere"), database.weatherDao().places().map { it.name })

                // The new table is there, empty, and keeps what it is given.
                val dao = database.financeSavedDao()
                assertNull(dao.saved("sheet"))
                dao.upsert(FinanceSavedEntity("sheet", """{"title":"Budget"}""", 100))
                dao.upsert(FinanceSavedEntity("budget:current", "{}", 100))
                dao.upsert(FinanceSavedEntity("budget:2026-09", "{}", 100))
                dao.upsert(FinanceSavedEntity("quote:OLD", "{}", 100))
                dao.upsert(FinanceSavedEntity("quote:NEW", "{}", 900))
                dao.upsert(FinanceSavedEntity("sheet", """{"title":"Budget, later"}""", 200))
                assertEquals(FinanceSavedEntity("sheet", """{"title":"Budget, later"}""", 200), dao.saved("sheet"))

                dao.deleteStartingWith("budget:")
                assertNull(dao.saved("budget:current"))
                assertNull(dao.saved("budget:2026-09"))
                dao.deleteStartingWithSavedBefore("quote:", 500)
                assertNull(dao.saved("quote:OLD"))
                assertEquals(900L, dao.saved("quote:NEW")?.savedAtEpochSeconds)
                dao.delete("sheet")
                assertNull(dao.saved("sheet"))
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
