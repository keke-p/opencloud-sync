package io.github.ocmediasync.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sync_folders")
data class SyncFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MediaStore の RELATIVE_PATH 形式 (例: "Pictures/Screenshots/") */
    val relativePath: String,
    /** パーソナルスペース内のアップロード先 (例: "/Screenshots") */
    val remotePath: String,
    val enabled: Boolean = true,
    /** false なら画像のみアップロードする */
    @ColumnInfo(defaultValue = "1") val includeVideos: Boolean = true,
    /** 自動同期の対象とする DATE_ADDED (秒) の下限 (= 登録時刻)。それより前のファイルは「既存ファイル」扱い */
    val baselineDateAdded: Long,
    val createdAt: Long,
)

enum class UploadStatus {
    /** アップロード中 (中断された場合もこの状態のまま残り、次回再開する) */
    UPLOADING,
    DONE,
    /** リモートに同名・同サイズのファイルがあったためアップロードしなかった */
    SKIPPED,
    FAILED,
    ;

    val isCompleted: Boolean get() = this == DONE || this == SKIPPED
}

/**
 * ローカルファイルごとのアップロード記録。localKey (ボリューム + パス + ファイル名) で一意。
 * 完了後にファイルが編集されても再アップロードはしない。
 */
@Entity(
    tableName = "uploads",
    indices = [Index(value = ["localKey"], unique = true), Index("updatedAt")],
)
data class UploadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val localKey: String,
    val displayName: String,
    val size: Long,
    /** 秒 */
    val dateModified: Long,
    /** パーソナルスペース内のパス (例: "/Screenshots/sub/a.png") */
    val remotePath: String? = null,
    val status: UploadStatus,
    val attempts: Int = 0,
    val tusUrl: String? = null,
    val error: String? = null,
    val updatedAt: Long,
)
