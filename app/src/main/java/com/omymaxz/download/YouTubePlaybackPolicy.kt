package com.omymaxz.download

/**
 * Only a video-only stream needs a second Media3 source for audio. Adaptive manifests
 * and combined streams already carry their own audio timeline; merging another audio
 * source can shorten playback to the duration of that second source.
 */
internal enum class YouTubeVideoSourceKind {
    ADAPTIVE_MANIFEST,
    COMBINED_AUDIO_VIDEO,
    VIDEO_ONLY
}

internal object YouTubePlaybackPolicy {
    fun requiresSeparateAudio(sourceKind: YouTubeVideoSourceKind): Boolean =
        sourceKind == YouTubeVideoSourceKind.VIDEO_ONLY
}
