package com.meticulouscreations.homesafe.weather.data

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import com.meticulouscreations.homesafe.data.MapTileEntity
import com.meticulouscreations.homesafe.data.WeatherDao
import com.meticulouscreations.homesafe.finance.data.suspendRunCatching
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.time.Clock

/**
 * Somewhere a map can get its tiles from: [MapTileStore] in the app, a handful of pictures in a
 * preview or a test. Stable for Compose: what changes is announced through [arrivals].
 */
@Stable
interface MapTileSource {
    /** Goes up each time a tile arrives: something to observe so the map redraws with it. */
    val arrivals: StateFlow<Int>

    /** The tile at [url] if it is in memory, and null (not a fetch) if it isn't. */
    fun image(url: String): ImageBitmap?

    /**
     * Fetches whichever of [urls] isn't in memory or already on its way, in [scope]. With [keep]
     * the bytes are also looked for in, and saved to, the device's store. A radar tile is passed
     * through [radar] as it arrives: its provider's colours turned into strengths (see
     * [RadarDecoder]), which is the picture kept.
     */
    fun request(urls: Collection<String>, scope: CoroutineScope, keep: Boolean, radar: RadarColorTable? = null)

    /** Says how many tiles the map on screen draws from (the base map and every frame of the loop), so that many are held at once. */
    fun reserve(count: Int) = Unit

    /** The map has gone: let go of all but a screenful. */
    fun trim() = Unit
}

/**
 * Map and radar tiles, fetched once and kept: decoded pictures in memory for drawing, and the
 * base map's bytes in the database so the map isn't downloaded again every time it is opened
 * (OpenStreetMap asks that of anything using its tiles). Radar tiles are not kept on disk: each
 * is one moment's picture, and stale within the hour.
 *
 * Used from the main thread only: [image] is read while drawing, and [request] is called from
 * the screen's own scope. Only the download and the decode leave it.
 */
@Inject
@SingleIn(AppScope::class)
class MapTileStore(
    @Named(WEATHER_CLIENT) private val httpClient: HttpClient,
    private val dao: WeatherDao,
    private val clock: Clock,
) : MapTileSource {
    private val images = LinkedHashMap<String, ImageBitmap>()
    private val loading = HashMap<String, Job>()
    private val failedAt = HashMap<String, Long>()
    private val downloads = Semaphore(MAX_DOWNLOADS)
    private var capacity = MIN_IMAGES
    private val _arrivals = MutableStateFlow(0)

    override val arrivals: StateFlow<Int> = _arrivals.asStateFlow()

    override fun image(url: String): ImageBitmap? {
        val image = images.remove(url) ?: return null
        // Put back at the end: the least recently drawn are the first to go.
        images[url] = image
        return image
    }

    override fun reserve(count: Int) {
        // A loop on a large window draws from more tiles than a phone's does; held to fewer, it
        // would evict the frames it is about to play and fetch them again, for ever.
        capacity = (count + count / 8).coerceIn(MIN_IMAGES, MAX_IMAGES)
    }

    override fun trim() {
        capacity = MIN_IMAGES
        while (images.size > KEPT_AFTER_TRIM) images.remove(images.keys.first())
        failedAt.clear()
    }

    override fun request(urls: Collection<String>, scope: CoroutineScope, keep: Boolean, radar: RadarColorTable?) {
        val now = clock.now().epochSeconds
        if (failedAt.size > MAX_FAILURES_REMEMBERED) failedAt.values.removeAll { now - it >= RETRY_SECONDS }
        for (url in urls) {
            if (url in images || loading[url]?.isActive == true) continue
            if (now - (failedAt[url] ?: 0L) < RETRY_SECONDS) continue
            lateinit var job: Job
            job = scope.launch {
                try {
                    val image = load(url, keep)?.let { loaded ->
                        if (radar == null) loaded else withContext(Dispatchers.Default) { runCatching { RadarDecoder.decode(loaded, radar) }.getOrNull() }
                    }
                    if (image != null) {
                        images[url] = image
                        while (images.size > capacity) images.remove(images.keys.first())
                        _arrivals.value++
                    } else {
                        failedAt[url] = clock.now().epochSeconds
                    }
                } finally {
                    // Only its own entry: a cancelled job must not take a newer one's place with it.
                    if (loading[url] === job) loading.remove(url)
                }
            }
            // A job that finished before it could be filed has already cleaned up after itself.
            if (job.isActive) loading[url] = job
        }
    }

    private suspend fun load(url: String, keep: Boolean): ImageBitmap? {
        if (keep) {
            val saved = suspendRunCatching { dao.tile(url) }.getOrNull()
            if (saved != null) decode(saved.bytes)?.let { return it }
        }
        val bytes = try {
            downloads.withPermit {
                val response = httpClient.get(url)
                if (response.status.isSuccess()) response.bodyAsBytes() else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val image = decode(bytes) ?: return null
        if (keep) {
            suspendRunCatching {
                dao.upsertTile(MapTileEntity(url, bytes, clock.now().epochSeconds))
                val over = dao.tileCount() - MAX_SAVED
                if (over > 0) dao.deleteOldestTiles(over + MAX_SAVED / 10)
            }
        }
        return image
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching { bytes.decodeToImageBitmap() }.getOrNull()
    }

    private companion object {
        /** A phone's screenful of map and a loop's worth of radar, at about a quarter megabyte each decoded. */
        const val MIN_IMAGES = 200

        /** As far as [reserve] can raise it, for a loop on a desktop's window. */
        const val MAX_IMAGES = 520

        /** What stays in memory once the map has gone: enough to draw it again at once. */
        const val KEPT_AFTER_TRIM = 48
        const val MAX_FAILURES_REMEMBERED = 400
        const val MAX_SAVED = 500
        const val MAX_DOWNLOADS = 6

        /** A tile that failed (a frame the server hasn't published yet, usually) isn't asked for again straight away. */
        const val RETRY_SECONDS = 45L
    }
}
