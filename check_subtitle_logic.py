with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

import re
matches = re.findall(r'(@JavascriptInterface\s*fun onDownloadActiveMedia.*?\})', content, re.DOTALL)
for match in matches:
    print(match)
