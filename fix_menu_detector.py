import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# I previously added code to launch FloatingBubbleService from the menu option, I need to remove that.
old_menu = """                R.id.menu_detect_active_media -> {
                    runActiveMediaDetection()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                        Toast.makeText(this@MainActivity, "Please grant overlay permission for the detector bubble", Toast.LENGTH_LONG).show()
                    } else {
                        val serviceIntent = Intent(this@MainActivity, FloatingBubbleService::class.java).apply {
                            putExtra(FloatingBubbleService.EXTRA_IS_DETECTOR, true)
                        }
                        startService(serviceIntent)
                    }
                }"""

new_menu = """                R.id.menu_detect_active_media -> runActiveMediaDetection()"""

content = content.replace(old_menu, new_menu)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    fb_content = f.read()

# Clean out the isDetectorMode from FloatingBubbleService to fully separate them as requested by the user.

import re
match = re.search(r"    // For WebMedia Detector state updates.*?// Global reference for MainActivity", fb_content, re.DOTALL)
if match:
    fb_content = fb_content.replace(match.group(0), "    // Global reference for MainActivity")

old_comp = """    companion object {
        var instance: FloatingBubbleService? = null
        const val ACTION_START_DETECTOR = "com.omymaxz.download.action.START_DETECTOR"
        const val ACTION_START_CUSTOM_PLAYER = "com.omymaxz.download.action.START_CUSTOM_PLAYER"
        const val EXTRA_IS_DETECTOR = "extra_is_detector"
    }"""
new_comp = """    companion object {
        var instance: FloatingBubbleService? = null
    }"""
fb_content = fb_content.replace(old_comp, new_comp)

old_start = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val isDet = intent.getBooleanExtra(EXTRA_IS_DETECTOR, false)
            if (isDet) {
                isDetectorMode = true
                if (bubbleView == null) {
                    createBubbleView()
                }
                startDetectorUpdates()
            } else {
                isDetectorMode = false
                videoUrl = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_URL)
                videoTitle = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE)
                currentPosition = intent.getLongExtra("current_position", 0L)

                if (bubbleView == null && videoUrl != null) {
                    createBubbleView()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startDetectorUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = object : Runnable {
            override fun run() {
                updateDetectorState()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    fun updateDetectorState() {
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

new_start = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            videoUrl = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_URL)
            videoTitle = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE)
            currentPosition = intent.getLongExtra("current_position", 0L)

            if (bubbleView == null && videoUrl != null) {
                createBubbleView()
            }
        }
        return START_NOT_STICKY
    }"""
fb_content = fb_content.replace(old_start, new_start)

old_touch = """                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                if (isDetectorMode) {
                                    isExpanded = !isExpanded
                                    expandedDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
                                    if (isExpanded) updateDetectorState()
                                } else if (videoUrl != null) {
                                    val launchIntent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
                                        putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, videoTitle)
                                    }
                                    startActivity(launchIntent)
                                    stopSelf()
                                }
                            }
                            return true
                        }"""

new_touch = """                        MotionEvent.ACTION_UP -> {
                            if (!moved && videoUrl != null) {
                                val launchIntent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
                                    putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, videoTitle)
                                }
                                startActivity(launchIntent)
                                stopSelf()
                            }
                            return true
                        }"""
fb_content = fb_content.replace(old_touch, new_touch)

old_destr = """    override fun onDestroy() {
        super.onDestroy()
        instance = null
        updateRunnable?.let { handler.removeCallbacks(it) }"""
new_destr = """    override fun onDestroy() {
        super.onDestroy()
        instance = null"""
fb_content = fb_content.replace(old_destr, new_destr)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(fb_content)

print("FloatingBubbleService cleaned of detector UI logic.")

with open("app/src/main/res/layout/layout_floating_bubble.xml", "r") as f:
    xml_content = f.read()

import re
old_xml = re.search(r"    <LinearLayout.*?        <!-- Expanded view -->", xml_content, re.DOTALL)
if old_xml:
    new_xml = """    <ImageView
        android:id="@+id/img_bubble_icon"
        android:layout_width="60dp"
        android:layout_height="60dp"
        android:background="@drawable/rounded_background"
        android:src="@android:drawable/ic_media_play"
        android:padding="12dp"
        android:elevation="4dp"/>

        <!-- Expanded view -->"""
    xml_content = xml_content.replace(old_xml.group(0), new_xml)

old_xml_expanded = re.search(r"        <!-- Expanded view -->.*?</LinearLayout>", xml_content, re.DOTALL)
if old_xml_expanded:
    xml_content = xml_content.replace(old_xml_expanded.group(0), "")

with open("app/src/main/res/layout/layout_floating_bubble.xml", "w") as f:
    f.write(xml_content)

print("Done")
