package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.FitnessTestData.set
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.BodyweightTrend
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.PaceVerdict
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.RecordKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.Rung
import com.meticulouscreations.homesafe.fitness.domain.Strength
import com.meticulouscreations.homesafe.fitness.domain.TargetReason
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitnessBoardBuilderTest {

    private val bench = exercise()
    private val squat = exercise(id = "legs/squat", name = "Squat", bodyPart = BodyPart.LEGS, primary = Muscle.QUADS)
    private val row = exercise(id = "back/row", name = "Row", bodyPart = BodyPart.BACK, primary = Muscle.BACK)
    private val pullUps = exercise(id = "back/pull-ups", name = "Pull-ups", bodyPart = BodyPart.BACK, loadKind = LoadKind.BODYWEIGHT, primary = Muscle.BACK)

    private fun ago(days: Long) = NOW - days * DAY

    private fun hoursAgo(hours: Long) = NOW - hours * 3_600L

    private fun build(log: FitnessLog, now: Long = NOW, offset: Int = 0) = FitnessBoardBuilder.build(log, now, offset)

    private fun logged(id: Long, weight: Double, reps: Int, at: Long, exerciseId: String = bench.id, workoutId: Long? = null, bodyweight: Double? = null) =
        set(id, weight, reps, at, exerciseId = exerciseId, workoutId = workoutId, bodyweight = bodyweight)

    private fun fromNotes(id: Long, weight: Double, reps: Int, at: Long = 0, exerciseId: String = bench.id) =
        set(id, weight, reps, at, exerciseId = exerciseId, imported = true)

    private fun board(log: FitnessLog, exercise: Exercise = bench) = build(log).boards.single { it.exercise.id == exercise.id }

    // ---- Nothing yet --------------------------------------------------------------------------

    @Test
    fun anEmptyLogHasNothingOnTheBoards() {
        val boards = build(FitnessLog())
        assertEquals(PhaseStatus(), boards.phase)
        assertEquals(emptyList(), boards.boards)
        assertNull(boards.workout)
        assertEquals(emptyList(), boards.recentRecords)
        assertEquals(emptyList(), boards.calendar)
        assertEquals(emptyList(), boards.history)
        assertEquals(BodyweightTrend(), boards.bodyweight)
    }

    @Test
    fun anEmptyLogStillHasACardForEveryKindOfWorkoutAndNoneIsDue() {
        val boards = build(FitnessLog())
        assertEquals(WorkoutFocus.entries, boards.days.map { it.focus })
        assertTrue(boards.days.none { it.due })
        assertTrue(boards.days.all { it.lastEpochSeconds == null && it.daysAgo == null && it.exercises == 0 && it.lifts.isEmpty() })
    }

    @Test
    fun anEmptyLogHasAWeekOfZeros() {
        val week = build(FitnessLog()).week
        assertEquals(0, week.sessions)
        assertEquals(0, week.sets)
        assertEquals(0, week.records)
        assertEquals(Muscle.entries, week.muscles.map { it.muscle })
        assertTrue(week.muscles.all { it.sets == 0.0 })
    }

    // ---- The boards ---------------------------------------------------------------------------

    @Test
    fun boardsAreSortedByBodyPartThenName() {
        val press = exercise(id = "chest/press", name = "Press")
        val boards = build(FitnessLog(exercises = listOf(squat, press, row, bench))).boards
        assertEquals(listOf("Bench press", "Press", "Row", "Squat"), boards.map { it.exercise.name })
    }

    @Test
    fun namesAreSortedWithoutRegardToCase() {
        val lower = exercise(id = "chest/apple", name = "apple")
        val upper = exercise(id = "chest/Banana", name = "Banana")
        val boards = build(FitnessLog(exercises = listOf(upper, lower))).boards
        assertEquals(listOf("apple", "Banana"), boards.map { it.exercise.name })
    }

    @Test
    fun anArchivedExerciseHasNoBoard() {
        val old = exercise(id = "chest/old", name = "Old", archived = true)
        val boards = build(FitnessLog(exercises = listOf(bench, old))).boards
        assertEquals(listOf(bench.id), boards.map { it.exercise.id })
    }

    @Test
    fun anExerciseWithNoSetsHasAnEmptyBoard() {
        val board = board(FitnessLog(exercises = listOf(bench)))
        assertEquals(ExerciseBoard(bench), board)
        assertEquals(1f, board.standing)
        assertNull(board.lastTrainedEpochSeconds)
    }

    // ---- What a board holds -------------------------------------------------------------------

    private val ladderLog = FitnessLog(
        exercises = listOf(bench),
        sets = listOf(
            fromNotes(1, 120.0, 8),
            logged(2, 100.0, 10, ago(10)),
            logged(3, 105.0, 8, ago(2)),
        ),
        phases = listOf(Phase(1, PhaseKind.BULK, ago(5))),
    )

    @Test
    fun theLadderHasARungForEveryWeightWithThisPhasesRepsBesideTheAllTimeOnes() {
        assertEquals(
            listOf(
                Rung(100.0, 10, null, ago(10)),
                Rung(105.0, 8, 8, ago(2)),
                Rung(120.0, 8, null, 0),
            ),
            board(ladderLog).ladder,
        )
    }

    @Test
    fun theBestIsTheHighestEstimateEverIncludingTheNotesBenchmarks() {
        assertEquals(1L, board(ladderLog).best?.id)
    }

    @Test
    fun thePhaseBestIsTheBestDatedSetSinceThePhaseBegan() {
        assertEquals(3L, board(ladderLog).phaseBest?.id)
    }

    @Test
    fun withNoPhaseThePhaseBestIsTheBestDatedSetThereIs() {
        val noPhase = ladderLog.copy(phases = emptyList())
        // 100 x 10 is 133.3 and 105 x 8 is 133.
        assertEquals(2L, board(noPhase).phaseBest?.id)
    }

    @Test
    fun theLastSetIsTheBestOfTheMostRecentDay() {
        assertEquals(3L, board(ladderLog).last?.id)
        assertEquals(ago(2), board(ladderLog).lastTrainedEpochSeconds)
    }

    @Test
    fun theStandingIsLastTimeAgainstTheAllTimeBest() {
        // 133 against 152.
        assertEquals(0.875f, board(ladderLog).standing, 1e-4f)
    }

    @Test
    fun theBoardCarriesTheTargetForNextTime() {
        val target = assertNotNull(board(ladderLog).target)
        assertEquals(TargetReason.REBUILD, target.reason)
        assertEquals(105.0, target.weight)
        assertEquals(10, target.reps)
    }

    @Test
    fun theTargetFollowsThePhase() {
        val cut = ladderLog.copy(phases = listOf(Phase(1, PhaseKind.CUT, ago(5))))
        assertEquals(TargetReason.HOLD, board(cut).target?.reason)
    }

    @Test
    fun theSetCountIncludesTheNotes() {
        assertEquals(3, board(ladderLog).setCount)
    }

    @Test
    fun setsWithNoRepsAreNotCounted() {
        val log = ladderLog.copy(sets = ladderLog.sets + logged(4, 300.0, 0, ago(1)))
        assertEquals(3, board(log).setCount)
        assertEquals(listOf(100.0, 105.0, 120.0), board(log).ladder.map { it.weight })
    }

    @Test
    fun theHistoryIsTheTopSetOfEachDayOldestFirst() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(
                logged(1, 100.0, 10, ago(3)),
                logged(2, 110.0, 8, ago(3) + 600),
                logged(3, 100.0, 11, ago(1)),
            ),
        )
        val history = board(log).history
        assertEquals(listOf(2L, 3L), history.map { it.top.id })
        assertEquals(listOf(ago(3) + 600, ago(1)), history.map { it.epochSeconds })
        assertEquals(Strength.e1rm(110.0, 8), history[0].score, 1e-9)
    }

    @Test
    fun theHistoryLeavesOutBenchmarksButKeepsDatedSetsFromTheNotes() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(fromNotes(1, 120.0, 8), fromNotes(2, 100.0, 10, at = ago(400))),
        )
        assertEquals(listOf(2L), board(log).history.map { it.top.id })
    }

    @Test
    fun anExerciseOnlyTheNotesKnowHasNoLastSetAndAStandingOfOne() {
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(fromNotes(1, 120.0, 8), fromNotes(2, 100.0, 12)))
        val board = board(log)
        assertNull(board.last)
        assertEquals(1f, board.standing)
        assertEquals(TargetReason.BENCHMARK, board.target?.reason)
        assertNotNull(board.best)
    }

    @Test
    fun aBodyweightMovementIsScoredWithTheBodyweightTrendWhenTheSetNotedNone() {
        val log = FitnessLog(
            exercises = listOf(pullUps),
            sets = listOf(logged(1, 0.0, 10, ago(2), exerciseId = pullUps.id)),
            bodyweights = listOf(BodyweightEntry(NOW / DAY, 180.0)),
        )
        assertEquals(Strength.e1rm(180.0, 10), board(log, pullUps).history.single().score, 1e-9)
    }

    @Test
    fun aBodyweightNotedOnTheSetIsWhatItIsScoredWith() {
        val log = FitnessLog(
            exercises = listOf(pullUps),
            sets = listOf(logged(1, 0.0, 10, ago(2), exerciseId = pullUps.id, bodyweight = 170.0)),
            bodyweights = listOf(BodyweightEntry(NOW / DAY, 180.0)),
        )
        assertEquals(Strength.e1rm(170.0, 10), board(log, pullUps).history.single().score, 1e-9)
    }

    // ---- Records ------------------------------------------------------------------------------

    private fun record(scope: RecordScope, kind: RecordKind) = Record(scope, kind)

    @Test
    fun aSetLoggedHeavierThanAnythingBeforeIsARecord() {
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(fromNotes(1, 100.0, 10), logged(2, 105.0, 10, ago(3))))
        val events = build(log).recentRecords
        assertEquals(1, events.size)
        assertEquals(2L, events.single().set.id)
        assertEquals(bench, events.single().exercise)
        assertEquals(record(RecordScope.ALL_TIME, RecordKind.WEIGHT), events.single().record)
    }

    @Test
    fun aFirstLoggedSetIsNoRecord() {
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(logged(1, 105.0, 10, ago(3))))
        assertEquals(emptyList(), build(log).recentRecords)
    }

    @Test
    fun onlySetsLoggedInTheAppCanBeRecordsNotTheNotes() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(
                fromNotes(1, 100.0, 10),
                logged(2, 105.0, 10, ago(3)),
                // Heavier than any, and dated, but it came in with the notes.
                fromNotes(3, 200.0, 10, at = ago(1)),
            ),
        )
        assertEquals(listOf(2L), build(log).recentRecords.map { it.set.id })
    }

    @Test
    fun aSetBelowABenchmarkIsNoRecord() {
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(fromNotes(1, 120.0, 10), logged(2, 105.0, 10, ago(3))))
        assertEquals(emptyList(), build(log).recentRecords)
    }

    @Test
    fun recordsAreJudgedAgainstThePhaseTheSetWasLoggedIn() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(
                logged(1, 100.0, 10, ago(40)),
                logged(2, 90.0, 8, ago(6)),
                logged(3, 90.0, 9, ago(5)),
            ),
            phases = listOf(Phase(1, PhaseKind.CUT, ago(20))),
        )
        val events = build(log).recentRecords
        assertEquals(listOf(3L), events.map { it.set.id })
        assertEquals(record(RecordScope.PHASE, RecordKind.ESTIMATE), events.single().record)
    }

    @Test
    fun recentRecordsAreTheLastThirtyDaysOldestDropped() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(
                logged(1, 100.0, 10, ago(60)),
                logged(2, 105.0, 10, ago(31)),
                logged(3, 110.0, 10, ago(30)),
                logged(4, 115.0, 10, ago(1)),
            ),
        )
        assertEquals(listOf(4L, 3L), build(log).recentRecords.map { it.set.id })
    }

    @Test
    fun recentRecordsAreTheNewestTwelveNewestFirst() {
        val sets = (1..14).map { i -> logged(i.toLong(), 100.0 + i, 10, ago(15L - i)) }
        val events = build(FitnessLog(exercises = listOf(bench), sets = sets)).recentRecords
        assertEquals((14 downTo 3).map { it.toLong() }, events.map { it.set.id })
    }

    // ---- The workout in progress --------------------------------------------------------------

    private fun open(id: Long, hoursOld: Long, focus: WorkoutFocus = WorkoutFocus.CHEST) = Workout(id, focus, hoursAgo(hoursOld))

    @Test
    fun anOpenRecentWorkoutIsTheActiveOne() {
        val log = FitnessLog(exercises = listOf(bench), workouts = listOf(open(1, 2)))
        val active = assertNotNull(build(log).workout)
        assertEquals(1L, active.workout.id)
        assertEquals(emptyList(), active.entries)
        assertEquals(0, active.setCount)
    }

    @Test
    fun aFinishedWorkoutIsNotActive() {
        val finished = open(1, 2).copy(finishedAtEpochSeconds = hoursAgo(1))
        assertNull(build(FitnessLog(exercises = listOf(bench), workouts = listOf(finished))).workout)
    }

    @Test
    fun aWorkoutLeftOpenForEightHoursIsNoLongerActive() {
        val justUnder = Workout(1, WorkoutFocus.CHEST, NOW - FitnessBoardBuilder.STALE_WORKOUT_SECONDS + 1)
        val exactly = Workout(1, WorkoutFocus.CHEST, NOW - FitnessBoardBuilder.STALE_WORKOUT_SECONDS)
        assertNotNull(build(FitnessLog(exercises = listOf(bench), workouts = listOf(justUnder))).workout)
        assertNull(build(FitnessLog(exercises = listOf(bench), workouts = listOf(exactly))).workout)
    }

    @Test
    fun theActiveWorkoutIsTheLatestOpenFreshOneEvenWhenAStaleOneIsListedAfterIt() {
        val log = FitnessLog(exercises = listOf(bench), workouts = listOf(open(1, 1), open(2, 10)))
        assertEquals(1L, build(log).workout?.workout?.id)
    }

    @Test
    fun setsLoggedInTheOpenWorkoutDoNotMoveLastTimeOrTheTarget() {
        val before = FitnessLog(exercises = listOf(bench), sets = listOf(logged(1, 100.0, 9, ago(7))))
        val during = before.copy(sets = before.sets + logged(2, 100.0, 10, hoursAgo(1), workoutId = 1), workouts = listOf(open(1, 2)))
        // The set just logged is the attempt at the target: it stays what the workout began with.
        assertEquals(board(before).target?.let { it.weight to it.reps }, board(during).target?.let { it.weight to it.reps })
        assertEquals(1L, board(during).last?.id)
        // Once the workout is finished, it is last time.
        val after = during.copy(workouts = listOf(during.workouts.single().copy(finishedAtEpochSeconds = NOW)))
        assertEquals(2L, board(after).last?.id)
    }

    @Test
    fun theWorkoutHoldsOnlyItsOwnSetsGroupedByExerciseInTheOrderTheyWereDone() {
        val log = FitnessLog(
            exercises = listOf(bench, squat),
            sets = listOf(
                logged(1, 100.0, 10, hoursAgo(1), workoutId = 1),
                logged(2, 150.0, 8, hoursAgo(1) + 600, exerciseId = squat.id, workoutId = 1),
                logged(3, 100.0, 9, hoursAgo(1) + 1_200, workoutId = 1),
                // Logged outside any workout, a moment before.
                logged(4, 100.0, 9, hoursAgo(1) + 1_800),
            ),
            workouts = listOf(open(1, 2)),
        )
        val active = assertNotNull(build(log).workout)
        assertEquals(listOf(bench.id, squat.id), active.entries.map { it.exerciseId })
        assertEquals(listOf(1L, 3L), active.entry(bench.id)?.sets?.map { it.id })
        assertEquals(listOf(2L), active.entry(squat.id)?.sets?.map { it.id })
        assertEquals(3, active.setCount)
        assertNull(active.entry("back/row"))
    }

    @Test
    fun theWorkoutShowsWhichOfItsSetsWereRecords() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(fromNotes(1, 100.0, 10), logged(2, 105.0, 10, hoursAgo(1), workoutId = 1), logged(3, 100.0, 8, hoursAgo(1) + 600, workoutId = 1)),
            workouts = listOf(open(1, 2)),
        )
        val active = assertNotNull(build(log).workout)
        assertEquals(mapOf(2L to record(RecordScope.ALL_TIME, RecordKind.WEIGHT)), active.entry(bench.id)?.records)
        assertEquals(1, active.recordCount)
    }

    // ---- What goes along with a workout --------------------------------------------------------

    private val plank = exercise(id = "core/plank", name = "Plank", bodyPart = BodyPart.CORE, loadKind = LoadKind.BODYWEIGHT, primary = Muscle.ABS)
    private val calves = exercise(id = "legs/calf-machine", name = "Calf machine", bodyPart = BodyPart.LEGS, primary = Muscle.CALVES)
    private val curl = exercise(id = "biceps/curl", name = "Curl", bodyPart = BodyPart.BICEPS, primary = Muscle.BICEPS)

    private fun regulars(log: FitnessLog, focus: WorkoutFocus = WorkoutFocus.BACK) =
        FitnessBoardBuilder.regulars(log, build(log).boards, focus)

    @Test
    fun coreAndCalvesGoAlongWithAnyWorkoutBeforeThereIsAnyToLearnFrom() {
        val log = FitnessLog(exercises = listOf(bench, squat, row, plank, calves))
        // Not the back day's own row, and not a squat or a bench press, which nothing has put alongside a back day.
        assertEquals(setOf(plank.id, calves.id), regulars(log).toSet())
        // On legs day the calves are on its own shelf already.
        assertEquals(listOf(plank.id), regulars(log, WorkoutFocus.LEGS))
    }

    @Test
    fun whatHasBeenDoneOnThisKindOfDayLeadsThenWhatTravelsWithOtherDays() {
        val log = FitnessLog(
            exercises = listOf(bench, squat, row, plank, calves, curl),
            workouts = listOf(finished(1, WorkoutFocus.BACK, 9), finished(2, WorkoutFocus.BACK, 2), finished(3, WorkoutFocus.CHEST, 5), finished(4, WorkoutFocus.LEGS, 4)),
            sets = listOf(
                // Curls on both back days, two sets each: counted once a workout.
                logged(1, 30.0, 12, ago(9), exerciseId = curl.id, workoutId = 1),
                logged(2, 30.0, 10, ago(9) + 60, exerciseId = curl.id, workoutId = 1),
                logged(3, 30.0, 12, ago(2), exerciseId = curl.id, workoutId = 2),
                // Calves on one back day, and on chest day.
                logged(4, 14.0, 25, ago(2) + 600, exerciseId = calves.id, workoutId = 2),
                logged(5, 14.0, 25, ago(5), exerciseId = calves.id, workoutId = 3),
                // A plank on chest day and on legs day: never on a back day, but it goes along with others.
                logged(6, 0.0, 60, ago(5) + 600, exerciseId = plank.id, workoutId = 3),
                logged(7, 0.0, 60, ago(4), exerciseId = plank.id, workoutId = 4),
                // A bench press once on a legs day: it has gone along with a workout, so it is offered, last.
                logged(8, 100.0, 10, ago(4) + 600, workoutId = 4),
                // A squat on legs day is that day's own; it went along with nothing.
                logged(9, 200.0, 10, ago(4) + 1_200, exerciseId = squat.id, workoutId = 4),
            ),
        )
        assertEquals(listOf(curl.id, calves.id, plank.id, bench.id), regulars(log))
    }

    @Test
    fun anOpenWorkoutCarriesItsRegulars() {
        val log = FitnessLog(exercises = listOf(row, plank), workouts = listOf(open(1, 1, WorkoutFocus.BACK)))
        assertEquals(listOf(plank.id), assertNotNull(build(log).workout).regulars)
    }

    @Test
    fun findMatchesEveryWordInAnyOrderThoseBeginningWithItFirst() {
        val boards = build(FitnessLog(exercises = listOf(bench, row, calves, plank, exercise(id = "legs/seated-calf-raise", name = "Seated calf raise", bodyPart = BodyPart.LEGS)))).boards
        assertEquals(listOf("Calf machine", "Seated calf raise"), FitnessBoardBuilder.find(boards, "calf").map { it.exercise.name })
        assertEquals(listOf("Seated calf raise"), FitnessBoardBuilder.find(boards, " RAISE  calf").map { it.exercise.name })
        assertEquals(emptyList(), FitnessBoardBuilder.find(boards, "  "))
        assertEquals(emptyList(), FitnessBoardBuilder.find(boards, "deadlift"))
    }

    // ---- The week -----------------------------------------------------------------------------

    @Test
    fun theWeekCountsTheLoggedSetsOfTheLastSevenDays() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(
                // Outside it: a second too early, a second too late, the notes', a benchmark, a set of no reps.
                logged(5, 100.0, 10, ago(7) - 1),
                logged(9, 100.0, 10, NOW + 1),
                fromNotes(6, 100.0, 10, at = ago(2)),
                fromNotes(7, 100.0, 10),
                logged(8, 100.0, 0, ago(1)),
                // Inside it, the oldest on the very edge.
                logged(4, 100.0, 10, ago(7)),
                logged(3, 100.0, 10, ago(3)),
                logged(1, 100.0, 10, ago(1)),
                logged(2, 100.0, 10, ago(1) + 600),
                logged(10, 110.0, 10, ago(1) + 1_200),
            ),
        )
        val week = build(log).week
        assertEquals(5, week.sets)
        assertEquals(3, week.sessions)
        assertEquals(5.0, week.muscles.single { it.muscle == Muscle.CHEST }.sets)
    }

    @Test
    fun theWeeksRecordsAreTheAllTimeOnesSetInIt() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(20)), logged(2, 105.0, 10, ago(3)), logged(3, 110.0, 10, ago(1))),
        )
        assertEquals(2, build(log).week.records)
    }

    @Test
    fun aRecordFromBeforeTheWeekIsNotTheWeeks() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(20)), logged(2, 105.0, 10, ago(10))),
        )
        assertEquals(0, build(log).week.records)
    }

    @Test
    fun aPhaseRecordIsNotCountedAmongTheWeeksRecords() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(40)), logged(2, 90.0, 8, ago(6)), logged(3, 90.0, 9, ago(5))),
            phases = listOf(Phase(1, PhaseKind.CUT, ago(20))),
        )
        val boards = build(log)
        assertEquals(1, boards.recentRecords.size)
        assertEquals(0, boards.week.records)
    }

    // ---- Which workout is due -----------------------------------------------------------------

    private fun finished(id: Long, focus: WorkoutFocus, daysAgo: Long) =
        Workout(id, focus, ago(daysAgo), finishedAtEpochSeconds = ago(daysAgo) + 3_600)

    private fun dueFocus(log: FitnessLog): List<WorkoutFocus> = build(log).days.filter { it.due }.map { it.focus }

    @Test
    fun theMainDayTrainedLeastRecentlyIsDue() {
        val log = FitnessLog(
            exercises = listOf(bench),
            workouts = listOf(finished(1, WorkoutFocus.CHEST, 5), finished(2, WorkoutFocus.BACK, 3), finished(3, WorkoutFocus.LEGS, 7)),
        )
        assertEquals(listOf(WorkoutFocus.LEGS), dueFocus(log))
    }

    @Test
    fun aMainDayNeverTrainedIsDueBeforeAnyThatWas() {
        val log = FitnessLog(
            exercises = listOf(bench),
            workouts = listOf(finished(1, WorkoutFocus.CHEST, 9), finished(2, WorkoutFocus.BACK, 3)),
        )
        assertEquals(listOf(WorkoutFocus.LEGS), dueFocus(log))
    }

    @Test
    fun withNothingEverTrainedTheFirstMainDayIsDue() {
        assertEquals(listOf(WorkoutFocus.CHEST), dueFocus(FitnessLog(exercises = listOf(bench))))
    }

    @Test
    fun theLatestWorkoutOfAKindIsTheOneCounted() {
        val log = FitnessLog(
            exercises = listOf(bench),
            workouts = listOf(
                finished(1, WorkoutFocus.CHEST, 20),
                finished(2, WorkoutFocus.CHEST, 1),
                finished(3, WorkoutFocus.BACK, 6),
                finished(4, WorkoutFocus.LEGS, 4),
            ),
        )
        assertEquals(listOf(WorkoutFocus.BACK), dueFocus(log))
    }

    @Test
    fun theShouldersAndArmsDaysAreNeverDue() {
        val log = FitnessLog(
            exercises = listOf(bench),
            workouts = listOf(finished(1, WorkoutFocus.CHEST, 2), finished(2, WorkoutFocus.BACK, 3), finished(3, WorkoutFocus.LEGS, 4)),
        )
        val cards = build(log).days.associateBy { it.focus }
        assertFalse(cards.getValue(WorkoutFocus.SHOULDERS).due)
        assertFalse(cards.getValue(WorkoutFocus.ARMS).due)
        assertTrue(cards.getValue(WorkoutFocus.LEGS).due)
    }

    @Test
    fun nothingIsDueWhileAWorkoutIsOpen() {
        val log = FitnessLog(exercises = listOf(bench), workouts = listOf(open(1, 1)))
        assertEquals(emptyList(), dueFocus(log))
    }

    @Test
    fun aWorkoutLeftOpenAndStaleDoesNotHoldBackTheDueDay() {
        val log = FitnessLog(exercises = listOf(bench), workouts = listOf(open(1, 20, WorkoutFocus.CHEST)))
        assertEquals(listOf(WorkoutFocus.BACK), dueFocus(log))
    }

    @Test
    fun nothingIsDueWhenThereAreNoExercisesToTrain() {
        val log = FitnessLog(workouts = listOf(finished(1, WorkoutFocus.CHEST, 5)))
        assertEquals(emptyList(), dueFocus(log))
    }

    // ---- The day cards ------------------------------------------------------------------------

    @Test
    fun aCardSaysWhenItsWorkoutWasLastDoneAndHowManyDaysAgo() {
        val log = FitnessLog(exercises = listOf(bench), workouts = listOf(finished(1, WorkoutFocus.CHEST, 5)))
        val card = build(log).days.single { it.focus == WorkoutFocus.CHEST }
        assertEquals(ago(5), card.lastEpochSeconds)
        assertEquals(5, card.daysAgo)
    }

    @Test
    fun theDaysAgoOfAWorkoutNotYetAWholeDayOldRoundsDown() {
        val log = FitnessLog(exercises = listOf(bench), workouts = listOf(Workout(1, WorkoutFocus.BACK, hoursAgo(30), hoursAgo(29))))
        assertEquals(1, build(log).days.single { it.focus == WorkoutFocus.BACK }.daysAgo)
    }

    @Test
    fun aCardCountsTheExercisesOnItsShelves() {
        val curl = exercise(id = "biceps/curl", name = "Curl", bodyPart = BodyPart.BICEPS, primary = Muscle.BICEPS)
        val pushdown = exercise(id = "triceps/pushdown", name = "Pushdown", bodyPart = BodyPart.TRICEPS, primary = Muscle.TRICEPS)
        val cards = build(FitnessLog(exercises = listOf(bench, squat, curl, pushdown))).days.associateBy { it.focus }
        assertEquals(1, cards.getValue(WorkoutFocus.CHEST).exercises)
        assertEquals(1, cards.getValue(WorkoutFocus.LEGS).exercises)
        assertEquals(2, cards.getValue(WorkoutFocus.ARMS).exercises)
        assertEquals(0, cards.getValue(WorkoutFocus.SHOULDERS).exercises)
    }

    @Test
    fun aCardOffersAtMostThreeLiftsMostRecentlyTrainedFirst() {
        val a = exercise(id = "chest/a", name = "A")
        val b = exercise(id = "chest/b", name = "B")
        val c = exercise(id = "chest/c", name = "C")
        val d = exercise(id = "chest/d", name = "D")
        val log = FitnessLog(
            exercises = listOf(a, b, c, d),
            sets = listOf(
                logged(1, 100.0, 10, ago(1), exerciseId = a.id),
                logged(2, 100.0, 10, ago(5), exerciseId = b.id),
                logged(3, 100.0, 10, ago(3), exerciseId = c.id),
                logged(4, 100.0, 10, ago(9), exerciseId = d.id),
            ),
        )
        val lifts = build(log).days.single { it.focus == WorkoutFocus.CHEST }.lifts
        assertEquals(listOf("A", "C", "B"), lifts.map { it.exercise.name })
    }

    @Test
    fun theWorkoutOrderPutsTheRecentlyTrainedFirstThenTheOnesWithMostHistoryThenByName() {
        val recent = ExerciseBoard(exercise(id = "x/recent", name = "Recent"), last = logged(1, 100.0, 10, ago(2)), setCount = 1)
        val older = ExerciseBoard(exercise(id = "x/older", name = "Older"), last = logged(2, 100.0, 10, ago(9)), setCount = 40)
        val deep = ExerciseBoard(exercise(id = "x/deep", name = "Deep"), setCount = 12)
        val bareB = ExerciseBoard(exercise(id = "x/b", name = "banana"))
        val bareA = ExerciseBoard(exercise(id = "x/a", name = "Apple"))
        val ordered = FitnessBoardBuilder.workoutOrder(listOf(bareB, older, bareA, deep, recent))
        assertEquals(listOf("Recent", "Older", "Deep", "Apple", "banana"), ordered.map { it.exercise.name })
    }

    // ---- The calendar -------------------------------------------------------------------------

    @Test
    fun theCalendarHasADayForEachDayWithLoggedSetsOldestFirst() {
        val day = NOW / DAY
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(
                logged(1, 100.0, 10, ago(2)),
                logged(2, 100.0, 9, ago(2) + 600),
                logged(3, 100.0, 10, ago(5)),
            ),
        )
        assertEquals(listOf(TrainedDay(day - 5, null, 1), TrainedDay(day - 2, null, 2)), build(log).calendar)
    }

    @Test
    fun aCalendarDayTakesItsFocusFromTheWorkoutStartedThatDay() {
        val day = NOW / DAY
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(2), workoutId = 1)),
            workouts = listOf(finished(1, WorkoutFocus.CHEST, 2)),
        )
        assertEquals(listOf(TrainedDay(day - 2, WorkoutFocus.CHEST, 1)), build(log).calendar)
    }

    @Test
    fun theCalendarLeavesOutBenchmarksTheNotesAndSetsOfNoReps() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(fromNotes(1, 100.0, 10), fromNotes(2, 100.0, 10, at = ago(2)), logged(3, 100.0, 0, ago(2))),
        )
        assertEquals(emptyList(), build(log).calendar)
    }

    @Test
    fun theCalendarReadsDaysInTheDevicesOwnTimeZone() {
        // 23:30 UTC on day 20,000.
        val late = 20_000 * DAY + 23 * 3_600L + 1_800
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(logged(1, 100.0, 10, late)))
        assertEquals(20_000L, build(log, offset = 0).calendar.single().epochDay)
        assertEquals(20_001L, build(log, offset = 2 * 3_600).calendar.single().epochDay)
        assertEquals(20_000L, build(log, offset = -7 * 3_600).calendar.single().epochDay)
    }

    @Test
    fun aWorkoutStartedAtTheEdgeOfTheDayLandsOnItsLocalDay() {
        val late = 20_000 * DAY + 23 * 3_600L + 1_800
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, late + 600, workoutId = 1)),
            workouts = listOf(Workout(1, WorkoutFocus.BACK, late, late + 3_600)),
        )
        assertEquals(WorkoutFocus.BACK, build(log, now = late + 7_200, offset = 2 * 3_600).calendar.single().focus)
    }

    // ---- The history --------------------------------------------------------------------------

    @Test
    fun aFinishedWorkoutIsASessionWithItsSetsUnderTheirExercisesInTheOrderTheyWereLogged() {
        val start = ago(2)
        val workout = Workout(7, WorkoutFocus.LEGS, start, start + 3_000)
        val log = FitnessLog(
            exercises = listOf(bench, squat),
            sets = listOf(
                logged(1, 225.0, 5, start + 60, exerciseId = squat.id, workoutId = 7),
                logged(2, 135.0, 8, start + 120, workoutId = 7),
                logged(3, 225.0, 4, start + 180, exerciseId = squat.id, workoutId = 7),
            ),
            workouts = listOf(workout),
        )
        val session = build(log).history.single()
        assertEquals(workout, session.workout)
        assertEquals(WorkoutFocus.LEGS, session.focus)
        assertEquals(NOW / DAY - 2, session.epochDay)
        assertEquals(start, session.startedAtEpochSeconds)
        assertEquals(3_000L, session.durationSeconds)
        assertFalse(session.inProgress)
        assertEquals(listOf(squat.id, bench.id), session.lifts.map { it.exercise.id })
        assertEquals(listOf(1L, 3L), session.lifts.first().sets.map { it.id })
        assertEquals(1L, session.lifts.first().top.id)
        assertEquals(3, session.setCount)
        assertEquals(17, session.repCount)
        assertEquals(225.0 * 5 + 135.0 * 8 + 225.0 * 4, session.pounds)
    }

    @Test
    fun sessionsAreListedTheLatestFirst() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(9), workoutId = 1), logged(2, 100.0, 10, ago(2), workoutId = 2), logged(3, 100.0, 10, ago(5))),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, ago(9), ago(9) + 600), Workout(2, WorkoutFocus.BACK, ago(2), ago(2) + 600)),
        )
        assertEquals(listOf(2L, null, 1L), build(log).history.map { it.workout?.id })
    }

    @Test
    fun theWorkoutInProgressIsMarkedSoAndHasNoLengthYet() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, hoursAgo(1) + 60, workoutId = 1)),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, hoursAgo(1))),
        )
        val session = build(log).history.single()
        assertTrue(session.inProgress)
        assertNull(session.durationSeconds)
        assertEquals(1, session.setCount)
    }

    @Test
    fun setsLoggedWithNoWorkoutOpenAreASessionForEachDay() {
        val log = FitnessLog(
            exercises = listOf(bench, squat),
            sets = listOf(logged(1, 100.0, 10, ago(3)), logged(2, 225.0, 5, ago(3) + 300, exerciseId = squat.id), logged(3, 105.0, 10, ago(1))),
        )
        val history = build(log).history
        assertTrue(history.all { it.workout == null && it.focus == null && it.durationSeconds == null && !it.inProgress })
        assertEquals(listOf(NOW / DAY - 1, NOW / DAY - 3), history.map { it.epochDay })
        assertEquals(listOf(listOf(3L), listOf(1L, 2L)), history.map { session -> session.lifts.flatMap { lift -> lift.sets.map { it.id } } })
        assertEquals(ago(3), history.last().startedAtEpochSeconds)
    }

    @Test
    fun aSetWhoseWorkoutHasGoneIsCountedWithItsDaysLooseSets() {
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(logged(1, 100.0, 10, ago(1), workoutId = 99), logged(2, 100.0, 9, ago(1) + 200)))
        val session = build(log).history.single()
        assertNull(session.workout)
        assertEquals(2, session.setCount)
    }

    @Test
    fun theNotesOwnNumbersAreNoSession() {
        val log = FitnessLog(exercises = listOf(bench), sets = listOf(fromNotes(1, 100.0, 10), fromNotes(2, 110.0, 8, at = ago(4))))
        assertEquals(emptyList(), build(log).history)
    }

    @Test
    fun aWorkoutIsFiledUnderTheLocalDayItBeganWheneverItsSetsWereLogged() {
        val late = 20_000L * DAY + 23 * 3_600L
        val log = FitnessLog(
            exercises = listOf(bench),
            // Begun before midnight, its set logged after it.
            sets = listOf(logged(1, 100.0, 10, late + 2 * 3_600, workoutId = 1)),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, late, late + 3 * 3_600)),
        )
        assertEquals(20_000L, build(log, now = late + DAY, offset = 0).history.single().epochDay)
        assertEquals(20_001L, build(log, now = late + DAY, offset = 2 * 3_600).history.single().epochDay)
    }

    @Test
    fun eachSessionIsFiledAndTimedByTheOffsetAtItsOwnMomentNotTodays() {
        // Half past eleven at night, UTC, in "summer"; the offset is an hour then and none in "winter", when it is looked back on.
        val summer = 20_000L * DAY + 23 * 3_600L + 1_800L
        val winter = summer + 120 * DAY
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, summer + 60, workoutId = 1), logged(2, 100.0, 10, summer + 5 * DAY), logged(3, 100.0, 10, winter - DAY)),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, summer, summer + 600)),
        )
        val offsetAt = { at: Long -> if (at < summer + 60 * DAY) 3_600 else 0 }

        val (late, loose, workout) = FitnessBoardBuilder.build(log, winter, 0, offsetAt).history

        // By today's offset it would be the 20,000th day still; by its own it was already the next.
        assertEquals(20_001L, workout.epochDay)
        assertEquals(3_600, workout.utcOffsetSeconds)
        assertEquals(20_006L, loose.epochDay)
        assertEquals(3_600, loose.utcOffsetSeconds)
        assertEquals(0, late.utcOffsetSeconds)
        assertEquals(20_119L, late.epochDay)
        // Left to itself, the one offset given serves throughout.
        assertEquals(20_000L, build(log, now = winter, offset = 0).history.last().epochDay)
    }

    @Test
    fun aRecordOnAnExerciseSincePutAwayIsStillARecordInTheHistoryAndNowhereElse() {
        val old = exercise(id = "chest/old", name = "Old press", archived = true)
        val log = FitnessLog(
            exercises = listOf(bench, old),
            sets = listOf(
                logged(1, 100.0, 10, ago(9), exerciseId = old.id, workoutId = 1),
                logged(2, 110.0, 10, ago(2), exerciseId = old.id, workoutId = 2),
            ),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, ago(9), ago(9) + 600), Workout(2, WorkoutFocus.CHEST, ago(2), ago(2) + 600)),
        )
        val boards = build(log)
        val latest = boards.history.first()
        assertEquals(old.id, latest.lifts.single().exercise.id)
        assertEquals(mapOf(2L to Record(RecordScope.ALL_TIME, RecordKind.WEIGHT)), latest.lifts.single().records)
        assertEquals(1, latest.recordCount)
        // It has no board, and isn't among the records the other screens count.
        assertEquals(listOf(bench.id), boards.boards.map { it.exercise.id })
        assertEquals(emptyList(), boards.recentRecords)
        assertEquals(0, boards.week.records)
    }

    @Test
    fun aSessionCountsTheRecordsSetInIt() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(9), workoutId = 1), logged(2, 110.0, 10, ago(2), workoutId = 2), logged(3, 110.0, 8, ago(2) + 200, workoutId = 2)),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, ago(9), ago(9) + 600), Workout(2, WorkoutFocus.CHEST, ago(2), ago(2) + 600)),
        )
        val (latest, first) = build(log).history
        assertEquals(mapOf(2L to Record(RecordScope.ALL_TIME, RecordKind.WEIGHT)), latest.lifts.single().records)
        assertEquals(1, latest.recordCount)
        // A first set of an exercise had nothing to beat.
        assertEquals(0, first.recordCount)
    }

    @Test
    fun theVolumeCountsBothHandsAndTheBodyAndLeavesOutWhatIsNotInPounds() {
        val dumbbell = exercise(id = "chest/dumbbell-press", name = "Dumbbell press", loadKind = LoadKind.PER_HAND)
        val calves = exercise(id = "legs/calf-machine", name = "Calf machine", bodyPart = BodyPart.LEGS, loadKind = LoadKind.LEVEL, primary = Muscle.CALVES)
        val log = FitnessLog(
            exercises = listOf(dumbbell, calves, pullUps),
            sets = listOf(
                logged(1, 50.0, 10, ago(1), exerciseId = dumbbell.id, workoutId = 1),
                logged(2, 14.0, 25, ago(1) + 100, exerciseId = calves.id, workoutId = 1),
                logged(3, 20.0, 5, ago(1) + 200, exerciseId = pullUps.id, workoutId = 1, bodyweight = 180.0),
            ),
            workouts = listOf(Workout(1, WorkoutFocus.CHEST, ago(1), ago(1) + 600)),
        )
        val session = build(log).history.single()
        assertEquals(50.0 * 2 * 10 + (180.0 + 20.0) * 5, session.pounds)
        assertEquals(3, session.setCount)
    }

    @Test
    fun aWorkoutWithNoSetsLeftInItIsStillInTheHistory() {
        val session = build(FitnessLog(workouts = listOf(Workout(1, WorkoutFocus.BACK, ago(4), ago(4) + 900)))).history.single()
        assertEquals(emptyList(), session.lifts)
        assertEquals(0.0, session.pounds)
        assertEquals(900L, session.durationSeconds)
    }

    @Test
    fun aSessionIsFoundByItsWorkoutOrForLooseSetsByItsDay() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(2), workoutId = 4), logged(2, 100.0, 10, ago(2) + 3_600)),
            workouts = listOf(Workout(4, WorkoutFocus.CHEST, ago(2), ago(2) + 600)),
        )
        val history = build(log).history
        val day = NOW / DAY - 2
        assertEquals(4L, history.single { it.matches(4, day) }.workout?.id)
        // The workout's own day says nothing: it is known by its id alone.
        assertEquals(4L, history.single { it.matches(4, 0) }.workout?.id)
        assertNull(history.single { it.matches(null, day) }.workout)
        assertTrue(history.none { it.matches(null, day + 1) })
        assertTrue(history.none { it.matches(5, day) })
    }

    // ---- The phase ----------------------------------------------------------------------------

    @Test
    fun withNoPhaseItIsMaintenanceSinceAlways() {
        val phase = build(FitnessLog(exercises = listOf(bench))).phase
        assertEquals(PhaseKind.MAINTAIN, phase.kind)
        assertEquals(0L, phase.startedAtEpochSeconds)
        assertEquals(0, phase.days)
    }

    @Test
    fun thePhaseInForceIsTheLatestOneBegunWhateverOrderTheyAreGivenIn() {
        val log = FitnessLog(phases = listOf(Phase(2, PhaseKind.CUT, ago(10)), Phase(1, PhaseKind.BULK, ago(100))))
        val phase = build(log).phase
        assertEquals(PhaseKind.CUT, phase.kind)
        assertEquals(ago(10), phase.startedAtEpochSeconds)
        assertEquals(10, phase.days)
    }

    @Test
    fun aPhaseThatHasNotBegunYetIsNotInForce() {
        val log = FitnessLog(phases = listOf(Phase(1, PhaseKind.CUT, ago(3)), Phase(2, PhaseKind.BULK, NOW + DAY)))
        assertEquals(PhaseKind.CUT, build(log).phase.kind)
    }

    @Test
    fun thePhasesDaysAreWholeDaysSinceItBegan() {
        val log = FitnessLog(phases = listOf(Phase(1, PhaseKind.BULK, NOW - 3 * DAY - 3_600)))
        assertEquals(3, build(log).phase.days)
    }

    @Test
    fun thePhaseStandingIsTheAverageOfTheLiftsTrainedInItAgainstTheirBests() {
        val log = FitnessLog(
            exercises = listOf(bench, squat, row),
            sets = listOf(
                logged(1, 120.0, 10, ago(30)),
                logged(2, 108.0, 10, ago(2)),
                logged(3, 100.0, 10, ago(30), exerciseId = squat.id),
                logged(4, 100.0, 10, ago(3), exerciseId = squat.id),
                // Last trained before the phase began: left out of the average.
                logged(5, 100.0, 10, ago(20), exerciseId = row.id),
                logged(6, 140.0, 10, ago(40), exerciseId = row.id),
            ),
            phases = listOf(Phase(1, PhaseKind.CUT, ago(10))),
        )
        // 108 against 120 is 0.9, and the squat is level with its best.
        assertEquals(0.95f, assertNotNull(build(log).phase.standing), 1e-4f)
    }

    @Test
    fun thePhaseStandingIsNothingWhenNoLiftHasBeenTrainedInIt() {
        val log = FitnessLog(
            exercises = listOf(bench),
            sets = listOf(logged(1, 100.0, 10, ago(20))),
            phases = listOf(Phase(1, PhaseKind.CUT, ago(10))),
        )
        assertNull(build(log).phase.standing)
    }

    private fun falling(from: Double = 200.0, perDay: Double = 2.0) =
        (0 until 15).map { BodyweightEntry(NOW / DAY - 14 + it, from - perDay * it) }

    @Test
    fun thePaceOfAFallingWeightIsReadAgainstThePhase() {
        val weights = falling()
        assertEquals(PaceVerdict.TOO_FAST, build(FitnessLog(phases = listOf(Phase(1, PhaseKind.CUT, ago(14))), bodyweights = weights)).phase.pace)
        assertEquals(PaceVerdict.TOO_SLOW, build(FitnessLog(phases = listOf(Phase(1, PhaseKind.BULK, ago(14))), bodyweights = weights)).phase.pace)
        assertEquals(PaceVerdict.STEADY, build(FitnessLog(bodyweights = weights)).phase.pace)
    }

    @Test
    fun thePaceIsNothingWithTooFewWeighIns() {
        assertNull(build(FitnessLog(bodyweights = listOf(BodyweightEntry(NOW / DAY, 180.0)))).phase.pace)
        assertNull(build(FitnessLog()).phase.pace)
    }

    @Test
    fun theBodyweightTrendIsPassedThrough() {
        val weights = falling()
        assertEquals(BodyweightTrend.of(weights), build(FitnessLog(bodyweights = weights)).bodyweight)
    }

    // ---- The log is only read ----------------------------------------------------------------

    @Test
    fun buildingTwiceFromTheSameLogGivesTheSameBoards() {
        assertEquals(build(ladderLog), build(ladderLog))
    }

    @Test
    fun buildingLeavesTheLogsListsAsTheyWere() {
        val sets: List<LoggedSet> = listOf(logged(2, 100.0, 10, ago(1)), logged(1, 100.0, 10, ago(3)))
        build(FitnessLog(exercises = listOf(bench), sets = sets))
        assertEquals(listOf(2L, 1L), sets.map { it.id })
    }
}
