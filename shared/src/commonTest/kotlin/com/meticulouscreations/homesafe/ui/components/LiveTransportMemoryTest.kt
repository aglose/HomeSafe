package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTransportMemoryTest {

    private var now = 1_000_000L
    private val memory = LiveTransportMemory(now = { now }, failuresBeforeFallback = 2, fallbackTtlMs = 600_000)
    private val lan = "http://192.168.68.64:1984/api/stream.m3u8?src=cam"
    private val tailscale = "http://100.99.163.71:1984/api/stream.m3u8?src=cam"

    @Test
    fun anUnknownStreamTriesWebRtc() {
        assertTrue(memory.allowsWebRtc(lan))
    }

    @Test
    fun theFallbackSticksAfterTheSecondFailure() {
        memory.markFailed(lan)
        assertTrue(memory.allowsWebRtc(lan), "one failure earns a retry")
        memory.markFailed(lan)
        assertFalse(memory.allowsWebRtc(lan))
    }

    @Test
    fun theFallbackExpiresAfterTheTtlMeasuredFromTheLastFailure() {
        memory.markFailed(lan)
        now += 500_000
        memory.markFailed(lan)
        now += 599_999
        assertFalse(memory.allowsWebRtc(lan))
        now += 1
        assertTrue(memory.allowsWebRtc(lan))
        // ...and the slate is clean, not one failure short of falling back again.
        memory.markFailed(lan)
        assertTrue(memory.allowsWebRtc(lan))
    }

    @Test
    fun aConnectionWipesTheStreamsRecord() {
        memory.markFailed(lan)
        memory.markFailed(lan)
        memory.markConnected(lan)
        assertTrue(memory.allowsWebRtc(lan))
        memory.markFailed(lan)
        assertTrue(memory.allowsWebRtc(lan))
    }

    @Test
    fun routesToTheSameCameraAreRememberedSeparately() {
        memory.markFailed(lan)
        memory.markFailed(lan)
        assertFalse(memory.allowsWebRtc(lan))
        assertTrue(memory.allowsWebRtc(tailscale), "a firewall problem on one route says nothing about the other")
    }

    @Test
    fun clearForgetsEverything() {
        memory.markFailed(lan)
        memory.markFailed(lan)
        memory.clear()
        assertTrue(memory.allowsWebRtc(lan))
    }
}
