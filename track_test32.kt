package com.omymaxz.download
import android.util.Log

fun analyzeStreamKeys() {
    // If the user watches on "Auto" (the default), `player?.currentTracks` might actually have MULTIPLE tracks selected within the same group for adaptive streaming!
    // Or, more accurately, ExoPlayer marks ALL tracks in the adaptive group as "selected" because it can switch between any of them.
    // If multiple tracks are selected, we pass multiple stream keys to the MediaItem.
    // Then HlsMediaSource exposes ALL of them.
    // Then Transformer's ExoPlayer uses adaptive selection again, and crashes!

    // We need to ensure that ONLY ONE video stream key is selected, specifically the one with the highest bitrate or resolution among the currently selected ones, or simply force a single track.
}
