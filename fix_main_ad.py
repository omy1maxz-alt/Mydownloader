import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

old_scan1 = """                        if (el.tagName === 'VIDEO' && el.currentTime > 0 && !el.paused) {
                                if (window.AndroidMediaState && window.AndroidMediaState.onActivePlayerFound) {
                                    window.AndroidMediaState.onActivePlayerFound(el.src, el.duration || 0);
                                }
                            }"""

new_scan1 = """                        if (el.tagName === 'VIDEO' && el.currentTime > 0 && !el.paused) {
                                // Ensure only videos with real progress and duration (or live) are tracked as the main active player
                                if (el.duration > 0 || el.duration === Infinity || el.readyState >= 2) {
                                    if (window.AndroidMediaState && window.AndroidMediaState.onActivePlayerFound) {
                                        window.AndroidMediaState.onActivePlayerFound(el.src, el.duration || 0);
                                    }
                                }
                            }"""

content = content.replace(old_scan1, new_scan1)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)

print("Done")
