@file:OptIn(ExperimentalForeignApi::class)

package com.meticulouscreations.homesafe.car3d

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Metal.MTLBlendFactorOne
import platform.Metal.MTLBlendFactorOneMinusSourceAlpha
import platform.Metal.MTLBufferProtocol
import platform.Metal.MTLCompareFunctionLess
import platform.Metal.MTLDepthStencilDescriptor
import platform.Metal.MTLDepthStencilStateProtocol
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLIndexTypeUInt16
import platform.Metal.MTLIndexTypeUInt32
import platform.Metal.MTLLibraryProtocol
import platform.Metal.MTLOrigin
import platform.Metal.MTLPixelFormat
import platform.Metal.MTLPixelFormatR8Unorm
import platform.Metal.MTLPixelFormatRGB9E5Float
import platform.Metal.MTLPrimitiveTypeTriangle
import platform.Metal.MTLRegion
import platform.Metal.MTLRenderCommandEncoderProtocol
import platform.Metal.MTLRenderPipelineDescriptor
import platform.Metal.MTLRenderPipelineStateProtocol
import platform.Metal.MTLResourceStorageModeShared
import platform.Metal.MTLSamplerAddressModeClampToEdge
import platform.Metal.MTLSamplerDescriptor
import platform.Metal.MTLSamplerMinMagFilterLinear
import platform.Metal.MTLSamplerMipFilterLinear
import platform.Metal.MTLSamplerStateProtocol
import platform.Metal.MTLSize
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLVertexDescriptor
import platform.Metal.MTLVertexFormatShort2Normalized
import platform.Metal.MTLVertexFormatShort4

/**
 * [Gl] over Metal, so [CarScene] is the same code on iOS. The shaders are [CAR_SHADERS_METAL], and
 * "uniforms" are slots in the one block they share. A frame's [encoder] is set by the surface.
 */
internal class MetalGl(
    private val device: MTLDeviceProtocol,
    private val colorFormat: MTLPixelFormat,
    private val depthFormat: MTLPixelFormat,
    private val sampleCount: Int,
) : Gl {
    var encoder: MTLRenderCommandEncoderProtocol? = null

    private class Mesh(val vertices: MTLBufferProtocol, val indices: MTLBufferProtocol)

    private val library: MTLLibraryProtocol = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        device.newLibraryWithSource(CAR_SHADERS_METAL, options = null, error = error.ptr)
            ?: error("the car's Metal shaders don't compile: ${error.value?.localizedDescription}")
    }
    private val pipelines = ArrayList<MTLRenderPipelineStateProtocol>()
    private val meshes = ArrayList<Mesh>()
    private val textures = ArrayList<MTLTextureProtocol>()
    private val uniforms = FloatArray(SLOTS * 4)
    private val writesDepth: MTLDepthStencilStateProtocol = depthState(write = true)
    private val keepsDepth: MTLDepthStencilStateProtocol = depthState(write = false)
    private val sampler: MTLSamplerStateProtocol = run {
        val descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = MTLSamplerMinMagFilterLinear
        descriptor.magFilter = MTLSamplerMinMagFilterLinear
        descriptor.mipFilter = MTLSamplerMipFilterLinear
        descriptor.sAddressMode = MTLSamplerAddressModeClampToEdge
        descriptor.tAddressMode = MTLSamplerAddressModeClampToEdge
        checkNotNull(device.newSamplerStateWithDescriptor(descriptor)) { "no sampler" }
    }
    private var current = 0
    private var depth = writesDepth
    private var cubeBound: MTLTextureProtocol? = null
    private var textureBound: MTLTextureProtocol? = null

    private fun depthState(write: Boolean): MTLDepthStencilStateProtocol {
        val descriptor = MTLDepthStencilDescriptor()
        descriptor.depthCompareFunction = MTLCompareFunctionLess
        descriptor.depthWriteEnabled = write
        return checkNotNull(device.newDepthStencilStateWithDescriptor(descriptor)) { "no depth state" }
    }

    /** Which pipeline a pair of [CarShaders] sources means. */
    override fun program(vertex: String, fragment: String): Int {
        val (vertexName, fragmentName, blended) = when (fragment) {
            CarShaders.SURFACE -> Triple("carVertex", "carSurface", false)
            CarShaders.GLASS -> Triple("carVertex", "carGlass", true)
            else -> Triple("carShadowVertex", "carShadow", true)
        }
        val descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = library.newFunctionWithName(vertexName)
        descriptor.fragmentFunction = library.newFunctionWithName(fragmentName)
        val layout = MTLVertexDescriptor()
        layout.attributes.objectAtIndexedSubscript(0u).apply {
            format = MTLVertexFormatShort4
            offset = 0u
            bufferIndex = 0u
        }
        layout.attributes.objectAtIndexedSubscript(1u).apply {
            format = MTLVertexFormatShort2Normalized
            offset = 8u
            bufferIndex = 0u
        }
        layout.layouts.objectAtIndexedSubscript(0u).stride = VERTEX_SIZE.convert()
        descriptor.vertexDescriptor = layout
        descriptor.rasterSampleCount = sampleCount.convert()
        descriptor.depthAttachmentPixelFormat = depthFormat
        val color = checkNotNull(descriptor.colorAttachments.objectAtIndexedSubscript(0u))
        color.pixelFormat = colorFormat
        if (blended) {
            // Premultiplied, as the shaders write it.
            color.blendingEnabled = true
            color.sourceRGBBlendFactor = MTLBlendFactorOne
            color.sourceAlphaBlendFactor = MTLBlendFactorOne
            color.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha
            color.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha
        }
        val state = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            device.newRenderPipelineStateWithDescriptor(descriptor, error = error.ptr)
                ?: error("no pipeline for $fragmentName: ${error.value?.localizedDescription}")
        }
        pipelines += state
        return pipelines.size - 1
    }

    override fun use(program: Int) {
        current = program
    }

    override fun location(program: Int, name: String): Int = SLOT[name]?.times(4) ?: -1

    override fun uniform(location: Int, value: Float) {
        if (location >= 0) uniforms[location] = value
    }

    override fun uniform2(location: Int, x: Float, y: Float) {
        if (location < 0) return
        uniforms[location] = x
        uniforms[location + 1] = y
    }

    override fun uniform(location: Int, x: Float, y: Float, z: Float) {
        if (location < 0) return
        uniforms[location] = x
        uniforms[location + 1] = y
        uniforms[location + 2] = z
    }

    override fun uniformInt(location: Int, value: Int) = Unit // samplers have fixed slots here

    /** Three floats to the value in GLSL's vec3 array; four to the slot here. */
    override fun uniformVectors(location: Int, values: FloatArray) {
        if (location < 0) return
        for (i in 0 until values.size / 3) {
            uniforms[location + i * 4] = values[i * 3]
            uniforms[location + i * 4 + 1] = values[i * 3 + 1]
            uniforms[location + i * 4 + 2] = values[i * 3 + 2]
        }
    }

    override fun uniformMatrix(location: Int, values: FloatArray) {
        if (location >= 0) values.copyInto(uniforms, location)
    }

    private fun buffer(data: ByteArray, offset: Int, length: Int): MTLBufferProtocol = data.usePinned {
        checkNotNull(device.newBufferWithBytes(it.addressOf(offset), length.convert(), MTLResourceStorageModeShared)) { "no buffer" }
    }

    override fun mesh(data: ByteArray, vertexOffset: Int, vertexBytes: Int, indexOffset: Int, indexBytes: Int, indexSize: Int): GlMesh {
        meshes += Mesh(buffer(data, vertexOffset, vertexBytes), buffer(data, indexOffset, indexBytes))
        val id = meshes.size - 1
        return GlMesh(id, id, id, indexBytes / indexSize, indexSize)
    }

    override fun draw(mesh: GlMesh) {
        val frame = encoder ?: return
        val buffers = meshes[mesh.array]
        frame.setRenderPipelineState(pipelines[current])
        frame.setDepthStencilState(depth)
        frame.setVertexBuffer(buffers.vertices, offset = 0u, atIndex = 0u)
        uniforms.usePinned {
            frame.setVertexBytes(it.addressOf(0), (uniforms.size * 4).convert(), atIndex = 1u)
            frame.setFragmentBytes(it.addressOf(0), (uniforms.size * 4).convert(), atIndex = 0u)
        }
        frame.setFragmentTexture(cubeBound, atIndex = 0u)
        frame.setFragmentTexture(textureBound, atIndex = 1u)
        frame.setFragmentSamplerState(sampler, atIndex = 0u)
        frame.drawIndexedPrimitives(
            MTLPrimitiveTypeTriangle,
            indexCount = mesh.count.convert(),
            indexType = if (mesh.indexSize == 2) MTLIndexTypeUInt16 else MTLIndexTypeUInt32,
            indexBuffer = buffers.indices,
            indexBufferOffset = 0u,
        )
    }

    private fun region(size: Int) = cValue<MTLRegion> {
        origin.x = 0u
        origin.y = 0u
        origin.z = 0u
        this.size.width = size.convert()
        this.size.height = size.convert()
        this.size.depth = 1u
    }

    override fun cube(size: Int, levels: Int, data: ByteArray, offset: Int): Int {
        val descriptor = MTLTextureDescriptor.textureCubeDescriptorWithPixelFormat(MTLPixelFormatRGB9E5Float, size.convert(), mipmapped = true)
        descriptor.mipmapLevelCount = levels.convert()
        val texture = checkNotNull(device.newTextureWithDescriptor(descriptor)) { "no cube texture" }
        var at = offset
        data.usePinned { pinned ->
            for (level in 0 until levels) {
                val side = size shr level
                for (face in 0 until 6) {
                    val bytes = side * side * 4
                    texture.replaceRegion(region(side), level.convert(), face.convert(), pinned.addressOf(at), (side * 4).convert(), bytes.convert())
                    at += bytes
                }
            }
        }
        textures += texture
        return textures.size - 1
    }

    override fun bindCube(unit: Int, texture: Int) {
        cubeBound = textures[texture]
    }

    override fun texture(size: Int, data: ByteArray): Int {
        val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatR8Unorm, size.convert(), size.convert(), mipmapped = false)
        val texture = checkNotNull(device.newTextureWithDescriptor(descriptor)) { "no shadow texture" }
        data.usePinned { texture.replaceRegion(region(size), 0u, it.addressOf(0), size.convert()) }
        textures += texture
        return textures.size - 1
    }

    override fun bindTexture(unit: Int, texture: Int) {
        textureBound = textures[texture]
    }

    override fun viewport(width: Int, height: Int) = Unit // the whole drawable, always

    override fun clear() = Unit // the render pass clears as it begins

    override fun opaque() {
        depth = writesDepth
    }

    override fun blended(depthWrites: Boolean) {
        depth = if (depthWrites) writesDepth else keepsDepth
    }

    override fun release(meshes: List<GlMesh>, textures: IntArray, cubes: IntArray, programs: IntArray) {
        this.meshes.clear()
        this.textures.clear()
        pipelines.clear()
        cubeBound = null
        textureBound = null
    }

    private companion object {
        const val VERTEX_SIZE = 12

        /** The float4 slot each uniform starts at in [CAR_SHADERS_METAL]'s `Uniforms`. */
        val SLOT = mapOf(
            "uViewProjection" to 0,
            "uModel" to 4,
            "uUnit" to 8,
            "uLevels" to 9,
            "uExposure" to 10,
            "uCamera" to 11,
            "uHarmonics" to 12,
            "uSkyTurn" to 21,
            "uBase" to 22,
            "uMetal" to 23,
            "uRoughness" to 24,
            "uCoat" to 25,
            "uCoatRoughness" to 26,
            "uFlake" to 27,
            "uFlakeAmount" to 28,
            "uEmissive" to 29,
            "uOcclusionPower" to 30,
            "uTint" to 31,
            "uHalfSize" to 32,
            "uStrength" to 33,
            "uFloor" to 34,
            "uSide" to 35,
        )
        const val SLOTS = 36
    }
}
