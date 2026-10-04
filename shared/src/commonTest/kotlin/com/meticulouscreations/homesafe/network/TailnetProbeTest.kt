package com.meticulouscreations.homesafe.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which addresses are Tailscale's, and which server addresses are reached through it. */
class TailnetProbeTest {

    @Test
    fun tailscaleHandsOutAddressesFromTheCarrierGradeNatBlock() {
        assertTrue(isTailnetIpv4("100.99.163.71"))
        assertTrue(isTailnetIpv4("100.64.0.0"))
        assertTrue(isTailnetIpv4("100.127.255.255"))
        assertTrue(isTailnetIpv4(" 100.118.204.121 "))
    }

    @Test
    fun everythingElseIsNotATailnetAddress() {
        assertFalse(isTailnetIpv4("100.63.255.255"), "just below the block")
        assertFalse(isTailnetIpv4("100.128.0.1"), "just above it")
        assertFalse(isTailnetIpv4("192.168.68.65"))
        assertFalse(isTailnetIpv4("10.0.2.2"))
        assertFalse(isTailnetIpv4("100.99.163"))
        assertFalse(isTailnetIpv4("100.99.163.71.5"))
        assertFalse(isTailnetIpv4("100.99.163.999"))
        assertFalse(isTailnetIpv4("fd7a:115c:a1e0::c401:a3ca"))
        assertFalse(isTailnetIpv4(""))
    }

    @Test
    fun aDeviceWithATailnetAddressAmongItsOwnIsOnTheTailnet() {
        assertEquals(true, tailnetVerdict(listOf("127.0.0.1", "192.168.68.84", "100.118.204.121")))
        assertEquals(true, tailnetVerdict(listOf("100.118.204.121")), "on cellular with only the tunnel showing an IPv4 address")
    }

    @Test
    fun aDeviceOnANetworkWithoutOneIsNot() {
        assertEquals(false, tailnetVerdict(listOf("127.0.0.1", "192.168.68.84")))
        assertEquals(false, tailnetVerdict(listOf("10.142.7.19")))
    }

    @Test
    fun aDeviceWithNoNetworkAtAllSaysNothingAboutTailscale() {
        assertNull(tailnetVerdict(listOf("127.0.0.1")), "flight mode")
        assertNull(tailnetVerdict(emptyList()), "the interfaces wouldn't show")
        assertNull(tailnetVerdict(listOf("", " ")))
    }

    @Test
    fun aServerIsReachedThroughTailscaleByItsAddressOrItsMagicDnsName() {
        assertTrue(isTailnetUrl("http://100.99.163.71:8971"))
        assertTrue(isTailnetUrl("https://debian-surveillance.tail4c441a.ts.net"))
        assertTrue(isTailnetUrl("http://Debian-Surveillance.Tail4c441a.TS.net:8971/"))
    }

    @Test
    fun aHomeNetworkOrPublicAddressIsNot() {
        assertFalse(isTailnetUrl("http://192.168.68.65:8971"))
        assertFalse(isTailnetUrl("https://frigate.example.com"))
        assertFalse(isTailnetUrl("http://nots.net:8971"), "a name that merely ends in the letters")
        assertFalse(isTailnetUrl(""))
    }
}
