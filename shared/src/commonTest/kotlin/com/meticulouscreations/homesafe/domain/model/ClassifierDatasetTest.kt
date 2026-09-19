package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ClassifierDataset.categories] is what the labelling screen puts under a crop, and it is wider
 * than the server's own category list on purpose — see the property's own note. These pin that
 * width, and that widening it left the training gate alone. The rest pin how the camera screen's
 * live section finds a queued crop for each car in view ([ClassifierDataset.liveCandidates]).
 */
class ClassifierDatasetTest {

    private fun dataset(
        categoryCounts: Map<String, Int> = emptyMap(),
        queue: List<UnlabeledCrop> = emptyList(),
    ) = ClassifierDataset(
        model = ClassifierModel(name = "known_cars", objects = listOf("car"), enabled = true),
        categoryCounts = categoryCounts,
        queue = queue,
        hasTrained = false,
        newImagesSinceTraining = 0,
    )

    /** Frigate's live crops: `<eventEpoch>-<suffix>-<frameEpoch>-<category>-<score>.webp`. */
    private fun crop(guess: String, score: Double = 0.42) =
        UnlabeledCrop.fromFileName("1788624497.850494-lvvjnr-1788624498.497929-$guess-$score.webp")

    @Test
    fun noneIsOfferedEvenWhenTheServerListsNoCategoriesAtAll() {
        // The dead end this change is about: a queue of crops and nothing to file them under.
        assertEquals(listOf("none"), dataset(queue = listOf(crop("unknown"))).categories)
    }

    @Test
    fun aCategoryTheQueueWasClassifiedIntoIsOfferedThoughTheDatasetHasNoImagesInIt() {
        // A cleared dataset under a still-trained model: the model keeps naming cars the
        // dataset no longer lists.
        val data = dataset(queue = listOf(crop("sarahs_tesla"), crop("ron_and_judys_mercedes")))
        assertEquals(listOf("ron_and_judys_mercedes", "sarahs_tesla", "none"), data.categories)
    }

    @Test
    fun noneSortsLastAndTheRestAlphabetically() {
        val data = dataset(categoryCounts = mapOf("none" to 2, "sarahs_tesla" to 1, "andys_van" to 3))
        assertEquals(listOf("andys_van", "sarahs_tesla", "none"), data.categories)
    }

    @Test
    fun aCategoryIsOfferedOnceHoweverManyWaysItArrives() {
        val data = dataset(
            categoryCounts = mapOf("sarahs_tesla" to 1, "none" to 2),
            queue = listOf(crop("sarahs_tesla"), crop("sarahs_tesla"), crop("none")),
        )
        assertEquals(listOf("sarahs_tesla", "none"), data.categories)
    }

    @Test
    fun placeholderGuessesAreNotCategories() {
        // "unknown" is what Frigate writes when the model has no opinion, and a crop with no
        // parseable guess has none at all; neither is a name to file under.
        val data = dataset(queue = listOf(crop("unknown"), UnlabeledCrop.fromFileName("example_001.jpg")))
        assertEquals(listOf("none"), data.categories)
    }

    @Test
    fun anOfferedCategoryDoesNotMakeAnUntrainableModelLookTrainable() {
        // The gate counts categories that have images, so the queue's guesses don't reach it.
        val data = dataset(
            categoryCounts = mapOf("sarahs_tesla" to 1),
            queue = listOf(crop("ron_and_judys_mercedes")),
        )
        assertEquals(listOf("ron_and_judys_mercedes", "sarahs_tesla", "none"), data.categories)
        assertFalse(data.canTrain, "one category with images is one class, whatever is on offer")

        assertTrue(dataset(categoryCounts = mapOf("sarahs_tesla" to 1, "none" to 2)).canTrain)
    }

    @Test
    fun anEmptyCategoryOnTheServerIsStillOffered() {
        // Frigate lists a folder it has, even with nothing in it yet.
        val data = dataset(categoryCounts = mapOf("sarahs_tesla" to 0, "none" to 2))
        assertEquals(listOf("sarahs_tesla", "none"), data.categories)
        assertFalse(data.canTrain)
    }

    /** A crop of event [eventId] taken at [frameEpoch], named the way Frigate names live attempts. */
    private fun cropOf(eventId: String, frameEpoch: String, guess: String = "none", score: Double = 0.42) =
        UnlabeledCrop.fromFileName("$eventId-$frameEpoch-$guess-$score.webp")

    @Test
    fun aTrackedCarIsOfferedWithItsNewestCrop() {
        val older = cropOf("1788832091.745689-e2bxi0", "1788832092.0")
        val newest = cropOf("1788832091.745689-e2bxi0", "1788832104.5", guess = "sarahs_tesla", score = 0.71)
        val car = TrackedObject(eventId = "1788832091.745689-e2bxi0", label = "car", subLabel = null)
        // Out of order on purpose: the newest is picked by when it was taken, not where it sits.
        val data = dataset(queue = listOf(older, newest))
        assertEquals(listOf(LiveCandidate(car, newest)), data.liveCandidates(listOf(car)))
    }

    @Test
    fun onlyTrackedObjectsTheModelRunsOnAndHasACropOfAreOffered() {
        val car = TrackedObject("1788832091.745689-e2bxi0", "car", subLabel = null)
        val person = TrackedObject("1788832095.985398-hkvbhs", "person", subLabel = null)
        val carWithoutCrops = TrackedObject("1788832130.376381-6wpol8", "car", subLabel = null)
        val parkedNamed = TrackedObject("1788830000.000000-parked", "car", subLabel = "sarahs_tesla")
        val data = dataset(
            queue = listOf(
                cropOf(car.eventId, "1788832092.0"),
                // Were a person's crop ever queued, the model still doesn't run on people.
                cropOf(person.eventId, "1788832096.0"),
                cropOf(parkedNamed.eventId, "1788830001.0", guess = "sarahs_tesla", score = 1.0),
                // A car that has left: its crops stay queued, but it's no longer on camera.
                cropOf("1788820000.000000-gone00", "1788820001.0"),
            ),
        )

        val candidates = data.liveCandidates(listOf(parkedNamed, person, car, carWithoutCrops))

        assertEquals(listOf(parkedNamed.eventId, car.eventId), candidates.map { it.tracked.eventId }, "in the order Frigate tracks them")
        assertEquals(listOf(true, false), candidates.map { it.isConfident }, "the named car's 100 % crop is folded away")
    }

    @Test
    fun historyMinedExamplesNeverMatchATrackedObject() {
        val car = TrackedObject("1788832091.745689-e2bxi0", "car", subLabel = null)
        assertTrue(dataset(queue = listOf(UnlabeledCrop.fromFileName("example_001.jpg"))).liveCandidates(listOf(car)).isEmpty())
    }
}
