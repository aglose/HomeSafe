package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shapes like the Front Door's on 2026-09-29: clutter by the door the detector kept calling a
 * person, its box only jittering, and someone climbing the steps to the same spot. The relay's
 * `PhantomTest` holds it to the same rule.
 */
class PhantomPeopleTest {

    private val door = DetectionBox(0.05, 0.30, 0.22, 0.40)
    private val jitter = listOf(0.16 to 0.70, 0.161 to 0.702, 0.159 to 0.70, 0.16 to 0.699, 0.162 to 0.701)
    private val climb = listOf(0.60 to 0.95, 0.45 to 0.88, 0.32 to 0.80, 0.20 to 0.72, 0.17 to 0.70)
    private val spots = listOf(PhantomSpot("marked", "amcrest_1", door))

    private fun person(
        id: String = "again",
        camera: String = "amcrest_1",
        label: String = "person",
        subLabel: String? = null,
        box: DetectionBox? = door,
        path: List<Pair<Double, Double>> = jitter,
    ) = MomentEvent(
        id = id, cameraName = camera, label = label, subLabel = subLabel,
        startEpochSeconds = 1_790_000_000.0, endEpochSeconds = 1_790_000_003.0, topScore = 0.8, hasClip = true, hasSnapshot = false,
        pathPoints = path.map { (x, y) -> MaskPoint(x, y) }, box = box,
    )

    @Test
    fun theSameClutterAtTheSameSpotIsThePhantomAgain() {
        assertTrue(person().isPhantom(spots))
        assertTrue(person(box = DetectionBox(0.06, 0.31, 0.21, 0.38)).isPhantom(spots), "the box jitters")
        assertTrue(person(path = jitter.take(2)).isPhantom(spots), "a one-second flicker")
        assertTrue(person(subLabel = "none").isPhantom(spots), "a placeholder is no name")
    }

    @Test
    fun theMarkedDetectionItselfIsAPhantomWhateverItsShape() {
        assertTrue(person(id = "marked", path = climb, box = null).isPhantom(spots))
    }

    @Test
    fun someoneRealAtTheSpotIsNot() {
        assertFalse(person(path = climb).isPhantom(spots), "they walked there, though they moved less than their box")
        assertTrue(person(path = climb).isStill(), "which a car's stillness would have missed")
        assertFalse(person(subLabel = "andrew").isPhantom(spots), "a face Frigate knows")
    }

    @Test
    fun onlyAPersonAtThatSpotOnThatCamera() {
        assertFalse(person(box = DetectionBox(0.6, 0.3, 0.2, 0.4)).isPhantom(spots))
        assertFalse(person(camera = "hikvision_1").isPhantom(spots))
        assertFalse(person(label = "dog").isPhantom(spots))
        assertFalse(person(box = null).isPhantom(spots), "no box to match")
        assertFalse(person().isPhantom(emptyList()))
    }

    @Test
    fun onlyAnUnnamedPersonCanBeMarked() {
        assertTrue(person().canMarkNotPerson)
        assertFalse(person(subLabel = "andrew").canMarkNotPerson)
        assertFalse(person(label = "car").canMarkNotPerson)
    }
}
