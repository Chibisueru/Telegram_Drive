package io.github.chibisueru.telegramdrive.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.ui.browser.BrowserScreen
import io.github.chibisueru.telegramdrive.ui.browser.MoveDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.chibisueru.telegramdrive.ui.settings.SettingsScreen
import io.github.chibisueru.telegramdrive.ui.transfers.TransfersScreen
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    FILES("Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    TRANSFERS("Transfers", Icons.Filled.Download, Icons.Outlined.Download),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** The three main sections with a Material 3 navigation bar at the bottom. */
@Composable
fun MainScreen(
    requestedTab: MutableStateFlow<Tab?>,
    sharedFiles: MutableStateFlow<List<Uri>>,
    onOpenFile: (String) -> Unit,
    onSignedOut: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOfTab(Tab.FILES) }
    val requested by requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { tab = it; requestedTab.value = null }
    }
    val context = LocalContext.current
    val c = context.container
    val activeTransfers by c.transfers.dao.activeCount().collectAsStateWithLifecycle(0)
    val shared by sharedFiles.collectAsStateWithLifecycle()
    val folders by c.dao.liveFoldersFlow().collectAsStateWithLifecycle(emptyList())

    // "Share → Telegram Drive": ask where to put the files, then copy and queue them.
    if (shared.isNotEmpty()) {
        MoveDialog(
            title = if (shared.size == 1) "Upload 1 file to…" else "Upload ${shared.size} files to…",
            folders = folders,
            confirmLabel = "Upload here",
            onDismiss = { sharedFiles.value = emptyList() },
        ) { folderId ->
            val uris = shared
            sharedFiles.value = emptyList()
            tab = Tab.TRANSFERS
            Toast.makeText(context, "Preparing ${uris.size} file${if (uris.size == 1) "" else "s"}…", Toast.LENGTH_SHORT).show()
            c.scope.launch {
                val queued = runCatching { c.repository.importShared(uris, folderId) }.getOrDefault(0)
                if (queued < uris.size) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "${uris.size - queued} file(s) couldn't be read", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.TRANSFERS && activeTransfers > 0) Badge { Text("$activeTransfers") } }) {
                                Icon(if (tab == t) t.selectedIcon else t.icon, null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                when (current) {
                    Tab.FILES -> BrowserScreen(onOpenFile = onOpenFile)
                    Tab.TRANSFERS -> TransfersScreen()
                    Tab.SETTINGS -> SettingsScreen(onSignedOut = onSignedOut)
                }
            }
        }
    }
}

private fun mutableStateOfTab(initial: Tab) = androidx.compose.runtime.mutableStateOf(initial)
