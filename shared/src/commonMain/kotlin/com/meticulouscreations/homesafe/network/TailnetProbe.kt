package com.meticulouscreations.homesafe.network

import io.ktor.http.Url
import kotlinx.io.IOException

/**
 * Whether this device is on a tailnet right now: Tailscale gives every device it has connected an
 * address in its own range, so a device with none has Tailscale switched off, signed out or not
 * installed. That is the difference between "the server is down" and "this phone can't get to
 * it", which from inside the app otherwise look the same: nothing answers.
 */
fun interface TailnetProbe {
    /** True with a tailnet address on some interface, false with none, null where the platform can't say (a browser). */
    fun isOnTailnet(): Boolean?
}

/** Builds the platform's [TailnetProbe]: Android, iOS and desktop read the device's own addresses; the web can't. */
expect fun createTailnetProbe(): TailnetProbe

/** Whether [address], a dotted IPv4 address, is in Tailscale's range: 100.64.0.0/10, the carrier-grade NAT block it hands addresses out of. */
internal fun isTailnetIpv4(address: String): Boolean {
    val parts = address.trim().split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return false }
    return octets[0] == 100 && octets[1] in 64..127
}

/**
 * What a device's own IPv4 addresses say: true with a tailnet one among them, false with others
 * but none of Tailscale's, and null with nothing to go on. A device with no address but its
 * loopback has no network at all (flight mode), or wouldn't show its interfaces, and neither is
 * Tailscale's doing: blaming it there would send someone to the wrong app.
 */
internal fun tailnetVerdict(ipv4Addresses: List<String>): Boolean? {
    val real = ipv4Addresses.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("127.") }
    if (real.isEmpty()) return null
    return real.any(::isTailnetIpv4)
}

/** Whether [url] is reached through Tailscale: its host is a tailnet address or a MagicDNS name. */
internal fun isTailnetUrl(url: String): Boolean {
    val host = runCatching { Url(url).host }.getOrNull()?.lowercase() ?: return false
    return isTailnetIpv4(host) || host.endsWith(".ts.net")
}

/**
 * Whether this failure is the network's: no route, a refused or reset connection, a timeout, a
 * name that wouldn't resolve. Those are what "nothing answered" means. Anything else came from a
 * server that did answer (a refusal, a body that wouldn't parse) and has to keep its own words.
 */
internal fun Throwable.isTransportFailure(): Boolean = this is IOException
