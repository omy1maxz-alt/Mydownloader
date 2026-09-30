import re

with open("./app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

target = """            if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, java.util.ArrayList(mediaFile.subtitleUrls))
            }"""

replacement = """            if (!mediaFile.subtitleUrls.isNullOrEmpty()) {
                putStringArrayListExtra(CustomPlayerActivity.EXTRA_SUBTITLE_URLS, java.util.ArrayList(mediaFile.subtitleUrls!!))
            }"""

if target in content:
    content = content.replace(target, replacement)
    with open("./app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
        f.write(content)
    print("Fixed compilation warning")
else:
    print("Could not find compilation warning target")
