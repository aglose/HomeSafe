package com.meticulouscreations.homesafe.network

import kotlin.test.Test
import kotlin.test.assertEquals

class Go2rtcStreamsTest {

    /**
     * Shaped like go2rtc 1.9.10's `GET /api/streams` on the home server: an idle stream carries
     * only its configured `url`, a running one much more. The Nest URLs' secrets are made up.
     */
    private val streamsJson = """{
      "hikvision_1_sub":{"producers":[{"id":2,"format_name":"rtsp","protocol":"rtsp+tcp","remote_addr":"127.0.0.1:46788","source":"exec:ffmpeg -i rtsp://x","url":"rtsp://user:pass@192.168.1.65:554/Streaming/Channels/102","sdp":"v=0"}],"consumers":[{"id":3}]},
      "amcrest_1_google":{"producers":[{"url":"ffmpeg:amcrest_1#video=h264#audio=copy"}],"consumers":[]},
      "nursery":{"producers":[{"url":"nest:?client_id=id&client_secret=secret&refresh_token=token&project_id=p&device_id=d2"}],"consumers":null},
      "living_room":{"producers":[{"url":"nest:?client_id=id&client_secret=secret&refresh_token=token&project_id=p&device_id=d1"}]},
      "empty":{"producers":null}
    }"""

    @Test
    fun onlyStreamsPullingANestCameraAreIndoorCameras() {
        assertEquals(listOf("living_room", "nursery"), nestStreamNames(parseGo2rtcStreams(streamsJson)))
    }

    @Test
    fun aServerWithoutNestCamerasHasNone() {
        assertEquals(emptyList(), nestStreamNames(parseGo2rtcStreams("""{"front":{"producers":[{"url":"rtsp://cam/1"}]}}""")))
        assertEquals(emptyList(), nestStreamNames(parseGo2rtcStreams("{}")))
    }

    @Test
    fun aNestCameraHasTheSubStreamBesideItAsItsGridStream() {
        val streams = parseGo2rtcStreams(
            """{
              "living_room":{"producers":[{"url":"nest:?device_id=d1"}]},
              "living_room_sub":{"producers":[{"url":"ffmpeg:living_room#video=h264#width=640#hardware=cuda"}]},
              "kitchen":{"producers":[{"url":"nest:?device_id=d2"}]}
            }""",
        )
        assertEquals(
            listOf(NestStream("kitchen"), NestStream("living_room", gridStreamName = "living_room_sub")),
            nestStreams(streams),
            "the sub stream is the camera's card stream, never a camera of its own",
        )
    }

    @Test
    fun aStillIsGo2rtcsFrameOfTheStream() {
        assertEquals("http://100.99.163.71:1984/api/frame.jpeg?src=living_room_sub", frigateLiveStillUrl("https://100.99.163.71:8971", "living_room_sub"))
    }

    @Test
    fun theStreamsListIsOnGo2rtcsPortOnFrigatesHost() {
        assertEquals("http://100.99.163.71:1984/api/streams", frigateGo2rtcStreamsUrl("https://100.99.163.71:8971"))
        assertEquals("http://frigate.lan:1984/api/streams", frigateGo2rtcStreamsUrl("http://frigate.lan/"))
    }
}
