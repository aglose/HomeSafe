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
 * Schema 25 to 26, on a real SQLite file: the Home page's own camera records arrive (the Nest
 * cameras kept from last time, and each camera's place and orientation), and the Frigate camera
 * cache that was there is still there. No fallback to wiping the database is allowed.
 */
class CameraListMigrationTest {

    /** The database as version 25 created it, read from the schema Room exported then. */
    private fun createVersion25(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(25)).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 25")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun theCameraCacheSurvivesAndTheNewRecordsTakeRows() = runBlocking {
        val file = File.createTempFile("homesafe-25-to-26", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        val server = "https://box.tailnet.ts.net:8971"
        try {
            val old = driver.open(file.absolutePath)
            createVersion25(old)
            old.execSQL("INSERT INTO CameraEntity VALUES ('$server', 'amcrest_1', 1, 'amcrest_1', 'amcrest_1_sub')")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                assertEquals(listOf("amcrest_1"), database.cameraDao().observeByServer(server).first().map { it.name })

                val dao = database.cameraListDao()
                assertEquals(emptyList(), dao.observeNestCameras(server).first())
                dao.insertNestCameras(listOf(NestCameraEntity(server, "kids_room"), NestCameraEntity(server, "kitchen")))
                dao.deleteOtherNestCameras(server, listOf("kitchen"))
                assertEquals(listOf("kitchen"), dao.observeNestCameras(server).first().map { it.name })

                dao.upsertLayouts(listOf(CameraLayoutEntity(server, "kids_room", position = 1, quarterTurns = 1, tiltDegrees = -8f)))
                assertEquals(listOf(CameraLayoutEntity(server, "kids_room", 1, 1, -8f)), dao.observeLayouts(server).first())
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
