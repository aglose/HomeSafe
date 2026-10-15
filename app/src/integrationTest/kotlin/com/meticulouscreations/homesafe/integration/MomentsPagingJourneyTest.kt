package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeEvent
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.RecordedRequest
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Moments feed over time: a server that fails and comes back, a history longer than one
 * page, a detection that happens while the feed is open, and a feed opened at an earlier day.
 * All of it is the repository's paging and polling against the fake's `/api/events`, which
 * honours `limit`, `before` and `cameras` the way Frigate does.
 *
 * The long-history journeys use a hundred dogs in the back yard, ten minutes apart (a full page:
 * the feed reads a hundred at a time), and a person there before all of them, who is on the
 * second page and nowhere else. The back yard has no zones, so every one of them is a moment.
 */
class MomentsPagingJourneyTest {

    @Test
    fun aServerErrorOnEventsIsShownAndTheFeedRecoversOnItsOwn() = runAppJourney {
        state.edit { failures[MomentsRobot.EVENTS_PATH] = 500 }
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()

        awaitText(MomentsRobot.UNREACHABLE)
        awaitText("Couldn't load events: 500", substring = true)

        // No refresh control: a feed that couldn't fetch retries by itself within seconds.
        state.edit { failures.remove(MomentsRobot.EVENTS_PATH) }
        moments.awaitMoment(PERSON_ON_PORCH, timeout = 40.seconds)
        awaitGone(hasText("Couldn't load events", substring = true), "the fetch error")
        assertFalse(exists(hasText(MomentsRobot.UNREACHABLE)), "no error state once the server answers")
    }

    @Test
    fun lookingFurtherBackAsksForThePageBeforeTheOldestLoaded() = runAppJourney(aFullPageOfDogsAndSomeoneBefore()) {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(DOG)

        // Nobody on the first page: the empty feed offers the next one down rather than a dead end.
        moments.pickType("People")
        awaitText(
            "No people to show. Detections appear here when they happen in a zone set to watch for them, or when they're recognised.",
        )
        awaitText(MomentsRobot.LOOK_FURTHER_BACK)
        assertFalse(server.received(::isOlderPage), "nothing older asked for before the tap: ${server.requests}")

        tapText(MomentsRobot.LOOK_FURTHER_BACK)

        val older = moments.awaitFeedRequest("the page before the oldest dog", ::isOlderPage)
        assertTrue(abs(older.before() - oldestDog()) < 1.0, "the next page starts at the oldest detection loaded (${oldestDog()}): $older")
        moments.awaitMoment(SOMEONE)
        awaitText(MomentsRobot.EVERYTHING_LOADED)
        assertFalse(exists(hasText(MomentsRobot.LOOK_FURTHER_BACK)), "a short page is the end of the server's history")
    }

    @Test
    fun scrollingToTheEndOfTheFeedLoadsTheNextPage() = runAppJourney(aFullPageOfDogsAndSomeoneBefore()) {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        // The oldest dog is only in the list once the server's full first page is.
        moments.scrollTo(oldestEventId("dog"))

        moments.scrollToEnd()

        val older = moments.awaitFeedRequest("the page before the oldest dog", ::isOlderPage)
        assertTrue(abs(older.before() - oldestDog()) < 1.0, "the next page starts at the oldest detection loaded (${oldestDog()}): $older")
        moments.awaitMoment(SOMEONE, key = oldestEventId("person"))
        moments.scrollToEnd()
        awaitText(MomentsRobot.EVERYTHING_LOADED)
    }

    @Test
    fun aDetectionThatHappensWhileTheFeedIsOpenTurnsUpOnTheNextPoll() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)

        val now = System.currentTimeMillis() / 1000.0
        state.edit { events.add(FakeEvent(frigateEventId(now, "bob001"), "back_yard", "person", now, subLabel = "Bob", subLabelScore = 0.95)) }

        // The live feed re-reads its first page every thirty seconds.
        moments.awaitMoment("Bob detected", timeout = 45.seconds)
        moments.awaitMoment(PERSON_ON_PORCH)
    }

    @Test
    fun pickingADayOpensTheFeedAtTheEndOfItAndClosingItGoesBackToNow() = runAppJourney {
        val moments = MomentsRobot(this)
        signIn.signInAs()
        moments.open()
        moments.awaitMoment(PERSON_ON_PORCH)
        val pickedAt = System.currentTimeMillis() / 1000.0

        // The calendar opens on today; showing it opens the feed at today's end.
        tap(hasContentDescription("Pick a day"), "the day chip")
        tapText("Show")

        awaitNode(hasText("From ", substring = true), "the day chip naming the day")
        val day = moments.awaitFeedRequest("the feed's page at the end of the day") { "before" in it.query }
        assertTrue(day.before() > pickedAt, "the end of today is still ahead: $day")
        moments.awaitMoment(PERSON_ON_PORCH)

        server.clearRequests()
        tap(hasContentDescription("Back to the latest moments"), "the day chip's close")

        awaitNode(hasContentDescription("Pick a day"), "the day chip, back to a plain calendar")
        moments.awaitFeedRequest("the live feed's page") { "before" !in it.query }
        moments.awaitMoment(PERSON_ON_PORCH)
    }

    /** A hundred dogs in the back yard, ten minutes apart, and a person half an hour before the first. */
    private fun aFullPageOfDogsAndSomeoneBefore(): FakeFrigateState = FakeFrigateState.quiet().apply {
        val now = System.currentTimeMillis() / 1000.0
        repeat(PAGE) { i ->
            val start = now - 600.0 * (i + 1)
            events += FakeEvent(frigateEventId(start, "dog%03d".format(Locale.ROOT, i)), "back_yard", "dog", start)
        }
        val before = now - 600.0 * PAGE - 1_800.0
        events += FakeEvent(frigateEventId(before, "psn001"), "back_yard", "person", before)
    }

    private companion object {
        const val PERSON_ON_PORCH = "Person in the porch"
        const val DOG = "Dog detected"
        const val SOMEONE = "Person detected"

        /** What the feed asks for at a time (MomentsRepositoryImpl's PAGE_SIZE); a page this full may have more below. */
        const val PAGE = 100
    }
}

/** A Frigate-shaped event id: `<epoch with 6 decimals>-<6 chars>`. */
private fun frigateEventId(epochSeconds: Double, suffix: String): String = "%.6f-%s".format(Locale.ROOT, epochSeconds, suffix)

private fun isOlderPage(request: RecordedRequest): Boolean = MomentsRobot.isFeedRequest(request) && "before" in request.query

private fun RecordedRequest.before(): Double = query.getValue("before").single().toDouble()

private fun AppJourney.oldestDog(): Double = state.edit { events.filter { it.label == "dog" }.minOf { it.startTime } }

/** The id of the oldest [label] on the server — the key its card is listed under. */
private fun AppJourney.oldestEventId(label: String): String = state.edit { events.filter { it.label == label }.minBy { it.startTime }.id }
