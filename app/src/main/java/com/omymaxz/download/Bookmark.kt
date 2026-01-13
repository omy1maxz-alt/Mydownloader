package com.omymaxz.download

import android.net.Uri
import androidx.room.Entity
import android.net.Uri
import androidx.room.Ignore
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val url: String
) {
    @Ignore
    val host: String? = try {
        Uri.parse(url).host
    } catch (e: Exception) {
        null
    }
}
    @Ignore
    val host: String? = try {
        Uri.parse(url).host
    } catch (e: Exception) {
        null
    }
}
