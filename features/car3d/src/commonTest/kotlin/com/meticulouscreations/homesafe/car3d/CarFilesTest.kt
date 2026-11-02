package com.meticulouscreations.homesafe.car3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CarFilesTest {
    private class Bytes {
        val out = ArrayList<Byte>()

        fun text(s: String) = s.encodeToByteArray().forEach { out.add(it) }

        fun byte(v: Int) {
            out.add(v.toByte())
        }

        fun int(v: Int) = (0..3).forEach { byte(v shr (it * 8)) }

        fun float(v: Float) = int(v.toBits())

        fun zeros(n: Int) = repeat(n) { byte(0) }

        fun align() {
            while (out.size % 4 != 0) byte(0)
        }

        fun array() = out.toByteArray()
    }

    @Test
    fun readsWhereEachPartSits() {
        val b = Bytes()
        b.text("HSCM")
        b.int(1)
        b.int(2)
        b.float(0.5f)
        b.byte(5)
        b.text("paint")
        b.int(3)
        b.int(6)
        b.byte(2)
        b.align()
        b.byte(3)
        b.text("rim")
        b.int(1)
        b.int(3)
        b.byte(4)
        b.align()
        val start = b.out.size
        b.zeros(3 * 12 + 6 * 2 + 12 + 3 * 4)

        val file = CarMeshFile(b.array())

        assertEquals(0.5f, file.unit)
        val (paint, rim) = file.parts
        assertEquals("paint", paint.name)
        assertEquals(start, paint.vertexOffset)
        assertEquals(36, paint.vertexBytes)
        assertEquals(start + 36, paint.indexOffset)
        assertEquals(12, paint.indexBytes)
        assertEquals("rim", rim.name)
        assertEquals(start + 48, rim.vertexOffset)
        assertEquals(4, rim.indexSize)
        assertEquals(12, rim.indexBytes)
    }

    @Test
    fun refusesAnotherKindOfFile() {
        assertFailsWith<IllegalStateException> { CarMeshFile("HSCE000000000000".encodeToByteArray()) }
    }

    @Test
    fun readsAnEnvironmentsHeader() {
        val b = Bytes()
        b.text("HSCE")
        b.int(1)
        b.int(128)
        b.int(6)
        repeat(27) { b.float(it.toFloat()) }

        val file = CarEnvironmentFile(b.array())

        assertEquals(128, file.size)
        assertEquals(6, file.levels)
        assertEquals(26f, file.harmonics[26])
        assertEquals(b.out.size, file.pixelsOffset)
    }

    @Test
    fun andrewsCarIsTheBlueOne() {
        assertEquals(CarModel.BLUE_PERFORMANCE, carModelFor("andrews_tesla", "Andrew's Tesla"))
        assertEquals(CarModel.RED_LONG_RANGE, carModelFor("sarahs_tesla", "Sarah's Tesla"))
    }

    @Test
    fun theOrbitStaysAboveTheGroundAndWithinZoom() {
        val orbit = CarOrbitState()
        orbit.turn(10f, -500f)
        assertEquals(CarOrbitState.START_AZIMUTH + 10f, orbit.azimuth)
        assertEquals(1.5f, orbit.elevation)
        orbit.turn(0f, 500f)
        assertEquals(78f, orbit.elevation)
        repeat(50) { orbit.zoomBy(1.5f) }
        assertEquals(3.6f, orbit.zoom)
    }
}
