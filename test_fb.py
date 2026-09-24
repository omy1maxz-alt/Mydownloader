import sys

# Ah! In CustomPlayerActivity.kt:
# `val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply { putExtra("video_url", videoUrl); putExtra("video_title", videoTitle) }`
# And in FloatingBubbleService:
# `val intent = Intent(this@FloatingBubbleService, CustomPlayerActivity::class.java).apply { putExtra("video_url", videoUrl); putExtra("video_title", videoTitle) }`

# When the bubble clicked, it sent `"video_url"` back to CustomPlayerActivity, but CustomPlayerActivity expects `EXTRA_VIDEO_URL` which equals `"extra_video_url"`!
# So CustomPlayerActivity received NULL and probably crashed or failed to load!
# And wait! The prompt says "The floating control/bubble that should appear from the Custom Player is currently missing or no longer working. Once restored, evolve that existing floating UI into a useful live Media Detector overlay."

# Wait, the prompt implies it doesn't even appear?
# "Whether the floating layout exists and is inflated... Whether the view is being immediately removed... Whether lifecycle code in CustomPlayerActivity stops/removes it"
