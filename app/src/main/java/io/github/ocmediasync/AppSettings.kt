package io.github.ocmediasync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** アプリ設定。ワーカーからも同期的に読むため SharedPreferences を使う。 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 公式アプリ側のアカウント名 */
    var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT_NAME, null)
        set(value) = prefs.edit {
            putString(KEY_ACCOUNT_NAME, value)
            clearServerCache()
        }

    /** アカウント名から推定したサーバーURL (http サーバー等のため編集可能) */
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit {
            putString(KEY_SERVER_URL, value?.trimEnd('/'))
            clearServerCache()
        }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

    var chargingOnly: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_CHARGING_ONLY, value) }

    /** 取りこぼし対策の定期チェック間隔 (分, 最小 15) */
    var intervalMinutes: Long
        get() = prefs.getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        set(value) = prefs.edit { putLong(KEY_INTERVAL_MINUTES, value.coerceAtLeast(15)) }

    /** パーソナルスペースの WebDAV ルート URL (Graph API から取得してキャッシュ) */
    var personalDavUrl: String?
        get() = prefs.getString(KEY_PERSONAL_DAV_URL, null)
        set(value) = prefs.edit { putString(KEY_PERSONAL_DAV_URL, value) }

    /** サーバーが TUS に対応しているか (未確認なら null) */
    var tusSupported: Boolean?
        get() = if (prefs.contains(KEY_TUS_SUPPORTED)) prefs.getBoolean(KEY_TUS_SUPPORTED, false) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_TUS_SUPPORTED) else putBoolean(KEY_TUS_SUPPORTED, value)
        }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC_AT, value) }

    var lastSyncMessage: String?
        get() = prefs.getString(KEY_LAST_SYNC_MESSAGE, null)
        set(value) = prefs.edit { putString(KEY_LAST_SYNC_MESSAGE, value) }

    private fun SharedPreferences.Editor.clearServerCache() {
        remove(KEY_PERSONAL_DAV_URL)
        remove(KEY_TUS_SUPPORTED)
    }

    /** いずれかの設定が変わるたびに発火する */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 60L

        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_PERSONAL_DAV_URL = "personal_dav_url"
        private const val KEY_TUS_SUPPORTED = "tus_supported"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_LAST_SYNC_MESSAGE = "last_sync_message"

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
