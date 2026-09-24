import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

old_build = """    private fun buildMediaGroupingKey(rawUrl: String): String {
        return try {
            val uri = java.net.URL(rawUrl)
            val path = uri.path.lowercase().substringBeforeLast("/", "")

            val queryKeys = uri.query
                ?.split("&")
                ?.mapNotNull { part ->
                    val key = part.substringBefore("=").lowercase()
                    if (key.isNotBlank()) key else null
                }
                ?.filterNot {
                    it in setOf("token", "signature", "sig", "expires", "expires_at", "hdntl", "auth_key", "hash")
                }
                ?.sorted()
                ?.joinToString(",")
                .orEmpty()

            "${uri.host.lowercase()}|$path|$queryKeys"
        } catch (_: Exception) {
            rawUrl
        }
    }"""

new_build = """    private fun buildMediaGroupingKey(rawUrl: String): String {
        return try {
            val uri = java.net.URL(rawUrl)
            val path = uri.path.lowercase().substringBeforeLast("/", "")

            val queryKeys = uri.query
                ?.split("&")
                ?.mapNotNull { part ->
                    val key = part.substringBefore("=").lowercase()
                    if (key.isNotBlank()) key else null
                }
                ?.filterNot {
                    it in setOf("token", "signature", "sig", "expires", "expires_at", "hdntl", "auth_key", "hash", "_t", "rnd", "time", "client")
                }
                ?.sorted()
                ?.joinToString(",")
                .orEmpty()

            "${uri.host.lowercase()}|$path|$queryKeys"
        } catch (_: Exception) {
            rawUrl
        }
    }"""

content = content.replace(old_build, new_build)

old_process = """        // Check for ad URL signals
        val isLikelyAd = isAdUrl(url)

        // Image blocking specifically for thumbnail segments masquerading as media
        if (!isManifest && !isProgressiveFinal && isLikelyAd) {
             isSegment = false
        }



        val type = when (mediaKind) {"""

new_process = """        // Check for ad URL signals
        val isLikelyAd = isAdUrl(url)

        // Image blocking specifically for thumbnail segments masquerading as media
        if (!isManifest && !isProgressiveFinal && isLikelyAd) {
             isSegment = false
        }

        // Deduplication: If we already have an identical progressive candidate (ignoring noisy query params), ignore this duplicate
        if (isProgressiveFinal) {
            val groupKey = buildMediaGroupingKey(url)
            val existing = candidates.values.find { it.isProgressiveFinal && buildMediaGroupingKey(it.url) == groupKey }
            if (existing != null) {
                existing.requestCount++
                existing.lastSeenTime = System.currentTimeMillis()
                return existing
            }
        }

        val type = when (mediaKind) {"""

content = content.replace(old_process, new_process)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
