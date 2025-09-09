package com.omymaxz.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScriptCacheDao {
    @Query("SELECT * FROM script_cache WHERE url = :url")
    suspend fun getScriptByUrl(url: String): ScriptCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: ScriptCache)
}
