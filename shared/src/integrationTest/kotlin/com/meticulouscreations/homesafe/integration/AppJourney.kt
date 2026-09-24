package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.App
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateServer
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs [block] against the whole app — [App] on a real [AppGraph], its real Ktor client,
 * repositories, Room database and view models — signed in to nothing yet, with a
 * [FakeFrigateServer] serving [state] on localhost for it to sign in to.
 *
 * This is the layer the screen-level tests in `src/uiTest` can't reach: whether the sign-in
 * screen, the shell, and every screen behind a `metroViewModel()` actually work together with
 * the data layer over HTTP. It runs on the JVM (desktop, in the unit-test job) and on an Android
 * emulator (the instrumented job), from the same source.
 *
 * The clock is frozen (`autoAdvance = false`) for the whole journey: the app has never-ending
 * frame loops (loading shimmers, live pills) that would keep an auto-advancing clock from ever
 * going idle. Every wait here therefore drives frames itself — see [awaitUntil].
 */
@OptIn(ExperimentalTestApi::class)
internal fun runAppJourney(
    state: FakeFrigateState = FakeFrigateState.household(),
    block: AppJourney.() -> Unit,
) = runComposeUiTest(testTimeout = JOURNEY_TIMEOUT) {
    FakeFrigateServer(state).start().use { server ->
        val graph = createAppGraph(testPlatformContext())
        val watchdog = JourneyWatchdog(Thread.currentThread(), JOURNEY_WATCHDOG)
        try {
            mainClock.autoAdvance = false
            setContent { App(graph) }
            AppJourney(this, server, graph).block()
        } catch (interrupted: InterruptedException) {
            throw AssertionError(
                "The journey was stuck for $JOURNEY_WATCHDOG and was interrupted. Every thread at that moment:\n${watchdog.threadDump}" +
                    "\n--- Last requests to the fake server ---\n  " + server.requests.takeLast(20).joinToString("\n  "),
                interrupted,
            )
        } finally {
            watchdog.cancel()
            // Each journey builds its own graph; don't leave this one's detection poller running beside the next.
            graph.detectionAlertService.stop()
        }
    }
}

/**
 * Interrupts [testThread] if the journey is still running after [limit], having first taken
 * every thread's stack. A journey that blocks outright (a wait on the UI thread that never
 * returns, say) would otherwise sit there until CI kills the whole job, with nothing in the log
 * to say where; `runTest`'s own timeout can't fire while the test thread is blocked.
 */
private class JourneyWatchdog(testThread: Thread, limit: Duration) {
    @Volatile
    var threadDump: String = ""
        private set

    private val timer = Thread({
        try {
            Thread.sleep(limit.inWholeMilliseconds)
        } catch (_: InterruptedException) {
            return@Thread
        }
        threadDump = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, frames) ->
            "\"${thread.name}\" ${thread.state}\n" + frames.take(40).joinToString("\n") { "    at $it" }
        }
        // Straight to the process's own stderr as well, past the test runner's capture: if the
        // UI thread is wedged, tearing the test down can hang too and the failure never be reported.
        runCatching {
            java.io.FileOutputStream(java.io.FileDescriptor.err).apply {
                write("\n=== Stuck journey on ${testThread.name}; every thread:\n$threadDump\n===\n".toByteArray())
                flush()
            }
        }
        testThread.interrupt()
    }, "journey-watchdog").apply {
        isDaemon = true
        start()
    }

    fun cancel() = timer.interrupt()
}

/**
 * One journey through the app: the compose test driving it, the fake server behind it, and the
 * app graph under test. Screen-specific steps live in the robots ([signIn], [shell] and the
 * per-area ones built on [AppJourney]); what is here is the waiting, which all of them share.
 */
@OptIn(ExperimentalTestApi::class)
internal class AppJourney(
    val ui: ComposeUiTest,
    val server: FakeFrigateServer,
    val graph: AppGraph,
) {
    val state: FakeFrigateState get() = server.state

    val signIn = SignInRobot(this)
    val shell = ShellRobot(this)

    /**
     * Drives frames until [condition] holds, or fails after [timeout] of real time with a dump
     * of what was on screen and what the server was asked — the only evidence a CI run leaves.
     * The clock is frozen, so each pass advances it one frame (recomposing whatever changed and
     * moving animations along) and then sleeps briefly so network responses can land.
     */
    fun awaitUntil(description: String, timeout: Duration = DEFAULT_WAIT, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var lastError: Throwable? = null
        while (true) {
            ui.mainClock.advanceTimeByFrame()
            val met = try {
                condition()
            } catch (error: AssertionError) {
                lastError = error
                false
            } catch (error: IllegalStateException) {
                // Only "nothing composed yet" is worth waiting out; anything else is a real failure
                // (say, an exception thrown during layout) and swallowing it would hide the cause.
                if (error.message?.contains("compose hierarch", ignoreCase = true) != true) throw error
                lastError = error
                false
            }
            if (met) return
            if (deadline.hasPassedNow()) {
                throw AssertionError("Timed out after $timeout waiting for $description.\n${diagnostics()}", lastError)
            }
            Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
        }
    }

    /** Advances the frozen clock by [duration] a frame at a time, e.g. to let a transition finish. */
    fun settle(duration: Duration = SETTLE) {
        val frames = (duration.inWholeMilliseconds / FRAME_MILLIS).coerceAtLeast(1)
        repeat(frames.toInt()) {
            ui.mainClock.advanceTimeByFrame()
            if (it % 4 == 0) Thread.sleep(1)
        }
    }

    /** How many nodes currently match [matcher] (merged tree). */
    fun count(matcher: SemanticsMatcher): Int = ui.onAllNodes(matcher).fetchSemanticsNodes().size

    fun exists(matcher: SemanticsMatcher): Boolean = count(matcher) > 0

    /** Waits for at least one node matching [matcher] and returns the first. */
    fun awaitNode(matcher: SemanticsMatcher, description: String = matcher.description, timeout: Duration = DEFAULT_WAIT): SemanticsNodeInteraction {
        awaitUntil("a node that is $description", timeout) { exists(matcher) }
        return ui.onAllNodes(matcher).onFirst()
    }

    /**
     * Waits until exactly one node matches [matcher] — no copy of it in a screen that is still
     * fading out — and returns it. What a tap should target.
     */
    fun awaitSingle(matcher: SemanticsMatcher, description: String = matcher.description, timeout: Duration = DEFAULT_WAIT): SemanticsNodeInteraction {
        awaitUntil("exactly one node that is $description", timeout) { count(matcher) == 1 }
        return ui.onNode(matcher)
    }

    fun awaitGone(matcher: SemanticsMatcher, description: String = matcher.description, timeout: Duration = DEFAULT_WAIT) {
        awaitUntil("no node to be $description", timeout) { !exists(matcher) }
    }

    fun awaitText(text: String, substring: Boolean = false, timeout: Duration = DEFAULT_WAIT): SemanticsNodeInteraction =
        awaitNode(hasText(text, substring = substring), "text \"$text\"", timeout)

    fun awaitTag(tag: String, timeout: Duration = DEFAULT_WAIT): SemanticsNodeInteraction = awaitNode(hasTestTag(tag), "tagged \"$tag\"", timeout)

    /**
     * Brings the item keyed [key] of the lazy list matching [list] on screen and waits for
     * [item] in it. Lazy lists only compose what fits, and on a phone-sized window (the JVM
     * test window is 1024x768) most of a list isn't there to be found until it is scrolled to.
     * A jump by key rather than a scroll by pixels: a lazy list animates `ScrollBy`, which a
     * frozen clock never finishes.
     */
    fun revealInList(list: SemanticsMatcher, key: Any, item: SemanticsMatcher, description: String = item.description) {
        awaitUntil("$description, scrolled to in the list") {
            exists(item) || run {
                // Throws while the list doesn't hold the key yet (still loading, say): try again next frame.
                runCatching { ui.onNode(list).performScrollToKey(key) }
                false
            }
        }
    }

    /** Taps the one node matching [matcher] once the screen has settled on it, then lets the tap's effects start. */
    fun tap(matcher: SemanticsMatcher, description: String = matcher.description) {
        awaitSingle(matcher, description).performClick()
        settle(TAP_SETTLE)
    }

    fun tapText(text: String, substring: Boolean = false) = tap(hasText(text, substring = substring), "text \"$text\"")

    /** The screen as the semantics tree sees it, plus the server's side of the story. */
    fun diagnostics(): String {
        val tree = runCatching { ui.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }.getOrElse { "<no semantics tree: $it>" }
        val requests = server.requests.takeLast(DIAGNOSTIC_REQUESTS).joinToString("\n  ", prefix = "  ")
        val unhandled = server.unhandled.joinToString("\n  ", prefix = "  ")
        return "--- Semantics tree ---\n$tree\n--- Last requests to the fake server ---\n$requests\n--- Unhandled by the fake server ---\n$unhandled"
    }

    companion object {
        /** Real time allowed for one step: a sign-in, a screen's first load. Generous for a cold emulator. */
        val DEFAULT_WAIT: Duration = 20.seconds

        private val POLL_INTERVAL: Duration = 10.milliseconds
        private val SETTLE: Duration = 1.seconds
        private val TAP_SETTLE: Duration = 500.milliseconds
        private const val FRAME_MILLIS = 16L
        private const val DIAGNOSTIC_REQUESTS = 30
    }
}

/** A whole journey, sign-in included; well past the sum of its steps so a slow emulator fails on a step, not on this. */
private val JOURNEY_TIMEOUT: Duration = 5.minutes

/** How long a journey may run before it is presumed stuck and interrupted with a thread dump. */
private val JOURNEY_WATCHDOG: Duration = 4.minutes
