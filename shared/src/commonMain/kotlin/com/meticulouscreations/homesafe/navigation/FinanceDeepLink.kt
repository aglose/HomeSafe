package com.meticulouscreations.homesafe.navigation

import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a notification about the household's money lands: the finance app, open on one of its
 * tabs. A budget alert opens it on Budget (see `ShellNavigation.openFinanceFromNotifications`).
 *
 * `homesafe://finance?tab=budget`, beside [MomentDeepLink]'s `homesafe://moment?…`.
 */
data class FinanceDeepLink(val tab: FinanceTab) {

    fun toUri(): String = "$SCHEME://$HOST?$KEY_TAB=${tab.name.lowercase()}"

    companion object {
        const val SCHEME = MomentDeepLink.SCHEME
        const val HOST = "finance"
        const val KEY_TAB = "tab"

        /** From a `homesafe://finance?…` URI; null for anything else. A tab this build doesn't know opens the Wallet. */
        fun fromUri(uri: String): FinanceDeepLink? {
            val url = runCatching { Url(uri) }.getOrNull() ?: return null
            if (url.protocol.name != SCHEME || url.host != HOST) return null
            val named = url.parameters[KEY_TAB]
            return FinanceDeepLink(FinanceTab.entries.firstOrNull { it.name.equals(named, ignoreCase = true) } ?: FinanceTab.WALLET)
        }
    }
}

/**
 * The hand-off between a platform that heard a tap on a finance notification and the Compose
 * shell that can act on it: a state rather than an event, for the reason [MomentDeepLinks] is one
 * (a tap that cold-starts the app arrives before the shell exists).
 */
object FinanceDeepLinks {
    private val _pending = MutableStateFlow<FinanceDeepLink?>(null)
    val pending: StateFlow<FinanceDeepLink?> = _pending.asStateFlow()

    fun open(link: FinanceDeepLink) {
        _pending.value = link
    }

    /** Clears [link] once it has been acted on; a newer one that arrived meanwhile is left for the next look. */
    fun consume(link: FinanceDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
