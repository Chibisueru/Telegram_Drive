package io.github.chibisueru.telegramdrive.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import coil3.compose.AsyncImage
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.util.FileKind
import io.github.chibisueru.telegramdrive.util.formatDate
import io.github.chibisueru.telegramdrive.util.formatSize
import io.github.chibisueru.telegramdrive.util.kindOf

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.PDF -> Icons.Default.PictureAsPdf
    FileKind.TEXT -> Icons.Default.Description
    FileKind.ARCHIVE -> Icons.Default.FolderZip
    FileKind.APK -> Icons.Default.Android
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun kindColors(kind: FileKind?): Pair<Color, Color> {
    val s = MaterialTheme.colorScheme
    return when (kind) {
        null -> s.primaryContainer to s.onPrimaryContainer // folder
        FileKind.IMAGE, FileKind.VIDEO -> s.tertiaryContainer to s.onTertiaryContainer
        FileKind.PDF -> s.errorContainer to s.onErrorContainer
        else -> s.secondaryContainer to s.onSecondaryContainer
    }
}

/** Shows the photo/video thumbnail if one was uploaded, otherwise a tinted type icon. */
@Composable
fun FileVisual(file: FileEntity, modifier: Modifier, iconSize: Int = 24) {
    val kind = kindOf(file.mimeType, file.name)
    val (bg, fg) = kindColors(kind)
    val context = LocalContext.current
    val thumb by produceState<java.io.File?>(null, file.id, file.thumbFileId) {
        value = if (file.thumbFileId != null) context.container.files.thumbnail(file) else null
    }
    Box(modifier.background(bg), contentAlignment = Alignment.Center) {
        if (thumb != null) {
            AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (kind == FileKind.VIDEO) {
                Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size((iconSize * 1.2f).dp))
            }
        } else {
            Icon(kind.icon(), null, tint = fg, modifier = Modifier.size(iconSize.dp))
        }
    }
}

@Composable
private fun FolderVisual(modifier: Modifier, iconSize: Int = 24) {
    val (bg, fg) = kindColors(null)
    Box(modifier.background(bg), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Folder, null, tint = fg, modifier = Modifier.size(iconSize.dp))
    }
}

private fun subtitle(item: DriveItem) = when (item) {
    is DriveItem.Folder -> "Folder · ${formatDate(item.folder.createdAt)}"
    is DriveItem.File -> "${formatSize(item.file.size)} · ${formatDate(item.file.createdAt)}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveListRow(item: DriveItem, onClick: () -> Unit, onMore: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onMore),
        leadingContent = {
            val m = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            when (item) {
                is DriveItem.Folder -> FolderVisual(m)
                is DriveItem.File -> FileVisual(item.file, m)
            }
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = { Text(subtitle(item), maxLines = 1) },
        trailingContent = {
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More options") }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveGridCell(item: DriveItem, onClick: () -> Unit, onMore: () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onMore),
    ) {
        val visual = Modifier.fillMaxWidth().aspectRatio(1.25f)
        when (item) {
            is DriveItem.Folder -> FolderVisual(visual, 40)
            is DriveItem.File -> FileVisual(item.file, visual, 40)
        }
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    if (item is DriveItem.File) formatSize(item.file.size) else "Folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More options") }
        }
    }
}

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    ListItem(
        modifier = Modifier.combinedClickableCompat(onClick),
        leadingContent = { Icon(icon, null, tint = tint) },
        headlineContent = { Text(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** Actions for a long-pressed or "⋮"-tapped item, shown inside a bottom sheet. */
@Composable
fun ItemActions(
    item: DriveItem,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val m = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            when (item) {
                is DriveItem.Folder -> FolderVisual(m)
                is DriveItem.File -> FileVisual(item.file, m)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SheetAction(if (item is DriveItem.Folder) Icons.Default.Folder else Icons.Default.Visibility, "Open", onOpen)
        if (item is DriveItem.File) SheetAction(Icons.Default.Download, "Download to phone", onDownload)
        SheetAction(Icons.Default.Edit, "Rename", onRename)
        SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move", onMove)
        SheetAction(Icons.Default.Delete, "Delete", onDelete, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // Pre-select the name without its extension, like most file managers do.
    val selectEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, selectEnd))) }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank() && '/' !in value.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun ConfirmDeleteDialog(item: DriveItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null) },
        title = { Text("Delete “${item.name}”?") },
        text = {
            Text(
                if (item is DriveItem.Folder) "The folder and everything inside it will be removed from your drive and the Telegram channel."
                else "The file will be removed from your drive and the Telegram channel."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lets the user browse the folder tree and pick a destination. */
@Composable
fun MoveDialog(item: DriveItem, folders: List<FolderEntity>, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    var location by remember { mutableStateOf<String?>(null) }
    val byId = remember(folders) { folders.associateBy { it.id } }
    val children = remember(folders, location) {
        folders.filter { it.parentId == location && it.id != item.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    val current = location?.let { byId[it] }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${item.name}”") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) {
                        IconButton(onClick = { location = current.parentId }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    }
                    Text(current?.name ?: "My Drive", style = MaterialTheme.typography.titleSmall)
                }
                HorizontalDivider()
                if (children.isEmpty()) {
                    Text(
                        "No subfolders",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(children, key = { it.id }) { f ->
                        ListItem(
                            modifier = Modifier.combinedClickableCompat { location = f.id },
                            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(location) }) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TransfersList(transfers: List<Transfer>, onCancel: (Transfer) -> Unit, onClear: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transfers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (transfers.any { !it.active }) TextButton(onClick = onClear) { Text("Clear finished") }
        }
        if (transfers.isEmpty()) {
            Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(transfers, key = { it.id }) { t ->
                ListItem(
                    leadingContent = {
                        val failed = t.error != null || t.state == WorkInfo.State.FAILED || t.state == WorkInfo.State.CANCELLED
                        val done = t.state == WorkInfo.State.SUCCEEDED && t.error == null
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
                            Text(
                                when {
                                    t.error != null -> t.error
                                    t.state == WorkInfo.State.SUCCEEDED -> if (t.upload) "Uploaded" else "Saved to Downloads"
                                    t.state == WorkInfo.State.CANCELLED -> "Cancelled"
                                    t.state == WorkInfo.State.RUNNING -> (if (t.upload) "Uploading" else "Downloading") +
                                        (t.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                                    else -> "Waiting…"
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (t.active) {
                                val p = t.progress
                                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    },
                    trailingContent = {
                        if (t.active) IconButton(onClick = { onCancel(t) }) { Icon(Icons.Default.Close, "Cancel") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
