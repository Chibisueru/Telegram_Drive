package io.github.chibisueru.telegramdrive.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.ui.BrowserScreen
import io.github.chibisueru.telegramdrive.desktop.ui.BrowserState
import io.github.chibisueru.telegramdrive.desktop.ui.ConfirmDialog
import io.github.chibisueru.telegramdrive.desktop.ui.Pickers
import io.github.chibisueru.telegramdrive.desktop.ui.SettingsScreen
import io.github.chibisueru.telegramdrive.desktop.ui.SetupScreen
import io.github.chibisueru.telegramdrive.desktop.ui.TgDriveTheme
import io.github.chibisueru.telegramdrive.desktop.ui.TransfersScreen
import io.github.chibisueru.telegramdrive.desktop.ui.UiMessage
import io.github.chibisueru.telegramdrive.desktop.ui.Viewer
import io.github.chibisueru.telegramdrive.desktop.ui.upload
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

enum class Tab(val label: String, val selectedIcon: ImageVector, val icon: ImageVector) {
    FILES("Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    TRANSFERS("Transfers", Icons.Filled.Download, Icons.Outlined.Download),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

fun main() {
    AppDirs.ensure()
    setLinuxWindowClass("telegram-drive-TelegramDrive")
    val c = AppContainer()
    val icon = AppContainer::class.java.getResourceAsStream("/icon.png")?.use {
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application {
        val settings by c.settings.flow.collectAsState()
        var askQuit by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(size = DpSize(1180.dp, 780.dp))
        Window(
            onCloseRequest = { if (c.transfers.activeCount > 0) askQuit = true else exitApplication() },
            title = "Telegram Drive",
            icon = icon,
            state = windowState,
        ) {
            val browser = remember { BrowserState(c) }
            var dragging by remember { mutableStateOf(false) }
            DisposableEffect(window) {
                window.minimumSize = Dimension(820, 560)
                Pickers.owner = window
                val focusListener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) = c.refreshSoon()
                }
                window.addWindowFocusListener(focusListener)
                // Drop files or folders from the file manager onto the window to upload them.
                window.dropTarget = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                    override fun dragEnter(e: DropTargetDragEvent) {
                        dragging = e.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && c.settings.current.isConfigured
                    }
                    override fun dragExit(e: DropTargetEvent) { dragging = false }
                    override fun drop(e: DropTargetDropEvent) {
                        dragging = false
                        if (!c.settings.current.isConfigured) { e.rejectDrop(); return }
                        e.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching { e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull().orEmpty()
                        e.dropComplete(files.isNotEmpty())
                        if (files.isNotEmpty()) upload(c, browser, files)
                    }
                }, true)
                onDispose { window.removeWindowFocusListener(focusListener) }
            }

            TgDriveTheme(settings.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    App(c, browser)
                    if (dragging) DropOverlay()
                }
                if (askQuit) ConfirmDialog(
                    "Quit Telegram Drive?",
                    "${c.transfers.activeCount} transfer(s) are still running and will be cancelled.",
                    "Quit", { askQuit = false },
                ) { exitApplication() }
            }
        }
    }
}

@Composable
internal fun App(c: AppContainer, browser: BrowserState) {
    val lost by c.telegram.sessionLost.collectAsState()
    var connected by remember { mutableStateOf(c.settings.current.isConfigured) }
    LaunchedEffect(lost) { if (lost) connected = false }
    if (!connected) {
        SetupScreen(c, onConnected = { browser.openFolder(null); connected = true })
        return
    }
    var tab by remember { mutableStateOf(Tab.FILES) }
    val transfers by c.transfers.list.collectAsState()
    val active = transfers.count { it.active }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.size(12.dp))
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.TRANSFERS && active > 0) Badge { Text("$active") } }) {
                                Icon(if (tab == t) t.selectedIcon else t.icon, t.label)
                            }
                        },
                        label = { Text(t.label) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.weight(1f)) { current ->
                when (current) {
                    Tab.FILES -> BrowserScreen(c, browser)
                    Tab.TRANSFERS -> TransfersScreen(c)
                    Tab.SETTINGS -> SettingsScreen(
                        c,
                        onSignedOut = { tab = Tab.FILES; connected = false },
                        onMessage = { browser.message = UiMessage(it); tab = Tab.FILES.takeIf { false } ?: tab },
                    )
                }
            }
        }
        browser.viewing?.let { (file, siblings) ->
            Viewer(
                c, file, siblings,
                onDownload = { f: DriveFile ->
                    c.transfers.download(listOf(f.id))
                    browser.message = UiMessage("Saving “${f.name}”. Progress is in Transfers.")
                },
                onClose = { browser.viewing = null },
            )
        }
    }
}

@Composable
private fun DropOverlay() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(16.dp)
            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Drop to upload to the open folder", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * On Linux the dock matches windows to their .desktop file by WM_CLASS; Java's default is the
 * main class name, which would show a generic icon. The .deb/.rpm menu entry made by jpackage is
 * named "<package>-<app>.desktop"; the Arch one sets StartupWMClass to the same value.
 */
private fun setLinuxWindowClass(name: String) {
    if (AppDirs.isWindows) return
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, name)
    }
}
