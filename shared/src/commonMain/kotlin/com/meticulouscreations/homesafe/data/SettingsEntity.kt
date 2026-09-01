package com.meticulouscreations.homesafe.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** A singleton row (always [id] = 0) holding the user's detection/alert toggle state. */
@Entity
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val autoPurgeOldMedia: Boolean,
    val globalMotionDetection: Boolean,
    val coralEdgeInference: Boolean,
    val faceRecognition: Boolean,
    val pushNotificationsEnabled: Boolean,
)
