package com.omymaxz.download

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "script_cache")
data class ScriptCache(
    @PrimaryKey val url: String,
    val content: String,
    val fetchedAt: Long = System.currentTimeMillis()
)
