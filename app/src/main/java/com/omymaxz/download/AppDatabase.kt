package com.omymaxz.download

    import android.content.Context
    import androidx.room.Database
    import androidx.room.Room
    import androidx.room.RoomDatabase
    import androidx.room.TypeConverters // Import this
    import androidx.room.migration.Migration
    import androidx.sqlite.db.SupportSQLiteDatabase

    // Add @TypeConverters annotation here
    @Database(entities = [Bookmark::class, UserScript::class], version = 4, exportSchema = false)
    @TypeConverters(Converters::class) // <-- Add this line
    abstract class AppDatabase : RoomDatabase() {

        abstract fun bookmarkDao(): BookmarkDao
        abstract fun userScriptDao(): UserScriptDao

        companion object {
            @Volatile
            private var INSTANCE: AppDatabase? = null

            private val MIGRATION_3_4 = object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE user_scripts ADD COLUMN matches TEXT NOT NULL DEFAULT '[]'")
                    database.execSQL("ALTER TABLE user_scripts ADD COLUMN excludes TEXT NOT NULL DEFAULT '[]'")
                }
            }

            fun getDatabase(context: Context): AppDatabase {
                return INSTANCE ?: synchronized(this) {
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "app_database"
                    )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration() // Retain for unrelated older schema versions
                    .build()
                    INSTANCE = instance
                    instance
                }
            }
        }
    }