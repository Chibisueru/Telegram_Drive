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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
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
    val thumb by produceState<java.io.File?>(null, file.id, file.thumbRemoteId) {
        value = file.thumbRemoteId?.let { context.container.files.downloadSmall(it) }
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

private fun subtitle(item: DriveItem) = (if (item.starred) "★ " else "") + when (item) {
    is DriveItem.Folder -> "Folder · ${formatDate(item.folder.createdAt)}"
    is DriveItem.File -> "${formatSize(item.file.size)} · ${formatDate(item.file.createdAt)}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveListRow(item: DriveItem, onClick: () -> Unit, onMore: () -> Unit, onLongClick: () -> Unit = onMore) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
fun DriveGridCell(item: DriveItem, onClick: () -> Unit, onMore: () -> Unit, onLongClick: () -> Unit = onMore) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                    (if (item.starred) "★ " else "") + if (item is DriveItem.File) formatSize(item.file.size) else "Folder",
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
    inTrash: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onStar: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
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
        if (inTrash) {
            SheetAction(Icons.Default.RestoreFromTrash, "Restore", onRestore)
            SheetAction(Icons.Default.DeleteForever, "Delete permanently", onDeleteForever, tint = MaterialTheme.colorScheme.error)
            return@Column
        }
        SheetAction(if (item is DriveItem.Folder) Icons.Default.Folder else Icons.Default.Visibility, "Open", onOpen)
        if (item is DriveItem.File) SheetAction(Icons.Default.Download, "Download to phone", onDownload)
        SheetAction(
            if (item.starred) Icons.Default.StarOutline else Icons.Default.Star,
            if (item.starred) "Remove from Favorites" else "Add to Favorites",
            onStar,
        )
        SheetAction(Icons.Default.Edit, "Rename", onRename)
        SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move", onMove)
        SheetAction(Icons.Default.Delete, "Move to trash", onTrash, tint = MaterialTheme.colorScheme.error)
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
fun DeleteForeverDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, null) },
        title = { Text("Delete $label permanently?") },
        text = { Text("This removes the files from your Telegram channel too. It can't be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lets the user browse the folder tree and pick a destination. */
@Composable
fun MoveDialog(
    title: String,
    folders: List<FolderEntity>,
    onDismiss: () -> Unit,
    confirmLabel: String = "Move here",
    /** Folders that can't be a destination (the ones being moved). */
    exclude: Set<String> = emptySet(),
    onMove: (String?) -> Unit,
) {
    var location by remember { mutableStateOf<String?>(null) }
    val byId = remember(folders) { folders.associateBy { it.id } }
    val children = remember(folders, location) {
        folders.filter { it.parentId == location && it.id !in exclude }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    val current = location?.let { byId[it] }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
        confirmButton = { TextButton(onClick = { onMove(location) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dense one-line row: icon, name, size. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveCompactRow(item: DriveItem, onClick: () -> Unit, onMore: () -> Unit, onLongClick: () -> Unit = onMore) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val m = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
        when (item) {
            is DriveItem.Folder -> FolderVisual(m, 18)
            is DriveItem.File -> FileVisual(item.file, m, 18)
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        if (item is DriveItem.File) {
            Text(formatSize(item.file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More options") }
    }
}

/** A square, text-free tile for the photo-wall style gallery view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveGalleryTile(item: DriveItem, onClick: () -> Unit, onMore: () -> Unit, onLongClick: () -> Unit = onMore) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        when (item) {
            is DriveItem.Folder -> FolderVisual(Modifier.fillMaxSize(), 36)
            is DriveItem.File -> FileVisual(item.file, Modifier.fillMaxSize(), 36)
        }
        // Folders and files without a thumbnail need their name to be recognisable.
        if (item is DriveItem.Folder || (item as DriveItem.File).file.thumbRemoteId == null) {
            Text(
                item.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
