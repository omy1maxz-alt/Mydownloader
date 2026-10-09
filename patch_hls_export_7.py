import re

with open("app/src/main/java/com/omymaxz/download/HlsExportService.kt", "r") as f:
    content = f.read()

# Let's inspect how muxToMp4FromCache processes the master playlist. Does it handle StreamKeys properly?
# If we look at the HlsExportService.kt file.
