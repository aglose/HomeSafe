package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.meticulouscreations.homesafe.ui.screens.DETECTION_ZONES_CANVAS_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.HOME_FEED_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.HOME_STATUS_TEST_TAG
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Home tab's camera list: the summary at the top, the "In view now" strip, and a card per
 * camera. The list is lazy and a card is most of a screen tall, so a card further down isn't in
 * the tree at all until the list is scrolled to it — see [scrollToCamera].
 */
@OptIn(ExperimentalTestApi::class)
internal class HomeRobot(private val journey: AppJourney) {

    private val list: SemanticsMatcher = hasTestTag(HOME_FEED_TEST_TAG)

    fun awaitList() {
        journey.awaitTag(HOME_FEED_TEST_TAG)
    }

    /**
     * Brings [cameraName]'s card (the list keys each card by the camera's Frigate name) to the
     * top of the list, where a tap on it lands clear of the bars — a jump by key, which needs no
     * animation to finish.
     */
    fun scrollToCamera(cameraName: String) {
        awaitKey(cameraName)
        journey.ui.onNode(list).performScrollToKey(cameraName)
        journey.settle()
    }

    /** Waits for [cameraName]'s card to read [displayName], scrolling the list to it if it isn't composed. */
    fun awaitCard(cameraName: String, displayName: String) {
        awaitKey(cameraName)
        journey.revealInList(list, cameraName, card(displayName), "the $displayName card")
    }

    /** Waits for [displayName]'s card to carry [badge] ("Disabled", "Connecting", "Live"). */
    fun awaitBadge(cameraName: String, displayName: String, badge: String) {
        awaitKey(cameraName)
        journey.revealInList(list, cameraName, card(displayName) and hasText(badge), "the $displayName card saying \"$badge\"")
    }

    /** Taps [displayName]'s card and waits for its camera screen. */
    fun openCamera(cameraName: String, displayName: String) {
        scrollToCamera(cameraName)
        journey.tap(card(displayName), "the $displayName card")
        CameraRobot(journey).awaitOpen(displayName)
    }

    /** Waits for the summary's text — its headline or its details line — to include [text]. */
    fun awaitSummary(text: String, substring: Boolean = false) {
        journey.awaitNode(hasTestTag(HOME_STATUS_TEST_TAG) and hasText(text, substring = substring), "the summary saying \"$text\"")
    }

    fun tapSummary() = journey.tap(hasTestTag(HOME_STATUS_TEST_TAG), "the summary")

    private fun card(displayName: String): SemanticsMatcher = hasText(displayName) and hasClickAction()

    /**
     * Waits for the list to hold an item keyed [cameraName]. Until the camera cache answers it
     * holds only the loading skeleton, and a jump to a key it doesn't have throws rather than
     * failing a wait.
     */
    private fun awaitKey(cameraName: String) {
        journey.awaitUntil("the camera list to have a card for $cameraName") {
            journey.ui.onNode(list).fetchSemanticsNode().config[SemanticsProperties.IndexForKey](cameraName) >= 0
        }
    }
}

/** One camera's own screen: its header, player, quick actions, timeline and recent activity. */
@OptIn(ExperimentalTestApi::class)
internal class CameraRobot(private val journey: AppJourney) {

    /** Waits for [displayName]'s screen to have replaced whatever was up before it. */
    fun awaitOpen(displayName: String) {
        journey.awaitGone(hasTestTag(HOME_FEED_TEST_TAG), "the camera list")
        journey.awaitGone(hasTestTag(DETECTION_ZONES_CANVAS_TEST_TAG), "the zone editor")
        journey.awaitNode(hasText(RECENT_ACTIVITY), "the Recent Activity heading")
        journey.awaitNode(hasText(displayName), "the header naming $displayName")
    }

    fun back() = journey.tap(hasContentDescription("Back"), "the Back arrow")

    /** Brings the node matching [matcher] into view — the page scrolls, and a tap must land on screen. */
    fun scrollTo(matcher: SemanticsMatcher, description: String) {
        journey.awaitNode(matcher, description).performScrollTo()
        // The scroll is animated, and the clock only moves when it is told to.
        journey.settle()
    }

    /**
     * Scrolls to the control matching [matcher] and presses it. The shell's bottom nav floats over
     * the foot of this page, and a control scrolled just into view can sit under it, where a
     * touch would land on the nav instead; so the press goes through the control's own click
     * action, after the same wait for exactly one match that a tap makes.
     */
    fun press(matcher: SemanticsMatcher, description: String) {
        scrollTo(matcher, description)
        journey.awaitSingle(matcher, description).performSemanticsAction(SemanticsActions.OnClick)
        journey.settle(PRESS_SETTLE)
    }

    fun openDetectionZones() {
        journey.tap(hasContentDescription("More options"), "the overflow menu")
        journey.tapText("Detection zones")
    }

    companion object {
        const val RECENT_ACTIVITY = "Recent Activity"
    }
}

/** The detection-zones editor: the layer chips, the frame to draw on, the toolbar and the save bar. */
@OptIn(ExperimentalTestApi::class)
internal class ZonesRobot(private val journey: AppJourney) {

    /** Waits for the editor to be up on [displayName] with the camera's config loaded (the canvas only draws then). */
    fun awaitLoaded(displayName: String) {
        journey.awaitNode(hasText("Detection zones"), "the editor's title")
        journey.awaitNode(hasText(displayName), "the camera's name under the title")
        journey.awaitTag(DETECTION_ZONES_CANVAS_TEST_TAG)
    }

    /** A layer chip, whose label carries its shape count once there is one: "Zones · 2". */
    fun awaitLayer(label: String) {
        journey.awaitNode(hasText(label) and hasClickAction(), "the \"$label\" layer chip")
    }

    fun openLayer(label: String) = journey.tap(hasText(label) and hasClickAction(), "the \"$label\" layer chip")

    /**
     * Draws a new shape on the current layer through the toolbar — "Add [noun]", a tap on the
     * frame at each of [corners] (fractions of its width and height), then Done.
     */
    fun draw(noun: String, vararg corners: Pair<Float, Float>) {
        press(hasText("Add $noun") and isEnabled(), "the Add $noun button")
        corners.forEach { (x, y) ->
            journey.awaitTag(DETECTION_ZONES_CANVAS_TEST_TAG).performTouchInput { click(percentOffset(x, y)) }
            journey.settle(CORNER_SETTLE)
        }
        press(hasText("Done") and isEnabled(), "the enabled Done button")
    }

    /** Types [name] over the selected zone's name — a new zone opens with its placeholder selected. */
    fun nameSelectedZone(name: String) {
        journey.awaitSingle(hasSetTextAction(), "the zone's Name field").performTextReplacement(name)
        journey.settle()
    }

    /** Taps Save. The header and the save bar each have one; either does the same. */
    fun save() {
        journey.awaitNode(hasText("Save") and hasClickAction() and isEnabled(), "an enabled Save button")
            .performSemanticsAction(SemanticsActions.OnClick)
        journey.settle(PRESS_SETTLE)
    }

    fun back() = journey.tap(hasContentDescription("Back"), "the Back arrow")

    /**
     * The toolbar sits under the frame, low enough on a short screen for the floating bottom nav
     * to cover it, so its buttons are pressed through their click action (see [CameraRobot.press]).
     */
    private fun press(matcher: SemanticsMatcher, description: String) {
        journey.awaitSingle(matcher, description).performSemanticsAction(SemanticsActions.OnClick)
        journey.settle(PRESS_SETTLE)
    }

    private companion object {
        val CORNER_SETTLE = 200.milliseconds
    }
}

/** What a press is given to start its effects, like [AppJourney.tap]'s own settle after a click. */
private val PRESS_SETTLE = 500.milliseconds
