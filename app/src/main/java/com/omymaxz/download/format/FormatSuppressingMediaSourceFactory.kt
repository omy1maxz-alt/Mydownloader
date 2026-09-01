package com.omymaxz.download.format

import android.content.Context
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.common.TrackGroup
import androidx.media3.common.Format
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.common.C
import java.util.concurrent.ConcurrentHashMap

class FormatSuppressingMediaSourceFactory(
    private val defaultFactory: DefaultMediaSourceFactory
) : MediaSource.Factory by defaultFactory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val original = defaultFactory.createMediaSource(mediaItem)
        return FormatSuppressingMediaSource(original)
    }
}

class FormatSuppressingMediaSource(mediaSource: MediaSource) : WrappingMediaSource(mediaSource) {
    // Shared format cache across all periods and streams for this source
    val firstFormats = ConcurrentHashMap<Int, Format>()

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: androidx.media3.exoplayer.upstream.Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val original = super.createPeriod(id, allocator, startPositionUs)
        return FormatSuppressingMediaPeriod(original, firstFormats)
    }
}

class FormatSuppressingMediaPeriod(
    private val wrapped: MediaPeriod,
    private val firstFormats: ConcurrentHashMap<Int, Format>
) : MediaPeriod by wrapped {
    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val unwrappedStreams = Array<SampleStream?>(streams.size) { i ->
            val stream = streams[i]
            if (stream is FormatSuppressingSampleStream) stream.wrapped else stream
        }

        val result = wrapped.selectTracks(
            selections, mayRetainStreamFlags, unwrappedStreams, streamResetFlags, positionUs
        )

        for (i in unwrappedStreams.indices) {
            val unwrapped = unwrappedStreams[i]
            if (unwrapped != null) {
                val trackType = selections[i]?.trackGroup?.type ?: C.TRACK_TYPE_UNKNOWN
                if (streams[i] is FormatSuppressingSampleStream && (streams[i] as FormatSuppressingSampleStream).wrapped === unwrapped) {
                    // Keep existing wrapper
                } else {
                    streams[i] = FormatSuppressingSampleStream(unwrapped, trackType, firstFormats)
                }
            } else {
                streams[i] = null
            }
        }
        return result
    }
}

class FormatSuppressingSampleStream(
    val wrapped: SampleStream,
    private val trackType: Int,
    private val firstFormats: ConcurrentHashMap<Int, Format>
) : SampleStream {

    override fun isReady(): Boolean = wrapped.isReady

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int
    ): Int {
        val result = wrapped.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_FORMAT_READ) {
            val format = formatHolder.format
            if (format != null) {
                // If it's the very first format we see for this track type, store it
                val firstFormat = firstFormats.putIfAbsent(trackType, format)
                if (firstFormat != null && !format.equals(firstFormat)) {
                    // Format changed mid-stream (e.g. ad segment). Suppress it!
                    formatHolder.format = firstFormat
                }
            }
        }
        return result
    }

    override fun skipData(positionUs: Long): Int = wrapped.skipData(positionUs)

    override fun maybeThrowError() = wrapped.maybeThrowError()
}
