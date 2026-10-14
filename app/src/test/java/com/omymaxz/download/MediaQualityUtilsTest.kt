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
}
