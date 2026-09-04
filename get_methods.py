import re
import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

methods = re.findall(r'fun\s+(\w+)\s*\(', content)
print("\n".join(set(methods)))
