package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.AppDirs
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.data.FileKind
import io.github.chibisueru.telegramdrive.desktop.data.canPreviewImage
import io.github.chibisueru.telegramdrive.desktop.data.formatDate
import io.github.chibisueru.telegramdrive.desktop.data.formatSize
import io.github.chibisueru.telegramdrive.desktop.data.kindOf
import io.github.chibisueru.telegramdrive.desktop.data.openWithSystem
import io.github.chibisueru.telegramdrive.desktop.data.safeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

private sealed interface Load {
    data class Progress(val value: Float) : Load
    data class Ready(val file: File) : Load
    data class Failed(val message: String) : Load
}

/**
 * Full-window viewer. Images and text open right here (←/→ steps through the folder);
 * other files open in the system's default app once they've been fetched from Telegram.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Viewer(c: AppContainer, start: DriveFile, siblings: List<DriveFile>, onDownload: (DriveFile) -> Unit, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(siblings.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) }
    val list = siblings.ifEmpty { listOf(start) }
    val file = list[index.coerceIn(0, list.lastIndex)]
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(file.id) { c.drive.markOpened(file.id) }
    fun step(d: Int) { index = (index + d).coerceIn(0, list.lastIndex) }

    Surface(
        color = Color(0xF0101114),
        modifier = Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.Escape -> { onClose(); true }
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(file.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatSize(file.size)} · ${formatDate(file.createdAt)}" + if (list.size > 1) " · ${index + 1} of ${list.size}" else "",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { onDownload(file) }) { Icon(Icons.Filled.Download, "Download", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val kind = kindOf(file.mimeType, file.name)
                when {
                    kind == FileKind.IMAGE && canPreviewImage(file.name) -> ImageView(c, file)
                    kind == FileKind.TEXT && file.size < 5_000_000 -> TextView(c, file)
                    else -> ExternalView(c, file, kind)
                }
                if (list.size > 1) {
                    NavButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", index > 0, Modifier.align(Alignment.CenterStart)) { step(-1) }
                    NavButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", index < list.lastIndex, Modifier.align(Alignment.CenterEnd)) { step(1) }
                }
            }
        }
    }
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (!enabled) return
    FilledIconButton(
        onClick = onClick, modifier = modifier.padding(16.dp).size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
    ) { Icon(icon, label) }
}

/** Fetches the whole file into TDLib's cache (reused if it's already there). */
@Composable
private fun rememberLoad(c: AppContainer, file: DriveFile, attempt: Int): Load {
    var state by remember(file.id, attempt) { mutableStateOf<Load>(Load.Progress(0f)) }
    LaunchedEffect(file.id, attempt) {
        state = try {
            Load.Ready(c.files.download(file.remoteId, priority = 32, messageId = file.messageId) { state = Load.Progress(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Download failed")
        }
    }
    return state
}

@Composable
private fun Loading(load: Load, thumb: ImageBitmap? = null, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = 0.6f) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (load) {
                is Load.Failed -> {
                    Text("Couldn't load the file", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(load.message, color = Color.White.copy(alpha = 0.7f))
                    Button(onClick = onRetry) { Text("Retry") }
                }
                is Load.Progress -> {
                    CircularProgressIndicator(progress = { load.value }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
                    Text("${(load.value * 100).toInt()} %", color = Color.White.copy(alpha = 0.8f))
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImageView(c: AppContainer, file: DriveFile) {
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    val load = rememberLoad(c, file, attempt)
    val thumb = thumbnailOf(c.files, file)
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    var decodeFailed by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(load) {
        if (load is Load.Ready) {
            bitmap = withContext(Dispatchers.IO) { decodeImage(load.file) }
            decodeFailed = bitmap == null
        }
    }
    val img = bitmap
    if (img == null) {
        if (decodeFailed) ExternalView(c, file, FileKind.IMAGE) else Loading(load, thumb) { attempt++ }
        return
    }
    var scale by remember(file.id) { mutableFloatStateOf(1f) }
    var offset by remember(file.id) { mutableStateOf(Offset.Zero) }
    fun zoom(by: Float) {
        scale = (scale * by).coerceIn(1f, 10f)
        if (scale == 1f) offset = Offset.Zero
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            img, file.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    zoom(if (dy < 0) 1.15f else 1 / 1.15f)
                }
                .pointerInput(file.id) {
                    detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
                }
                .pointerInput(file.id) {
                    detectDragGestures { change, drag -> if (scale > 1f) { change.consume(); offset += drag } }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
        Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
            IconButton(onClick = { zoom(1 / 1.4f) }) { Icon(Icons.Filled.ZoomOut, "Zoom out", tint = Color.White) }
            Text("${(scale * 100).toInt()} %", color = Color.White, modifier = Modifier.align(Alignment.CenterVertically).width(56.dp))
            IconButton(onClick = { zoom(1.4f) }) { Icon(Icons.Filled.ZoomIn, "Zoom in", tint = Color.White) }
        }
    }
}

@Composable
private fun TextView(c: AppContainer, file: DriveFile) {
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    val load = rememberLoad(c, file, attempt)
    var text by remember(file.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(load) {
        if (load is Load.Ready) text = withContext(Dispatchers.IO) { runCatching { load.file.readText() }.getOrElse { "Can't read file: ${it.message}" } }
    }
    val t = text ?: return Loading(load) { attempt++ }
    Surface(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        SelectionContainer {
            Text(
                t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}

/** Videos, music, PDFs and everything else: fetch, then hand to the system's default app. */
@Composable
private fun ExternalView(c: AppContainer, file: DriveFile, kind: FileKind) {
    val scope = rememberCoroutineScope()
    var progress by remember(file.id) { mutableStateOf<Float?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    val thumb = thumbnailOf(c.files, file)
    fun open() = scope.launch {
        error = null
        progress = 0f
        try {
            val cached = c.files.download(file.remoteId, priority = 32, messageId = file.messageId) { progress = it }
            val named = withContext(Dispatchers.IO) { namedCopy(file, cached) }
            openWithSystem(named)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open the file"
        }
        progress = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(420.dp)) {
            if (thumb != null) Image(thumb, null, Modifier.size(220.dp), contentScale = ContentScale.Fit)
            else Icon(iconFor(file), null, Modifier.size(96.dp), tint = Color.White.copy(alpha = 0.85f))
            Text(file.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val p = progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text("Fetching from Telegram… ${(p * 100).toInt()} %", color = Color.White.copy(alpha = 0.8f))
            } else {
                val label = when (kind) {
                    FileKind.VIDEO, FileKind.AUDIO -> "Play"
                    else -> "Open"
                }
                Button(onClick = { open() }) {
                    Icon(if (label == "Play") Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Text("  $label with default app")
                }
                Text("The file is fetched once and kept in the cache, so opening it again is instant.",
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { c.transfers.download(listOf(file.id)) }) { Text("Save a copy to Downloads") }
        }
    }
}

/**
 * TDLib's cache file may not carry the real name, and other apps pick their handler by
 * extension: expose it under its real name (a hard link where possible, else a copy).
 */
private fun namedCopy(file: DriveFile, cached: File): File {
    val dir = File(AppDirs.cache, "open/${file.id}").apply { mkdirs() }
    val target = File(dir, safeFileName(file.name))
    if (target.exists() && target.length() == cached.length()) return target
    target.delete()
    runCatching { Files.createLink(target.toPath(), cached.toPath()) }.onFailure { cached.copyTo(target, overwrite = true) }
    return target
}
