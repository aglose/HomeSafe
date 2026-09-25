package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.ConfidentCropsRow
import com.meticulouscreations.homesafe.ui.screens.CropCard
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two pieces of the classifier labelling screen that the camera screen's live section shares
 * with it: [CropCard], one crop with the model's guess and a chip per category, and
 * [ConfidentCropsRow], the fold the model's certain crops are tucked into. Both take plain data
 * and callbacks, so they're driven here with no view model behind them.
 *
 * What matters is that each crop says what the model thinks in words a person reads ("Not ours",
 * "Sarah's Tesla", not `none` or `sarahs_tesla`), that each chip files the crop under that
 * category's key, and that a crop already on its way to the server takes no second answer.
 *
 * No crop has an image URL, so nothing is fetched, and none has a capture time, whose clock label
 * would depend on the host's time zone. `mainClock.autoAdvance = false` throughout: a busy crop or
 * fold shows a spinner that turns forever.
 */
@OptIn(ExperimentalTestApi::class)
class ClassifierCropsUiTest {

    private val categories = listOf(ClassifierDataset.NONE_CATEGORY, "sarahs_tesla", "andrews_tesla")

    private fun aCrop(guessedCategory: String? = "sarahs_tesla", guessedScore: Double? = 0.75) = UnlabeledCrop(
        fileName = "1789000000.5-abc123-1789000004.0-sarahs_tesla-0.75.webp",
        eventId = null,
        capturedEpochSeconds = null,
        guessedCategory = guessedCategory,
        guessedScore = guessedScore,
    )

    private fun runCard(
        crop: UnlabeledCrop = aCrop(),
        busy: Boolean = false,
        decided: String? = null,
        onLabel: (String) -> Unit = {},
        onDiscard: (() -> Unit)? = {},
        knownAs: String? = null,
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CropCard(
                    crop = crop,
                    imageUrl = null,
                    categories = categories,
                    busy = busy,
                    decided = decided,
                    onLabel = onLabel,
                    onDiscard = onDiscard,
                    knownAs = knownAs,
                )
            }
        }
        block()
    }

    private fun runFold(
        expanded: Boolean = false,
        busy: Boolean = false,
        onToggle: () -> Unit = {},
        onClear: (() -> Unit)? = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                ConfidentCropsRow(count = 12, expanded = expanded, busy = busy, onToggle = onToggle, onClear = onClear)
            }
        }
        block()
    }

    @Test
    fun theFoldSaysHowManyItHoldsAndShowsThem() {
        var toggled = 0
        runFold(onToggle = { toggled++ }) {
            onNodeWithText("12 more the model is sure about").assertIsDisplayed()
            onNodeWithText("Show").performClick()
            assertEquals(1, toggled)
        }
    }

    @Test
    fun anOpenFoldOffersToHideThemAgain() {
        var toggled = 0
        runFold(expanded = true, onToggle = { toggled++ }) {
            onAllNodesWithText("Show").assertCountEquals(0)
            onNodeWithText("Hide").performClick()
            assertEquals(1, toggled)
        }
    }

    @Test
    fun clearNamesHowManyItWillThrowAway() {
        var cleared = 0
        runFold(onClear = { cleared++ }) {
            onNodeWithText("Clear 12").performClick()
            assertEquals(1, cleared)
        }
    }

    @Test
    fun theLiveViewsFoldOnlyShowsAndHides() = runFold(onClear = null) {
        onNodeWithText("Show").assertIsDisplayed()
        onAllNodesWithText("Clear 12").assertCountEquals(0)
    }

    @Test
    fun aBusyFoldAnswersNeitherButtonAndSpinsInPlaceOfClear() {
        var toggled = 0
        runFold(busy = true, onToggle = { toggled++ }) {
            onNodeWithText("Show").assertIsNotEnabled().performClick()
            assertEquals(0, toggled)
            onAllNodesWithText("Clear 12").assertCountEquals(0)
        }
    }

    @Test
    fun aCropShowsTheModelsGuessAndHowSureItIs() = runCard {
        onNodeWithText("Model thinks: Sarah's Tesla (75%)").assertIsDisplayed()
    }

    @Test
    fun aGuessWithNoScoreIsNamedOnItsOwn() = runCard(crop = aCrop(guessedScore = null)) {
        onNodeWithText("Model thinks: Sarah's Tesla").assertIsDisplayed()
    }

    @Test
    fun aModelWithNoOpinionYetSaysSo() = runCard(crop = aCrop(guessedCategory = ClassifierDataset.UNKNOWN_GUESS, guessedScore = null)) {
        onNodeWithText("Model hasn't guessed").assertIsDisplayed()
    }

    @Test
    fun theReservedCategoryReadsAsNotOurs() = runCard(crop = aCrop(guessedCategory = ClassifierDataset.NONE_CATEGORY, guessedScore = 1.0)) {
        onNodeWithText("Model thinks: Not ours (100%)").assertIsDisplayed()
        onNodeWithText("Not ours").assertIsDisplayed()
    }

    @Test
    fun eachCategoryIsAChipThatFilesTheCropUnderItsKey() {
        var labelled: String? = null
        runCard(onLabel = { labelled = it }) {
            onNodeWithText("Not ours").assertIsDisplayed()
            onNodeWithText("Sarah's Tesla").assertIsDisplayed()
            onNodeWithText("Andrew's Tesla").performClick()
            assertEquals("andrews_tesla", labelled)
        }
    }

    @Test
    fun aCropOnItsWayToTheServerTakesNoSecondAnswer() {
        var labelled: String? = null
        runCard(busy = true, onLabel = { labelled = it }) {
            onNodeWithText("Andrew's Tesla").performClick()
            assertEquals(null, labelled)
            onNodeWithText("Discard this crop").assertIsNotEnabled()
        }
    }

    @Test
    fun discardThrowsTheCropAway() {
        var discarded = 0
        runCard(onDiscard = { discarded++ }) {
            onNodeWithText("Discard this crop").performClick()
            assertEquals(1, discarded)
        }
    }

    @Test
    fun theLiveViewsCropSaysWhatFrigateCallsItAndCannotBeDiscarded() = runCard(onDiscard = null, knownAs = "andrews_tesla") {
        onNodeWithText("Frigate calls it Andrew's Tesla").assertIsDisplayed()
        onAllNodesWithText("Discard this crop").assertCountEquals(0)
    }

    @Test
    fun aFiledCropSaysWhereItWentInPlaceOfTheChips() = runCard(decided = "sarahs_tesla") {
        onNodeWithText("Filed as Sarah's Tesla").assertIsDisplayed()
        onAllNodesWithText("Andrew's Tesla").assertCountEquals(0)
        onAllNodesWithText("Discard this crop").assertCountEquals(0)
    }
}
