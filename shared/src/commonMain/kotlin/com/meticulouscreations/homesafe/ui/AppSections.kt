package com.meticulouscreations.homesafe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource

/** One of an app's sections (Finance's Wallet, Fitness's Lifts): its name and its picture, as its own nav shows them. */
@Immutable
data class AppSection(val label: StringResource, val icon: ImageVector)

/**
 * Where an app beside the shell's sidebar hands up its sections, for the sidebar to list under
 * the app's own row, as a Mac's Mail lists its mailboxes: a desktop has a list of everything down
 * the side already, and a second nav floating over the foot of the page (built for a thumb) is
 * one too many. The app then draws neither its own nav nor its close button (the sidebar is the
 * way out).
 */
@Stable
class AppSectionsHost {
    var sections by mutableStateOf<List<AppSection>>(emptyList())
        private set

    /** Which of [sections] is up; -1 while a page beyond them is (a quote's chart, a run). */
    var selected by mutableIntStateOf(-1)
        private set

    private var onSelect: (Int) -> Unit = {}

    internal fun publish(sections: List<AppSection>, selected: Int, onSelect: (Int) -> Unit) {
        if (this.sections != sections) this.sections = sections
        this.selected = selected
        this.onSelect = onSelect
    }

    internal fun clear() {
        sections = emptyList()
        selected = -1
        onSelect = {}
    }

    /** The sidebar's row for section [index] was clicked: the app goes to it, from whatever page it is on. */
    fun select(index: Int) = onSelect(index)
}

/** The sidebar's host while the app drawn here is beside it; null over the shell, on a phone, and in an iPad's sidebar tab. */
val LocalAppSections = staticCompositionLocalOf<AppSectionsHost?> { null }

/**
 * For an app: hands [sections] up to the sidebar beside it, with [selected] (-1 for none) and
 * [onSelect] for its rows, while there is one ([LocalAppSections]). True then, and the app leaves
 * its own nav and close button out; false everywhere else, where it draws them as it always has.
 */
@Composable
fun publishAppSections(sections: List<AppSection>, selected: Int, onSelect: (Int) -> Unit): Boolean {
    val host = LocalAppSections.current ?: return false
    val currentOnSelect by rememberUpdatedState(onSelect)
    SideEffect { host.publish(sections, selected) { currentOnSelect(it) } }
    DisposableEffect(host) { onDispose { host.clear() } }
    return true
}
