import androidx.media3.transformer.*
import androidx.media3.exoplayer.source.MediaSource
import android.content.Context
import androidx.media3.common.util.Clock

// Let's go back to DownloadHelper!
// Wait, the crash happens when we pass `mediaItem` (reconstructed with StreamKeys) DIRECTLY to `muxToMp4`.
// In the current HlsExportService code:
/*
    val streamKeys = streamKeyStrings?.map {
        val parts = it.split(",")
        androidx.media3.common.StreamKey(parts[0].toInt(), parts[1].toInt())
    } ?: emptyList()

    val mediaItem = androidx.media3.common.MediaItem.Builder()
        .setUri(android.net.Uri.parse(videoUrl))
        .setMimeType(mimeType)
        .setStreamKeys(streamKeys)
        .build()

    muxToMp4(mediaItem, title)
*/

// BUT we just bypassed the DownloadHelper entirely for this case! And the cache doesn't know about Track Selection unless we actually apply it to the MediaItem.
// Did the streamKeys get applied correctly? Yes, setStreamKeys(streamKeys).
// BUT wait, does ExoAssetLoaderBaseRenderer support `StreamKeys` natively?
// Actually, ExoPlayer *does* filter out stream keys if configured. But wait... StreamKeys are for DASH/HLS to filter tracks at the **MediaSource** level!
// If StreamKeys filter out everything but the 480p track, then the HLS MediaSource will ONLY expose the 480p track.
// If it ONLY exposes the 480p track, there should be NO adaptive format changes.
// So why is there a format change?
