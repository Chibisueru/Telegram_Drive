package io.github.chibisueru.telegramdrive.ui.viewer

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import kotlinx.coroutines.delay
import java.io.File

/**
 * A video inside the photo/video pager. Only the page on screen plays; the others show their
 * thumbnail. The controls are drawn in Compose and the video view takes no touches itself, so
 * swiping left/right still moves to the next photo or video.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(vm: ViewerViewModel, file: FileEntity, current: Boolean, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    if (!current) {
        VideoPoster(file)
        return
    }
    val player = remember(file.id) { vm.showVideo(file) }
    LifecycleResumeEffect(player) { onPauseOrDispose { player.pause() } }
    DisposableEffect(file.id) { onDispose { vm.pauseVideo() } }

    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                buffering = p.playbackState == Player.STATE_BUFFERING
                duration = p.duration.coerceAtLeast(0)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            // Hide the controls after a few seconds of playback.
            if (playing && !dragging && System.currentTimeMillis() - lastTouch > 3_000) controls = false
            delay(250)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(player) {
            detectTapGestures(
                onTap = { controls = !controls; lastTouch = System.currentTimeMillis() },
                onDoubleTap = { offset ->
                    // Double-tap the left/right side to jump back/forward 10 seconds.
                    val forward = offset.x > size.width / 2
                    player.seekTo((player.currentPosition + if (forward) 10_000 else -10_000).coerceIn(0, player.duration.coerceAtLeast(0)))
                    controls = true; lastTouch = System.currentTimeMillis()
                },
            )
        },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isClickable = false
                    isFocusable = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    this.player = player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                if (!buffering) FilledIconButton(
                    onClick = {
                        if (player.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                        lastTouch = System.currentTimeMillis()
                    },
                    modifier = Modifier.align(Alignment.Center).size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White),
                ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp)) }

                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val shownPos = if (dragging) (dragValue * duration).toLong() else position
                    Text(clock(shownPos), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = true; dragValue = it; lastTouch = System.currentTimeMillis() },
                        onValueChangeFinished = { player.seekTo((dragValue * duration).toLong()); dragging = false },
                        enabled = duration > 0,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(if (duration > 0) clock(duration) else "--:--", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onFullscreen(!fullscreen); lastTouch = System.currentTimeMillis() }) {
                        Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** A video page that isn't on screen: its thumbnail with a play symbol. */
@Composable
private fun VideoPoster(file: FileEntity) {
    val files = LocalContext.current.container.files
    val thumb by produceState<File?>(null, file.thumbRemoteId) { value = file.thumbRemoteId?.let { files.downloadSmall(it) } }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        thumb?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        Icon(Icons.Default.PlayArrow, null, Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(12.dp), tint = Color.White)
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
