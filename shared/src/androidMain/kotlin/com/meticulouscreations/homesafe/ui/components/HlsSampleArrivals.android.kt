package com.meticulouscreations.homesafe.ui.components

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * When each video sample of a live HLS stream reached the player, in the player's own media
 * time — what [LiveEdgeTracker] needs, and what ExoPlayer does not offer.
 *
 * Its buffered position won't do: for HLS that is the *later* of the newest sample it holds and
 * the end the playlist claims for the last segment it loaded, and go2rtc's playlist claims half a
 * second for every segment whatever is in it — a segment holds what built up since the one
 * before, be that one frame or two seconds. The claimed end can run well ahead of the
 * video actually held, so a delay worked out from it would be that much too long.
 *
 * Samples are parsed on ExoPlayer's loader thread and read on the main thread, hence the locks.
 * Between two [drain]s only the sample that arrived soonest for its timestamp is kept, which is
 * the one the tracker would have picked anyway, along with the newest timestamp seen.
 */
internal class HlsSampleArrivals {
    /** [mediaTimeMs] arrived at [atMs] ([SystemClock.elapsedRealtime]); nothing newer than [newestMediaMs] has. */
    class Arrival(val mediaTimeMs: Long, val atMs: Long, val newestMediaMs: Long)

    private var best: Arrival? = null

    @Synchronized
    fun onVideoSample(timeUs: Long) {
        if (timeUs == C.TIME_UNSET) return
        val mediaTimeMs = timeUs / 1_000
        val atMs = SystemClock.elapsedRealtime()
        val current = best
        val newest = maxOf(mediaTimeMs, current?.newestMediaMs ?: mediaTimeMs)
        best = if (current == null || mediaTimeMs - atMs >= current.mediaTimeMs - current.atMs) {
            Arrival(mediaTimeMs, atMs, newest)
        } else {
            Arrival(current.mediaTimeMs, current.atMs, newest)
        }
    }

    /** What arrived since the last call, or null if no video did. */
    @Synchronized
    fun drain(): Arrival? = best.also { best = null }

    @Synchronized
    fun clear() {
        best = null
    }
}

/**
 * ExoPlayer's own HLS extractors, with every video sample's timestamp reported to [arrivals] as
 * it is parsed. Everything else passes straight through to [DefaultHlsExtractorFactory].
 */
internal class ArrivalReportingExtractorFactory(private val arrivals: HlsSampleArrivals) : HlsExtractorFactory {
    private val delegate = DefaultHlsExtractorFactory()

    override fun createExtractor(
        uri: Uri,
        format: Format,
        muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster,
        responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput,
        playerId: PlayerId,
    ): HlsMediaChunkExtractor =
        ReportingExtractor(delegate.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory = apply {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory = apply {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)

    private inner class ReportingExtractor(private val extractor: HlsMediaChunkExtractor) : HlsMediaChunkExtractor {
        override fun init(extractorOutput: ExtractorOutput) = extractor.init(ReportingOutput(extractorOutput))
        override fun read(extractorInput: ExtractorInput): Boolean = extractor.read(extractorInput)
        override fun isPackedAudioExtractor(): Boolean = extractor.isPackedAudioExtractor
        override fun isReusable(): Boolean = extractor.isReusable
        override fun recreate(): HlsMediaChunkExtractor = ReportingExtractor(extractor.recreate())
        override fun onTruncatedSegmentParsed() = extractor.onTruncatedSegmentParsed()
    }

    private inner class ReportingOutput(private val output: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = output.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) ReportingTrack(track) else track
        }

        override fun endTracks() = output.endTracks()
        override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
    }

    /** Every method forwarded by hand, the defaulted ones too: the sample queue behind this overrides them all. */
    private inner class ReportingTrack(private val track: TrackOutput) : TrackOutput {
        override fun durationUs(durationUs: Long) = track.durationUs(durationUs)
        override fun format(format: Format) = track.format(format)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int = track.sampleData(input, length, allowEndOfInput)
        override fun sampleData(data: ParsableByteArray, length: Int) = track.sampleData(data, length)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            track.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = track.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            arrivals.onVideoSample(timeUs)
            track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }
}
