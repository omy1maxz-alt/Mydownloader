import sys

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Let's fix the `copyMp4FromCache` to actually check SimpleCache spans instead of solely relying on CacheDataSource which throws on incomplete progressive files if length is unknown.
old_copyMp4 = """    private suspend fun copyMp4FromCache(url: String, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val cacheOnlyFactory = cacheOnlyDataSource()
        val uri = android.net.Uri.parse(url)
        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
        val dataSpec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(uri)
            .setKey(cacheKey)
            .build()

        try {
            writeExportLog("Reading MP4 directly from cache for: $url")
            cacheOnlyFactory.open(dataSpec)
            out.outputStream().use { fos ->
                val buffer = ByteArray(1024 * 256)
                var bytesRead: Int
                while (cacheOnlyFactory.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
            writeExportLog("MP4 cache copy complete: $title")
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            writeExportLog("Failed to copy MP4 from cache: ${e.message}")
            throw Exception("Failed to copy MP4 from cache", e)
        } finally {
            try {
                cacheOnlyFactory.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }"""

new_copyMp4 = """    private suspend fun copyMp4FromCache(url: String, title: String) = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        var out = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$safeTitle.mp4"
        )
        var counter = 1
        while (out.exists()) {
            out = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${safeTitle}_$counter.mp4"
            )
            counter++
        }

        val uri = android.net.Uri.parse(url)
        val cacheKey = HlsDownloadHelper.customCacheKeyFactory.buildCacheKey(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
        val cache = HlsDownloadHelper.getUnifiedCache(applicationContext)

        val spans = cache.getCachedSpans(cacheKey).sortedBy { it.position }
        if (spans.isEmpty()) {
            writeExportLog("No cache spans found for key: $cacheKey")
            throw Exception("No cache spans found for MP4")
        }

        try {
            writeExportLog("Copying MP4 from cache via direct spans (${spans.size} found) for key: $cacheKey")
            out.outputStream().use { fos ->
                for (span in spans) {
                    if (span.file != null && span.file!!.exists()) {
                        span.file!!.inputStream().use { fis ->
                            fis.copyTo(fos)
                        }
                    }
                }
            }

            // Notify MediaStore
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, out.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = applicationContext.contentResolver
                val targetUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri)?.use { output ->
                        out.inputStream().use { input -> input.copyTo(output) }
                    }
                    out.delete()
                }
            }
            writeExportLog("MP4 cache copy complete: $title")
        } catch (e: Exception) {
            if (out.exists()) out.delete()
            writeExportLog("Failed to copy MP4 from cache spans: ${e.message}")
            throw Exception("Failed to copy MP4 from cache spans", e)
        }
    }"""

content = content.replace(old_copyMp4, new_copyMp4)

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "w") as f:
    f.write(content)

print("Done")
