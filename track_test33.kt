package com.omymaxz.download
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks

fun extractSingleKeys(tracks: Tracks?): List<String> {
    val streamKeyStrings = ArrayList<String>()
    if (tracks != null) {
        tracks.groups.forEachIndexed { groupIndex, group ->
            var bestVideoIndex = -1
            var bestVideoBitrate = -1
            var hasVideo = false

            // Collect audio/text tracks normally, but for video tracks, only keep the highest selected bitrate
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val format = group.getTrackFormat(i)
                    if (format.sampleMimeType?.startsWith("video/") == true) {
                        hasVideo = true
                        if (format.bitrate > bestVideoBitrate) {
                            bestVideoBitrate = format.bitrate
                            bestVideoIndex = i
                        }
                    } else {
                        streamKeyStrings.add("$groupIndex,$i")
                    }
                }
            }
            if (hasVideo && bestVideoIndex != -1) {
                streamKeyStrings.add("$groupIndex,$bestVideoIndex")
            }
        }
    }
    return streamKeyStrings
}
