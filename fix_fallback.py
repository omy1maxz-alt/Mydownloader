import re

with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'r') as f:
    content = f.read()

target = """                    bundledMediaItem != null -> {
                        // Use the bundled MediaItem directly if provided (from CustomPlayerActivity 'Play in App' export)
                        try {
                            muxToMp4WithTransformer(bundledMediaItem, title)
                        } catch (e: Exception) {
                            writeExportLog("Transformer failed, falling back to muxToMp4FromCache: ${e.message}")
                            if (videoUrl != null) {
                                val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                                try {
                                    muxToMp4FromCache(finalUrl, title)
                                } catch (cacheEx: Exception) {
                                    writeExportLog("muxToMp4FromCache failed (likely incomplete cache), falling back to network FFmpeg: ${cacheEx.message}")
                                    muxToMp4(finalUrl, title)
                                }
                            } else {
                                throw e
                            }
                        }
                    }"""

orig = """                    bundledMediaItem != null -> {
                        // Use the bundled MediaItem directly if provided (from CustomPlayerActivity 'Play in App' export)
                        try {
                            muxToMp4WithTransformer(bundledMediaItem, title)
                        } catch (e: Exception) {
                            writeExportLog("Transformer failed, falling back to FFmpeg: ${e.message}")
                            if (videoUrl != null) {
                                val finalUrl = resolveVariantUrl(videoUrl, streamKeyStrings)
                                muxToMp4(finalUrl, title)
                            } else {
                                throw e
                            }
                        }
                    }"""

if orig in content:
    content = content.replace(orig, target)
    with open('app/src/main/java/com/omymaxz/download/HlsExportService.kt', 'w') as f:
        f.write(content)
    print("Patched fallback block to use muxToMp4FromCache")
else:
    print("Could not find block")
