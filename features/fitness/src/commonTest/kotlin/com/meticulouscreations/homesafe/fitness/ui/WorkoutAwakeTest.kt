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
}
