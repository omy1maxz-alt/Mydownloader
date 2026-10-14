package com.omymaxz.download

/**
 * Playlist-only quality detection. An HLS URL is not proof of multiple quality
 * choices: a media playlist can contain a single rendition and only its segments.
 */
internal object MediaQualityUtils {
    fun hasMultipleHlsVariants(playlist: String): Boolean {
        val variantCount = Regex("""(?im)^[ \t]*#EXT-X-STREAM-INF\s*:""")
            .findAll(playlist)
            .take(2)
            .count()
        return variantCount > 1
    }
}
