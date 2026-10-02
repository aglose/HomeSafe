package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentPresentation
import com.meticulouscreations.homesafe.domain.model.VisitKind
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.MomentsFeed
import com.meticulouscreations.homesafe.viewmodel.DownloadUiState
import com.meticulouscreations.homesafe.viewmodel.MomentClip
import com.meticulouscreations.homesafe.viewmodel.MomentGroup
import com.meticulouscreations.homesafe.viewmodel.MomentItem
import com.meticulouscreations.homesafe.viewmodel.MomentsUiState
import com.meticulouscreations.homesafe.viewmodel.NotAPersonUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.moments_not_a_person_mark_failed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Not a person": offered on a moment whose person nobody named (and on a visit's clip of one),
 * never on a named face; and the bar that says it was done and offers to take it back.
 */
@OptIn(ExperimentalTestApi::class)
class NotAPersonUiTest {

    private val start = 1_790_000_000.0

    private fun person(id: String, subLabel: String? = null) = MomentEvent(
        id = id,
        cameraName = "amcrest_1",
        label = "person",
        subLabel = subLabel,
        startEpochSeconds = start,
        endEpochSeconds = start + 2,
        topScore = 0.8,
        hasClip = true,
        hasSnapshot = false,
    )

    private fun presentation(title: String, clipCountLabel: String? = null) = MomentPresentation(
        title = title.asUiText(),
        timeLabel = "4:23 PM".asUiText(),
        durationLabel = "0:02",
        dateGroup = "Today".asUiText(),
        dateSubLabel = "Sep 29".asUiText(),
        badgeLabel = "person".asUiText(),
        locationLabel = "Front Door".asUiText(),
        sightingsLabel = null,
        clipCountLabel = clipCountLabel?.asUiText(),
    )

    private fun feed(
        items: List<MomentItem>,
        notAPersonState: NotAPersonUiState = NotAPersonUiState(),
        onNotAPerson: (MomentEvent) -> Unit = {},
        onUndo: () -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                MomentsFeed(
                    state = MomentsUiState(groups = listOf(MomentGroup("Today".asUiText(), "Sep 29".asUiText(), items))),
                    downloadState = DownloadUiState(),
                    onSelectCategory = {},
                    onUnfamiliarOnlyChange = {},
                    onSelectCamera = {},
                    onShowDay = {},
                    onLoadOlder = {},
                    onCardClick = {},
                    onClipBuffering = {},
                    onClipError = {},
                    onDownloadClick = {},
                    onFullScreenClick = {},
                    notAPersonState = notAPersonState,
                    onNotAPerson = onNotAPerson,
                    onUndoNotAPerson = onUndo,
                )
            }
        }
        block()
    }

    @Test
    fun anUnnamedPersonsCardOffersItAndAKnownFaceDoesNot() {
        val tapped = mutableListOf<String>()
        feed(
            listOf(
                MomentItem(person("phantom"), presentation("Person detected"), thumbnailUrl = null, canMarkNotPerson = true),
                MomentItem(person("andrew", "andrew"), presentation("Andrew at the front door"), thumbnailUrl = null, key = "andrew"),
            ),
            onNotAPerson = { tapped += it.id },
        ) {
            onAllNodesWithText("Not a person").assertCountEquals(1)
            onNodeWithText("Not a person").performClick()
        }
        assertEquals(listOf("phantom"), tapped)
    }

    @Test
    fun aVisitsClipCanBeMarkedFromItsRow() {
        val tapped = mutableListOf<String>()
        val clips = listOf(
            MomentClip(person("c0"), "4:23 PM".asUiText(), "Person detected".asUiText(), "0:17", canMarkNotPerson = true),
            MomentClip(person("c1"), "4:25 PM".asUiText(), "Person detected".asUiText(), "0:02", canMarkNotPerson = true),
        )
        val visit = MomentItem(clips.first().event, presentation("Person detected", "2 clips"), null, key = "c0", kind = VisitKind.VISIT, clips = clips)
        feed(listOf(visit), onNotAPerson = { tapped += it.id }) {
            onNodeWithText("2 clips").performClick()
            mainClock.advanceTimeBy(1_000)
            onAllNodesWithText("Not a person").assertCountEquals(0)
            // The rows' own glyphs; the card's pill is only for a visit whose lead can be marked.
            val glyphs = onAllNodesWithContentDescription("Not a person")
            glyphs.assertCountEquals(2)
            glyphs[1].assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("c1"), tapped)
    }

    @Test
    fun theBarSaysItWasDoneAndOffersUndo() {
        var undone = 0
        feed(listOf(), notAPersonState = NotAPersonUiState(marked = person("phantom")), onUndo = { undone++ }) {
            onNodeWithText("Marked not a person", substring = true).assertIsDisplayed()
            onNodeWithText("Undo").performClick()
        }
        assertEquals(1, undone)
    }

    @Test
    fun theBarSaysWhyAMarkFailed() = feed(listOf(), notAPersonState = NotAPersonUiState(error = UiText.of(Res.string.moments_not_a_person_mark_failed, "Relay answered 502"))) {
        onNodeWithText("Couldn't mark it: Relay answered 502").assertIsDisplayed()
    }
}
