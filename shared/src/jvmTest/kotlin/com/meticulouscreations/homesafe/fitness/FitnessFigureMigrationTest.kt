package com.meticulouscreations.homesafe.fitness

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.AppDatabase
import com.meticulouscreations.homesafe.data.FitnessPreferencesEntity
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
 * Schema 21 to 22, on a real SQLite file: the fitness app's preferences table arrives, and the
 * training log and the heart-rate settings that were there are still there. As with
 * [FitnessHeartMigrationTest], no fallback to wiping the database is allowed here, so a missing
 * or wrong migration fails instead of quietly taking the log with it.
 */
class FitnessFigureMigrationTest {

    /** The database as version 21 created it, read from the schema Room exported then. */
    private fun createVersion21(connection: SQLiteConnection) {
        // Gradle runs the JVM tests from the module's directory, where the exported schemas are.
        val schema = Json.parseToJsonElement(File("schemas/com.meticulouscreations.homesafe.data.AppDatabase/21.json").readText()).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 21")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun aLogKeptUnderSchema21IsAllThereUnder22WithTheFigureChoiceBesideIt() = runBlocking {
        val file = File.createTempFile("homesafe-21-to-22", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion21(old)
            old.execSQL("INSERT INTO FitnessExerciseEntity VALUES ('legs/hack-squat', 'Hack squat', 'LEGS', 'MACHINE', 'WEIGHT', 'QUADS', 'GLUTES', 8, 12, 10.0, 150, '', 0)")
            old.execSQL("INSERT INTO FitnessWorkoutEntity VALUES (7, 'LEGS', 1000, 4000)")
            old.execSQL("INSERT INTO FitnessSetEntity VALUES (1, 'legs/hack-squat', 7, 1200, 250.0, 11, NULL, '', 0)")
            old.execSQL("INSERT INTO FitnessHeartSettingsEntity VALUES (0, 188, NULL, 58, 'AA:BB:CC:00:00:01', 'Test Band')")
            old.execSQL("INSERT INTO FitnessHeartSummaryEntity VALUES (7, 1000, 2000, 3000, 4000, 5000, 6000, 2700000, 174)")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val dao = database.fitnessDao()
                assertEquals(listOf("Hack squat"), dao.observeExercises().first().map { it.name })
                assertEquals(listOf(11), dao.sets().map { it.reps })
                assertEquals(4000L, dao.workout(7)?.finishedAtEpochSeconds)
                assertEquals(188, dao.heartSettings()?.maxBpm)
                assertEquals("Test Band", dao.heartSettings()?.sensorName)
                assertEquals(listOf(174), dao.observeHeartSummaries().first().map { it.peakBpm })

                // The new table is there, empty (which reads as the default figure), and takes its one row.
                assertNull(dao.observePreferences().first())
                dao.upsertPreferences(FitnessPreferencesEntity(figureStyle = "STONE"))
                assertEquals(FitnessPreferencesEntity(id = 0, figureStyle = "STONE"), dao.observePreferences().first())
                dao.upsertPreferences(FitnessPreferencesEntity(figureStyle = "FORGED"))
                assertEquals("FORGED", dao.observePreferences().first()?.figureStyle)
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
