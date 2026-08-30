package com.omymaxz.download

import android.content.Context
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.transformer.Transformer

fun configure(context: Context) {
    // Media3 Transformer does not have a TrackSelector exposed, but we can configure TrackSelectionParameters via EditedMediaItem or MediaItem?
    // Let's check MediaItem Builder. Wait, MediaItem doesn't have track selection parameters?
}
