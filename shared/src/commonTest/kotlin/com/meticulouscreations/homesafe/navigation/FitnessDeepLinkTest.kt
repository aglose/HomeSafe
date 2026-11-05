package com.meticulouscreations.homesafe.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [FitnessDeepLink]: the URI behind a tap on the workout in progress from outside the app, and the hand-off that holds it. */
class FitnessDeepLinkTest {

    @Test
    fun aLinkReadsBackFromItsOwnUri() {
        assertEquals("homesafe://fitness?page=workout", FitnessDeepLink.Workout.toUri())
        assertEquals("homesafe://fitness", FitnessDeepLink().toUri())
        FitnessDeepLink.Page.entries.forEach { page -> assertEquals(FitnessDeepLink(page), FitnessDeepLink.fromUri(FitnessDeepLink(page).toUri())) }
    }

    @Test
    fun aPageThisBuildDoesNotKnowOpensTheAppWhereItWasLeft() {
        assertEquals(FitnessDeepLink(), FitnessDeepLink.fromUri("homesafe://fitness?page=sauna"))
    }

    @Test
    fun anythingElseIsNotAFitnessLink() {
        assertNull(FitnessDeepLink.fromUri("homesafe://finance?tab=budget"))
        assertNull(FitnessDeepLink.fromUri("https://fitness?page=workout"))
        assertNull(FitnessDeepLink.fromUri("not a uri"))
        assertNull(FitnessDeepLink.fromUri(null))
        assertNull(MomentDeepLink.fromUri(FitnessDeepLink.Workout.toUri()))
    }

    @Test
    fun aTapWaitsToBeActedOn() {
        FitnessDeepLinks.open(FitnessDeepLink.Workout)
        assertEquals(FitnessDeepLink.Workout, FitnessDeepLinks.pending.value)
        FitnessDeepLinks.consume(FitnessDeepLink())
        assertEquals(FitnessDeepLink.Workout, FitnessDeepLinks.pending.value)
        FitnessDeepLinks.consume(FitnessDeepLink.Workout)
        assertNull(FitnessDeepLinks.pending.value)
    }
}
