import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Fix IframeSniffer callback
old_iframe = """                                    val existsAlready = synchronized(detectedMediaFiles) {
                                        detectedMediaFiles.any { it.url == url }
                                    }
                                    if (!existsAlready) {"""

new_iframe = """                                    val existsAlready = synchronized(detectedMediaFiles) {
                                        detectedMediaFiles.any { it.url == url }
                                    }
                                    val candidate = mediaEngine.getCandidate(url)
                                    val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)
                                    if (!existsAlready && isEligible) {"""
content = content.replace(old_iframe, new_iframe)

# Fix onDownloadActiveMedia
old_download_add = """                    synchronized(activity.detectedMediaFiles) {
                        activity.detectedMediaFiles.removeIf { it.url == mediaFile.url }
                        activity.detectedMediaFiles.add(0, mediaFile)
                    }"""

new_download_add = """                    val cand = activity.mediaEngine.getCandidate(finalUrl)
                    val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                    if (isEligible) {
                        synchronized(activity.detectedMediaFiles) {
                            activity.detectedMediaFiles.removeIf { it.url == mediaFile.url }
                            activity.detectedMediaFiles.add(0, mediaFile)
                        }
                    }"""
content = content.replace(old_download_add, new_download_add)

# Fix onMediaDetected callback
old_media_det = """                            synchronized(activity.detectedMediaFiles) {
                                activity.detectedMediaFiles.add(mediaFile)
                            }"""

new_media_det = """                            val cand = activity.mediaEngine.getCandidate(url)
                            val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                            if (isEligible) {
                                synchronized(activity.detectedMediaFiles) {
                                    activity.detectedMediaFiles.add(mediaFile)
                                }
                            }"""
content = content.replace(old_media_det, new_media_det)


# Fix handleMediaScanResult
old_scan_res = """                        val existingUrls = detectedMediaFiles.map { it.url }.toSet()
                        newMediaFiles.forEach {
                            if (!existingUrls.contains(it.url)) {
                                detectedMediaFiles.add(it)"""

new_scan_res = """                        val existingUrls = detectedMediaFiles.map { it.url }.toSet()
                        newMediaFiles.forEach {
                            val cand = mediaEngine.getCandidate(it.url)
                            val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                            if (!existingUrls.contains(it.url) && isEligible) {
                                detectedMediaFiles.add(it)"""
content = content.replace(old_scan_res, new_scan_res)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Fixed UI list additions.")
