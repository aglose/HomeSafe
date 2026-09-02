package com.meticulouscreations.homesafe.data

import androidx.room3.Entity

@Entity(primaryKeys = ["serverUrl", "name"])
data class CameraEntity(
    val serverUrl: String,
    val name: String,
    val enabled: Boolean,
    val liveStreamName: String = name,
    val gridStreamName: String = name,
)
