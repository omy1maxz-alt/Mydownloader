import sys

with open("app/src/main/java/com/omymaxz/download/CustomPlayerActivity.kt", "r") as f:
    content = f.read()

# CustomPlayerActivity expects EXTRA_VIDEO_URL ("extra_video_url"). FloatingBubbleService was passing "video_url" back, and reading "video_url" from CustomPlayerActivity.
# So when clicking the bubble, CustomPlayerActivity gets reopened with NULL video URL, meaning it crashes or displays nothing.

# Let's fix FloatingBubbleService.kt first

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    bubble_content = f.read()

old_intent_send = """                            val intent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra("video_url", videoUrl)
                                putExtra("video_title", videoTitle)
                            }"""

new_intent_send = """                            val intent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
                                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, videoTitle)
                            }"""

bubble_content = bubble_content.replace(old_intent_send, new_intent_send)

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(bubble_content)

print("Fixed FloatingBubbleService Intent parameters.")
