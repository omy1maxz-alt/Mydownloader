import sys

with open("app/src/main/java/com/omymaxz/download/CustomPlayerActivity.kt", "r") as f:
    content = f.read()

old_custom_send = """        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("video_url", videoUrl)
            putExtra("video_title", videoTitle)
            putExtra("current_position", player?.currentPosition ?: 0L)
        }"""

new_custom_send = """        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(EXTRA_VIDEO_URL, videoUrl)
            putExtra(EXTRA_VIDEO_TITLE, videoTitle)
            putExtra("current_position", player?.currentPosition ?: 0L)
        }"""

content = content.replace(old_custom_send, new_custom_send)

with open("app/src/main/java/com/omymaxz/download/CustomPlayerActivity.kt", "w") as f:
    f.write(content)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    bubble_content = f.read()

old_get_intent = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            videoUrl = intent.getStringExtra("video_url")
            videoTitle = intent.getStringExtra("video_title")
            currentPosition = intent.getLongExtra("current_position", 0L)
        }
        return START_NOT_STICKY
    }"""

new_get_intent = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            videoUrl = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_URL)
            videoTitle = intent.getStringExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE)
            currentPosition = intent.getLongExtra("current_position", 0L)
        }
        return START_NOT_STICKY
    }"""

bubble_content = bubble_content.replace(old_get_intent, new_get_intent)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(bubble_content)

print("Fixed CustomPlayerActivity Intent parameters.")
