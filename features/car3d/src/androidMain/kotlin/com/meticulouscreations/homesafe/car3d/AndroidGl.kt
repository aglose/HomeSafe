package com.meticulouscreations.homesafe.car3d

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AndroidGl : Gl {
    private val status = IntArray(1)

    override fun program(vertex: String, fragment: String): Int {
        val program = GLES30.glCreateProgram()
        listOf(GLES30.GL_VERTEX_SHADER to vertex, GLES30.GL_FRAGMENT_SHADER to fragment).forEach { (kind, source) ->
            val shader = GLES30.glCreateShader(kind)
            GLES30.glShaderSource(shader, "#version 300 es\nprecision highp float;\nprecision highp samplerCube;\nprecision highp sampler2D;\n$source")
            GLES30.glCompileShader(shader)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
            GLES30.glAttachShader(program, shader)
            GLES30.glDeleteShader(shader)
        }
        GLES30.glLinkProgram(program)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(program) }
        return program
    }

    override fun use(program: Int) = GLES30.glUseProgram(program)

    override fun location(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)

    override fun uniform(location: Int, value: Float) = GLES30.glUniform1f(location, value)

    override fun uniform2(location: Int, x: Float, y: Float) = GLES30.glUniform2f(location, x, y)

    override fun uniform(location: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location, x, y, z)

    override fun uniformInt(location: Int, value: Int) = GLES30.glUniform1i(location, value)

    override fun uniformVectors(location: Int, values: FloatArray) = GLES30.glUniform3fv(location, values.size / 3, values, 0)

    override fun uniformMatrix(location: Int, values: FloatArray) = GLES30.glUniformMatrix4fv(location, 1, false, values, 0)

    private fun direct(data: ByteArray, offset: Int, length: Int): ByteBuffer =
        ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder()).put(data, offset, length).also { it.flip() }

    private fun generated(make: (IntArray) -> Unit): Int {
        val id = IntArray(1)
        make(id)
        return id[0]
    }

    override fun mesh(data: ByteArray, vertexOffset: Int, vertexBytes: Int, indexOffset: Int, indexBytes: Int, indexSize: Int): GlMesh {
        val array = generated { GLES30.glGenVertexArrays(1, it, 0) }
        GLES30.glBindVertexArray(array)
        val vertices = generated { GLES30.glGenBuffers(1, it, 0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertices)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBytes, direct(data, vertexOffset, vertexBytes), GLES30.GL_STATIC_DRAW)
        val indices = generated { GLES30.glGenBuffers(1, it, 0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, direct(data, indexOffset, indexBytes), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_SHORT, false, 12, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_SHORT, true, 12, 8)
        GLES30.glBindVertexArray(0)
        return GlMesh(array, vertices, indices, indexBytes / indexSize, indexSize)
    }

    override fun draw(mesh: GlMesh) {
        GLES30.glBindVertexArray(mesh.array)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.count, if (mesh.indexSize == 2) GLES30.GL_UNSIGNED_SHORT else GLES30.GL_UNSIGNED_INT, 0)
    }

    override fun cube(size: Int, levels: Int, data: ByteArray, offset: Int): Int {
        val texture = generated { GLES30.glGenTextures(1, it, 0) }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, texture)
        var at = offset
        for (level in 0 until levels) {
            val side = size shr level
            for (face in 0 until 6) {
                val bytes = side * side * 4
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, GLES30.GL_RGB9_E5, side, side, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_INT_5_9_9_9_REV, direct(data, at, bytes))
                at += bytes
            }
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1)
        return texture
    }

    override fun bindCube(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, texture)
    }

    override fun texture(size: Int, data: ByteArray): Int {
        val texture = generated { GLES30.glGenTextures(1, it, 0) }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, size, size, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, direct(data, 0, size * size))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return texture
    }

    override fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    override fun viewport(width: Int, height: Int) = GLES30.glViewport(0, 0, width, height)

    override fun clear() {
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    }

    override fun opaque() {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    override fun blended(depthWrites: Boolean) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(depthWrites)
    }

    override fun release(meshes: List<GlMesh>, textures: IntArray, cubes: IntArray, programs: IntArray) {
        meshes.forEach {
            GLES30.glDeleteVertexArrays(1, intArrayOf(it.array), 0)
            GLES30.glDeleteBuffers(2, intArrayOf(it.vertexBuffer, it.indexBuffer), 0)
        }
        GLES30.glDeleteTextures(textures.size, textures, 0)
        GLES30.glDeleteTextures(cubes.size, cubes, 0)
        programs.forEach(GLES30::glDeleteProgram)
    }
}
