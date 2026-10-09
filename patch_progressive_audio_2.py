with open("app/src/main/java/com/omymaxz/download/ProgressiveAudioDownloadService.kt", "r") as f:
    content = f.read()

# I noticed downloadAudio is called with `cookie` instead of `activeCookie`
content = content.replace("downloadAudio(url, title, userAgent, referer, cookie)", "downloadAudio(url, title, userAgent, referer, activeCookie)")

# Ensure we call stopForeground when done
content = content.replace("            } catch (e: Exception) {", """            } catch (e: Exception) {
            } finally {
                stopForeground(true)
                stopSelf()""")

with open("app/src/main/java/com/omymaxz/download/ProgressiveAudioDownloadService.kt", "w") as f:
    f.write(content)
