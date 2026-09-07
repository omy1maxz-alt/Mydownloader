import re

with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'r') as f:
    content = f.read()

# We need to scan the playlist for `#EXT-X-MAP` to determine if it's fMP4.
# In `processPlaylist`, we can just check `val isFmp4 = playlistContent.contains("#EXT-X-MAP")`

target = """                val isFmp4 = playlistContent.contains("#EXT-X-MAP")
                val lines = playlistContent.lines()
                val newLines = mutableListOf<String>()
                var segmentIndex = 0

                for (line in lines) {
                    if (line.isBlank()) continue

                    if (line.startsWith("#EXT-X-MAP:URI=")) {
                        val uriMatch = Regex("URI=\\"([^\\"]+)\\"").find(line)
                        if (uriMatch != null) {
                            val uriStr = uriMatch.groupValues[1]
                            val fullUrl = if (uriStr.startsWith("http")) uriStr else java.net.URI(playlistUrl).resolve(uriStr).toString()
                            var ext = fullUrl.substringAfterLast(".", "mp4").substringBefore("?")
                            if (ext.lowercase() in listOf("png", "jpg", "jpeg", "bmp", "gif", "bin", "php")) {
                                ext = "mp4" // Assuming fMP4 init chunks shouldn't be fake images either
                            }
                            val localFile = File(tmpDir, "init_$segmentIndex.$ext")

                            val mapSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            try {
                                cacheOnlyFactory.open(mapSpec)
                            } catch (e: Exception) {
                                networkFactory.open(mapSpec)
                            }
                            val fos = java.io.FileOutputStream(localFile)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            try {
                                while (true) {
                                    val source = if (cacheOnlyFactory.uri != null) cacheOnlyFactory else networkFactory
                                    val r = source.read(buffer, 0, buffer.size)
                                    if (r == -1) break
                                    fos.write(buffer, 0, r)
                                }
                            } finally {
                                fos.close()
                                cacheOnlyFactory.close()
                                networkFactory.close()
                            }
                            newLines.add(line.replace(uriStr, localFile.name))
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }

                    if (line.startsWith("#EXT-X-KEY:URI=")) {
                        val uriMatch = Regex("URI=\\"([^\\"]+)\\"").find(line)
                        if (uriMatch != null && !uriMatch.groupValues[1].startsWith("data:")) {
                            val uriStr = uriMatch.groupValues[1]
                            val fullUrl = if (uriStr.startsWith("http")) uriStr else java.net.URI(playlistUrl).resolve(uriStr).toString()
                            val localFile = File(tmpDir, "key_$segmentIndex.bin")

                            val keySpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            try {
                                cacheOnlyFactory.open(keySpec)
                            } catch (e: Exception) {
                                networkFactory.open(keySpec)
                            }
                            val fos = java.io.FileOutputStream(localFile)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            try {
                                while (true) {
                                    val source = if (cacheOnlyFactory.uri != null) cacheOnlyFactory else networkFactory
                                    val r = source.read(buffer, 0, buffer.size)
                                    if (r == -1) break
                                    fos.write(buffer, 0, r)
                                }
                            } finally {
                                fos.close()
                                cacheOnlyFactory.close()
                                networkFactory.close()
                            }
                            newLines.add(line.replace(uriStr, localFile.absolutePath))
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }

                    if (!line.startsWith("#")) {
                        val segmentUrl = if (line.startsWith("http")) line else java.net.URI(playlistUrl).resolve(line).toString()
                        var ext = segmentUrl.substringAfterLast(".", "ts").substringBefore("?")
                        // FFmpeg strictly blocks non-media extensions (like .PNG obfuscation) in LOCAL playlists for security.
                        // We must normalize image/fake extensions back to media extensions.
                        if (ext.lowercase() in listOf("png", "jpg", "jpeg", "bmp", "gif", "bin", "php") || !segmentUrl.contains(".")) {
                            ext = if (isFmp4) "m4s" else "ts"
                        }
                        val localSegment = File(tmpDir, "seg_${outputFileName}_%05d.$ext".format(segmentIndex))"""

orig = """                val lines = playlistContent.lines()
                val newLines = mutableListOf<String>()
                var segmentIndex = 0

                for (line in lines) {
                    if (line.isBlank()) continue

                    if (line.startsWith("#EXT-X-MAP:URI=")) {
                        val uriMatch = Regex("URI=\\"([^\\"]+)\\"").find(line)
                        if (uriMatch != null) {
                            val uriStr = uriMatch.groupValues[1]
                            val fullUrl = if (uriStr.startsWith("http")) uriStr else java.net.URI(playlistUrl).resolve(uriStr).toString()
                            var ext = fullUrl.substringAfterLast(".", "mp4").substringBefore("?")
                            if (ext.lowercase() in listOf("png", "jpg", "jpeg", "bmp", "gif", "bin", "php")) {
                                ext = "mp4" // Assuming fMP4 init chunks shouldn't be fake images either
                            }
                            val localFile = File(tmpDir, "init_$segmentIndex.$ext")

                            val mapSpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            try {
                                cacheOnlyFactory.open(mapSpec)
                            } catch (e: Exception) {
                                networkFactory.open(mapSpec)
                            }
                            val fos = java.io.FileOutputStream(localFile)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            try {
                                while (true) {
                                    val source = if (cacheOnlyFactory.uri != null) cacheOnlyFactory else networkFactory
                                    val r = source.read(buffer, 0, buffer.size)
                                    if (r == -1) break
                                    fos.write(buffer, 0, r)
                                }
                            } finally {
                                fos.close()
                                cacheOnlyFactory.close()
                                networkFactory.close()
                            }
                            newLines.add(line.replace(uriStr, localFile.name))
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }

                    if (line.startsWith("#EXT-X-KEY:URI=")) {
                        val uriMatch = Regex("URI=\\"([^\\"]+)\\"").find(line)
                        if (uriMatch != null && !uriMatch.groupValues[1].startsWith("data:")) {
                            val uriStr = uriMatch.groupValues[1]
                            val fullUrl = if (uriStr.startsWith("http")) uriStr else java.net.URI(playlistUrl).resolve(uriStr).toString()
                            val localFile = File(tmpDir, "key_$segmentIndex.bin")

                            val keySpec = androidx.media3.datasource.DataSpec(android.net.Uri.parse(fullUrl))
                            try {
                                cacheOnlyFactory.open(keySpec)
                            } catch (e: Exception) {
                                networkFactory.open(keySpec)
                            }
                            val fos = java.io.FileOutputStream(localFile)
                            val buffer = ByteArray(1024 * 64)
                            var bytesRead: Int
                            try {
                                while (true) {
                                    val source = if (cacheOnlyFactory.uri != null) cacheOnlyFactory else networkFactory
                                    val r = source.read(buffer, 0, buffer.size)
                                    if (r == -1) break
                                    fos.write(buffer, 0, r)
                                }
                            } finally {
                                fos.close()
                                cacheOnlyFactory.close()
                                networkFactory.close()
                            }
                            newLines.add(line.replace(uriStr, localFile.absolutePath))
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }

                    if (!line.startsWith("#")) {
                        val segmentUrl = if (line.startsWith("http")) line else java.net.URI(playlistUrl).resolve(line).toString()
                        var ext = segmentUrl.substringAfterLast(".", "ts").substringBefore("?")
                        // FFmpeg strictly blocks non-media extensions (like .PNG obfuscation) in LOCAL playlists for security.
                        // We must normalize image/fake extensions back to media extensions.
                        if (ext.lowercase() in listOf("png", "jpg", "jpeg", "bmp", "gif", "bin", "php") || !segmentUrl.contains(".")) {
                            ext = "ts"
                        }
                        val localSegment = File(tmpDir, "seg_${outputFileName}_%05d.$ext".format(segmentIndex))"""

if orig in content:
    content = content.replace(orig, target)
    with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'w') as f:
        f.write(content)
    print("Patched segment extension logic to respect fMP4")
else:
    print("Could not find block")
