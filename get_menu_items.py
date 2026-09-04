import re

with open("app/src/main/res/menu/main_menu.xml", "r") as f:
    text = f.read()

items = re.findall(r'<item.*?android:id="@+id/(.*?)".*?android:title="(.*?)".*?/>', text, re.DOTALL)
print(items)
