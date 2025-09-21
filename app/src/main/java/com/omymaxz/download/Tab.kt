package com.omymaxz.download

import android.graphics.Bitmap
import android.os.Bundle
import java.util.UUID

data class Tab(
    val id: String = UUID.randomUUID().toString(),
    var url: String? = null,
    var title: String = "New Tab",
    var state: Bundle? = null,
    @Transient var favicon: Bitmap? = null,
    var scrollPosition: Int = 0,
    var lastAccessTime: Long = System.currentTimeMillis(),

    // Enhanced media state preservation
    var hasActiveMedia: Boolean = false,
    var mediaUrl: String? = null,
    var mediaPosition: Float = 0f,
    var isMediaPaused: Boolean = false,
    var mediaTitle: String? = null,
    var mediaType: String = "video", // video, audio

    // Page loading state for background continuation
    var isPageLoading: Boolean = false,
    var pageLoadProgress: Int = 0
)