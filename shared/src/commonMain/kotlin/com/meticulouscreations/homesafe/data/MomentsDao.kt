package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * The Moments feed's on-device copy of what the server has told it so far.
 *
 * [page] answers exactly the question the feed asks Frigate — this server's newest detections,
 * optionally before an instant and optionally on one camera — so a cached page and a fetched one
 * are the same slice, and the feed can open on the cache and be corrected by the fetch.
 */
@Dao
interface MomentsDao {
    /**
     * [serverUrl]'s newest detections, newest first: those that started before
     * [beforeEpochSeconds] (null for no upper edge), on [cameraName] (null for every camera).
     */
    @Query(
        """
        SELECT * FROM MomentEventEntity
        WHERE serverUrl = :serverUrl
          AND (:beforeEpochSeconds IS NULL OR startEpochSeconds < :beforeEpochSeconds)
          AND (:cameraName IS NULL OR cameraName = :cameraName)
        ORDER BY startEpochSeconds DESC
        LIMIT :limit
        """,
    )
    suspend fun page(serverUrl: String, beforeEpochSeconds: Double?, cameraName: String?, limit: Int): List<MomentEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MomentEventEntity>)

    /**
     * Squares the cache with a page the server just answered: drops this server's rows in the
     * range the page covered — `[fromEpochSeconds, beforeEpochSeconds)`, on the same camera the
     * page was asked for — that the page didn't contain. Those are detections Frigate has since
     * purged, or ones the zones no longer want; either way the feed would be wrong to keep them.
     */
    @Query(
        """
        DELETE FROM MomentEventEntity
        WHERE serverUrl = :serverUrl
          AND (:cameraName IS NULL OR cameraName = :cameraName)
          AND startEpochSeconds >= :fromEpochSeconds
          AND (:beforeEpochSeconds IS NULL OR startEpochSeconds < :beforeEpochSeconds)
          AND id NOT IN (:keptIds)
        """,
    )
    suspend fun deleteMissingInRange(
        serverUrl: String,
        cameraName: String?,
        fromEpochSeconds: Double,
        beforeEpochSeconds: Double?,
        keptIds: List<String>,
    )

    /** Keeps [serverUrl]'s newest [keep] detections and drops the rest, so the cache can't grow without end. */
    @Query(
        """
        DELETE FROM MomentEventEntity
        WHERE serverUrl = :serverUrl AND id NOT IN (
            SELECT id FROM MomentEventEntity WHERE serverUrl = :serverUrl
            ORDER BY startEpochSeconds DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimToNewest(serverUrl: String, keep: Int)
}
