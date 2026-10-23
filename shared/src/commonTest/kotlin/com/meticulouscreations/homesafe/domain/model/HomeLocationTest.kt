package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** How far a fix is from home: what decides whether a phone is in or out without a crossing. */
class HomeLocationTest {

    @Test
    fun aDistanceFromHomeIsMeasuredAlongTheGround() {
        val home = HomeLocation(latitude = 37.76, longitude = -122.23, radiusMeters = 150.0)
        assertEquals(0.0, home.distanceMeters(37.76, -122.23))
        // A thousandth of a degree of latitude is ~111 m anywhere; of longitude, ~111 m times cos(latitude).
        assertEquals(111.2, home.distanceMeters(37.761, -122.23), absoluteTolerance = 0.1)
        assertEquals(87.9, home.distanceMeters(37.76, -122.229), absoluteTolerance = 0.1)
    }
}
