package com.omymaxz.download

data class YouTubeVideo(
    val title: String,
    val formats: List<YouTubeFormat>
)

data class YouTubeFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val width: Int,
    val height: Int,
    val contentLength: String?
)
