with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

import re

match = re.search(r'override fun onCreateWindow.*?return true\s*\}', content, re.DOTALL)
if match:
    print(match.group(0))
