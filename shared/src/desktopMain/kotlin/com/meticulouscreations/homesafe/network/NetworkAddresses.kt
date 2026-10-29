package com.meticulouscreations.homesafe.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** The IPv4 addresses on the computer's interfaces that are up, by interface: what the network looks like from here. */
internal fun upIpv4Addresses(): Map<String, List<String>> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp }.getOrDefault(false) }
        .associate { networkInterface ->
            networkInterface.name to networkInterface.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        }
        .filterValues { it.isNotEmpty() }
}.getOrDefault(emptyMap())
