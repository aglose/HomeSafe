package com.meticulouscreations.homesafe.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** A singleton row (always [id] = 0) holding the user's alert preferences. */
@Entity
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val pushNotificationsEnabled: Boolean,
    @ColumnInfo(defaultValue = "1") val notifyPeople: Boolean,
    @ColumnInfo(defaultValue = "1") val notifyVehicles: Boolean,
    @ColumnInfo(defaultValue = "0") val notifyAnimals: Boolean,
)
