package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToKey
import com.meticulouscreations.homesafe.fakefrigate.RecordedRequest
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.HOME_FEED_TEST_TAG
import kotlin.time.Duration

/**
 * The Moments tab: its filter row (a camera chip and a type chip, each opening a menu, and the
 * calendar chip), and the feed beneath it. Cards are found by their title, which is how the
 * feed reads — "Person in the porch", "Dog detected" — and, when a card may be below the fold,
 * scrolled to by its list key, which is the id of the Frigate event it leads with.
 */
@OptIn(ExperimentalTestApi::class)
internal class MomentsRobot(private val journey: AppJourney) {

    /** Switches to the tab and waits for its filter row, with Home's list gone from under it. */
    fun open() {
        journey.shell.openTab(TopLevelRoute.Moments)
        awaitFeed()
    }

    /** The tab is up: its filter row is drawn, and the Home list it replaced has finished fading out. */
    fun awaitFeed() {
        journey.awaitNode(CAMERA_CHIP, "the Moments camera filter chip")
        journey.awaitGone(hasTestTag(HOME_FEED_TEST_TAG), "the Home list, still fading out")
    }

    /**
     * Waits for a card titled [title]. With [key] (the lead event's id), a card that is not on
     * screen is scrolled to, so the wait holds however far down the feed it landed.
     */
    fun awaitMoment(title: String, key: String? = null, timeout: Duration = AppJourney.DEFAULT_WAIT) {
        journey.awaitUntil("a moment titled \"$title\"", timeout) {
            if (key != null && !journey.exists(hasText(title))) tryScrollTo(key)
            journey.exists(hasText(title))
        }
    }

    fun awaitNoMoment(title: String) = journey.awaitGone(hasText(title), "a moment titled \"$title\"")

    /** Waits until the feed holds an entry keyed [key] (a lead event's id) and scrolls it into view. */
    fun scrollTo(key: String) {
        journey.awaitUntil("an entry keyed \"$key\" in the feed") { tryScrollTo(key) }
        journey.settle()
    }

    /** Scrolls to the feed's last item — its "load older" button, spinner or full stop — which is what asks for the next page. */
    fun scrollToEnd() = scrollTo(FEED_END_KEY)

    fun pickCamera(displayName: String) {
        journey.tap(CAMERA_CHIP, "the camera filter chip")
        journey.tap(menuItem(displayName), "\"$displayName\" in the camera menu")
        awaitMenuClosed()
        journey.awaitNode(CAMERA_CHIP and hasText(displayName), "the camera chip reading \"$displayName\"")
    }

    fun pickType(label: String) {
        journey.tap(TYPE_CHIP, "the type filter chip")
        journey.tap(menuItem(label), "\"$label\" in the type menu")
        awaitMenuClosed()
        journey.awaitNode(TYPE_CHIP and hasText(label), "the type chip reading \"$label\"")
    }

    fun toggleUnfamiliarOnly() {
        journey.tap(TYPE_CHIP, "the type filter chip")
        journey.tap(menuItem(UNFAMILIAR_ONLY), "\"$UNFAMILIAR_ONLY\" in the type menu")
        awaitMenuClosed()
    }

    /** Taps a card's top — its title — which opens its clip beneath it. */
    fun openClip(title: String) = journey.tap(hasText(title), "the \"$title\" card")

    /** The open clip's way out to the camera's own screen; the button is only there while a clip is open. */
    fun playFullScreen() {
        journey.awaitNode(PLAY_FULL_SCREEN, "the open clip's full-screen button")
        // Let the player finish unfolding (and the feed scroll it into view) before tapping into it.
        journey.settle()
        journey.tap(PLAY_FULL_SCREEN, "the open clip's full-screen button")
    }

    /**
     * Waits for the feed's own ask of `/api/events` matching [predicate]. The feed never sends
     * `after` — the in-view strip and the detection alerts poll the same endpoint with it — so a
     * request without one is the feed's.
     */
    fun awaitFeedRequest(description: String, predicate: (RecordedRequest) -> Boolean): RecordedRequest =
        journey.server.awaitRequest(description = description) { isFeedRequest(it) && predicate(it) }

    private fun awaitMenuClosed() = journey.awaitGone(hasContentDescription(MENU_TICK), "the filter menu, closing")

    /** One scroll attempt; false (to be retried) while the list, or the key in it, isn't there yet. */
    private fun tryScrollTo(key: String): Boolean = try {
        journey.ui.onNode(FEED_LIST).performScrollToKey(key)
        true
    } catch (notYet: AssertionError) {
        false
    } catch (notYet: IllegalArgumentException) {
        false
    }

    companion object {
        const val EVENTS_PATH = "/api/events"
        const val UNFAMILIAR_ONLY = "Unfamiliar only"
        const val LOOK_FURTHER_BACK = "Look further back"
        const val EVERYTHING_LOADED = "That's everything the server still has."
        const val NOTHING_YET =
            "Nothing to show yet. Detections appear here when they happen in a zone set to watch for them, or when Frigate recognises who or what they are."
        const val UNREACHABLE = "Couldn't reach the server for detections."

        /** The list's last item, keyed so in `MomentsFeed`. */
        private const val FEED_END_KEY = "feed-end"

        /** The check a filter menu puts beside the option in force. */
        private const val MENU_TICK = "Selected"

        private val CAMERA_CHIP = hasClickLabel("Filter by camera")
        private val TYPE_CHIP = hasClickLabel("Filter by type")
        private val PLAY_FULL_SCREEN = hasContentDescription("Play full screen")

        /** The feed's LazyColumn: the tab's one lazy list (Home's, tagged, may still be fading out). */
        private val FEED_LIST = hasScrollToIndexAction() and !hasTestTag(HOME_FEED_TEST_TAG)

        /** An option in an open filter menu — clickable, with [label], and not the chip that opened it (which may read the same). */
        private fun menuItem(label: String): SemanticsMatcher = hasText(label) and hasClickAction() and !CAMERA_CHIP and !TYPE_CHIP

        /** The chips have no text of their own that says what they filter; their click label does. */
        private fun hasClickLabel(label: String): SemanticsMatcher =
            SemanticsMatcher("has click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

        fun isFeedRequest(request: RecordedRequest): Boolean =
            request.method == "GET" && request.path == EVENTS_PATH && "after" !in request.query
    }
}
