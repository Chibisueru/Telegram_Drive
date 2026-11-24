package io.github.chibisueru.telegramdrive.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.data.db.TransferEntity
import io.github.chibisueru.telegramdrive.ui.appViewModel
import io.github.chibisueru.telegramdrive.util.formatSize
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransfersViewModel(private val c: AppContainer) : ViewModel() {
    val transfers = c.transfers.dao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True while the queue waits because "Wi-Fi only" is on and the phone isn't on Wi-Fi. */
    val waitingForWifi = combine(c.settings.flow, c.network.onWifi) { s, wifi -> s.wifiOnly && !wifi }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun cancel(t: TransferEntity) = viewModelScope.launch { c.transfers.cancel(t.id) }
    fun retry(t: TransferEntity) = viewModelScope.launch { c.transfers.retry(t.id) }
    fun cancelAll() = viewModelScope.launch { c.transfers.cancelAll() }
    fun clearFinished() = viewModelScope.launch { c.transfers.clearFinished() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen() {
    val vm = appViewModel { c, _ -> TransfersViewModel(c) }
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val waitingForWifi by vm.waitingForWifi.collectAsStateWithLifecycle()
    val active = transfers.filter { it.active }.sortedBy { if (it.state == TransferEntity.STATE_RUNNING) 0 else 1 }
    val finished = transfers.filter { !it.active }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                actions = {
                    if (active.isNotEmpty()) TextButton(onClick = vm::cancelAll) { Text("Cancel all") }
                    if (finished.isNotEmpty()) TextButton(onClick = vm::clearFinished) { Text("Clear finished") }
                },
            )
        },
    ) { padding ->
        if (transfers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(112.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Download, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Text("No transfers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
                Text(
                    "Uploads and downloads show up here while they run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (waitingForWifi && active.isNotEmpty()) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text("Waiting for Wi-Fi") },
                        supportingContent = { Text("“Wi-Fi only” is on in Settings. Transfers continue when you connect.") },
                    )
                }
            }
            if (active.isNotEmpty()) {
                item { Section("In progress · ${active.size}") }
                items(active, key = { it.id }) { t -> TransferRow(t, onCancel = { vm.cancel(t) }, onRetry = null) }
            }
            if (finished.isNotEmpty()) {
                item { Section("Finished") }
                items(finished, key = { it.id }) { t ->
                    TransferRow(t, onCancel = null, onRetry = if (t.state != TransferEntity.STATE_DONE) ({ vm.retry(t) }) else null)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun TransferRow(t: TransferEntity, onCancel: (() -> Unit)?, onRetry: (() -> Unit)?) {
    val failed = t.state == TransferEntity.STATE_FAILED || t.state == TransferEntity.STATE_CANCELLED
    val done = t.state == TransferEntity.STATE_DONE
    ListItem(
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when {
                    failed -> MaterialTheme.colorScheme.errorContainer
                    done -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            failed -> Icons.Default.Error
                            done -> Icons.Default.CheckCircle
                            t.upload -> Icons.Default.Upload
                            else -> Icons.Default.Download
                        },
                        null,
                    )
                }
            }
        },
        headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val size = if (t.size > 0) formatSize(t.size) + " · " else ""
                Text(
                    size + when (t.state) {
                        TransferEntity.STATE_RUNNING -> (if (t.upload) "Uploading" else "Downloading") + " · ${(t.progress * 100).toInt()}%"
                        TransferEntity.STATE_QUEUED -> "Waiting"
                        TransferEntity.STATE_DONE -> if (t.upload) "Uploaded" else "Saved to Downloads"
                        TransferEntity.STATE_CANCELLED -> "Cancelled"
                        else -> t.error ?: "Failed"
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (t.state == TransferEntity.STATE_FAILED) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                if (t.state == TransferEntity.STATE_RUNNING) {
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = {
            when {
                onCancel != null -> IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                onRetry != null -> IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
