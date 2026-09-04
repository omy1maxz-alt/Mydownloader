import re

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

wire_code = """    private fun setupToolbarNavButtons() {
        binding.overflowMenuButton.setOnClickListener { showCustomOverflowMenu(it) }"""

content = re.sub(r'    private fun setupToolbarNavButtons\(\) \{', wire_code, content)

with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "w") as f:
    f.write(content)
