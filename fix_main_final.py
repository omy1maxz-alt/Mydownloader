import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

content = content.replace("val cand = activity.mediaEngine.getCandidate(finalUrl)", "val cand = activity.mediaEngine.getCandidate(bestPlayable.url)")

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Fixed syntax")
