with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt") as f:
    lines = f.readlines()
for i, l in enumerate(lines):
    if "DefaultHttpDataSource.Factory" in l:
        print(i+1, l)
    if "DefaultDataSource.Factory" in l:
        print(i+1, l)
