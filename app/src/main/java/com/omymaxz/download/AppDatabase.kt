package com.omymaxz.download

    import android.content.Context
    import androidx.room.Database
    import androidx.room.Room
    import androidx.room.RoomDatabase
    import androidx.room.TypeConverters // Import this
    import androidx.room.migration.Migration
    import androidx.sqlite.db.SupportSQLiteDatabase

    // Add @TypeConverters annotation here
@Database(entities = [Bookmark::class, UserScript::class, ScriptCache::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
    abstract class AppDatabase : RoomDatabase() {

        abstract fun bookmarkDao(): BookmarkDao
        abstract fun userScriptDao(): UserScriptDao
        abstract fun scriptCacheDao(): ScriptCacheDao

        companion object {
            @Volatile
            private var INSTANCE: AppDatabase? = null

            fun getDatabase(context: Context): AppDatabase {
                return INSTANCE ?: synchronized(this) {
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "app_database"
                    )
                    .fallbackToDestructiveMigration() // Consider removing this in production
                    .build()
                    INSTANCE = instance
                    instance
                }
            }
        }
    }