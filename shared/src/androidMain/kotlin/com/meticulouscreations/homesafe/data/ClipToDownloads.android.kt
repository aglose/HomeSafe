package com.meticulouscreations.homesafe.data

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

private const val TAG = "ClipToDownloads"

/**
 * Puts the MP4 at [source] into the public Downloads collection as [fileName] and returns the
 * new entry, rewriting it as an ordinary MP4 on the way.
 *
 * Frigate streams its clips (`/api/<camera>/start/…/clip.mp4`, `/api/events/<id>/clip.mp4`) as
 * a *fragmented* MP4 — an empty `moov` and then a run of `moof`/`mdat` fragments — so it can
 * send the file while ffmpeg is still cutting it. Players cope, but Android's media scanner reads
 * no duration from one, so the clip shows as 00:00 in photo pickers, and apps that check or
 * compress a video before sending it (Google Messages: "Failed to add attachment") give up.
 * [MediaExtractor] reads fragments fine and [MediaMuxer] writes the one `moov` everyone expects;
 * the samples are copied, not re-encoded, so it takes about as long as copying the file.
 *
 * Should the rewrite fail on a stream [MediaMuxer] won't take, the clip is kept as it came,
 * which plays everywhere it did before.
 */
internal fun saveClipToDownloads(resolver: ContentResolver, source: File, fileName: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val target = resolver.insert(collection, values) ?: error("MediaStore refused a new download")
    try {
        val remuxed = runCatching {
            resolver.openFileDescriptor(target, "rw").use { pfd ->
                remuxToMp4(source, checkNotNull(pfd) { "No descriptor for $target" }.fileDescriptor)
            }
        }
        remuxed.exceptionOrNull()?.let { e ->
            Log.w(TAG, "Couldn't rewrite ${source.name}; keeping it as it came", e)
            // "wt" truncates whatever the muxer got as far as writing.
            checkNotNull(resolver.openOutputStream(target, "wt")) { "No stream for $target" }.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
        }
        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return target
    } catch (e: Throwable) {
        resolver.delete(target, null, null)
        throw e
    }
}

/** Copies every audio and video sample of [source] into a non-fragmented MP4 written to [target]. */
private fun remuxToMp4(source: File, target: FileDescriptor) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source.path)
        val muxer = MediaMuxer(target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val trackMap = IntArray(extractor.trackCount) { -1 }
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                trackMap[track] = muxer.addTrack(format)
                extractor.selectTrack(track)
            }
            check(trackMap.any { it >= 0 }) { "No audio or video in ${source.name}" }
            muxer.start()
            started = true

            var buffer = ByteBuffer.allocateDirect(INITIAL_SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val size = extractor.sampleSize
                // A 4K keyframe can be several megabytes; grow to whatever the largest one needs.
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, read, extractor.sampleTime, flags)
                muxer.writeSampleData(trackMap[track], buffer, info)
                extractor.advance()
            }
        } finally {
            // stop() is what writes the moov; a muxer that never started can only be released.
            try {
                if (started) muxer.stop()
            } finally {
                muxer.release()
            }
        }
    } finally {
        extractor.release()
    }
}

private const val INITIAL_SAMPLE_BUFFER_BYTES = 1 shl 20
