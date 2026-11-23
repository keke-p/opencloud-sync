@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.ocmediasync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ocmediasync.data.UploadRecord
import io.github.ocmediasync.data.UploadStatus

@Composable
fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val history by viewModel.history.collectAsState()
    val failedCount by viewModel.failedCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アップロード履歴") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (failedCount > 0) {
                        TextButton(onClick = viewModel::retryFailed) { Text("失敗分を再試行") }
                    }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Text("履歴はありません", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            items(history, key = { it.id }) { record ->
                HistoryRow(record)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HistoryRow(record: UploadRecord) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(statusLabel(record.status), color = statusColor(record.status), style = MaterialTheme.typography.labelLarge)
        }
        record.remotePath?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            "${formatTime(record.updatedAt)} ・ ${formatSize(record.size)}" +
                if (record.attempts > 0) " ・ 失敗 ${record.attempts}回" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (record.status != UploadStatus.DONE && record.status != UploadStatus.SKIPPED) {
            record.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun statusLabel(status: UploadStatus) = when (status) {
    UploadStatus.UPLOADING -> "中断/送信中"
    UploadStatus.DONE -> "完了"
    UploadStatus.SKIPPED -> "既存のため省略"
    UploadStatus.FAILED -> "失敗"
}

@Composable
private fun statusColor(status: UploadStatus): Color = when (status) {
    UploadStatus.FAILED -> MaterialTheme.colorScheme.error
    UploadStatus.UPLOADING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
