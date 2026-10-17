package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import kotlinx.coroutines.flow.Flow

/**
 * How the cameras sit on the Home page, on this device, for the connected server: their order in
 * each list and which way up each picture is. Kept by camera name, Frigate's and Nest's alike.
 */
interface CameraLayoutRepository {
    /** Each camera with a layout, by name; empty while signed out, or until anything has been arranged. */
    fun observeLayouts(): Flow<Map<String, CameraLayout>>

    /** Puts [cameraNames] (one list's cameras) in this order, leaving each one's orientation as it was. */
    suspend fun saveOrder(cameraNames: List<String>)

    /** Turns [cameraName]'s picture to [orientation], leaving its place in the list as it was. */
    suspend fun setOrientation(cameraName: String, orientation: CameraOrientation)
}
