package io.github.ocmediasync.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.ocmediasync.sync.RemotePaths

data class MediaItem(
    val uri: Uri,
    val volume: String,
    /** 例: "Pictures/Screenshots/" */
    val relativePath: String,
    val displayName: String,
    val size: Long,
    /** 秒 */
    val dateAdded: Long,
    /** 秒 */
    val dateModified: Long,
) {
    /** アップロード済み判定に使う一意キー */
    val localKey: String get() = "$volume:$relativePath$displayName"
}

/** MediaStore 経由で画像・動画を検索する。サブディレクトリも RELATIVE_PATH の前方一致で拾う。 */
class MediaRepository(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * [folderRelativePath] 配下 (サブディレクトリ含む) で dateAdded >= [minDateAdded] の画像
     * (と [includeVideos] なら動画)
     */
    fun queryFolder(folderRelativePath: String, minDateAdded: Long, includeVideos: Boolean): List<MediaItem> {
        val folder = RemotePaths.normalizeRelativePath(folderRelativePath)
        val selection = buildString {
            append(if (includeVideos) MEDIA_TYPE_SELECTION else IMAGE_ONLY_SELECTION)
            append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
            append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'")
            append(" AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?")
        }
        val args = (if (includeVideos) MEDIA_TYPE_ARGS else IMAGE_ONLY_ARGS) + arrayOf(RemotePaths.escapeLike(folder) + "%", minDateAdded.toString())
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val items = ArrayList<MediaItem>()
        resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(4) ?: continue
                // .trashed-xxx / .pending-xxx などの隠しファイルは対象外
                if (name.startsWith(".")) continue
                val volume = c.getString(2) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val isVideo = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(volume)
                } else {
                    MediaStore.Images.Media.getContentUri(volume)
                }
                items += MediaItem(
                    uri = ContentUris.withAppendedId(base, c.getLong(0)),
                    volume = volume,
                    relativePath = RemotePaths.normalizeRelativePath(c.getString(3) ?: ""),
                    displayName = name,
                    size = c.getLong(5),
                    dateAdded = c.getLong(6),
                    dateModified = c.getLong(7),
                )
            }
        }
        return items
    }

    /** 読み出し用 URI。位置情報の権限があれば EXIF の位置情報を削らないオリジナルを読む。 */
    fun readableUri(item: MediaItem): Uri =
        if (hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            MediaStore.setRequireOriginal(item.uri)
        } else {
            item.uri
        }

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 画像・動画の全件読み取り権限があるか (Android 14 の「一部のみ許可」は false) */
    fun hasFullMediaAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    companion object {
        private val MEDIA_TYPE_SELECTION =
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)"
        private val MEDIA_TYPE_ARGS = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        private val IMAGE_ONLY_SELECTION = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        private val IMAGE_ONLY_ARGS = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())

        /** 実行時に要求する権限 */
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()
    }
}
