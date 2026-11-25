package io.github.chibisueru.telegramdrive.ui.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.chibisueru.telegramdrive.player.Music
import io.github.chibisueru.telegramdrive.player.MusicState

/** Full-screen player for the drive's music. Playback itself runs in the background service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(music: Music, onBack: () -> Unit) {
    val s by music.state.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now playing", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Up next") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            Artwork(s, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(32.dp))
            Text(
                s.title.ifBlank { "—" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            val subtitle = listOfNotNull(s.artist, s.album).joinToString(" · ")
            if (subtitle.isNotBlank()) Text(
                subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            s.error?.let {
                Text("Can't play this file: $it", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
            SeekBar(s, music)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = music::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = music::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                FilledIconButton(onClick = music::togglePlay, modifier = Modifier.size(76.dp), shape = RoundedCornerShape(26.dp)) {
                    if (s.buffering && !s.playing) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.playing) "Pause" else "Play", Modifier.size(40.dp))
                }
                IconButton(onClick = music::next, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp)) }
                IconButton(onClick = music::cycleRepeat) {
                    Icon(
                        if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        when (s.repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                        tint = if (s.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (s.queue.size > 1) {
                val next = s.queue.getOrNull(s.index + 1)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { showQueue = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (next != null) "Up next: ${next.fileName}" else "Track ${s.index + 1} of ${s.queue.size}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            Text("Up next · ${s.queue.size} tracks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { if (s.index > 2) listState.scrollToItem(s.index - 2) }
            LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
                itemsIndexed(s.queue, key = { i, e -> "$i-${e.fileId}" }) { i, entry ->
                    val current = i == s.index
                    ListItem(
                        leadingContent = {
                            Icon(if (current) Icons.Default.GraphicEq else Icons.Default.MusicNote, null,
                                tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        headlineContent = {
                            Text(entry.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { music.skipTo(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Artwork(s: MusicState, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 6.dp, modifier = modifier) {
        val art = s.artwork
        if (art != null) {
            Image(remember(art) { art.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun SeekBar(s: MusicState, music: Music) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = s.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else (s.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { music.seekTo((dragValue * duration).toLong()); dragging = false },
            enabled = s.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(time(if (dragging) (dragValue * duration).toLong() else s.positionMs), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (s.durationMs > 0) time(s.durationMs) else "--:--", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun time(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Small bar above the tab bar while music plays: track, play/pause, close. Tap to open the player. */
@Composable
fun MiniPlayer(music: Music, onOpen: () -> Unit) {
    val s by music.state.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = s.active, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Column {
                val duration = s.durationMs.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { (s.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    drawStopIndicator = {},
                )
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        val art = s.artwork
                        if (art != null) Image(remember(art) { art.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.artist?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = music::togglePlay, colors = IconButtonDefaults.iconButtonColors()) {
                        Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.playing) "Pause" else "Play")
                    }
                    IconButton(onClick = music::stop) { Icon(Icons.Default.Close, "Stop") }
                    Spacer(Modifier.width(2.dp))
                }
            }
        }
    }
}
