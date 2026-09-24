import sys

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    lines = f.readlines()

new_lines = lines[:1062] + lines[1065:]

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.writelines(new_lines)

print("Done")
