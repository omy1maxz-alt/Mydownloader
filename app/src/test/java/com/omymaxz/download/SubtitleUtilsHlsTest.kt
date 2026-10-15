package com.omymaxz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleUtilsHlsTest {
    @Test
    fun resolvesEveryRelativeSegmentAgainstSubtitlePlaylistUrl() {
        val playlist = """#EXTM3U
#EXT-X-VERSION:3
#EXTINF:5.0,
segments/part000.vtt
#EXTINF:5.0,
segments/part001.vtt?sig=two
#EXT-X-ENDLIST
"""
        assertEquals(
            listOf(
                "https://cdn.example/episode/subtitles/segments/part000.vtt",
                "https://cdn.example/episode/subtitles/segments/part001.vtt?sig=two"
            ),
            SubtitleUtils.parseHlsSubtitlePlaylistUris(
                playlist,
                "https://cdn.example/episode/subtitles/index.m3u8"
            )
        )
    }

    @Test
    fun followsFirstVariantWhenSubtitlePlaylistIsMaster() {
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=12000
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=24000
high/index.m3u8
"""
        assertEquals(
            listOf("https://cdn.example/subs/low/index.m3u8"),
            SubtitleUtils.parseHlsSubtitlePlaylistUris(playlist, "https://cdn.example/subs/master.m3u8")
        )
    }

    @Test
    fun mergesAllWebVttSegmentsAndRemovesRepeatedSegmentHeaders() {
        val merged = SubtitleUtils.mergeHlsSubtitleSegments(
            listOf(
                """WEBVTT
X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0

00:00:00.000 --> 00:00:02.000
First caption""",
                """WEBVTT
X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:90000

00:00:02.000 --> 00:00:04.000
Second caption"""
            )
        )
        assertTrue(merged.startsWith("WEBVTT\n\n"))
        assertTrue(merged.contains("00:00:00.000 --> 00:00:02.000\nFirst caption"))
        assertTrue(merged.contains("00:00:02.000 --> 00:00:04.000\nSecond caption"))
        assertEquals(2, Regex("-->").findAll(merged).count())
        assertEquals(1, Regex("WEBVTT").findAll(merged).count())
        assertEquals(0, Regex("X-TIMESTAMP-MAP").findAll(merged).count())
    }
}
