package com.meticulouscreations.homesafe.finance

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.meticulouscreations.homesafe.data.AppDatabase
import com.meticulouscreations.homesafe.data.FinancePreferencesRepositoryImpl
import com.meticulouscreations.homesafe.data.exportedSchema
import com.meticulouscreations.homesafe.data.testContext
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
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
 * Schema 23 to 24, on a real SQLite file: the finance preferences row gains what the drawer's
 * card shows, an install from before it keeping the S&P 500 and net worth it always had, and the
 * tone chosen then. No fallback to wiping the database is allowed, so a missing or wrong migration
 * fails here.
 */
class FinanceDrawerMigrationTest {

    /** The database as version 23 created it, read from the schema Room exported then. */
    private fun createVersion23(connection: SQLiteConnection) {
        val schema = Json.parseToJsonElement(exportedSchema(23)).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            connection.execSQL(entity.sql("createSql", table))
            for (index in (entity["indices"] as? JsonArray).orEmpty()) connection.execSQL(index.jsonObject.sql("createSql", table))
        }
        for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = 23")
    }

    private fun JsonObject.sql(key: String, table: String) = getValue(key).jsonPrimitive.content.replace("\${TABLE_NAME}", table)

    @Test
    fun aToneKeptUnderSchema23StaysAndTheCardKeepsWhatItShowed() = runBlocking {
        val file = File.createTempFile("homesafe-23-to-24", ".db").apply { delete() }
        val driver = BundledSQLiteDriver()
        try {
            val old = driver.open(file.absolutePath)
            createVersion23(old)
            old.execSQL("INSERT INTO FinancePreferencesEntity VALUES (0, 'BRIGHT_SIDE')")
            old.close()

            val database = Room.databaseBuilder<AppDatabase>(context = testContext(), name = file.absolutePath).setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
            try {
                val preferences = FinancePreferencesRepositoryImpl(database.settingsDao())
                assertEquals(EconomyTone.BRIGHT_SIDE, preferences.observeEconomyTone().first())
                assertEquals(FinanceDrawerItem.DEFAULT, preferences.observeDrawerItems().first())

                preferences.setDrawerItems(listOf(FinanceDrawerItem.CASH, FinanceDrawerItem.DOW))
                assertEquals(listOf(FinanceDrawerItem.DOW, FinanceDrawerItem.CASH), preferences.observeDrawerItems().first())
                assertEquals(EconomyTone.BRIGHT_SIDE, preferences.observeEconomyTone().first())
            } finally {
                database.close()
            }
        } finally {
            file.delete()
        }
    }
}
