package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.fitness.HealthAccess
import com.meticulouscreations.homesafe.fitness.HealthUiState
import com.meticulouscreations.homesafe.fitness.domain.BodyBoards
import com.meticulouscreations.homesafe.fitness.domain.DistanceSpan
import com.meticulouscreations.homesafe.fitness.domain.GpsSignal
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.METERS_PER_MILE
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.Runs
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import com.meticulouscreations.homesafe.fitness.domain.SleepStage
import com.meticulouscreations.homesafe.fitness.domain.SleepStageKind
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * What a health store might hold, to draw the body card and the runs from in previews and in the
 * UI tests: a month of nights, resting heart rates and heart-rate variability, a week of steps,
 * and nine runs over six weeks, each with a track to open. It goes through the same code the
 * app's own readings do ([BodyBoards], [Runs]). The numbers are made up, and so is the place.
 */
object HealthFixtures {
    private const val DAY = 86_400L
    val NOW: Long = FitnessFixtures.NOW
    private val today = NOW / DAY

    private val steps = listOf(6_420L, 9_180L, 11_340L, 4_905L, 8_760L, 7_215L, 8_412L).mapIndexed { index, count -> today - 6 + index to count }.toMap()

    /** A night that ended at five past seven on [day], [asleepMinutes] of it asleep, in the cycles a night runs in. */
    private fun night(day: Long, asleepMinutes: Int): SleepSession {
        val cycle = listOf(SleepStageKind.LIGHT to 34, SleepStageKind.DEEP to 21, SleepStageKind.LIGHT to 19, SleepStageKind.REM to 22, SleepStageKind.AWAKE to 6)
        val stages = ArrayList<Pair<SleepStageKind, Int>>()
        var asleep = 0
        var at = 0
        while (asleep < asleepMinutes) {
            val (kind, minutes) = cycle[at++ % cycle.size]
            val taken = if (kind == SleepStageKind.AWAKE) minutes else minOf(minutes, asleepMinutes - asleep)
            stages += kind to taken
            if (kind != SleepStageKind.AWAKE) asleep += taken
        }
        val end = day * DAY + 7 * 3_600 + 5 * 60
        var from = end - stages.sumOf { it.second } * 60L
        return SleepSession(
            from,
            end,
            stages.map { (kind, minutes) -> SleepStage(from, from + minutes * 60L, kind).also { from = it.endEpochSeconds } },
        )
    }

    /** A month of nights; [lastNightMinutes] is how long the one that ended this morning was, null when it hasn't come in. */
    private fun sleep(lastNightMinutes: Int?): List<SleepSession> =
        (today - 29 until today).map { night(it, (400 + 48 * sin(it * 1.7)).roundToInt()) } + listOfNotNull(lastNightMinutes?.let { night(today, it) })

    private fun resting(todays: Double?): List<HealthSample> =
        (today - 29 until today).map { HealthSample(it * DAY + 8 * 3_600, 54 + 2 * sin(it * 1.3)) } + listOfNotNull(todays?.let { HealthSample(today * DAY + 8 * 3_600, it) })

    private fun variability(lastNights: Double?): List<HealthSample> =
        (today - 29 until today).map { HealthSample(it * DAY + 3 * 3_600, 46 + 5 * sin(it * 0.9)) } + listOfNotNull(lastNights?.let { HealthSample(today * DAY + 3 * 3_600, it) })

    private data class Outing(val daysAgo: Int, val hour: Int, val minute: Int, val miles: Double, val pace: Int, val bpm: Int, val treadmill: Boolean = false)

    private val outings = listOf(
        Outing(1, 6, 40, 3.10, 552, 151),
        Outing(3, 17, 35, 5.02, 580, 148),
        Outing(6, 12, 10, 2.00, 528, 156, treadmill = true),
        Outing(9, 6, 55, 4.25, 565, 150),
        Outing(13, 7, 20, 6.21, 595, 146),
        Outing(17, 18, 5, 3.05, 538, 158),
        Outing(24, 6, 30, 3.12, 571, 149),
        Outing(31, 16, 15, 2.50, 602, 144),
        Outing(40, 7, 0, 4.00, 585, 147),
        // What the band called runs that weren't: a walk at lunch, and forty minutes of lunges.
        Outing(2, 12, 15, 1.60, 1_110, 104),
        Outing(4, 18, 30, 0.12, 20_000, 128),
    )

    val records: List<RunRecord> = outings.mapIndexed { index, outing ->
        val start = (today - outing.daysAgo) * DAY + outing.hour * 3_600L + outing.minute * 60L
        val moving = (outing.miles * outing.pace).roundToLong()
        RunRecord(
            id = "run-${index + 1}",
            startEpochSeconds = start,
            endEpochSeconds = start + moving + 45,
            utcOffsetSeconds = 0,
            treadmill = outing.treadmill,
            meters = outing.miles * METERS_PER_MILE,
            activeSeconds = moving,
            calories = outing.miles * 108,
            averageBpm = outing.bpm,
            peakBpm = outing.bpm + 12,
            steps = (moving / 60.0 * 168).roundToLong(),
            climbMeters = if (outing.treadmill) null else outing.miles * 11.5,
            route = when {
                outing.treadmill -> RouteAccess.NONE
                index == 0 -> RouteAccess.READABLE
                else -> RouteAccess.ASK
            },
        )
    }

    /** A run's track: the heart climbing over the first minutes and drifting up from there, the ground covered in half-minutes at a pace that wanders, and a loop to have run it round. */
    fun track(record: RunRecord): RunTrack {
        val seconds = record.activeSeconds ?: (record.endEpochSeconds - record.startEpochSeconds)
        val average = record.averageBpm ?: 150
        val heart = (0 until seconds step 5).map { at ->
            val warm = (at / 180.0).coerceAtMost(1.0)
            HealthSample(record.startEpochSeconds + at, average - 5 - 42 * (1 - warm) + 6 * sin(at * 0.021) + 10.0 * at / seconds)
        }
        // Whole half-minutes only, so the last stretch is as long as the rest.
        val wander = (0 until seconds - 29 step 30).map { 1 + 0.09 * sin(it * 0.0071 + record.startEpochSeconds % 7) }
        val meters = record.meters ?: 0.0
        val distance = wander.mapIndexed { index, share ->
            DistanceSpan(record.startEpochSeconds + index * 30L, record.startEpochSeconds + (index + 1) * 30L, meters * share / wander.sum())
        }
        val route = if (record.route != RouteAccess.READABLE) {
            emptyList()
        } else {
            (0..120).map { step ->
                val turn = 2 * PI * step / 120
                RoutePoint(45.0 + 0.006 * sin(turn) * (1 + 0.3 * sin(3 * turn)), -93.0 + 0.009 * cos(turn) * (1 + 0.2 * cos(2 * turn)))
            }
        }
        return RunTrack(heart, distance, route)
    }

    /**
     * The health store connected and read. [lastNightMinutes] and [variability] are last night's
     * sleep and heart-rate variability, null for a night that hasn't come in; with [opened], that
     * run's page has its track.
     */
    fun connected(lastNightMinutes: Int? = 432, variability: Double? = 49.0, resting: Double? = 53.0, opened: String? = null): HealthUiState {
        val board = Runs.board(records, today, offsetAt = { 0 })
        return HealthUiState(
            access = HealthAccess.ON,
            granted = HealthKind.entries.toSet(),
            loaded = true,
            nowEpochSeconds = NOW,
            body = BodyBoards.build(NOW, { 0 }, steps, sleep(lastNightMinutes), resting(resting), variability(variability)),
            runs = board,
            detail = opened?.let { id -> Runs.detail(board.runs.first { it.id == id }, track(records.first { it.id == id }), HeartZones.bounds(FitnessFixtures.heartProfile)) },
        )
    }

    /** A short night after a hard day: readiness low. */
    fun runDown(): HealthUiState = connected(lastNightMinutes = 335, variability = 39.0, resting = 57.5)

    /** The morning before the band has synced: nothing from last night yet. */
    fun waiting(): HealthUiState = connected(lastNightMinutes = null, variability = null, resting = null)

    /** Health Connect there, and the app not yet let in; with [refused], after Android has stopped asking. */
    fun off(refused: Boolean = false): HealthUiState = HealthUiState(access = HealthAccess.OFF, refused = refused)

    val missing = HealthUiState(access = HealthAccess.MISSING)

    /**
     * A run being recorded with the phone's GPS: 2.14 miles in 19:40 of moving, most of the way
     * round the loop, [paused] or going at a 9:05 mile just now.
     */
    fun recording(paused: Boolean = false): Recording {
        val loop = (0..96).map { step ->
            val turn = 2 * PI * step / 120
            RoutePoint(45.0 + 0.006 * sin(turn) * (1 + 0.3 * sin(3 * turn)), -93.0 + 0.009 * cos(turn) * (1 + 0.2 * cos(2 * turn)))
        }
        val startedAt = NOW * 1_000 - 1_260_000
        return Recording(
            id = "app-preview",
            startEpochMillis = startedAt,
            paused = paused,
            meters = 2.14 * METERS_PER_MILE,
            movingMillis = 1_180_000,
            asOfMillis = NOW * 1_000,
            paceSeconds = if (paused) null else 545,
            signal = GpsSignal.GOOD,
            route = loop,
        )
    }

    /** Connected with only exercise shared: runs, and nothing for the body card. */
    fun runsOnly(): HealthUiState = connected().let { it.copy(granted = setOf(HealthKind.EXERCISE), body = BodyBoards.build(NOW, { 0 })) }
}

@Preview(widthDp = 412, heightDp = 1700)
@Composable
private fun FitnessTodayBodyPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), health = HealthFixtures.connected()) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun FitnessTodayBodyRunDownPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), health = HealthFixtures.runDown()) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun FitnessTodayBodyWaitingPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), health = HealthFixtures.waiting()) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun FitnessTodayBodyConnectPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), health = HealthFixtures.off(refused = true)) }
}

@Preview(widthDp = 412, heightDp = 1700)
@Composable
private fun FitnessRunsPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Runs, health = HealthFixtures.connected().copy(canRecord = true)) }
}

@Preview(widthDp = 412, heightDp = 700)
@Composable
private fun FitnessRunsRecordingPreview() {
    FrigateTheme {
        FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Runs, health = HealthFixtures.connected().copy(canRecord = true), recording = HealthFixtures.recording())
    }
}

@Preview(widthDp = 412, heightDp = 700)
@Composable
private fun FitnessRunsNotSharedPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Runs, health = HealthFixtures.off().copy(canRecord = true, locationRefused = true)) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessRecordingPreview() {
    FrigateTheme { FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Recording, health = HealthFixtures.connected().copy(canRecord = true), recording = HealthFixtures.recording()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessRecordingPausedPreview() {
    FrigateTheme {
        FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Recording, health = HealthFixtures.connected().copy(canRecord = true), recording = HealthFixtures.recording(paused = true))
    }
}

@Preview(widthDp = 412, heightDp = 1900)
@Composable
private fun FitnessRunPreview() {
    FrigateTheme {
        FitnessAppContent(FitnessFixtures.state(), FitnessActions(), initialPage = FitnessPage.Run("run-1"), heart = FitnessFixtures.heartAtRest(), health = HealthFixtures.connected(opened = "run-1"))
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FitnessHealthRationalePreview() {
    FrigateTheme { HealthRationale(onDone = {}) }
}
