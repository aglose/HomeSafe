package com.meticulouscreations.homesafe.atlas

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.AppDatabase
import com.meticulouscreations.homesafe.data.AtlasMoveEntity
import com.meticulouscreations.homesafe.data.AtlasPlaceEntity
import com.meticulouscreations.homesafe.data.AtlasStateEntity
import com.meticulouscreations.homesafe.data.AtlasTrackEntity
import com.meticulouscreations.homesafe.data.AtlasVisitEntity
import com.meticulouscreations.homesafe.data.AtlasYearEntity
import com.meticulouscreations.homesafe.data.exportedSchema
import com.meticulouscreations.homesafe.data.testContext
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

/**
 * Schema 22 to 23, on a real SQLite file: the atlas app's tables arrive, and what was already on
 * the device (the training log, the saved finance figures) is still there. The app's own builders
 * fall back to wiping the database when a migration is missing, which would take all of that with
 * it; this one doesn't, so a missing or wrong migration fails here.
 */
class AtlasMigrationTest {

    /** The database as version 22 created it, read from the schema Room exported then. */
    private fun createVersion22(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(22)).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 22")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    private fun visit(year: Int, start: Long) = AtlasVisitEntity(year = year, startEpochSeconds = start, endEpochSeconds = start + 3600, offsetMinutes = -360, latitude = 40.0, longitude = -89.0, placeId = "ChIJhome", kind = "home")

    @Test
    fun whatWasKeptUnderSchema22IsAllThereUnder23WithTheAtlasTablesBesideIt() = runBlocking {
        val file = File.createTempFile("homesafe-22-to-23", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion22(old)
            old.execSQL("INSERT INTO FitnessWorkoutEntity VALUES (7, 'LEGS', 1000, 4000)")
            old.execSQL("INSERT INTO FinanceSavedEntity VALUES ('sheet', '{}', 100)")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                assertEquals(4000L, database.fitnessDao().workout(7)?.finishedAtEpochSeconds)
                assertEquals("{}", database.financeSavedDao().saved("sheet")?.json)

                // The new tables are there, empty, and a year goes in and is replaced whole.
                val dao = database.atlasDao()
                assertTrue(dao.visits().isEmpty())
                assertNull(dao.state())
                val start = 1_700_000_000L
                dao.replaceYear(
                    AtlasYearEntity(2023, "a", 2, 1, 2, 10),
                    listOf(visit(2023, start + 7200), visit(2023, start)),
                    listOf(AtlasMoveEntity(year = 2023, startEpochSeconds = start + 3600, endEpochSeconds = start + 7200, offsetMinutes = -360, mode = "drive", latitude = 40.0, longitude = -89.0, toLatitude = 40.05, toLongitude = -89.02, meters = 6000.0)),
                    listOf(AtlasTrackEntity(year = 2023, startEpochSeconds = start + 3600, endEpochSeconds = start + 7200, offsetMinutes = -360, points = "_p~iF~ps|U", times = "?", count = 2)),
                )
                dao.replaceYear(AtlasYearEntity(2024, "b", 1, 0, 0, 10), listOf(visit(2024, start + 40_000_000)), emptyList(), emptyList())
                // In the order they happened, whatever order they were written in.
                assertEquals(listOf(start, start + 7200, start + 40_000_000), dao.visits().map { it.startEpochSeconds })
                assertEquals(listOf(2023, 2024), dao.years().map { it.year })
                assertEquals(1, dao.tracksBetween(start, start + 4000).size)
                assertTrue(dao.tracksBetween(start + 8000, start + 9000).isEmpty())

                dao.replaceYear(AtlasYearEntity(2023, "c", 1, 0, 0, 20), listOf(visit(2023, start + 100)), emptyList(), emptyList())
                assertEquals(listOf(start + 100, start + 40_000_000), dao.visits().map { it.startEpochSeconds })
                assertTrue(dao.moves().isEmpty())
                assertTrue(dao.tracks().isEmpty())
                assertEquals("c", dao.years().first().etag)

                dao.replacePlaces(listOf(AtlasPlaceEntity("ChIJhome", 40.0, -89.0, "home", "Home", null, null, "Fairview", "IL", "US")))
                dao.replacePlaces(listOf(AtlasPlaceEntity("ChIJwork", 40.05, -89.02, "work", null, null, null, null, null, null)))
                assertEquals(listOf("ChIJwork"), dao.places().map { it.id })
                dao.upsert(AtlasStateEntity(owner = "https://box", placesVersion = 3, checkedAtEpochSeconds = 50))
                assertEquals(3, dao.state()?.placesVersion)

                dao.deleteYear(2024)
                assertEquals(listOf(2023), dao.years().map { it.year })
                dao.clear()
                assertTrue(dao.visits().isEmpty() && dao.years().isEmpty() && dao.places().isEmpty())
                assertNull(dao.state())
                // Clearing the atlas takes nothing else with it.
                assertEquals(4000L, database.fitnessDao().workout(7)?.finishedAtEpochSeconds)
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
