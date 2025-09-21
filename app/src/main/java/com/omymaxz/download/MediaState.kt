package com.omymaxz.download

data class MediaState(
    val isPlaying: Boolean,
    val title: String,
    val position: Float,
    val duration: Float,
    val source: String
)
