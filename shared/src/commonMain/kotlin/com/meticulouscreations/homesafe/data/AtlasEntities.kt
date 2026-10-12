package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

/**
 * A stay somewhere, from the location history the relay keeps (`relay/relay.py`, "timeline").
 * Added in schema 22, with the rest of this file: the atlas app's own copy, fetched a [year] at a
 * time so that it opens with the server out of reach. [offsetMinutes] is how far ahead of UTC the
 * clock on the wall was, so a day is the day it was there. [placeId] is Google's, null for a stay
 * Google never matched to a place; [kind] is `home`, `work` or `other`.
 */
@Entity(indices = [Index("year"), Index("startEpochSeconds")])
data class AtlasVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val year: Int,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val offsetMinutes: Int,
    val latitude: Double,
    val longitude: Double,
    val placeId: String?,
    val kind: String,
)

/** Getting from one place to another, and how ([mode]: `walk`, `drive`, `fly`…). */
@Entity(indices = [Index("year"), Index("startEpochSeconds")])
data class AtlasMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val year: Int,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val offsetMinutes: Int,
    val mode: String,
    val latitude: Double,
    val longitude: Double,
    val toLatitude: Double,
    val toLongitude: Double,
    val meters: Double,
)

/**
 * The points along a stretch of the way, as the relay packs them: [points] is an encoded polyline
 * and [times] each point's seconds after [startEpochSeconds], packed the same way (see `Polyline`).
 */
@Entity(indices = [Index("year"), Index("startEpochSeconds")])
data class AtlasTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val year: Int,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val offsetMinutes: Int,
    val points: String,
    val times: String,
    val count: Int,
)

/** A place that was visited, with the name Google gave for its id once the relay had asked. */
@Entity
data class AtlasPlaceEntity(
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val name: String?,
    val address: String?,
    val type: String?,
    val city: String?,
    val region: String?,
    val country: String?,
)

/** A year this device holds, and the relay's `etag` for it when it was fetched: a different one means fetch it again. */
@Entity
data class AtlasYearEntity(
    @PrimaryKey val year: Int,
    val etag: String,
    val visits: Int,
    val moves: Int,
    val points: Int,
    val syncedAtEpochSeconds: Long,
)

/**
 * A singleton row (always [id] = 0): whose history this is ([owner], the server it came from), how
 * far the place names had got ([placesVersion]) and when the relay was last heard from.
 */
@Entity
data class AtlasStateEntity(
    @PrimaryKey val id: Int = 0,
    val owner: String,
    val placesVersion: Int,
    val checkedAtEpochSeconds: Long,
)

@Dao
interface AtlasDao {
    @Query("SELECT * FROM AtlasVisitEntity ORDER BY startEpochSeconds, endEpochSeconds")
    suspend fun visits(): List<AtlasVisitEntity>

    @Query("SELECT * FROM AtlasMoveEntity ORDER BY startEpochSeconds, endEpochSeconds")
    suspend fun moves(): List<AtlasMoveEntity>

    @Query("SELECT * FROM AtlasTrackEntity ORDER BY startEpochSeconds")
    suspend fun tracks(): List<AtlasTrackEntity>

    /** The stretches of path that touch a span of time. */
    @Query("SELECT * FROM AtlasTrackEntity WHERE endEpochSeconds >= :from AND startEpochSeconds <= :until ORDER BY startEpochSeconds")
    suspend fun tracksBetween(from: Long, until: Long): List<AtlasTrackEntity>

    @Query("SELECT * FROM AtlasPlaceEntity")
    suspend fun places(): List<AtlasPlaceEntity>

    @Query("SELECT * FROM AtlasYearEntity ORDER BY year")
    suspend fun years(): List<AtlasYearEntity>

    @Query("SELECT * FROM AtlasStateEntity WHERE id = 0")
    suspend fun state(): AtlasStateEntity?

    @Upsert
    suspend fun upsert(state: AtlasStateEntity)

    @Insert
    suspend fun insertVisits(rows: List<AtlasVisitEntity>)

    @Insert
    suspend fun insertMoves(rows: List<AtlasMoveEntity>)

    @Insert
    suspend fun insertTracks(rows: List<AtlasTrackEntity>)

    @Upsert
    suspend fun upsertPlaces(rows: List<AtlasPlaceEntity>)

    @Upsert
    suspend fun upsert(year: AtlasYearEntity)

    @Query("DELETE FROM AtlasVisitEntity WHERE year = :year")
    suspend fun deleteVisits(year: Int)

    @Query("DELETE FROM AtlasMoveEntity WHERE year = :year")
    suspend fun deleteMoves(year: Int)

    @Query("DELETE FROM AtlasTrackEntity WHERE year = :year")
    suspend fun deleteTracks(year: Int)

    @Query("DELETE FROM AtlasYearEntity WHERE year = :year")
    suspend fun deleteYearRow(year: Int)

    @Query("DELETE FROM AtlasPlaceEntity")
    suspend fun deletePlaces()

    @Query("DELETE FROM AtlasStateEntity")
    suspend fun deleteState()

    /** One year as the relay now has it, in place of whatever this device held for it. */
    @Transaction
    suspend fun replaceYear(year: AtlasYearEntity, visits: List<AtlasVisitEntity>, moves: List<AtlasMoveEntity>, tracks: List<AtlasTrackEntity>) {
        deleteVisits(year.year)
        deleteMoves(year.year)
        deleteTracks(year.year)
        // In pieces: one statement binds only so many values.
        visits.chunked(INSERT_ROWS).forEach { insertVisits(it) }
        moves.chunked(INSERT_ROWS).forEach { insertMoves(it) }
        tracks.chunked(INSERT_ROWS).forEach { insertTracks(it) }
        upsert(year)
    }

    /** A year the relay no longer has. */
    @Transaction
    suspend fun deleteYear(year: Int) {
        deleteVisits(year)
        deleteMoves(year)
        deleteTracks(year)
        deleteYearRow(year)
    }

    @Transaction
    suspend fun replacePlaces(rows: List<AtlasPlaceEntity>) {
        deletePlaces()
        rows.chunked(INSERT_ROWS).forEach { upsertPlaces(it) }
    }

    /** Everything, when the history turns out to be someone else's or no longer this account's to see. */
    @Transaction
    suspend fun clear() {
        years().forEach { deleteYear(it.year) }
        deletePlaces()
        deleteState()
    }

    companion object {
        const val INSERT_ROWS = 400
    }
}

/** [AtlasDao] in memory: for the web target, which has no database, and for tests. */
class InMemoryAtlasDao : AtlasDao {
    private val visits = ArrayList<AtlasVisitEntity>()
    private val moves = ArrayList<AtlasMoveEntity>()
    private val tracks = ArrayList<AtlasTrackEntity>()
    private val places = LinkedHashMap<String, AtlasPlaceEntity>()
    private val years = HashMap<Int, AtlasYearEntity>()
    private var state: AtlasStateEntity? = null

    override suspend fun visits(): List<AtlasVisitEntity> = visits.sortedWith(compareBy({ it.startEpochSeconds }, { it.endEpochSeconds }))

    override suspend fun moves(): List<AtlasMoveEntity> = moves.sortedWith(compareBy({ it.startEpochSeconds }, { it.endEpochSeconds }))

    override suspend fun tracks(): List<AtlasTrackEntity> = tracks.sortedBy { it.startEpochSeconds }

    override suspend fun tracksBetween(from: Long, until: Long): List<AtlasTrackEntity> =
        tracks().filter { it.endEpochSeconds >= from && it.startEpochSeconds <= until }

    override suspend fun places(): List<AtlasPlaceEntity> = places.values.toList()

    override suspend fun years(): List<AtlasYearEntity> = years.values.sortedBy { it.year }

    override suspend fun state(): AtlasStateEntity? = state

    override suspend fun upsert(state: AtlasStateEntity) {
        this.state = state
    }

    override suspend fun insertVisits(rows: List<AtlasVisitEntity>) {
        visits += rows
    }

    override suspend fun insertMoves(rows: List<AtlasMoveEntity>) {
        moves += rows
    }

    override suspend fun insertTracks(rows: List<AtlasTrackEntity>) {
        tracks += rows
    }

    override suspend fun upsertPlaces(rows: List<AtlasPlaceEntity>) {
        rows.forEach { places[it.id] = it }
    }

    override suspend fun upsert(year: AtlasYearEntity) {
        years[year.year] = year
    }

    override suspend fun deleteVisits(year: Int) {
        visits.removeAll { it.year == year }
    }

    override suspend fun deleteMoves(year: Int) {
        moves.removeAll { it.year == year }
    }

    override suspend fun deleteTracks(year: Int) {
        tracks.removeAll { it.year == year }
    }

    override suspend fun deleteYearRow(year: Int) {
        years.remove(year)
    }

    override suspend fun deletePlaces() {
        places.clear()
    }

    override suspend fun deleteState() {
        state = null
    }
}
