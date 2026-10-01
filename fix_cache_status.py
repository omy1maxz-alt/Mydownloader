import re

with open('app/src/main/java/com/omymaxz/download/CustomPlayerActivity.kt', 'r') as f:
    content = f.read()

replacement = """    private val cacheProgressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val currentMediaItem = p.currentMediaItem

            if (currentMediaItem != null && !hasNotifiedCacheComplete) {
                // Using exact exact cache-complete evaluation mirroring HlsExportService
                val uri = currentMediaItem.localConfiguration?.uri
                if (uri != null) {
                    try {
                        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(
                            androidx.media3.datasource.DataSpec.Builder().setUri(uri).build()
                        )
                        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)
                        val spans = cache.getCachedSpans(cacheKey).sortedBy { it.position }
                        val metadata = cache.getContentMetadata(cacheKey)
                        val expectedLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(metadata)

                        var totalCachedBytes = 0L
                        for (span in spans) {
                            if (span.isCached && span.file != null && span.file!!.exists()) {
                                totalCachedBytes += span.length
                            }
                        }

                        // We use duration fallback only if length is unknown, but standard HLS/MPD
                        // might not report length properly in ContentMetadata immediately.
                        val isFullyCached = if (expectedLength > 0) {
                            totalCachedBytes >= expectedLength
                        } else {
                            // If expectedLength is missing, fall back to Exoplayer's buffer if it's 100%
                            // and the stream has reached the end, which is the old fallback behavior.
                            val duration = p.duration
                            val buffered = p.bufferedPosition
                            duration > 0 && buffered >= duration - 1500 && p.bufferedPercentage >= 99
                        }

                        if (isFullyCached) {
                            hasNotifiedCacheComplete = true
                            val fab = findViewById<android.widget.ImageButton>(R.id.fab_more_options)
                            fab?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
                            Toast.makeText(this@CustomPlayerActivity, "Video fully cached! Safe to Save Offline.", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            cacheProgressHandler.postDelayed(this, 1000)
        }
    }"""

start_str = '    private val cacheProgressRunnable = object : Runnable {'
end_str = '        }\n    }'

start_idx = content.find(start_str)
end_idx = content.find(end_str, start_idx) + len(end_str)

if start_idx != -1 and end_idx != -1:
    new_content = content[:start_idx] + replacement + content[end_idx:]
    with open('app/src/main/java/com/omymaxz/download/CustomPlayerActivity.kt', 'w') as f:
        f.write(new_content)
    print("Fixed cacheProgressRunnable")
else:
    print("Could not find cacheProgressRunnable")
