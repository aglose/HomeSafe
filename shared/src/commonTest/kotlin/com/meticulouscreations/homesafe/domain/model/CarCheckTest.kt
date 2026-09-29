package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 100% guess is only sure once the relay's car check confirmed it, by plate or by make, model and
 * colour; the rest are asked about. Without the relay a 1.0 is taken at its word, as it always was.
 */
class CarCheckTest {

    private fun crop(guess: String, score: Double = 1.0, check: CarCheck? = null, checksKnown: Boolean = true) =
        UnlabeledCrop.fromFileName("1790636195.070977-unrrq2-1790636200.5-$guess-$score.webp").copy(check = check, checksKnown = checksKnown)

    private val blueTesla = CarCheck(verdict = "keep", classifierName = "andrews_tesla", name = "andrews_tesla", verified = "looks", sawColour = "blue", sawMake = "tesla", sawModel = "Model Y")
    private val blackTesla = CarCheck(verdict = "clear", classifierName = "andrews_tesla", name = null, verified = null, sawColour = "black", sawMake = "tesla")
    private val night = CarCheck(verdict = "keep", classifierName = "andrews_tesla", name = "andrews_tesla", verified = null, sawColour = "unknown", sawMake = "unknown")
    private val sarahsByPlate = CarCheck(verdict = "relabel", classifierName = "andrews_tesla", name = "sarahs_car", verified = "plate", sawColour = "unknown")

    @Test
    fun aSureGuessTheCheckConfirmedIsSure() {
        val sure = crop("andrews_tesla", check = blueTesla)
        assertTrue(sure.isConfident)
        assertFalse(sure.isDoubted)
        assertEquals("Confirmed by its looks: looked like a blue Tesla Model Y", sure.checkNote())
    }

    @Test
    fun aSureGuessTheCheckContradictedIsAskedAbout() {
        val doubted = crop("andrews_tesla", check = blackTesla)
        assertFalse(doubted.isConfident)
        assertTrue(doubted.isDoubted)
        assertEquals("Car check: looked like a black Tesla, not Andrew's Tesla", doubted.checkNote())
    }

    @Test
    fun aCarTooDarkToCheckIsAskedAbout() {
        val doubted = crop("andrews_tesla", check = night)
        assertTrue(doubted.isDoubted)
        assertEquals("Car check couldn't confirm Andrew's Tesla: too dark to tell its colour", doubted.checkNote())
    }

    @Test
    fun aPlateThatNamesAnotherCarSaysWhose() {
        assertEquals("Car check: Sarah's Car by its plate, not Andrew's Tesla", crop("andrews_tesla", check = sarahsByPlate).checkNote())
    }

    @Test
    fun aSureGuessNotLookedAtYetIsAskedAbout() {
        val unchecked = crop("andrews_tesla")
        assertTrue(unchecked.isDoubted)
        assertEquals("Sure it's Andrew's Tesla, but not confirmed by its plate or looks yet", unchecked.checkNote())
    }

    @Test
    fun notOursAtOneHundredStaysSureWithNoCheck() {
        val street = crop("none")
        assertTrue(street.isConfident)
        assertNull(street.checkNote())
    }

    @Test
    fun withoutTheRelayAOneHundredIsTakenAtItsWord() {
        val old = crop("andrews_tesla", checksKnown = false)
        assertTrue(old.isConfident)
        assertFalse(old.isDoubted)
        assertNull(old.checkNote())
    }

    @Test
    fun anUnsureGuessIsNeitherSureNorDoubted() {
        val unsure = crop("andrews_tesla", score = 0.93, check = blueTesla)
        assertFalse(unsure.isConfident)
        assertFalse(unsure.isDoubted)
        assertEquals("Confirmed by its looks: looked like a blue Tesla Model Y", unsure.checkNote())
    }

    @Test
    fun theDatasetSplitsOnTheCheck() {
        val data = ClassifierDataset(
            model = ClassifierModel("known_cars", listOf("car")),
            categoryCounts = mapOf("none" to 1, "andrews_tesla" to 1),
            queue = listOf(crop("andrews_tesla", check = blueTesla), crop("andrews_tesla", check = blackTesla), crop("none")),
            hasTrained = true,
            newImagesSinceTraining = 0,
        )
        assertEquals(listOf(blackTesla), data.uncertainQueue.map { it.check })
        assertEquals(2, data.confidentQueue.size)
    }

    @Test
    fun plates() {
        assertEquals("8ABC123", CarProfile.normalPlate(" 8abc-123 "))
        assertEquals("blue Tesla Model Y", CarProfile("andrews_tesla", make = "tesla", model = "Model Y", colour = "blue").looks)
        assertEquals("BMW", makeName("bmw"))
        assertTrue(CarProfile("x").isEmpty)
    }
}
