package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.TooltipArea
import androidx.compose.ui.input.pointer.isCtrlPressed as isCtrlDown
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.data.SortMode
import io.github.chibisueru.telegramdrive.desktop.data.TRASH_DAYS
import io.github.chibisueru.telegramdrive.desktop.data.ViewMode
import io.github.chibisueru.telegramdrive.desktop.data.formatDate
import io.github.chibisueru.telegramdrive.desktop.data.formatSize

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowserScreen(c: AppContainer, state: BrowserState) {
    val idx by c.drive.index.collectAsState()
    val settings by c.settings.flow.collectAsState()
    val items = remember(idx, settings, state.folderId, state.special, state.query) { state.items(idx, settings) }
    val path = remember(idx, state.folderId) { state.path(idx) }
    val selected = items.filter { it.id in state.selection }
    val inTrash = state.special == Special.TRASH
    val windowInfo = LocalWindowInfo.current
    val focus = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }

    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DriveItem?>(null) }
    var moving by remember { mutableStateOf<List<DriveItem>?>(null) }
    var deleting by remember { mutableStateOf<List<DriveItem>?>(null) }
    var emptyingTrash by remember { mutableStateOf(false) }
    var lastClickId by remember { mutableStateOf<String?>(null) }
    var lastClickAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(m.text, m.actionLabel, withDismissAction = m.actionLabel == null)
        if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
        state.message = null
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun click(item: DriveItem) {
        focus.requestFocus()
        val mods = windowInfo.keyboardModifiers
        val now = System.currentTimeMillis()
        when {
            mods.isCtrlDown -> {
                state.selection = if (item.id in state.selection) state.selection - item.id else state.selection + item.id
                state.anchor = item.id
            }
            mods.isShiftPressed && state.anchor != null -> {
                val a = items.indexOfFirst { it.id == state.anchor }
                val b = items.indexOf(item)
                if (a >= 0 && b >= 0) state.selection = items.subList(minOf(a, b), maxOf(a, b) + 1).map { it.id }.toSet()
            }
            lastClickId == item.id && now - lastClickAt < 450 -> {
                state.selection = emptySet()
                state.open(item, items)
            }
            else -> {
                state.selection = setOf(item.id)
                state.anchor = item.id
            }
        }
        lastClickId = item.id
        lastClickAt = now
    }

    fun targets(item: DriveItem) = if (item.id in state.selection) selected else listOf(item)

    fun menuFor(item: DriveItem): List<ContextMenuItem> {
        val list = targets(item)
        if (item.id !in state.selection) state.selection = setOf(item.id)
        if (inTrash) return listOf(
            ContextMenuItem("Restore") { state.restore(list) },
            ContextMenuItem("Delete forever…") { deleting = list },
        )
        return buildList {
            if (list.size == 1) add(ContextMenuItem("Open") { state.open(item, items) })
            if (list.any { it is DriveItem.Doc }) add(ContextMenuItem("Download") { state.download(list) })
            if (list.size == 1) add(ContextMenuItem("Rename…") { renaming = item })
            add(ContextMenuItem("Move to…") { moving = list })
            if (list.all { it.starred }) add(ContextMenuItem("Remove from Favorites") { state.setStarred(list, false) })
            else add(ContextMenuItem("Add to Favorites") { state.setStarred(list, true) })
            if (state.searching || state.special != null) {
                val parent = (item as? DriveItem.Doc)?.file?.folderId ?: (item as? DriveItem.Dir)?.folder?.parentId
                if (list.size == 1) add(ContextMenuItem("Show in folder") { state.openFolder(parent) })
            }
            add(ContextMenuItem("Move to trash") { state.trash(list) })
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.isCtrlPressed && e.key == Key.A -> { state.selection = items.map { it.id }.toSet(); true }
                    e.key == Key.Escape -> { if (state.selection.isNotEmpty()) state.selection = emptySet() else state.goUp(idx); true }
                    e.key == Key.Backspace -> { state.goUp(idx); true }
                    e.key == Key.Enter && selected.size == 1 -> { state.open(selected[0], items); true }
                    e.key == Key.Delete && selected.isNotEmpty() -> { if (inTrash) deleting = selected else state.trash(selected); true }
                    e.key == Key.F2 && selected.size == 1 && !inTrash -> { renaming = selected[0]; true }
                    else -> false
                }
            },
        ) {
            // ---- Top bar ----
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val canGoUp = state.folderId != null || state.special != null || state.searching
                IconButton(onClick = { state.goUp(idx) }, enabled = canGoUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        state.searching -> Title("Search results")
                        state.special != null -> Title(state.special!!.title)
                        else -> {
                            Crumb("My Drive", path.isEmpty()) { state.openFolder(null) }
                            path.forEachIndexed { i, f ->
                                Text("›", Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Crumb(f.name, i == path.lastIndex) { state.openFolder(f.id) }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    state.query, { state.query = it; state.selection = emptySet() },
                    placeholder = { Text("Search") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { state.query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.width(260.dp),
                )
                ViewMenu(settings.viewMode) { m -> c.settings.update { it.copy(viewMode = m) } }
                if (state.special != Special.RECENT && !inTrash) SortMenu(state.sortOf(settings), state::setSort)
                if (state.special == null && !state.searching) {
                    OutlinedButton(onClick = { newFolder = true }) {
                        Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp)); Text("  New folder")
                    }
                    UploadButton(
                        onFiles = { Pickers.pickFiles().takeIf { it.isNotEmpty() }?.let { upload(c, state, it) } },
                        onFolder = { Pickers.pickFolder("Upload a folder")?.let { upload(c, state, listOf(it)) } },
                    )
                }
            }

            // ---- Quick access ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("My Drive", Icons.Filled.Folder, state.special == null && !state.searching) { state.openFolder(null) }
                Chip("Favorites", Icons.Filled.Star, state.special == Special.FAVORITES) { state.openSpecial(Special.FAVORITES) }
                Chip("Recent", Icons.Filled.History, state.special == Special.RECENT) { state.openSpecial(Special.RECENT) }
                Chip("Trash", Icons.Filled.Delete, inTrash) { state.openSpecial(Special.TRASH) }
                Spacer(Modifier.weight(1f))
                if (inTrash) {
                    Text("Items are deleted for good after $TRASH_DAYS days", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { emptyingTrash = true }, enabled = items.isNotEmpty()) { Text("Empty trash") }
                }
            }

            // ---- Selection bar ----
            if (selected.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { state.selection = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                        Text("${selected.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Action(Icons.Filled.SelectAll, "Select all") { state.selection = items.map { it.id }.toSet() }
                        if (inTrash) {
                            Action(Icons.Filled.Restore, "Restore") { state.restore(selected) }
                            Action(Icons.Filled.DeleteForever, "Delete forever") { deleting = selected }
                        } else {
                            if (selected.any { it is DriveItem.Doc }) Action(Icons.Filled.Download, "Download") { state.download(selected) }
                            Action(Icons.AutoMirrored.Filled.DriveFileMove, "Move to…") { moving = selected }
                            if (selected.all { it.starred }) Action(Icons.Filled.Star, "Remove from Favorites") { state.setStarred(selected, false) }
                            else Action(Icons.Outlined.StarOutline, "Add to Favorites") { state.setStarred(selected, true) }
                            Action(Icons.Outlined.Delete, "Move to trash") { state.trash(selected) }
                        }
                    }
                }
            }

            // ---- Content ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (items.isEmpty()) {
                    when {
                        state.searching -> EmptyState(Icons.Filled.Search, "Nothing found", "No file or folder name contains “${state.query.trim()}”.")
                        state.special == Special.FAVORITES -> EmptyState(Icons.Outlined.StarOutline, "No favorites yet", "Right-click a file or folder and choose “Add to Favorites”.")
                        state.special == Special.RECENT -> EmptyState(Icons.Filled.History, "Nothing opened yet", "Files you open on this computer show up here.")
                        inTrash -> EmptyState(Icons.Outlined.Delete, "Trash is empty", "Deleted items stay here for $TRASH_DAYS days.")
                        else -> EmptyState(Icons.Outlined.CloudUpload, "This folder is empty", "Drag files or folders here, or use Upload.")
                    }
                } else when (settings.viewMode) {
                    ViewMode.LIST, ViewMode.COMPACT -> {
                        val compact = settings.viewMode == ViewMode.COMPACT
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
                            item { ListHeader(showLocation = false) }
                            items(items, key = { it.id }) { item ->
                                ContextMenuArea(items = { menuFor(item) }) {
                                    ListRow(c, item, item.id in state.selection, compact, inTrash) { click(item) }
                                }
                            }
                        }
                    }
                    ViewMode.GRID, ViewMode.LARGE_GRID -> {
                        val large = settings.viewMode == ViewMode.LARGE_GRID
                        LazyVerticalGrid(
                            GridCells.Adaptive(if (large) 220.dp else 150.dp),
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(items, key = { it.id }) { item ->
                                ContextMenuArea(items = { menuFor(item) }) {
                                    GridCell(c, item, item.id in state.selection) { click(item) }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    // ---- Dialogs ----
    if (newFolder) TextInputDialog("New folder", "", "Folder name", "Create", { newFolder = false }) { name ->
        c.drive.createFolder(state.folderId, name); newFolder = false
    }
    renaming?.let { item ->
        TextInputDialog("Rename", item.name, "Name", "Rename", { renaming = null }) { name ->
            c.drive.rename(item.id, name); renaming = null
        }
    }
    moving?.let { list ->
        val exclude = list.filterIsInstance<DriveItem.Dir>().flatMap { c.drive.descendantsOf(it.id, idx.folders) }.toSet()
        FolderPickerDialog(
            title = if (list.size == 1) "Move “${list[0].name}”" else "Move ${list.size} items",
            folders = idx.folders.filter { it.deletedAt == null },
            confirmLabel = "Move here",
            exclude = exclude,
            onDismiss = { moving = null },
        ) { target -> state.move(list, target); moving = null }
    }
    deleting?.let { list ->
        ConfirmDialog(
            "Delete forever?",
            (if (list.size == 1) "“${list[0].name}”" else "${list.size} items") + " will be deleted from Telegram. This can't be undone.",
            "Delete forever", { deleting = null },
        ) { state.deleteForever(list) }
    }
    if (emptyingTrash) ConfirmDialog(
        "Empty the trash?", "Everything in the trash will be deleted from Telegram. This can't be undone.",
        "Empty trash", { emptyingTrash = false },
    ) { state.emptyTrash() }
}

fun upload(c: AppContainer, state: BrowserState, files: List<java.io.File>) {
    val target = if (state.special == null && !state.searching) state.folderId else null
    c.transfers.upload(files, target)
    val n = files.size
    state.message = UiMessage("Uploading $n item${if (n == 1) "" else "s"}. Progress is in Transfers.")
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = if (current) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun Chip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) },
        leadingIcon = { Icon(if (selected) Icons.Filled.Check else icon, null, Modifier.size(18.dp)) })
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipArea(tooltip = {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface) {
            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodySmall)
        }
    }) {
        IconButton(onClick = onClick) { Icon(icon, label) }
    }
}

@Composable
private fun ViewMenu(current: ViewMode, onPick: (ViewMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Action(Icons.Outlined.GridView, "View") { open = true }
        DropdownMenu(open, { open = false }) {
            ViewMode.entries.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { onPick(m); open = false },
                    leadingIcon = { if (m == current) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) })
            }
        }
    }
}

@Composable
private fun SortMenu(current: SortMode, onPick: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Action(Icons.AutoMirrored.Filled.Sort, "Sort this folder") { open = true }
        DropdownMenu(open, { open = false }) {
            Text("Sort this folder", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SortMode.entries.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { onPick(m); open = false },
                    leadingIcon = { if (m == current) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) })
            }
        }
    }
}

@Composable
private fun UploadButton(onFiles: () -> Unit, onFolder: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }) { Icon(Icons.Filled.Upload, null, Modifier.size(18.dp)); Text("  Upload") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Files…") }, onClick = { open = false; onFiles() },
                leadingIcon = { Icon(Icons.Filled.Upload, null) })
            DropdownMenuItem(text = { Text("Folder…") }, onClick = { open = false; onFolder() },
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) })
        }
    }
}

@Composable
private fun ListHeader(showLocation: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            HeaderText("Name", Modifier.weight(1f))
            HeaderText("Added", Modifier.width(130.dp))
            HeaderText("Size", Modifier.width(90.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun HeaderText(text: String, modifier: Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ListRow(c: AppContainer, item: DriveItem, selected: Boolean, compact: Boolean, inTrash: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier.fillMaxWidth().height(if (compact) 36.dp else 52.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp)) { ItemIcon(c, item, if (compact) 22.dp else 34.dp) }
        Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
        if (item.starred) Icon(Icons.Filled.Star, "Favorite", Modifier.padding(horizontal = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        val date = when (item) {
            is DriveItem.Dir -> if (inTrash) item.folder.deletedAt else item.folder.createdAt
            is DriveItem.Doc -> if (inTrash) item.file.deletedAt else item.file.createdAt
        }
        Text(date?.takeIf { it > 0 }?.let(::formatDate) ?: "", Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text((item as? DriveItem.Doc)?.file?.size?.let(::formatSize) ?: "—", Modifier.width(90.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemIcon(c: AppContainer, item: DriveItem, size: androidx.compose.ui.unit.Dp) {
    when (item) {
        is DriveItem.Dir -> Icon(Icons.Filled.Folder, null, Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
        is DriveItem.Doc -> {
            val thumb = thumbnailOf(c.files, item.file)
            if (thumb != null) {
                Image(thumb, null, Modifier.size(size).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            } else {
                Icon(iconFor(item.file), null, Modifier.size(size), tint = kindColor(item.file))
            }
        }
    }
}

@Composable
private fun GridCell(c: AppContainer, item: DriveItem, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.25f).padding(8.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = (item as? DriveItem.Doc)?.let { thumbnailOf(c.files, it.file) }
                when {
                    thumb != null -> Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    item is DriveItem.Dir -> Icon(Icons.Filled.Folder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    item is DriveItem.Doc -> Icon(iconFor(item.file), null, Modifier.size(48.dp), tint = kindColor(item.file))
                }
                if (item.starred) Icon(Icons.Filled.Star, "Favorite", Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary)
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(
                    when (item) {
                        is DriveItem.Dir -> "Folder"
                        is DriveItem.Doc -> formatSize(item.file.size)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
