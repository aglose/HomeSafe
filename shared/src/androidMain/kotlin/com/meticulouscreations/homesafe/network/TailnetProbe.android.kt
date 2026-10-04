package com.meticulouscreations.homesafe.network

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Reads the device's own interface addresses, which needs no permission and no callback: while
 * Tailscale is connected its tunnel interface carries the device's tailnet address. An interface
 * list that can't be read at all is "can't say", not "off" (see [tailnetVerdict]).
 */
actual fun createTailnetProbe(): TailnetProbe = TailnetProbe {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@TailnetProbe null
        tailnetVerdict(
            interfaces.asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .toList(),
        )
    } catch (_: Exception) {
        null
    }
}
