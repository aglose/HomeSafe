package com.meticulouscreations.homesafe.fitness.domain

import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogCopyTest {

    private val bench = exercise()
    private val squat = exercise(
        id = "legs/squat",
        name = "Squat",
        bodyPart = BodyPart.LEGS,
        loadKind = LoadKind.PLATES,
        primary = Muscle.QUADS,
        secondary = listOf(Muscle.GLUTES, Muscle.HAMSTRINGS),
        repLow = 6,
        repHigh = 10,
        increment = 50.0,
        restSeconds = 180,
        note = "belt from 3 plates",
    )
    private val heart = HeartSummary(belowMillis = 4_000, zoneMillis = listOf(1_000L, 2_000L, 3_000L, 0L, 500L), bpmMillis = 1_260_000, peakBpm = 171)

    /** A log with one of everything in it: benchmarks from the notes, a workout with its sets and its heart, a phase, a weigh-in, a sensor. */
    private val full = LogCopy(
        exercises = listOf(bench, squat),
        sets = listOf(
            LoggedSet(1, bench.id, 135.0, 10, 0, imported = true),
            LoggedSet(2, squat.id, 270.0, 8, 0, note = "pause", imported = true),
            LoggedSet(3, squat.id, 290.0, 6, 5_000, workoutId = 7),
            LoggedSet(4, bench.id, 0.0, 12, 5_100, workoutId = 7, bodyweight = 180.5),
        ),
        workouts = listOf(Workout(7, WorkoutFocus.LEGS, 4_900, 6_000)),
        phases = listOf(Phase(1, PhaseKind.CUT, 1_000)),
        bodyweights = listOf(BodyweightEntry(19_000, 180.5)),
        heart = HeartSettings(HeartProfile(maxBpm = 188, restingBpm = 54), HeartSensor("AA:BB:CC:00:00:01", "Band")),
        heartSummaries = mapOf(7L to heart),
    )

    private fun read(text: String): LogCopy = assertIs<LogCopyRead.Copy>(LogCopyText.read(text)).copy

    // ---- As text ------------------------------------------------------------------------------

    @Test
    fun aCopyReadBackFromItsTextIsTheLogItWasMadeFrom() {
        val back = read(LogCopyText.encode(full))
        assertEquals(full.exercises, back.exercises)
        // Sets travel inside their exercises and without their ids; everything else about them comes through.
        assertEquals(full.sets.map { it.copy(id = 0) }.toSet(), back.sets.map { it.copy(id = 0) }.toSet())
        assertEquals(full.workouts, back.workouts)
        assertEquals(full.phases, back.phases)
        assertEquals(full.bodyweights, back.bodyweights)
        assertEquals(full.heart, back.heart)
        assertEquals(full.heartSummaries, back.heartSummaries)
    }

    @Test
    fun anExercisesSetsKeepTheirOrder() {
        val ladder = (1..5).map { LoggedSet(it.toLong(), bench.id, 100.0 + it, 10, 0) }
        assertEquals(ladder.map { it.weight }, read(LogCopyText.encode(LogCopy(listOf(bench), ladder))).sets.map { it.weight })
    }

    @Test
    fun anEmptyLogIsStillACopy() {
        assertEquals(LogCopy(), read(LogCopyText.encode(LogCopy())))
    }

    @Test
    fun whatAShareSheetPutsRoundACopyIsSteppedOver() {
        assertEquals(full.exercises, read("Training log\n" + LogCopyText.encode(full) + "\n\nSent from my phone").exercises)
    }

    @Test
    fun notesAreNotACopy() {
        assertEquals(LogCopyRead.NotACopy, LogCopyText.read("Hack squat\n- 290lbs - 12 reps"))
        assertEquals(LogCopyRead.NotACopy, LogCopyText.read(""))
        assertEquals(LogCopyRead.NotACopy, LogCopyText.read("{\"exercises\":[]}"))
    }

    @Test
    fun aCopyCutShortIsUnreadableAndNotTakenForNotes() {
        val text = LogCopyText.encode(full)
        assertEquals(LogCopyRead.Unreadable, LogCopyText.read(text.take(text.length / 2)))
        assertEquals(LogCopyRead.Unreadable, LogCopyText.read(text.dropLast(1)))
    }

    @Test
    fun aCopyFromALaterFormatIsUnreadable() {
        assertEquals(LogCopyRead.Unreadable, LogCopyText.read("{\"percysafeTrainingLog\":2,\"exercises\":[]}"))
    }

    @Test
    fun namesThisBuildDoesNotKnowFallBackAsTheyDoFromTheDatabase() {
        val text = """{"percysafeTrainingLog":1,"somethingNew":true,"exercises":[{"id":"x/y","name":"Y","part":"NECK","equipment":"KETTLEBELL","load":"STONES","primary":"NECK","secondary":["NECK","TRAPS"]}],"workouts":[{"id":3,"focus":"NECK","start":10}],"phases":[{"kind":"RECOMP","start":5}]}"""
        val copy = read(text)
        val expected = Exercise("x/y", "Y", BodyPart.CORE, Equipment.OTHER, LoadKind.WEIGHT, Muscle.ABS, listOf(Muscle.TRAPS))
        assertEquals(listOf(expected), copy.exercises)
        assertEquals(listOf(Workout(3, WorkoutFocus.CHEST, 10)), copy.workouts)
        assertEquals(listOf(Phase(1, PhaseKind.MAINTAIN, 5)), copy.phases)
    }

    @Test
    fun whatTheLogCouldNotHoldIsLeftOutOnTheWayIn() {
        val text = """{"percysafeTrainingLog":1,"exercises":[
            {"id":"a/b","name":"B","repLow":0,"repHigh":-3,"step":0.0,"rest":-5,"sets":[{"w":100.0,"r":0},{"w":-5.0,"r":8},{"w":100.0,"r":8,"t":-9}]},
            {"id":"a/b","name":"B again"},
            {"id":"","name":"No id"},
            {"id":"a/c","name":" "}
            ],"weighIns":[{"day":5,"pounds":0.0},{"day":6,"pounds":181.0},{"day":6,"pounds":182.0}],
            "heart":{"sensorAddress":" ","workouts":[{"workout":3,"zones":[1,2],"peak":150}]}}"""
        val copy = read(text)
        val kept = copy.exercises.single()
        assertEquals("B", kept.name)
        assertEquals(1 to 1, kept.repLow to kept.repHigh)
        assertEquals(5.0, kept.increment)
        assertEquals(0, kept.restSeconds)
        assertEquals(listOf(LoggedSet(1, "a/b", 100.0, 8, 0)), copy.sets)
        assertEquals(listOf(BodyweightEntry(6, 181.0)), copy.bodyweights)
        assertNull(copy.heart.sensor)
        assertEquals(listOf(1L, 2L, 0L, 0L, 0L), copy.heartSummaries.getValue(3).zoneMillis)
    }

    // ---- Into a log ---------------------------------------------------------------------------

    @Test
    fun intoAnEmptyLogEverythingIsBroughtIn() {
        val merge = LogCopies.merge(full, LogCopy())
        assertEquals(full.exercises, merge.exercises)
        assertEquals(2, merge.newExercises)
        assertEquals(0, merge.changedExercises)
        assertEquals(4, merge.sets.size)
        assertEquals(listOf(Workout(1, WorkoutFocus.LEGS, 4_900, 6_000)), merge.workouts)
        assertEquals(full.phases, merge.phases)
        assertEquals(full.bodyweights, merge.bodyweights)
        assertEquals(full.heart, merge.heart)
        assertEquals(mapOf(1L to heart), merge.heartSummaries)
    }

    @Test
    fun aWorkoutsSetsFollowItToItsNewId() {
        val into = LogCopy(workouts = listOf(Workout(7, WorkoutFocus.CHEST, 100, 200), Workout(8, WorkoutFocus.BACK, 300, 400)))
        val merge = LogCopies.merge(full, into)
        assertEquals(listOf(9L), merge.workouts.map { it.id })
        assertEquals(listOf(null, null, 9L, 9L), merge.sets.map { it.workoutId })
        assertEquals(setOf(9L), merge.heartSummaries.keys)
    }

    @Test
    fun idsCarryOnFromTheHighestTheLogHas() {
        val into = LogCopy(
            exercises = listOf(bench),
            sets = listOf(LoggedSet(40, bench.id, 225.0, 3, 9_000)),
            phases = listOf(Phase(5, PhaseKind.BULK, 50)),
        )
        val merge = LogCopies.merge(full, into)
        assertEquals(listOf(41L, 42L, 43L, 44L), merge.sets.map { it.id })
        assertEquals(listOf(6L), merge.phases.map { it.id })
    }

    @Test
    fun theSameCopyBroughtInTwiceAddsNothingTwice() {
        val first = LogCopies.merge(full, LogCopy())
        val after = LogCopy(first.exercises, first.sets, first.workouts, first.phases, first.bodyweights, first.heart!!, first.heartSummaries)
        val second = LogCopies.merge(full, after)
        assertTrue(second.isEmpty)
        assertEquals(LogMerge(), second)
    }

    @Test
    fun onlyTheSetsTheLogHasNotGotAreAdded() {
        val into = LogCopy(exercises = listOf(bench, squat), sets = listOf(LoggedSet(1, squat.id, 270.0, 8, 0), LoggedSet(2, squat.id, 290.0, 6, 5_000)))
        val merge = LogCopies.merge(full, into)
        assertEquals(listOf(bench.id to 135.0, bench.id to 0.0), merge.sets.map { it.exerciseId to it.weight })
    }

    @Test
    fun aWorkoutTheLogAlreadyHasIsNotAddedAgainAndItsNewSetsJoinIt() {
        val into = LogCopy(exercises = listOf(bench, squat), workouts = listOf(Workout(3, WorkoutFocus.LEGS, 4_900, 6_000)), heartSummaries = mapOf(3L to HeartSummary(belowMillis = 9)))
        val merge = LogCopies.merge(full, into)
        assertEquals(emptyList(), merge.workouts)
        assertEquals(listOf(3L, 3L), merge.sets.mapNotNull { it.workoutId })
        // Its heart is already here, and is left as it is.
        assertEquals(emptyMap(), merge.heartSummaries)
    }

    @Test
    fun anExerciseTheLogHasDifferentlyIsSetUpTheWayTheCopyHasIt() {
        val moved = bench.copy(bodyPart = BodyPart.SHOULDERS, repLow = 4, repHigh = 8)
        val merge = LogCopies.merge(LogCopy(exercises = listOf(bench, squat)), LogCopy(exercises = listOf(moved, squat)))
        assertEquals(listOf(bench), merge.exercises)
        assertEquals(0, merge.newExercises)
        assertEquals(1, merge.changedExercises)
    }

    @Test
    fun aSetOfAnExerciseNobodyKnowsIsLeftBehind() {
        val merge = LogCopies.merge(LogCopy(sets = listOf(LoggedSet(1, "gone/lift", 50.0, 5, 0))), LogCopy())
        assertTrue(merge.isEmpty)
    }

    @Test
    fun aSetOfAnExerciseOnlyTheLogKnowsIsStillAdded() {
        val merge = LogCopies.merge(LogCopy(sets = listOf(LoggedSet(1, bench.id, 50.0, 5, 0))), LogCopy(exercises = listOf(bench)))
        assertEquals(listOf(50.0), merge.sets.map { it.weight })
    }

    @Test
    fun aWeighInTheLogHasForThatDayIsKept() {
        val into = LogCopy(bodyweights = listOf(BodyweightEntry(19_000, 179.0)))
        val copy = LogCopy(bodyweights = listOf(BodyweightEntry(19_000, 180.5), BodyweightEntry(19_001, 180.0)))
        assertEquals(listOf(BodyweightEntry(19_001, 180.0)), LogCopies.merge(copy, into).bodyweights)
    }

    @Test
    fun aPhaseIsTheSameOneOnlyWhenItsKindAndStartAre() {
        val into = LogCopy(phases = listOf(Phase(1, PhaseKind.CUT, 1_000)))
        val copy = LogCopy(phases = listOf(Phase(1, PhaseKind.CUT, 1_000), Phase(2, PhaseKind.BULK, 1_000), Phase(3, PhaseKind.CUT, 2_000)))
        assertEquals(listOf(Phase(2, PhaseKind.BULK, 1_000), Phase(3, PhaseKind.CUT, 2_000)), LogCopies.merge(copy, into).phases)
    }

    @Test
    fun heartSettingsFillInWhatTheLogHasNotGotAndLeaveWhatItHas() {
        val mine = HeartSettings(HeartProfile(maxBpm = 190, age = 40), HeartSensor("11:22:33:44:55:66", "Mine"))
        val merge = LogCopies.merge(full, LogCopy(heart = mine))
        assertEquals(HeartSettings(HeartProfile(maxBpm = 190, age = 40, restingBpm = 54), mine.sensor), merge.heart)
    }

    @Test
    fun heartSettingsThatWouldNotChangeAreNotWritten() {
        assertNull(LogCopies.merge(full, LogCopy(heart = full.heart)).heart)
        assertNull(LogCopies.merge(LogCopy(), LogCopy(heart = full.heart)).heart)
    }

    @Test
    fun aHeartSummaryWithNothingInItOrForNoWorkoutIsNotBroughtIn() {
        val copy = full.copy(heartSummaries = mapOf(7L to HeartSummary(), 99L to heart))
        assertEquals(emptyMap(), LogCopies.merge(copy, LogCopy()).heartSummaries)
    }
}
