import sys

# Now we have correctly separated FloatingBubbleService (for CustomPlayer) and floatingDetectorUI (for WebMedia playback verification state).
# In MainActivity.kt, the `floatingDetectorUI` in `activity_main.xml` is controlled by `updateFabVisibility`.
# Let's check `MainActivity.kt`'s `updateFabVisibility` implementation again.

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

import re
old_fab = re.search(r"    fun updateFabVisibility\(\) \{.*?    \}", content, re.DOTALL)
if old_fab:
    new_fab = """    fun updateFabVisibility() {
        val hasMedia = synchronized(detectedMediaFiles) {
            detectedMediaFiles.isNotEmpty()
        }
        binding.fabShowMedia.visibility = if (hasMedia) View.VISIBLE else View.GONE

        // Update the new WebMedia Detector Floating Button
        val bestPlayable = mediaEngine.getBestCandidate()
        val floatingDetector = findViewById<android.widget.LinearLayout>(R.id.floatingDetectorUI)
        val txtState = findViewById<android.widget.TextView>(R.id.txtDetectorState)

        // Ensure ONLY verified, playing media shows the floating detector.
        // We require duration validation (> 60s) AND active playing state.
        if (bestPlayable != null && bestPlayable.isActivePlayer && bestPlayable.durationSec >= 60) {
            floatingDetector?.visibility = View.VISIBLE
            val typeStr = if (bestPlayable.isManifest) "HLS" else "MP4"
            txtState?.text = "Verified: $typeStr"
            floatingDetector?.setOnClickListener {
                showMediaListDialog()
            }
        } else {
            floatingDetector?.visibility = View.GONE
        }
    }"""
    content = content.replace(old_fab.group(0), new_fab)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Updated updateFabVisibility")
