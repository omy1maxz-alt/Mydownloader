import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Filter insertions into detectedMediaFiles
# We need to make sure that ANY item added has its duration checked, but wait, `detectedMediaFiles` uses `MediaFile`, not `MediaCandidate`. `MediaFile` does not have a `durationSec` field!
# Wait, `mediaEngine` has candidates. We should check the candidate's duration before adding to UI!

old_add_1 = """                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { it.url == url || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                            }
                            if (!existsAlready) {"""

new_add_1 = """                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { it.url == url || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                            }
                            // 60-Second Hard Eligibility Check
                            val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)
                            if (!existsAlready && isEligible) {"""
content = content.replace(old_add_1, new_add_1)

old_add_2 = """                                        val existsAlready = synchronized(detectedMediaFiles) {
                                            detectedMediaFiles.any { it.url == url || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                                        }
                                        if (!existsAlready) {"""

new_add_2 = """                                        val existsAlready = synchronized(detectedMediaFiles) {
                                            detectedMediaFiles.any { it.url == url || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                                        }
                                        val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)
                                        if (!existsAlready && isEligible) {"""
content = content.replace(old_add_2, new_add_2)


old_add_3 = """                            val existsAlready = synchronized(activity.detectedMediaFiles) {
                                activity.detectedMediaFiles.any { it.url == url || (candidate != null && activity.mediaEngine.getCandidate(it.url) == candidate) }
                            }
                            if (!existsAlready) {"""

new_add_3 = """                            val existsAlready = synchronized(activity.detectedMediaFiles) {
                                activity.detectedMediaFiles.any { it.url == url || (candidate != null && activity.mediaEngine.getCandidate(it.url) == candidate) }
                            }
                            val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)
                            if (!existsAlready && isEligible) {"""
content = content.replace(old_add_3, new_add_3)


old_add_4 = """                            // URL was not previously caught by shouldInterceptRequest but is playing, add it now
                            activity.detectedMediaFiles.add(0, MediaFile(
                                url = videoUrl, title = "Detected_Video_${System.currentTimeMillis()}",
                                mimeType = "video/*", quality = "Auto", category = MediaCategory.VIDEO,
                                fileSize = "Unknown", language = null, isMainContent = true,
                                referer = activity.lastUsedUrl
                            ))
                            updated = true"""

new_add_4 = """                            // URL was not previously caught by shouldInterceptRequest but is playing, add it now
                            val cand = activity.mediaEngine.getCandidate(videoUrl)
                            val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                            if (isEligible) {
                                activity.detectedMediaFiles.add(0, MediaFile(
                                    url = videoUrl, title = "Detected_Video_${System.currentTimeMillis()}",
                                    mimeType = "video/*", quality = "Auto", category = MediaCategory.VIDEO,
                                    fileSize = "Unknown", language = null, isMainContent = true,
                                    referer = activity.lastUsedUrl
                                ))
                                updated = true
                            }"""
content = content.replace(old_add_4, new_add_4)

# Also fix getBestCandidate inside MediaDetectionEngine to reject < 60s
with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
