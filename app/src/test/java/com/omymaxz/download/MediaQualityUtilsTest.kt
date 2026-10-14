package com.omymaxz.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQualityUtilsTest {
    @Test
    fun singleHlsVariantIsNotMarkedAsMultiQuality() {
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
720/index.m3u8
"""
        assertFalse(MediaQualityUtils.hasMultipleHlsVariants(playlist))
    }

    @Test
    fun multipleHlsVariantsAreMarkedAsMultiQuality() {
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360
360/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
720/index.m3u8
"""
        assertTrue(MediaQualityUtils.hasMultipleHlsVariants(playlist))
    }

    @Test
    fun mediaPlaylistWithSegmentsIsNotMarkedAsMultiQuality() {
        val playlist = """#EXTM3U
#EXTINF:6.0,
segment001.ts
#EXTINF:6.0,
segment002.ts
"""
        assertFalse(MediaQualityUtils.hasMultipleHlsVariants(playlist))
    }

    @Test
    fun oneDashVideoRepresentationPlusAudioIsNotMultiQuality() {
        val mpd = """<MPD><Period>
          <AdaptationSet contentType="video" mimeType="video/mp4">
            <Representation id="v1" bandwidth="1000" />
          </AdaptationSet>
          <AdaptationSet contentType="audio" mimeType="audio/mp4">
            <Representation id="a1" bandwidth="128" />
            <Representation id="a2" bandwidth="96" />
          </AdaptationSet>
        </Period></MPD>"""
        assertFalse(MediaQualityUtils.hasMultipleDashVideoRepresentations(mpd))
    }

    @Test
    fun multipleDashVideoRepresentationsAreMultiQuality() {
        val mpd = """<MPD><Period>
          <AdaptationSet contentType="video" mimeType="video/mp4">
            <Representation id="v360" bandwidth="500000" />
            <Representation id="v720" bandwidth="1500000" />
          </AdaptationSet>
          <AdaptationSet contentType="audio" mimeType="audio/mp4">
            <Representation id="a1" bandwidth="128" />
          </AdaptationSet>
        </Period></MPD>"""
        assertTrue(MediaQualityUtils.hasMultipleDashVideoRepresentations(mpd))
    }
}
