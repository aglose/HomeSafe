package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoneConfigParamsTest {

    private val tri = MaskPolygon(listOf(MaskPoint(0.0, 0.0), MaskPoint(1.0, 0.0), MaskPoint(0.5, 0.5)))
    private val quad = MaskPolygon(listOf(MaskPoint(0.1, 0.1), MaskPoint(0.4, 0.1), MaskPoint(0.4, 0.4), MaskPoint(0.1, 0.4)))

    @Test
    fun aNewZoneWritesCoordinatesObjectsAndFriendlyName() {
        val params = zoneConfigParams("cam", listOf(DetectionZone("driveway", "Driveway", tri, listOf("car", "person"))), previous = emptyList())
        assertEquals(
            listOf(
                "cameras.cam.zones.driveway.coordinates" to "0.0,0.0,1.0,0.0,0.5,0.5",
                "cameras.cam.zones.driveway.objects" to "car",
                "cameras.cam.zones.driveway.objects" to "person",
                "cameras.cam.zones.driveway.friendly_name" to "Driveway",
            ),
            params,
        )
    }

    @Test
    fun anUnchangedZoneSendsNothing() {
        val zone = DetectionZone("driveway", "Driveway", tri, listOf("car"))
        assertTrue(zoneConfigParams("cam", listOf(zone), listOf(zone)).isEmpty())
    }

    @Test
    fun onlyChangedFieldsAreWritten() {
        val before = DetectionZone("driveway", "Driveway", tri, listOf("car"))
        val after = before.copy(polygon = quad)
        assertEquals(
            listOf("cameras.cam.zones.driveway.coordinates" to "0.1,0.1,0.4,0.1,0.4,0.4,0.1,0.4"),
            zoneConfigParams("cam", listOf(after), listOf(before)),
        )
    }

    @Test
    fun clearingObjectsOrFriendlyNameSendsABlankToDeleteTheKey() {
        val before = DetectionZone("driveway", "Driveway", tri, listOf("car"))
        val after = DetectionZone("driveway", null, tri, emptyList())
        assertEquals(
            listOf(
                "cameras.cam.zones.driveway.objects" to "",
                "cameras.cam.zones.driveway.friendly_name" to "",
            ),
            zoneConfigParams("cam", listOf(after), listOf(before)),
        )
    }

    @Test
    fun aZoneThatNeverHadObjectsDoesNotTryToDeleteThem() {
        // Frigate answers 500 when asked to delete a key that isn't there.
        val before = DetectionZone("lawn", null, tri)
        val after = before.copy(polygon = quad)
        assertEquals(listOf("cameras.cam.zones.lawn.coordinates" to quad.let { "0.1,0.1,0.4,0.1,0.4,0.4,0.1,0.4" }), zoneConfigParams("cam", listOf(after), listOf(before)))
    }

    @Test
    fun aRemovedZoneIsDeletedByItsBareKey() {
        val gone = DetectionZone("sidewalk", null, tri)
        val kept = DetectionZone("lawn", null, quad)
        assertEquals(listOf("cameras.cam.zones.sidewalk" to ""), zoneConfigParams("cam", listOf(kept), listOf(gone, kept)))
    }

    @Test
    fun invalidZonesAreSkippedRatherThanSent() {
        val badName = DetectionZone("side yard", null, tri)
        val tooFewPoints = DetectionZone("lawn", null, MaskPolygon(listOf(MaskPoint(0.0, 0.0), MaskPoint(1.0, 1.0))))
        assertTrue(zoneConfigParams("cam", listOf(badName, tooFewPoints), emptyList()).isEmpty())
    }
}
