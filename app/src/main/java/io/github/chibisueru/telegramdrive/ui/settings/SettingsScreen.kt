package io.github.chibisueru.telegramdrive.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.data.SyncState
import io.github.chibisueru.telegramdrive.ui.appViewModel
import io.github.chibisueru.telegramdrive.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val totalSize = c.dao.totalSize().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val fileCount = c.dao.fileCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val syncState = c.indexSync.state
    val busy = MutableStateFlow(false)

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }
    fun setThemeMode(v: Int) = viewModelScope.launch { c.settings.setThemeMode(v) }

    fun syncNow() = run("Index saved to Telegram") { c.indexSync.syncNow() }

    fun restore() = run("Drive restored from Telegram") {
        if (!c.indexSync.restoreFromTelegram()) error("No pinned index found in the channel")
    }

    fun clearCache() = run("Cache cleared") {
        withContext(Dispatchers.IO) {
            c.files.clearViewCache()
            c.files.clearThumbnails()
            c.mediaCache.keys.toList().forEach { c.mediaCache.removeResource(it) }
        }
    }

    /** Saves the index first so nothing is lost, then forgets the bot and clears local data. */
    fun signOut(onDone: () -> Unit) = run(null) {
        c.indexSync.syncNow()
        c.repository.cancelAllTransfers()
        c.settings.clearConnection()
        c.dao.clearAll()
        withContext(Dispatchers.IO) {
            c.files.clearViewCache()
            c.files.clearThumbnails()
        }
        onDone()
    }

    private fun run(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            block()
            success?.let { _events.send(it) }
        } catch (e: Exception) {
            _events.send(e.message ?: "Something went wrong")
        } finally {
            busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val vm = appViewModel { c, _ -> SettingsViewModel(c) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val total by vm.totalSize.collectAsStateWithLifecycle()
    val count by vm.fileCount.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    val settings = s ?: return

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Section("Storage")
            ListItem(
                leadingContent = { Icon(Icons.Default.Storage, null) },
                headlineContent = { Text("${formatSize(total)} used") },
                supportingContent = { Text("$count file${if (count == 1) "" else "s"} · no storage limit") },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.SmartToy, null) },
                headlineContent = { Text(settings.chatTitle.ifBlank { "Storage channel" }) },
                supportingContent = { Text("Bot @${settings.botName} · ${settings.chatId}") },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Appearance")
            ListItem(
                modifier = Modifier.clickable { vm.setDynamicColor(!settings.dynamicColor) },
                leadingContent = { Icon(Icons.Default.Palette, null) },
                headlineContent = { Text("Dynamic color") },
                supportingContent = { Text("Use colors from your wallpaper (Material You)") },
                trailingContent = { Switch(checked = settings.dynamicColor, onCheckedChange = vm::setDynamicColor) },
            )
            val modes = listOf("System", "Light", "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                modes.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = settings.themeMode == i,
                        onClick = { vm.setThemeMode(i) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Sync")
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { vm.syncNow() },
                leadingContent = { Icon(Icons.Default.CloudSync, null) },
                headlineContent = { Text("Save index now") },
                supportingContent = {
                    Text(
                        when (val st = sync) {
                            SyncState.Syncing -> "Saving…"
                            is SyncState.Failed -> "Last save failed: ${st.message}"
                            SyncState.Idle -> if (settings.lastSyncAt > 0) {
                                "Last saved " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSyncAt))
                            } else "The folder structure is pinned in your channel"
                        },
                        color = if (sync is SyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { confirm = "restore" },
                leadingContent = { Icon(Icons.Default.CloudDownload, null) },
                headlineContent = { Text("Restore from Telegram") },
                supportingContent = { Text("Replace this device's index with the pinned one") },
            )
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { vm.clearCache() },
                leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                headlineContent = { Text("Clear cache") },
                supportingContent = { Text("Delete previews and streamed media stored on this phone") },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { confirm = "signout" },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Your files stay in the channel; connect again to get them back") },
            )
        }
    }

    when (confirm) {
        "restore" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restore from Telegram?") },
            text = { Text("Changes on this device that weren't saved yet will be lost.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.restore() }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "signout" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Disconnect?") },
            text = { Text("The index is saved to the channel first. Running transfers are cancelled.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.signOut(onSignedOut) }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
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
