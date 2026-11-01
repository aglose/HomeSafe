package com.meticulouscreations.homesafe.navigation

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a package notification's tap lands: the packages app, opened over whatever was up, on the
 * parcel it was about. `homesafe://packages?package=<id>`; plain `homesafe://packages` opens the
 * app where it opens (the morning's list is about no one parcel).
 */
data class PackageDeepLink(val packageId: String? = null) {

    fun toUri(): String = "$SCHEME://$HOST" + packageId?.let { "?$KEY_PACKAGE=${it.encodeURLParameter()}" }.orEmpty()

    companion object {
        const val SCHEME = MomentDeepLink.SCHEME
        const val HOST = "packages"

        /** The push's own name for the parcel's id (`PackageAlert.KEY_PACKAGE`). */
        const val KEY_PACKAGE = "package"

        /** From a push's data or iOS `userInfo`; null when they aren't a package's. An empty id is the morning's list. */
        fun from(values: (String) -> String?): PackageDeepLink? = values(KEY_PACKAGE)?.let { id -> PackageDeepLink(id.takeIf { it.isNotBlank() }) }

        /** From a `homesafe://packages…` URI; null for anything else. */
        fun fromUri(uri: String?): PackageDeepLink? {
            val url = uri?.let { runCatching { Url(it) }.getOrNull() } ?: return null
            if (url.protocol.name != SCHEME || url.host != HOST) return null
            return PackageDeepLink(url.parameters[KEY_PACKAGE]?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * The hand-off between a platform that heard a tap on a package notification and the shell that
 * opens the app: a state rather than an event, for the reason [MomentDeepLinks] is one (a tap that
 * cold-starts the app arrives before the shell exists).
 */
object PackageDeepLinks {
    private val _pending = MutableStateFlow<PackageDeepLink?>(null)
    val pending: StateFlow<PackageDeepLink?> = _pending.asStateFlow()

    fun open(link: PackageDeepLink) {
        _pending.value = link
    }

    fun consume(link: PackageDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
