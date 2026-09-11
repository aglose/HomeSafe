package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The process-wide libwebrtc pieces every peer shares: the native library initialised once,
 * one EGL context that decoders and renderers all draw through (so a decoded frame reaches a
 * `TextureView` without a copy), and one audio device module.
 *
 * Main thread only, like everything else in the live player stack.
 */
internal object WebRtcRuntime {

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    /**
     * The shared EGL context. Cheap to create and needs no native library, so a renderer can
     * be built for a card before — or without — a peer ever being made.
     */
    val eglContext: EglBase.Context
        get() = (eglBase ?: EglBase.create().also { eglBase = it }).eglBaseContext

    fun peerConnectionFactory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        val appContext = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val egl = eglContext
        // Receive-only: nothing is ever captured, but the module is where playback attributes
        // live. Media usage keeps the sound on the speaker/headphones route like the HLS player
        // instead of libwebrtc's default voice-call route (the earpiece on a phone).
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, false, false))
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
            .also { factory = it }
    }
}
