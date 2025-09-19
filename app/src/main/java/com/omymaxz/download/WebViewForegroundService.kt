package com.omymaxz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WebViewForegroundService : Service() {

    private val binder = WebViewBinder()
    private var currentTabId: String? = null

    companion object {
        const val ACTION_START = "com.omymaxz.download.ACTION_START"
        const val ACTION_STOP = "com.omymaxz.download.ACTION_STOP"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "WebViewForegroundServiceChannel"
    }

    inner class WebViewBinder : Binder() {
        fun getService(): WebViewForegroundService = this@WebViewForegroundService
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentTabId = intent.getStringExtra("TAB_ID")
                if (currentTabId != null) {
                    val notification = createNotification()
                    startForeground(NOTIFICATION_ID, notification)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // The webview is managed by the tab lifecycle now, so we don't release it here.
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Web View Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val url = currentTabId?.let { tabId ->
            WebViewManager.getWebView(tabId)?.url
        } ?: "a page"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Browser is running in background")
            .setContentText("Loading $url")
            .setSmallIcon(R.drawable.ic_public)
            .build()
    }
}
