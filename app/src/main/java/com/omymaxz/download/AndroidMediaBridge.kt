package com.omymaxz.download

import android.webkit.JavascriptInterface
import com.google.gson.Gson

class AndroidMediaBridge(private val mainActivity: MainActivity) {

    @JavascriptInterface
    fun updateMediaState(mediaStateJson: String) {
        try {
            val gson = Gson()
            val mediaState = gson.fromJson(mediaStateJson, MediaState::class.java)

            mainActivity.runOnUiThread {
                // Update the current tab's media state
                if (mainActivity.currentTabIndex in mainActivity.tabs.indices) {
                    val currentTab = mainActivity.tabs[mainActivity.currentTabIndex]
                    currentTab.hasActiveMedia = mediaState.isPlaying || (!mediaState.isPlaying && mediaState.duration > 0)
                    currentTab.isMediaPaused = !mediaState.isPlaying && mediaState.duration > 0
                    currentTab.mediaPosition = mediaState.position
                    currentTab.mediaTitle = mediaState.title
                    currentTab.mediaUrl = mediaState.source

                    // Update global media playing state
                    mainActivity.isMediaPlaying = mediaState.isPlaying
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AndroidMediaBridge", "Error updating media state: ${e.message}")
        }
    }
}
