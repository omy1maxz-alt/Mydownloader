package com.omymaxz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaybackPolicyTest {
    @Test
    fun adaptiveManifestDoesNotAttachSeparateAudio() {
        assertNull(
            YouTubePlaybackPolicy.separateAudioUrl(
                YouTubeVideoSourceKind.ADAPTIVE_MANIFEST,
                "https://audio.example/stream"
            )
        )
    }

    @Test
    fun combinedVideoAudioStreamDoesNotAttachSeparateAudio() {
        assertNull(
            YouTubePlaybackPolicy.separateAudioUrl(
                YouTubeVideoSourceKind.COMBINED_AUDIO_VIDEO,
                "https://audio.example/stream"
            )
        )
    }

    @Test
    fun videoOnlyStreamKeepsItsSeparateAudioSource() {
        val audioUrl = "https://audio.example/stream"
        assertEquals(
            audioUrl,
            YouTubePlaybackPolicy.separateAudioUrl(
                YouTubeVideoSourceKind.VIDEO_ONLY,
                audioUrl
            )
        )
    }
}
