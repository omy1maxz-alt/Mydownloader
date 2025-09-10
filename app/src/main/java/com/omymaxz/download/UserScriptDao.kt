package com.omymaxz.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UserScriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: UserScript)

    @Update
    suspend fun update(script: UserScript)

    @Query("SELECT * FROM user_scripts WHERE isEnabled = 1")
    suspend fun getEnabledScripts(): List<UserScript>
    
    @Query("SELECT * FROM user_scripts ORDER BY name ASC")
    suspend fun getAllScripts(): List<UserScript>
    
    @Query("SELECT * FROM user_scripts WHERE id = :scriptId")
    suspend fun getScriptById(scriptId: String): UserScript?

    @Query("DELETE FROM user_scripts WHERE id = :scriptId")
    suspend fun deleteById(scriptId: String)
    
    // Add these for bulk operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scripts: List<UserScript>)
    
    @Query("DELETE FROM user_scripts")
    suspend fun deleteAll()
}