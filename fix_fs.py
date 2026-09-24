import sys

with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "r") as f:
    content = f.read()

import re
match = re.search(r"            val bubbleContainer.*?            val expandedDetails.*?\n", content, re.DOTALL)
if match:
    content = content.replace(match.group(0), "")

match_touch = re.search(r"            bubbleContainer.setOnTouchListener", content, re.DOTALL)
if match_touch:
    content = content.replace(match_touch.group(0), "            bubbleIcon.setOnTouchListener")


with open("app/src/main/java/com/omymaxz/download/FloatingBubbleService.kt", "w") as f:
    f.write(content)
