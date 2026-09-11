import re

file_path = "app/src/main/java/com/omymaxz/download/MainActivity.kt"
with open(file_path, "r") as f:
    content = f.read()

# Add checkForYouTube inside MainActivity exactly once
func_code = """
    private var lastYoutubeUrl: String? = null

    private fun checkForYouTube(url: String) {
        if (url == lastYoutubeUrl) return
        lastYoutubeUrl = url

        android.widget.Toast.makeText(this, "YouTube video detected. Extracting stream...", android.widget.Toast.LENGTH_SHORT).show()
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

if "private fun checkForYouTube(" not in content:
    content = content.replace("    private fun isMainVideoContent", func_code + "\n    private fun isMainVideoContent")

# Hook it into shouldOverrideUrlLoading or onPageStarted
search_block = """                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isPageLoading = true"""

replace_block = """                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isPageLoading = true
                    if (url != null && (url.contains("youtube.com/watch") || url.contains("youtu.be/"))) {
                        checkForYouTube(url)
                    }"""

if search_block in content:
    content = content.replace(search_block, replace_block)
else:
    # it might be Bitmap instead of android.graphics.Bitmap depending on imports
    search_block2 = """                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // Auto-clear detected media on new page load"""
    replace_block2 = """                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url != null && (url.contains("youtube.com/watch") || url.contains("youtu.be/"))) {
                        checkForYouTube(url)
                    }
                    // Auto-clear detected media on new page load"""
    if search_block2 in content:
        content = content.replace(search_block2, replace_block2)

with open(file_path, "w") as f:
    f.write(content)
