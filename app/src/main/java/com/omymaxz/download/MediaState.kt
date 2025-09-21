package com.omymaxz.download

data class MediaState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val source: String = ""
)
