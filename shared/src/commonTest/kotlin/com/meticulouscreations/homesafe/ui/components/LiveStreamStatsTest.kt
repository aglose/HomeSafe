package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The arithmetic behind the camera screen's "stats for nerds". The WebRTC figures are a real
 * pair of reports, three seconds apart, taken from a browser playing a camera's sub-stream off
 * the go2rtc server this app talks to — so the member names and units are the engine's own.
 */
class LiveStreamStatsTest {

    private fun report(
        bytesReceived: Int,
        framesDecoded: Int,
        jitterBufferDelay: Double,
        totalDecodeTime: Double,
        totalProcessingDelay: Double,
        roundTripTime: Double,
    ): List<RtcStat> = listOf(
        RtcStat("CIT01_102", "codec", mapOf("mimeType" to "video/H264", "clockRate" to 90_000)),
        // ICE tried every pair of addresses; only the one the transport settled on carries the video.
        RtcStat("CP-waiting", "candidate-pair", mapOf("nominated" to false, "state" to "waiting", "totalRoundTripTime" to 0)),
        RtcStat("CP-chosen", "candidate-pair", mapOf("nominated" to true, "state" to "succeeded", "currentRoundTripTime" to roundTripTime)),
        RtcStat("T01", "transport", mapOf("selectedCandidatePairId" to "CP-chosen", "dtlsState" to "connected")),
        RtcStat("ROV1", "remote-outbound-rtp", mapOf("kind" to "video", "bytesSent" to bytesReceived)),
        RtcStat("IT01A1", "inbound-rtp", mapOf("kind" to "audio", "bytesReceived" to 1_234)),
        RtcStat(
            "IT01V1",
            "inbound-rtp",
            mapOf(
                "kind" to "video",
                "codecId" to "CIT01_102",
                "bytesReceived" to bytesReceived,
                "framesDecoded" to framesDecoded,
                "jitterBufferDelay" to jitterBufferDelay,
                "jitterBufferEmittedCount" to framesDecoded,
                "totalDecodeTime" to totalDecodeTime,
                "totalProcessingDelay" to totalProcessingDelay,
                "frameWidth" to 640,
                "frameHeight" to 360,
                "framesPerSecond" to 25,
                "framesDropped" to 0,
                "packetsLost" to 0,
            ),
        ),
    )

    private val first = report(
        bytesReceived = 568_985,
        framesDecoded = 198,
        jitterBufferDelay = 4.760123,
        totalDecodeTime = 0.285774,
        totalProcessingDelay = 5.051062,
        roundTripTime = 0.003,
    )
    private val second = report(
        bytesReceived = 756_825,
        framesDecoded = 273,
        jitterBufferDelay = 6.430446,
        totalDecodeTime = 0.362542,
        totalProcessingDelay = 6.799624,
        roundTripTime = 0.004,
    )

    @Test
    fun theReportIsReadOffTheIncomingVideoAndThePairCarryingIt() {
        val stats = webRtcReceiverStats(second, timestampMs = 3_003.671)!!

        assertEquals(4.0, stats.roundTripTimeMs)
        assertEquals(6_430.446, stats.jitterBufferDelayMs!!, 0.001)
        assertEquals(273.0, stats.framesDecoded)
        assertEquals(756_825.0, stats.bytesReceived)
        assertEquals(640, stats.frameWidth)
        assertEquals(360, stats.frameHeight)
        assertEquals("video/H264", stats.codec)
    }

    @Test
    fun aReportWithNoIncomingVideoHasNothingToSay() {
        assertNull(webRtcReceiverStats(first.filterNot { it.id == "IT01V1" }, timestampMs = 0.0))
    }

    @Test
    fun withoutATransportEntryTheNominatedPairIsTheOneInUse() {
        val stats = webRtcReceiverStats(second.filterNot { it.type == "transport" }, timestampMs = 0.0)!!

        assertEquals(4.0, stats.roundTripTimeMs)
    }

    @Test
    fun aPeerThatIsNotBundledIsTimedOnTheVideosOwnTransport() {
        // Audio first, on a path of its own with a round trip nothing like the video's.
        val unbundled = listOf(
            RtcStat("CP-audio", "candidate-pair", mapOf("nominated" to true, "state" to "succeeded", "currentRoundTripTime" to 0.250)),
            RtcStat("T-audio", "transport", mapOf("selectedCandidatePairId" to "CP-audio")),
        ) + second.map { stat -> if (stat.id == "IT01V1") RtcStat(stat.id, stat.type, stat.members + ("transportId" to "T01")) else stat }

        assertEquals(4.0, webRtcReceiverStats(unbundled, timestampMs = 0.0)!!.roundTripTimeMs)
    }

    @Test
    fun flagsThatArriveAsNumbersStillCount() {
        // iOS hands every value over as an NSNumber, booleans included.
        val asNumbers = second.filterNot { it.type == "transport" }.map { stat ->
            RtcStat(stat.id, stat.type, stat.members.mapValues { (_, value) -> if (value is Boolean) (if (value) 1.0 else 0.0) else value })
        }

        assertEquals(4.0, webRtcReceiverStats(asNumbers, timestampMs = 0.0)!!.roundTripTimeMs)
    }

    @Test
    fun theFirstReportHasNothingToBeComparedWith() {
        assertNull(WebRtcStatsMeter().update(webRtcReceiverStats(first, timestampMs = 0.0)!!))
    }

    @Test
    fun twoReportsGiveTheDelaySinceTheFirstAndWhatItIsMadeOf() {
        val meter = WebRtcStatsMeter()
        meter.update(webRtcReceiverStats(first, timestampMs = 0.0)!!)

        val stats = meter.update(webRtcReceiverStats(second, timestampMs = 3_003.671)!!)!!

        assertEquals(LiveTransport.WEBRTC, stats.transport)
        // 75 frames spent 1.670 s between them in the jitter buffer and 0.077 s in the decoder,
        // 1.749 s from first packet to decoded; the 4 ms round trip is 2 ms each way.
        assertEquals(22, stats.bufferMs)
        assertEquals(1, stats.decodeMs)
        assertEquals(2, stats.networkMs)
        assertEquals(25, stats.delayMs)
        assertEquals(500, stats.bitrateKbps)
        assertEquals(25, stats.framesPerSecond)
        assertEquals(640, stats.width)
        assertEquals(360, stats.height)
        assertEquals(0L, stats.droppedFrames)
        assertEquals(0L, stats.packetsLost)
    }

    @Test
    fun aStretchWithNoFramesKeepsTheLastTimingsRatherThanLosingThem() {
        val meter = WebRtcStatsMeter()
        meter.update(webRtcReceiverStats(first, timestampMs = 0.0)!!)
        meter.update(webRtcReceiverStats(second, timestampMs = 3_000.0)!!)

        val stalled = meter.update(webRtcReceiverStats(second, timestampMs = 4_000.0)!!)!!

        assertEquals(22, stalled.bufferMs)
        assertEquals(25, stalled.delayMs)
        assertEquals(0, stalled.bitrateKbps)
    }

    @Test
    fun totalsThatGoBackwardsAreADifferentPeerAndStartOver() {
        val meter = WebRtcStatsMeter()
        meter.update(webRtcReceiverStats(second, timestampMs = 0.0)!!)

        assertNull(meter.update(webRtcReceiverStats(first, timestampMs = 1_000.0)!!))
    }

    @Test
    fun withoutTheEnginesOwnTotalTheDelayIsBufferPlusDecode() {
        fun withoutProcessingDelay(report: List<RtcStat>) = report.map { RtcStat(it.id, it.type, it.members - "totalProcessingDelay") }
        val meter = WebRtcStatsMeter()
        meter.update(webRtcReceiverStats(withoutProcessingDelay(first), timestampMs = 0.0)!!)

        val stats = meter.update(webRtcReceiverStats(withoutProcessingDelay(second), timestampMs = 3_003.671)!!)!!

        // 22.3 ms + 1.0 ms + 2 ms of network.
        assertEquals(25, stats.delayMs)
    }

    @Test
    fun theLiveEdgeIsUnknownBeforeAnyVideoHasArrived() {
        val edge = LiveEdgeTracker()

        assertNull(edge.behindMs(positionMs = 0, nowMs = 1_000))
        assertNull(edge.bufferedMs(positionMs = 0))
    }

    @Test
    fun thePlayheadIsBehindByWhatItHasNotPlayedPlusTheTimeSinceThatArrived() {
        val edge = LiveEdgeTracker()
        // Video up to 10.0 s was the server's newest when it arrived, at 50.0 s on the clock.
        edge.observe(mediaTimeMs = 10_000, atMs = 50_000)

        // Half a second on, the playhead is at 9.0 s: a second of video in hand, and the edge has moved on half a second.
        assertEquals(1_500, edge.behindMs(positionMs = 9_000, nowMs = 50_500))
        assertEquals(1_000, edge.bufferedMs(positionMs = 9_000))
    }

    @Test
    fun anArrivalNoticedLateDoesNotMoveTheEdgeBack() {
        val edge = LiveEdgeTracker()
        edge.observe(mediaTimeMs = 10_000, atMs = 50_000)
        // A second of new video, noticed 200 ms after the server sent it.
        edge.observe(mediaTimeMs = 11_000, atMs = 51_200)

        assertEquals(1_000, edge.behindMs(positionMs = 11_000, nowMs = 52_000))
    }

    @Test
    fun anArrivalNoticedSoonerMovesTheEdgeForward() {
        val edge = LiveEdgeTracker()
        edge.observe(mediaTimeMs = 10_000, atMs = 50_300)
        edge.observe(mediaTimeMs = 11_000, atMs = 51_000)

        assertEquals(1_000, edge.behindMs(positionMs = 11_000, nowMs = 52_000))
    }

    @Test
    fun theBestArrivalIsForgottenOnceItIsOlderThanTheWindow() {
        val edge = LiveEdgeTracker(windowMs = 10_000)
        edge.observe(mediaTimeMs = 10_000, atMs = 50_000)
        // The camera's clock runs a touch slow, so later video sits 300 ms nearer the playhead than the first arrival implies.
        edge.observe(mediaTimeMs = 15_700, atMs = 56_000)
        edge.observe(mediaTimeMs = 20_700, atMs = 61_000)

        assertEquals(0, edge.behindMs(positionMs = 20_700, nowMs = 61_000))
    }

    @Test
    fun withNothingArrivingTheGapGrowsFromTheLastArrivalHoweverOld() {
        val edge = LiveEdgeTracker(windowMs = 10_000)
        edge.observe(mediaTimeMs = 10_000, atMs = 50_000)

        assertEquals(30_000, edge.behindMs(positionMs = 10_000, nowMs = 80_000))
    }

    @Test
    fun mediaTimeStartingOverIsANewStream() {
        val edge = LiveEdgeTracker()
        edge.observe(mediaTimeMs = 100_000, atMs = 50_000)
        // The player reconnected: its media time is back near zero.
        edge.observe(mediaTimeMs = 500, atMs = 51_000)

        assertEquals(500, edge.behindMs(positionMs = 0, nowMs = 51_000))
        assertEquals(500, edge.bufferedMs(positionMs = 0))
    }

    @Test
    fun aPlayheadPastTheEdgeIsNotAheadOfLive() {
        val edge = LiveEdgeTracker()
        edge.observe(mediaTimeMs = 10_000, atMs = 50_000)

        assertEquals(0, edge.behindMs(positionMs = 10_400, nowMs = 50_100))
        assertEquals(0, edge.bufferedMs(positionMs = 10_400))
    }

    @Test
    fun aRunningTotalBecomesARatePerSecond() {
        val meter = RateMeter()

        assertNull(meter.perSecond(1_000, atMs = 0))
        assertEquals(250.0, meter.perSecond(1_500, atMs = 2_000))
        // A new session counts from zero again: no rate until its second reading.
        assertNull(meter.perSecond(100, atMs = 3_000))
        assertEquals(400.0, meter.perSecond(500, atMs = 4_000))
        // Left unread for a while and reset: the next reading is a first one, not an average over the gap.
        meter.reset()
        assertNull(meter.perSecond(900, atMs = 60_000))
        assertEquals(100.0, meter.perSecond(1_000, atMs = 61_000))
        assertEquals(500, 62_500.0.bytesPerSecondAsKbps())
    }

    @Test
    fun codecsGoByTheNamePeopleKnow() {
        assertEquals("H.264", codecDisplayName("video/H264"))
        assertEquals("H.264", codecDisplayName("video/avc"))
        assertEquals("H.264", codecDisplayName("avc1.4D0029"))
        assertEquals("H.264", codecDisplayName("h264"))
        assertEquals("H.265", codecDisplayName("video/hevc"))
        assertEquals("H.265", codecDisplayName("hvc1.1.6.L120"))
        assertEquals("AV1", codecDisplayName("video/AV1"))
    }
}
