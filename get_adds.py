import subprocess
result = subprocess.run(["grep", "-in", "-B", "5", "-A", "5", "detectedMediaFiles.add", "app/src/main/java/com/omymaxz/download/MainActivity.kt"], capture_output=True, text=True)
print(result.stdout)
