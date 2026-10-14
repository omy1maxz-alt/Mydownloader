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

    /**
     * Counts representations only within video AdaptationSets, so a single video
     * rendition plus separate audio representations is not mislabeled multi-quality.
     */
    fun hasMultipleDashVideoRepresentations(mpd: String): Boolean {
        val adaptationSets = Regex("""(?is)<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet\\s*>""")
        var videoRepresentationCount = 0

        for (match in adaptationSets.findAll(mpd)) {
            val attributes = match.groupValues[1]
            val body = match.groupValues[2]
            val isVideoAdaptation =
                Regex("""(?i)\\bcontentType\\s*=\\s*["']video["']""").containsMatchIn(attributes) ||
                Regex("""(?i)\\bmimeType\\s*=\\s*["']video/[^"']+["']""").containsMatchIn(attributes) ||
                Regex("""(?i)<Representation\\b[^>]*\\bmimeType\\s*=\\s*["']video/[^"']+["']""")
                    .containsMatchIn(body)

            if (isVideoAdaptation) {
                videoRepresentationCount += Regex("""(?i)<Representation\\b""")
                    .findAll(body)
                    .take(2)
                    .count()
                if (videoRepresentationCount > 1) return true
            }
        }
        return false
    }
}
