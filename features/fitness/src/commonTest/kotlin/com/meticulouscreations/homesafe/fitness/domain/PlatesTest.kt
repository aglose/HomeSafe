package com.meticulouscreations.homesafe.fitness.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatesTest {

    @Test
    fun theBarAloneIsNoPlates() {
        assertEquals(emptyList(), Plates.perSide(45.0))
    }

    @Test
    fun theHeaviestPlatesGoOnFirst() {
        assertEquals(listOf(45.0, 45.0), Plates.perSide(225.0))
        assertEquals(listOf(45.0, 35.0, 5.0), Plates.perSide(215.0))
        assertEquals(listOf(45.0, 25.0), Plates.perSide(185.0))
        assertEquals(listOf(45.0, 10.0, 5.0), Plates.perSide(165.0))
        assertEquals(listOf(45.0, 35.0), Plates.perSide(205.0))
        assertEquals(listOf(45.0, 45.0, 25.0, 5.0, 2.5), Plates.perSide(290.0))
    }

    @Test
    fun lighterThanTheBarCantBeLoaded() {
        assertNull(Plates.perSide(40.0))
    }

    @Test
    fun aWeightThePlatesDontMakeCantBeLoaded() {
        assertNull(Plates.perSide(137.0))
        assertNull(Plates.perSide(46.0))
    }

    @Test
    fun anotherBarIsCountedFrom() {
        assertEquals(listOf(10.0), Plates.perSide(55.0, bar = 35.0))
        assertNull(Plates.perSide(30.0, bar = 35.0))
    }
}
