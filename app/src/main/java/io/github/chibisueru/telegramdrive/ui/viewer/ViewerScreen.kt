package io.github.chibisueru.telegramdrive.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.ui.appViewModel
import io.github.chibisueru.telegramdrive.ui.browser.icon
import io.github.chibisueru.telegramdrive.util.FileKind
import io.github.chibisueru.telegramdrive.util.formatDate
import io.github.chibisueru.telegramdrive.util.formatSize
import io.github.chibisueru.telegramdrive.util.kindOf
import java.io.File

/** Text files larger than this are not previewed inline. */
private const val TEXT_PREVIEW_LIMIT = 4L * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(fileId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = fileId) { c, _ -> ViewerViewModel(c, fileId) }
    val file by vm.file.collectAsStateWithLifecycle()
    val cache by vm.cache.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    val entity = file ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val kind = kindOf(entity.mimeType, entity.name)
    val dark = kind == FileKind.VIDEO || kind == FileKind.IMAGE

    FullscreenEffect(fullscreen)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = if (dark) Color.Black else MaterialTheme.colorScheme.surface,
        topBar = {
            if (!fullscreen) {
                TopAppBar(
                    title = { Text(entity.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        IconButton(onClick = { vm.ensureCached { share(context, entity, it) } }) {
                            Icon(Icons.Default.Share, "Share")
                        }
                        IconButton(onClick = { vm.download() }) { Icon(Icons.Default.Download, "Download") }
                        IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, "Details") }
                    },
                    colors = if (dark) TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ) else TopAppBarDefaults.topAppBarColors(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(if (fullscreen) androidx.compose.foundation.layout.PaddingValues() else padding)) {
            when (kind) {
                FileKind.VIDEO, FileKind.AUDIO -> MediaPlayer(vm, entity, kind == FileKind.AUDIO, fullscreen) { fullscreen = it }
                FileKind.IMAGE -> NeedsCache(vm, cache, dark = true) { ZoomableImage(it) }
                FileKind.PDF -> NeedsCache(vm, cache) { PdfViewer(it) }
                FileKind.TEXT -> if (entity.size <= TEXT_PREVIEW_LIMIT) {
                    NeedsCache(vm, cache) { TextViewer(it) }
                } else {
                    NoPreview(entity, "This text file is too large to preview.", vm, cache, context)
                }
                else -> NoPreview(entity, "There's no built-in preview for this file type.", vm, cache, context)
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text("Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Name: ${entity.name}")
                    Text("Type: ${entity.mimeType}")
                    Text("Size: ${formatSize(entity.size)}")
                    Text("Uploaded: ${formatDate(entity.createdAt)}")
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPlayer(vm: ViewerViewModel, file: FileEntity, audioOnly: Boolean, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val player = remember(file.id) { vm.player(file) }
    LifecycleResumeEffect(player) {
        onPauseOrDispose { player.pause() }
    }
    Box(Modifier.fillMaxSize().background(if (audioOnly) MaterialTheme.colorScheme.surface else Color.Black)) {
        if (audioOnly) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).padding(bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(200.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(FileKind.AUDIO.icon(), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                Text(file.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    if (audioOnly) {
                        controllerShowTimeoutMs = 0
                        controllerHideOnTouch = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        showController()
                    } else {
                        setFullscreenButtonClickListener { onFullscreen(it) }
                    }
                }
            },
            update = { if (!audioOnly) it.setFullscreenButtonState(fullscreen) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Shows a download progress screen until the whole file is in the cache, then [content]. */
@Composable
private fun NeedsCache(vm: ViewerViewModel, cache: CacheState, dark: Boolean = false, content: @Composable (File) -> Unit) {
    LaunchedEffect(Unit) { vm.ensureCached() }
    val textColor = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    when (cache) {
        is CacheState.Ready -> content(cache.file)
        is CacheState.Failed -> CenterColumn {
            Text("Couldn't load the file", style = MaterialTheme.typography.titleMedium, color = textColor)
            Text(cache.message, color = textColor.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Button(onClick = { vm.ensureCached() }) { Text("Retry") }
        }
        is CacheState.Loading, CacheState.Idle -> CenterColumn {
            val p = (cache as? CacheState.Loading)?.progress ?: 0f
            Text("Loading from Telegram… ${(p * 100).toInt()}%", color = textColor)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun NoPreview(file: FileEntity, reason: String, vm: ViewerViewModel, cache: CacheState, context: Context) {
    CenterColumn {
        val kind = kindOf(file.mimeType, file.name)
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(120.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(kind.icon(), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text(file.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text("${formatSize(file.size)} · $reason", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (cache is CacheState.Loading) {
            LinearProgressIndicator(progress = { cache.progress }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth())
        }
        if (cache is CacheState.Failed) Text(cache.message, color = MaterialTheme.colorScheme.error)
        Button(onClick = { vm.ensureCached { openWith(context, file, it) } }, enabled = cache !is CacheState.Loading) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(end = 8.dp))
            Text("Open with another app")
        }
        FilledTonalButton(onClick = { vm.download() }) {
            Icon(Icons.Default.Download, null, Modifier.padding(end = 8.dp))
            Text("Download to phone")
        }
    }
}

@Composable
private fun CenterColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Landscape + hidden system bars while a video is fullscreen. */
@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun contentUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun share(context: Context, entity: FileEntity, file: File) {
    context.container.appLock.allowExternalActivity()
    val intent = Intent(Intent.ACTION_SEND)
        .setType(entity.mimeType)
        .putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, entity.name))
}

private fun openWith(context: Context, entity: FileEntity, file: File) {
    context.container.appLock.allowExternalActivity()
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(context, file), entity.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, entity.name))
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}
