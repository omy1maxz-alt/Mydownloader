import sys

filename = "app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt"
with open(filename, "r") as f:
    content = f.read()

# Already var isPlaybackActive: Boolean = false and not private

with open(filename, "w") as f:
    f.write(content)
