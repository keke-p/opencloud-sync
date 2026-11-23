package io.github.ocmediasync.ui

import android.app.Activity
import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import io.github.ocmediasync.AppSettings
import io.github.ocmediasync.account.AccountName
import io.github.ocmediasync.account.AuthRequiredException
import io.github.ocmediasync.account.OpenCloudAccounts
import io.github.ocmediasync.data.AppDatabase
import io.github.ocmediasync.data.SyncFolder
import io.github.ocmediasync.data.UploadRecord
import io.github.ocmediasync.media.MediaRepository
import io.github.ocmediasync.remote.OpenCloudApi
import io.github.ocmediasync.sync.RemotePaths
import io.github.ocmediasync.sync.SyncProgress
import io.github.ocmediasync.sync.SyncScheduler
import io.github.ocmediasync.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val accountName: String?,
    val serverUrl: String?,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val intervalMinutes: Long,
    val lastSyncAt: Long,
    val lastSyncMessage: String?,
)

data class SyncUiState(val running: Boolean, val progress: SyncProgress?)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState
    data class Ok(val message: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class EnvironmentState(
    val fullMediaAccess: Boolean,
    val officialAppInstalled: Boolean,
    /** 選択済みのアカウントが公式アプリ側に存在し、本アプリから見えるか */
    val accountAvailable: Boolean,
    /** バッテリー最適化の対象外になっているか */
    val batteryOptimizationIgnored: Boolean,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings.get(app)
    private val db = AppDatabase.get(app)
    private val media = MediaRepository(app)

    val settingsState: StateFlow<SettingsUiState> = settings.changes()
        .map { snapshot() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, snapshot())

    val folders: StateFlow<List<SyncFolder>> = db.folderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<UploadRecord>> = db.uploadDao().observeRecent(HISTORY_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val failedCount: StateFlow<Int> = db.uploadDao().observeFailedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val syncState: StateFlow<SyncUiState> = SyncScheduler.observeSyncWork(app)
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            SyncUiState(running != null, running?.let { SyncWorker.progressFrom(it.progress) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState(false, null))

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _environment = MutableStateFlow(readEnvironment())
    val environment: StateFlow<EnvironmentState> = _environment.asStateFlow()

    /** 画面に戻ってきたとき (権限変更・公式アプリでの操作の後) に呼ぶ */
    fun refreshEnvironment() {
        _environment.value = readEnvironment()
    }

    fun currentAccount() = OpenCloudAccounts.find(app, settings.accountName)

    fun onAccountChosen(accountName: String) {
        settings.accountName = accountName
        settings.serverUrl = AccountName.parse(accountName)?.serverUrl
        _connection.value = ConnectionState.Idle
        refreshEnvironment()
    }

    fun setServerUrl(url: String) {
        settings.serverUrl = url.trim()
        _connection.value = ConnectionState.Idle
    }

    fun setWifiOnly(value: Boolean) {
        settings.wifiOnly = value
        rescheduleAuto()
    }

    fun setChargingOnly(value: Boolean) {
        settings.chargingOnly = value
        rescheduleAuto()
    }

    fun setIntervalMinutes(value: Long) {
        settings.intervalMinutes = value
        rescheduleAuto()
    }

    private fun rescheduleAuto() {
        viewModelScope.launch { SyncScheduler.onConditionsChanged(app) }
    }

    /**
     * 接続テスト。初回は OS のアクセス許可ダイアログ (または公式アプリのログイン画面) が表示されるので、
     * バックグラウンド同期の前に一度実行してもらう。
     */
    fun testConnection(activity: Activity) {
        val account = currentAccount() ?: run {
            _connection.value = ConnectionState.Error("アカウントが選択されていません")
            return
        }
        val serverUrl = settings.serverUrl ?: run {
            _connection.value = ConnectionState.Error("サーバーURLが設定されていません")
            return
        }
        _connection.value = ConnectionState.Testing
        viewModelScope.launch {
            _connection.value = withContext(Dispatchers.IO) {
                try {
                    val api = OpenCloudApi(serverUrl, OpenCloudAccounts.interactiveTokenSource(activity, account))
                    val davUrl = api.personalDriveDavUrl()
                    val tus = api.supportsTus(davUrl)
                    settings.personalDavUrl = davUrl
                    settings.tusSupported = tus
                    ConnectionState.Ok("接続できました (分割アップロード: ${if (tus) "対応" else "非対応"})")
                } catch (e: AuthRequiredException) {
                    ConnectionState.Error(e.message ?: "認証が必要です")
                } catch (e: Exception) {
                    ConnectionState.Error("接続できません: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun syncNow() = SyncScheduler.enqueueManual(app)

    fun uploadExisting(folder: SyncFolder) = SyncScheduler.enqueueExisting(app, folder.id)

    fun retryFailed() {
        viewModelScope.launch {
            db.uploadDao().resetFailedAttempts()
            SyncScheduler.enqueueManual(app)
        }
    }

    /** パーソナルスペース内の [segments] 直下のサブフォルダ名 (名前順) */
    suspend fun listRemoteFolders(segments: List<String>): List<String> = withContext(Dispatchers.IO) {
        val (api, davRoot) = remoteContext()
        api.listSubfolders(api.davUrl(davRoot, segments)).sortedBy { it.lowercase() }
    }

    suspend fun createRemoteFolder(segments: List<String>) = withContext(Dispatchers.IO) {
        val (api, davRoot) = remoteContext()
        api.createFolder(api.davUrl(davRoot, segments))
    }

    /** バックグラウンド用のトークンを使う。未許可なら「接続テスト」を促す例外になる */
    private fun remoteContext(): Pair<OpenCloudApi, String> {
        val account = currentAccount() ?: throw AuthRequiredException("アカウントが選択されていません")
        val serverUrl = settings.serverUrl ?: throw AuthRequiredException("サーバーURLが設定されていません")
        val api = OpenCloudApi(serverUrl, OpenCloudAccounts.backgroundTokenSource(app, account))
        val davRoot = settings.personalDavUrl ?: api.personalDriveDavUrl().also { settings.personalDavUrl = it }
        return api to davRoot
    }

    /** 追加しようとしているフォルダの入力チェック。問題なければ null */
    fun validateNewFolder(relativePath: String?, remotePath: String): String? {
        if (relativePath == null) return "フォルダを選択してください"
        if (RemotePaths.parseRemoteDir(remotePath) == null) return "アップロード先のパスが不正です"
        val overlapping = folders.value.firstOrNull { RemotePaths.overlaps(it.relativePath, relativePath) }
        if (overlapping != null) return "登録済みのフォルダ (${overlapping.relativePath}) と重なっています"
        return null
    }

    fun addFolder(relativePath: String, remotePath: String, includeVideos: Boolean) {
        val segments = RemotePaths.parseRemoteDir(remotePath) ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            db.folderDao().insert(
                SyncFolder(
                    relativePath = RemotePaths.normalizeRelativePath(relativePath),
                    remotePath = RemotePaths.formatRemoteDir(segments),
                    includeVideos = includeVideos,
                    // 登録以降に追加されたファイルだけを自動同期する
                    baselineDateAdded = now / 1000,
                    createdAt = now,
                ),
            )
        }
    }

    fun setFolderEnabled(folder: SyncFolder, enabled: Boolean) {
        viewModelScope.launch { db.folderDao().update(folder.copy(enabled = enabled)) }
    }

    fun setFolderIncludeVideos(folder: SyncFolder, includeVideos: Boolean) {
        viewModelScope.launch { db.folderDao().update(folder.copy(includeVideos = includeVideos)) }
    }

    /** アップロード記録は残すので、同じフォルダを再登録しても二重アップロードはしない */
    fun deleteFolder(folder: SyncFolder) {
        viewModelScope.launch { db.folderDao().delete(folder) }
    }

    private fun snapshot() = SettingsUiState(
        accountName = settings.accountName,
        serverUrl = settings.serverUrl,
        wifiOnly = settings.wifiOnly,
        chargingOnly = settings.chargingOnly,
        intervalMinutes = settings.intervalMinutes,
        lastSyncAt = settings.lastSyncAt,
        lastSyncMessage = settings.lastSyncMessage,
    )

    private fun readEnvironment() = EnvironmentState(
        fullMediaAccess = media.hasFullMediaAccess(),
        officialAppInstalled = OpenCloudAccounts.isOfficialAppInstalled(app),
        accountAvailable = OpenCloudAccounts.find(app, settings.accountName) != null,
        batteryOptimizationIgnored = app.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(app.packageName),
    )

    companion object {
        private const val HISTORY_LIMIT = 300
    }
}
