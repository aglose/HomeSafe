package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.fitness.FitnessBoardBuilder
import com.meticulouscreations.homesafe.fitness.FitnessLog
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.ImportState
import com.meticulouscreations.homesafe.fitness.RecordFlash
import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.WorkoutHeart
import com.meticulouscreations.homesafe.fitness.ZoneNotice
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartRecovery
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.HeartSettings
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.NotesImport
import com.meticulouscreations.homesafe.fitness.domain.NotesParser
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.RecordKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.SensorSighting
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import kotlin.math.sin

/**
 * A training log to draw the fitness screens from, in previews and in the UI tests: a few notes
 * read by the real parser, nine weeks of sessions logged on top of them, a bulk that turned into
 * a cut five weeks ago, and the weigh-ins to go with it. The numbers are made up.
 */
internal object FitnessFixtures {
    const val DAY = 86_400L

    /** Five in the afternoon on 8 October 2026, UTC. */
    val NOW: Long = NotesParser.epochDay(2026, 10, 8) * DAY + 17 * 3_600L

    const val NOTES = """Legs

Hack squat
- 180lbs - 12 reps
- 200lbs - 12 reps
- 220lbs - 11 reps
- 250lbs - 11 reps
- 270lbs - 12 reps
- 300lbs (3plates+15lbs)- 10 reps

Leg press
- 3 plates - 13 reps
- 4 plates - 12 reps
- 4 plates + 25lbs - 12 reps

Leg extension
- 170lbs - 14 reps
- 180lbs - 12 reps
- 190lbs - 12 reps
- 200lbs - 10 reps

Leg curl
- 120lbs - 13 reps
- 130lbs - 12 reps
- 140lbs - 11 reps

Calf machine
- 14 - 29 reps
- 15 - 30 reps
- 16 - 25 reps

Chest

Incline press machine
- 90lbs - 12 reps
- 110lbs - 14 reps
- 140lbs - 12 reps
- 160lbs - 12 reps
- 170lbs - 9 reps

Chest fly machine
- 100lbs - 12 reps
- 110lbs - 12 reps
- 120lbs - 12 reps

Incline dumbbell
- 50lbs x 2 - 16 reps
- 60lbs x 2 - 14 reps
- 70lbs x 2 - 12 reps

Shoulders

Shoulder press machine
- 120lbs - 14 reps
- 130lbs - 13 reps
- 140lbs - 11 reps

Middle delt cable
- 12.5lbs - 20 reps
- 15lbs - 21 reps
- 17.5lbs - 18 reps

Back

Lat pulldown (strict)
- 170lbs - 13 reps
- 187lbs - 12 reps
- 204lbs - 9 reps

High row
- 140lbs - 14 reps
- 160lbs - 12 reps
- 180lbs - 11 reps

Weighted pullups
- 25lbs - 11 reps - bodyweight 180lb
- 40lbs - 8 reps - bodyweight 178lb

Bis & Tris
Tris

Triangle bar cable
- 70lbs - 15 reps
- 77lbs - 14 reps

Bis

Hammer curls
- 35lbs - 18 reps
- 40lbs - 14 reps
- 45lbs - 12 reps
"""

    private val cutStart = NOW - 34 * DAY

    val log: FitnessLog by lazy { build(working = false) }
    private val workingLog: FitnessLog by lazy { build(working = true) }

    val empty = FitnessUiState(loaded = true, nowEpochSeconds = NOW)

    /** The app as it stands on an ordinary evening; with [working], twenty-five minutes into a legs day. */
    fun state(working: Boolean = false, resting: Boolean = working, flash: Boolean = false): FitnessUiState {
        val source = if (working) workingLog else log
        val boards = FitnessBoardBuilder.build(source, NOW, 0)
        val hack = source.exercises.first { it.name == "Hack squat" }
        return FitnessUiState(
            loaded = true,
            nowEpochSeconds = NOW,
            phase = boards.phase,
            phases = source.phases,
            boards = boards.boards,
            days = boards.days,
            workout = boards.workout,
            rest = if (resting) RestTimer(NOW * 1000 + 96_000, 150, hack.id) else null,
            flash = if (flash) RecordFlash(1, hack.name, source.sets.last { it.exerciseId == hack.id }, Record(RecordScope.PHASE, RecordKind.ESTIMATE), hack.loadKind) else null,
            week = boards.week,
            recentRecords = boards.recentRecords,
            bodyweight = boards.bodyweight,
            calendar = boards.calendar,
        )
    }

    /** The notes part-way through being brought in. */
    fun importing(): FitnessUiState {
        val text = NOTES.substringBefore("Chest").trim() + "\n\nSissy squat\n- 25lbs - 12 reps\n??? what was this"
        return empty.copy(import = ImportState(text = text, plan = NotesImport.plan(NotesParser.parse(text), log.exercises.take(3), log.sets.take(12), NOW)))
    }

    fun exercise(name: String): Exercise = log.exercises.first { it.name == name }

    /** A band for the heart-rate screens: the name such a band gives itself, at an address that is nobody's. */
    val band = HeartSensor("AA:BB:CC:00:00:01", "Fitbit Air")

    /** Someone else's strap across the room, for the list a search turns up. */
    val strap = HeartSensor("AA:BB:CC:00:00:02", "Chest Strap")
    val heartProfile = HeartProfile(maxBpm = 190)
    private val bounds = HeartZones.bounds(heartProfile)

    /** Twenty-three minutes of a workout's heart: mostly easy, with a few minutes worked hard. */
    val heartSummary = HeartSummary(
        belowMillis = 3 * 60_000L,
        zoneMillis = listOf(6 * 60_000L, 9 * 60_000L, 4 * 60_000L, 60_000L, 0L),
        bpmMillis = 23 * 60_000L * 118,
        peakBpm = 158,
    )

    /**
     * The heart on a workout: the band linked and reading [bpm] (null: linked, or with another
     * [sensor] state not linked, and nothing to read). With [notice] it has just settled into
     * the zone it is in; with [resting] it is coming down from a set.
     */
    fun heart(bpm: Int? = 142, notice: Boolean = false, resting: Boolean = false, sensor: HeartSensorState? = null): HeartUiState {
        val zone = bpm?.let { bounds?.zoneOf(it) }
        return HeartUiState(
            sensor = sensor ?: HeartSensorState.Connected(band, bpm, NOW * 1000),
            settings = HeartSettings(heartProfile, band),
            bounds = bounds,
            bpm = bpm,
            zone = zone,
            notice = if (notice) ZoneNotice(1, HeartZone.LIGHT, zone) else null,
            recovery = if (resting && bpm != null) HeartRecovery(bpm + 24, bpm) else null,
            summary = heartSummary,
        )
    }

    /** Away from a workout, the band chosen but not being listened to, and the last workout's heart to look back on. */
    fun heartAtRest(): HeartUiState =
        HeartUiState(sensor = HeartSensorState.Off, settings = HeartSettings(heartProfile, band), bounds = bounds, last = WorkoutHeart(log.workouts.last(), heartSummary))

    /** Before any band has been chosen: Bluetooth there to be used, and [sensor] what it is doing. */
    fun heartUnset(sensor: HeartSensorState = HeartSensorState.Off): HeartUiState = HeartUiState(sensor = sensor)

    /** A search that has turned up the band and a stranger's strap. */
    fun heartSearching(): HeartUiState = heartUnset(HeartSensorState.Searching(listOf(SensorSighting(band, -52), SensorSighting(strap, -84))))

    private fun build(working: Boolean): FitnessLog {
        val plan = NotesImport.plan(NotesParser.parse(NOTES), emptyList(), emptyList(), NOW)
        val exercises = plan.exercises.map { it.exercise }
        val sets = ArrayList<LoggedSet>()
        var id = 0L
        plan.exercises.forEach { item -> item.sets.forEach { sets += LoggedSet(++id, it.exerciseId, it.weight, it.reps, it.epochSeconds, bodyweight = it.bodyweight, imported = true) } }
        val workouts = ArrayList<Workout>()
        var workoutId = 0L

        fun idOf(name: String) = exercises.first { it.name == name }.id

        // Nine weeks of the split: legs, then chest two days on, then back two days after that.
        val week = listOf(
            Triple(WorkoutFocus.LEGS, 6, listOf("Hack squat" to (250.0 to 20.0), "Leg extension" to (180.0 to 10.0), "Leg curl" to (120.0 to 10.0), "Calf machine" to (14.0 to 1.0))),
            Triple(WorkoutFocus.CHEST, 4, listOf("Incline press machine" to (140.0 to 20.0), "Chest fly machine" to (100.0 to 10.0), "Shoulder press machine" to (120.0 to 10.0), "Triangle bar cable" to (70.0 to 7.0))),
            Triple(WorkoutFocus.BACK, 2, listOf("Lat pulldown (strict)" to (170.0 to 17.0), "High row" to (140.0 to 20.0), "Hammer curls" to (35.0 to 5.0))),
        )
        for (weeksAgo in 8 downTo 0) {
            for ((focus, daysAgo, lifts) in week) {
                val at = NOW - (weeksAgo * 7 + daysAgo) * DAY - 3_600
                val session = Workout(++workoutId, focus, at, at + 3_300)
                workouts += session
                lifts.forEachIndexed { index, (name, load) ->
                    val (start, step) = load
                    // Up a rung every third week of the bulk; in the cut the weight holds and a rep or two goes.
                    val cutting = at >= cutStart
                    val rung = if (cutting) 2 else (8 - weeksAgo) / 2
                    val reps = (if (cutting) 11 - (4 - weeksAgo).coerceAtLeast(0) / 3 else 10 + (8 - weeksAgo) % 2 * 2) + if (name == "Calf machine") 15 else 0
                    sets += LoggedSet(++id, idOf(name), start + step * rung, reps, at + index * 540L, workoutId = session.id)
                    sets += LoggedSet(++id, idOf(name), start + step * rung, reps - 2, at + index * 540L + 200, workoutId = session.id)
                }
            }
        }
        if (working) {
            val session = Workout(++workoutId, WorkoutFocus.LEGS, NOW - 25 * 60, null)
            workouts += session
            sets += LoggedSet(++id, idOf("Leg extension"), 200.0, 11, NOW - 20 * 60, workoutId = session.id)
            sets += LoggedSet(++id, idOf("Leg extension"), 200.0, 9, NOW - 16 * 60, workoutId = session.id)
            sets += LoggedSet(++id, idOf("Hack squat"), 290.0, 11, NOW - 8 * 60, workoutId = session.id)
        }
        val today = NOW / DAY
        val weights = (70 downTo 0).filter { it % 3 != 1 }.map { daysAgo ->
            // A slow bulk to 183, then the cut taking a little over a pound a week off it, with a day's water on top.
            val trend = if (daysAgo > 34) 183.0 - (daysAgo - 34) * 0.05 else 183.0 - (34 - daysAgo) * 0.17
            BodyweightEntry(today - daysAgo, ((trend + sin(daysAgo * 1.7) * 0.9) * 10).toInt() / 10.0)
        }
        return FitnessLog(
            exercises = exercises,
            sets = sets,
            workouts = workouts,
            phases = listOf(Phase(1, PhaseKind.BULK, NOW - 150 * DAY), Phase(2, PhaseKind.CUT, cutStart)),
            bodyweights = weights,
        )
    }
}

// A handful of whole screens, one for each place the app can be. The forge and the fibres are
// drawn by shaders, which the preview renderers run on the CPU, a frame at a time.
@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessTodayPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessTodayEmptyPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.empty, FitnessActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessTodayWorkingPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(working = true, resting = false), FitnessActions()) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessWorkoutPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(working = true), FitnessActions(), initialPage = FitnessPage.Workout) }
}

// The heart rate, where there is a sensor: the workout with it live and a rest counting, the moment a zone changes,
// the link lost, and the page it is set up on.
@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessWorkoutHeartPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(working = true), FitnessActions(), initialPage = FitnessPage.Workout, heart = FitnessFixtures.heart(resting = true)) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessWorkoutZoneChangePreview() {
    FrigateTheme {
        FitnessAppContent(FitnessFixtures.state(working = true, resting = false), FitnessActions(), initialPage = FitnessPage.Workout, heart = FitnessFixtures.heart(bpm = 156, notice = true))
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessWorkoutHeartLostPreview() {
    FrigateTheme {
        FitnessAppContent(
            FitnessFixtures.state(working = true, resting = false),
            FitnessActions(),
            initialPage = FitnessPage.Workout,
            heart = FitnessFixtures.heart(bpm = null, sensor = HeartSensorState.Lost(FitnessFixtures.band)),
        )
    }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessHeartPagePreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Heart, heart = FitnessFixtures.heart(bpm = 96)) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessHeartSearchPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Heart, heart = FitnessFixtures.heartSearching()) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessTodayHeartPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), heart = FitnessFixtures.heartAtRest()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessRecordPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(working = true, flash = true), FitnessActions(), initialPage = FitnessPage.Workout) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessLiftsPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialTab = FitnessTab.LIFTS) }
}

@Preview(widthDp = 412, heightDp = 1900)
@Composable
private fun FitnessExercisePreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Lift(FitnessFixtures.exercise("Hack squat").id)) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessEditExercisePreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Edit(FitnessFixtures.exercise("Leg press").id)) }
}

@Preview(widthDp = 412, heightDp = 2300)
@Composable
private fun FitnessProgressPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialTab = FitnessTab.PROGRESS) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun FitnessImportPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.importing(), FitnessActions(), initialPage = FitnessPage.Import) }
}
