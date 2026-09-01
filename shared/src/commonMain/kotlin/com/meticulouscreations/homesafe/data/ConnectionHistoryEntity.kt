package com.meticulouscreations.homesafe.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverUrl: String,
    val connectedAtEpochMillis: Long,
)
