package com.meticulouscreations.homesafe.car3d

/**
 * The little of OpenGL the car needs, the same on each platform: GL ES 3 on the phones, GL 4.1 on
 * the Mac. Every call is made on the thread holding the surface's context.
 */
internal interface Gl {
    /** Compiled from the shared sources, under the header for this platform's GLSL. */
    fun program(vertex: String, fragment: String): Int
    fun use(program: Int)
    fun location(program: Int, name: String): Int
    fun uniform(location: Int, value: Float)
    fun uniform2(location: Int, x: Float, y: Float)
    fun uniform(location: Int, x: Float, y: Float, z: Float)
    fun uniformInt(location: Int, value: Int)
    fun uniformVectors(location: Int, values: FloatArray)
    fun uniformMatrix(location: Int, values: FloatArray)

    /** Vertices as tools/car-model/export.py writes them: 4 shorts, then 2 normalized shorts, 12 bytes in all. */
    fun mesh(data: ByteArray, vertexOffset: Int, vertexBytes: Int, indexOffset: Int, indexBytes: Int, indexSize: Int): GlMesh
    fun draw(mesh: GlMesh)

    /** A cube of RGB9E5 texels, one level per [levels] entry: each is all six faces in order. */
    fun cube(size: Int, levels: Int, data: ByteArray, offset: Int): Int
    fun bindCube(unit: Int, texture: Int)

    /** A single-channel picture. */
    fun texture(size: Int, data: ByteArray): Int
    fun bindTexture(unit: Int, texture: Int)

    fun viewport(width: Int, height: Int)
    fun clear()
    fun opaque()
    fun blended(depthWrites: Boolean)
    fun release(meshes: List<GlMesh>, textures: IntArray, cubes: IntArray, programs: IntArray)
}

internal class GlMesh(val array: Int, val vertexBuffer: Int, val indexBuffer: Int, val count: Int, val indexSize: Int)
