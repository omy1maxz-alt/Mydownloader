import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# We need the UI list to only show ELIGIBLE media.
# The user states: "The detectedMediaFiles list in MainActivity is separate from MediaDetectionEngine.getBestCandidate()... The UI list must be a filtered projection of eligible candidates... Prefer a single source of truth for eligibility rather than duplicating slightly different filtering rules throughout MainActivity."
# If `detectedMediaFiles` is bound to the Adapter, we should ideally filter the list *before* showing it to the user, OR filter candidates before inserting them into `detectedMediaFiles`.

old_adapter = """        val adapter = MediaListAdapter(this, detectedMediaFiles, { mediaFile ->"""

new_adapter = """
        // Filter detected files to only show eligible presentations (duration 0 or >= 60s, and not an ad)
        val eligibleFiles = synchronized(detectedMediaFiles) {
            detectedMediaFiles.filter { mediaFile ->
                val cand = mediaEngine.getCandidate(mediaFile.url)
                if (cand != null) {
                    !(cand.durationSec in 1..59) && cand.adScore == 0
                } else {
                    !mediaEngine.isAdUrl(mediaFile.url) && !mediaFile.url.contains("media-hls.growcdnssedge.com/b-hls-")
                }
            }
        }

        val adapter = MediaListAdapter(this, eligibleFiles.toMutableList(), { mediaFile ->"""

content = content.replace(old_adapter, new_adapter)


with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
