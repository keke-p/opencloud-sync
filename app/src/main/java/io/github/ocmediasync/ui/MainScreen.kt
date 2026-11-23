@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.ocmediasync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ocmediasync.data.SyncFolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppRoot(viewModel: MainViewModel, actions: ScreenActions) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    if (showHistory) {
        BackHandler { showHistory = false }
        HistoryScreen(viewModel, onBack = { showHistory = false })
    } else {
        MainScreen(viewModel, actions, onOpenHistory = { showHistory = true })
    }
}

@Composable
private fun MainScreen(viewModel: MainViewModel, actions: ScreenActions, onOpenHistory: () -> Unit) {
    val settings by viewModel.settingsState.collectAsState()
    val environment by viewModel.environment.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val failedCount by viewModel.failedCount.collectAsState()
    var showAddFolder by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("OpenCloud Media Sync") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!environment.fullMediaAccess) {
                item { PermissionCard(actions) }
            }
            item { AccountCard(settings, environment, connection, actions, viewModel::setServerUrl) }
            item {
                SyncCard(settings, sync, failedCount, onSyncNow = viewModel::syncNow, onOpenHistory = onOpenHistory)
            }
            item {
                ConditionsCard(
                    settings,
                    onWifiOnly = viewModel::setWifiOnly,
                    onChargingOnly = viewModel::setChargingOnly,
                    onInterval = viewModel::setIntervalMinutes,
                )
            }
            item { BackgroundCard(environment, actions) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("同期フォルダ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAddFolder = true }, enabled = environment.fullMediaAccess) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("追加")
                    }
                }
            }
            if (folders.isEmpty()) {
                item {
                    Text(
                        "フォルダが登録されていません。「追加」から同期するフォルダを選んでください。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(folders, key = { it.id }) { folder ->
                FolderCard(
                    folder,
                    onEnabledChange = { viewModel.setFolderEnabled(folder, it) },
                    onIncludeVideosChange = { viewModel.setFolderIncludeVideos(folder, it) },
                    onUploadExisting = { viewModel.uploadExisting(folder) },
                    onDelete = { viewModel.deleteFolder(folder) },
                )
            }
        }
    }

    if (showAddFolder) {
        AddFolderDialog(viewModel, onDismiss = { showAddFolder = false })
    }
}

@Composable
private fun PermissionCard(actions: ScreenActions) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("写真と動画へのアクセスが必要です", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "「すべて許可」を選んでください。「選択した写真のみ」では新しいファイルを同期できません。",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::requestPermissions) { Text("許可する") }
                OutlinedButton(onClick = actions::openAppSettings) { Text("アプリの設定を開く") }
            }
        }
    }
}

@Composable
private fun AccountCard(
    settings: SettingsUiState,
    environment: EnvironmentState,
    connection: ConnectionState,
    actions: ScreenActions,
    onServerUrlChange: (String) -> Unit,
) {
    var editingUrl by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("アカウント", style = MaterialTheme.typography.titleMedium)
            when {
                !environment.officialAppInstalled -> {
                    Text("公式 OpenCloud アプリがインストールされていません。公式アプリでログインしたアカウントを使用します。")
                }
                settings.accountName == null -> {
                    Text("公式アプリでログインしているアカウントを選択してください。")
                    Button(onClick = actions::chooseAccount) { Text("アカウントを選択") }
                }
                else -> {
                    Text(settings.accountName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "サーバー: ${settings.serverUrl ?: "未設定"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!environment.accountAvailable) {
                        Text(
                            "このアカウントが見つかりません。公式アプリでログインし直すか、アカウントを選択し直してください。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    when (connection) {
                        ConnectionState.Idle -> Text(
                            "初回は「接続テスト」でアカウントへのアクセスを許可してください。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ConnectionState.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is ConnectionState.Ok -> Text(connection.message, color = MaterialTheme.colorScheme.primary)
                        is ConnectionState.Error -> Text(connection.message, color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions::testConnection, enabled = connection != ConnectionState.Testing) {
                            Text("接続テスト")
                        }
                        OutlinedButton(onClick = actions::chooseAccount) { Text("変更") }
                        OutlinedButton(onClick = { editingUrl = true }) { Text("URL編集") }
                        if (connection is ConnectionState.Error) {
                            OutlinedButton(onClick = actions::openOfficialApp) { Text("公式アプリを開く") }
                        }
                    }
                }
            }
        }
    }
    if (editingUrl) {
        ServerUrlDialog(settings.serverUrl.orEmpty(), onDismiss = { editingUrl = false }) {
            onServerUrlChange(it)
            editingUrl = false
        }
    }
}

@Composable
private fun ServerUrlDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf(initial) }
    val valid = url.startsWith("https://") || url.startsWith("http://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("サーバーURL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "アカウント名から推定しています。http で運用している場合などに変更してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, isError = !valid)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(url) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun SyncCard(
    settings: SettingsUiState,
    sync: SyncUiState,
    failedCount: Int,
    onSyncNow: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同期", style = MaterialTheme.typography.titleMedium)
            val progress = sync.progress
            when {
                sync.running && progress != null -> {
                    Text("アップロード中 (${minOf(progress.done + 1, progress.total)}/${progress.total})")
                    progress.currentName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (progress.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { progress.bytes.toFloat() / progress.bytesTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                sync.running -> {
                    Text("確認中…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                settings.lastSyncAt > 0 -> {
                    Text("前回: ${formatTime(settings.lastSyncAt)}", style = MaterialTheme.typography.bodySmall)
                    settings.lastSyncMessage?.let { Text(it) }
                }
                else -> Text("まだ同期していません")
            }
            if (failedCount > 0) {
                Text("失敗したファイルが ${failedCount}件あります", color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow, enabled = !sync.running) { Text("今すぐ同期") }
                OutlinedButton(onClick = onOpenHistory) { Text("履歴") }
            }
            Text(
                "「今すぐ同期」は Wi-Fi・充電の条件を無視して実行します。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConditionsCard(
    settings: SettingsUiState,
    onWifiOnly: (Boolean) -> Unit,
    onChargingOnly: (Boolean) -> Unit,
    onInterval: (Long) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("自動同期の条件", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Wi-Fi 接続時のみ", "従量制でないネットワークのみ", settings.wifiOnly, onWifiOnly)
            SwitchRow("充電中のみ", null, settings.chargingOnly, onChargingOnly)
            Spacer(Modifier.padding(top = 4.dp))
            Text("定期チェックの間隔", style = MaterialTheme.typography.bodyLarge)
            Text(
                "新しい写真・動画は追加時に検知します。これは検知漏れを拾うための間隔です。",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVALS.forEach { (minutes, label) ->
                    FilterChip(
                        selected = settings.intervalMinutes == minutes,
                        onClick = { onInterval(minutes) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundCard(environment: EnvironmentState, actions: ScreenActions) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("バックグラウンド動作", style = MaterialTheme.typography.titleMedium)
            if (environment.batteryOptimizationIgnored) {
                Text("バッテリーの最適化: 対象外 (推奨設定)", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "バッテリーの最適化が有効なため、自動同期が遅れたり止められたりすることがあります。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = actions::requestIgnoreBatteryOptimizations) { Text("最適化の対象外にする") }
            }
            Text(
                "再起動後はアプリを開かなくても自動同期が再開されます。" +
                    "一部メーカーの端末では、アプリ情報の「自動起動」などの許可も必要です。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = actions::openAppSettings) { Text("アプリ情報を開く") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FolderCard(
    folder: SyncFolder,
    onEnabledChange: (Boolean) -> Unit,
    onIncludeVideosChange: (Boolean) -> Unit,
    onUploadExisting: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf<FolderAction?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder.relativePath, style = MaterialTheme.typography.bodyLarge)
                    Text("→ ${folder.remotePath}", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = folder.enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                "${formatTime(folder.createdAt)} 以降の追加分を自動同期",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("動画もアップロードする", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = folder.includeVideos, onCheckedChange = onIncludeVideosChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { confirm = FolderAction.UPLOAD_EXISTING }) { Text("既存ファイルをアップロード") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { confirm = FolderAction.DELETE }) {
                    Icon(Icons.Filled.Delete, contentDescription = "削除")
                }
            }
        }
    }
    when (confirm) {
        FolderAction.UPLOAD_EXISTING -> ConfirmDialog(
            title = "既存ファイルをアップロード",
            message = "${folder.relativePath} 配下のまだアップロードしていない" +
                (if (folder.includeVideos) "画像・動画" else "画像") + "をすべてアップロードします。" +
                "Wi-Fi・充電の条件は無視されます。",
            confirmLabel = "アップロード",
            onDismiss = { confirm = null },
            onConfirm = { confirm = null; onUploadExisting() },
        )
        FolderAction.DELETE -> ConfirmDialog(
            title = "フォルダの登録を解除",
            message = "${folder.relativePath} の同期を止めます。アップロード済みのファイルは削除されません。",
            confirmLabel = "解除",
            onDismiss = { confirm = null },
            onConfirm = { confirm = null; onDelete() },
        )
        null -> Unit
    }
}

private enum class FolderAction { UPLOAD_EXISTING, DELETE }

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private val INTERVALS = listOf(
    15L to "15分",
    30L to "30分",
    60L to "1時間",
    180L to "3時間",
    360L to "6時間",
)

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(millis))
