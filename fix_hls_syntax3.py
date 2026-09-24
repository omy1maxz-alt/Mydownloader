import sys

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "r") as f:
    lines = f.readlines()

lines.insert(199, "    }\n")

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "w") as f:
    f.writelines(lines)

print("Done")
