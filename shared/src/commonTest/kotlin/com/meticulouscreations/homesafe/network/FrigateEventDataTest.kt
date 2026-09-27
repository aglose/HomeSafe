package com.meticulouscreations.homesafe.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a tracked object's box is now, from its best frame and its path. */
class FrigateEventDataTest {

    private fun path(json: String) = Json.parseToJsonElement(json).jsonArray

    @Test
    fun aCarThatDroveOnIsWhereItsPathEnds() {
        // Andrew's Tesla, 2026-09-27 14:43: best frame in the driveway, now parked at the kerb.
        val data = FrigateEventData(
            box = listOf(0.249, 0.343, 0.255, 0.225),
            pathData = path("""[[[0.3109, 0.625], 1790545384.2], [[0.6, 0.55], 1790545390.0], [[0.8469, 0.5167], 1790545812.1]]"""),
        )
        val now = data.latestBox()!!
        listOf(0.7194, 0.2917, 0.255, 0.225).zip(now).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-9) }
    }

    @Test
    fun withNoPathItIsTheBestFrame() {
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), FrigateEventData(box = listOf(0.1, 0.2, 0.3, 0.4)).latestBox())
        assertNull(FrigateEventData(box = null).latestBox())
    }
}
