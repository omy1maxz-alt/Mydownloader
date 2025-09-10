package com.omymaxz.download

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupRestoreManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val gson = Gson()

    suspend fun createBackup(): String {
        val backupData = mutableMapOf<String, Any>()

        // Backup SharedPreferences
        val appDataPrefs = context.getSharedPreferences("AppData", Context.MODE_PRIVATE)
        backupData["AppData"] = appDataPrefs.all.mapValues { entry ->
            val value = entry.value
            if (value is Set<*>) value.toList() else value
        }

        val adBlockerPrefs = context.getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        backupData["AdBlocker"] = adBlockerPrefs.all.mapValues { entry ->
            val value = entry.value
            if (value is Set<*>) value.toList() else value
        }

        // Backup Room Database
        val bookmarks = db.bookmarkDao().getAll()
        backupData["bookmarks"] = bookmarks

        val userScripts = db.userScriptDao().getAllScripts()
        backupData["userScripts"] = userScripts

        return gson.toJson(backupData)
    }

    suspend fun restoreBackup(backupJson: String) {
        withContext(Dispatchers.IO) {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val backupData: Map<String, Any> = gson.fromJson(backupJson, type)

            // Restore SharedPreferences
            @Suppress("UNCHECKED_CAST")
            restoreSharedPreferences("AppData", backupData["AppData"] as Map<String, *>)
            @Suppress("UNCHECKED_CAST")
            restoreSharedPreferences("AdBlocker", backupData["AdBlocker"] as Map<String, *>)

            // Restore Room Database - Bookmarks
            val bookmarks: List<Bookmark> = gson.fromJson(
                gson.toJson(backupData["bookmarks"]), 
                object : TypeToken<List<Bookmark>>() {}.type
            )
            // Clear existing bookmarks and insert new ones
            // Since there's no bulk delete/insert, we'll need to handle this differently
            for (bookmark in bookmarks) {
                db.bookmarkDao().insert(bookmark)
            }

            // Restore Room Database - User Scripts
            val userScripts: List<UserScript> = gson.fromJson(
                gson.toJson(backupData["userScripts"]), 
                object : TypeToken<List<UserScript>>() {}.type
            )
            // Clear existing scripts and insert new ones
            // Since there's no bulk delete, you might want to add a deleteAll method to your DAO
            for (script in userScripts) {
                db.userScriptDao().insert(script)
            }
        }
    }

    private fun restoreSharedPreferences(prefName: String, data: Map<String, *>) {
        val sharedPrefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()
        editor.clear()
        for ((key, value) in data) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is List<*> -> {
                    val stringSet = value.map { it.toString() }.toSet()
                    editor.putStringSet(key, stringSet)
                }
            }
        }
        editor.apply()
    }
}