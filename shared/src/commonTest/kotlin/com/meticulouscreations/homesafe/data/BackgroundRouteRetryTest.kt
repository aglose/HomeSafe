package com.meticulouscreations.homesafe.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundRouteRetryTest {

    @Test
    fun offScreenTheWaitDoublesFromTheOrdinaryOneAndStopsAtFiveMinutes() {
        assertEquals(
            listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            listOf(1, 2, 3, 4, 5, 6, 40).map { backgroundRouteRetryMs(10_000L, it) },
        )
    }

    @Test
    fun aWaitAlreadyLongerThanThatIsLeftAsItIs() {
        assertEquals(Long.MAX_VALUE, backgroundRouteRetryMs(Long.MAX_VALUE, 3))
    }
}
