package com.meticulouscreations.homesafe.domain.model

/** User-configurable detection/alert toggles, persisted across sessions. */
data class DetectionSettings(
    val autoPurgeOldMedia: Boolean,
    val globalMotionDetection: Boolean,
    val coralEdgeInference: Boolean,
    val faceRecognition: Boolean,
    val pushNotificationsEnabled: Boolean,
)
