import sys

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "r") as f:
    content = f.read()

content = content.replace("    private fun saveToDownloads(    private fun saveToDownloads(", "    private fun saveToDownloads(")

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "w") as f:
    f.write(content)

print("Fixed syntax errors part 2")
