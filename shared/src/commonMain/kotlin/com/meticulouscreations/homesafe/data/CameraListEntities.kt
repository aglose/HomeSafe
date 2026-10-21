package com.meticulouscreations.homesafe.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * A Google Nest camera the server's go2rtc relays, as it last said ([serverUrl] is the server's
 * identity, as for [CameraEntity]). Kept so the Home page has them the moment it opens, before
 * go2rtc has been asked again. Added in schema 26; [gridStreamName], its smaller stream for the
 * cards when the server has one (`<name>_sub`, as the Frigate cameras have), in schema 27.
 */
@Entity(primaryKeys = ["serverUrl", "name"])
data class NestCameraEntity(
    val serverUrl: String,
    val name: String,
    val gridStreamName: String? = null,
)

/**
 * How one camera sits on the Home page, on this device: where it comes in its list ([position],
 * null until the list has been arranged), which way up its picture is ([quarterTurns]
 * clockwise, then [tiltDegrees] more, positive clockwise), how much of it shows ([cropZoom]
 * times about [cropCenterX], [cropCenterY]; see `CameraCrop`), and whether it is left off the
 * page ([hidden]). Any camera, Frigate's or Nest's, by its name on [serverUrl]. Added in schema
 * 26; the crop in schema 27; [hidden] in schema 28.
 */
@Entity(primaryKeys = ["serverUrl", "name"])
data class CameraLayoutEntity(
    val serverUrl: String,
    val name: String,
    val position: Int? = null,
    @ColumnInfo(defaultValue = "0") val quarterTurns: Int = 0,
    @ColumnInfo(defaultValue = "0") val tiltDegrees: Float = 0f,
    @ColumnInfo(defaultValue = "1") val cropZoom: Float = 1f,
    @ColumnInfo(defaultValue = "0.5") val cropCenterX: Float = 0.5f,
    @ColumnInfo(defaultValue = "0.5") val cropCenterY: Float = 0.5f,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

/** The Home page's own record of its cameras: the Nest ones the server last listed, and how each camera is laid out. */
@Dao
interface CameraListDao {
    @Query("SELECT * FROM NestCameraEntity WHERE serverUrl = :serverUrl")
    fun observeNestCameras(serverUrl: String): Flow<List<NestCameraEntity>>

    /** Drops [serverUrl]'s Nest cameras that are not in [names]: the second half of a refresh that never empties the list. */
    @Query("DELETE FROM NestCameraEntity WHERE serverUrl = :serverUrl AND name NOT IN (:names)")
    suspend fun deleteOtherNestCameras(serverUrl: String, names: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNestCameras(cameras: List<NestCameraEntity>)

    @Query("SELECT * FROM CameraLayoutEntity WHERE serverUrl = :serverUrl")
    fun observeLayouts(serverUrl: String): Flow<List<CameraLayoutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLayouts(layouts: List<CameraLayoutEntity>)
}
