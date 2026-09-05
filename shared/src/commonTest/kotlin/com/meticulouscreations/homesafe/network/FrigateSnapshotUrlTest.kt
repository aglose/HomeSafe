package com.meticulouscreations.homesafe.network

import kotlin.test.Test
import kotlin.test.assertEquals

class FrigateSnapshotUrlTest {

    @Test
    fun fullSizeSnapshotHasNoQuery() {
        assertEquals("http://frigate:8971/api/cam/latest.jpg", frigateSnapshotUrl("http://frigate:8971/", "cam"))
    }

    @Test
    fun heightIsPassedAsFrigatesHParameter() {
        assertEquals("http://frigate:8971/api/cam/latest.jpg?h=480", frigateSnapshotUrl("http://frigate:8971", "cam", height = 480))
    }

    @Test
    fun recordingSnapshotUsesMillisecondTimeAndFrigatesHeightParameter() {
        assertEquals(
            "http://frigate:8971/api/cam/recordings/1788407038.500/snapshot.jpg?height=480",
            frigateRecordingSnapshotUrl("http://frigate:8971", "cam", epochSeconds = 1788407038.5, height = 480),
        )
        assertEquals(
            "http://frigate:8971/api/cam/recordings/1788407038.000/snapshot.jpg",
            frigateRecordingSnapshotUrl("http://frigate:8971/", "cam", epochSeconds = 1788407038.0),
        )
    }
}
