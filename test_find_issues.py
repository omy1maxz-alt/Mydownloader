import sys

with open("app/src/main/java/com/omymaxz/download/MediaDetectionEngine.kt", "r") as f:
    content = f.read()

print("getBestCandidate score logic check:")
import re
match = re.search(r"fun getBestCandidate.*?return", content, re.DOTALL)
if match:
    print(match.group(0))

print("\n\nfindParentManifestForSegment logic check:")
match2 = re.search(r"fun findParentManifestForSegment.*?\}", content, re.DOTALL)
if match2:
    print(match2.group(0))
