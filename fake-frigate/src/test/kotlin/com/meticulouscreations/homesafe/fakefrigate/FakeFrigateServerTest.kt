package com.meticulouscreations.homesafe.fakefrigate

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake Frigate itself, over plain `HttpURLConnection`: if these fail, every journey built on
 * it fails for a reason that has nothing to do with the app, so they are checked on their own.
 */
class FakeFrigateServerTest {

    private val server = FakeFrigateServer().start()

    @AfterTest
    fun stop() = server.close()

    private class Response(val status: Int, val body: String, val cookie: String?)

    private fun call(method: String, path: String, body: String? = null, cookie: String? = null): Response {
        val connection = URI("${server.baseUrl}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val setCookie = connection.getHeaderField("Set-Cookie")?.substringBefore(';')
        return Response(status, text, setCookie)
    }

    /** A GET's status and raw body, for the video the server sends. */
    private fun fetch(path: String, cookie: String? = null): Pair<Int, ByteArray> {
        val connection = URI("${server.baseUrl}$path").toURL().openConnection() as HttpURLConnection
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        val bytes = (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return status to bytes
    }

    /** How many boxes of [type] an MP4 holds at its top level. */
    private fun ByteArray.topLevelBoxes(type: String): Int {
        var at = 0
        var count = 0
        while (at + 8 <= size) {
            if (String(this, at + 4, 4, Charsets.ISO_8859_1) == type) count++
            at += java.nio.ByteBuffer.wrap(this, at, 4).int
        }
        assertEquals(size, at, "the boxes add up to the file")
        return count
    }

    private fun signIn(user: FakeUser = FakeFrigateState.ADMIN): String {
        val response = call("POST", "/api/login", """{"user":"${user.username}","password":"${user.password}"}""")
        assertEquals(200, response.status)
        return checkNotNull(response.cookie) { "login set no session cookie" }
    }

    @Test
    fun versionAnswersWithoutASession() {
        assertEquals(200, call("GET", "/api/version").status)
    }

    @Test
    fun theApiRefusesRequestsWithoutASession() {
        assertEquals(401, call("GET", "/api/config").status)
        assertEquals(401, call("GET", "/api/profile").status)
    }

    @Test
    fun aWrongPasswordIsRefused() {
        val response = call("POST", "/api/login", """{"user":"admin","password":"nope"}""")
        assertEquals(401, response.status)
    }

    @Test
    fun aSignedInSessionReadsTheConfigAndProfile() {
        val cookie = signIn()
        val config = call("GET", "/api/config", cookie = cookie)
        assertEquals(200, config.status)
        assertContains(config.body, "\"front_door\"")
        assertContains(config.body, "\"driveway_sub\"")
        assertContains(call("GET", "/api/profile", cookie = cookie).body, "\"role\":\"admin\"")
    }

    @Test
    fun eventsAreNewestFirstAndFilterable() {
        val cookie = signIn()
        val all = call("GET", "/api/events?limit=100", cookie = cookie).body
        assertTrue(all.indexOf("frnt01") < all.indexOf("drv001"), "newest first")
        val driveway = call("GET", "/api/events?cameras=driveway", cookie = cookie).body
        assertFalse("front_door" in driveway)
        assertContains(driveway, "sarahs_tesla")
    }

    @Test
    fun aDetectionSwitchIsAppliedToTheConfig() {
        val cookie = signIn()
        val body = """{"requires_restart":0,"update_topic":"config/cameras/back_yard/detect","config_data":{"cameras":{"back_yard":{"detect":{"enabled":false}}}}}"""
        assertEquals(200, call("PUT", "/api/config/set", body, cookie).status)
        assertFalse(server.state.camera("back_yard")!!.detectEnabled)
        server.awaitRequest { it.path == "/api/config/set" }
    }

    @Test
    fun aViewerMayNotWriteConfig() {
        val cookie = signIn(FakeFrigateState.VIEWER)
        val body = """{"requires_restart":0,"update_topic":"x","config_data":{}}"""
        assertEquals(401, call("PUT", "/api/config/set", body, cookie).status)
    }

    @Test
    fun filingAFaceAttemptMovesItToThatPerson() {
        val cookie = signIn()
        val attempt = server.state.faces.getValue(FakeFrigateState.TRAIN_FOLDER).first()
        val response = call("POST", "/api/faces/train/Bob/classify", """{"training_file":"$attempt"}""", cookie)
        assertEquals(200, response.status)
        assertTrue(attempt in server.state.faces.getValue("Bob"))
        assertTrue(server.state.faces.getValue(FakeFrigateState.TRAIN_FOLDER).isEmpty())
    }

    @Test
    fun recordingsAreShortEnoughForTheAppToPlay() {
        // The app treats a segment of ten minutes or more as unplayable and leaves it off the timeline.
        val longest = server.state.recordings.maxOf { it.endTime - it.startTime }
        assertTrue(longest < 600.0, "segments are $longest s long")
        val cookie = signIn()
        val now = System.currentTimeMillis() / 1000.0
        val body = call("GET", "/api/front_door/recordings?after=${now - 3 * 3600}&before=$now", cookie = cookie).body
        assertTrue(body.split("start_time").size - 1 >= 170, "three hours of one-minute segments")
    }

    @Test
    fun aStretchOfRecordingDownloadsAsAnMp4OfThatLength() {
        val cookie = signIn()
        val end = System.currentTimeMillis() / 1000 - 60
        val (status, clip) = fetch("/api/front_door/start/${end - 20}.000/end/$end.000/clip.mp4", cookie)
        assertEquals(200, status)
        assertEquals(1, clip.topLevelBoxes("ftyp"))
        assertEquals(1, clip.topLevelBoxes("moov"))
        // Two seconds a fragment.
        assertEquals(10, clip.topLevelBoxes("moof"))
        assertEquals(10, clip.topLevelBoxes("mdat"))
        // Fragments are numbered from one and stamped two seconds (20,000 ticks) apart.
        val second = FakeVideo.fragment(1)
        val third = FakeVideo.fragment(2)
        val differing = second.indices.filter { second[it] != third[it] }
        assertTrue(differing.isNotEmpty() && differing.size <= 4, "only the number and the time differ: $differing")
    }

    @Test
    fun aStretchOfRecordingPlaysAsAnHlsPlaylistWithItsSegmentsBesideIt() {
        val cookie = signIn()
        val end = System.currentTimeMillis() / 1000 - 60
        val folder = "/vod/driveway/start/${end - 9}.000/end/$end.000"
        val playlist = call("GET", "$folder/index.m3u8", cookie = cookie)
        assertEquals(200, playlist.status)
        assertContains(playlist.body, "#EXT-X-MAP:URI=\"init.mp4\"")
        assertEquals(listOf("seg-0.m4s", "seg-1.m4s", "seg-2.m4s", "seg-3.m4s", "seg-4.m4s"), playlist.body.lines().filter { it.endsWith(".m4s") })
        assertContains(playlist.body, "#EXT-X-ENDLIST")
        val (initStatus, init) = fetch("$folder/init.mp4", cookie)
        assertEquals(200, initStatus)
        assertEquals(1, init.topLevelBoxes("moov"))
        val (segmentStatus, segment) = fetch("$folder/seg-4.m4s", cookie)
        assertEquals(200, segmentStatus)
        assertEquals(1, segment.topLevelBoxes("moof"))
        assertEquals(404, fetch("$folder/seg-5.m4s", cookie).first, "nothing past the playlist's end")
    }

    @Test
    fun aDetectionsClipPlaysAndDownloadsForAsLongAsItLasted() {
        val cookie = signIn()
        // The driveway's car was there for two minutes.
        val car = server.state.events.first { it.subLabel == "sarahs_tesla" }
        val playlist = call("GET", "/vod/event/${car.id}/index.m3u8", cookie = cookie)
        assertEquals(200, playlist.status)
        assertEquals(60, playlist.body.lines().count { it.endsWith(".m4s") })
        val (status, clip) = fetch("/api/events/${car.id}/clip.mp4", cookie)
        assertEquals(200, status)
        assertEquals(60, clip.topLevelBoxes("moof"))
        assertEquals(404, fetch("/api/events/no-such-event/clip.mp4", cookie).first)
    }

    @Test
    fun aGapInTheRecordingIsLeftOutOfThePlaylistAndTheClip() {
        // Twenty seconds on disk, thirty missing, then ten more: a minute's span holds thirty seconds of video.
        server.state.edit {
            recordings.clear()
            recordings += FakeRecording("front_door", 1_000.0, 1_020.0, motion = 0, objects = 0)
            recordings += FakeRecording("front_door", 1_050.0, 1_060.0, motion = 0, objects = 0)
        }
        val cookie = signIn()
        val playlist = call("GET", "/vod/front_door/start/1000.000/end/1060.000/index.m3u8", cookie = cookie)
        assertEquals(15, playlist.body.lines().count { it.endsWith(".m4s") })
        assertEquals(15, fetch("/api/front_door/start/1000.000/end/1060.000/clip.mp4", cookie).second.topLevelBoxes("moof"))
        // A span that starts and ends inside segments takes only its share of each.
        assertEquals(6, fetch("/api/front_door/start/1010.000/end/1052.000/clip.mp4", cookie).second.topLevelBoxes("moof"))
        assertEquals(404, fetch("/api/front_door/start/1025.000/end/1045.000/clip.mp4", cookie).first, "nothing but the gap")
    }

    @Test
    fun videoNeedsASessionAndSomethingRecorded() {
        val end = System.currentTimeMillis() / 1000 - 60
        val clip = "/api/front_door/start/${end - 20}.000/end/$end.000/clip.mp4"
        assertEquals(401, fetch(clip).first)
        val cookie = signIn()
        // Long before the six hours the household has on disk.
        assertEquals(404, fetch("/api/front_door/start/1000.000/end/1020.000/clip.mp4", cookie).first)
        assertEquals(404, fetch("/vod/front_door/start/1000.000/end/1020.000/index.m3u8", cookie).first)
        assertEquals(404, fetch("/api/garage/start/${end - 20}.000/end/$end.000/clip.mp4", cookie).first, "no such camera")
    }

    @Test
    fun injectedFailuresAnswerWithTheirStatus() {
        val cookie = signIn()
        server.state.edit { failures["/api/stats"] = 500 }
        assertEquals(500, call("GET", "/api/stats", cookie = cookie).status)
    }

    @Test
    fun imagesAreServedAndUnknownPathsAreReported() {
        val cookie = signIn()
        assertEquals(200, call("GET", "/api/front_door/latest.jpg?h=480", cookie = cookie).status)
        assertEquals(404, call("GET", "/api/nothing-here", cookie = cookie).status)
        assertEquals("/api/nothing-here", server.unhandled.single().path)
    }
}
