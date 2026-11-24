package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.data.FileKind
import io.github.chibisueru.telegramdrive.desktop.data.Folder
import io.github.chibisueru.telegramdrive.desktop.data.kindOf
import io.github.chibisueru.telegramdrive.desktop.telegram.TgFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

fun iconFor(file: DriveFile): ImageVector = when (kindOf(file.mimeType, file.name)) {
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.Audiotrack
    FileKind.PDF -> Icons.Filled.PictureAsPdf
    FileKind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    FileKind.ARCHIVE -> Icons.Filled.FolderZip
    FileKind.APK -> Icons.Filled.Android
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun kindColor(file: DriveFile): Color = when (kindOf(file.mimeType, file.name)) {
    FileKind.IMAGE -> Color(0xFF2E7D32)
    FileKind.VIDEO -> Color(0xFFC62828)
    FileKind.AUDIO -> Color(0xFF6A1B9A)
    FileKind.PDF -> Color(0xFFD84315)
    FileKind.ARCHIVE -> Color(0xFF8D6E63)
    else -> MaterialTheme.colorScheme.primary
}

/** Decodes an image file with Skia (JPEG, PNG, WebP, GIF, BMP). */
fun decodeImage(file: File): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()

/** Small in-memory cache of decoded thumbnails. */
object Thumbnails {
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 400
    })

    fun cached(remoteId: String): ImageBitmap? = cache[remoteId]

    suspend fun load(files: TgFiles, remoteId: String): ImageBitmap? {
        cache[remoteId]?.let { return it }
        val file = files.downloadSmall(remoteId) ?: return null
        val img = withContext(Dispatchers.IO) { decodeImage(file) } ?: return null
        cache[remoteId] = img
        return img
    }
}

@Composable
fun thumbnailOf(files: TgFiles, file: DriveFile): ImageBitmap? {
    val id = file.thumbRemoteId ?: return null
    val img by produceState(Thumbnails.cached(id), id) { if (value == null) value = Thumbnails.load(files, id) }
    return img
}

/** A dialog asking for a single line of text, e.g. a folder name. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Select the name without its extension, as file managers do.
    val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, dot))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.key == Key.Enter) { submit(); true } else false
                },
            )
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = value.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a destination folder by browsing the folder tree. */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<Folder>,
    confirmLabel: String,
    exclude: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var current by remember { mutableStateOf<String?>(null) }
    val byId = remember(folders) { folders.associateBy { it.id } }
    val children = remember(folders, current) {
        folders.filter { it.parentId == current && it.id !in exclude }.sortedBy { it.name.lowercase() }
    }
    val crumbs = remember(current, byId) {
        val out = ArrayList<Folder>(); var f = current?.let { byId[it] }
        while (f != null && out.size < 100) { out.add(0, f); f = f.parentId?.let { byId[it] } }
        out
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Icon(Icons.Outlined.Home, null, Modifier.size(18.dp).clickable { current = null })
                    crumbs.forEach { f ->
                        Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { current = f.id })
                    }
                }
                LazyColumn(Modifier.heightIn(min = 120.dp, max = 360.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    if (children.isEmpty()) item {
                        Text("No folders here", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(children, key = { it.id }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { current = f.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "Destination: " + (crumbs.lastOrNull()?.name ?: "My Drive"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
