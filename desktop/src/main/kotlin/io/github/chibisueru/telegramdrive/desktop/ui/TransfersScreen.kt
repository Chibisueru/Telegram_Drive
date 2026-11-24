package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.data.formatSize
import io.github.chibisueru.telegramdrive.desktop.data.openWithSystem
import io.github.chibisueru.telegramdrive.desktop.transfer.Transfer
import io.github.chibisueru.telegramdrive.desktop.transfer.TransferState

@Composable
fun TransfersScreen(c: AppContainer) {
    val list by c.transfers.list.collectAsState()
    val active = list.filter { it.active }
    val finished = list.filter { !it.active }.reversed()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Transfers", style = MaterialTheme.typography.titleLarge)
                val done = list.count { it.state == TransferState.DONE }
                if (list.isNotEmpty()) Text(
                    "${active.size} in progress · $done done",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active.isNotEmpty()) TextButton(onClick = c.transfers::cancelAll) { Text("Cancel all") }
            if (finished.isNotEmpty()) TextButton(onClick = c.transfers::clearFinished) { Text("Clear finished") }
        }
        if (list.isEmpty()) {
            EmptyState(Icons.Outlined.SwapVert, "No transfers", "Uploads and downloads show up here while they run.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(active, key = { it.id }) { TransferRow(c, it) }
            if (finished.isNotEmpty()) item {
                Text("Finished", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            items(finished, key = { it.id }) { TransferRow(c, it) }
        }
    }
}

@Composable
private fun TransferRow(c: AppContainer, t: Transfer) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (t.state) {
                TransferState.DONE -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
                TransferState.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
                else -> (if (t.upload) Icons.Filled.Upload else Icons.Filled.Download) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, null, tint = tint)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val status = when (t.state) {
                    TransferState.QUEUED -> "Waiting"
                    TransferState.RUNNING -> "${if (t.upload) "Uploading" else "Downloading"} · ${(t.progress * 100).toInt()} % of ${formatSize(t.size)}"
                    TransferState.DONE -> if (t.upload) "Uploaded" else "Saved to ${t.savedTo?.parentFile?.absolutePath ?: "Downloads"}"
                    TransferState.FAILED -> t.error ?: "Failed"
                    TransferState.CANCELLED -> "Cancelled"
                }
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (t.state == TransferState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.state == TransferState.RUNNING) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(8.dp))
            when {
                t.active -> Action(Icons.Filled.Close, "Cancel") { c.transfers.cancel(t.id) }
                t.state == TransferState.FAILED || t.state == TransferState.CANCELLED -> Action(Icons.Filled.Refresh, "Retry") { c.transfers.retry(t.id) }
                t.state == TransferState.DONE && t.savedTo != null -> Action(Icons.Filled.FolderOpen, "Open folder") {
                    t.savedTo.parentFile?.let { runCatching { openWithSystem(it) } }
                }
            }
        }
    }
}
