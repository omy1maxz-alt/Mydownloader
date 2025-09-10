package com.omymaxz.download

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BackupRestoreManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val gson = Gson()

    suspend fun createBackup(): String {
        val backupData = mutableMapOf<String, Any>()

        // Backup SharedPreferences
        val appDataPrefs = context.getSharedPreferences("AppData", Context.MODE_PRIVATE)
        backupData["AppData"] = appDataPrefs.all.mapValues { if (it.value is Set<*>) it.value.toList() else it.value }


        val adBlockerPrefs = context.getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        backupData["AdBlocker"] = adBlockerPrefs.all.mapValues { if (it.value is Set<*>) it.value.toList() else it.value }

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
            restoreSharedPreferences("AppData", backupData["AppData"] as Map<String, *>)
            restoreSharedPreferences("AdBlocker", backupData["AdBlocker"] as Map<String, *>)

            // Restore Room Database
            db.bookmarkDao().deleteAll()
            val bookmarks: List<Bookmark> = gson.fromJson(gson.toJson(backupData["bookmarks"]), object : TypeToken<List<Bookmark>>() {}.type)
            db.bookmarkDao().insertAll(*bookmarks.toTypedArray())

            db.userScriptDao().deleteAll()
            val userScripts: List<UserScript> = gson.fromJson(gson.toJson(backupData["userScripts"]), object : TypeToken<List<UserScript>>() {}.type)
            db.userScriptDao().insertAll(*userScripts.toTypedArray())
        }
    }

    private fun restoreSharedPreferences(prefName: String, data: Map<String, *>) {
        val sharedPrefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val editor = sharedPrefs.edit()
        editor.clear()
        for ((key, value) in data) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is List<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            }
        }
        editor.apply()
    }
}
