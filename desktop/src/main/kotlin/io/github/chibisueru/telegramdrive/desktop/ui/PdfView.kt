package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File

/** Built-in PDF viewer: pages are rendered with PDFBox as you scroll to them. */
@Composable
fun PdfView(file: File) {
    val doc = remember(file) { runCatching { Loader.loadPDF(file) }.getOrNull() }
    val mutex = remember { Mutex() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This PDF can't be opened.", color = Color.White) }
        return
    }
    val renderer = remember(doc) { PDFRenderer(doc) }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageWidthPx = with(density) { minOf(maxWidth, 1000.dp).toPx() }
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(doc.numberOfPages) { index ->
                val box = doc.getPage(index).mediaBox
                val ratio = (box.width / box.height).takeIf { it > 0 } ?: 0.707f
                val page by produceState<ImageBitmap?>(null, index, pageWidthPx) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            runCatching {
                                // Render at the on-screen width (72 dpi = 1 PDF point per pixel).
                                val dpi = (pageWidthPx / box.width * 72f).coerceIn(48f, 300f)
                                renderer.renderImageWithDPI(index, dpi, ImageType.RGB).toComposeImageBitmap()
                            }.getOrNull()
                        }
                    }
                }
                Surface(shadowElevation = 3.dp, color = Color.White, modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth().aspectRatio(ratio)) {
                    val img = page
                    if (img != null) Image(img, "Page ${index + 1}", Modifier.fillMaxSize())
                    else Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        Text(
            "${listState.firstVisibleItemIndex + 1} / ${doc.numberOfPages}",
            color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
