package com.omymaxz.download

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsQualityVariantCountTest {
    @Test
    fun mediaPlaylistDoesNotImplyMultipleQualities() {
        val playlist = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:6.0,
segment000.ts
#EXTINF:6.0,
segment001.ts
#EXT-X-ENDLIST
"""
        assertEquals(0, countHlsQualityVariants(playlist))
    }

    @Test
    fun masterPlaylistCountsActualRenditions() {
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
360p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
720p/index.m3u8
"""
        assertEquals(2, countHlsQualityVariants(playlist))
    }

    @Test
    fun singleRenditionMasterIsNotMultiQuality() {
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
360p/index.m3u8
"""
        assertEquals(1, countHlsQualityVariants(playlist))
    }
}
