package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which tracked car a hand-drawn rectangle is about, and reading a frame's size from its JPEG header. */
class CarTaggingTest {

    private fun box(x: Double, y: Double, w: Double, h: Double) = SeenBox(x, y, w, h, epochSeconds = null)

    // Two cars side by side in the driveway, and one with no box at all.
    private val tesla = TrackedObject("1.0-tesla", "car", subLabel = "andrews_tesla", box = box(0.10, 0.50, 0.30, 0.25))
    private val mercedes = TrackedObject("2.0-merc", "car", subLabel = null, box = box(0.45, 0.52, 0.28, 0.22))
    private val boxless = TrackedObject("3.0-none", "car", subLabel = null, box = null)
    private val tracked = listOf(tesla, mercedes, boxless)

    @Test
    fun aLooseRectangleRoundACarIsThatCar() {
        assertEquals(tesla, tracked.trackedObjectAt(box(0.08, 0.47, 0.35, 0.30)))
        assertEquals(mercedes, tracked.trackedObjectAt(box(0.47, 0.55, 0.22, 0.17)), "drawn just inside the box")
    }

    @Test
    fun aRectangleOverBothCarsIsTheOneItCoversMost() {
        assertEquals(mercedes, tracked.trackedObjectAt(box(0.35, 0.50, 0.40, 0.25)))
    }

    @Test
    fun aRectangleWhereNothingIsTrackedIsNoCar() {
        assertNull(tracked.trackedObjectAt(box(0.80, 0.10, 0.15, 0.10)), "a car Frigate missed")
        assertNull(listOf(boxless).trackedObjectAt(box(0.0, 0.0, 1.0, 1.0)), "a tracked object with no box can't be matched")
    }

    @Test
    fun aRectangleThatBarelyTouchesACarIsNotIt() {
        // Overlaps the Tesla's right edge by a sliver.
        assertNull(tracked.trackedObjectAt(box(0.38, 0.20, 0.05, 0.35)))
    }

    @Test
    fun theRectangleBetweenTwoPointsIsTheSameWhicheverWayItWasDragged() {
        val expected = box(0.2, 0.3, 0.4, 0.5)
        listOf(
            boxBetween(0.2, 0.3, 0.6, 0.8),
            boxBetween(0.6, 0.8, 0.2, 0.3),
            boxBetween(0.6, 0.3, 0.2, 0.8),
        ).forEach { drawn ->
            assertEquals(expected.x, drawn.x, 1e-9)
            assertEquals(expected.y, drawn.y, 1e-9)
            assertEquals(expected.width, drawn.width, 1e-9)
            assertEquals(expected.height, drawn.height, 1e-9)
        }
    }

    @Test
    fun aDragOffTheFrameStopsAtItsEdge() {
        val drawn = boxBetween(0.9, 0.9, 1.4, -0.2)
        assertEquals(0.9, drawn.x, 1e-9)
        assertEquals(0.0, drawn.y, 1e-9)
        assertEquals(0.1, drawn.width, 1e-9)
        assertEquals(0.9, drawn.height, 1e-9)
    }

    @Test
    fun aStrayTouchIsNotACar() {
        assertFalse(box(0.5, 0.5, 0.01, 0.2).isTaggable)
        assertTrue(box(0.5, 0.5, 0.05, 0.05).isTaggable)
    }

    @Test
    fun readsTheFrameSizeFromTheJpegHeader() {
        // SOI, an APP0 segment to skip, then a baseline SOF0 for 1280x720.
        val jpeg = bytes(
            0xFF, 0xD8,
            0xFF, 0xE0, 0x00, 0x06, 0x4A, 0x46, 0x49, 0x46,
            0xFF, 0xC0, 0x00, 0x11, 0x08, 0x02, 0xD0, 0x05, 0x00, 0x03, 0x01, 0x22, 0x00,
        )
        assertEquals(1280 to 720, jpegSize(jpeg))
    }

    @Test
    fun aHuffmanTableIsNotTheFrameHeader() {
        // DHT (0xC4) sits in the SOF marker range; the real SOF2 after it is 704x480.
        val jpeg = bytes(
            0xFF, 0xD8,
            0xFF, 0xC4, 0x00, 0x04, 0x00, 0x00,
            0xFF, 0xC2, 0x00, 0x11, 0x08, 0x01, 0xE0, 0x02, 0xC0, 0x03,
        )
        assertEquals(704 to 480, jpegSize(jpeg))
    }

    @Test
    fun somethingThatIsNotAJpegHasNoSize() {
        assertNull(jpegSize(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)), "a PNG")
        assertNull(jpegSize(ByteArray(0)))
        assertNull(jpegSize(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)), "cut off before the frame header")
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun aNewKnownCarIsFiledUnderTheKeyItsNameReadsBackFrom() {
        assertEquals("grandmas_van", CarTagging.knownCarKey("  Grandma's Van "))
        assertEquals("Grandma's Van", subLabelDisplayName(CarTagging.knownCarKey("Grandma's Van")!!))
        assertEquals("ron_and_judys_mercedes", CarTagging.knownCarKey("Ron and Judy's Mercedes"))
        assertEquals("in-laws_mercedes", CarTagging.knownCarKey("-In-laws Mercedes!"), "no leading dash, which the relay refuses")
        assertEquals(64, CarTagging.knownCarKey("a".repeat(100))?.length)
    }

    @Test
    fun aPlaceholderOrNothingIsNotANewCar() {
        assertNull(CarTagging.knownCarKey("   "))
        assertNull(CarTagging.knownCarKey("!!!"), "the slug would make up a name")
        assertNull(CarTagging.knownCarKey("None"), "`none` is \"not ours\"")
        assertNull(CarTagging.knownCarKey("Not ours"))
    }

    @Test
    fun aGenericCarIsACarTheClassifierLeftUnnamed() {
        fun moment(label: String, subLabel: String?) = MomentEvent(
            id = "e",
            cameraName = "hikvision_1",
            label = label,
            subLabel = subLabel,
            startEpochSeconds = 1.0,
            endEpochSeconds = 2.0,
            topScore = 0.9,
            hasClip = true,
            hasSnapshot = false,
        )
        assertTrue(moment("car", null).isGenericCar)
        assertTrue(moment("car", "none").isGenericCar, "the none class is not a name")
        assertTrue(moment("Car", "").isGenericCar)
        assertFalse(moment("car", "andrews_tesla").isGenericCar)
        assertFalse(moment("truck", null).isGenericCar, "the classifier only runs on cars")
        assertFalse(moment("person", null).isGenericCar)
    }
}
