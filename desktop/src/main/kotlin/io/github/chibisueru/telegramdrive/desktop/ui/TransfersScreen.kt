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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Which transfers the page lists: running ones by default, or one of the two pills. */
private enum class TransferFilter { ACTIVE, COMPLETED, ERRORED }

@Composable
fun TransfersScreen(c: AppContainer) {
    val list by c.transfers.list.collectAsState()
    val active = list.filter { it.active }
    val completed = list.filter { it.state == TransferState.DONE }.reversed()
    val errored = list.filter { it.state == TransferState.FAILED || it.state == TransferState.CANCELLED }.reversed()
    var filter by remember { mutableStateOf(TransferFilter.ACTIVE) }
    fun toggle(f: TransferFilter) { filter = if (filter == f) TransferFilter.ACTIVE else f }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Transfers", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${active.size} in progress",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (filter) {
                TransferFilter.ACTIVE -> if (active.isNotEmpty()) TextButton(onClick = c.transfers::cancelAll) { Text("Cancel all") }
                TransferFilter.COMPLETED -> if (completed.isNotEmpty()) TextButton(onClick = c.transfers::clearCompleted) { Text("Clear all") }
                TransferFilter.ERRORED -> if (errored.isNotEmpty()) {
                    FilledTonalButton(onClick = c.transfers::retryAllErrored) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Text("  Retry all")
                    }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = c.transfers::clearErrored) {
                        Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); Text("  Clear all")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == TransferFilter.COMPLETED,
                onClick = { toggle(TransferFilter.COMPLETED) },
                label = { Text("Completed · ${completed.size}") },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = filter == TransferFilter.ERRORED,
                onClick = { toggle(TransferFilter.ERRORED) },
                label = { Text("Errored · ${errored.size}") },
                leadingIcon = {
                    Icon(Icons.Filled.Error, null, Modifier.size(18.dp),
                        tint = if (errored.isNotEmpty()) MaterialTheme.colorScheme.error else LocalContentColor.current)
                },
            )
        }
        val shown = when (filter) {
            TransferFilter.ACTIVE -> active
            TransferFilter.COMPLETED -> completed
            TransferFilter.ERRORED -> errored
        }
        if (shown.isEmpty()) {
            when (filter) {
                TransferFilter.ACTIVE -> EmptyState(Icons.Outlined.SwapVert, "Nothing in progress", "Uploads and downloads show up here while they run.")
                TransferFilter.COMPLETED -> EmptyState(Icons.Filled.CheckCircle, "No completed transfers", "Finished uploads and downloads are listed here.")
                TransferFilter.ERRORED -> EmptyState(Icons.Filled.Error, "No errors", "Transfers that fail or are cancelled are listed here, ready to retry.")
            }
            return
        }
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(end = 12.dp), state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { TransferRow(c, it) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
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
