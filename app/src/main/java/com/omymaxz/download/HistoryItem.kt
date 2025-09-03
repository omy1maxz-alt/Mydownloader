package com.omymaxz.download

import java.util.Date

data class HistoryItem(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val hostname: String
        get() = try {
            android.net.Uri.parse(url).host ?: ""
        } catch (e: Exception) {
            ""
        }
    
    val date: String
        get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(Date(timestamp))
}