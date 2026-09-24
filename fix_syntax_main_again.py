with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

import re

# We need to find the definition of `updateFabVisibility` and everything up to `private var lastYoutubeUrl: String? = null`
pattern = r"    fun updateFabVisibility\(\) \{.*?    \}(?=\n\n    private var lastYoutubeUrl: String\? = null)"

new_update = """    fun updateFabVisibility() {
        val hasMedia = synchronized(detectedMediaFiles) {
            detectedMediaFiles.isNotEmpty()
        }
        binding.fabShowMedia.visibility = if (hasMedia) android.view.View.VISIBLE else android.view.View.GONE

        binding.fabShowMedia.setOnLongClickListener {
            if (hasMedia) {
                showMediaListDialog()
            } else {
                android.widget.Toast.makeText(this, "No media intercepted yet. Play the video first.", android.widget.Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Update the new WebMedia Detector Floating Button
        val bestPlayable = mediaEngine.getBestCandidate()
        val floatingDetector = findViewById<android.widget.LinearLayout>(R.id.floatingDetectorUI)
        val txtState = findViewById<android.widget.TextView>(R.id.txtDetectorState)

        // Ensure ONLY verified, playing media shows the floating detector.
        // We require duration validation (> 60s) AND active playing state.
        if (bestPlayable != null && bestPlayable.isActivePlayer && bestPlayable.durationSec >= 60) {
            floatingDetector?.visibility = android.view.View.VISIBLE
            val typeStr = if (bestPlayable.isManifest) "HLS" else "MP4"
            txtState?.text = "Verified: $typeStr"
            floatingDetector?.setOnClickListener {
                showMediaListDialog()
            }
        } else {
            floatingDetector?.visibility = android.view.View.GONE
        }
    }"""

content = re.sub(pattern, new_update, content, flags=re.DOTALL)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
