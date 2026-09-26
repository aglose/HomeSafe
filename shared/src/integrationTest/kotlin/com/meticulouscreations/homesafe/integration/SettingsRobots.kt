package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.RecordedRequest
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Settings tab's own page and the Server page one tap below it.
 *
 * Clicks here go through the node's click action ([press]) rather than a touch. The page scrolls
 * under the shell's floating top bar and bottom nav, so a row scrolled into view can end up
 * beneath the nav, and a touch there would change tabs. Pressing still waits for exactly one
 * match and lets the result settle, as [AppJourney.tap] does.
 *
 * Nothing on the page has a test tag, and each camera repeats the same "Object detection" and
 * "Motion detection" rows, so a camera's switches are found by where they are: the first two
 * switches at or below that camera's name. Positions come from [SemanticsNode.positionInRoot],
 * which isn't clipped, so this works for rows below the fold too.
 */
@OptIn(ExperimentalTestApi::class)
internal class SettingsRobot(private val journey: AppJourney) {

    /** One `PUT /api/config/set` that flips one camera switch: the camera, the section (`detect` or `motion`), and which way. */
    data class SwitchWrite(val camera: String, val section: String, val enabled: Boolean)

    /** Opens the Settings tab and waits for the page's last row, the one into the Server page. */
    fun open() {
        journey.shell.openTab(TopLevelRoute.Settings)
        awaitPage()
    }

    fun awaitPage() {
        journey.awaitNode(SERVER_ROW, "the Server row")
    }

    /** Waits for the Server row's one-line summary to read exactly [summary]. */
    fun awaitSummary(summary: String) {
        journey.awaitNode(SERVER_ROW and hasText(summary), "the Server row reading \"$summary\"")
    }

    fun facesRow(): SemanticsMatcher = hasText(FACES_ROW_TITLE) and hasClickAction()

    fun classifierRow(displayName: String): SemanticsMatcher = hasText(displayName) and hasClickAction()

    fun openServer() {
        press(SERVER_ROW, "the Server row")
        journey.awaitText(SERVER_PAGE_SUBTITLE)
    }

    /** Taps a nested page's Back arrow and waits for the page subtitled [subtitle] to be gone. */
    fun back(subtitle: String) {
        press(hasContentDescription("Back") and hasClickAction(), "the Back arrow")
        journey.awaitGone(hasText(subtitle), "the page subtitled \"$subtitle\"")
    }

    fun backFromServer() {
        back(SERVER_PAGE_SUBTITLE)
        awaitPage()
    }

    /** The Refresh button in a nested page's header. */
    fun refresh() = press(hasContentDescription("Refresh") and hasClickAction(), "the Refresh button")

    /** Clicks the one node matching [matcher] through its click action, then lets the click's effects start. */
    fun press(matcher: SemanticsMatcher, description: String = matcher.description) {
        journey.awaitSingle(matcher, description).performSemanticsAction(SemanticsActions.OnClick)
        journey.settle(PRESS_SETTLE)
        journey.snapshot("after press on $description")
    }

    /** As [press], for a control repeated on every card of a list: clicks the first (topmost) of them. */
    fun pressFirst(matcher: SemanticsMatcher, description: String = matcher.description) {
        journey.awaitUntil("at least one node that is $description") { journey.count(matcher) > 0 }
        journey.ui.onAllNodes(matcher)[0].performSemanticsAction(SemanticsActions.OnClick)
        journey.settle(PRESS_SETTLE)
        journey.snapshot("after press on the first $description")
    }

    /** Replaces the text in the page's one text field. */
    fun type(text: String) {
        journey.awaitSingle(hasSetTextAction(), "the text field").performTextReplacement(text)
        journey.settle()
    }

    // ---- Label / value read-outs -----------------------------------------------------------------

    /**
     * The text straight under the label [label] (the Server page's read-outs are an upper-case
     * label over a value), or null while [label] isn't on screen exactly once.
     */
    fun valueUnder(label: String): String? {
        val labelNode = journey.ui.onAllNodes(hasText(label)).fetchSemanticsNodes().singleOrNull() ?: return null
        return journey.ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .filter { it.id != labelNode.id && top(it) >= bottom(labelNode) - 1f && abs(it.positionInRoot.x - labelNode.positionInRoot.x) < 1f }
            .minByOrNull { top(it) }
            ?.let { textOf(it) }
    }

    fun awaitValueUnder(label: String, expected: String) {
        journey.awaitUntil("\"$expected\" under \"$label\"") { valueUnder(label) == expected }
    }

    // ---- Camera switches -------------------------------------------------------------------------

    fun awaitDetection(camera: String, on: Boolean, enabled: Boolean) = awaitSwitch(camera, DETECTION, "$camera's object detection switch", on, enabled)

    fun awaitMotion(camera: String, on: Boolean, enabled: Boolean) = awaitSwitch(camera, MOTION, "$camera's motion detection switch", on, enabled)

    fun flipDetection(camera: String) = flip(camera, DETECTION, "$camera's object detection switch")

    fun flipMotion(camera: String) = flip(camera, MOTION, "$camera's motion detection switch")

    /** The switch in the row titled [title] (the first switch that doesn't end above the title). */
    fun awaitSwitchInRow(title: String, on: Boolean, enabled: Boolean) = awaitSwitch(title, 0, "the \"$title\" switch", on, enabled)

    /** Every camera-switch write the server has received so far, oldest first. */
    fun switchWrites(): List<SwitchWrite> = journey.server.requests.mapNotNull { switchWriteOf(it) }

    /** Waits (in real time) for the server to be told to turn [camera]'s [section] ([enabled]). */
    fun awaitSwitchWrite(camera: String, section: String, enabled: Boolean): RecordedRequest {
        val expected = SwitchWrite(camera, section, enabled)
        return journey.server.awaitRequest(description = "a config write of $expected") { switchWriteOf(it) == expected }
    }

    private fun awaitSwitch(anchor: String, index: Int, what: String, on: Boolean, enabled: Boolean) {
        val state = if (on) isOn() else isOff()
        val able = if (enabled) isEnabled() else isNotEnabled()
        journey.awaitUntil("$what to be ${if (on) "on" else "off"} and ${if (enabled) "enabled" else "locked"}") {
            val node = switchesFrom(anchor).getOrNull(index)
            node != null && state.matches(node) && able.matches(node)
        }
    }

    private fun flip(anchor: String, index: Int, what: String) {
        var id = -1
        journey.awaitUntil("$what to be there and enabled") {
            val node = switchesFrom(anchor).getOrNull(index)
            if (node != null && isEnabled().matches(node)) id = node.id
            id != -1
        }
        journey.ui.onNode(SemanticsMatcher(what) { it.id == id }).performSemanticsAction(SemanticsActions.OnClick)
        journey.settle(PRESS_SETTLE)
        journey.snapshot("after flipping $what")
    }

    /**
     * The switches from [anchor]'s row down, top first — empty while [anchor] isn't on screen
     * exactly once (say, mid tab transition). A switch sits vertically centred in its row, so the
     * test is "doesn't end above the anchor starts", not "starts below it".
     */
    private fun switchesFrom(anchor: String): List<SemanticsNode> {
        val label = journey.ui.onAllNodes(hasText(anchor)).fetchSemanticsNodes().singleOrNull() ?: return emptyList()
        return journey.ui.onAllNodes(isToggleable()).fetchSemanticsNodes()
            .filter { bottom(it) > top(label) }
            .sortedBy { top(it) }
    }

    private fun top(node: SemanticsNode): Float = node.positionInRoot.y

    private fun bottom(node: SemanticsNode): Float = node.positionInRoot.y + node.size.height

    private fun textOf(node: SemanticsNode): String? = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun switchWriteOf(request: RecordedRequest): SwitchWrite? {
        if (request.method != "PUT" || request.path != "/api/config/set") return null
        return runCatching {
            val cameras = Json.parseToJsonElement(request.body).jsonObject.getValue("config_data").jsonObject.getValue("cameras").jsonObject
            val (camera, sections) = cameras.entries.single()
            val (section, value) = sections.jsonObject.entries.single()
            SwitchWrite(camera, section, checkNotNull(value.jsonObject.getValue("enabled").jsonPrimitive.booleanOrNull))
        }.getOrNull()
    }

    companion object {
        const val SERVER_ROW_TITLE = "Server"
        const val SERVER_PAGE_SUBTITLE = "What Frigate is and is doing"
        const val FACES_ROW_TITLE = "Faces"
        const val VIEWER_CAPTION = "Signed in as a viewer: the switches below are read-only. An admin account can change them."
        const val NOTIFICATIONS_UNAVAILABLE = "Not available on this platform. Use the Android or iOS app for alerts."

        /** The last row on the Settings page: the way into the Server page, with its one-line summary. */
        val SERVER_ROW: SemanticsMatcher = hasText(SERVER_ROW_TITLE) and hasClickAction()

        private const val DETECTION = 0
        private const val MOTION = 1
        private val PRESS_SETTLE = 500.milliseconds

        /** A request body as a JSON object, or null when it isn't one. */
        fun jsonBodyOf(request: RecordedRequest): JsonObject? = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
    }
}

/** Frigate's face library, opened from the Settings page's Faces row. */
@OptIn(ExperimentalTestApi::class)
internal class FaceLibraryRobot(private val journey: AppJourney) {
    private val settings = SettingsRobot(journey)

    /** Opens the Settings tab, then the face library from its row, and waits for the library to load. */
    fun open() {
        settings.open()
        settings.press(settings.facesRow(), "the Faces row")
        journey.awaitText(SUBTITLE)
    }

    fun awaitLoaded() {
        journey.awaitText("People")
    }

    /** Waits for [displayName]'s chip in the People card, "Alice · 2". */
    fun awaitPerson(displayName: String, faces: Int) {
        journey.awaitText("$displayName · $faces")
    }

    fun awaitWaiting(count: Int) {
        journey.awaitText(
            when (count) {
                0 -> "No faces waiting"
                1 -> "1 face waiting for a name"
                else -> "$count faces waiting for a name"
            },
        )
    }

    /** A person's chip on the waiting face; tapping it files the face under them. */
    fun personChip(displayName: String): SemanticsMatcher = hasText(displayName) and hasClickAction()

    fun fileWaitingFaceUnder(displayName: String) {
        revealWaitingFace(personChip(displayName))
        settings.press(personChip(displayName), "the \"$displayName\" chip on the waiting face")
    }

    fun discardWaitingFace() {
        val discard = hasText("Not one of us") and hasClickAction()
        revealWaitingFace(discard)
        settings.press(discard, "the \"Not one of us\" button")
    }

    /**
     * Brings the (first) waiting face's card into view and waits for [item] on it. The list is
     * lazy and keyed by file name, and the file is the server's, so it is read from there.
     */
    fun revealWaitingFace(item: SemanticsMatcher) {
        val file = journey.state.edit { faces[FakeFrigateState.TRAIN_FOLDER]?.firstOrNull() } ?: return
        journey.revealInList(hasScrollToIndexAction(), file, item)
    }

    fun addPerson(name: String) {
        settings.type(name)
        settings.press(hasText("Add") and hasClickAction() and isEnabled(), "the enabled Add button")
    }

    fun back() = settings.back(SUBTITLE)

    companion object {
        const val SUBTITLE = "Teach Frigate who's who"
    }
}

/** A custom classifier's labelling screen, opened from its row on the Settings page. */
@OptIn(ExperimentalTestApi::class)
internal class ClassifierRobot(private val journey: AppJourney) {
    private val settings = SettingsRobot(journey)

    /** Opens the Settings tab, then [displayName]'s labelling screen from its row. */
    fun open(displayName: String = HOUSEHOLD_CARS) {
        settings.open()
        settings.press(settings.classifierRow(displayName), "the \"$displayName\" row")
        journey.awaitText(SUBTITLE)
    }

    fun awaitLoaded() {
        journey.awaitText("Categories")
    }

    /** Waits for [displayName]'s chip in the Categories card, "Sarah's Tesla · 2". */
    fun awaitCategory(displayName: String, images: Int) {
        journey.awaitText("$displayName · $images")
    }

    fun awaitWaiting(count: Int) {
        journey.awaitText(if (count == 0) "Nothing waiting to be labelled" else "$count waiting to be labelled")
    }

    /**
     * Brings the queued crop [fileName]'s card into view and waits for [item] on it. The list is
     * lazy and keyed by file name, and on a 768-high window the second crop starts at the fold.
     */
    fun revealCrop(fileName: String, item: SemanticsMatcher) {
        journey.revealInList(hasScrollToIndexAction(), fileName, item)
    }

    /** A category's chip on a queued crop (every crop card has one per category). */
    fun categoryChip(displayName: String): SemanticsMatcher = hasText(displayName) and hasClickAction()

    /** Files the topmost queued crop under [displayName]. */
    fun fileFirstCropUnder(displayName: String) = settings.pressFirst(categoryChip(displayName), "the \"$displayName\" chip on a queued crop")

    /** Files the one queued crop left under [displayName]. */
    fun fileOnlyCropUnder(displayName: String) = settings.press(categoryChip(displayName), "the \"$displayName\" chip on the last queued crop")

    fun discardFirstCrop() = settings.pressFirst(hasText("Discard this crop") and hasClickAction(), "a \"Discard this crop\" button")

    fun train() = settings.press(hasText("Train") and hasClickAction(), "the Train button")

    fun addCategory(name: String) {
        settings.type(name)
        settings.press(hasText("Add") and hasClickAction() and isEnabled(), "the enabled Add button")
    }

    fun back() = settings.back(SUBTITLE)

    /** Waits (in real time) for a crop to be filed under [category] (Frigate's key, e.g. `none`) and returns the request. */
    fun awaitFiledUnder(category: String): RecordedRequest =
        journey.server.awaitRequest(description = "a crop filed under $category") {
            it.method == "POST" &&
                it.path == "/api/classification/$MODEL/dataset/categorize" &&
                SettingsRobot.jsonBodyOf(it)?.get("category")?.jsonPrimitive?.content == category
        }

    companion object {
        const val SUBTITLE = "Teach Frigate what it's looking at"

        /** How the Settings row and the screen's header name the fake's `household_cars` model. */
        const val HOUSEHOLD_CARS = "Household Cars"
        const val MODEL = "household_cars"

        /** The crop a categorize request filed. */
        fun trainingFileOf(request: RecordedRequest): String =
            checkNotNull(SettingsRobot.jsonBodyOf(request)?.get("training_file")?.jsonPrimitive?.content) { "no training_file in $request" }
    }
}
