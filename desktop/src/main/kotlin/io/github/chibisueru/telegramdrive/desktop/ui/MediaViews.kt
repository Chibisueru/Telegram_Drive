package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.media.MediaEngine
import io.github.chibisueru.telegramdrive.desktop.media.Music
import io.github.chibisueru.telegramdrive.desktop.media.PlaybackState
import io.github.chibisueru.telegramdrive.desktop.media.Repeat
import kotlinx.coroutines.delay

/** Lets the video player put the window in and out of fullscreen. */
class FullscreenControl(val isFullscreen: () -> Boolean, val set: (Boolean) -> Unit)
val LocalFullscreen = compositionLocalOf { FullscreenControl({ false }, {}) }

fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Built-in video player: streams the file from Telegram through FFmpeg. Controls show on mouse
 * movement; Space plays/pauses (via [onToggleReady]); double-click toggles fullscreen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoPlayerView(c: AppContainer, file: DriveFile, onToggleReady: (() -> Unit) -> Unit) {
    val engine = remember(file.id) { MediaEngine(c.stream.urlFor(file), withVideo = true) }
    DisposableEffect(engine) {
        c.music.pause() // don't play over the music
        engine.setVolume(c.music.state.value.volume)
        engine.play()
        onToggleReady { engine.toggle() }
        onDispose { engine.close() }
    }
    val s by engine.state.collectAsState()
    val fullscreen = LocalFullscreen.current
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(engine) {
        while (true) {
            controls = !s.playing || System.currentTimeMillis() - lastMove < 2_500
            delay(200)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .pointerInput(engine) {
                detectTapGestures(
                    onTap = { engine.toggle(); lastMove = System.currentTimeMillis() },
                    onDoubleTap = { fullscreen.set(!fullscreen.isFullscreen()) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        s.frame?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        when {
            s.error != null -> Text("Can't play this video: ${s.error}", color = Color.White, textAlign = TextAlign.Center)
            s.buffering || (s.frame == null && s.playing) -> CircularProgressIndicator(color = Color.White)
        }
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { engine.toggle() }) {
                    Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) "Pause" else "Play", tint = Color.White)
                }
                SeekRow(s, onSeek = engine::seek, light = true, modifier = Modifier.weight(1f))
                VolumeControl(c.music.state.collectAsState().value.volume, light = true) { v -> c.music.setVolume(v); engine.setVolume(v) }
                IconButton(onClick = { fullscreen.set(!fullscreen.isFullscreen()) }) {
                    Icon(if (fullscreen.isFullscreen()) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SeekRow(s: PlaybackState, onSeek: (Long) -> Unit, light: Boolean, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    val duration = s.durationMs.coerceAtLeast(1)
    val color = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(clock(if (dragging) (dragValue * duration).toLong() else s.positionMs), color = color, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = if (dragging) dragValue else (s.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = {
                dragging = true; dragValue = it
                // Follow the slider while dragging (a few seeks a second), not only on release.
                val now = System.currentTimeMillis()
                if (now - lastSeekAt > 150) { lastSeekAt = now; onSeek((it * duration).toLong()) }
            },
            onValueChangeFinished = { onSeek((dragValue * duration).toLong()); dragging = false },
            enabled = s.durationMs > 0,
            colors = if (light) SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
            else SliderDefaults.colors(),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(if (s.durationMs > 0) clock(s.durationMs) else "--:--", color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VolumeControl(volume: Float, light: Boolean, onChange: (Float) -> Unit) {
    val tint = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    var before by remember { mutableFloatStateOf(1f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (volume > 0f) { before = volume; onChange(0f) } else onChange(before.coerceAtLeast(0.1f)) }) {
            Icon(if (volume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = tint)
        }
        Slider(
            value = volume, onValueChange = onChange, modifier = Modifier.width(110.dp),
            colors = if (light) SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
            else SliderDefaults.colors(),
        )
    }
}

/** Full music player, shown in the viewer for music files. Playback keeps going after you close it. */
@Composable
fun MusicPlayerView(c: AppContainer, file: DriveFile, queue: List<DriveFile>, onToggleReady: (() -> Unit) -> Unit) {
    val music = c.music
    LaunchedEffect(file.id) {
        music.play(queue.ifEmpty { listOf(file) }, file.id)
        onToggleReady { music.togglePlay() }
    }
    val s by music.state.collectAsState()
    val p = s.playback
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f)) {
                val cover = p.cover
                if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(s.title.ifBlank { "—" }, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            val sub = listOfNotNull(p.artist, p.album).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            p.error?.let { Text("Can't play this file: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp))
            SeekRow(p, onSeek = music::seekTo, light = true, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = music::toggleShuffle) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = music::previous, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(34.dp), tint = Color.White) }
                FilledIconButton(onClick = music::togglePlay, modifier = Modifier.size(72.dp), shape = RoundedCornerShape(24.dp)) {
                    if (p.buffering && !p.playing) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Pause" else "Play", Modifier.size(38.dp))
                }
                IconButton(onClick = music::next, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(34.dp), tint = Color.White) }
                IconButton(onClick = music::cycleRepeat) {
                    Icon(if (s.repeat == Repeat.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat: ${s.repeat.name.lowercase()}",
                        tint = if (s.repeat != Repeat.OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
                }
            }
        }
        // Up next
        if (s.queue.size > 1) {
            Surface(color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(20.dp), modifier = Modifier.width(340.dp).fillMaxHeight()) {
                Column {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.White)
                        Text("  Up next · ${s.queue.size} tracks", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(s.index) { if (s.index > 2) listState.animateScrollToItem(s.index - 2) }
                    LazyColumn(state = listState) {
                        itemsIndexed(s.queue, key = { _, f -> f.id }) { i, f ->
                            val current = i == s.index
                            Row(
                                Modifier.fillMaxWidth().clickable { music.skipTo(i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (current) Icons.Filled.GraphicEq else Icons.Filled.MusicNote, null, Modifier.size(18.dp),
                                    tint = if (current) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f))
                                Text("  " + f.name, color = if (current) MaterialTheme.colorScheme.primary else Color.White,
                                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Bar at the bottom of the window while music plays; click it to open the full player. */
@Composable
fun MiniPlayer(music: Music, onOpen: () -> Unit) {
    val s by music.state.collectAsState()
    AnimatedVisibility(s.active, enter = expandVertically(), exit = shrinkVertically()) {
        val p = s.playback
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                val duration = p.durationMs.coerceAtLeast(1)
                LinearProgressIndicator(progress = { (p.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {})
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            val cover = p.cover
                            if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(p.artist, p.album).joinToString(" · ").ifBlank { "${clock(p.positionMs)} / ${clock(p.durationMs)}" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = music::previous) { Icon(Icons.Filled.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = music::togglePlay, colors = IconButtonDefaults.filledIconButtonColors()) {
                        Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Pause" else "Play")
                    }
                    IconButton(onClick = music::next) { Icon(Icons.Filled.SkipNext, "Next") }
                    VolumeControl(s.volume, light = false, onChange = music::setVolume)
                    IconButton(onClick = music::stop) { Icon(Icons.Filled.Close, "Stop") }
                }
            }
        }
    }
}
