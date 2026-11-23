package io.github.ocmediasync.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncFolderDao {
    @Query("SELECT * FROM sync_folders ORDER BY relativePath")
    fun observeAll(): Flow<List<SyncFolder>>

    @Query("SELECT * FROM sync_folders ORDER BY relativePath")
    suspend fun getAll(): List<SyncFolder>

    @Query("SELECT * FROM sync_folders WHERE id = :id")
    suspend fun get(id: Long): SyncFolder?

    @Insert
    suspend fun insert(folder: SyncFolder): Long

    @Update
    suspend fun update(folder: SyncFolder)

    @Delete
    suspend fun delete(folder: SyncFolder)
}

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads WHERE localKey = :localKey")
    suspend fun get(localKey: String): UploadRecord?

    /** 全記録の状態。アップロード候補の絞り込みに使う */
    @Query("SELECT localKey, status, attempts FROM uploads")
    suspend fun getStates(): List<UploadState>

    @Upsert
    suspend fun upsert(record: UploadRecord): Long

    @Query("SELECT * FROM uploads ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<UploadRecord>>

    @Query("SELECT COUNT(*) FROM uploads WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    /** 失敗したものを自動同期で再試行できるよう試行回数をリセットする */
    @Query("UPDATE uploads SET attempts = 0 WHERE status = 'FAILED'")
    suspend fun resetFailedAttempts()
}

data class UploadState(val localKey: String, val status: UploadStatus, val attempts: Int)
