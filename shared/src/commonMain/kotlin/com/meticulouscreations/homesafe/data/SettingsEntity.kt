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
    /** Added in schema 9: automatic presence (geofence + LAN). Off until the user turns it on and grants location. */
    @ColumnInfo(defaultValue = "0") val automaticPresence: Boolean = false,
)

/**
 * A singleton row (always [id] = 0): who this install is to the push relay. Added in schema 9.
 * [deviceId] is minted once and never changes; [secret] is what the relay handed back at
 * registration and lets this install report its own presence from a background wake that has no
 * Frigate session. The home fields cache the household's home so the geofence can be re-armed
 * after a reboot without asking the relay.
 */
@Entity
data class DeviceIdentityEntity(
    @PrimaryKey val id: Int = 0,
    val deviceId: String,
    val secret: String? = null,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val homeRadiusMeters: Double? = null,
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

/**
 * A singleton row (always [id] = 0) holding the detail player's preferences. Added in schema 8;
 * a missing row means the defaults (see `PlaybackPreferences.DEFAULT`).
 */
@Entity
data class PlaybackPreferencesEntity(
    @PrimaryKey val id: Int = 0,
    /** A `StreamQuality` name. Stored as text so an unknown value degrades to the default instead of failing to read. */
    val quality: String,
    val soundOn: Boolean,
)
