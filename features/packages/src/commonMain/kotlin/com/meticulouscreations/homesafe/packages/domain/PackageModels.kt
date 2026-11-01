package com.meticulouscreations.homesafe.packages.domain

import androidx.compose.runtime.Immutable

/** How far a parcel has got, in the order they happen. */
enum class ParcelStage { ORDERED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED }

/** What the door camera made of a delivery (see "packages" in `relay/relay.py`, and docs/packages.md). */
enum class CameraVerdict {
    /** An email says delivered, a visit fits its time, and the model saw a parcel appear. */
    CONFIRMED,

    /** An email says delivered and a visit fits its time: the two pictures are there to judge by. */
    SEEN,

    /** An email says delivered and the camera saw nobody at the door. */
    NOT_SEEN,

    /** The camera thinks a parcel landed, and no email has said so. */
    CAMERA_ONLY,
}

/** Where a delivered parcel is, as far as the door sensor, the camera and the household can say. */
enum class PorchState { ON_PORCH, INSIDE, MAY_BE_GONE }

enum class ParcelProblem { ATTEMPTED, EXCEPTION }

enum class ReturnState { NONE, STARTED, SENT, REFUNDED }

/** Who carries it. The names and marks of real companies are data, so these are not translated. */
enum class Carrier(val label: String) {
    AMAZON("Amazon"),
    UPS("UPS"),
    USPS("USPS"),
    FEDEX("FedEx"),
    DHL("DHL"),
    ONTRAC("OnTrac"),
    LASERSHIP("LaserShip"),
    VEHO("Veho"),
    OTHER(""),
}

/** One line of a parcel's timeline. [kind] is the relay's word for what happened; [detail] is the carrier's or the household's own words, shown as written. */
@Immutable
data class ParcelEvent(val at: Long, val kind: String, val detail: String? = null)

/** One parcel, as much of it as this phone may be told: a surprise kept from it is [masked], and nearly everything else about it is null. */
@Immutable
data class Parcel(
    val id: String,
    val owner: String = "",
    val stage: ParcelStage = ParcelStage.ORDERED,
    val stageAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val carrier: Carrier = Carrier.OTHER,
    /** A carrier the app has no name for, as the relay has it. */
    val carrierName: String? = null,
    val tracking: String? = null,
    val orderId: String? = null,
    val shop: String? = null,
    val title: String? = null,
    val nickname: String? = null,
    val notes: String? = null,
    val leftNote: String? = null,
    val total: Double? = null,
    /** The first and last days it is promised for, as day numbers from 1970-01-01. */
    val expectedFromDay: Int? = null,
    val expectedToDay: Int? = null,
    /** Clock times the promise narrows to, "HH:MM" on the household's clock. */
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val deliveredAt: Long? = null,
    val signature: Boolean = false,
    val problem: ParcelProblem? = null,
    val problemAt: Long? = null,
    val late: Boolean = false,
    val surprise: Boolean = false,
    val masked: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val verdict: CameraVerdict? = null,
    val verdictBy: String? = null,
    val verdictWhat: String? = null,
    val verdictConfidence: Double? = null,
    val visitAt: Long? = null,
    val visitEvent: String? = null,
    val porch: PorchState? = null,
    val porchAt: Long? = null,
    val porchBy: String? = null,
    val returnByDay: Int? = null,
    val returnState: ReturnState = ReturnState.NONE,
    val refundAt: Long? = null,
    val url: String? = null,
    val frames: List<String> = emptyList(),
    val timeline: List<ParcelEvent> = emptyList(),
    /** The camera made this one up from what it saw: no email has claimed it yet. */
    val fromCamera: Boolean = false,
) {
    val delivered: Boolean get() = stage == ParcelStage.DELIVERED
    val needsAttention: Boolean get() = problem != null || porch == PorchState.MAY_BE_GONE || (late && !delivered) || (signature && !delivered)

    fun hasFrames(after: Boolean = true): Boolean = (if (after) "after" else "before") in frames
}

/** Which pushes the household wants, by the relay's names for them, and who is hiding everything new. */
@Immutable
data class PackagesSettings(
    val notify: Map<String, Boolean> = emptyMap(),
    val hideNew: Map<String, Boolean> = emptyMap(),
) {
    fun wants(kind: String): Boolean = notify[kind] ?: true
}

/** All the relay has, as this phone may have it. */
@Immutable
data class PackagesSnapshot(
    val parcels: List<Parcel> = emptyList(),
    /** Who this phone is, by mailbox name; null until it has said. */
    val viewer: String? = null,
    val people: List<String> = emptyList(),
    val nowEpochSeconds: Long = 0,
    /** Today on the household's clock, as a day number. */
    val today: Int = 0,
    val mailOn: Boolean = false,
    val syncedAt: Long? = null,
    val failedMailboxes: List<String> = emptyList(),
    val cameraOn: Boolean = false,
    val cameraName: String = "",
    val cameraId: String = "",
    val doorSensor: Boolean = false,
    val modelOn: Boolean = false,
    val trackerOn: Boolean = false,
    val away: Boolean = false,
    val settings: PackagesSettings = PackagesSettings(),
)

/** Why the relay's list couldn't be read. */
enum class PackagesProblem { SIGNED_OUT, UNREACHABLE, RELAY_OUTDATED, OTHER }

/** The list couldn't be read, for [problem]; [text] is what to tell the person. */
class PackagesUnavailableException(val problem: PackagesProblem, text: com.meticulouscreations.homesafe.text.UiText, technical: String? = null) : com.meticulouscreations.homesafe.text.LocalizedException(text, technical)
