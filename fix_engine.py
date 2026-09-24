import sys

# The user reported that my previous duration filter (added to MainActivity directly before adding to detectedMediaFiles) caused issues where legitimate <60s videos were not added at all, or where ads managed to bypass because of 0 duration, or similar.
# Wait, the user wrote: "Sextb currently works better when the global duration filter is absent."
# "NEVER treat "durationSec == 0" as an ad. It means unknown/provisional/live/MSE/not-yet-measured until proven otherwise. Also do not put a hard "<60s" rejection at the raw discovery layer."
# "Only ELIGIBLE_MAIN_MEDIA should be exposed as the main detected-media item."

# Let's clean out the strict filtering from `MainActivity` when adding to `detectedMediaFiles`.

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Revert shouldInterceptRequest checking Candidate
import re
old_inter_cand = re.search(r"                            val candidate = mediaEngine\.getCandidate\(url\).*?                            val existsAlready = synchronized\(detectedMediaFiles\) \{", content, re.DOTALL)
if old_inter_cand:
    new_inter_cand = """                            val existsAlready = synchronized(detectedMediaFiles) {"""
    content = content.replace(old_inter_cand.group(0), new_inter_cand)

old_inter_add = re.search(r"                            // 60-Second Hard Eligibility Check.*?                            if \(\!existsAlready && isEligible\) \{", content, re.DOTALL)
if old_inter_add:
    content = content.replace(old_inter_add.group(0), "                            if (!existsAlready) {")

# 2. IframeSniffer callback
old_iframe_cand = re.search(r"                                    val candidate = mediaEngine\.getCandidate\(url\).*?                                    if \(\!existsAlready && isEligible\) \{", content, re.DOTALL)
if old_iframe_cand:
    content = content.replace(old_iframe_cand.group(0), "                                    if (!existsAlready) {")

# 3. onDownloadActiveMedia
old_dl_add = re.search(r"                    val cand = activity\.mediaEngine\.getCandidate\(finalUrl\).*?                    if \(isEligible\) \{.*?synchronized\(activity\.detectedMediaFiles\) \{.*?\}", content, re.DOTALL)
if old_dl_add:
    new_dl_add = """                    synchronized(activity.detectedMediaFiles) {
                        activity.detectedMediaFiles.removeIf { it.url == mediaFile.url }
                        activity.detectedMediaFiles.add(0, mediaFile)
                    }"""
    content = content.replace(old_dl_add.group(0), new_dl_add)

# 4. onMediaDetected
old_md_add = re.search(r"                            val cand = activity\.mediaEngine\.getCandidate\(url\).*?                            if \(isEligible\) \{.*?synchronized\(activity\.detectedMediaFiles\) \{.*?\}", content, re.DOTALL)
if old_md_add:
    new_md_add = """                            synchronized(activity.detectedMediaFiles) {
                                activity.detectedMediaFiles.add(mediaFile)
                            }"""
    content = content.replace(old_md_add.group(0), new_md_add)

# 5. handleMediaScanResult
old_sm_add = re.search(r"                            val cand = mediaEngine\.getCandidate\(it\.url\).*?                            if \(\!existingUrls\.contains\(it\.url\) && isEligible\) \{", content, re.DOTALL)
if old_sm_add:
    content = content.replace(old_sm_add.group(0), "                            if (!existingUrls.contains(it.url)) {")

# 6. onActiveMediaFound fallback addition
old_am_add = re.search(r"                            val cand = activity\.mediaEngine\.getCandidate\(videoUrl\).*?                            if \(isEligible\) \{.*?activity\.detectedMediaFiles\.add\(0.*?\}\)", content, re.DOTALL)
if old_am_add:
    new_am_add = """                            activity.detectedMediaFiles.add(0, MediaFile(
                                url = videoUrl, title = "Detected_Video_${System.currentTimeMillis()}",
                                mimeType = "video/*", quality = "Auto", category = MediaCategory.VIDEO,
                                fileSize = "Unknown", language = null, isMainContent = true,
                                referer = activity.lastUsedUrl
                            ))"""
    content = content.replace(old_am_add.group(0), new_am_add)


with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Cleaned MainActivity restrictions")
