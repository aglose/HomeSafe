package com.meticulouscreations.homesafe.fitness

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.FitnessRunsDatabase
import com.meticulouscreations.homesafe.data.FitnessSyncRowEntity
import com.meticulouscreations.homesafe.data.FitnessSyncStateEntity
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
 * `fitness_runs.db` from version 1 to 2, on a real SQLite file: what was last agreed with the
 * server arrives beside the runs, empty, and the runs that were there are still there. This database
 * has no fallback to wiping it, so a wrong migration fails instead of taking the runs with it.
 */
class FitnessSyncMigrationTest {

    private fun createVersion1(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(1, "FitnessRunsDatabase")).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 1")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun runsKeptUnderVersion1AreAllThereUnder2WithNothingYetAgreed() = runBlocking {
        val file = File.createTempFile("fitness-runs-1-to-2", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion1(old)
            old.execSQL("INSERT INTO FitnessRecordedRunEntity VALUES ('r1', 1000, 601000, -14400, 1609.0, 590000)")
            old.execSQL("INSERT INTO FitnessRunEventEntity VALUES ('r1', 0, 'POSITION', 1000, 40.0, -75.0, 5.0)")
            old.close()

            val database = Room.databaseBuilder<FitnessRunsDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val runs = database.fitnessRunDao()
                assertEquals(listOf("r1"), runs.observeFinished().first().map { it.id })
                assertEquals(listOf(40.0), runs.events("r1").map { it.latitude })

                val agreed = database.fitnessSyncDao()
                assertNull(agreed.state())
                assertEquals(emptyList(), agreed.rows())
                val state = FitnessSyncStateEntity(server = "https://example", owner = "someone", cursor = 3)
                agreed.record(listOf(FitnessSyncRowEntity("set/1", "{}")), emptyList(), state)
                assertEquals(state, agreed.state())
                assertEquals(listOf("set/1"), agreed.rows().map { it.key })
                agreed.forget()
                assertNull(agreed.state())
                assertEquals(emptyList(), agreed.rows())
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
