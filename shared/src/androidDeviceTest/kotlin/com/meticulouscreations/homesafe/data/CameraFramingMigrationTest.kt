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
 * Schema 26 to 27, on a real SQLite file: each camera's layout gains its crop (the whole picture
 * for a camera already arranged) and each kept Nest camera its card stream (none), and what was
 * there is still there. No fallback to wiping the database is allowed.
 */
class CameraFramingMigrationTest {

    /** The database as version 26 created it, read from the schema Room exported then. */
    private fun createVersion26(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(26)).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 26")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun anArrangedCameraKeepsItsTurnAndShowsTheWholePicture() = runBlocking {
        val file = File.createTempFile("homesafe-26-to-27", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        val server = "https://box.tailnet.ts.net:8971"
        try {
            val old = driver.open(file.absolutePath)
            createVersion26(old)
            old.execSQL("INSERT INTO CameraLayoutEntity VALUES ('$server', 'kids_room', 1, 1, -8.0)")
            old.execSQL("INSERT INTO NestCameraEntity VALUES ('$server', 'kitchen')")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val dao = database.cameraListDao()
                assertEquals(listOf(CameraLayoutEntity(server, "kids_room", position = 1, quarterTurns = 1, tiltDegrees = -8f)), dao.observeLayouts(server).first())
                assertEquals(listOf(NestCameraEntity(server, "kitchen", gridStreamName = null)), dao.observeNestCameras(server).first())

                dao.upsertLayouts(listOf(CameraLayoutEntity(server, "kids_room", 1, 1, -8f, cropZoom = 2f, cropCenterX = 0.3f, cropCenterY = 0.6f)))
                dao.insertNestCameras(listOf(NestCameraEntity(server, "kitchen", gridStreamName = "kitchen_sub")))
                assertEquals(2f, dao.observeLayouts(server).first().single().cropZoom)
                assertEquals("kitchen_sub", dao.observeNestCameras(server).first().single().gridStreamName)
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
