import re

file_path = "app/src/main/java/com/omymaxz/download/MainActivity.kt"
with open(file_path, "r") as f:
    content = f.read()

# Remove duplicate definitions
content = re.sub(r"    private var lastYoutubeUrl: String\? = null\n    \n    private fun checkForYouTube\(url: String\) \{.*?\}", "", content, count=1, flags=re.DOTALL)

with open(file_path, "w") as f:
    f.write(content)
