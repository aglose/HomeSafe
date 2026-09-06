package com.meticulouscreations.homesafe.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** A singleton row (always [id] = 0) holding the user's alert preferences. */
@Entity
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val pushNotificationsEnabled: Boolean,
    /** Added in schema 7; the default keeps existing installs alerting for everyone until they choose otherwise. */
    @ColumnInfo(defaultValue = "0") val quietFamiliarPeople: Boolean = false,
)

/**
 * Which categories notify for one place on one camera. [zone] is the Frigate zone name, or
 * [NO_ZONE] for detections outside every zone on that camera. Places without a row use the
 * app's defaults, so a zone drawn after the fact starts out alerting like any other.
 */
@Entity(primaryKeys = ["camera", "zone"])
data class AlertZoneRuleEntity(
    val camera: String,
    val zone: String,
    val notifyPeople: Boolean,
    val notifyVehicles: Boolean,
    val notifyAnimals: Boolean,
) {
    companion object {
        /** SQLite can't key on NULL, so "no zone" is the empty string. */
        const val NO_ZONE = ""
    }
}
