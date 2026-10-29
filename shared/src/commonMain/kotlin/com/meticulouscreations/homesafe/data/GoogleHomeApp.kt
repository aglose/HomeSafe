package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext

/**
 * The Google Home app, for the one thing a Nest camera can do there and not here: talk through
 * its speaker. The server reaches the Nest cameras through Google's Device Access API, which
 * hands out their video and sound but takes no audio back (its WebRTC offer must be
 * receive-only), so the way to talk is Google's own app. Nothing here can pick the camera or
 * press its talk button; that is the person's to do once it is open.
 */
fun interface GoogleHomeApp {
    /** Brings Google Home to the front, or its store page when it isn't installed. False when neither could be opened. */
    fun open(): Boolean
}

/** Builds the platform's [GoogleHomeApp]. */
expect fun createGoogleHomeApp(context: PlatformContext): GoogleHomeApp
