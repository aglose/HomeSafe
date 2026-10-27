package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * The assistant as the screens see it: whether it is up, the bar it grew out of, and what each
 * screen says about itself for Claude to know where the person is. One per window, provided as
 * [LocalAssistant] by [AssistantRoot]; null where there is none (previews, tests), and then every
 * trigger and [AssistantScreenContext] does nothing.
 */
@Stable
class AssistantHost {
    var isOpen by mutableStateOf(false)
        private set

    /** The bar it was opened from, in the window's coordinates: where the box grows out of and shrinks back into. */
    var origin by mutableStateOf(Rect.Zero)
        private set

    /** Counts openings, so a second hold while it closes opens it afresh. */
    var openings by mutableIntStateOf(0)
        private set

    private val screens = mutableStateListOf<ScreenContext>()
    private val locks = mutableStateListOf<StateFlow<Boolean>>()

    /** A picture of whatever is under the assistant, once it is up; set by [AssistantRoot]. */
    internal var capture: (suspend () -> ImageBitmap?)? = null

    /** A hold on a bottom bar at [from]: opens it there, or closes it if it is already up. */
    fun toggle(from: Rect) {
        if (isOpen) close() else open(from)
    }

    fun open(from: Rect) {
        origin = from
        isOpen = true
        openings++
    }

    fun close() {
        isOpen = false
    }

    /** What the screens up now say about themselves, outermost first, one line each. */
    fun describeScreen(): String = screens.mapNotNull { runCatching { it.describe() }.getOrNull()?.takeIf(String::isNotBlank) }.joinToString("\n")

    /** The locks the screens up now are shown from behind (see [showsBehind]). */
    fun screenLocks(): List<StateFlow<Boolean>> = locks.toList()

    /**
     * Says the screen up now shows what [lock] holds back, for as long as it is up: a question asked
     * over it (a picture of it, at the least, goes with the question) is then taken back when the lock
     * comes back on, as one that read from behind it is. Returns what to call once it has gone.
     */
    fun showsBehind(lock: StateFlow<Boolean>): () -> Unit {
        locks += lock
        return { locks -= lock }
    }

    internal fun add(context: ScreenContext) {
        screens += context
    }

    internal fun remove(context: ScreenContext) {
        screens -= context
    }
}

internal class ScreenContext(val describe: () -> String)

/** The window's [AssistantHost]; null where the assistant isn't installed. */
val LocalAssistant = staticCompositionLocalOf<AssistantHost?> { null }

/**
 * Tells the assistant what this screen is while it is in the composition: a line for Claude (so
 * plain English, not copy, and not translated), such as `"Finance app, Budget tab"` or
 * `"Camera page for front_door"`. Kept short; Claude can look up the details with its tools, and
 * usually has a picture of the screen too. [describe] is read when a question is sent.
 */
@Composable
fun AssistantScreenContext(describe: () -> String) {
    val host = LocalAssistant.current ?: return
    val latest by rememberUpdatedState(describe)
    DisposableEffect(host) {
        val context = ScreenContext { latest() }
        host.add(context)
        onDispose { host.remove(context) }
    }
}
