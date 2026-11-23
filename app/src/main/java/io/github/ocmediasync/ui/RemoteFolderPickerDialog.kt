package io.github.ocmediasync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ocmediasync.account.AuthRequiredException
import io.github.ocmediasync.remote.RemoteHttpException
import io.github.ocmediasync.sync.RemotePaths
import kotlinx.coroutines.launch

/** パーソナルスペース内のフォルダを辿って選ぶダイアログ */
@Composable
fun RemoteFolderPickerDialog(
    viewModel: MainViewModel,
    initialSegments: List<String>,
    onDismiss: () -> Unit,
    onSelect: (List<String>) -> Unit,
) {
    var segments by remember { mutableStateOf(initialSegments) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(segments) {
        folders = null
        error = null
        try {
            folders = viewModel.listRemoteFolders(segments)
        } catch (e: Exception) {
            error = errorMessage(e)
            folders = emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アップロード先を選択") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        RemotePaths.formatRemoteDir(segments),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { creating = true }, enabled = error == null && folders != null) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("新規")
                    }
                }
                HorizontalDivider()
                if (segments.isNotEmpty()) {
                    PickerRow(
                        label = "上のフォルダへ",
                        leading = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                        onClick = { segments = segments.dropLast(1) },
                    )
                }
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    val list = folders
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        list.isEmpty() -> Text("サブフォルダはありません", style = MaterialTheme.typography.bodyMedium)
                        else -> LazyColumn {
                            items(list, key = { it }) { name ->
                                PickerRow(
                                    label = name,
                                    trailing = {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                    },
                                    onClick = { segments = segments + name },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(segments) }) { Text("ここを選択") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )

    if (creating) {
        NewFolderDialog(
            existing = folders.orEmpty(),
            onDismiss = { creating = false },
            onCreate = { name ->
                scope.launch {
                    try {
                        viewModel.createRemoteFolder(segments + name)
                        creating = false
                        segments = segments + name
                    } catch (e: Exception) {
                        creating = false
                        error = errorMessage(e)
                    }
                }
            },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
private fun NewFolderDialog(existing: List<String>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val problem = when {
        trimmed.isEmpty() -> null
        RemotePaths.parseRemoteDir(trimmed) != listOf(trimmed) -> "使用できない名前です"
        existing.any { it.equals(trimmed, ignoreCase = true) } -> "同名のフォルダがあります"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しいフォルダ") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(trimmed) }, enabled = trimmed.isNotEmpty() && problem == null) {
                Text("作成")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private fun errorMessage(e: Exception): String = when {
    e is RemoteHttpException && e.code == 404 -> "このフォルダはまだありません (アップロード時に作成されます)"
    e is AuthRequiredException -> "${e.message}\nアカウント欄の「接続テスト」を実行してください"
    else -> "取得できません: ${e.message ?: e.javaClass.simpleName}"
}
