import sys
filename = "app/src/main/java/com/omymaxz/download/MainActivity.kt"
with open(filename, "r") as f:
    content = f.read()

import re
matches = re.finditer(r"val mediaEngine = MediaDetectionEngine\(this\)", content)
for m in matches:
    print(m.start())
