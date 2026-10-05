package com.meticulouscreations.homesafe.network

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.IFF_UP
import platform.posix.sockaddr_in

/**
 * Walks `getifaddrs`, as the JVM's `NetworkInterface` does underneath: while Tailscale is
 * connected its `utun` interface carries the device's tailnet address. No entitlement is needed
 * to read one's own addresses. A list that can't be read is "can't say", not "off" (see [tailnetVerdict]).
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createTailnetProbe(): TailnetProbe = TailnetProbe {
    memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@TailnetProbe null
        try {
            val addresses = mutableListOf<String>()
            var entry = head.value
            while (entry != null) {
                val item = entry.pointed
                val address = item.ifa_addr
                if (address != null && address.pointed.sa_family.toInt() == AF_INET && (item.ifa_flags.toInt() and IFF_UP) != 0) {
                    // `s_addr` is in network byte order and read here little-endian: the first octet is the low byte.
                    val raw = address.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
                    addresses += (0..3).joinToString(".") { octet -> ((raw shr (8 * octet)) and 0xFFu).toString() }
                }
                entry = item.ifa_next
            }
            tailnetVerdict(addresses)
        } finally {
            freeifaddrs(head.value)
        }
    }
}
