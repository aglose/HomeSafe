package com.meticulouscreations.homesafe.ui.components

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Rational
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.LifecycleStartEffect
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Through the Activity's own picture-in-picture (API 31's auto-enter; minSdk is 33), so no extra
 * dependency. The manifest opts the Activity in (`supportsPictureInPicture`), and its
 * `configChanges` already cover the resize, so shrinking to the mini player and growing back
 * never recreates it: the same composition, the same player, the stream never rebinds.
 *
 * The screen that claims it last, while it is started, is the one that floats when the person
 * leaves the app: its video's box is handed to the system ([PictureInPicture.sourceModifier]) so
 * the window shrinks from exactly where the picture was, and auto-enter is switched off again as
 * the screen stops or leaves, so leaving from anywhere else just leaves.
 */
@Composable
actual fun rememberPictureInPicture(enabled: Boolean, aspectRatio: Float): PictureInPicture {
    val activity = LocalActivity.current
    val supported = remember(activity) { activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true }
    val pip = remember(activity, supported) { if (activity != null && supported) ActivityPictureInPicture(activity) else null }
    if (pip == null) return NoPictureInPicture
    SideEffect { pip.setAspectRatio(aspectRatio) }
    val claims = enabled && LocalPictureInPictureAllowed.current
    LifecycleStartEffect(pip, claims) {
        if (claims) PictureInPictureClaims.claim(pip)
        onStopOrDispose { PictureInPictureClaims.release(pip) }
    }
    return pip
}

private class ActivityPictureInPicture(val activity: Activity) : PictureInPicture {
    override val available: Boolean = true

    /** Whether this is the screen that floats when the person leaves the app (see [PictureInPictureClaims]). */
    var claimed = false

    private var aspect = Rational(16, 9)
    private var sourceRect: Rect? = null

    // The video's box moves on every frame of a transition or a zoom: the system hears where it
    // came to rest, not each step on the way.
    private val pushSettled = Runnable { push() }

    override val sourceModifier: Modifier = Modifier.onGloballyPositioned { coordinates ->
        // In the mini player the box is the window itself, which is no place to grow from.
        if (activity.isInPictureInPictureMode) return@onGloballyPositioned
        val bounds = coordinates.boundsInWindow()
        val rect = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
        if (rect == sourceRect) return@onGloballyPositioned
        sourceRect = rect
        if (claimed) {
            val view = activity.window.decorView
            view.removeCallbacks(pushSettled)
            view.postDelayed(pushSettled, SOURCE_SETTLE_MS)
        }
    }

    fun setAspectRatio(ratio: Float) {
        val rational = ratio.coerceIn(MIN_ASPECT, MAX_ASPECT).let { Rational((it * ASPECT_SCALE).roundToInt(), ASPECT_SCALE) }
        if (rational == aspect) return
        aspect = rational
        push()
    }

    override fun enter() {
        // False (or thrown) when the person has turned picture-in-picture off for the app in the system's settings.
        runCatching { activity.enterPictureInPictureMode(params(autoEnter = claimed)) }
    }

    fun push() {
        if (claimed) setParams(params(autoEnter = true))
    }

    fun setParams(params: PictureInPictureParams) {
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching { activity.setPictureInPictureParams(params) }
    }

    private fun params(autoEnter: Boolean): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(aspect)
        .setSourceRectHint(sourceRect?.takeUnless { it.isEmpty })
        .setAutoEnterEnabled(autoEnter)
        .build()
}

/** Which screen floats, per Activity: the last to claim it. Main thread only, like the effects that call it. */
private object PictureInPictureClaims {
    private val owners = WeakHashMap<Activity, ActivityPictureInPicture>()

    fun claim(pip: ActivityPictureInPicture) {
        owners.put(pip.activity, pip)?.takeIf { it !== pip }?.claimed = false
        pip.claimed = true
        pip.push()
    }

    fun release(pip: ActivityPictureInPicture) {
        pip.claimed = false
        if (owners[pip.activity] !== pip) return
        owners.remove(pip.activity)
        pip.setParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
    }
}

/** How long the video's box has to stay put before the system is told where it is. */
private const val SOURCE_SETTLE_MS = 150L

/**
 * The system's limits on the mini player's shape are 2.39:1 and 1:2.39; a ratio outside them is
 * refused. The tall one is held a hair inside, where rounding to [ASPECT_SCALE] can't cross it.
 */
private const val MAX_ASPECT = 2.39f
private const val MIN_ASPECT = 0.42f
private const val ASPECT_SCALE = 1000
