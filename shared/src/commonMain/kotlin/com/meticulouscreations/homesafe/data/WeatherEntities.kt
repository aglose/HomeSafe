package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A city the weather app follows beside wherever the phone is. Added in schema 18, with the
 * rest of this file. [id] is made from the coordinates (see `Place.idFor`), [position] is its
 * place in the list.
 */
@Entity
data class WeatherPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val position: Int,
)

/**
 * A singleton row (always [id] = 0) holding the weather app's preferences; a missing row means
 * the defaults (see `WeatherPreferences`). The units are enum names, stored as text so a value
 * this build doesn't know degrades to the default instead of failing to read.
 */
@Entity
data class WeatherPreferencesEntity(
    @PrimaryKey val id: Int = 0,
    val temperatureUnit: String,
    val measures: String,
    val noticesEnabled: Boolean,
    val noticePrecipitationSoon: Boolean,
    val noticeDailyOutlook: Boolean,
    val noticeSevereAlerts: Boolean,
    val noticeExtremes: Boolean,
    val stillSky: Boolean,
    val selectedPlaceId: String? = null,
    /** Where the phone last was when the app could ask: what the background check forecasts for when it can't get a fix. */
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastName: String? = null,
    val lastRegion: String? = null,
)

/**
 * The last forecast fetched for a place, as JSON, so the app opens on what it had rather than on
 * a spinner. The coordinates say where it was for: the phone's own place moves, and a forecast
 * for where it was this morning is not one for where it is now.
 */
@Entity
data class WeatherReportEntity(
    @PrimaryKey val placeId: String,
    val json: String,
    val fetchedAtEpochSeconds: Long,
    val latitude: Double,
    val longitude: Double,
)

/** A weather notification that has been posted, by the key of what it was about, so it isn't posted again. */
@Entity
data class WeatherNoticeEntity(
    @PrimaryKey val key: String,
    val sentAtEpochSeconds: Long,
)

/**
 * One tile of the radar screen's base map, kept so the map isn't fetched again each time it is
 * opened (which is also what the map's provider asks of an app). [bytes] is the image as it
 * arrived.
 */
@Entity
class MapTileEntity(
    @PrimaryKey val url: String,
    val bytes: ByteArray,
    val storedAtEpochSeconds: Long,
)

@Dao
interface WeatherDao {
    @Query("SELECT * FROM WeatherPlaceEntity ORDER BY position, name")
    fun observePlaces(): Flow<List<WeatherPlaceEntity>>

    @Query("SELECT * FROM WeatherPlaceEntity ORDER BY position, name")
    suspend fun places(): List<WeatherPlaceEntity>

    @Upsert
    suspend fun upsertPlaces(places: List<WeatherPlaceEntity>)

    @Query("DELETE FROM WeatherPlaceEntity WHERE id = :id")
    suspend fun deletePlace(id: String)

    @Query("SELECT * FROM WeatherPreferencesEntity WHERE id = 0")
    fun observePreferences(): Flow<WeatherPreferencesEntity?>

    @Upsert
    suspend fun upsertPreferences(entity: WeatherPreferencesEntity)

    @Query("SELECT * FROM WeatherReportEntity WHERE placeId = :placeId")
    suspend fun report(placeId: String): WeatherReportEntity?

    @Upsert
    suspend fun upsertReport(entity: WeatherReportEntity)

    @Query("DELETE FROM WeatherReportEntity WHERE placeId = :placeId")
    suspend fun deleteReport(placeId: String)

    @Query("SELECT * FROM WeatherNoticeEntity")
    suspend fun notices(): List<WeatherNoticeEntity>

    @Upsert
    suspend fun upsertNotice(entity: WeatherNoticeEntity)

    @Query("DELETE FROM WeatherNoticeEntity WHERE sentAtEpochSeconds < :before")
    suspend fun deleteNoticesBefore(before: Long)

    @Query("SELECT * FROM MapTileEntity WHERE url = :url")
    suspend fun tile(url: String): MapTileEntity?

    @Upsert
    suspend fun upsertTile(entity: MapTileEntity)

    @Query("SELECT COUNT(*) FROM MapTileEntity")
    suspend fun tileCount(): Int

    @Query("DELETE FROM MapTileEntity WHERE url IN (SELECT url FROM MapTileEntity ORDER BY storedAtEpochSeconds LIMIT :count)")
    suspend fun deleteOldestTiles(count: Int)
}

/** [WeatherDao] in memory: for the web target, which has no database, and for tests. */
class InMemoryWeatherDao : WeatherDao {
    private val places = MutableStateFlow<List<WeatherPlaceEntity>>(emptyList())
    private val preferences = MutableStateFlow<WeatherPreferencesEntity?>(null)
    private val reports = HashMap<String, WeatherReportEntity>()
    private val notices = HashMap<String, WeatherNoticeEntity>()
    private val tiles = LinkedHashMap<String, MapTileEntity>()

    override fun observePlaces(): Flow<List<WeatherPlaceEntity>> = places

    override suspend fun places(): List<WeatherPlaceEntity> = places.value

    override suspend fun upsertPlaces(places: List<WeatherPlaceEntity>) {
        this.places.update { current ->
            val replaced = places.associateBy { it.id }
            (current.filter { it.id !in replaced } + places).sortedWith(compareBy({ it.position }, { it.name }))
        }
    }

    override suspend fun deletePlace(id: String) {
        places.update { current -> current.filter { it.id != id } }
    }

    override fun observePreferences(): Flow<WeatherPreferencesEntity?> = preferences

    override suspend fun upsertPreferences(entity: WeatherPreferencesEntity) {
        preferences.value = entity
    }

    override suspend fun report(placeId: String): WeatherReportEntity? = reports[placeId]

    override suspend fun upsertReport(entity: WeatherReportEntity) {
        reports[entity.placeId] = entity
    }

    override suspend fun deleteReport(placeId: String) {
        reports.remove(placeId)
    }

    override suspend fun notices(): List<WeatherNoticeEntity> = notices.values.toList()

    override suspend fun upsertNotice(entity: WeatherNoticeEntity) {
        notices[entity.key] = entity
    }

    override suspend fun deleteNoticesBefore(before: Long) {
        notices.values.removeAll { it.sentAtEpochSeconds < before }
    }

    override suspend fun tile(url: String): MapTileEntity? = tiles[url]

    override suspend fun upsertTile(entity: MapTileEntity) {
        tiles.remove(entity.url)
        tiles[entity.url] = entity
    }

    override suspend fun tileCount(): Int = tiles.size

    override suspend fun deleteOldestTiles(count: Int) {
        tiles.values.sortedBy { it.storedAtEpochSeconds }.take(count).forEach { tiles.remove(it.url) }
    }
}
