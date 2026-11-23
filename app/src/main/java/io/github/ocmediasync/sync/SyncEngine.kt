package io.github.ocmediasync.sync

import android.content.Context
import android.net.Uri
import io.github.ocmediasync.AppSettings
import io.github.ocmediasync.account.AccountName
import io.github.ocmediasync.account.AuthRequiredException
import io.github.ocmediasync.account.OpenCloudAccounts
import io.github.ocmediasync.data.AppDatabase
import io.github.ocmediasync.data.SyncFolder
import io.github.ocmediasync.data.UploadRecord
import io.github.ocmediasync.data.UploadStatus
import io.github.ocmediasync.media.MediaItem
import io.github.ocmediasync.media.MediaRepository
import io.github.ocmediasync.remote.ContentUriRequestBody
import io.github.ocmediasync.remote.LocalFileException
import io.github.ocmediasync.remote.OpenCloudApi
import io.github.ocmediasync.remote.RemoteHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

enum class SyncMode {
    /** 自動同期 (登録時刻以降に追加されたファイル。Wi-Fi/充電の条件付き) */
    AUTO,

    /** 即時同期 (対象は AUTO と同じ。条件を無視し、失敗の試行回数上限も無視) */
    MANUAL,

    /** 指定フォルダの既存ファイルを含めてすべてアップロード */
    EXISTING,
}

data class SyncProgress(
    val done: Int,
    val total: Int,
    val currentName: String?,
    val bytes: Long = 0,
    val bytesTotal: Long = 0,
)

data class SyncSummary(val uploaded: Int, val skipped: Int, val failed: Int) {
    val message: String
        get() = if (uploaded + skipped + failed == 0) {
            "新しいファイルはありません"
        } else {
            "アップロード ${uploaded}件 / 既存のため省略 ${skipped}件 / 失敗 ${failed}件"
        }
}

/** 権限不足など、ユーザーが設定を直すまで同期できない状態 */
class SyncBlockedException(message: String) : Exception(message)

/**
 * 同期処理の本体。
 *
 * 通信エラー (RemoteHttpException 以外の IOException) と認証エラーは同期全体を中断して呼び出し元に投げる。
 * サーバーがエラーを返した場合やローカルファイルの問題は、そのファイルを失敗として記録して続行する。
 */
class SyncEngine(private val context: Context) {

    private val settings = AppSettings.get(context)
    private val folderDao = AppDatabase.get(context).folderDao()
    private val uploadDao = AppDatabase.get(context).uploadDao()
    private val media = MediaRepository(context)
    private val resolver get() = context.contentResolver

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    suspend fun run(mode: SyncMode, folderId: Long? = null): SyncSummary =
        // 自動同期と即時同期が同時に走って同じファイルを二重に送らないよう直列化する
        runLock.withLock { withContext(Dispatchers.IO) { runLocked(mode, folderId) } }

    private suspend fun runLocked(mode: SyncMode, folderId: Long?): SyncSummary {
        if (!media.hasFullMediaAccess()) {
            throw SyncBlockedException("画像・動画へのアクセスが「すべて許可」になっていません")
        }
        val account = OpenCloudAccounts.find(context, settings.accountName)
            ?: throw AuthRequiredException("OpenCloud アカウントが選択されていないか、公式アプリから削除されています")
        val serverUrl = settings.serverUrl ?: AccountName.parse(account.name)?.serverUrl
            ?: throw SyncBlockedException("サーバーURLが設定されていません")

        val folders = when (mode) {
            SyncMode.EXISTING -> listOfNotNull(folderId?.let { folderDao.get(it) })
            else -> folderDao.getAll().filter { it.enabled }
        }
        val candidates = findCandidates(mode, folders)
        if (candidates.isEmpty()) return SyncSummary(0, 0, 0)

        val api = OpenCloudApi(serverUrl, OpenCloudAccounts.backgroundTokenSource(context, account))
        val davRoot = settings.personalDavUrl
            ?: api.personalDriveDavUrl().also { settings.personalDavUrl = it }
        val tus = settings.tusSupported
            ?: api.supportsTus(davRoot).also { settings.tusSupported = it }
        val createdDirs = HashSet<String>()

        var uploaded = 0
        var skipped = 0
        var failed = 0
        candidates.forEachIndexed { index, (folder, item) ->
            currentCoroutineContext().ensureActive()
            _progress.value = SyncProgress(index, candidates.size, item.displayName)
            try {
                val result = upload(api, davRoot, tus, folder, item, createdDirs) { bytes, total ->
                    _progress.value = SyncProgress(index, candidates.size, item.displayName, bytes, total)
                }
                if (result == UploadStatus.SKIPPED) skipped++ else uploaded++
            } catch (e: RemoteHttpException) {
                failed++
                recordFailure(folder, item, e)
            } catch (e: LocalFileException) {
                failed++
                recordFailure(folder, item, e)
            } catch (e: FileNotFoundException) {
                failed++
                recordFailure(folder, item, e)
            } catch (e: IOException) {
                // 通信断などはファイルの問題ではないので、記録だけして同期全体を中断する
                recordFailure(folder, item, e)
                throw e
            }
        }
        _progress.value = SyncProgress(candidates.size, candidates.size, null)
        return SyncSummary(uploaded, skipped, failed)
    }

    private suspend fun findCandidates(mode: SyncMode, folders: List<SyncFolder>): List<Pair<SyncFolder, MediaItem>> {
        val states = uploadDao.getStates().associateBy { it.localKey }
        return folders.flatMap { folder ->
            val minDateAdded = if (mode == SyncMode.EXISTING) 0 else folder.baselineDateAdded
            media.queryFolder(folder.relativePath, minDateAdded, folder.includeVideos)
                .filter { item ->
                    val state = states[item.localKey]
                    when {
                        state == null -> true
                        state.status.isCompleted -> false
                        // 自動同期では何度も失敗しているファイルを諦める (即時同期・既存アップロードでは再試行)
                        mode == SyncMode.AUTO && state.attempts >= MAX_AUTO_ATTEMPTS -> false
                        else -> true
                    }
                }
                .map { folder to it }
        }.distinctBy { it.second.localKey }
    }

    private suspend fun upload(
        api: OpenCloudApi,
        davRoot: String,
        tus: Boolean,
        folder: SyncFolder,
        item: MediaItem,
        createdDirs: MutableSet<String>,
        onBytes: (Long, Long) -> Unit,
    ): UploadStatus {
        val uri = media.readableUri(item)
        // MediaStore の SIZE は古いことがあるので実ファイルのサイズを使う
        val length = try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        } catch (e: SecurityException) {
            throw LocalFileException("ファイルを読み取れません: ${e.message}", e)
        } ?: throw FileNotFoundException(item.displayName)
        val useTus = tus && length >= TUS_THRESHOLD

        var record = (uploadDao.get(item.localKey) ?: newRecord(folder, item, UploadStatus.UPLOADING))
            .copy(
                folderId = folder.id,
                size = length,
                dateModified = item.dateModified,
                status = UploadStatus.UPLOADING,
                updatedAt = System.currentTimeMillis(),
            )

        // 中断した分割アップロードの再開
        val previousTusUrl = record.tusUrl
        if (useTus && previousTusUrl != null && record.remotePath != null) {
            val offset = api.tusOffset(previousTusUrl)
            if (offset != null) {
                uploadDao.upsert(record)
                sendTus(api, previousTusUrl, uri, offset, length, onBytes)
                return complete(record, UploadStatus.DONE)
            }
        }

        val baseSegments = RemotePaths.parseRemoteDir(folder.remotePath).orEmpty()
        val dirSegments = baseSegments + RemotePaths.subdirSegments(folder.relativePath, item.relativePath)
        api.ensureDirectories(davRoot, dirSegments, createdDirs)

        val (fileName, alreadyUploaded) = chooseRemoteName(api, davRoot, dirSegments, item.displayName, length)
        if (alreadyUploaded) {
            return complete(
                record.copy(remotePath = RemotePaths.formatRemoteDir(dirSegments + fileName)),
                UploadStatus.SKIPPED,
            )
        }
        record = record.copy(remotePath = RemotePaths.formatRemoteDir(dirSegments + fileName), tusUrl = null)
        uploadDao.upsert(record)

        if (useTus) {
            val uploadUrl = api.tusCreate(api.davUrl(davRoot, dirSegments), fileName, length, item.dateModified)
            record = record.copy(tusUrl = uploadUrl)
            uploadDao.upsert(record)
            sendTus(api, uploadUrl, uri, 0, length, onBytes)
        } else {
            val body = ContentUriRequestBody(resolver, uri, 0, length) { onBytes(it, length) }
            api.put(api.davUrl(davRoot, dirSegments + fileName), body, item.dateModified)
        }
        return complete(record, UploadStatus.DONE)
    }

    /**
     * 使うファイル名と、「同名・同サイズのファイルが既にある (= アップロード不要)」かどうかを返す。
     */
    private fun chooseRemoteName(
        api: OpenCloudApi,
        davRoot: String,
        dirSegments: List<String>,
        displayName: String,
        length: Long,
    ): Pair<String, Boolean> {
        for (n in 1..MAX_RENAME) {
            val candidate = RemotePaths.conflictName(displayName, n)
            val entry = api.stat(api.davUrl(davRoot, dirSegments + candidate)) ?: return candidate to false
            if (!entry.isCollection && entry.size == length) return candidate to true
        }
        throw RemoteHttpException(409, "同名のファイルが多すぎます: $displayName")
    }

    private suspend fun sendTus(
        api: OpenCloudApi,
        uploadUrl: String,
        uri: Uri,
        startOffset: Long,
        length: Long,
        onBytes: (Long, Long) -> Unit,
    ) {
        var offset = startOffset
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            val chunkStart = offset
            val chunkLength = minOf(TUS_CHUNK_SIZE, length - offset)
            val body = ContentUriRequestBody(
                resolver, uri, chunkStart, chunkLength, ContentUriRequestBody.OFFSET_OCTET_STREAM,
            ) { onBytes(chunkStart + it, length) }
            offset = try {
                api.tusPatch(uploadUrl, chunkStart, body)
            } catch (e: RemoteHttpException) {
                // オフセット不一致はサーバー側の値に合わせて続行する
                if (e.code != 409) throw e
                api.tusOffset(uploadUrl) ?: throw e
            }
        }
    }

    private suspend fun complete(record: UploadRecord, status: UploadStatus): UploadStatus {
        uploadDao.upsert(
            record.copy(status = status, tusUrl = null, error = null, updatedAt = System.currentTimeMillis()),
        )
        return status
    }

    /** tusUrl / remotePath は残すので、次回は途中から再開できる */
    private suspend fun recordFailure(folder: SyncFolder, item: MediaItem, e: Exception) {
        val record = uploadDao.get(item.localKey) ?: newRecord(folder, item, UploadStatus.FAILED)
        uploadDao.upsert(
            record.copy(
                status = UploadStatus.FAILED,
                attempts = record.attempts + 1,
                error = e.message ?: e.javaClass.simpleName,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun newRecord(folder: SyncFolder, item: MediaItem, status: UploadStatus) = UploadRecord(
        folderId = folder.id,
        localKey = item.localKey,
        displayName = item.displayName,
        size = item.size,
        dateModified = item.dateModified,
        status = status,
        updatedAt = System.currentTimeMillis(),
    )

    companion object {
        private val runLock = Mutex()

        const val MAX_AUTO_ATTEMPTS = 5
        private const val MAX_RENAME = 100

        /** これ以上のサイズは TUS で分割・再開可能なアップロードにする */
        private const val TUS_THRESHOLD = 10L * 1024 * 1024
        private const val TUS_CHUNK_SIZE = 10L * 1024 * 1024
    }
}
