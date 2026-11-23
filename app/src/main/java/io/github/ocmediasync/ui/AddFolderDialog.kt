package io.github.ocmediasync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ocmediasync.media.StorageTree
import io.github.ocmediasync.sync.RemotePaths

@Composable
fun AddFolderDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var remotePath by remember { mutableStateOf("") }
    // ユーザーが編集するまではフォルダ名から自動で埋める
    var remoteEdited by remember { mutableStateOf(false) }
    var pickingRemote by remember { mutableStateOf(false) }
    var includeVideos by remember { mutableStateOf(true) }

    // 標準のフォルダ選択画面。読み取りは MediaStore 経由なので、URI の永続的な権限は取らない
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val relativePath = StorageTree.relativePathOf(uri)
        if (relativePath == null) {
            pickError = "内部ストレージまたは SD カード内のフォルダを選んでください (ストレージ直下は選べません)"
            return@rememberLauncherForActivityResult
        }
        pickError = null
        selected = relativePath
        if (!remoteEdited) remotePath = "/" + relativePath.trimEnd('/').substringAfterLast('/')
    }

    // ダイアログを開いたらすぐ選択画面を出す
    LaunchedEffect(Unit) { localPicker.launch(StorageTree.initialUri()) }

    val error = viewModel.validateNewFolder(selected, remotePath)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期フォルダを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = selected.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("端末のフォルダ") },
                    placeholder = { Text("未選択") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { localPicker.launch(StorageTree.initialUri()) }) { Text("選択") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                pickError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = {
                        remotePath = it
                        remoteEdited = true
                    },
                    label = { Text("アップロード先 (パーソナルスペース内)") },
                    placeholder = { Text("/Screenshots") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { pickingRemote = true }) { Text("選択") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("動画もアップロードする", modifier = Modifier.weight(1f))
                    Switch(checked = includeVideos, onCheckedChange = { includeVideos = it })
                }
                Text(
                    "サブフォルダも含めて同期します。登録後に追加されたファイルを自動同期し、" +
                        "既存のファイルは登録後に「既存ファイルをアップロード」で送れます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (selected != null && error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let { viewModel.addFolder(it, remotePath, includeVideos) }
                    onDismiss()
                },
                enabled = error == null,
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )

    if (pickingRemote) {
        RemoteFolderPickerDialog(
            viewModel = viewModel,
            initialSegments = RemotePaths.parseRemoteDir(remotePath).orEmpty(),
            onDismiss = { pickingRemote = false },
            onSelect = { segments ->
                remotePath = RemotePaths.formatRemoteDir(segments)
                remoteEdited = true
                pickingRemote = false
            },
        )
    }
}
