import re

with open('app/src/main/java/com/omymaxz/download/MainActivity.kt', 'r') as f:
    content = f.read()

target = """                        .setTitle("Video Action")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    Toast.makeText(activity, "Downloading Active Video...", Toast.LENGTH_SHORT).show()
                                    activity.downloadMediaFile(mediaFile)
                                }
                                1 -> {
                                    val intent = android.content.Intent(activity, CustomPlayerActivity::class.java).apply {"""

replacement = """                        .setTitle("Video Action")
                        .setItems(options) { _, which ->
                            val input = android.widget.EditText(activity)
                            input.setText(mediaFile.title.substringBeforeLast("."))
                            androidx.appcompat.app.AlertDialog.Builder(activity)
                                .setTitle("Rename File")
                                .setView(input)
                                .setPositiveButton("OK") { _, _ ->
                                    val newTitle = input.text.toString() + "." + mediaFile.title.substringAfterLast(".", "")
                                    val updatedMediaFile = mediaFile.copy(title = newTitle)
                                    when (which) {
                                        0 -> {
                                            Toast.makeText(activity, "Downloading Active Video...", Toast.LENGTH_SHORT).show()
                                            activity.downloadMediaFile(updatedMediaFile)
                                        }
                                        1 -> {
                                            val intent = android.content.Intent(activity, CustomPlayerActivity::class.java).apply {
                                                putExtra(CustomPlayerActivity.EXTRA_VIDEO_URL, url)
                                                putExtra(CustomPlayerActivity.EXTRA_VIDEO_TITLE, newTitle)

                                                val allSubtitleUrls = synchronized(activity.detectedMediaFiles) {
                                                    activity.detectedMediaFiles
                                                        .filter { it.category == MediaCategory.SUBTITLE || it.title.endsWith(".vtt") || it.title.endsWith(".srt") }
                                                        .map { it.url }
                                                        .toMutableList()
                                                }
                                                if (allSubtitleUrls.isNotEmpty()) {
                                                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, java.util.ArrayList(allSubtitleUrls))
                                                } else if (subtitleUrl.isNotEmpty()) {
                                                    putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, arrayListOf(subtitleUrl))
                                                }
                                            }
                                            activity.startActivity(intent)
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }"""

# The target is slightly different so let's do it manually. Let's see the context
