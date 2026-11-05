package com.meticulouscreations.homesafe.fitness

import androidx.compose.ui.graphics.toArgb
import com.meticulouscreations.homesafe.fitness.live.LivePhase
import com.meticulouscreations.homesafe.fitness.live.LiveWorkout
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutSurface
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutWords
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.navigation.FitnessDeepLink
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.time.Clock

/**
 * What the iPhone's Live Activity shows of the workout in progress, as plain values for Swift
 * (`WorkoutLiveActivities.swift` turns them into the activity's content). Every word is already
 * in the reader's language. Times are milliseconds since 1970; [restEndsAtEpochMillis] is 0
 * between rests. The colours are the workout's two, as ARGB.
 */
class IosLiveWorkoutState(
    val workoutId: Long,
    val startedAtEpochMillis: Long,
    val accentArgb: Int,
    val accent2Argb: Int,
    val title: String,
    val summary: String,
    val resting: String,
    val restOver: String,
    val trainingDetail: String,
    val restingDetail: String,
    val restOverDetail: String,
    val heart: String?,
    val restEndsAtEpochMillis: Long,
    val restTotalSeconds: Int,
    val restLabel: String,
    val goLabel: String,
    val skipLabel: String,
    val minusShort: String,
    val minusLabel: String,
    val plusShort: String,
    val plusLabel: String,
    val openLabel: String,
)

/**
 * The Live Activity itself, on the Swift side: ActivityKit has no Objective-C face, so Kotlin
 * can't start one. Swift hands one in at launch (`startIosApp` in IosApp.kt).
 */
interface IosLiveActivities {
    fun show(state: IosLiveWorkoutState)

    fun end()
}

/**
 * The workout in progress on an iPhone: a Live Activity on the Lock Screen and in the Dynamic
 * Island ([IosLiveActivities]), which counts the rest down and up again by itself with the app
 * asleep, and whose buttons skip or change the rest (`WorkoutRestIntents.swift`, which come back
 * to the process's rest through `onWorkoutRestAction`).
 *
 * A suspended app can't say when a rest is over, so that is said ahead of time: a local
 * notification is scheduled for the moment the rest ends and moved whenever the rest is, and is
 * kept off the screen when the workout's own page is in front (see `IosNotificationTaps`). It is
 * the one notification of the app's that makes a sound: an iPhone only buzzes for one that does,
 * and a rest timer that doesn't buzz goes unnoticed on the bench. With the ring switch off it is
 * the buzz alone.
 */
internal class IosLiveWorkoutSurface(private val activities: IosLiveActivities, private val clock: Clock = Clock.System) : LiveWorkoutSurface {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    /** The end of the rest a notification is waiting for, so it is only moved when the rest is. */
    private var scheduledFor: Long? = null

    override fun show(workout: LiveWorkout, words: LiveWorkoutWords) {
        activities.show(state(workout, words))
        schedule(workout, words)
    }

    override fun end() {
        activities.end()
        unschedule()
    }

    private fun schedule(workout: LiveWorkout, words: LiveWorkoutWords) {
        val endsAt = workout.rest?.endsAtEpochMillis
        val now = clock.now().toEpochMilliseconds()
        if (endsAt == null || endsAt <= now) {
            // Skipped, or already over: a notification still waiting would be wrong.
            if (endsAt == null) unschedule()
            return
        }
        if (scheduledFor == endsAt) return
        scheduledFor = endsAt
        val content = UNMutableNotificationContent().apply {
            setTitle(words.restOver)
            setBody(words.detail(LivePhase.REST_OVER))
            setSound(UNNotificationSound.defaultSound)
            setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive)
            setThreadIdentifier(THREAD)
            setUserInfo(mapOf(FitnessDeepLink.KEY to "rest_over"))
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval((endsAt - now) / 1000.0, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(REQUEST, content, trigger), withCompletionHandler = null)
    }

    private fun unschedule() {
        scheduledFor = null
        center.removePendingNotificationRequestsWithIdentifiers(listOf(REQUEST))
    }

    private fun state(workout: LiveWorkout, words: LiveWorkoutWords): IosLiveWorkoutState {
        val colors = FitnessPalette().focus(workout.focus)
        val labels = words.labels
        return IosLiveWorkoutState(
            workoutId = workout.workoutId,
            startedAtEpochMillis = workout.startedAtEpochMillis,
            accentArgb = colors.first.toArgb(),
            accent2Argb = colors.second.toArgb(),
            title = words.title,
            summary = words.summary,
            resting = words.resting,
            restOver = words.restOver,
            trainingDetail = words.detail(LivePhase.TRAINING),
            restingDetail = words.detail(LivePhase.RESTING),
            restOverDetail = words.detail(LivePhase.REST_OVER),
            heart = words.heart,
            restEndsAtEpochMillis = workout.rest?.endsAtEpochMillis ?: 0,
            restTotalSeconds = workout.rest?.totalSeconds ?: 0,
            restLabel = labels.rest,
            goLabel = labels.go,
            skipLabel = labels.skip,
            minusShort = labels.minusShort,
            minusLabel = labels.minus,
            plusShort = labels.plusShort,
            plusLabel = labels.plus,
            openLabel = labels.open,
        )
    }

    private companion object {
        const val REQUEST = "homesafe.fitness.rest_over"
        const val THREAD = "homesafe.fitness"
    }
}
