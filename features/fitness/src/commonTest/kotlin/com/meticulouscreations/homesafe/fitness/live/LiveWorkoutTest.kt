package com.meticulouscreations.homesafe.fitness.live

import com.meticulouscreations.homesafe.fitness.ActiveWorkout
import com.meticulouscreations.homesafe.fitness.ExerciseBoard
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.WorkoutEntry
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.HeartRecovery
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.Target
import com.meticulouscreations.homesafe.fitness.domain.TargetReason
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.FitnessFormat
import com.meticulouscreations.homesafe.text.TextLoader
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.fitness_focus_chest
import homesafe.shared.generated.resources.fitness_live_first_up_aim
import homesafe.shared.generated.resources.fitness_live_heart_down
import homesafe.shared.generated.resources.fitness_live_heart_zone
import homesafe.shared.generated.resources.fitness_live_lift_aim
import homesafe.shared.generated.resources.fitness_live_lift_last
import homesafe.shared.generated.resources.fitness_live_no_sets
import homesafe.shared.generated.resources.fitness_live_records
import homesafe.shared.generated.resources.fitness_live_resting
import homesafe.shared.generated.resources.fitness_live_resting_after
import homesafe.shared.generated.resources.fitness_live_sets
import homesafe.shared.generated.resources.fitness_live_up_next
import homesafe.shared.generated.resources.fitness_live_up_next_aim
import homesafe.shared.generated.resources.fitness_workout_title
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [LiveWorkout]: the workout in progress as the notification, the Live Activity and the Mac's island show it, and what they say. */
class LiveWorkoutTest {
    private val bench = exercise()
    private val incline = exercise(id = "chest/incline_press", name = "Incline press", loadKind = LoadKind.PER_HAND)
    private val fly = exercise(id = "chest/fly", name = "Cable fly")
    private val squat = exercise(id = "legs/squat", name = "Squat", bodyPart = BodyPart.LEGS)
    private val workout = Workout(id = 3, focus = WorkoutFocus.CHEST, startedAtEpochSeconds = NOW - 600)

    private fun target(weight: Double, reps: Int) = Target(weight, reps, TargetReason.ADD_REP, LoggedSet(0, "", weight, reps, 0))

    private fun set(id: Long, exerciseId: String, at: Long, weight: Double = 185.0, reps: Int = 8) = LoggedSet(id, exerciseId, weight, reps, at, workoutId = workout.id)

    /** A chest day with a bench press set logged and another of incline after it; incline was trained more recently than fly. */
    private fun state(rest: RestTimer? = null, entries: List<WorkoutEntry>? = null) = FitnessUiState(
        loaded = true,
        boards = listOf(
            ExerciseBoard(bench, last = set(90, bench.id, NOW - 86_400 * 7), target = target(190.0, 8)),
            ExerciseBoard(incline, last = set(91, incline.id, NOW - 86_400 * 3), target = target(70.0, 10)),
            ExerciseBoard(fly, last = set(92, fly.id, NOW - 86_400 * 2), target = target(40.0, 12)),
            ExerciseBoard(squat, last = set(93, squat.id, NOW - 86_400), target = target(315.0, 5)),
        ),
        workout = ActiveWorkout(
            workout,
            entries ?: listOf(
                WorkoutEntry(bench.id, listOf(set(1, bench.id, NOW - 400), set(2, bench.id, NOW - 300, reps = 7))),
                WorkoutEntry(incline.id, listOf(set(3, incline.id, NOW - 100, weight = 65.0, reps = 10))),
            ),
        ),
        rest = rest,
    )

    @Test
    fun noWorkoutOpenIsNothingToShow() {
        assertNull(LiveWorkout.of(FitnessUiState(loaded = true), HeartUiState(), onScreen = false))
    }

    @Test
    fun betweenRestsTheLiftInHandIsTheOneLoggedLastAndNextIsTheFirstNotDoneOnTheShelf() {
        val live = LiveWorkout.of(state(), HeartUiState(), onScreen = true)!!
        assertEquals(3, live.workoutId)
        assertEquals((NOW - 600) * 1000, live.startedAtEpochMillis)
        assertEquals(3, live.sets)
        assertEquals(2, live.exercisesDone)
        assertEquals(LiveLift(incline.id, "Incline press", LoadKind.PER_HAND, setsToday = 1, last = LiveSet(65.0, 10), target = LiveSet(70.0, 10)), live.lift)
        // Fly, the one left on the chest shelf; the squat is on another shelf.
        assertEquals(fly.id, live.next?.exerciseId)
        assertEquals(true, live.onScreen)
    }

    @Test
    fun duringARestTheLiftInHandIsTheOneRestedFrom() {
        val rest = RestTimer(NOW * 1000 + 60_000, 120, bench.id)
        val live = LiveWorkout.of(state(rest), HeartUiState(), onScreen = false)!!
        assertEquals(bench.id, live.lift?.exerciseId)
        assertEquals(LiveSet(185.0, 7), live.lift?.last)
        assertEquals(2, live.lift?.setsToday)
        assertEquals(fly.id, live.next?.exerciseId)
    }

    @Test
    fun beforeTheFirstSetThereIsNoLiftInHandAndNextIsWhatTheWorkoutListsFirst() {
        val live = LiveWorkout.of(state(entries = emptyList()), HeartUiState(), onScreen = false)!!
        assertNull(live.lift)
        // The most recently trained comes first, as on the workout's page.
        assertEquals(fly.id, live.next?.exerciseId)
    }

    @Test
    fun theHeartIsShownWithItsZoneAndDuringARestHowFarItHasComeDown() {
        val heart = HeartUiState(bpm = 128, zone = HeartZone.LIGHT, recovery = HeartRecovery(152, 128))
        assertEquals(LiveHeart(128, HeartZone.LIGHT, peakBpm = null), LiveWorkout.of(state(), heart, onScreen = false)?.heart)
        val resting = LiveWorkout.of(state(RestTimer(NOW * 1000 + 60_000, 120, bench.id)), heart, onScreen = false)!!
        assertEquals(LiveHeart(128, HeartZone.LIGHT, peakBpm = 152), resting.heart)
        assertEquals(24, resting.heart?.drop)
        assertNull(LiveWorkout.of(state(), HeartUiState(), onScreen = false)?.heart)
    }

    @Test
    fun thePhaseAndTheRestLeftAreReadOffTheClock() {
        val live = LiveWorkout(1, WorkoutFocus.CHEST, 0, rest = RestTimer(100_000, 100, bench.id))
        assertEquals(LivePhase.RESTING, live.phase(50_000))
        assertEquals(0.5f, live.restLeft(50_000))
        assertEquals(50, live.restSecondsLeft(50_000))
        assertEquals(50, live.restSecondsLeft(50_001 - 1_000 + 999))
        assertEquals("0:50", live.restClock(50_000))
        assertEquals(LivePhase.REST_OVER, live.phase(100_000))
        assertEquals(0f, live.restLeft(120_000))
        assertEquals(0, live.restSecondsLeft(120_000))
        assertEquals(LivePhase.TRAINING, live.copy(rest = null).phase(50_000))
    }

    // ---- What it says -----------------------------------------------------------------------------

    @Test
    fun theLinesNameTheWorkoutWhatItHasComeToTheLiftAndWhatIsNext() {
        val live = LiveWorkout.of(state(RestTimer(NOW * 1000 + 60_000, 120, bench.id)), HeartUiState(bpm = 128, recovery = HeartRecovery(152, 128)), onScreen = false)!!.copy(records = 1)
        val lines = LiveWorkoutLines.of(live)
        assertEquals(UiText.of(Res.string.fitness_workout_title, UiText.of(Res.string.fitness_focus_chest)), lines.title)
        assertEquals(
            UiText.Joined(listOf(UiText.plural(Res.plurals.fitness_live_sets, 3), UiText.plural(Res.plurals.fitness_live_records, 1)), UiText.of(Res.string.common_dot_separator)),
            lines.summary,
        )
        assertEquals(UiText.of(Res.string.fitness_live_resting_after, "Bench press".asUiText()), lines.resting)
        assertEquals(UiText.of(Res.string.fitness_live_lift_last, "Bench press".asUiText(), FitnessFormat.set(LoadKind.WEIGHT, 185.0, 7)), lines.lift)
        assertEquals(UiText.of(Res.string.fitness_live_up_next_aim, "Cable fly".asUiText(), FitnessFormat.set(LoadKind.WEIGHT, 40.0, 12)), lines.next)
        assertEquals(UiText.of(Res.string.fitness_live_heart_down, 128, 24), lines.heart)
    }

    @Test
    fun beforeTheFirstSetItSaysThereAreNoneAndWhatToStartOn() {
        val lines = LiveWorkoutLines.of(LiveWorkout.of(state(entries = emptyList()), HeartUiState(bpm = 90, zone = HeartZone.VERY_LIGHT), onScreen = false)!!)
        assertEquals(UiText.of(Res.string.fitness_live_no_sets), lines.summary)
        assertEquals(UiText.of(Res.string.fitness_live_first_up_aim, "Cable fly".asUiText(), FitnessFormat.set(LoadKind.WEIGHT, 40.0, 12)), lines.next)
        assertEquals(UiText.of(Res.string.fitness_live_resting), lines.resting)
        assertEquals(UiText.of(Res.string.fitness_live_heart_zone, 90, 1), lines.heart)
        assertNull(lines.lift)
    }

    @Test
    fun aLiftNotDoneYetTodayIsGivenItsTargetAndOneWithNoneJustItsName() {
        val lift = LiveLift(bench.id, "Bench press", LoadKind.WEIGHT, target = LiveSet(190.0, 8))
        val base = LiveWorkout(1, WorkoutFocus.CHEST, 0, sets = 2, lift = lift, next = LiveLift(fly.id, "Cable fly", LoadKind.WEIGHT))
        assertEquals(UiText.of(Res.string.fitness_live_lift_aim, "Bench press".asUiText(), FitnessFormat.set(LoadKind.WEIGHT, 190.0, 8)), LiveWorkoutLines.of(base).lift)
        assertEquals("Bench press".asUiText(), LiveWorkoutLines.of(base.copy(lift = lift.copy(target = null))).lift)
        assertEquals(UiText.of(Res.string.fitness_live_up_next, "Cable fly".asUiText()), LiveWorkoutLines.of(base).next)
        assertEquals(UiText.plural(Res.plurals.fitness_live_sets, 2), LiveWorkoutLines.of(base).summary)
    }

    @Test
    fun eachPhaseLeadsWithItsOwnHeadlineAndTheLineThatMattersThen() = runTest {
        val words = LiveWorkoutWords.load(
            LiveWorkoutLines(
                title = "title".asUiText(),
                summary = "summary".asUiText(),
                resting = "resting".asUiText(),
                restOver = "over".asUiText(),
                lift = "lift".asUiText(),
                next = "next".asUiText(),
                heart = null,
            ),
            LiveWorkoutLabels(),
            TextLoader { it.toString() },
        )
        assertEquals("Verbatim(value=title)", words.headline(LivePhase.TRAINING))
        assertEquals("Verbatim(value=lift)", words.detail(LivePhase.TRAINING))
        assertEquals("Verbatim(value=resting)", words.headline(LivePhase.RESTING))
        // While resting, what comes after; once it is over, what to go back to.
        assertEquals("Verbatim(value=next)", words.detail(LivePhase.RESTING))
        assertEquals("Verbatim(value=over)", words.headline(LivePhase.REST_OVER))
        assertEquals("Verbatim(value=lift)", words.detail(LivePhase.REST_OVER))
        assertEquals("Verbatim(value=summary)", words.copy(lift = null, next = null).detail(LivePhase.RESTING))
    }
}
