with open("app/src/main/java/com/omymaxz/download/MainActivity.kt", "r") as f:
    content = f.read()

# Make sure WebChromeClient handles file chooser correctly.
# In patch_file_chooser.py we modified fileChooserLauncher and onShowFileChooser.
