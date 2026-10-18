package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertNotEquals

class LastSeenStillTest {

    @Test
    fun twoServersCamerasOfTheSameNameNeverShareAStill() {
        assertNotEquals(
            lastSeenStillKey("http://100.99.163.71:1984/api/frame.jpeg?src=living_room"),
            lastSeenStillKey("http://192.168.1.20:1984/api/frame.jpeg?src=living_room"),
        )
    }

    @Test
    fun twoCamerasOnOneServerNeverShareAStill() {
        assertNotEquals(
            lastSeenStillKey("http://100.99.163.71:1984/api/frame.jpeg?src=living_room"),
            lastSeenStillKey("http://100.99.163.71:1984/api/frame.jpeg?src=kitchen"),
        )
    }
}
