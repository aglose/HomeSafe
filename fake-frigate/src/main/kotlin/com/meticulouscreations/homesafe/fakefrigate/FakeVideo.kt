package com.meticulouscreations.homesafe.fakefrigate

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.ceil

/**
 * The fake server's recorded video: real H.264 a player decodes and a muxer can rewrite, of
 * whatever length is asked for, without ffmpeg at run time.
 *
 * It is all cut from one two-second fragmented MP4 in the resources (a 320×180 test card at five
 * frames a second, a keyframe first, about 13 KB): an `ftyp` and an empty `moov`, then one
 * `moof`/`mdat` fragment. Any length is that fragment over and over, each copy numbered and
 * stamped two seconds after the last, which is what Frigate's own clips are: a fragmented MP4 it
 * can send while ffmpeg is still cutting. So lengths come in whole fragments, rounded up.
 *
 * The same fragments are the segments of the HLS playlists (`/vod/…/index.m3u8`), the way
 * Frigate's nginx-vod serves a recording: one `init.mp4` and a run of `seg-<n>.m4s`.
 *
 * To make the file again:
 *
 *     ffmpeg -f lavfi -i "testsrc2=size=320x180:rate=5" -t 2 -c:v libx264 -profile:v baseline \
 *       -level 3.0 -pix_fmt yuv420p -g 10 -keyint_min 10 -sc_threshold 0 -bf 0 -crf 34 \
 *       -video_track_timescale 10000 -movflags frag_keyframe+empty_moov+default_base_moof \
 *       -frag_duration 10000000 -map_metadata -1 -fflags +bitexact two-seconds.mp4
 */
object FakeVideo {
    /** How long one fragment plays. */
    const val FRAGMENT_SECONDS = 2

    /** The track's timescale (`-video_track_timescale`): ticks a second in a fragment's decode time. */
    private const val TIMESCALE = 10_000L

    private const val RESOURCE = "/fake-frigate/two-seconds.mp4"

    private class Box(val type: String, val start: Int, val size: Int) {
        val end: Int get() = start + size
        val body: Int get() = start + HEADER
    }

    private val source: ByteArray =
        checkNotNull(FakeVideo::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing from the fake Frigate's resources" }.use { it.readBytes() }

    private val top: List<Box> = boxes(source, 0, source.size)

    /** `ftyp` and `moov`: what a player needs before any fragment, and the playlists' `init.mp4`. */
    val init: ByteArray = top.filter { it.type == "ftyp" || it.type == "moov" }.let { boxes ->
        check(boxes.size == 2) { "$RESOURCE should hold one ftyp and one moov" }
        ByteArrayOutputStream().apply { boxes.forEach { write(source, it.start, it.size) } }.toByteArray()
    }

    /** The one `moof` and its `mdat`, as they are in the file. */
    private val fragment: ByteArray

    /** Where, in [fragment], the fragment's number and its decode time are written. */
    private val sequenceOffset: Int
    private val decodeTimeOffset: Int

    init {
        val moof = top.single { it.type == "moof" }
        val mdat = top.single { it.type == "mdat" }
        check(mdat.start == moof.end) { "$RESOURCE's mdat should follow its moof" }
        fragment = source.copyOfRange(moof.start, mdat.end)
        val inMoof = boxes(source, moof.body, moof.end)
        val traf = inMoof.single { it.type == "traf" }
        val tfdt = boxes(source, traf.body, traf.end).single { it.type == "tfdt" }
        check(source[tfdt.body].toInt() == 1) { "$RESOURCE's tfdt should be version 1 (a 64-bit decode time)" }
        // Both are full boxes: a version byte and three of flags, then the value.
        sequenceOffset = inMoof.single { it.type == "mfhd" }.body + FULL_BOX - moof.start
        decodeTimeOffset = tfdt.body + FULL_BOX - moof.start
    }

    /** How many fragments [seconds] of video takes: whole ones, rounded up, never none. */
    fun fragmentCount(seconds: Double): Int = ceil(seconds / FRAGMENT_SECONDS).toInt().coerceAtLeast(1)

    /** The fragment that plays from [index] × [FRAGMENT_SECONDS]: a playlist's `seg-<index>.m4s`. */
    fun fragment(index: Int): ByteArray {
        require(index >= 0) { "No fragment $index" }
        return fragment.copyOf().also { copy ->
            ByteBuffer.wrap(copy).apply {
                putInt(sequenceOffset, index + 1)
                putLong(decodeTimeOffset, index * FRAGMENT_SECONDS * TIMESCALE)
            }
        }
    }

    /** A clip [seconds] long as one fragmented MP4, the way Frigate sends `clip.mp4`. */
    fun clip(seconds: Double): ByteArray = ByteArrayOutputStream().apply {
        write(init)
        repeat(fragmentCount(seconds)) { write(fragment(it)) }
    }.toByteArray()

    /** The HLS media playlist of [seconds] of video: `init.mp4` and its segments, beside the playlist. */
    fun playlist(seconds: Double): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:$FRAGMENT_SECONDS\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXT-X-MAP:URI=\"$INIT_NAME\"\n")
        repeat(fragmentCount(seconds)) { append("#EXTINF:${"%.3f".format(Locale.ROOT, FRAGMENT_SECONDS.toDouble())},\nseg-$it.m4s\n") }
        append("#EXT-X-ENDLIST\n")
    }

    /** The number in a playlist's segment name, `seg-12.m4s`, or null for any other name. */
    fun segmentIndex(fileName: String): Int? = SEGMENT_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

    const val INIT_NAME = "init.mp4"

    private val SEGMENT_NAME = Regex("""seg-(\d{1,9})\.m4s""")

    private const val HEADER = 8
    private const val FULL_BOX = 4

    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val found = mutableListOf<Box>()
        var at = from
        while (at + HEADER <= to) {
            val size = ByteBuffer.wrap(bytes, at, 4).int
            check(size >= HEADER && at + size <= to) { "A box of $size bytes at $at doesn't fit" }
            found += Box(String(bytes, at + 4, 4, Charsets.ISO_8859_1), at, size)
            at += size
        }
        return found
    }
}
