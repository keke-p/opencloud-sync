package io.github.ocmediasync.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SyncFolder::class, UploadRecord::class],
    version = 2,
    autoMigrations = [
        // 2: sync_folders.includeVideos を追加
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): SyncFolderDao
    abstract fun uploadDao(): UploadDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ocmediasync.db")
                    .build()
                    .also { instance = it }
            }
    }
}
