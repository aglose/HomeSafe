package com.meticulouscreations.homesafe.packages.ui

import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.PackagesBoard
import com.meticulouscreations.homesafe.packages.domain.PackagesProblem
import com.meticulouscreations.homesafe.packages.domain.PackagesSettings
import com.meticulouscreations.homesafe.packages.domain.PackagesSnapshot
import com.meticulouscreations.homesafe.packages.domain.PackagesSync
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelEvent
import com.meticulouscreations.homesafe.packages.domain.ParcelProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.ParcelZone
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.packages.domain.ReturnState
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_error_unreachable

/** A made-up household's parcels, for previews and tests: Sam and Alex, and a run of the ordinary and the awkward. */
internal object PackagesFixtures {
    val today = ParcelDays.of(2026, 10, 30)
    const val OFFSET = -7 * 3600
    private val midnight = today * 86_400L - OFFSET
    val now = midnight + 15 * 3600

    private fun at(daysAgo: Int, hour: Int, minute: Int = 0) = midnight - daysAgo * 86_400L + hour * 3600 + minute * 60

    val atDoor = Parcel(
        id = "door", owner = "sam", stage = ParcelStage.DELIVERED, stageAt = at(0, 13, 12), createdAt = at(3, 9), updatedAt = at(0, 13, 12), carrier = Carrier.AMAZON,
        orderId = "113-0000000-0000001", shop = "Amazon", title = "Coffee, Kitchen", total = 46.87, deliveredAt = at(0, 13, 12),
        leftNote = "Your package was left near the front door or porch.",
        verdict = CameraVerdict.CONFIRMED, verdictWhat = "box", verdictConfidence = 0.95, visitAt = at(0, 13, 11), visitEvent = "1.2-abc",
        porch = PorchState.ON_PORCH, porchAt = at(0, 13, 12), returnByDay = today + 30, frames = listOf("after", "before"),
        timeline = listOf(
            ParcelEvent(at(3, 9), "ordered"),
            ParcelEvent(at(1, 18), "shipped"),
            ParcelEvent(at(0, 8, 40), "out_for_delivery"),
            ParcelEvent(at(0, 13, 11), "camera_confirmed", "frames"),
            ParcelEvent(at(0, 13, 12), "delivered", "Your package was left near the front door or porch."),
        ),
    )
    val outToday = Parcel(
        id = "out", owner = "alex", stage = ParcelStage.OUT_FOR_DELIVERY, stageAt = at(0, 8), createdAt = at(4, 12), updatedAt = at(0, 8), carrier = Carrier.USPS, tracking = "9400000000000000000000",
        shop = "Etsy", title = "Hand-thrown mugs", expectedFromDay = today, expectedToDay = today, windowStart = "14:30", windowEnd = "16:30", total = 64.0,
        timeline = listOf(ParcelEvent(at(4, 12), "shipped"), ParcelEvent(at(1, 22), "scan", "Arrived at USPS Regional Facility · Oakland CA"), ParcelEvent(at(0, 8), "out_for_delivery")),
    )
    val tomorrow = Parcel(id = "tmrw", owner = "sam", stage = ParcelStage.SHIPPED, stageAt = at(1, 20), createdAt = at(2, 10), updatedAt = at(1, 20), carrier = Carrier.UPS, tracking = "1Z999AA10123456784", shop = "The Home Depot", nickname = "Porch light", expectedFromDay = today + 1, expectedToDay = today + 1, signature = true, total = 89.5)
    val later = Parcel(id = "later", owner = "alex", stage = ParcelStage.ORDERED, stageAt = at(0, 9), createdAt = at(0, 9), updatedAt = at(0, 9), carrier = Carrier.AMAZON, orderId = "113-0000000-0000004", shop = "Amazon", title = "Toys & Games", expectedFromDay = today + 4, expectedToDay = today + 6, total = 31.2)
    val surpriseMine = Parcel(id = "gift", owner = "sam", stage = ParcelStage.SHIPPED, stageAt = at(1, 10), createdAt = at(1, 9), updatedAt = at(1, 10), carrier = Carrier.FEDEX, tracking = "123456789012", shop = "Jeweller", nickname = "Anniversary", expectedFromDay = today + 2, expectedToDay = today + 2, surprise = true)
    val surpriseKept = Parcel(id = "kept", owner = "alex", stage = ParcelStage.SHIPPED, stageAt = at(1, 12), createdAt = at(1, 9), updatedAt = at(1, 12), carrier = Carrier.UPS, expectedFromDay = today + 1, expectedToDay = today + 1, surprise = true, masked = true)
    val attempted = Parcel(id = "attempt", owner = "sam", stage = ParcelStage.OUT_FOR_DELIVERY, stageAt = at(0, 9), createdAt = at(5, 9), updatedAt = at(0, 11), carrier = Carrier.FEDEX, tracking = "987654321098", shop = "Apple", title = "Phone case", problem = ParcelProblem.ATTEMPTED, problemAt = at(0, 11), signature = true)
    val late = Parcel(id = "late", owner = "alex", stage = ParcelStage.SHIPPED, stageAt = at(6, 9), createdAt = at(8, 9), updatedAt = at(6, 9), carrier = Carrier.ONTRAC, tracking = "D10011223344556", shop = "Everlane", title = "Sweater", expectedFromDay = today - 3, expectedToDay = today - 2, late = true, total = 128.0)
    val notSeen = Parcel(
        id = "notseen", owner = "alex", stage = ParcelStage.DELIVERED, stageAt = at(1, 16), createdAt = at(4, 9), updatedAt = at(1, 16), carrier = Carrier.UPS, tracking = "1Z999AA10123456784", shop = "Target", title = "Bath towels",
        deliveredAt = at(1, 16), verdict = CameraVerdict.NOT_SEEN, returnByDay = today + 88, total = 54.0, timeline = listOf(ParcelEvent(at(1, 16), "delivered"), ParcelEvent(at(1, 16, 5), "camera_not_seen")),
    )
    val mayBeGone = Parcel(
        id = "gone", owner = "sam", stage = ParcelStage.DELIVERED, stageAt = at(0, 10), createdAt = at(3, 9), updatedAt = at(0, 12), carrier = Carrier.AMAZON, orderId = "113-0000000-0000006", shop = "Amazon", title = "Headphones",
        deliveredAt = at(0, 10), verdict = CameraVerdict.SEEN, visitAt = at(0, 9, 58), porch = PorchState.MAY_BE_GONE, porchAt = at(0, 12), returnByDay = today + 30, frames = listOf("after", "before", "missing_after", "missing_before"),
        timeline = listOf(ParcelEvent(at(0, 10), "delivered"), ParcelEvent(at(0, 12), "may_be_gone")),
    )
    val cameraOnly = Parcel(id = "cam", owner = "", stage = ParcelStage.DELIVERED, stageAt = at(0, 11), createdAt = at(0, 11), updatedAt = at(0, 11), deliveredAt = at(0, 11), verdict = CameraVerdict.CAMERA_ONLY, verdictWhat = "box", verdictConfidence = 0.95, visitAt = at(0, 11), porch = PorchState.ON_PORCH, porchAt = at(0, 11), frames = listOf("after", "before"), fromCamera = true)
    val returning = Parcel(id = "ret", owner = "alex", stage = ParcelStage.DELIVERED, stageAt = at(9, 15), createdAt = at(12, 9), updatedAt = at(1, 9), carrier = Carrier.AMAZON, orderId = "113-0000000-0000007", shop = "Amazon", title = "Dresses", deliveredAt = at(9, 15), porch = PorchState.INSIDE, porchBy = "door", verdict = CameraVerdict.SEEN, returnByDay = today + 21, returnState = ReturnState.SENT)
    val closing = Parcel(id = "closing", owner = "sam", stage = ParcelStage.DELIVERED, stageAt = at(27, 14), createdAt = at(30, 9), updatedAt = at(27, 14), carrier = Carrier.AMAZON, orderId = "113-0000000-0000008", shop = "Amazon", title = "Standing desk mat", deliveredAt = at(27, 14), porch = PorchState.INSIDE, porchBy = "sam", returnByDay = today + 3, total = 72.5)

    /** Enough past deliveries for the patterns to have something to find. */
    val history: List<Parcel> = List(46) { i ->
        val days = 2 + i * 8 - i % 3
        val carrier = listOf(Carrier.AMAZON, Carrier.AMAZON, Carrier.AMAZON, Carrier.USPS, Carrier.UPS, Carrier.FEDEX, Carrier.ONTRAC)[i % 7]
        val hour = when (carrier) {
            Carrier.AMAZON -> 12 + i % 6
            Carrier.USPS -> 14 + i % 3
            Carrier.UPS -> 10 + i % 4
            else -> 9 + i % 8
        }
        Parcel(
            id = "h$i", owner = if (i % 3 == 0) "alex" else "sam", stage = ParcelStage.DELIVERED, stageAt = at(days, hour), createdAt = at(days + 3, 9), updatedAt = at(days, hour), carrier = carrier,
            shop = listOf("Amazon", "Amazon", "Amazon", "Etsy", "Target", "Apple", "Everlane")[i % 7], title = listOf("Vitamins", "Coffee", "Hardware", "Ceramics", "Towels", "Cable", "Socks")[i % 7],
            deliveredAt = at(days, hour), expectedFromDay = today - days - (i % 5 / 4), expectedToDay = today - days - (i % 5 / 4),
            verdict = when (i % 5) {
                0, 3 -> CameraVerdict.SEEN
                1, 2 -> CameraVerdict.CONFIRMED
                else -> CameraVerdict.NOT_SEEN
            },
            porch = PorchState.INSIDE, porchBy = "door", total = 12.0 + i * 3.2,
        )
    }

    val parcels: List<Parcel> = listOf(attempted, late, mayBeGone, atDoor, cameraOnly, outToday, tomorrow, surpriseKept, surpriseMine, later, notSeen, returning, closing) + history

    val snapshot = PackagesSnapshot(
        parcels = parcels, viewer = "sam", people = listOf("alex", "sam"), nowEpochSeconds = now, today = today, mailOn = true, syncedAt = now - 90, cameraOn = true, cameraName = "Front Door", cameraId = "front_door",
        doorSensor = true, modelOn = true, trackerOn = false,
        settings = PackagesSettings(mapOf("shipped" to false, "out_for_delivery" to true, "delivered" to true, "porch" to true, "rain" to true, "missing" to true, "problems" to true, "returns" to true, "morning" to true), mapOf("sam" to true)),
    )

    fun state(snapshot: PackagesSnapshot = this.snapshot) = PackagesUiState(
        loaded = true,
        snapshot = snapshot,
        board = PackagesBoard.of(snapshot.parcels, snapshot.today, snapshot.nowEpochSeconds),
        zone = ParcelZone.fixed(OFFSET),
    )

    /** The list as last heard, and the relay no longer answering. */
    val trouble = state().copy(sync = PackagesSync(problem = PackagesProblem.UNREACHABLE, message = UiText.of(Res.string.packages_error_unreachable)))

    /** Nothing coming, and everything delivered long ago. */
    val quiet = state(snapshot.copy(parcels = history))
}

/** The made-up household, for tests of the app's screens from outside this module. */
object PackagesPreviewData {
    val atDoorId: String get() = PackagesFixtures.atDoor.id
    val notSeenId: String get() = PackagesFixtures.notSeen.id
    val keptId: String get() = PackagesFixtures.surpriseKept.id

    fun state(): PackagesUiState = PackagesFixtures.state()
}
