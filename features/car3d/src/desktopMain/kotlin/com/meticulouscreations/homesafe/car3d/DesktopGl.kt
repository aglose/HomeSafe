package com.meticulouscreations.homesafe.car3d

import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL32C
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DesktopGl : Gl {
    override fun program(vertex: String, fragment: String): Int {
        val program = GL20C.glCreateProgram()
        listOf(GL20C.GL_VERTEX_SHADER to vertex, GL20C.GL_FRAGMENT_SHADER to fragment).forEach { (kind, source) ->
            val shader = GL20C.glCreateShader(kind)
            GL20C.glShaderSource(shader, "#version 410 core\n$source")
            GL20C.glCompileShader(shader)
            check(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_TRUE) { GL20C.glGetShaderInfoLog(shader) }
            GL20C.glAttachShader(program, shader)
            GL20C.glDeleteShader(shader)
        }
        GL20C.glLinkProgram(program)
        check(GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_TRUE) { GL20C.glGetProgramInfoLog(program) }
        return program
    }

    override fun use(program: Int) = GL20C.glUseProgram(program)

    override fun location(program: Int, name: String) = GL20C.glGetUniformLocation(program, name)

    override fun uniform(location: Int, value: Float) = GL20C.glUniform1f(location, value)

    override fun uniform2(location: Int, x: Float, y: Float) = GL20C.glUniform2f(location, x, y)

    override fun uniform(location: Int, x: Float, y: Float, z: Float) = GL20C.glUniform3f(location, x, y, z)

    override fun uniformInt(location: Int, value: Int) = GL20C.glUniform1i(location, value)

    override fun uniformVectors(location: Int, values: FloatArray) = GL20C.glUniform3fv(location, values)

    override fun uniformMatrix(location: Int, values: FloatArray) = GL20C.glUniformMatrix4fv(location, false, values)

    private fun direct(data: ByteArray, offset: Int, length: Int): ByteBuffer =
        ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder()).put(data, offset, length).flip()

    override fun mesh(data: ByteArray, vertexOffset: Int, vertexBytes: Int, indexOffset: Int, indexBytes: Int, indexSize: Int): GlMesh {
        val array = GL30C.glGenVertexArrays()
        GL30C.glBindVertexArray(array)
        val vertices = GL15C.glGenBuffers()
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices)
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, direct(data, vertexOffset, vertexBytes), GL15C.GL_STATIC_DRAW)
        val indices = GL15C.glGenBuffers()
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices)
        GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, direct(data, indexOffset, indexBytes), GL15C.GL_STATIC_DRAW)
        GL20C.glEnableVertexAttribArray(0)
        GL20C.glVertexAttribPointer(0, 4, GL11C.GL_SHORT, false, 12, 0L)
        GL20C.glEnableVertexAttribArray(1)
        GL20C.glVertexAttribPointer(1, 2, GL11C.GL_SHORT, true, 12, 8L)
        GL30C.glBindVertexArray(0)
        return GlMesh(array, vertices, indices, indexBytes / indexSize, indexSize)
    }

    override fun draw(mesh: GlMesh) {
        GL30C.glBindVertexArray(mesh.array)
        GL11C.glDrawElements(GL11C.GL_TRIANGLES, mesh.count, if (mesh.indexSize == 2) GL11C.GL_UNSIGNED_SHORT else GL11C.GL_UNSIGNED_INT, 0L)
    }

    override fun cube(size: Int, levels: Int, data: ByteArray, offset: Int): Int {
        val texture = GL11C.glGenTextures()
        GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, texture)
        var at = offset
        for (level in 0 until levels) {
            val side = size shr level
            for (face in 0 until 6) {
                val bytes = side * side * 4
                GL11C.glTexImage2D(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, GL30C.GL_RGB9_E5, side, side, 0, GL11C.GL_RGB, GL30C.GL_UNSIGNED_INT_5_9_9_9_REV, direct(data, at, bytes))
                at += bytes
            }
        }
        GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR)
        GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
        GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL12C.GL_TEXTURE_MAX_LEVEL, levels - 1)
        GL11C.glEnable(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS)
        return texture
    }

    override fun bindCube(unit: Int, texture: Int) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit)
        GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, texture)
    }

    override fun texture(size: Int, data: ByteArray): Int {
        val texture = GL11C.glGenTextures()
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture)
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1)
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R8, size, size, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, direct(data, 0, size * size))
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE)
        return texture
    }

    override fun bindTexture(unit: Int, texture: Int) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit)
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture)
    }

    override fun viewport(width: Int, height: Int) = GL11C.glViewport(0, 0, width, height)

    override fun clear() {
        GL11C.glDepthMask(true)
        GL11C.glClearColor(0f, 0f, 0f, 0f)
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT or GL11C.GL_DEPTH_BUFFER_BIT)
    }

    override fun opaque() {
        GL11C.glDisable(GL11C.GL_BLEND)
        GL11C.glEnable(GL11C.GL_DEPTH_TEST)
        GL11C.glDepthMask(true)
        GL11C.glDisable(GL11C.GL_CULL_FACE)
    }

    override fun blended(depthWrites: Boolean) {
        GL11C.glEnable(GL11C.GL_BLEND)
        GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA)
        GL11C.glDepthMask(depthWrites)
    }

    override fun release(meshes: List<GlMesh>, textures: IntArray, cubes: IntArray, programs: IntArray) {
        meshes.forEach {
            GL30C.glDeleteVertexArrays(it.array)
            GL15C.glDeleteBuffers(it.vertexBuffer)
            GL15C.glDeleteBuffers(it.indexBuffer)
        }
        GL11C.glDeleteTextures(textures)
        GL11C.glDeleteTextures(cubes)
        programs.forEach(GL20C::glDeleteProgram)
    }
}
