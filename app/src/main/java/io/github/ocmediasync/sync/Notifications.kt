package io.github.ocmediasync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import io.github.ocmediasync.R
import io.github.ocmediasync.ui.MainActivity

object Notifications {
    private const val CHANNEL_PROGRESS = "sync_progress"
    private const val CHANNEL_ALERT = "sync_alert"

    private const val ID_PROGRESS = 1
    private const val ID_ACTION_REQUIRED = 2
    private const val ID_FAILURES = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "同期の進捗", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "同期エラー", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun foregroundInfo(context: Context, progress: SyncProgress?): ForegroundInfo =
        ForegroundInfo(ID_PROGRESS, progressNotification(context, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun progressNotification(context: Context, progress: SyncProgress?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("アップロード中")
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress == null || progress.total == 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setContentTitle("アップロード中 (${progress.done + 1}/${progress.total})")
            builder.setContentText(progress.currentName)
            if (progress.bytesTotal > 0) {
                val percent = (progress.bytes * 100 / progress.bytesTotal).toInt()
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    fun showActionRequired(context: Context, message: String) {
        notify(
            context,
            ID_ACTION_REQUIRED,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("同期できません")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showFailures(context: Context, summary: SyncSummary) {
        notify(
            context,
            ID_FAILURES,
            NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("${summary.failed}件のアップロードに失敗しました")
                .setContentText("アプリの履歴で詳細を確認できます")
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearActionRequired(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_ACTION_REQUIRED)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 通知の権限がない場合は表示しない
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
