package io.github.ocmediasync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.ocmediasync.AppSettings
import io.github.ocmediasync.account.AuthRequiredException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.IOException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val settings = AppSettings.get(context)

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val mode = SyncMode.valueOf(inputData.getString(KEY_MODE) ?: SyncMode.AUTO.name)
        val folderId = inputData.getLong(KEY_FOLDER_ID, -1).takeIf { it >= 0 }
        val engine = SyncEngine(applicationContext)

        var canForeground = true
        val reporter = launch {
            engine.progress.filterNotNull().sample(PROGRESS_INTERVAL_MS).collect { progress ->
                setProgress(progress.toData())
                // アップロード対象があるときだけフォアグラウンド化して通知を更新する (長時間の転送でも止められにくくする)
                if (progress.total > 0 && canForeground) {
                    canForeground = trySetForeground(progress)
                }
            }
        }

        try {
            val summary = engine.run(mode, folderId)
            Notifications.clearActionRequired(applicationContext)
            if (summary.failed > 0) Notifications.showFailures(applicationContext, summary)
            finish(summary.message)
            Result.success(workDataOf(KEY_MESSAGE to summary.message))
        } catch (e: AuthRequiredException) {
            blocked(e.message ?: "認証が必要です")
        } catch (e: SyncBlockedException) {
            blocked(e.message ?: "同期できません")
        } catch (e: IOException) {
            val message = "通信エラーのため中断しました: ${e.message ?: e.javaClass.simpleName}"
            finish(message)
            if (mode == SyncMode.AUTO && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_MESSAGE to message))
            }
        } finally {
            reporter.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notifications.foregroundInfo(applicationContext, null)

    private suspend fun trySetForeground(progress: SyncProgress): Boolean =
        try {
            setForeground(Notifications.foregroundInfo(applicationContext, progress))
            true
        } catch (_: IllegalStateException) {
            // Android 12 以降、バックグラウンドからはフォアグラウンドサービスを開始できない場合がある
            false
        }

    private fun blocked(message: String): Result {
        finish(message)
        Notifications.showActionRequired(applicationContext, message)
        return Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    private fun finish(message: String) {
        settings.lastSyncAt = System.currentTimeMillis()
        settings.lastSyncMessage = message
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_MESSAGE = "message"

        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_NAME = "name"
        const val KEY_BYTES = "bytes"
        const val KEY_BYTES_TOTAL = "bytes_total"

        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val MAX_RETRIES = 5

        fun input(mode: SyncMode, folderId: Long? = null): Data =
            workDataOf(KEY_MODE to mode.name, KEY_FOLDER_ID to (folderId ?: -1L))

        private fun SyncProgress.toData(): Data = workDataOf(
            KEY_DONE to done,
            KEY_TOTAL to total,
            KEY_NAME to currentName,
            KEY_BYTES to bytes,
            KEY_BYTES_TOTAL to bytesTotal,
        )

        fun progressFrom(data: Data): SyncProgress? {
            val total = data.getInt(KEY_TOTAL, 0)
            if (total == 0) return null
            return SyncProgress(
                done = data.getInt(KEY_DONE, 0),
                total = total,
                currentName = data.getString(KEY_NAME),
                bytes = data.getLong(KEY_BYTES, 0),
                bytesTotal = data.getLong(KEY_BYTES_TOTAL, 0),
            )
        }
    }
}

/** 画像・動画の追加で起動され、自動同期を予約して自身を再登録する */
class MediaTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncScheduler.enqueueAuto(applicationContext)
        SyncScheduler.rearmMediaTrigger(applicationContext)
        return Result.success()
    }
}
