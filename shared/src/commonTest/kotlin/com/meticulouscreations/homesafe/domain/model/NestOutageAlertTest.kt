package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NestOutageAlertTest {

    private val outage = mapOf(
        "system" to "nest",
        "notif_id" to "nest-backyard",
        "camera" to "backyard",
        "nest_camera_name" to "Backyard",
        "nest_event" to "unavailable",
        "nest_reason" to "refused",
        "nest_since" to "1792300000",
    )

    private val back = mapOf("system" to "nest", "notif_id" to "nest-backyard", "camera" to "backyard", "nest_camera_name" to "Backyard", "nest_event" to "streaming")

    @Test
    fun aNestOutageIsReadFromThePushsData() {
        assertEquals(true, NestOutageAlert.isNestOutage { outage[it] })
        assertEquals(NestOutageAlert("nest-backyard", "backyard", "Backyard", NestOutageAlert.Reason.REFUSED, 1_792_300_000L), NestOutageAlert.from { outage[it] })
        assertEquals(NestOutageAlert.Reason.UNCONFIRMED, NestOutageAlert.from { (outage + ("nest_reason" to "unconfirmed"))[it] }?.reason)
        assertNull(NestOutageAlert.from { (outage - "nest_since")[it] }?.sinceEpochSeconds, "the relay didn't know when it began")
    }

    @Test
    fun theCamerasReturnIsReadFromThePushsData() {
        assertEquals(NestOutageAlert("nest-backyard", "backyard", "Backyard", reason = null, sinceEpochSeconds = null), NestOutageAlert.from { back[it] })
    }

    @Test
    fun whatAPushLeavesOutFallsBackToTheCamera() {
        val bare = mapOf("system" to "nest", "camera" to "backyard", "nest_event" to "streaming")
        assertEquals(NestOutageAlert("nest-backyard", "backyard", "backyard", reason = null, sinceEpochSeconds = null), NestOutageAlert.from { bare[it] })
    }

    @Test
    fun anEventOrReasonThisBuildCantWordIsLeftToTheRelaysEnglish() {
        assertNull(NestOutageAlert.from { (outage + ("nest_event" to "degraded"))[it] }, "an event a newer relay knows")
        assertNull(NestOutageAlert.from { (outage + ("nest_reason" to "quota"))[it] }, "a reason a newer relay knows")
        assertNull(NestOutageAlert.from { (outage - "nest_reason")[it] }, "an outage with no reason")
        assertNull(NestOutageAlert.from { (outage - "camera")[it] }, "no camera")
        assertEquals(true, NestOutageAlert.isNestOutage { (outage + ("nest_event" to "degraded"))[it] }, "still a Nest push, so the relay's English is shown")
    }

    @Test
    fun otherPushesArentNestOutages() {
        assertEquals(false, NestOutageAlert.isNestOutage { mapOf("review_id" to "r1")[it] })
        assertEquals(false, NestOutageAlert.isNestOutage { mapOf("door" to "0x01", "door_event" to "opened")[it] })
        assertNull(NestOutageAlert.from { mapOf("camera" to "backyard", "nest_event" to "streaming")[it] })
    }
}
