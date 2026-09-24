import sys

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "r") as f:
    lines = f.readlines()

new_lines = lines[:200] + lines[210:]

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "w") as f:
    f.writelines(new_lines)

print("Done")
