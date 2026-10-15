package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Teaching a custom classifier from the Settings page: the household's `household_cars` model
 * with its "Sarah's Tesla" and "Not ours" categories and two crops of the driveway car waiting
 * for a label. Filing, discarding, adding a category and training each go to
 * `/api/classification/household_cars/...` and land on the fake server.
 */
class ClassifierJourneyTest {

    @Test
    fun theClassifierRowOpensItsLabellingScreenWithCategoriesAndTheQueue() = runAppJourney {
        val queued = state.edit { classifiers.single().queue.toList() }
        val classifier = ClassifierRobot(this)
        signIn.signInAs()
        classifier.open()

        awaitText(ClassifierRobot.HOUSEHOLD_CARS)
        classifier.awaitCategory("Sarah's Tesla", images = 2)
        classifier.awaitCategory("Not ours", images = 1)
        awaitText("Never trained")
        classifier.awaitWaiting(2)
        // One crop the model took for Sarah's Tesla, one it took for a stranger's car (Frigate writes
        // its guess into the file name); each card shows the guess and offers every category.
        for (file in queued) {
            val guess = if ("-sarahs_tesla-" in file) "Sarah's Tesla" else "Not ours"
            classifier.revealCrop(file, hasText("Model thinks: $guess", substring = true))
        }
        awaitNode(classifier.categoryChip("Sarah's Tesla"), "a crop's \"Sarah's Tesla\" chip")
        awaitNode(classifier.categoryChip("Not ours"), "a crop's \"Not ours\" chip")

        classifier.back()
        SettingsRobot(this).awaitPage()
    }

    @Test
    fun filingCropsMovesThemIntoTheDatasetAndOffTheQueue() = runAppJourney {
        val queued = state.edit { classifiers.single().queue.toList() }
        val classifier = ClassifierRobot(this)
        signIn.signInAs()
        classifier.open()
        classifier.awaitWaiting(2)

        classifier.fileFirstCropUnder("Sarah's Tesla")

        val first = classifier.awaitFiledUnder("sarahs_tesla")
        val firstFile = ClassifierRobot.trainingFileOf(first)
        assertTrue(firstFile in queued, "a queued crop was filed: $first")
        classifier.awaitWaiting(1)
        classifier.awaitCategory("Sarah's Tesla", images = 3)
        assertTrue(state.edit { firstFile !in classifiers.single().queue && firstFile in classifiers.single().categories.getValue("sarahs_tesla") }, "the server moved it")

        classifier.fileOnlyCropUnder("Not ours")

        val second = classifier.awaitFiledUnder("none")
        assertEquals(queued.toSet(), setOf(firstFile, ClassifierRobot.trainingFileOf(second)), "the other crop went second")
        classifier.awaitWaiting(0)
        classifier.awaitCategory("Not ours", images = 2)
        assertTrue(state.edit { classifiers.single().queue.isEmpty() }, "nothing left queued on the server")
    }

    @Test
    fun discardingACropDeletesItWithoutFilingIt() = runAppJourney {
        val queued = state.edit { classifiers.single().queue.toList() }
        val classifier = ClassifierRobot(this)
        signIn.signInAs()
        classifier.open()
        classifier.awaitWaiting(2)

        classifier.discardFirstCrop()

        val request = server.awaitRequest(description = "a queued crop deleted") {
            it.method == "POST" && it.path == "/api/classification/${ClassifierRobot.MODEL}/train/delete"
        }
        val ids = SettingsRobot.jsonBodyOf(request)?.get("ids")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertEquals(1, ids.size, "one crop: $request")
        assertTrue(ids.single() in queued, "a queued one: $request")
        classifier.awaitWaiting(1)
        classifier.awaitCategory("Sarah's Tesla", images = 2)
        classifier.awaitCategory("Not ours", images = 1)
        assertTrue(state.edit { ids.single() !in classifiers.single().queue }, "gone from the server's queue")
        assertFalse(server.received { it.path.endsWith("/dataset/categorize") }, "nothing was filed: ${server.requests}")
    }

    @Test
    fun trainingAsksTheServerToRetrainAndReportsWhenItHas() = runAppJourney {
        val classifier = ClassifierRobot(this)
        signIn.signInAs()
        classifier.open()
        awaitText("Never trained")

        classifier.train()

        server.awaitRequest(description = "a training request") { it.method == "POST" && it.path == "/api/classification/${ClassifierRobot.MODEL}/train" }
        // The screen polls the dataset every few seconds until the server says it has trained.
        awaitText("Trained on 3 images. Frigate is using the new model now.", timeout = 45.seconds)
        awaitText("Up to date")
        assertEquals(1, state.edit { classifiers.single().trainings }, "trained once")
    }

    @Test
    fun aNewCategoryIsCreatedOnTheServerAndOfferedForTheCrops() = runAppJourney {
        val classifier = ClassifierRobot(this)
        signIn.signInAs()
        classifier.open()
        classifier.awaitLoaded()

        classifier.addCategory("Grandma's Van")

        // The apostrophe can't be in a Frigate key; the chip puts it back.
        server.awaitRequest(description = "the category created") {
            it.method == "POST" && it.path == "/api/classification/${ClassifierRobot.MODEL}/dataset/grandmas_van/create"
        }
        awaitText("Added category grandmas_van")
        classifier.awaitCategory("Grandma's Van", images = 0)
        awaitNode(classifier.categoryChip("Grandma's Van"), "the new category's chip on a crop")
        assertTrue(state.edit { "grandmas_van" in classifiers.single().categories }, "the server has it")
    }

    @Test
    fun aViewerAccountIsToldTheClassifierCouldNotBeLoaded() = runAppJourney {
        val classifier = ClassifierRobot(this)
        signIn.signInAs(FakeFrigateState.VIEWER)
        classifier.open()

        // The model list comes from the config a viewer can read; its dataset is admin-only.
        awaitText("Couldn't load the classifier: Couldn't load dataset: 401", substring = true)
        awaitNode(hasText("Retry") and hasClickAction(), "the Retry button")
        assertFalse(exists(hasText("Categories")), "no dataset behind the error")
    }
}
