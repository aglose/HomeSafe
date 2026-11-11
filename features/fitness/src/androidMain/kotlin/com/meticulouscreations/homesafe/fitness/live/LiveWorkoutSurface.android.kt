package com.meticulouscreations.homesafe.fitness.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.meticulouscreations.homesafe.appicon.appLaunchIntent
import com.meticulouscreations.homesafe.data.createSilentChannel
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.navigation.FitnessDeepLink
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawableRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "HomeSafeWorkout"

/** What the notification is drawing: the workout and its words, as the presenter last handed them over. */
private class Shown(val workout: LiveWorkout, val words: LiveWorkoutWords)

/**
 * The workout in progress on an Android phone: an ongoing notification that Android 16 promotes
 * to a Live Update (at the top of the shade and the Lock Screen, and a chip in the status bar
 * counting the rest down), kept up by [WorkoutSessionService], a foreground service of the
 * `health` type that keeps the app running through the workout with the screen off. Where that
 * service can't be started (Android lets one of that type run only once heart rate may be read,
 * or for the `connectedDevice` type once a Bluetooth sensor may be linked; see
 * [foregroundTypes]) the same notification is posted without it, and lasts as long as the app
 * does.
 *
 * - **Between rests** the stopwatch counts up from when the workout began; under it the exercise
 *   in hand and what comes next, and over it the heart rate when a sensor is on.
 * - **Resting** the chip and the notification count the rest down, a bar drains with it, and
 *   three buttons take fifteen seconds off, add fifteen, or skip it. They act on the rest the app
 *   itself shows ([LiveWorkouts]), so the two never disagree.
 * - **When the rest runs out** with the app away, a second notification on a channel of its own
 *   buzzes once ("Rest's up"), and the chip says go; with the app in front it says so itself.
 *
 * A tap on either opens the workout full screen ([FitnessDeepLink.Workout]).
 *
 * Logged under `HomeSafeWorkout`, without the workout's numbers.
 */
class AndroidLiveWorkoutSurface(context: Context) : LiveWorkoutSurface {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shown = MutableStateFlow<Shown?>(null)
    private val wakeLock by lazy {
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeSafe:rest").apply { setReferenceCounted(false) }
    }

    /** The rest whose end has been announced, by when it ended, so it buzzes once. */
    private var announced: Long? = null

    /** Whether the foreground service is up and the notification is its. */
    private var serviceUp = false

    /** What the notification last showed, and when its heart last changed (elapsed realtime). */
    private var posted: Shown? = null
    private var heartPostedAt = 0L

    init {
        WorkoutNotifications.createChannels(app)
        scope.launch { follow() }
    }

    override val keepsAppRunning: Boolean get() = foregroundTypes(app) != null

    override fun show(workout: LiveWorkout, words: LiveWorkoutWords) {
        shown.value = Shown(workout, words)
    }

    override fun end() {
        shown.value = null
    }

    /**
     * Draws whatever is shown, again each second while a rest counts (its bar drains; the chip
     * and the countdown tick by themselves), and announces the rest's end once.
     */
    private suspend fun follow() {
        shown.collectLatest { current ->
            if (current == null) {
                stop()
                return@collectLatest
            }
            startService()
            // A heart reading comes every second, and each would post the notification again, through the whole workout.
            // Outside a rest (which redraws each second anyway) a change that is only the heart waits its turn; the wait is
            // to a fixed moment, so the readings that keep replacing this one don't put it off.
            val wait = heartWaitMillis(current, SystemClock.elapsedRealtime())
            if (wait > 0) delay(wait)
            while (true) {
                val now = System.currentTimeMillis()
                val phase = current.workout.phase(now)
                post(current, now)
                announce(current, phase, now)
                if (phase != LivePhase.RESTING) {
                    releaseWake()
                    return@collectLatest
                }
                // The phone may sleep between sets; the rest's end must still buzz on time.
                val rest = checkNotNull(current.workout.rest)
                if (serviceUp) holdWake(rest.endsAtEpochMillis - now + WAKE_MARGIN_MILLIS)
                delay(minOf(TICK_MILLIS, (rest.endsAtEpochMillis - now).coerceAtLeast(1)))
            }
        }
    }

    /** How long [current] waits before it is posted: nothing, unless all that is new in it is the heart and the heart was posted under [HEART_EVERY_MILLIS] ago. */
    private fun heartWaitMillis(current: Shown, elapsedNow: Long): Long {
        val before = posted?.workout ?: return 0
        val workout = current.workout
        if (workout.phase(System.currentTimeMillis()) == LivePhase.RESTING) return 0
        // A heart that appears or goes (the sensor linked, or lost) is news; one that only reads differently is not.
        val onlyTheHeart = before.heart != null && workout.heart != null && before.copy(heart = workout.heart) == workout
        return if (onlyTheHeart) (heartPostedAt + HEART_EVERY_MILLIS - elapsedNow).coerceAtLeast(0) else 0
    }

    private fun post(current: Shown, now: Long) {
        if (posted?.workout?.heart != current.workout.heart) heartPostedAt = SystemClock.elapsedRealtime()
        posted = current
        try {
            manager.notify(WorkoutNotifications.ONGOING_ID, WorkoutNotifications.ongoing(app, current.workout, current.words, now))
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't post the workout", e)
        }
    }

    private fun announce(current: Shown, phase: LivePhase, now: Long) {
        val rest = current.workout.rest
        when {
            phase == LivePhase.RESTING || rest == null -> {
                // A new rest: whatever was said about the last one goes.
                if (announced != null) manager.cancel(WorkoutNotifications.REST_OVER_ID)
                announced = null
            }

            current.workout.onScreen -> {
                // The workout's own page says so, larger.
                manager.cancel(WorkoutNotifications.REST_OVER_ID)
                announced = rest.endsAtEpochMillis
            }

            announced != rest.endsAtEpochMillis -> {
                announced = rest.endsAtEpochMillis
                // Not one that ran out long ago, while the app was stopped: that is no news now.
                if (now - rest.endsAtEpochMillis < STALE_ANNOUNCEMENT_MILLIS) {
                    runCatching { manager.notify(WorkoutNotifications.REST_OVER_ID, WorkoutNotifications.restOver(app, current.workout, current.words)) }
                        .onFailure { Log.w(TAG, "Couldn't say the rest is over", it) }
                }
            }
        }
    }

    private fun startService() {
        if (serviceUp || foregroundTypes(app) == null) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, WorkoutSessionService::class.java))
            serviceUp = true
            Log.i(TAG, "Keeping the app running for the workout")
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background. The notification is still posted.
            Log.w(TAG, "Couldn't keep the app running for the workout", e)
        }
    }

    private fun stop() {
        posted = null
        releaseWake()
        if (serviceUp) app.stopService(Intent(app, WorkoutSessionService::class.java))
        serviceUp = false
        manager.cancel(WorkoutNotifications.ONGOING_ID)
        manager.cancel(WorkoutNotifications.REST_OVER_ID)
        announced = null
    }

    @SuppressLint("WakelockTimeout")
    private fun holdWake(millis: Long) {
        runCatching { wakeLock.acquire(millis.coerceIn(1_000, MAX_WAKE_MILLIS)) }
    }

    private fun releaseWake() {
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    /** The notification the service starts with: the workout as last drawn, or a plain line until it has been. */
    internal fun current(now: Long): Notification {
        val current = shown.value ?: return WorkoutNotifications.placeholder(app)
        return WorkoutNotifications.ongoing(app, current.workout, current.words, now)
    }

    private companion object {
        const val TICK_MILLIS = 1_000L

        /** How often, at most, the notification is posted again for nothing but a new heart reading. */
        const val HEART_EVERY_MILLIS = 10_000L
        const val WAKE_MARGIN_MILLIS = 10_000L

        /** A rest is at most some minutes; the phone is never held awake longer than this for one. */
        const val MAX_WAKE_MILLIS = 15 * 60_000L

        /** A rest that ran out longer ago than this, unseen, isn't announced. */
        const val STALE_ANNOUNCEMENT_MILLIS = 60_000L
    }
}

/**
 * The foreground service types the workout's service can run as now, or null for none. `health`
 * is the one for a workout, and Android starts one only once the app may read heart rate (from
 * Health Connect, which the fitness app asks for when its health store is connected) or
 * recognise activity; `connectedDevice` once a Bluetooth heart-rate sensor may be linked, which
 * is what keeps that link up with the screen off. Before Android 14 a service needs no type.
 */
internal fun foregroundTypes(context: Context): Int? {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return if (granted(Manifest.permission.BLUETOOTH_CONNECT)) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
    }
    var types = 0
    if (granted(READ_HEART_RATE) || granted(Manifest.permission.ACTIVITY_RECOGNITION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    if (granted(Manifest.permission.BLUETOOTH_CONNECT)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    return types.takeIf { it != 0 }
}

/** Health Connect's permission to read heart rate, a platform permission from Android 14. */
private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

/**
 * Keeps the app running in the foreground while a workout is in progress, so its rest still buzzes
 * on time and the heart-rate sensor stays linked with the screen off. It shows the workout's own
 * notification ([AndroidLiveWorkoutSurface] posts it, under the same id); started and stopped by
 * that surface alone.
 */
class WorkoutSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val surface = LiveWorkoutPresenter.surface as? AndroidLiveWorkoutSurface
        val types = foregroundTypes(this)
        if (surface == null || types == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, WorkoutNotifications.ONGOING_ID, surface.current(System.currentTimeMillis()), types)
        } catch (e: RuntimeException) {
            // A permission taken away between the check and here (SecurityException), or a start from the background refused.
            Log.w(TAG, "Couldn't run in the foreground", e)
            stopSelf()
        }
        // Not brought back by Android if the app is stopped: the workout is in the log, and its notification comes back with the app.
        return START_NOT_STICKY
    }
}

/** The notification's rest buttons: they act on the process's rest, which the app's own screens follow. */
class WorkoutActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hub = LiveWorkouts.process
        when (intent.action) {
            ACTION_SKIP -> hub.skipRest()
            ACTION_ADJUST -> hub.adjustRest(intent.getIntExtra(EXTRA_SECONDS, 0))
        }
    }

    internal companion object {
        const val ACTION_SKIP = "com.meticulouscreations.homesafe.fitness.SKIP_REST"
        const val ACTION_ADJUST = "com.meticulouscreations.homesafe.fitness.ADJUST_REST"
        const val EXTRA_SECONDS = "seconds"
    }
}

/** The workout's two notifications, built from what the presenter handed over. */
internal object WorkoutNotifications {
    const val ONGOING_ID = 0x574b54
    const val REST_OVER_ID = 0x525354
    private const val CHANNEL_ONGOING = "workout_live"
    private const val CHANNEL_REST_OVER = "workout_rest_over"
    private const val REST_NUDGE_SECONDS = 15

    /** One buzz, long enough to feel through a bench or a pocket. */
    private val BUZZ = longArrayOf(0, 450, 160, 450)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Silent: the workout changing is no reason to make a sound. Default importance, so it is a Live Update and has its chip.
        manager.createSilentChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.notif_channel_workout_name),
            context.getString(R.string.notif_channel_workout_description),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createSilentChannel(
            CHANNEL_REST_OVER,
            context.getString(R.string.notif_channel_rest_over_name),
            context.getString(R.string.notif_channel_rest_over_description),
            NotificationManager.IMPORTANCE_HIGH,
            vibrate = true,
        )
        manager.getNotificationChannel(CHANNEL_REST_OVER)?.takeIf { it.vibrationPattern == null }?.let { channel ->
            channel.vibrationPattern = BUZZ
            manager.createNotificationChannel(channel)
        }
    }

    fun placeholder(context: Context): Notification = base(context, CHANNEL_ONGOING, null)
        .setContentTitle(context.getString(R.string.notif_workout_title))
        .build()

    fun ongoing(context: Context, workout: LiveWorkout, words: LiveWorkoutWords, now: Long): Notification {
        val phase = workout.phase(now)
        val builder = base(context, CHANNEL_ONGOING, workout)
            .setContentTitle(words.headline(phase))
            .setContentText(words.detail(phase))
            // Over the title, small: the heart where a sensor is on, and otherwise what the workout has come to.
            .setSubText(words.heart ?: words.summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (phase) {
            LivePhase.TRAINING -> {
                // The stopwatch, counting up from when the workout began, in the shade and in the chip.
                builder
                    .setWhen(workout.startedAtEpochMillis)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setSmallIcon(NotificationGlyph.WORKOUT.drawableRes)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(words)))
            }

            LivePhase.RESTING -> {
                val rest = checkNotNull(workout.rest)
                val total = rest.totalSeconds.coerceAtLeast(1)
                val gone = ((total * 1000L - (rest.endsAtEpochMillis - now)) / 1000).toInt().coerceIn(0, total)
                builder
                    .setWhen(rest.endsAtEpochMillis)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setSmallIcon(NotificationGlyph.REST.drawableRes)
                    .setStyle(
                        NotificationCompat.ProgressStyle()
                            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(total).setColor(accent(workout))))
                            .setProgress(gone)
                            .setStyledByProgress(true)
                            .setProgressTrackerIcon(IconCompat.createWithResource(context, NotificationGlyph.REST.drawableRes)),
                    )
                    .addAction(action(context, words.labels.minusShort, WorkoutActionReceiver.ACTION_ADJUST, -REST_NUDGE_SECONDS))
                    .addAction(action(context, words.labels.plusShort, WorkoutActionReceiver.ACTION_ADJUST, REST_NUDGE_SECONDS))
                    .addAction(action(context, words.labels.skip, WorkoutActionReceiver.ACTION_SKIP, 0))
            }

            LivePhase.REST_OVER -> {
                // The chip says go in place of a time; the shade says when the rest ran out.
                builder
                    .setWhen(checkNotNull(workout.rest).endsAtEpochMillis)
                    .setShowWhen(true)
                    .setShortCriticalText(words.labels.go)
                    .setSmallIcon(NotificationGlyph.WORKOUT.drawableRes)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(bigText(words)))
            }
        }
        return builder.build()
    }

    /** Opened out, between rests: the exercise in hand and what comes next, each on its line. */
    private fun bigText(words: LiveWorkoutWords): String = listOfNotNull(words.lift, words.next).distinct().joinToString("\n").ifEmpty { words.summary }

    fun restOver(context: Context, workout: LiveWorkout, words: LiveWorkoutWords): Notification = base(context, CHANNEL_REST_OVER, workout)
        .setSmallIcon(NotificationGlyph.REST.drawableRes)
        .setContentTitle(words.restOver)
        .setContentText(words.detail(LivePhase.REST_OVER))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVibrate(BUZZ)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // Said once and gone: the ongoing one carries on saying it.
        .setTimeoutAfter(2 * 60_000L)
        .build()

    private fun base(context: Context, channel: String, workout: LiveWorkout?): NotificationCompat.Builder = NotificationCompat.Builder(context, channel)
        .setSmallIcon(NotificationGlyph.WORKOUT.drawableRes)
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setContentIntent(openWorkout(context))
        .apply { if (workout != null) setColor(accent(workout)) }

    private fun accent(workout: LiveWorkout): Int = FitnessPalette().focus(workout.focus).first.toArgb()

    /** The workout full screen, in the running app (MainActivity is single-task) or a new one. */
    private fun openWorkout(context: Context): PendingIntent? {
        val target = context.appLaunchIntent()?.component ?: return null
        val intent = Intent(Intent.ACTION_VIEW, FitnessDeepLink.Workout.toUri().toUri()).setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, ONGOING_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, title: String, action: String, seconds: Int): NotificationCompat.Action {
        val intent = Intent(context, WorkoutActionReceiver::class.java).setAction(action).putExtra(WorkoutActionReceiver.EXTRA_SECONDS, seconds)
        val pending = PendingIntent.getBroadcast(context, action.hashCode() + seconds, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(null, title, pending)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(false)
            .build()
    }
}
