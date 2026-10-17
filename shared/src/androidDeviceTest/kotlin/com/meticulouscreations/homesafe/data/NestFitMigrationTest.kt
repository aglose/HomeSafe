package com.meticulouscreations.homesafe.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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

/**
 * Schema 24 to 25, on a real SQLite file: the saved player preferences gain how the Nest cameras'
 * pictures sit in their boxes, and the quality and sound already chosen are still chosen. No
 * fallback to wiping the database is allowed, so a missing migration fails here.
 */
class NestFitMigrationTest {

    /** The database as version 24 created it, read from the schema Room exported then. */
    private fun createVersion24(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(24)).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 24")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun savedPreferencesKeepTheirChoicesAndShowNestPicturesWhole() = runBlocking {
        val file = File.createTempFile("homesafe-24-to-25", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion24(old)
            old.execSQL("INSERT INTO PlaybackPreferencesEntity VALUES (0, 'HIGH', 1)")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val dao = database.settingsDao()
                assertEquals(PlaybackPreferencesEntity(id = 0, quality = "HIGH", soundOn = true, nestFit = "Fit"), dao.observePlaybackPreferences().first())
                dao.upsertPlaybackPreferences(PlaybackPreferencesEntity(quality = "HIGH", soundOn = true, nestFit = "Fill"))
                assertEquals("Fill", dao.observePlaybackPreferences().first()?.nestFit)
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
