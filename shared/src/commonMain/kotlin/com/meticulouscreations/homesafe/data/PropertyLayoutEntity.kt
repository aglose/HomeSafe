package com.meticulouscreations.homesafe.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Where one camera sits on the property plan. Added in schema 10. Keyed by camera name alone,
 * not by server: a placement describes the house, so it outlives a re-scan of the camera cache
 * (which deletes [CameraEntity] rows per server) and survives moving to a second Frigate host.
 */
@Entity
data class CameraPlacementEntity(
    @PrimaryKey val cameraName: String,
    val x: Float,
    val y: Float,
)

/**
 * A singleton row (always [id] = 0) holding the Home tab's chosen layout. Added in schema 10;
 * a missing row means [com.meticulouscreations.homesafe.domain.model.HomeLayout.DEFAULT].
 *
 * Its own table rather than a column on [SettingsEntity] because that row is written wholesale
 * by the alert settings repository — a second writer would clobber whatever it hadn't read.
 */
@Entity
data class HomeLayoutEntity(
    @PrimaryKey val id: Int = 0,
    /** A `HomeLayout` name. Text so an unknown value degrades to the default instead of failing to read. */
    val layout: String,
)
