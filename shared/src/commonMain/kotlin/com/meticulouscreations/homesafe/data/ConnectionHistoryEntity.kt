package com.meticulouscreations.homesafe.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverUrl: String,
    /** Added in schema v4 (nullable, so the auto-migration is a plain ADD COLUMN). */
    val localUrl: String? = null,
    val connectedAtEpochMillis: Long,
)
