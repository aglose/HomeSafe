package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.HOME_FEED_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.HOME_STATUS_TEST_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Moments tab end to end: the real feed view model and repository reading `/api/events`
 * from the fake Frigate, placing each detection by the zones in `/api/config`, folding and
 * filtering it, and the way out of a card to the camera's own screen on Home.
 *
 * The household scenario reads, newest first: a person on the front-door porch, Sarah's Tesla
 * in the driveway, the dog in the back yard, and Alice at the front door. The feed titles them
 * the way it would a real server's — "Person in the porch", "Dog detected", "Alice detected" —
 * and leaves yesterday's unzoned front-door person out, as it does on a camera with zones.
 *
 * The fake serves every event with its best-frame box but no `path_data`, so the app reads each
 * car as having never moved and, true to form, leaves it out (see [aCarOnlyEverSeenStandingStillIsNotAMoment]).
 * Journeys that need the named car on screen give it no box, which the app takes as nothing to
 * judge its movement on (see [showSarahsTesla]).
 */
class MomentsJourneyTest {

    @Test
    fun openingMomentsListsTheServersDetectionsTitledByWhoOrWhatAndWhere() = runAppJourney(showSarahsTesla(FakeFrigateState.household())) {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()

        moments.awaitMoment(PERSON_ON_PORCH, key = state.eventId("front_door", "person", subLabel = null))
        moments.awaitMoment(SARAHS_TESLA, key = state.eventId("driveway", "car", subLabel = "sarahs_tesla"))
        moments.awaitMoment(DOG, key = state.eventId("back_yard", "dog", subLabel = null))
        moments.awaitMoment(ALICE, key = state.eventId("front_door", "person", subLabel = "Alice"))

        val first = moments.awaitFeedRequest("the feed's first page") { true }
        assertFalse("cameras" in first.query, "the unfiltered feed asks for every camera: $first")
        assertFalse("before" in first.query, "the live feed opens at now: $first")
        assertEquals(listOf("100"), first.query["limit"], "the feed reads a page at a time: $first")
    }

    @Test
    fun aCarOnlyEverSeenStandingStillIsNotAMoment() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()

        // Sarah's Tesla would sit between these two; with both composed, it would be too.
        moments.awaitMoment(PERSON_ON_PORCH)
        moments.awaitMoment(DOG, key = state.eventId("back_yard", "dog", subLabel = null))
        moments.awaitFeedRequest("the feed's first page") { true }
        settle()
        assertFalse(exists(hasText("Sarah's Tesla", substring = true)), "a parked car that never moved is left out of the feed")
    }

    @Test
    fun pickingACameraAsksTheServerForThatCameraAlone() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)

        moments.pickCamera("Back Yard")

        val narrowed = moments.awaitFeedRequest("the feed's page for the back yard") { it.query["cameras"] == listOf("back_yard") }
        assertFalse("before" in narrowed.query, "still the live feed: $narrowed")
        moments.awaitMoment(DOG)
        moments.awaitNoMoment(PERSON_ON_PORCH)
        moments.awaitNoMoment(ALICE)
    }

    @Test
    fun pickingAllCamerasAgainAsksForEveryCamera() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)
        moments.pickCamera("Front Door")
        moments.awaitFeedRequest("the feed's page for the front door") { it.query["cameras"] == listOf("front_door") }
        moments.awaitNoMoment(DOG)

        server.clearRequests()
        moments.pickCamera("All cameras")

        moments.awaitFeedRequest("the feed's page for every camera") { "cameras" !in it.query }
        moments.awaitMoment(DOG)
        moments.awaitMoment(PERSON_ON_PORCH)
    }

    @Test
    fun theTypeFilterNarrowsTheFeedToOneKindOfDetection() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)

        moments.pickType("Animals")
        moments.awaitMoment(DOG)
        moments.awaitNoMoment(PERSON_ON_PORCH)
        moments.awaitNoMoment(ALICE)

        moments.pickType("People")
        moments.awaitMoment(PERSON_ON_PORCH)
        moments.awaitMoment(ALICE, key = state.eventId("front_door", "person", subLabel = "Alice"))
        moments.awaitNoMoment(DOG)
    }

    @Test
    fun unfamiliarOnlyLeavesOutWhoFrigateRecognised() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(ALICE, key = state.eventId("front_door", "person", subLabel = "Alice"))

        moments.pickType("People")
        moments.toggleUnfamiliarOnly()

        awaitNode(hasText("Unfamiliar people"), "the type chip naming both filters")
        moments.awaitMoment(PERSON_ON_PORCH)
        moments.awaitNoMoment(ALICE)
    }

    @Test
    fun aFilterThatMatchesNothingSaysWhatItWasLookingFor() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)

        moments.pickCamera("Front Door")
        moments.pickType("Animals")

        awaitText(
            "No animals on Front Door to show. Detections appear here when they happen in a zone set to watch for them, or when they're recognised.",
        )
        assertFalse(exists(hasText(MomentsRobot.LOOK_FURTHER_BACK)), "the server had less than a page: nothing further back to offer")
    }

    @Test
    fun aQuietServerShowsTheEmptyFeed() = runAppJourney(FakeFrigateState.quiet()) {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()

        moments.awaitFeedRequest("the feed's first page") { true }
        awaitText(MomentsRobot.NOTHING_YET)
        settle()
        assertTrue(exists(hasText(MomentsRobot.NOTHING_YET)), "still empty once the server has answered")
        assertFalse(exists(hasText(MomentsRobot.LOOK_FURTHER_BACK)), "nothing further back on a server with no history")
        assertFalse(exists(hasText(MomentsRobot.UNREACHABLE)), "an empty answer is not an error")
    }

    @Test
    fun playFullScreenOpensTheCamerasOwnScreenOnHomeAndBackReturnsToTheCameraList() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)

        moments.openClip(PERSON_ON_PORCH)
        server.clearRequests()
        moments.playFullScreen()

        shell.awaitSelected(TopLevelRoute.Home)
        awaitNode(hasContentDescription("Back"), "the camera screen's back button")
        awaitText("Front Door")
        server.awaitRequest(description = "the front door's recordings, for the moment's place in them") { it.path == "/api/front_door/recordings" }

        tap(hasContentDescription("Back"), "the camera screen's back button")
        awaitGone(hasContentDescription("Back"), "the camera screen")
        awaitTag(HOME_FEED_TEST_TAG)
        shell.awaitCameraCard("driveway", "Driveway")
        shell.awaitSelected(TopLevelRoute.Home)
    }

    @Test
    fun afterFullScreenTheMomentsTabIsOneTapAwayWithItsClipClosed() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)
        moments.openClip(PERSON_ON_PORCH)
        moments.playFullScreen()
        shell.awaitSelected(TopLevelRoute.Home)
        awaitNode(hasContentDescription("Back"), "the camera screen's back button")

        moments.open()

        moments.awaitMoment(PERSON_ON_PORCH)
        settle()
        assertFalse(exists(hasContentDescription("Play full screen")), "the inline player closed on the way out")
    }

    @Test
    fun tappingTheHomeSummaryOpensMoments() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        awaitTag(HOME_FEED_TEST_TAG)

        tap(hasTestTag(HOME_STATUS_TEST_TAG), "the Home summary")

        shell.awaitSelected(TopLevelRoute.Moments)
        moments.awaitFeed()
        moments.awaitMoment(PERSON_ON_PORCH)
    }

    private companion object {
        const val PERSON_ON_PORCH = "Person in the porch"
        const val SARAHS_TESLA = "Sarah's Tesla on the parking"
        const val DOG = "Dog detected"
        const val ALICE = "Alice detected"
    }
}

/**
 * The household's named car with no best-frame box: nothing for the app to judge its movement
 * on, so it is kept as a moment instead of being dropped as a car that only ever stood still.
 */
private fun showSarahsTesla(state: FakeFrigateState): FakeFrigateState = state.apply {
    val index = events.indexOfFirst { it.subLabel == "sarahs_tesla" }
    events[index] = events[index].copy(box = emptyList())
}

/** The id of the one event on [camera] with [label] and [subLabel] — the key its card is listed under. */
private fun FakeFrigateState.eventId(camera: String, label: String, subLabel: String?): String =
    edit { events.first { it.camera == camera && it.label == label && it.subLabel == subLabel }.id }
