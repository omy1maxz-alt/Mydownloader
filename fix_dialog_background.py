import os

files_to_fix = [
    'app/src/main/res/layout/dialog_tabs.xml',
    'app/src/main/res/layout/dialog_media_list.xml'
]

for file in files_to_fix:
    if os.path.exists(file):
        with open(file, 'r') as f:
            content = f.read()

        # Remove android:background="?android:attr/windowBackground" from the root LinearLayout
        content = content.replace('android:background="?android:attr/windowBackground"', '')

        with open(file, 'w') as f:
            f.write(content)
        print(f"Fixed background in {file}")
