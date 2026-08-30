package com.omymaxz.download

import android.content.Context
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.common.C
import java.util.Collections

class CustomMediaSourceFactory(
    private val defaultFactory: DefaultMediaSourceFactory
) : MediaSource.Factory by defaultFactory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val original = defaultFactory.createMediaSource(mediaItem)
        // FilteringMediaSource filters TrackTypes, NOT formats or specific bitrates.
        // It won't prevent adaptive bitrate switches within a video track.
        return original
    }
}
