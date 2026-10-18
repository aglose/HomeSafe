package com.meticulouscreations.homesafe.ui.components

/**
 * Whether any of the app's AVPlayers is playing: the one thing the WebRTC audio device (Swift's
 * `PlaybackOnlyAudioDevice`) has to know before it lets go of the audio session it shares with
 * them. Deactivating a session stops everything running in it, so the device releases the
 * session (letting music another app paused resume) only while no player here is playing, muted
 * or not, since a muted player still runs in the session.
 *
 * Each [LivePlayerHolder] reports itself as it plays, pauses, swaps engine or is released. Main
 * thread only, like the holders.
 */
object IosAudioSessionPlayers {
    private val playing = mutableSetOf<Any>()

    /** True while at least one AVPlayer is playing an item. */
    val anyPlaying: Boolean get() = playing.isNotEmpty()

    internal fun report(player: Any, isPlaying: Boolean) {
        if (isPlaying) playing.add(player) else playing.remove(player)
    }
}
