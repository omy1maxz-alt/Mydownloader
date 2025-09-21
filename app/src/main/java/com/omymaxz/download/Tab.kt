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
    var hasActiveMedia: Boolean = false,
    var isMediaPaused: Boolean = true,
    var mediaPosition: Double = 0.0,
    var mediaUrl: String? = null,
    var mediaTitle: String? = null,
    // Add content caching
    var cachedHtml: String? = null,
    var cacheTimestamp: Long = 0
)