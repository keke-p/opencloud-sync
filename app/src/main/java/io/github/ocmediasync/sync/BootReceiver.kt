package io.github.ocmediasync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 端末の再起動・アプリの更新後に、アプリを開かなくても同期が再開されるようにする。
 *
 * WorkManager 自身も再起動時に再登録を行うが、それに頼り切らず、
 * 起動のたびに登録を確認して、電源オフ中・更新中の取りこぼしを拾う自動同期を予約する。
 * (プロセスが起動すれば App.onCreate でも ensureScheduled が呼ばれる)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                SyncScheduler.ensureScheduled(appContext)
                SyncScheduler.enqueueAuto(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
