package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasuredVideoShapesTest {

    private val doorbell = "http://box.lan:1984/api/stream.m3u8?src=test_doorbell"

    @Test
    fun aMeasuredSizeIsRememberedAsItsStreamsShape() {
        MeasuredVideoShapes.record(doorbell, 960, 1280)
        assertEquals(0.75f, MeasuredVideoShapes[doorbell])
        assertEquals(0.75f, MeasuredVideoShapes["$doorbell&video&audio=opus"], "asking for sound is the same stream")
    }

    @Test
    fun theSameNameOnAnotherServerIsAnotherStream() {
        MeasuredVideoShapes.record("http://box.lan:1984/api/stream.m3u8?src=test_porch", 960, 1280)
        assertNull(MeasuredVideoShapes["http://other.lan:1984/api/stream.m3u8?src=test_porch"])
    }

    @Test
    fun aSizeTheEngineCouldNotGiveIsNotAShape() {
        val unknown = "http://box.lan:1984/api/stream.m3u8?src=test_unknown"
        MeasuredVideoShapes.record(unknown, null, 1080)
        MeasuredVideoShapes.record(unknown, 0, 0)
        assertNull(MeasuredVideoShapes[unknown], "stats before the first frame have no size; the video keeps stretching until one comes")
    }
}
