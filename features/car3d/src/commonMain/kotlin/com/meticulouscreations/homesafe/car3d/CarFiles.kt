package com.meticulouscreations.homesafe.car3d

/** Reads a little-endian file made by tools/car-model, a field at a time. */
internal class LittleEndianReader(private val data: ByteArray, var position: Int = 0) {
    fun tag(expected: String) {
        val found = data.decodeToString(position, position + expected.length)
        check(found == expected) { "expected a $expected file, got $found" }
        position += expected.length
    }

    fun byte(): Int = data[position++].toInt() and 0xff

    fun int(): Int {
        val value = (data[position].toInt() and 0xff) or
            ((data[position + 1].toInt() and 0xff) shl 8) or
            ((data[position + 2].toInt() and 0xff) shl 16) or
            ((data[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return value
    }

    fun float(): Float = Float.fromBits(int())

    fun string(length: Int): String = data.decodeToString(position, position + length).also { position += length }

    fun alignTo4() {
        position = (position + 3) and 3.inv()
    }
}

/** One material's worth of a mesh file: where its vertices and indices sit in the bytes. */
internal class MeshPart(val name: String, val vertexOffset: Int, val vertexBytes: Int, val indexOffset: Int, val indexBytes: Int, val indexSize: Int)

/** A `.car` file (tools/car-model/export.py). */
internal class CarMeshFile(val data: ByteArray) {
    val unit: Float
    val parts: List<MeshPart>

    init {
        val reader = LittleEndianReader(data)
        reader.tag("HSCM")
        check(reader.int() == 1) { "unknown mesh version" }
        val count = reader.int()
        unit = reader.float()
        val heads = List(count) {
            val nameLength = reader.byte()
            val name = reader.string(nameLength)
            val vertices = reader.int()
            val indices = reader.int()
            val size = reader.byte()
            reader.alignTo4()
            Triple(name, vertices to indices, size)
        }
        var at = reader.position
        parts = heads.map { (name, counts, size) ->
            val vertexBytes = counts.first * VERTEX_SIZE
            val indexBytes = counts.second * size
            MeshPart(name, at, vertexBytes, at + vertexBytes, indexBytes, size).also { at += vertexBytes + indexBytes }
        }
    }

    private companion object {
        const val VERTEX_SIZE = 12
    }
}

/** An `.env` file (tools/car-model/envmap.py): a prefiltered sky and its diffuse light. */
internal class CarEnvironmentFile(val data: ByteArray) {
    val size: Int
    val levels: Int
    val harmonics: FloatArray
    val pixelsOffset: Int

    init {
        val reader = LittleEndianReader(data)
        reader.tag("HSCE")
        check(reader.int() == 1) { "unknown environment version" }
        size = reader.int()
        levels = reader.int()
        harmonics = FloatArray(27) { reader.float() }
        pixelsOffset = reader.position
    }
}
