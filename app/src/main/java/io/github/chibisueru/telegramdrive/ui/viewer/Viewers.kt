package io.github.chibisueru.telegramdrive.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.positionChanged
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Swipe left/right between the images of a folder. Neighbouring images are loaded ahead of
 * time; swiping is disabled while the current image is zoomed in, so dragging pans instead.
 */
@Composable
fun ImagePager(images: List<FileEntity>, initialId: String, onPageChanged: (FileEntity) -> Unit) {
    val start = remember { images.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = start) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState, images) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            zoomed = false
            images.getOrNull(page)?.let(onPageChanged)
        }
    }
    HorizontalPager(
        state = pagerState,
        key = { images.getOrNull(it)?.id ?: it },
        beyondViewportPageCount = 1,
        pageSpacing = 16.dp,
        userScrollEnabled = !zoomed,
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) { page ->
        val file = images[page]
        ImagePage(file, onZoomChanged = { if (page == pagerState.currentPage) zoomed = it })
    }
}

/** One image: its thumbnail while the full image downloads, then the zoomable image. */
@Composable
private fun ImagePage(file: FileEntity, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val files = context.container.files
    var state by remember(file.id) { mutableStateOf<CacheState>(CacheState.Loading(0f)) }
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    val thumb by produceState<File?>(null, file.thumbRemoteId) {
        value = file.thumbRemoteId?.let { files.downloadSmall(it) }
    }
    LaunchedEffect(file.id, attempt) {
        state = CacheState.Loading(0f)
        state = try {
            CacheState.Ready(files.download(file.remoteId, priority = 32) { state = CacheState.Loading(it) })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CacheState.Failed(e.message ?: "Download failed")
        }
    }
    when (val s = state) {
        is CacheState.Ready -> ZoomableImage(s.file, onZoomChanged)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            thumb?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s is CacheState.Failed) {
                    Text("Couldn't load the image", color = Color.White)
                    Text(s.message, color = Color.White.copy(alpha = 0.7f))
                    Button(onClick = { attempt++ }) { Text("Retry") }
                } else {
                    val p = (s as? CacheState.Loading)?.progress ?: 0f
                    CircularProgressIndicator(progress = { p }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
                }
            }
        }
    }
}

/**
 * Pinch to zoom, double-tap to toggle 2.5× zoom, drag to pan while zoomed. One-finger drags on
 * an unzoomed image are left alone so the pager can swipe to the next image.
 */
@Composable
fun ZoomableImage(file: File, onZoomChanged: (Boolean) -> Unit = {}) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun apply(newScale: Float, newOffset: Offset) {
        val wasZoomed = scale > 1f
        scale = newScale.coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else clamp(newOffset, scale)
        if ((scale > 1f) != wasZoomed) onZoomChanged(scale > 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        apply(1f, Offset.Zero)
                    } else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        apply(2.5f, (center - tap) * 1.5f)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Only take over pinches, and drags while zoomed in.
                        if (fingers > 1 || scale > 1f) {
                            apply(scale * event.calculateZoom(), offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(file).size(4096).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}

/** Renders PDF pages lazily with the platform PdfRenderer. */
@Composable
fun PdfViewer(file: File) {
    val mutex = remember { Mutex() }
    val pdf = remember(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fd to PdfRenderer(fd)
        }.getOrNull()
    }
    DisposableEffect(pdf) {
        onDispose {
            pdf?.let { (fd, renderer) ->
                runBlocking { mutex.withLock { renderer.close(); fd.close() } }
            }
        }
    }
    if (pdf == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This PDF can't be opened.") }
        return
    }
    val renderer = pdf.second
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val widthPx = constraints.maxWidth
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(renderer.pageCount) { index ->
                val bitmap by produceState<Bitmap?>(null, index, widthPx) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            renderer.openPage(index).use { page ->
                                val w = widthPx.coerceAtMost(2000)
                                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                createBitmap(w, h).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
                Surface(shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    val b = bitmap
                    if (b == null) {
                        Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        Image(
                            b.asImageBitmap(), "Page ${index + 1}",
                            modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TextViewer(file: File) {
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrElse { "Can't read file: ${it.message}" } }
    }
    val t = text
    if (t == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    SelectionContainer {
        Text(
            t,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}
