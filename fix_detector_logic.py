import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Instead of updating the `FloatingBubbleService` which the user explicitly said is NOT the detector:
# "It is NOT a CustomPlayerActivity button. It is NOT the PiP/Custom Player bubble... The feature I originally wanted is #3: A floating button that appears from the WebMedia/browser screen when the detector has identified and VERIFIED that actual media is playing."

old_update = """    fun updateFabVisibility() {
        val hasMedia = synchronized(detectedMediaFiles) {
            detectedMediaFiles.isNotEmpty()
        }
        binding.fabShowMedia.visibility = if (hasMedia) View.VISIBLE else View.GONE
    }"""

new_update = """    fun updateFabVisibility() {
        val hasMedia = synchronized(detectedMediaFiles) {
            detectedMediaFiles.isNotEmpty()
        }
        binding.fabShowMedia.visibility = if (hasMedia) View.VISIBLE else View.GONE

        // Update the new WebMedia Detector Floating Button
        val bestPlayable = mediaEngine.getBestCandidate()
        val floatingDetector = findViewById<android.widget.LinearLayout>(R.id.floatingDetectorUI)
        val txtState = findViewById<android.widget.TextView>(R.id.txtDetectorState)

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

content = content.replace(old_update, new_update)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Updated updateFabVisibility to toggle floatingDetectorUI")
