package com.omymaxz.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRestoreManagerTest {

    private lateinit var backupRestoreManager: BackupRestoreManager
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(context)
        backupRestoreManager = BackupRestoreManager(context)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `backup and restore should work correctly`() = runBlocking {
        // Given: some data in the database and shared preferences
        val appDataPrefs = context.getSharedPreferences("AppData", Context.MODE_PRIVATE)
        appDataPrefs.edit().putString("test_key", "test_value").apply()

        val adBlockerPrefs = context.getSharedPreferences("AdBlocker", Context.MODE_PRIVATE)
        adBlockerPrefs.edit().putBoolean("ad_blocker_enabled", true).apply()

        val bookmark = Bookmark(title = "Test Bookmark", url = "https://example.com")
        db.bookmarkDao().insert(bookmark)

        val userScript = UserScript(name = "Test Script", script = "alert('hello')", grants = emptyList(), targetUrl = ".*")
        db.userScriptDao().insert(userScript)

        // When: we create a backup
        val backupJson = backupRestoreManager.createBackup()

        // Then: the backup json should not be empty
        assert(backupJson.isNotEmpty())

        // When: we clear the data
        appDataPrefs.edit().clear().apply()
        adBlockerPrefs.edit().clear().apply()
        db.bookmarkDao().getAll().forEach { db.bookmarkDao().delete(it) }
        db.userScriptDao().getAllScripts().forEach { db.userScriptDao().deleteById(it.id) }

        // And: we restore the backup
        backupRestoreManager.restoreBackup(backupJson)

        // Then: the data should be restored
        assertEquals("test_value", appDataPrefs.getString("test_key", null))
        assertEquals(true, adBlockerPrefs.getBoolean("ad_blocker_enabled", false))

        val restoredBookmarks = db.bookmarkDao().getAll()
        assertEquals(1, restoredBookmarks.size)
        assertEquals("Test Bookmark", restoredBookmarks[0].title)

        val restoredUserScripts = db.userScriptDao().getAllScripts()
        assertEquals(1, restoredUserScripts.size)
        assertEquals("Test Script", restoredUserScripts[0].name)
    }
}
