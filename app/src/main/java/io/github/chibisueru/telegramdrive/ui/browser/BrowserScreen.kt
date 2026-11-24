package io.github.chibisueru.telegramdrive.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.SortMode
import io.github.chibisueru.telegramdrive.data.TRASH_DAYS
import io.github.chibisueru.telegramdrive.data.ViewMode
import io.github.chibisueru.telegramdrive.ui.appViewModel
import kotlinx.coroutines.launch

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val item: DriveItem) : Dialog
    data class Move(val items: List<DriveItem>) : Dialog
    data class DeleteForever(val items: List<DriveItem>) : Dialog
    data object EmptyTrash : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onOpenFile: (String) -> Unit) {
    val vm = appViewModel { c, _ -> BrowserViewModel(c) }
    val items by vm.items.collectAsStateWithLifecycle()
    val path by vm.path.collectAsStateWithLifecycle()
    val folderId by vm.currentFolderId.collectAsStateWithLifecycle()
    val special by vm.special.collectAsStateWithLifecycle()
    val searching by vm.searchActive.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val allFolders by vm.allFolders.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val currentSort by vm.currentSort.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var actionsFor by remember { mutableStateOf<DriveItem?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    val viewMode = settings?.viewMode ?: ViewMode.LIST
    val inTrash = special == Special.TRASH
    val selecting = selection.isNotEmpty()
    val atRoot = folderId == null && special == null && !searching

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            val result = snackbar.showSnackbar(e.message, actionLabel = e.actionLabel, withDismissAction = e.actionLabel != null)
            if (result == SnackbarResult.ActionPerformed) e.action?.invoke()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.upload(uris)
        if (uris.isNotEmpty() &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && searching) { vm.setSearchActive(false) }
    BackHandler(enabled = !selecting && !searching && (folderId != null || special != null)) { vm.goUp() }

    fun open(item: DriveItem) = when {
        inTrash -> actionsFor = item // trashed items can only be restored or deleted
        item is DriveItem.Folder -> vm.openFolder(item.id)
        else -> onOpenFile(item.id)
    }
    /** Tap: toggles while selecting, opens otherwise. Long press: selects. */
    fun onClick(item: DriveItem) = if (selecting) vm.toggleSelected(item) else open(item)
    fun onLongClick(item: DriveItem) = vm.toggleSelected(item)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // The bottom navigation bar (in MainScreen) already handles the bottom inset.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                if (selecting) {
                    SelectionBar(
                        count = selection.size,
                        inTrash = inTrash,
                        onClose = vm::clearSelection,
                        onSelectAll = vm::selectAll,
                        onAction = { action ->
                            val chosen = vm.selectedItems()
                            when (action) {
                                BulkAction.DOWNLOAD -> vm.download(chosen)
                                BulkAction.STAR -> vm.setStarred(chosen, !chosen.all { it.starred })
                                BulkAction.MOVE -> dialog = Dialog.Move(chosen)
                                BulkAction.TRASH -> vm.trash(chosen)
                                BulkAction.RESTORE -> vm.restore(chosen)
                                BulkAction.DELETE_FOREVER -> dialog = Dialog.DeleteForever(chosen)
                            }
                        },
                        allStarred = vm.selectedItems().let { it.isNotEmpty() && it.all { i -> i.starred } },
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    TopAppBar(
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            when {
                                searching -> IconButton(onClick = { vm.setSearchActive(false) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                                }
                                folderId != null || special != null -> IconButton(onClick = { vm.goUp() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                                }
                            }
                        },
                        title = {
                            if (searching) {
                                val focus = remember { FocusRequester() }
                                TextField(
                                    value = query,
                                    onValueChange = { vm.query.value = it },
                                    placeholder = { Text("Search your drive") },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                )
                                LaunchedEffect(Unit) { focus.requestFocus() }
                            } else {
                                Text(
                                    special?.title ?: path.lastOrNull()?.name ?: "My Drive",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            if (searching) {
                                if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Clear") }
                                return@TopAppBar
                            }
                            if (inTrash) {
                                if (!items.isNullOrEmpty()) {
                                    TextButton(onClick = { dialog = Dialog.EmptyTrash }) { Text("Empty trash") }
                                }
                                return@TopAppBar
                            }
                            IconButton(onClick = { vm.setSearchActive(true) }) { Icon(Icons.Default.Search, "Search") }
                            Box {
                                IconButton(onClick = { showViewMenu = true }) { Icon(viewMode.icon(), "View: ${viewMode.label()}") }
                                DropdownMenu(expanded = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                                    ViewMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label()) },
                                            leadingIcon = { Icon(mode.icon(), null) },
                                            trailingIcon = { if (viewMode == mode) Icon(Icons.Default.Check, null) },
                                            onClick = { vm.setViewMode(mode); showViewMenu = false },
                                        )
                                    }
                                }
                            }
                            if (special != Special.RECENT) {
                                Box {
                                    IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                        Text(
                                            if (special == null && !searching) "Sort this folder" else "Sort this view",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        )
                                        SortMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(mode.label()) },
                                                trailingIcon = { if (currentSort == mode) Icon(Icons.Default.Check, null) },
                                                onClick = { vm.setSort(mode); showSortMenu = false },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                when {
                    !selecting && !searching && path.isNotEmpty() && special == null ->
                        Breadcrumbs(path.map { it.id to it.name }, onClick = { vm.openFolder(it) })
                    !selecting && atRoot -> QuickAccess(onOpen = vm::openSpecial)
                    inTrash && !selecting -> Text(
                        "Items in the trash are deleted permanently after $TRASH_DAYS days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            if (!searching && !selecting && special == null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateSheet = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New") },
                    expanded = scrollBehavior.state.contentOffset > -50f,
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = Triple(folderId to special, searching, viewMode),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(padding),
            label = "folder",
        ) { (_, _, mode) ->
            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(searching = searching, special = special)
                mode == ViewMode.LIST || mode == ViewMode.COMPACT -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list, key = { it.id }) { item ->
                        Selectable(item.id in selection) {
                            if (mode == ViewMode.LIST) DriveListRow(item, onClick = { onClick(item) }, onMore = { actionsFor = item }, onLongClick = { onLongClick(item) })
                            else DriveCompactRow(item, onClick = { onClick(item) }, onMore = { actionsFor = item }, onLongClick = { onLongClick(item) })
                        }
                    }
                }
                else -> {
                    val (cell, gap) = when (mode) {
                        ViewMode.GALLERY -> 110.dp to 3.dp
                        ViewMode.LARGE_GRID -> 260.dp to 16.dp
                        else -> 160.dp to 12.dp
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(cell),
                        contentPadding = PaddingValues(start = gap, end = gap, top = gap, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.id }) { item ->
                            Selectable(item.id in selection) {
                                if (mode == ViewMode.GALLERY) DriveGalleryTile(item, onClick = { onClick(item) }, onMore = { actionsFor = item }, onLongClick = { onLongClick(item) })
                                else DriveGridCell(item, onClick = { onClick(item) }, onMore = { actionsFor = item }, onLongClick = { onLongClick(item) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }, sheetState = state) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                SheetAction(Icons.Default.UploadFile, "Upload files", onClick = {
                    showCreateSheet = false
                    context.container.appLock.allowExternalActivity()
                    picker.launch(arrayOf("*/*"))
                })
                SheetAction(Icons.Default.CreateNewFolder, "New folder", onClick = {
                    showCreateSheet = false
                    dialog = Dialog.NewFolder
                })
            }
        }
    }

    actionsFor?.let { item ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun act(block: () -> Unit) {
            scope.launch { state.hide() }.invokeOnCompletion { actionsFor = null; block() }
        }
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, sheetState = state) {
            ItemActions(
                item = item,
                inTrash = inTrash,
                onOpen = { act { open(item) } },
                onDownload = { act { vm.download(listOf(item)) } },
                onRename = { act { dialog = Dialog.Rename(item) } },
                onMove = { act { dialog = Dialog.Move(listOf(item)) } },
                onStar = { act { vm.setStarred(listOf(item), !item.starred) } },
                onTrash = { act { vm.trash(listOf(item)) } },
                onRestore = { act { vm.restore(listOf(item)) } },
                onDeleteForever = { act { dialog = Dialog.DeleteForever(listOf(item)) } },
            )
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog("New folder", "", "Create", onDismiss = { dialog = null }) {
            vm.createFolder(it); dialog = null
        }
        is Dialog.Rename -> NameDialog("Rename", d.item.name, "Rename", onDismiss = { dialog = null }) {
            vm.rename(d.item, it); dialog = null
        }
        is Dialog.Move -> MoveDialog(
            title = if (d.items.size == 1) "Move “${d.items[0].name}”" else "Move ${d.items.size} items",
            folders = allFolders,
            exclude = d.items.filterIsInstance<DriveItem.Folder>().map { it.id }.toSet(),
            onDismiss = { dialog = null },
        ) { vm.move(d.items, it); dialog = null }
        is Dialog.DeleteForever -> DeleteForeverDialog(
            label = if (d.items.size == 1) "“${d.items[0].name}”" else "${d.items.size} items",
            onDismiss = { dialog = null },
        ) { vm.deleteForever(d.items); dialog = null }
        Dialog.EmptyTrash -> DeleteForeverDialog(label = "everything in the trash", onDismiss = { dialog = null }) {
            vm.emptyTrash(); dialog = null
        }
        null -> Unit
    }
}

private enum class BulkAction { DOWNLOAD, STAR, MOVE, TRASH, RESTORE, DELETE_FOREVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    inTrash: Boolean,
    allStarred: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAction: (BulkAction) -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    TopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancel selection") } },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }
            if (inTrash) {
                IconButton(onClick = { onAction(BulkAction.RESTORE) }) { Icon(Icons.Default.RestoreFromTrash, "Restore") }
                IconButton(onClick = { onAction(BulkAction.DELETE_FOREVER) }) { Icon(Icons.Default.DeleteForever, "Delete permanently") }
            } else {
                IconButton(onClick = { onAction(BulkAction.DOWNLOAD) }) { Icon(Icons.Default.Download, "Download") }
                IconButton(onClick = { onAction(BulkAction.STAR) }) {
                    Icon(if (allStarred) Icons.Default.StarOutline else Icons.Default.Star, if (allStarred) "Remove from Favorites" else "Add to Favorites")
                }
                IconButton(onClick = { onAction(BulkAction.MOVE) }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move") }
                IconButton(onClick = { onAction(BulkAction.TRASH) }) { Icon(Icons.Default.Delete, "Move to trash") }
            }
        },
    )
}

/** Tints a selected item and marks it with a check. */
@Composable
private fun Selectable(selected: Boolean, content: @Composable BoxScope.() -> Unit) {
    Box {
        content()
        if (selected) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(6.dp).size(22.dp)) {
                Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Shortcuts shown at the top of "My Drive". */
@Composable
private fun QuickAccess(onOpen: (Special) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            Special.FAVORITES to Icons.Default.Star,
            Special.RECENT to Icons.Default.History,
            Special.TRASH to Icons.Default.Delete,
        ).forEach { (special, icon) ->
            AssistChip(
                onClick = { onOpen(special) },
                label = { Text(special.title) },
                leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun Breadcrumbs(path: List<Pair<String, String>>, onClick: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            TextButton(onClick = { onClick(null) }) { Text("My Drive") }
        }
        itemsIndexed(path) { i, (id, name) ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onClick(id) }, enabled = i != path.lastIndex) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, special: Special?) {
    val (icon: ImageVector, title, text) = when {
        searching -> Triple(Icons.Default.Search, "No matches", "Try a different name.")
        special == Special.FAVORITES -> Triple(Icons.Default.StarOutline, "No favorites yet", "Long-press an item, or use its ⋮ menu, to add it to Favorites.")
        special == Special.RECENT -> Triple(Icons.Default.History, "Nothing opened yet", "Files you open show up here.")
        special == Special.TRASH -> Triple(Icons.Default.DeleteSweep, "The trash is empty", "Deleted items stay here for $TRASH_DAYS days.")
        else -> Triple(Icons.Default.CloudQueue, "This folder is empty", "Tap “New” to upload files or create a folder.")
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun ViewMode.label() = when (this) {
    ViewMode.LIST -> "List"
    ViewMode.COMPACT -> "Compact list"
    ViewMode.GRID -> "Grid"
    ViewMode.LARGE_GRID -> "Large grid"
    ViewMode.GALLERY -> "Gallery"
}

private fun ViewMode.icon() = when (this) {
    ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
    ViewMode.COMPACT -> Icons.Default.ViewHeadline
    ViewMode.GRID -> Icons.Default.GridView
    ViewMode.LARGE_GRID -> Icons.Default.ViewModule
    ViewMode.GALLERY -> Icons.Default.Apps
}

private fun SortMode.label() = when (this) {
    SortMode.NAME -> "Name (A–Z)"
    SortMode.NEWEST -> "Newest first"
    SortMode.OLDEST -> "Oldest first"
    SortMode.LARGEST -> "Largest first"
    SortMode.SMALLEST -> "Smallest first"
    SortMode.TYPE -> "File type"
}
