package io.github.ocmediasync.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.ocmediasync.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 同期ジョブの登録。
 *
 * - メディア追加の検知: MediaStore の content URI トリガー → 自動同期を予約
 * - 取りこぼし対策: 定期実行 (設定の間隔)
 * - 自動同期には Wi-Fi のみ / 充電中のみ の条件を付け、即時同期・既存アップロードには付けない
 */
object SyncScheduler {
    const val TAG_SYNC = "sync"

    private const val WORK_AUTO = "auto-sync"
    private const val WORK_PERIODIC = "periodic-sync"
    private const val WORK_MANUAL = "manual-sync"
    private const val WORK_EXISTING_PREFIX = "existing-sync-"
    private const val WORK_MEDIA_TRIGGER = "media-trigger"

    /** アプリ起動時に呼ぶ。既に登録済みなら何もしない */
    fun ensureScheduled(context: Context) {
        schedulePeriodic(context, ExistingPeriodicWorkPolicy.KEEP)
        armMediaTrigger(context, ExistingWorkPolicy.KEEP)
    }

    /** Wi-Fi / 充電 / 間隔の設定が変わったときに呼ぶ */
    suspend fun onConditionsChanged(context: Context) {
        schedulePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)
        enqueueAuto(context, replacePending = true)
    }

    /** 自動同期を予約する。条件 (Wi-Fi 等) を満たすまで待機する */
    suspend fun enqueueAuto(context: Context, replacePending: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        val states = workManager.getWorkInfosForUniqueWorkFlow(WORK_AUTO).first().map { it.state }
        val policy = when {
            // 待機中のものがあれば、それが走るときに新しいファイルも拾われる
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
                if (replacePending && WorkInfo.State.RUNNING !in states) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            // 実行中なら、走査後に追加されたファイルのために後続を予約する
            WorkInfo.State.RUNNING in states -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(autoConstraints(context))
            .setInputData(SyncWorker.input(SyncMode.AUTO))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG_SYNC)
            .build()
        workManager.enqueueUniqueWork(WORK_AUTO, policy, request)
    }

    /** 即時同期。Wi-Fi / 充電の条件を無視する */
    fun enqueueManual(context: Context) {
        enqueueImmediate(context, WORK_MANUAL, SyncMode.MANUAL, null)
    }

    /** フォルダ内の既存ファイルをすべてアップロードする。条件を無視する */
    fun enqueueExisting(context: Context, folderId: Long) {
        enqueueImmediate(context, WORK_EXISTING_PREFIX + folderId, SyncMode.EXISTING, folderId)
    }

    fun observeSyncWork(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_SYNC)

    /** MediaTriggerWorker の実行後に次の検知を登録する (トリガーは一度発火すると終わるため) */
    fun rearmMediaTrigger(context: Context) {
        armMediaTrigger(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueImmediate(context: Context, name: String, mode: SyncMode, folderId: Long?) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(SyncWorker.input(mode, folderId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_SYNC)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    private fun schedulePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val settings = AppSettings.get(context)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(autoConstraints(context))
            .setInputData(SyncWorker.input(SyncMode.AUTO))
            .addTag(TAG_SYNC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, policy, request)
    }

    private fun armMediaTrigger(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // 書き込み完了 (IS_PENDING 解除) を待つため少し遅らせる
            .setTriggerContentUpdateDelay(Duration.ofSeconds(10))
            .setTriggerContentMaxDelay(Duration.ofMinutes(1))
            .build()
        val request = OneTimeWorkRequestBuilder<MediaTriggerWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_MEDIA_TRIGGER, policy, request)
    }

    private fun autoConstraints(context: Context): Constraints {
        val settings = AppSettings.get(context)
        return Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.chargingOnly)
            .build()
    }
}
