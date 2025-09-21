package com.omymaxz.download

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.omymaxz.download.MediaState

class AndroidMediaBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun updateMediaState(jsonState: String) {
        activity.runOnUiThread {
            try {
                val state = Gson().fromJson(jsonState, MediaState::class.java)
                val currentTab = if (activity.currentTabIndex in activity.tabs.indices) activity.tabs[activity.currentTabIndex] else return@runOnUiThread

                val mediaIsPlaying = state.isPlaying && state.duration > 0

                currentTab.hasActiveMedia = mediaIsPlaying
                currentTab.isMediaPaused = !state.isPlaying
                currentTab.mediaPosition = state.position
                currentTab.mediaUrl = state.source
                currentTab.mediaTitle = state.title

                activity.isMediaPlaying = mediaIsPlaying
                activity.currentVideoUrl = if(mediaIsPlaying) state.source else null

            } catch (e: Exception) {
                android.util.Log.e("MediaStateInterface", "Error parsing media state from JS: ${e.message}")
            }
        }
    }
}
