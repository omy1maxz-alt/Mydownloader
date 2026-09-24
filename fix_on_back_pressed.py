import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

old_back = """    override fun onBackPressed() {
        } else if (fullscreenView != null) {"""

new_back = """    override fun onBackPressed() {
        if (fullscreenView != null) {"""

content = content.replace(old_back, new_back)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
