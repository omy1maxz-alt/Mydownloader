import re

with open("app/src/main/java/com/omymaxz/download/ProgressiveAudioDownloadService.kt", "r") as f:
    content = f.read()

# Add foreground service imports and implementation
imports_to_add = """import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.webkit.CookieManager
"""

if "import android.app.NotificationChannel" not in content:
    content = content.replace("import android.os.IBinder", imports_to_add + "import android.os.IBinder")

# Add NOTIFICATION_ID
if "const val NOTIFICATION_ID = 4001" not in content:
    content = content.replace("private const val TAG = \"ProgressiveAudioDownloadService\"", "private const val TAG = \"ProgressiveAudioDownloadService\"\n        const val NOTIFICATION_ID = 4001")

notification_builder = """
    private fun createNotification(title: String): android.app.Notification {
        val channelId = "progressive_audio_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Audio Downloads", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Downloading Audio")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }
"""

if "private fun createNotification" not in content:
    content = content.replace("override fun onBind", notification_builder + "\n    override fun onBind")

start_command = """        val cookie = intent.getStringExtra(EXTRA_COOKIE)

        startForeground(NOTIFICATION_ID, createNotification(title))

        val activeCookie = if (cookie.isNullOrEmpty()) {
            CookieManager.getInstance().getCookie(url) ?: ""
        } else {
            cookie
        }
"""

content = content.replace("""        val cookie = intent.getStringExtra(EXTRA_COOKIE)""", start_command)

# Also update the HTTP connection to use `activeCookie` instead of `cookie`
content = content.replace("""                if (!cookie.isNullOrEmpty()) {
                    setRequestProperty("Cookie", cookie)
                }""", """                if (activeCookie.isNotEmpty()) {
                    setRequestProperty("Cookie", activeCookie)
                }""")
# For logging
content = content.replace("cookiePresent=${!cookie.isNullOrEmpty()}", "cookiePresent=${activeCookie.isNotEmpty()}")

with open("app/src/main/java/com/omymaxz/download/ProgressiveAudioDownloadService.kt", "w") as f:
    f.write(content)
