package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.PlatformContext

/**
 * A real browser, for the few things a resort's site only gives one: its ticket shop answers
 * a page's own requests and turns anything else away. This opens the page as a person's browser
 * would, identifying itself as what it is, and asks for what the page itself would ask for. If
 * a site still says no, the answer is null: it is never made to look like something it isn't.
 *
 * It is one hidden page at a time, and slow: a few seconds and a page's worth of data each. So
 * it's only for what can't be had otherwise, and what it brings back is kept for a day.
 */
interface SkiBrowser {
    /** Whether there's a browser to use here. A desktop app on anything but a Mac has none, and goes without. */
    val isSupported: Boolean

    /**
     * Opens [url], and runs [script] in it until it gives something other than null, or until
     * [timeoutMillis]. The script is run again and again, and starts over after any redirect (a
     * waiting room, say), so it should say null until the page is ready. Null if it never did.
     */
    suspend fun read(url: String, script: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): String?

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 45_000L

        /** How long to wait between one try of a script and the next. */
        const val POLL_MILLIS = 500L
    }
}

/** Each platform's browser; see `SkiBrowser.<platform>.kt`. */
expect fun createSkiBrowser(platformContext: PlatformContext): SkiBrowser
