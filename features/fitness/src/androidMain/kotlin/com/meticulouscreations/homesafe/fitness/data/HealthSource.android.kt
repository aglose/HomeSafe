package com.meticulouscreations.homesafe.fitness.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.DistanceSpan
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import com.meticulouscreations.homesafe.fitness.domain.SleepStage
import com.meticulouscreations.homesafe.fitness.domain.SleepStageKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import kotlin.reflect.KClass

private const val TAG = "HomeSafeHealth"

/** Health Connect as an app of its own, which is what it is before Android 14. */
private const val PROVIDER = "com.google.android.apps.healthdata"

/** Android 14's own page of one app's health permissions (`HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS`). */
private const val ACTION_MANAGE_PERMISSIONS = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"

/** The permission that goes with each kind the app reads. All are declared in the manifest. */
private val PERMISSIONS: Map<HealthKind, String> = mapOf(
    HealthKind.STEPS to HealthPermission.getReadPermission(StepsRecord::class),
    HealthKind.SLEEP to HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthKind.HEART_RATE to HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthKind.RESTING_HEART_RATE to HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    HealthKind.HEART_RATE_VARIABILITY to HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    HealthKind.EXERCISE to HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthKind.DISTANCE to HealthPermission.getReadPermission(DistanceRecord::class),
    HealthKind.CALORIES to HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthKind.ELEVATION to HealthPermission.getReadPermission(ElevationGainedRecord::class),
)

/**
 * Health Connect, read. The Google Health app writes what a Fitbit band records into it (once
 * that is switched on under Google Health's Connections), and this reads it back out: nothing is
 * written, and nothing read leaves the phone.
 *
 * A day's steps come from Health Connect's own aggregation over every app, which counts a step
 * once when both the phone and the band saw it. A run is read from the app that recorded it,
 * its totals and its track alike, so that another tracker worn at the same time isn't added in
 * or averaged with it; what that app recorded none of is taken from whoever did.
 *
 * Without the history permission Health Connect only hands over the thirty days before the app
 * was first allowed in, and whatever has been recorded since. It is asked for with the rest where
 * the device offers it.
 *
 * What it does is logged under `HomeSafeHealth`, without any of the readings.
 */
private class HealthConnectSource(private val context: Context, activity: FragmentActivity?) : HealthSource {
    private var client: HealthConnectClient? = null
    private var pendingRequest: CompletableDeferred<Unit>? = null
    private var pendingRoute: CompletableDeferred<ExerciseRoute?>? = null

    /** A run's totals as last added up, by its id and when it was last changed: adding one up is a call to Health Connect each. */
    private val totals = HashMap<String, RunRecord>()

    // Registered on the registry directly, as HeartRateLink.android.kt does: the graph is built
    // too late in onCreate for the lifecycle-bound register().
    private val permissionLauncher = activity?.activityResultRegistry?.register(
        "homesafe.health",
        PermissionController.createRequestPermissionResultContract(),
    ) {
        pendingRequest?.complete(Unit)
        pendingRequest = null
    }

    private val routeLauncher = activity?.activityResultRegistry?.register("homesafe.health.route", ExerciseRouteRequestContract()) { route ->
        pendingRoute?.complete(route)
        pendingRoute = null
    }

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE

        // There to be had: not installed yet, or too old.
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.MISSING

        // Not for this device at all (a profile it isn't offered in, say): there is nothing to send anyone off to get.
        else -> HealthAvailability.UNSUPPORTED
    }

    /** Asked for afresh until there is one: Health Connect may be installed while the app is open. */
    private fun connected(): HealthConnectClient = client ?: HealthConnectClient.getOrCreate(context).also { client = it }

    override suspend fun granted(): Set<HealthKind> {
        if (availability() != HealthAvailability.AVAILABLE) return emptySet()
        val allowed = connected().permissionController.getGrantedPermissions()
        return PERMISSIONS.filterValues { it in allowed }.keys
    }

    // The sheet is put up, and its answer taken, on the main thread.
    override suspend fun request(): Set<HealthKind> = withContext(Dispatchers.Main.immediate) {
        if (availability() != HealthAvailability.AVAILABLE) return@withContext emptySet()
        // No Activity (something woke the app with no screen): nowhere to ask from.
        val launcher = permissionLauncher ?: return@withContext granted()
        val request = pendingRequest ?: CompletableDeferred<Unit>().also { asking ->
            pendingRequest = asking
            val wanted = PERMISSIONS.values.toMutableSet()
            if (connected().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                wanted += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
            }
            try {
                launcher.launch(wanted)
            } catch (e: IllegalStateException) {
                // The Activity has gone since this was built.
                Log.w(TAG, "Couldn't ask for health data", e)
                pendingRequest = null
                return@withContext granted()
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Health Connect has no permission screen to show", e)
                pendingRequest = null
                return@withContext granted()
            }
        }
        request.await()
        // What the sheet hands back is only what was allowed just now; the whole of it is asked for.
        granted().also { Log.i(TAG, "Allowed ${it.size} of ${PERMISSIONS.size} kinds") }
    }

    override fun manage(): Boolean {
        val intents = if (availability() == HealthAvailability.AVAILABLE) {
            listOfNotNull(
                Intent(ACTION_MANAGE_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
                HealthConnectClient.getHealthConnectManageDataIntent(context, PROVIDER),
            )
        } else {
            // The store page that carries on into Health Connect's own setup once it is installed.
            listOf(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")).setPackage("com.android.vending"))
        }
        return intents.any { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }

    override suspend fun stepsByDay(fromEpochDay: Long, toEpochDay: Long): Map<Long, Long> {
        val days = connected().aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(LocalDate.ofEpochDay(fromEpochDay).atStartOfDay(), LocalDate.ofEpochDay(toEpochDay + 1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        return days.mapNotNull { day -> day.result[StepsRecord.COUNT_TOTAL]?.let { day.startTime.toLocalDate().toEpochDay() to it } }.toMap()
    }

    override suspend fun sleep(fromEpochSeconds: Long, toEpochSeconds: Long): List<SleepSession> =
        readAll(SleepSessionRecord::class, fromEpochSeconds, toEpochSeconds).map { session ->
            SleepSession(
                session.startTime.epochSecond,
                session.endTime.epochSecond,
                session.stages.map { SleepStage(it.startTime.epochSecond, it.endTime.epochSecond, stageKind(it.stage)) },
                startUtcOffsetSeconds = session.startZoneOffset?.totalSeconds,
                endUtcOffsetSeconds = session.endZoneOffset?.totalSeconds,
            )
        }

    override suspend fun restingHeartRates(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> =
        readAll(RestingHeartRateRecord::class, fromEpochSeconds, toEpochSeconds).map { HealthSample(it.time.epochSecond, it.beatsPerMinute.toDouble()) }

    override suspend fun heartRateVariability(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> =
        readAll(HeartRateVariabilityRmssdRecord::class, fromEpochSeconds, toEpochSeconds).map { HealthSample(it.time.epochSecond, it.heartRateVariabilityMillis) }

    override suspend fun runs(fromEpochSeconds: Long, toEpochSeconds: Long): List<RunRecord> {
        val allowed = granted()
        val sessions = readAll(ExerciseSessionRecord::class, fromEpochSeconds, toEpochSeconds).filter {
            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING || it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        }
        Log.i(TAG, "Read ${sessions.size} runs")
        val seen = HashSet<String>()
        return sessions.map { session ->
            // Added up again only when the run has changed, or more of it may be read than could be last time.
            val key = "${session.metadata.id}@${session.metadata.lastModifiedTime.toEpochMilli()}/${allowed.hashCode()}"
            seen += key
            totals.getOrPut(key) { totalled(session, allowed) }
        }.also { totals.keys.retainAll(seen) }
    }

    private suspend fun totalled(session: ExerciseSessionRecord, allowed: Set<HealthKind>): RunRecord {
        // Asking for a total of a kind that isn't allowed fails the whole request, so only what is allowed is asked for.
        val measures: Set<AggregateMetric<*>> = buildSet {
            if (HealthKind.DISTANCE in allowed) add(DistanceRecord.DISTANCE_TOTAL)
            if (HealthKind.CALORIES in allowed) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (HealthKind.STEPS in allowed) add(StepsRecord.COUNT_TOTAL)
            if (HealthKind.ELEVATION in allowed) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
            if (HealthKind.HEART_RATE in allowed) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MAX)
            }
        }
        val window = TimeRangeFilter.between(session.startTime, session.endTime)
        val own = connected().aggregate(AggregateRequest(measures + ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, window, setOf(session.metadata.dataOrigin)))
        // A run logged in one app and measured by another: what its own app has none of comes from whoever has.
        val missing = measures.filterTo(HashSet()) { it !in own }
        val others = if (missing.isEmpty()) null else connected().aggregate(AggregateRequest(missing, window))

        fun <T : Any> sum(metric: AggregateMetric<T>): T? = own[metric] ?: others?.get(metric)
        return RunRecord(
            id = session.metadata.id,
            startEpochSeconds = session.startTime.epochSecond,
            endEpochSeconds = session.endTime.epochSecond,
            utcOffsetSeconds = session.startZoneOffset?.totalSeconds,
            treadmill = session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            meters = sum(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            activeSeconds = own[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.seconds,
            calories = sum(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            averageBpm = sum(HeartRateRecord.BPM_AVG)?.toInt(),
            peakBpm = sum(HeartRateRecord.BPM_MAX)?.toInt(),
            steps = sum(StepsRecord.COUNT_TOTAL),
            climbMeters = sum(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
            route = when (session.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> RouteAccess.READABLE
                is ExerciseRouteResult.ConsentRequired -> RouteAccess.ASK
                else -> RouteAccess.NONE
            },
        )
    }

    override suspend fun track(run: RunRecord): RunTrack {
        val allowed = granted()
        val session = connected().readRecord(ExerciseSessionRecord::class, run.id).record
        val recorder = setOf(session.metadata.dataOrigin)
        val window = run.startEpochSeconds..run.endEpochSeconds
        val heart = if (HealthKind.HEART_RATE in allowed) {
            fromRecorder(HeartRateRecord::class, run, recorder).flatMap { it.samples }
                .filter { it.time.epochSecond in window }
                .map { HealthSample(it.time.epochSecond, it.beatsPerMinute.toDouble()) }
        } else {
            emptyList()
        }
        val distance = if (HealthKind.DISTANCE in allowed) {
            fromRecorder(DistanceRecord::class, run, recorder).map { DistanceSpan(it.startTime.epochSecond, it.endTime.epochSecond, it.distance.inMeters) }
        } else {
            emptyList()
        }
        Log.i(TAG, "A run's track: ${heart.size} heart readings, ${distance.size} stretches of distance")
        return RunTrack(heart, distance, points(session.exerciseRouteResult))
    }

    override suspend fun heartRate(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> =
        readAll(HeartRateRecord::class, fromEpochSeconds, toEpochSeconds).flatMap { it.samples }
            .filter { it.time.epochSecond in fromEpochSeconds..toEpochSeconds }
            .map { HealthSample(it.time.epochSecond, it.beatsPerMinute.toDouble()) }
            .sortedBy { it.epochSeconds }
            .also { Log.i(TAG, "A recorded run's heart: ${it.size} readings") }

    /** A run's records of one kind from the app that recorded the run; from anyone, when that app wrote none. */
    private suspend fun <T : Record> fromRecorder(type: KClass<T>, run: RunRecord, recorder: Set<DataOrigin>): List<T> =
        readAll(type, run.startEpochSeconds, run.endEpochSeconds, recorder).ifEmpty { readAll(type, run.startEpochSeconds, run.endEpochSeconds) }

    override suspend fun requestRoute(runId: String): List<RoutePoint> = withContext(Dispatchers.Main.immediate) {
        val launcher = routeLauncher ?: return@withContext emptyList()
        val request = pendingRoute ?: CompletableDeferred<ExerciseRoute?>().also { asking ->
            pendingRoute = asking
            try {
                launcher.launch(runId)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't ask for a route", e)
                pendingRoute = null
                return@withContext emptyList()
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Health Connect has no route screen to show", e)
                pendingRoute = null
                return@withContext emptyList()
            }
        }
        request.await()?.route.orEmpty().map { RoutePoint(it.latitude, it.longitude) }
    }

    private fun points(route: ExerciseRouteResult): List<RoutePoint> =
        (route as? ExerciseRouteResult.Data)?.exerciseRoute?.route.orEmpty().map { RoutePoint(it.latitude, it.longitude) }

    /** Every record of [type] in the range, page after page. */
    private suspend fun <T : Record> readAll(type: KClass<T>, fromEpochSeconds: Long, toEpochSeconds: Long, origins: Set<DataOrigin> = emptySet()): List<T> {
        val range = TimeRangeFilter.between(Instant.ofEpochSecond(fromEpochSeconds), Instant.ofEpochSecond(toEpochSeconds))
        val records = ArrayList<T>()
        var page: String? = null
        do {
            val response = connected().readRecords(ReadRecordsRequest(type, range, origins, pageToken = page))
            records += response.records
            page = response.pageToken
        } while (page != null)
        return records
    }

    /** Health Connect's stages as the app counts them: every way of lying awake is awake, and sleep of no stated depth is sleep. */
    private fun stageKind(stage: Int): SleepStageKind = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageKind.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageKind.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageKind.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStageKind.REM
        else -> SleepStageKind.ASLEEP
    }
}

actual fun createHealthSource(platformContext: PlatformContext): HealthSource =
    HealthConnectSource(platformContext.context.applicationContext, platformContext.context as? FragmentActivity)
