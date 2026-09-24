import sys

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "r") as f:
    content = f.read()

if "import androidx.media3.datasource.ResolvingDataSource" not in content:
    content = content.replace("import androidx.media3.datasource.DataSource", "import androidx.media3.datasource.DataSource\nimport androidx.media3.datasource.ResolvingDataSource\nimport android.util.Log")

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "w") as f:
    f.write(content)

print("Done")
