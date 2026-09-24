import sys

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "r") as f:
    lines = f.readlines()

# Delete lines 279 through 310
new_lines = lines[:278] + lines[310:]

with open("app/src/main/java/com/omymaxz/download/YouTubeDownloadService.kt", "w") as f:
    f.writelines(new_lines)

print("Fixed syntax errors part 1")
