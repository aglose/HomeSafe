package com.meticulouscreations.homesafe.domain.model

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.connection_route_local_network
import homesafe.shared.generated.resources.connection_route_local_network_short
import homesafe.shared.generated.resources.connection_route_tailscale
import org.jetbrains.compose.resources.StringResource

/**
 * Which of the server's addresses the app is talking to right now.
 *
 * [shortLabel] is [label] for where there is only room for a word: the top bar's badge, which
 * sits beside the centred title.
 */
enum class ConnectionRoute(val label: StringResource, val shortLabel: StringResource = label) {
    /** The server's private LAN address: same Wi-Fi, no VPN hop. The fast path for video. */
    LOCAL_NETWORK(Res.string.connection_route_local_network, Res.string.connection_route_local_network_short),

    /** The server's Tailscale address: works from anywhere, at the cost of a WireGuard (and possibly relay) hop. */
    TAILSCALE(Res.string.connection_route_tailscale),
}

/**
 * The server the app is signed in to, and which of its addresses is in use right now.
 *
 * The route is chosen by *probing*, never by inspecting the network: the app tries the local
 * address and uses it if it answers, otherwise falls back to [serverUrl]. That makes it
 * indifferent to which SSID the phone joined (a router that splits one LAN into several SSIDs
 * still routes between them) and to whether Tailscale happens to be up.
 */
data class ActiveConnection(
    /**
     * The Tailscale (or other remote) URL the user signed in with. Identifies the server —
     * e.g. as the camera cache key — regardless of which route is active.
     */
    val serverUrl: String,
    /** The server's private LAN URL, if the user configured one. */
    val localUrl: String?,
    val route: ConnectionRoute,
) {
    /** The base URL every API request, snapshot, and stream should use right now. */
    val activeUrl: String
        get() = if (route == ConnectionRoute.LOCAL_NETWORK) checkNotNull(localUrl) else serverUrl
}
