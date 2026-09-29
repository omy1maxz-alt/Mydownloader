with open("DEV_JOURNAL.md", "r") as f:
    content = f.read()

content = content.replace("<<<<<<< HEAD\n", "").replace("=======\n", "").replace(">>>>>>> fix-custom-player-theme\n", "")

with open("DEV_JOURNAL.md", "w") as f:
    f.write(content)
