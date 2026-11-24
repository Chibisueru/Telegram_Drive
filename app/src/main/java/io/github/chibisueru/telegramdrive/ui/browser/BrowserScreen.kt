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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.chibisueru.telegramdrive.data.SortMode
import io.github.chibisueru.telegramdrive.ui.appViewModel
import kotlinx.coroutines.launch

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val item: DriveItem) : Dialog
    data class Move(val item: DriveItem) : Dialog
    data class Delete(val item: DriveItem) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onOpenFile: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { c, _ -> BrowserViewModel(c) }
    val items by vm.items.collectAsStateWithLifecycle()
    val path by vm.path.collectAsStateWithLifecycle()
    val folderId by vm.currentFolderId.collectAsStateWithLifecycle()
    val searching by vm.searchActive.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val allFolders by vm.allFolders.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var actionsFor by remember { mutableStateOf<DriveItem?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val grid = settings?.gridView == true
    val activeTransfers = transfers.count { it.active }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.upload(uris)
        if (uris.isNotEmpty() &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = searching) { vm.setSearchActive(false) }
    BackHandler(enabled = !searching && folderId != null) { vm.goUp() }

    fun open(item: DriveItem) = when (item) {
        is DriveItem.Folder -> vm.openFolder(item.id)
        is DriveItem.File -> onOpenFile(item.id)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        when {
                            searching -> IconButton(onClick = { vm.setSearchActive(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                            }
                            folderId != null -> IconButton(onClick = { vm.goUp() }) {
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
                            Text(path.lastOrNull()?.name ?: "My Drive", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (searching) {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Clear") }
                        } else {
                            IconButton(onClick = { vm.setSearchActive(true) }) { Icon(Icons.Default.Search, "Search") }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    listOf(SortMode.NAME to "Name", SortMode.NEWEST to "Newest first", SortMode.SIZE to "Largest first")
                                        .forEach { (mode, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = {
                                                    if (settings?.sortMode == mode) Icon(Icons.Default.Check, null)
                                                    else Icon(Icons.Default.SwapVert, null, tint = Color.Transparent)
                                                },
                                                onClick = { vm.setSort(mode); showSortMenu = false },
                                            )
                                        }
                                    DropdownMenuItem(
                                        text = { Text(if (grid) "List view" else "Grid view") },
                                        leadingIcon = { Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, null) },
                                        onClick = { vm.setGrid(!grid); showSortMenu = false },
                                    )
                                }
                            }
                            IconButton(onClick = { showTransfers = true }) {
                                BadgedBox(badge = { if (activeTransfers > 0) Badge { Text("$activeTransfers") } }) {
                                    if (activeTransfers > 0) {
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.CloudQueue, "Transfers")
                                    }
                                }
                            }
                            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                        }
                    },
                )
                if (!searching && path.isNotEmpty()) {
                    Breadcrumbs(path.map { it.id to it.name }, onClick = { vm.openFolder(it) })
                }
            }
        },
        floatingActionButton = {
            if (!searching) {
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
            targetState = Triple(folderId, searching, grid),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(padding),
            label = "folder",
        ) { (_, _, isGrid) ->
            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(searching = searching)
                isGrid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.id }) { item ->
                        DriveGridCell(item, onClick = { open(item) }, onMore = { actionsFor = item })
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list, key = { it.id }) { item ->
                        DriveListRow(item, onClick = { open(item) }, onMore = { actionsFor = item })
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
                onOpen = { act { open(item) } },
                onDownload = { act { (item as? DriveItem.File)?.let { vm.download(it.file) } } },
                onRename = { act { dialog = Dialog.Rename(item) } },
                onMove = { act { dialog = Dialog.Move(item) } },
                onDelete = { act { dialog = Dialog.Delete(item) } },
            )
        }
    }

    if (showTransfers) {
        ModalBottomSheet(onDismissRequest = { showTransfers = false }) {
            TransfersList(transfers, onCancel = { vm.cancelTransfer(it.id) }, onClear = { vm.clearFinishedTransfers() })
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog("New folder", "", "Create", onDismiss = { dialog = null }) {
            vm.createFolder(it); dialog = null
        }
        is Dialog.Rename -> NameDialog("Rename", d.item.name, "Rename", onDismiss = { dialog = null }) {
            vm.rename(d.item, it); dialog = null
        }
        is Dialog.Move -> MoveDialog(d.item, allFolders, onDismiss = { dialog = null }) {
            vm.move(d.item, it); dialog = null
        }
        is Dialog.Delete -> ConfirmDeleteDialog(d.item, onDismiss = { dialog = null }) {
            vm.delete(d.item); dialog = null
        }
        null -> Unit
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
private fun EmptyState(searching: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (searching) Icons.Default.Search else Icons.Default.CloudQueue, null,
                    Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Text(
            if (searching) "No matches" else "This folder is empty",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            if (searching) "Try a different name." else "Tap “New” to upload files or create a folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
