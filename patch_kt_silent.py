import re
import sys

def patch_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Define the old block to search for
    search_block = """    fun handleMediaScanResult(resultJson: String) {
        try {
            val items = org.json.JSONArray(resultJson)
            if (items.length() > 0) {
                var addedCount = 0
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val url = item.getString("url")
                    if (detectedMediaList.none { it.url == url }) {
                        detectedMediaList.add(DetectedMedia(
                            url = url,
                            type = item.getString("type"),
                            title = "Detected Media ${detectedMediaList.size + 1} (${item.getString("type")})"
                        ))
                        addedCount++
                    }
                }

                if (detectedMediaList.isNotEmpty()) {
                    runOnUiThread {
                        if (addedCount > 0) {
                            Toast.makeText(this, "$addedCount new media item(s) found!", Toast.LENGTH_SHORT).show()
                            showMediaListDialog()
                        } else {
                            Toast.makeText(this, "No new media found since last scan. Showing existing list.", Toast.LENGTH_SHORT).show()
                            showMediaListDialog()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "No media detected. Try playing the video first.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error parsing media scan result", e)
            runOnUiThread {
                Toast.makeText(this, "Error scanning media.", Toast.LENGTH_SHORT).show()
            }
        }
    }"""

    replace_block = """    fun handleMediaScanResult(resultJson: String) {
        try {
            val items = org.json.JSONArray(resultJson)
            if (items.length() > 0) {
                var addedCount = 0
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val url = item.getString("url")
                    if (detectedMediaList.none { it.url == url }) {
                        detectedMediaList.add(DetectedMedia(
                            url = url,
                            type = item.getString("type"),
                            title = "Detected Media ${detectedMediaList.size + 1} (${item.getString("type")})"
                        ))
                        addedCount++
                    }
                }

                if (detectedMediaList.isNotEmpty()) {
                    runOnUiThread {
                        if (addedCount > 0) {
                            Toast.makeText(this, "$addedCount new media item(s) found!", Toast.LENGTH_SHORT).show()
                            showMediaListDialog()
                        } else {
                            showMediaListDialog()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error parsing media scan result", e)
            runOnUiThread {
                Toast.makeText(this, "Error scanning media.", Toast.LENGTH_SHORT).show()
            }
        }
    }"""

    if search_block in content:
        content = content.replace(search_block, replace_block)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print("Success")
    else:
        print("Search block not found")

if __name__ == '__main__':
    patch_file('app/src/main/java/com/omymaxz/download/MainActivity.kt')
