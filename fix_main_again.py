import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Filter insertions into detectedMediaFiles
# We need to make sure that ANY item added has its duration checked.

# 1. shouldInterceptRequest checking Candidate
import re
old_inter_add = re.search(r"                            val existsAlready = synchronized\(detectedMediaFiles\) \{.*?                            if \(\!existsAlready\) \{", content, re.DOTALL)
if old_inter_add:
    new_inter_add = """                            val candidate = mediaEngine.getCandidate(url)
                            val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)

                            val existsAlready = synchronized(detectedMediaFiles) {
                                detectedMediaFiles.any { it.url == url || (candidate != null && mediaEngine.getCandidate(it.url) == candidate) }
                            }
                            if (!existsAlready && isEligible) {"""
    content = content.replace(old_inter_add.group(0), new_inter_add)


# 2. IframeSniffer callback
old_iframe_cand = re.search(r"                                    val existsAlready = synchronized\(detectedMediaFiles\) \{.*?                                    if \(\!existsAlready\) \{", content, re.DOTALL)
if old_iframe_cand:
    new_iframe_cand = """                                    val candidate = mediaEngine.getCandidate(url)
                                    val isEligible = candidate == null || (candidate.durationSec == 0 || candidate.durationSec >= 60)
                                    val existsAlready = synchronized(detectedMediaFiles) {
                                        detectedMediaFiles.any { it.url == url }
                                    }
                                    if (!existsAlready && isEligible) {"""
    content = content.replace(old_iframe_cand.group(0), new_iframe_cand)

# 3. onDownloadActiveMedia
old_dl_add = re.search(r"                    synchronized\(activity\.detectedMediaFiles\) \{.*?                        activity\.detectedMediaFiles\.add\(0, mediaFile\).*?                    \}", content, re.DOTALL)
if old_dl_add:
    new_dl_add = """                    val cand = activity.mediaEngine.getCandidate(finalUrl)
                    val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                    if (isEligible) {
                        synchronized(activity.detectedMediaFiles) {
                            activity.detectedMediaFiles.removeIf { it.url == mediaFile.url }
                            activity.detectedMediaFiles.add(0, mediaFile)
                        }
                    }"""
    content = content.replace(old_dl_add.group(0), new_dl_add)

# 4. onMediaDetected
old_md_add = re.search(r"                            synchronized\(activity\.detectedMediaFiles\) \{.*?                                activity\.detectedMediaFiles\.add\(mediaFile\).*?                            \}", content, re.DOTALL)
if old_md_add:
    new_md_add = """                            val cand = activity.mediaEngine.getCandidate(url)
                            val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                            if (isEligible) {
                                synchronized(activity.detectedMediaFiles) {
                                    activity.detectedMediaFiles.add(mediaFile)
                                }
                            }"""
    content = content.replace(old_md_add.group(0), new_md_add)

# 5. handleMediaScanResult
old_sm_add = re.search(r"                            if \(\!existingUrls\.contains\(it\.url\)\) \{.*?                                detectedMediaFiles\.add\(it\)", content, re.DOTALL)
if old_sm_add:
    new_sm_add = """                            val cand = mediaEngine.getCandidate(it.url)
                            val isEligible = cand == null || (cand.durationSec == 0 || cand.durationSec >= 60)
                            if (!existingUrls.contains(it.url) && isEligible) {
                                detectedMediaFiles.add(it)"""
    content = content.replace(old_sm_add.group(0), new_sm_add)

# 6. onActiveMediaFound fallback addition
old_am_add = re.search(r"                            // URL was not previously caught by shouldInterceptRequest but is playing, add it now.*?                            activity\.detectedMediaFiles\.add\(0, MediaFile\(.*?                            updated = true", content, re.DOTALL)
if old_am_add:
    new_am_add = """                            // URL was not previously caught by shouldInterceptRequest but is playing, add it now
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
    content = content.replace(old_am_add.group(0), new_am_add)


with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
