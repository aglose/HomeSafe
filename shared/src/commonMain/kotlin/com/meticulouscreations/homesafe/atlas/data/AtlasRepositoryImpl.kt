package com.meticulouscreations.homesafe.atlas.data

import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.AtlasProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasRepository
import com.meticulouscreations.homesafe.atlas.domain.AtlasServerStatus
import com.meticulouscreations.homesafe.atlas.domain.AtlasSetupProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasSyncState
import com.meticulouscreations.homesafe.atlas.domain.AtlasUnavailableException
import com.meticulouscreations.homesafe.atlas.domain.Move
import com.meticulouscreations.homesafe.atlas.domain.Place
import com.meticulouscreations.homesafe.atlas.domain.PlaceKind
import com.meticulouscreations.homesafe.atlas.domain.Polyline
import com.meticulouscreations.homesafe.atlas.domain.Track
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.Visit
import com.meticulouscreations.homesafe.data.AtlasDao
import com.meticulouscreations.homesafe.data.AtlasMoveEntity
import com.meticulouscreations.homesafe.data.AtlasPlaceEntity
import com.meticulouscreations.homesafe.data.AtlasStateEntity
import com.meticulouscreations.homesafe.data.AtlasTrackEntity
import com.meticulouscreations.homesafe.data.AtlasVisitEntity
import com.meticulouscreations.homesafe.data.AtlasYearEntity
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_error_signed_out
import homesafe.shared.generated.resources.atlas_error_unreachable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * [AtlasRepository] over the relay and the device's database.
 *
 * A refresh asks the relay which years it has and fetches only those whose `etag` this device
 * doesn't hold, one at a time, each written in one transaction: a fetch cut short leaves whole
 * years, old or new, never half of one. The copy belongs to the server it came from; signing in
 * to another clears it, and so does an account the relay refuses.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AtlasRepositoryImpl(
    private val api: AtlasRelayApi,
    private val dao: AtlasDao,
    private val connectionRepository: ConnectionRepository,
    private val clock: Clock,
) : AtlasRepository {

    private val _history = MutableStateFlow<AtlasHistory?>(null)
    override val history: StateFlow<AtlasHistory?> = _history.asStateFlow()

    private val _sync = MutableStateFlow(AtlasSyncState())
    override val sync: StateFlow<AtlasSyncState> = _sync.asStateFlow()

    private val reading = Mutex()
    private val refreshing = Mutex()
    private var tracks: List<Track>? = null

    override suspend fun load() {
        if (_history.value != null) return
        reading.withLock {
            if (_history.value == null) read()
        }
    }

    private suspend fun read() {
        val checkedAt = dao.state()?.checkedAtEpochSeconds
        _history.value = withContext(Dispatchers.Default) {
            val places = dao.places().associate { it.id to it.toPlace() }
            AtlasHistory(dao.visits().map { it.toVisit() }, dao.moves().map { it.toMove() }, places)
        }
        _sync.update { it.copy(checkedAtEpochSeconds = checkedAt ?: it.checkedAtEpochSeconds) }
    }

    override suspend fun tracks(): List<Track> {
        tracks?.let { return it }
        return reading.withLock {
            tracks ?: withContext(Dispatchers.Default) {
                dao.tracks().map { Track.decode(it.startEpochSeconds, it.offsetMinutes, it.points, it.times) }
            }.also { tracks = it }
        }
    }

    override suspend fun refresh(askDrive: Boolean) {
        // A second ask while one is under way is the same ask.
        if (!refreshing.tryLock()) return
        try {
            load()
            val serverUrl = connectionRepository.currentServerUrl.value
            if (serverUrl == null) {
                _sync.update { it.copy(syncing = false, problem = AtlasProblem.SIGNED_OUT, message = UiText.of(Res.string.atlas_error_signed_out)) }
                return
            }
            _sync.update { it.copy(syncing = true, yearsDone = 0, yearsTotal = 0) }
            fetch(serverUrl, askDrive).onFailure { failure ->
                val problem = (failure as? AtlasUnavailableException)?.problem ?: AtlasProblem.UNREACHABLE
                // Not this account's to see: what the device holds goes too.
                if (problem == AtlasProblem.NOT_ALLOWED) {
                    dao.clear()
                    tracks = null
                    read()
                }
                val message = if (failure is AtlasUnavailableException) failure.userMessage() else UiText.of(Res.string.atlas_error_unreachable)
                _sync.update { it.copy(syncing = false, problem = problem, message = message) }
            }
        } finally {
            refreshing.unlock()
        }
    }

    private suspend fun fetch(serverUrl: String, askDrive: Boolean): Result<Unit> {
        val timeline = api.timeline(serverUrl, askDrive).getOrElse { return Result.failure(it) }
        val state = dao.state()?.takeIf { it.owner == serverUrl }
        // Another server's history: not this one's to add to.
        val foreign = state == null && dao.years().isNotEmpty()
        if (foreign) {
            dao.clear()
            tracks = null
        }
        // Said before a year is written: a fetch cut short leaves years that are this server's, and known to be.
        if (state == null) dao.upsert(AtlasStateEntity(owner = serverUrl, placesVersion = NO_PLACES, checkedAtEpochSeconds = 0))
        val held = dao.years().associateBy { it.year }
        val wanted = timeline.years.filter { held[it.year]?.etag != it.etag }
        val gone = held.keys - timeline.years.mapTo(HashSet()) { it.year }
        _sync.update { it.copy(yearsTotal = wanted.size, server = timeline.toStatus()) }
        var changed = foreign || gone.isNotEmpty()
        gone.forEach { dao.deleteYear(it) }
        wanted.forEachIndexed { index, summary ->
            val body = api.year(serverUrl, summary.year).getOrElse { failure ->
                // What did arrive is kept, and shown.
                if (changed) reread()
                return Result.failure(failure)
            }
            withContext(Dispatchers.Default) { store(summary, body) }
            changed = true
            _sync.update { it.copy(yearsDone = index + 1) }
        }
        val now = clock.now().epochSeconds
        var placesVersion = state?.placesVersion ?: NO_PLACES
        if (changed || placesVersion != timeline.places.version) {
            // A failure leaves the version as it was, so the names are asked for again next time.
            api.places(serverUrl).onSuccess { places ->
                dao.replacePlaces(places.places.map { it.toEntity() })
                placesVersion = places.version
                changed = true
            }
        }
        dao.upsert(AtlasStateEntity(owner = serverUrl, placesVersion = placesVersion, checkedAtEpochSeconds = now))
        if (changed) reread()
        _sync.update { it.copy(syncing = false, problem = null, message = null, checkedAtEpochSeconds = now) }
        return Result.success(Unit)
    }

    private companion object {
        /** No version the relay will ever give: the places have not been fetched. */
        const val NO_PLACES = -1
    }

    private suspend fun reread() {
        tracks = null
        reading.withLock { read() }
    }

    private suspend fun store(summary: RelayYearSummary, body: RelayYear) {
        val year = summary.year
        val visits = body.visits.started.indices.map { i ->
            AtlasVisitEntity(
                year = year,
                startEpochSeconds = body.visits.started[i],
                endEpochSeconds = body.visits.ended.getOrElse(i) { body.visits.started[i] },
                offsetMinutes = body.visits.offset.getOrElse(i) { 0 },
                latitude = body.visits.lat.getOrElse(i) { 0.0 },
                longitude = body.visits.lon.getOrElse(i) { 0.0 },
                placeId = body.visits.place.getOrNull(i)?.let(body.places::getOrNull),
                kind = body.visits.kind.getOrElse(i) { PlaceKind.OTHER.key },
            )
        }
        val moves = body.moves.started.indices.map { i ->
            AtlasMoveEntity(
                year = year,
                startEpochSeconds = body.moves.started[i],
                endEpochSeconds = body.moves.ended.getOrElse(i) { body.moves.started[i] },
                offsetMinutes = body.moves.offset.getOrElse(i) { 0 },
                mode = body.moves.mode.getOrElse(i) { TravelMode.OTHER.key },
                latitude = body.moves.lat.getOrElse(i) { 0.0 },
                longitude = body.moves.lon.getOrElse(i) { 0.0 },
                toLatitude = body.moves.toLat.getOrElse(i) { 0.0 },
                toLongitude = body.moves.toLon.getOrElse(i) { 0.0 },
                meters = body.moves.metres.getOrElse(i) { 0.0 },
            )
        }
        val tracks = body.paths.started.indices.mapNotNull { i ->
            val points = body.paths.points.getOrNull(i) ?: return@mapNotNull null
            val times = body.paths.times.getOrElse(i) { "" }
            AtlasTrackEntity(
                year = year,
                startEpochSeconds = body.paths.started[i],
                endEpochSeconds = body.paths.started[i] + (Polyline.decodeNumbers(times).lastOrNull() ?: 0),
                offsetMinutes = body.paths.offset.getOrElse(i) { 0 },
                points = points,
                times = times,
                count = body.paths.count.getOrElse(i) { 0 },
            )
        }
        dao.replaceYear(AtlasYearEntity(year, summary.etag, visits.size, moves.size, tracks.sumOf { it.count }, clock.now().epochSeconds), visits, moves, tracks)
    }

    private fun RelayTimeline.toStatus() = AtlasServerStatus(
        driveConfigured = drive.configured,
        driveProblem = AtlasSetupProblem.of(drive.error),
        driveMessage = drive.message,
        serviceAccount = drive.serviceAccount,
        activationUrl = drive.activationUrl,
        checkedAtEpochSeconds = drive.checkedAt?.toLong(),
        nextCheckAtEpochSeconds = drive.nextCheckAt?.toLong(),
        fileName = file?.name,
        fileReadAtEpochSeconds = file?.ingestedAt?.toLong(),
        namingConfigured = naming.configured,
        namingProblem = AtlasSetupProblem.of(naming.error),
        namingMessage = naming.message,
        placesTotal = places.total,
        placesNamed = places.named,
        askedThisMonth = naming.askedThisMonth,
        monthlyCap = naming.monthlyCap,
        reading = syncing,
    )
}

internal fun AtlasVisitEntity.toVisit() = Visit(
    start = startEpochSeconds,
    end = endEpochSeconds,
    offsetMinutes = offsetMinutes,
    latitude = latitude,
    longitude = longitude,
    placeKey = placeId ?: Place.keyAt(latitude, longitude),
    kind = PlaceKind.of(kind),
)

internal fun AtlasMoveEntity.toMove() = Move(startEpochSeconds, endEpochSeconds, offsetMinutes, TravelMode.of(mode), latitude, longitude, toLatitude, toLongitude, meters)

internal fun AtlasPlaceEntity.toPlace() = Place(id, latitude, longitude, PlaceKind.of(kind), name, address, type, city, region, country)

internal fun RelayPlace.toEntity() = AtlasPlaceEntity(id, lat, lon, kind, name, address, type, city, region, country)
