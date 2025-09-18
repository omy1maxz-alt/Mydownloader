package com.omymaxz.download

import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MediaDetectionService : NotificationListenerService() {

    private val TAG = "MediaDetectionService"

    companion object {
        internal const val WEIBO_PACKAGE_NAME = "com.sina.weibo"
        const val MEDIA_DETECTED_ACTION = "com.omymaxz.download.MEDIA_DETECTED"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null && sbn.packageName == WEIBO_PACKAGE_NAME) {
            val notification = sbn.notification
            if (notification != null) {
                @Suppress("DEPRECATION")
                val token = notification.extras.getParcelable<MediaSession.Token>("android.media.session")
                if (token != null) {
                    val mediaController = MediaController(applicationContext, token)
                    val metadata = mediaController.metadata
                    if (metadata != null) {
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        Log.d(TAG, "Weibo Media Metadata: Title=$title, Artist=$artist, Album=$album")

                        val intent = Intent(MEDIA_DETECTED_ACTION)
                        intent.putExtra("title", title)
                        intent.putExtra("artist", artist)
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    } else {
                        val title = notification.extras.getString("android.title")
                        val text = notification.extras.getString("android.text")
                        Log.d(TAG, "Weibo media notification (fallback): Title = $title, Text = $text")
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            Log.d(TAG, "Notification Removed from ${sbn.packageName}")
        }
    }
}
