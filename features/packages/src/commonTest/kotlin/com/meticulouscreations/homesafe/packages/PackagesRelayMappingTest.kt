package com.meticulouscreations.homesafe.packages

import com.meticulouscreations.homesafe.packages.data.PackagesRelayApi
import com.meticulouscreations.homesafe.packages.data.RelayPackage
import com.meticulouscreations.homesafe.packages.data.RelayPackages
import com.meticulouscreations.homesafe.packages.data.toParcel
import com.meticulouscreations.homesafe.packages.data.toPatch
import com.meticulouscreations.homesafe.packages.data.toSnapshot
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.packages.domain.ReturnState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackagesRelayMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** What `packages_snapshot` in relay/relay.py writes, field for field. */
    private val body = """
        {"viewer": "sam", "people": ["alex", "sam"], "now": 1793316000.5, "today": "2026-10-29", "mail_on": true,
         "synced": {"at": 1793315900.0, "emails": 2, "failed": ["alex"]}, "camera_on": true, "camera": "Front Door", "camera_id": "front_door",
         "door_sensor": true, "model_on": true, "tracker_on": false, "away": true,
         "settings": {"notify": {"shipped": false, "delivered": true}, "hide_new": {"sam": true}},
         "packages": [
           {"id": "a", "owner": "sam", "source": "email", "stage": "delivered", "stage_at": 1793300000.0, "expected_from": "2026-10-28", "expected_to": "2026-10-29",
            "window_start": "14:00", "window_end": "16:00", "signature": true, "problem": null, "problem_at": null, "created": 1793000000.0, "updated": 1793300000.0,
            "delivered_at": 1793300000.0, "surprise": false, "masked": false, "pinned": true, "archived": false, "porch": "porch", "porch_at": 1793300000.0, "porch_by": null,
            "verdict": "confirmed", "verdict_by": null, "late": false, "carrier": "ups", "tracking": "1Z", "order_id": "113-1", "shop": "Target", "title": "Towels",
            "nickname": "New towels", "left_note": "Left at front door", "total": 54.5, "notes": "For the guests", "return_by": "2026-11-28", "return_state": "started",
            "refund_at": null, "verdict_what": "box", "verdict_confidence": 0.95, "visit_event": "1.2-abc", "visit_at": 1793299900.0, "url": "https://www.ups.com/track?tracknum=1Z",
            "frames": ["after", "before"], "timeline": [{"at": 1793300000.0, "kind": "delivered", "detail": "Left at front door"}]},
           {"id": "b", "owner": "alex", "source": "email", "stage": "shipped", "signature": false, "surprise": true, "masked": true, "pinned": false, "archived": false, "late": false, "carrier": "fedex"},
           {"id": "c", "owner": "", "source": "camera", "stage": "delivered", "verdict": "camera_only", "porch": "missing", "problem": "attempted", "carrier": "veho", "return_state": "none", "signature": false, "surprise": false, "masked": false, "pinned": false, "archived": false, "late": false}
         ]}
    """.trimIndent()

    private val snapshot get() = json.decodeFromString<RelayPackages>(body).toSnapshot()

    @Test
    fun aSnapshotComesThroughWhole() {
        val s = snapshot
        assertEquals("sam", s.viewer)
        assertEquals(listOf("alex", "sam"), s.people)
        assertEquals(1793316000L, s.nowEpochSeconds)
        assertEquals(ParcelDays.of(2026, 10, 29), s.today)
        assertTrue(s.mailOn && s.cameraOn && s.doorSensor && s.modelOn && s.away)
        assertFalse(s.trackerOn)
        assertEquals("Front Door", s.cameraName)
        assertEquals(1793315900L, s.syncedAt)
        assertEquals(listOf("alex"), s.failedMailboxes)
        assertFalse(s.settings.wants("shipped"))
        assertTrue(s.settings.wants("delivered"))
        assertTrue(s.settings.wants("something_new"))
        assertEquals(true, s.settings.hideNew["sam"])
    }

    @Test
    fun aParcelComesThroughWhole() {
        val p = snapshot.parcels[0]
        assertEquals(ParcelStage.DELIVERED, p.stage)
        assertEquals(Carrier.UPS, p.carrier)
        assertNull(p.carrierName)
        assertEquals(ParcelDays.of(2026, 10, 28), p.expectedFromDay)
        assertEquals(ParcelDays.of(2026, 10, 29), p.expectedToDay)
        assertEquals("14:00" to "16:00", p.windowStart to p.windowEnd)
        assertEquals(CameraVerdict.CONFIRMED, p.verdict)
        assertEquals("box", p.verdictWhat)
        assertEquals(PorchState.ON_PORCH, p.porch)
        assertEquals(ReturnState.STARTED, p.returnState)
        assertEquals(ParcelDays.of(2026, 11, 28), p.returnByDay)
        assertEquals(54.5, p.total)
        assertTrue(p.signature && p.pinned && p.hasFrames(after = true) && p.hasFrames(after = false))
        assertEquals("Left at front door", p.timeline.single().detail)
    }

    @Test
    fun aMaskedParcelHasOnlyWhatItWasGiven() {
        val p = snapshot.parcels[1]
        assertTrue(p.masked && p.surprise)
        assertNull(p.title)
        assertNull(p.shop)
        assertNull(p.tracking)
        assertTrue(p.timeline.isEmpty())
        assertEquals(Carrier.FEDEX, p.carrier)
    }

    @Test
    fun theCamerasOwnParcelAndAnUnknownCarrierAreKept() {
        val p = snapshot.parcels[2]
        assertTrue(p.fromCamera)
        assertEquals(CameraVerdict.CAMERA_ONLY, p.verdict)
        assertEquals(PorchState.MAY_BE_GONE, p.porch)
        assertEquals(ParcelProblem.ATTEMPTED, p.problem)
        assertEquals(Carrier.VEHO, p.carrier)
        assertEquals(ReturnState.NONE, p.returnState)
    }

    @Test
    fun aCarrierThisBuildHasNeverHeardOfKeepsItsName() {
        val p = RelayPackage(id = "x", carrier = "uniuni").toParcel()
        assertEquals(Carrier.OTHER, p.carrier)
        assertEquals("uniuni", p.carrierName)
        assertNull(RelayPackage(id = "x", carrier = "other").toParcel().carrierName)
    }

    @Test
    fun anUnknownStageOrVerdictReadsAsNothingRatherThanBreaking() {
        val p = RelayPackage(id = "x", stage = "teleported", verdict = "maybe", problem = "gremlins", porch = "moon").toParcel()
        assertEquals(ParcelStage.ORDERED, p.stage)
        assertNull(p.verdict)
        assertNull(p.problem)
        assertNull(p.porch)
    }

    @Test
    fun aChangeSaysOnlyWhatChanged() {
        val patch = ParcelChange(porch = PorchState.INSIDE, returnState = ReturnState.SENT, cameraRight = false).toPatch()
        assertEquals("inside", patch.porch)
        assertEquals("sent", patch.returnState)
        assertEquals("no", patch.camera)
        assertNull(patch.nickname)
        assertNull(patch.stage)
        assertEquals("delivered", ParcelChange(markDelivered = true).toPatch().stage)
        assertEquals("porch", ParcelChange(porch = PorchState.ON_PORCH).toPatch().porch)
        assertNull(ParcelChange(porch = PorchState.MAY_BE_GONE).toPatch().porch)
    }

    @Test
    fun aPicturesAddressSaysWhoIsAsking() {
        assertEquals("http://box:8787/packages/a/frames/after?device=me", PackagesRelayApi.pictureUrl("http://box:5000", "me", "a", "after"))
        assertEquals("http://box:8787/packages/porch/now?device=me", PackagesRelayApi.pictureUrl("http://box:5000", "me", null, "now"))
    }
}
