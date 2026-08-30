package com.omymaxz.download

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.common.TrackGroup
import androidx.media3.common.Format
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.source.SampleStream

class SingleFormatMediaPeriod(private val wrapped: MediaPeriod) : MediaPeriod by wrapped {
    override fun getTrackGroups(): TrackGroupArray {
        val originalGroups = wrapped.trackGroups
        val newGroups = Array(originalGroups.length) { i ->
            val group = originalGroups.get(i)
            if (group.length > 0) {
                var bestFormat = group.getFormat(0)
                for (j in 1 until group.length) {
                    val f = group.getFormat(j)
                    if (f.bitrate > bestFormat.bitrate || f.width > bestFormat.width) {
                        bestFormat = f
                    }
                }
                TrackGroup(group.id, bestFormat)
            } else {
                group
            }
        }
        return TrackGroupArray(*newGroups)
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        // If the caller selected our single-format group, we need to map the index back to the original group!
        // Wait, ExoPlayer's track selection logic passes the ExoTrackSelection which contains the original TrackGroup inside it?
        // No, ExoTrackSelection uses the TrackGroup WE provided.
        // If we provide a synthetic TrackGroup, it might cause issues downstream in the actual MediaPeriod.
        // It's safer to intercept it at the TrackSelector level.
    }
}
