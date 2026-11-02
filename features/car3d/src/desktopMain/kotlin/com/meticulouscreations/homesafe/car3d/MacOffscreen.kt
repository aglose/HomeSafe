package com.meticulouscreations.homesafe.car3d

import org.lwjgl.BufferUtils
import org.lwjgl.PointerBuffer
import org.lwjgl.opengl.CGL
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL30C
import org.lwjgl.system.Configuration
import java.nio.ByteBuffer

/**
 * An OpenGL context of its own, with no window, and a multisampled frame to draw into and read
 * back: the Compose window's own surface isn't ours to draw on. Every method runs on the thread
 * that made it. Apple's GL stops at 4× multisampling, so the frame is drawn at [supersample] times
 * the size asked for as well and filtered down: thin trim and the wheels' edges stay clean.
 */
internal class MacOffscreen(requestedSamples: Int, private val supersample: Int = 1) {
    private val context: Long
    private val samples: Int

    private var frame = 0
    private var resolved = 0
    private var colour = 0
    private var depth = 0
    private var resolvedColour = 0
    private var output = 0
    private var outputColour = 0
    private var width = 0
    private var height = 0

    init {
        // Packaging the app signs its libraries again, so they no longer match the hashes in LWJGL's jars.
        Configuration.DISABLE_HASH_CHECKS.set(true)
        val attributes = BufferUtils.createIntBuffer(16)
        attributes.put(CGL.kCGLPFAOpenGLProfile).put(0x4100)
        attributes.put(CGL.kCGLPFAAccelerated)
        attributes.put(CGL.kCGLPFAColorSize).put(32)
        attributes.put(CGL.kCGLPFADepthSize).put(24)
        attributes.put(0).flip()
        val formats = PointerBuffer.allocateDirect(1)
        val count = BufferUtils.createIntBuffer(1)
        check(CGL.CGLChoosePixelFormat(attributes, formats, count) == 0 && formats[0] != 0L) { "no OpenGL pixel format" }
        val made = PointerBuffer.allocateDirect(1)
        check(CGL.CGLCreateContext(formats[0], 0L, made) == 0) { "no OpenGL context" }
        CGL.CGLDestroyPixelFormat(formats[0])
        context = made[0]
        CGL.CGLSetCurrentContext(context)
        GL.createCapabilities()
        samples = minOf(requestedSamples, GL11C.glGetInteger(GL30C.GL_MAX_SAMPLES))
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        deleteFrames()
        val drawWidth = width * supersample
        val drawHeight = height * supersample
        colour = GL30C.glGenRenderbuffers()
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, colour)
        GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, samples, GL11C.GL_RGBA8, drawWidth, drawHeight)
        depth = GL30C.glGenRenderbuffers()
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depth)
        GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, samples, GL30C.GL_DEPTH_COMPONENT24, drawWidth, drawHeight)
        frame = GL30C.glGenFramebuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, frame)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, colour)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, depth)
        check(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) == GL30C.GL_FRAMEBUFFER_COMPLETE) { "the car's frame can't be drawn to" }
        resolvedColour = GL30C.glGenRenderbuffers()
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, resolvedColour)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL11C.GL_RGBA8, drawWidth, drawHeight)
        resolved = GL30C.glGenFramebuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, resolved)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, resolvedColour)
        outputColour = GL30C.glGenRenderbuffers()
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, outputColour)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL11C.GL_RGBA8, width, height)
        output = GL30C.glGenFramebuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, output)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, outputColour)
    }

    /** Runs [draw] on the frame with the size it is drawn at, then fills [into] with the pixels, top row first (RGBA, premultiplied). */
    fun render(into: ByteBuffer, draw: (width: Int, height: Int) -> Unit) {
        val drawWidth = width * supersample
        val drawHeight = height * supersample
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, frame)
        draw(drawWidth, drawHeight)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, frame)
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, resolved)
        GL30C.glBlitFramebuffer(0, 0, drawWidth, drawHeight, 0, 0, drawWidth, drawHeight, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, resolved)
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, output)
        GL30C.glBlitFramebuffer(0, 0, drawWidth, drawHeight, 0, 0, width, height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, output)
        into.clear()
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1)
        GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, into)
        flip(into)
    }

    private fun flip(pixels: ByteBuffer) {
        val stride = width * 4
        val top = ByteArray(stride)
        val bottom = ByteArray(stride)
        for (row in 0 until height / 2) {
            val a = row * stride
            val b = (height - 1 - row) * stride
            pixels.position(a).get(top)
            pixels.position(b).get(bottom)
            pixels.position(a).put(bottom)
            pixels.position(b).put(top)
        }
        pixels.clear()
    }

    private fun deleteFrames() {
        if (frame == 0) return
        GL30C.glDeleteFramebuffers(frame)
        GL30C.glDeleteFramebuffers(resolved)
        GL30C.glDeleteFramebuffers(output)
        GL30C.glDeleteRenderbuffers(outputColour)
        GL30C.glDeleteRenderbuffers(colour)
        GL30C.glDeleteRenderbuffers(depth)
        GL30C.glDeleteRenderbuffers(resolvedColour)
    }

    fun close() {
        deleteFrames()
        CGL.CGLSetCurrentContext(0L)
        CGL.CGLDestroyContext(context)
    }
}
