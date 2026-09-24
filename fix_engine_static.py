import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

# Make MediaDetectionEngine accessible statically via a companion object so FloatingBubbleService can read the state without crashing.
old_engine = """class MediaDetectionEngine(private val context: Context) {"""
new_engine = """class MediaDetectionEngine(private val context: Context) {
    companion object {
        var instance: MediaDetectionEngine? = null
    }

    init {
        instance = this
    }
"""

content = content.replace(old_engine, new_engine)

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "w") as f:
    f.write(content)

print("Done")
