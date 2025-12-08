package com.omymaxz.download

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

class HlsDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.exo_download_notification_channel_name,
    /* channelDescriptionResourceId= */ 0
) {

    override fun getDownloadManager(): DownloadManager {
        // This will be called only once on service creation.
        return HlsDownloadHelper.getDownloadManager(this)
    }

    override fun getScheduler(): Scheduler? {
        return if (Util.SDK_INT >= 21) PlatformScheduler(this, JOB_ID) else null
    }

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        return HlsDownloadHelper.getDownloadNotificationHelper(this)
            .buildProgressNotification(
                this,
                R.drawable.ic_download, // Ensure you have a drawable resource for this
                /* contentIntent= */ null,
                /* message= */ null,
                downloads,
                notMetRequirements
            )
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val CHANNEL_ID = "download_channel"
    }
}
