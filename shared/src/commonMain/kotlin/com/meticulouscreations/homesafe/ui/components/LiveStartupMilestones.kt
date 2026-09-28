package com.meticulouscreations.homesafe.ui.components

/**
 * Named points on the way from launch to the first live picture: sign-in submitted and accepted,
 * each camera's join stages, and the first frame a surface actually drew. What
 * `scripts/bench-first-live-pixel.sh` reads to time a cold start stage by stage.
 *
 * Only the first occurrence of each name per process is recorded, so a name means "the cold
 * start's", never a later reconnect's. Android installs [recorder] (logcat, relative to process
 * start); everywhere else nothing is installed and [mark] costs a null check.
 */
object LiveStartupMilestones {
    var recorder: ((String) -> Unit)? = null

    fun mark(name: String) {
        recorder?.invoke(name)
    }
}
