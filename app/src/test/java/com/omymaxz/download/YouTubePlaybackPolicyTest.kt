package com.omymaxz.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackPolicyTest {
    @Test
    fun adaptiveManifestDoesNotNeedSeparateAudio() {
        assertFalse(
            YouTubePlaybackPolicy.requiresSeparateAudio(
                YouTubeVideoSourceKind.ADAPTIVE_MANIFEST
            )
        )
    }

    @Test
    fun combinedVideoAudioStreamDoesNotNeedSeparateAudio() {
        assertFalse(
            YouTubePlaybackPolicy.requiresSeparateAudio(
                YouTubeVideoSourceKind.COMBINED_AUDIO_VIDEO
            )
        )
    }

    @Test
    fun videoOnlyStreamRequiresSeparateAudio() {
        assertTrue(
            YouTubePlaybackPolicy.requiresSeparateAudio(
                YouTubeVideoSourceKind.VIDEO_ONLY
            )
        )
    }
}
