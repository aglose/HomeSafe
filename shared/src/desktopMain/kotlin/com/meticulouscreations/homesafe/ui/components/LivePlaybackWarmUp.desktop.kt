package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.javacpp.Loader

/**
 * Loads FFmpeg's native libraries off the UI thread at launch, so the first camera card doesn't
 * wait the second or so that unpacking and linking them takes.
 */
actual fun warmUpLivePlayback(context: PlatformContext) {
    CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            Loader.load(avformat::class.java)
            avformat.avformat_network_init()
        }
    }
}
