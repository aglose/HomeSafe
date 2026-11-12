package com.meticulouscreations.homesafe.fitness.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkoutAwakeTest {

    @Test
    fun theScreenStaysOnTenMinutesPastTheLastThingThatHappened() {
        assertEquals(1_000L + WORKOUT_AWAKE_MILLIS, workoutAwakeUntil(lastActiveMillis = 1_000, restEndsAtMillis = null))
    }

    @Test
    fun aRestCountingKeepsItOnThroughTheRestAndTenMinutesPast() {
        assertEquals(181_000L + WORKOUT_AWAKE_MILLIS, workoutAwakeUntil(lastActiveMillis = 1_000, restEndsAtMillis = 181_000))
    }

    @Test
    fun aRestLongOverDoesNotShortenIt() {
        assertEquals(500_000L + WORKOUT_AWAKE_MILLIS, workoutAwakeUntil(lastActiveMillis = 500_000, restEndsAtMillis = 181_000))
    }

    @Test
    fun aRestThatRanOutBeingLetGoOfIsNotSomethingHappening() {
        val rested = WorkoutActivity(changedAt = 1_000, restEndsAt = 181_000)
        // Five minutes after it ran out, the app lets the rest go, with nobody there.
        val after = nextWorkoutActivity(rested, setsChanged = false, restEndsAt = null, nowMillis = 481_000)
        assertEquals(rested, after)
        assertEquals(181_000L + WORKOUT_AWAKE_MILLIS, workoutAwakeUntil(after.changedAt, after.restEndsAt), "ten minutes past the rest's end, not past the letting go")
    }

    @Test
    fun aRestSkippedBeforeItRanOutIsSomethingHappening() {
        val rested = WorkoutActivity(changedAt = 1_000, restEndsAt = 181_000)
        assertEquals(WorkoutActivity(60_000, null), nextWorkoutActivity(rested, setsChanged = false, restEndsAt = null, nowMillis = 60_000))
    }

    @Test
    fun aSetLoggedIsSomethingHappeningWhateverTheRestDid() {
        val rested = WorkoutActivity(changedAt = 1_000, restEndsAt = 181_000)
        assertEquals(WorkoutActivity(481_000, null), nextWorkoutActivity(rested, setsChanged = true, restEndsAt = null, nowMillis = 481_000))
        assertEquals(WorkoutActivity(481_000, 601_000), nextWorkoutActivity(rested, setsChanged = true, restEndsAt = 601_000, nowMillis = 481_000))
    }
}
