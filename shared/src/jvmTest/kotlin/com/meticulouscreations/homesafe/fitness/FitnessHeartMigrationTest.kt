package com.meticulouscreations.homesafe.fitness

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.AppDatabase
import com.meticulouscreations.homesafe.data.FitnessHeartSettingsEntity
import com.meticulouscreations.homesafe.data.FitnessHeartSummaryEntity
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
 * Schema 19 to 20, on a real SQLite file: the heart-rate tables arrive and the training log that
 * was there is still there. The app's own builders fall back to wiping the database when a
 * migration is missing, which would take the log with it; this one doesn't, so a missing or
 * wrong migration fails here instead.
 */
class FitnessHeartMigrationTest {

    /** The database as version 19 created it, read from the schema Room exported then. */
    private fun createVersion19(connection: SQLiteConnection) {
        // Gradle runs the JVM tests from the module's directory, where the exported schemas are.
        val schema = Json.parseToJsonElement(File("schemas/com.meticulouscreations.homesafe.data.AppDatabase/19.json").readText()).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 19")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun aLogKeptUnderSchema19IsAllThereUnder20WithTheHeartTablesBesideIt() = runBlocking {
        val file = File.createTempFile("homesafe-19-to-20", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion19(old)
            old.execSQL("INSERT INTO FitnessExerciseEntity VALUES ('legs/hack-squat', 'Hack squat', 'LEGS', 'MACHINE', 'WEIGHT', 'QUADS', 'GLUTES', 8, 12, 10.0, 150, '', 0)")
            old.execSQL("INSERT INTO FitnessWorkoutEntity VALUES (7, 'LEGS', 1000, 4000)")
            old.execSQL("INSERT INTO FitnessSetEntity VALUES (1, 'legs/hack-squat', 7, 1200, 250.0, 11, NULL, '', 0)")
            old.execSQL("INSERT INTO FitnessSetEntity VALUES (2, 'legs/hack-squat', 7, 1500, 250.0, 9, NULL, 'pause', 0)")
            old.execSQL("INSERT INTO FitnessPhaseEntity VALUES (1, 'CUT', 500)")
            old.execSQL("INSERT INTO FitnessBodyweightEntity VALUES (20000, 181.4)")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val dao = database.fitnessDao()
                assertEquals(listOf("Hack squat"), dao.observeExercises().first().map { it.name })
                assertEquals(listOf(11, 9), dao.sets().map { it.reps })
                assertEquals("pause", dao.sets().last().note)
                assertEquals(4000L, dao.workout(7)?.finishedAtEpochSeconds)
                assertEquals(listOf("CUT"), dao.observePhases().first().map { it.kind })
                assertEquals(listOf(181.4), dao.observeBodyweights().first().map { it.pounds })

                // The new tables are there, empty, and take rows.
                assertNull(dao.heartSettings())
                assertEquals(emptyList(), dao.observeHeartSummaries().first())
                val settings = FitnessHeartSettingsEntity(maxBpm = 188, age = null, restingBpm = 58, sensorAddress = "AA:BB:CC:00:00:01", sensorName = "Test Band")
                dao.upsertHeartSettings(settings)
                assertEquals(settings, dao.heartSettings())
                val summary = FitnessHeartSummaryEntity(7, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 2_700_000, 174)
                dao.upsertHeartSummary(summary)
                assertEquals(listOf(summary), dao.observeHeartSummaries().first())
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
