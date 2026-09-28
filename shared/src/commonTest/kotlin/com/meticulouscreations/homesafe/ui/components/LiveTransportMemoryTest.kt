package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTransportMemoryTest {

    private var now = 1_000_000L
    private val memory = LiveTransportMemory(now = { now }, failuresBeforeFallback = 2, fallbackTtlMs = 600_000, provenTtlMs = 600_000)
    private val lan = "http://192.168.68.64:1984/api/stream.m3u8?src=cam"
    private val tailscale = "http://100.99.163.71:1984/api/stream.m3u8?src=cam"

    /** What a previous launch left behind, and what this one writes back. */
    private class FakeStore(var saved: Map<String, Long> = emptyMap()) : LiveTransportMemory.Store {
        override fun load() = saved
        override fun save(connectedAt: Map<String, Long>) {
            saved = connectedAt
        }
    }

    @Test
    fun aStreamProvenInAnEarlierLaunchIsStillProven_untilItsWindowRunsOut() {
        val store = FakeStore(mapOf(lan to now - 500_000, tailscale to now - 600_000))
        memory.restore(store)

        assertTrue(memory.recentlyConnected(lan))
        assertFalse(memory.recentlyConnected(tailscale), "joined longer ago than the proven window")
        now += 100_000
        assertFalse(memory.recentlyConnected(lan))
    }

    @Test
    fun joinsAndFailuresAreWrittenBack_soTheNextLaunchSeesThem() {
        val store = FakeStore()
        memory.restore(store)

        memory.markConnected(lan)
        memory.markConnected(tailscale)
        assertTrue(store.saved.keys == setOf(lan, tailscale))

        // A failure unproves the stream for the next launch too; its failure count stays behind.
        memory.markFailed(tailscale)
        assertTrue(store.saved.keys == setOf(lan))
        val nextLaunch = LiveTransportMemory(now = { now }, provenTtlMs = 600_000)
        nextLaunch.restore(store)
        assertTrue(nextLaunch.recentlyConnected(lan))
        assertFalse(nextLaunch.recentlyConnected(tailscale))
        assertTrue(nextLaunch.allowsWebRtc(tailscale))
    }

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

    @Test
    fun aStreamIsUnprovenUntilItHasJoined() {
        assertFalse(memory.recentlyConnected(lan))
        memory.markConnected(lan)
        assertTrue(memory.recentlyConnected(lan))
        assertFalse(memory.recentlyConnected(tailscale), "a join on one route proves nothing about the other")
    }

    @Test
    fun aProvenStreamLapsesAfterTheTtl() {
        memory.markConnected(lan)
        now += 599_999
        assertTrue(memory.recentlyConnected(lan))
        now += 1
        assertFalse(memory.recentlyConnected(lan))
    }

    @Test
    fun aFailureRevokesTheProof() {
        memory.markConnected(lan)
        memory.markFailed(lan)
        assertFalse(memory.recentlyConnected(lan))
        assertTrue(memory.allowsWebRtc(lan), "one failure still earns a retry, just a shadowed one")
    }

    @Test
    fun clearForgetsProofToo() {
        memory.markConnected(lan)
        memory.clear()
        assertFalse(memory.recentlyConnected(lan))
    }
}
