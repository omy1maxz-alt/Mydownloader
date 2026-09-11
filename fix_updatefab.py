import re

file_path = "app/src/main/java/com/omymaxz/download/MainActivity.kt"
with open(file_path, "r") as f:
    content = f.read()

# I accidentally placed `checkForYouTube` inside `updateFabVisibility()` because I matched the `private fun updateFabVisibility()` header.
# Let's restore the original `updateFabVisibility()` header and move the `checkForYouTube` function to the bottom of the file (or outside the class).

search_block = """    private var lastYoutubeUrl: String? = null

    private fun checkForYouTube(url: String?) {
        if (url == null || url == lastYoutubeUrl) return
        lastYoutubeUrl = url

        runOnUiThread {
            updateFabVisibility()
            android.widget.Toast.makeText(this, "YouTube video detected. Extracting stream...", android.widget.Toast.LENGTH_SHORT).show()
        }
        androidx.lifecycle.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mediaFile = YoutubeExtractorHelper.extractMedia(url)
            if (mediaFile != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val existsAlready = synchronized(detectedMediaFiles) {
                        detectedMediaFiles.any { it.url == mediaFile.url }
                    }
                    if (!existsAlready) {
                        synchronized(detectedMediaFiles) {
                            detectedMediaFiles.add(0, mediaFile)
                        }
                        updateFabVisibility()
                        currentMediaListAdapter?.notifyDataSetChanged()
                        android.widget.Toast.makeText(this@MainActivity, "YouTube stream ready! Tap the floating button to play/download.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                 kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                     android.widget.Toast.makeText(this@MainActivity, "Failed to extract YouTube stream.", android.widget.Toast.LENGTH_SHORT).show()
                 }
            }
        }
    }
"""

if search_block in content:
    content = content.replace(search_block, "")

    # insert it cleanly before the last bracket
    content = content[:-2] + search_block + "\n}\n"

with open(file_path, "w") as f:
    f.write(content)
