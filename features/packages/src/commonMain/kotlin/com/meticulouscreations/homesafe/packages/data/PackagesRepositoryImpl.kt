package com.meticulouscreations.homesafe.packages.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.PackagesProblem
import com.meticulouscreations.homesafe.packages.domain.PackagesRepository
import com.meticulouscreations.homesafe.packages.domain.PackagesSettings
import com.meticulouscreations.homesafe.packages.domain.PackagesSnapshot
import com.meticulouscreations.homesafe.packages.domain.PackagesSync
import com.meticulouscreations.homesafe.packages.domain.PackagesUnavailableException
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelEvent
import com.meticulouscreations.homesafe.packages.domain.ParcelProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.packages.domain.ReturnState
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_error_signed_out
import homesafe.shared.generated.resources.packages_error_unreachable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [PackagesRepository] over the relay. What it holds lives as long as the app does: a parcel is
 * news only while it is right, so nothing of it stays on the device for someone else to open, and
 * signing out empties it. Each answer is the whole list, which is the size of a few hundred
 * parcels at most, so one change can't leave the screen out of step with the box.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PackagesRepositoryImpl(
    private val api: PackagesRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val identity: DeviceIdentityStore,
) : PackagesRepository {

    private val _snapshot = MutableStateFlow<PackagesSnapshot?>(null)
    override val snapshot: StateFlow<PackagesSnapshot?> = _snapshot.asStateFlow()

    private val _sync = MutableStateFlow(PackagesSync())
    override val sync: StateFlow<PackagesSync> = _sync.asStateFlow()

    private val refreshing = Mutex()

    /** One edit at a time, so each answer is newer than the last. */
    private val editing = Mutex()

    /** Goes up when an edit starts and when it lands: a list asked for before then may not have it. */
    private val edits = MutableStateFlow(0)

    /** The server the list in [_snapshot] came from. The same across its addresses: only signing in elsewhere changes it. */
    private var heldFrom: String? = null

    private fun endpoint(): String? = connectionRepository.currentServerUrl.value.takeIf { connectionRepository.activeConnection.value != null }

    private fun server(): String? = connectionRepository.activeConnection.value?.serverUrl

    override fun forgetIfSignedOut() {
        if (endpoint() == null) {
            _snapshot.value = null
            heldFrom = null
            _sync.value = PackagesSync(problem = PackagesProblem.SIGNED_OUT, message = UiText.of(Res.string.packages_error_signed_out))
        } else if (heldFrom != null && heldFrom != server()) {
            // Signed in to another server: what the last one said isn't this one's to show.
            _snapshot.value = null
            heldFrom = null
            _sync.value = PackagesSync()
        }
    }

    /** An answer, unless the session it was asked for has ended while it was on its way. */
    private fun hold(body: RelayPackages, askedOf: String?) {
        if (askedOf == null || askedOf != server()) return
        _snapshot.value = body.toSnapshot()
        heldFrom = askedOf
    }

    override suspend fun refresh(readMail: Boolean) {
        forgetIfSignedOut()
        val askedOf = server() ?: return
        // A second ask while one is under way is the same ask, unless this one also wants the mail read.
        if (readMail) {
            refreshing.lock()
        } else if (!refreshing.tryLock()) {
            return
        }
        try {
            // Waiting its turn can outlast the session it was asked in.
            val endpoint = endpoint()?.takeIf { askedOf == server() } ?: return
            _sync.update { it.copy(syncing = true) }
            val device = identity.deviceId()
            val asked = edits.value
            val answer = if (readMail) api.sync(endpoint, device) else api.snapshot(endpoint, device)
            answer.onSuccess { body ->
                if (edits.value == asked) hold(body, askedOf)
                _sync.value = PackagesSync(checkedAt = body.now.toLong())
            }.onFailure { failure ->
                // The server that session was on failing is no news about this one.
                if (askedOf != server()) {
                    _sync.update { it.copy(syncing = false) }
                    return@onFailure
                }
                val known = failure as? PackagesUnavailableException
                _sync.update {
                    it.copy(syncing = false, problem = known?.problem ?: PackagesProblem.UNREACHABLE, message = known?.text ?: UiText.of(Res.string.packages_error_unreachable))
                }
            }
        } finally {
            refreshing.unlock()
        }
    }

    private suspend fun apply(call: suspend (String, String) -> Result<RelayPackages>): Result<Unit> {
        val askedOf = server()
        return editing.withLock {
            // Asked for before a sign-out or a switch of server, and only now its turn: it was never meant for whoever is signed in now.
            val endpoint = endpoint()?.takeIf { askedOf != null && askedOf == server() }
                ?: return@withLock Result.failure(PackagesUnavailableException(PackagesProblem.SIGNED_OUT, UiText.of(Res.string.packages_error_signed_out)))
            edits.update { it + 1 }
            call(endpoint, identity.deviceId()).map { body ->
                hold(body, askedOf)
                edits.update { it + 1 }
            }
        }
    }

    override suspend fun add(tracking: String, nickname: String?, owner: String?, surprise: Boolean): Result<Unit> =
        apply { endpoint, device -> api.add(endpoint, device, RelayPackageAdd(tracking, nickname, owner, surprise)) }

    override suspend fun change(parcelId: String, change: ParcelChange): Result<Unit> =
        apply { endpoint, device -> api.change(endpoint, device, parcelId, change.toPatch()) }

    override suspend fun forget(parcelId: String): Result<Unit> = apply { endpoint, device -> api.forget(endpoint, device, parcelId) }

    override suspend fun setPhoneOwner(person: String?): Result<Unit> = apply { endpoint, device -> api.setPhone(endpoint, device, person) }

    override suspend fun setNotify(kind: String, on: Boolean): Result<Unit> =
        apply { endpoint, device -> api.settings(endpoint, device, RelayPackageSettingsChange(notify = mapOf(kind to on))) }

    override suspend fun setHideNew(person: String, on: Boolean): Result<Unit> =
        apply { endpoint, device -> api.settings(endpoint, device, RelayPackageSettingsChange(hideNew = mapOf(person to on))) }

    override suspend fun pictureUrl(parcelId: String?, name: String): String? {
        val endpoint = endpoint() ?: return null
        return PackagesRelayApi.pictureUrl(endpoint, identity.deviceId(), parcelId, name)
    }
}

internal fun ParcelChange.toPatch() = RelayPackagePatch(
    nickname = nickname,
    notes = notes,
    owner = owner,
    surprise = surprise,
    pinned = pinned,
    archived = archived,
    returnBy = returnBy,
    returnState = returnState?.name?.lowercase(),
    porch = when (porch) {
        PorchState.ON_PORCH -> "porch"
        PorchState.INSIDE -> "inside"
        else -> null
    },
    camera = cameraRight?.let { if (it) "yes" else "no" },
    stage = if (markDelivered) "delivered" else null,
)

internal fun RelayPackages.toSnapshot() = PackagesSnapshot(
    parcels = packages.map { it.toParcel() },
    viewer = viewer,
    people = people,
    nowEpochSeconds = now.toLong(),
    today = ParcelDays.parse(today) ?: (now.toLong() / 86_400L).toInt(),
    mailOn = mailOn,
    syncedAt = synced?.at?.toLong(),
    failedMailboxes = synced?.failed.orEmpty(),
    cameraOn = cameraOn,
    cameraName = camera,
    cameraId = cameraId,
    doorSensor = doorSensor,
    modelOn = modelOn,
    trackerOn = trackerOn,
    away = away,
    settings = PackagesSettings(settings.notify, settings.hideNew),
)

internal fun RelayPackage.toParcel() = Parcel(
    id = id,
    owner = owner.orEmpty(),
    stage = when (stage) {
        "shipped" -> ParcelStage.SHIPPED
        "out_for_delivery" -> ParcelStage.OUT_FOR_DELIVERY
        "delivered" -> ParcelStage.DELIVERED
        else -> ParcelStage.ORDERED
    },
    stageAt = stageAt?.toLong() ?: 0,
    createdAt = created?.toLong() ?: 0,
    updatedAt = updated?.toLong() ?: 0,
    carrier = Carrier.entries.firstOrNull { it != Carrier.OTHER && it.name.equals(carrier, ignoreCase = true) } ?: Carrier.OTHER,
    carrierName = carrier?.takeIf { name -> Carrier.entries.none { it != Carrier.OTHER && it.name.equals(name, ignoreCase = true) } && name != "other" },
    tracking = tracking,
    orderId = orderId,
    shop = shop,
    title = title,
    nickname = nickname,
    notes = notes,
    leftNote = leftNote,
    total = total,
    expectedFromDay = ParcelDays.parse(expectedFrom),
    expectedToDay = ParcelDays.parse(expectedTo),
    windowStart = windowStart,
    windowEnd = windowEnd,
    deliveredAt = deliveredAt?.toLong(),
    signature = signature,
    problem = when (problem) {
        "attempted" -> ParcelProblem.ATTEMPTED
        "exception" -> ParcelProblem.EXCEPTION
        else -> null
    },
    problemAt = problemAt?.toLong(),
    late = late,
    surprise = surprise,
    masked = masked,
    pinned = pinned,
    archived = archived,
    verdict = when (verdict) {
        "confirmed" -> CameraVerdict.CONFIRMED
        "seen" -> CameraVerdict.SEEN
        "not_seen" -> CameraVerdict.NOT_SEEN
        "camera_only" -> CameraVerdict.CAMERA_ONLY
        else -> null
    },
    verdictBy = verdictBy,
    verdictWhat = verdictWhat?.takeIf { it != "none" },
    verdictConfidence = verdictConfidence,
    visitAt = visitAt?.toLong(),
    visitEvent = visitEvent,
    porch = when (porch) {
        "porch" -> PorchState.ON_PORCH
        "inside" -> PorchState.INSIDE
        "missing" -> PorchState.MAY_BE_GONE
        else -> null
    },
    porchAt = porchAt?.toLong(),
    porchBy = porchBy,
    returnByDay = ParcelDays.parse(returnBy),
    returnState = when (returnState) {
        "started" -> ReturnState.STARTED
        "sent" -> ReturnState.SENT
        "refunded" -> ReturnState.REFUNDED
        else -> ReturnState.NONE
    },
    refundAt = refundAt?.toLong(),
    url = url,
    frames = frames,
    timeline = timeline.map { ParcelEvent(it.at.toLong(), it.kind, it.detail) },
    fromCamera = source == "camera",
)
