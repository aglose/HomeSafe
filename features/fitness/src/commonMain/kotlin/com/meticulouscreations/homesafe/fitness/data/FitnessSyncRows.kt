package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessBodyweightEntity
import com.meticulouscreations.homesafe.data.FitnessExerciseEntity
import com.meticulouscreations.homesafe.data.FitnessHeartSummaryEntity
import com.meticulouscreations.homesafe.data.FitnessLogGone
import com.meticulouscreations.homesafe.data.FitnessLogRows
import com.meticulouscreations.homesafe.data.FitnessPhaseEntity
import com.meticulouscreations.homesafe.data.FitnessRecordedRunEntity
import com.meticulouscreations.homesafe.data.FitnessRunEventEntity
import com.meticulouscreations.homesafe.data.FitnessSetEntity
import com.meticulouscreations.homesafe.data.FitnessWorkoutEntity
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A row of the log or a recorded run as it is kept on the server, which outlives any one version
 * of the app: these names are a format, and aren't the database's, so that either can change
 * apart. Each has a default, so a row from a later or earlier version still reads.
 *
 * A row's text here is the same whenever it says the same thing, so rows can be told apart by
 * comparing it: what this device has against what the server last had. A recorded run is compared
 * without its positions, which never change once it is finished and are many ([forComparing]).
 */
internal object FitnessSyncRows {
    const val EXERCISE = "exercise"
    const val SET = "set"
    const val WORKOUT = "workout"
    const val PHASE = "phase"
    const val WEIGH_IN = "weigh_in"
    const val HEART_PROFILE = "heart_profile"
    const val HEART_SUMMARY = "heart_summary"
    const val RUN = "run"

    /** The heart-rate profile's one row. The chosen sensor isn't in it: that is this device's radio talking to a band, and means nothing on another. */
    private const val ONLY = "me"

    /** Parents before what hangs off them, so that a device that hears part of a change never sees a set with no exercise to show under. */
    val ORDER = listOf(EXERCISE, WORKOUT, PHASE, WEIGH_IN, HEART_PROFILE, HEART_SUMMARY, SET, RUN)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun key(kind: String, id: String) = "$kind/$id"

    fun kindOf(key: String) = key.substringBefore('/')

    fun idOf(key: String) = key.substringAfter('/')

    fun isLog(key: String) = kindOf(key) != RUN

    /** Every row of the log, by key, as text. */
    fun of(rows: FitnessLogRows, profile: HeartProfile): Map<String, String> = buildMap {
        rows.exercises.forEach { put(key(EXERCISE, it.id), text(ExerciseRow.serializer(), it.toRow())) }
        rows.sets.forEach { put(key(SET, it.id.toString()), text(SetRow.serializer(), it.toRow())) }
        rows.workouts.forEach { put(key(WORKOUT, it.id.toString()), text(WorkoutRow.serializer(), WorkoutRow(it.focus, it.startedAtEpochSeconds, it.finishedAtEpochSeconds))) }
        rows.phases.forEach { put(key(PHASE, it.id.toString()), text(PhaseRow.serializer(), PhaseRow(it.kind, it.startedAtEpochSeconds))) }
        rows.bodyweights.forEach { put(key(WEIGH_IN, it.epochDay.toString()), text(WeighInRow.serializer(), WeighInRow(it.pounds))) }
        rows.heartSummaries.forEach { put(key(HEART_SUMMARY, it.workoutId.toString()), text(HeartSummaryRow.serializer(), it.toRow())) }
        if (profile != HeartProfile()) put(key(HEART_PROFILE, ONLY), text(HeartProfileRow.serializer(), HeartProfileRow(profile.maxBpm, profile.age, profile.restingBpm)))
    }

    /** A finished run, by key, as text without its positions. */
    fun of(run: FitnessRecordedRunEntity): Pair<String, String> = key(RUN, run.id) to text(RunRow.serializer(), run.toRow(emptyList()))

    /** The same [run] as what is sent: with its positions. */
    fun forSending(run: FitnessRecordedRunEntity, events: List<FitnessRunEventEntity>): JsonObject =
        parse(text(RunRow.serializer(), run.toRow(events.map { RunEventRow(it.kind, it.epochMillis, it.latitude, it.longitude, it.accuracyMeters) })))

    fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    /**
     * What a row from the server says, in the same text as [of] would give it, and for a run without
     * its positions. Null when it can't be read, which is left alone: a row of a kind this build
     * hasn't heard of, or from one that wrote what can't be understood.
     */
    fun forComparing(kind: String, data: JsonObject): String? = runCatching {
        when (kind) {
            EXERCISE -> text(ExerciseRow.serializer(), read(ExerciseRow.serializer(), data))
            SET -> text(SetRow.serializer(), read(SetRow.serializer(), data))
            WORKOUT -> text(WorkoutRow.serializer(), read(WorkoutRow.serializer(), data))
            PHASE -> text(PhaseRow.serializer(), read(PhaseRow.serializer(), data))
            WEIGH_IN -> text(WeighInRow.serializer(), read(WeighInRow.serializer(), data))
            HEART_PROFILE -> text(HeartProfileRow.serializer(), read(HeartProfileRow.serializer(), data))
            HEART_SUMMARY -> text(HeartSummaryRow.serializer(), read(HeartSummaryRow.serializer(), data))
            RUN -> text(RunRow.serializer(), read(RunRow.serializer(), data).copy(events = emptyList()))
            else -> null
        }
    }.getOrNull()

    /** What came from the server for the log: rows as the database holds them, and the deleted ones' ids. */
    class LogWrite(val rows: FitnessLogRows, val profile: HeartProfile?, val gone: FitnessLogGone) {
        val isEmpty: Boolean get() = rows == FitnessLogRows() && profile == null && gone == FitnessLogGone()
    }

    /** [written] is rows by key, read by [forComparing]; [deleted] the keys deleted. Anything but the log's is passed over. */
    fun logWrite(written: Map<String, JsonObject>, deleted: Collection<String>): LogWrite {
        val exercises = ArrayList<FitnessExerciseEntity>()
        val sets = ArrayList<FitnessSetEntity>()
        val workouts = ArrayList<FitnessWorkoutEntity>()
        val phases = ArrayList<FitnessPhaseEntity>()
        val bodyweights = ArrayList<FitnessBodyweightEntity>()
        val summaries = ArrayList<FitnessHeartSummaryEntity>()
        var profile: HeartProfile? = null
        for ((key, data) in written) {
            val id = idOf(key)
            when (kindOf(key)) {
                EXERCISE -> exercises += read(ExerciseRow.serializer(), data).toEntity(id)
                SET -> id.toLongOrNull()?.let { sets += read(SetRow.serializer(), data).toEntity(it) }
                WORKOUT -> id.toLongOrNull()?.let { read(WorkoutRow.serializer(), data).run { workouts += FitnessWorkoutEntity(it, focus, start, end) } }
                PHASE -> id.toLongOrNull()?.let { read(PhaseRow.serializer(), data).run { phases += FitnessPhaseEntity(it, kind, start) } }
                WEIGH_IN -> id.toLongOrNull()?.let { bodyweights += FitnessBodyweightEntity(it, read(WeighInRow.serializer(), data).pounds) }
                HEART_SUMMARY -> id.toLongOrNull()?.let { summaries += read(HeartSummaryRow.serializer(), data).toEntity(it) }
                HEART_PROFILE -> profile = read(HeartProfileRow.serializer(), data).run { HeartProfile(max, age, resting) }
            }
        }
        val goneKeys = deleted.groupBy(::kindOf, ::idOf)
        fun longs(kind: String) = goneKeys[kind].orEmpty().mapNotNull { it.toLongOrNull() }
        return LogWrite(
            FitnessLogRows(exercises, sets, workouts, phases, bodyweights, summaries),
            // A profile deleted is every number in it taken away, on every device.
            profile ?: HeartProfile().takeIf { HEART_PROFILE in goneKeys },
            FitnessLogGone(goneKeys[EXERCISE].orEmpty(), longs(SET), longs(WORKOUT), longs(PHASE), longs(WEIGH_IN), longs(HEART_SUMMARY)),
        )
    }

    /** A run from the server as the database holds it, with its positions. */
    fun run(id: String, data: JsonObject): Pair<FitnessRecordedRunEntity, List<FitnessRunEventEntity>> {
        val row = read(RunRow.serializer(), data)
        return FitnessRecordedRunEntity(id, row.start, row.end, row.offset, row.meters, row.moving) to
            row.events.mapIndexed { seq, event -> FitnessRunEventEntity(id, seq, event.kind, event.at, event.latitude, event.longitude, event.accuracy) }
    }

    private fun <T> text(serializer: KSerializer<T>, row: T): String = json.encodeToString(serializer, row)

    private fun <T> read(serializer: KSerializer<T>, data: JsonObject): T = json.decodeFromJsonElement(serializer, data)

    private fun FitnessExerciseEntity.toRow() = ExerciseRow(name, bodyPart, equipment, loadKind, primaryMuscle, secondaryMuscles, repLow, repHigh, increment, restSeconds, note, archived)

    private fun ExerciseRow.toEntity(id: String) = FitnessExerciseEntity(id, name, bodyPart, equipment, loadKind, primaryMuscle, secondaryMuscles, repLow, repHigh, increment, restSeconds, note, archived)

    private fun FitnessSetEntity.toRow() = SetRow(exerciseId, workoutId, epochSeconds, weight, reps, bodyweight, note, imported)

    private fun SetRow.toEntity(id: Long) = FitnessSetEntity(id, exercise, workout, at, weight, reps, bodyweight, note, imported)

    private fun FitnessHeartSummaryEntity.toRow() = HeartSummaryRow(belowMillis, listOf(zone1Millis, zone2Millis, zone3Millis, zone4Millis, zone5Millis), bpmMillis, peakBpm)

    private fun HeartSummaryRow.toEntity(workoutId: Long) = FitnessHeartSummaryEntity(
        workoutId = workoutId,
        belowMillis = below,
        zone1Millis = zones.getOrElse(0) { 0L },
        zone2Millis = zones.getOrElse(1) { 0L },
        zone3Millis = zones.getOrElse(2) { 0L },
        zone4Millis = zones.getOrElse(3) { 0L },
        zone5Millis = zones.getOrElse(4) { 0L },
        bpmMillis = bpmMillis,
        peakBpm = peak,
    )

    private fun FitnessRecordedRunEntity.toRow(events: List<RunEventRow>) = RunRow(startEpochMillis, endEpochMillis, utcOffsetSeconds, meters, movingMillis, events)

    @Serializable
    private data class ExerciseRow(
        val name: String = "",
        val bodyPart: String = "",
        val equipment: String = "",
        val loadKind: String = "",
        val primaryMuscle: String = "",
        val secondaryMuscles: String = "",
        val repLow: Int = 8,
        val repHigh: Int = 12,
        val increment: Double = 5.0,
        val restSeconds: Int = 120,
        val note: String = "",
        val archived: Boolean = false,
    )

    @Serializable
    private data class SetRow(
        val exercise: String = "",
        val workout: Long? = null,
        val at: Long = 0,
        val weight: Double = 0.0,
        val reps: Int = 0,
        val bodyweight: Double? = null,
        val note: String = "",
        val imported: Boolean = false,
    )

    @Serializable
    private data class WorkoutRow(val focus: String = "", val start: Long = 0, val end: Long? = null)

    @Serializable
    private data class PhaseRow(val kind: String = "", val start: Long = 0)

    @Serializable
    private data class WeighInRow(val pounds: Double = 0.0)

    @Serializable
    private data class HeartProfileRow(val max: Int? = null, val age: Int? = null, val resting: Int? = null)

    @Serializable
    private data class HeartSummaryRow(val below: Long = 0, val zones: List<Long> = emptyList(), val bpmMillis: Long = 0, val peak: Int = 0)

    @Serializable
    private data class RunRow(
        val start: Long = 0,
        val end: Long? = null,
        val offset: Int = 0,
        val meters: Double = 0.0,
        val moving: Long = 0,
        val events: List<RunEventRow> = emptyList(),
    )

    /** One to a second of a run, so under short names. */
    @Serializable
    private data class RunEventRow(
        @SerialName("k") val kind: String = "",
        @SerialName("t") val at: Long = 0,
        @SerialName("a") val latitude: Double = 0.0,
        @SerialName("o") val longitude: Double = 0.0,
        @SerialName("c") val accuracy: Double = 0.0,
    )
}
