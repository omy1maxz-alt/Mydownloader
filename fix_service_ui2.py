import sys

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    content = f.read()

old_state = """    fun updateDetectorState() {
        if (!isDetectorMode || bubbleView == null) return

        val txtBadge = bubbleView?.findViewById<TextView>(R.id.txt_bubble_badge)
        val txtStatus = bubbleView?.findViewById<TextView>(R.id.txt_detector_status)
        val txtDetails = bubbleView?.findViewById<TextView>(R.id.txt_detector_details)
        val btnOpenPlayer = bubbleView?.findViewById<Button>(R.id.btn_open_player)

        // This requires MainActivity to expose the mediaEngine instance, but since a Service doesn't have a direct reference to the Activity instance easily without binding, we should fetch it via a static registry or singleton engine if possible.
        // For now, let's fetch it if we can access the MediaDetectionEngine through a singleton, or we can just send broadasts.
        // Since MediaDetectionEngine is currently instantiated in MainActivity, we can add a static accessor.
    }"""

new_state = """    fun updateDetectorState() {
        if (!isDetectorMode || bubbleView == null) return

        val txtBadge = bubbleView?.findViewById<TextView>(R.id.txt_bubble_badge)
        val txtStatus = bubbleView?.findViewById<TextView>(R.id.txt_detector_status)
        val txtDetails = bubbleView?.findViewById<TextView>(R.id.txt_detector_details)
        val btnOpenPlayer = bubbleView?.findViewById<Button>(R.id.btn_open_player)

        val engine = MediaDetectionEngine.instance ?: return

        val candidates = engine.candidates.values.toList()
        val verifiedPlayable = candidates.find { it.isActivePlayer && it.durationSec >= 60 }
        val bestCand = engine.getBestCandidate()

        txtBadge?.text = "[ ${candidates.size} ]"

        if (verifiedPlayable != null || bestCand != null) {
            val mainCand = verifiedPlayable ?: bestCand!!
            txtStatus?.text = "● PLAYBACK VERIFIED"
            txtStatus?.setTextColor(android.graphics.Color.GREEN)

            val typeStr = if (mainCand.isManifest) "HLS/DASH" else "Progressive"
            val durStr = if (mainCand.durationSec > 0) "${mainCand.durationSec}s" else "Unknown"
            val scoreStr = mainCand.finalScore

            txtDetails?.text = "$typeStr \\nDur: $durStr \\nScore: $scoreStr \\nConf: ${mainCand.confidence}\\nCandidates: ${candidates.size}"

            btnOpenPlayer?.visibility = View.VISIBLE
            btnOpenPlayer?.setOnClickListener {
                val launchIntent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, mainCand.url)
                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, "Detected Media")
                }
                startActivity(launchIntent)
            }
        } else {
            txtStatus?.text = "● NO VERIFIED MEDIA"
            txtStatus?.setTextColor(android.graphics.Color.YELLOW)
            txtDetails?.text = "Candidates: ${candidates.size}"
            btnOpenPlayer?.visibility = View.GONE
        }
    }"""

content = content.replace(old_state, new_state)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(content)

print("Done")
