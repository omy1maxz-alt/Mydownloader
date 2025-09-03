    package com.omymaxz.download
    
    import android.content.Context
    import androidx.room.Database
    import androidx.room.Room
    import androidx.room.RoomDatabase
    import androidx.room.migration.Migration
    import androidx.sqlite.db.SupportSQLiteDatabase
    
    @Database(entities = [Bookmark::class, UserScript::class], version = 3, exportSchema = false)
    abstract class AppDatabase : RoomDatabase() {
    
        abstract fun bookmarkDao(): BookmarkDao
        abstract fun userScriptDao(): UserScriptDao
    
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
                    .fallbackToDestructiveMigration()
                    .build()
                    INSTANCE = instance
                    instance
                }
            }
        }
    }