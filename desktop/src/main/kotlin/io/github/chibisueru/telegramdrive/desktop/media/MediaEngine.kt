package io.github.chibisueru.telegramdrive.desktop.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** What a player screen shows. */
data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The current video frame (video only). */
    val frame: ImageBitmap? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** From the file's tags. */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Embedded cover art (music). */
    val cover: ImageBitmap? = null,
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * Plays one video or audio file with FFmpeg: decoding runs on its own thread, sound goes to the
 * default audio output, and video frames are shown in step with the sound (the audio clock is
 * the master; late frames are dropped). Formats: whatever FFmpeg decodes, which is nearly all.
 */
class MediaEngine(
    private val url: String,
    private val withVideo: Boolean,
    private val onEnded: () -> Unit = {},
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    @Volatile private var paused = false
    @Volatile private var seekRequestMs = -1L
    @Volatile private var closed = false
    @Volatile private var volume = 1f
    private var line: SourceDataLine? = null

    private val thread = Thread({ run() }, "media").apply { isDaemon = true; start() }

    fun play() { paused = false; _state.update { it.copy(playing = true) } }
    fun pause() { paused = true; _state.update { it.copy(playing = false) } }
    fun toggle() = if (paused || _state.value.ended) { if (_state.value.ended) seek(0); play() } else pause()
    fun seek(ms: Long) {
        seekRequestMs = ms.coerceAtLeast(0)
        _state.update { it.copy(positionMs = ms, ended = false, buffering = true) }
    }
    /** Applied to the samples as they're decoded: heard within ~0.15 s for music (see [run]). */
    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f) }

    fun close() {
        closed = true
        thread.interrupt()
    }

    private fun run() {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        val g = FFmpegFrameGrabber(url)
        try {
            g.setOption("rw_timeout", "60000000") // µs: Telegram may need a moment for a new position
            g.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            g.sampleRate = SAMPLE_RATE
            g.audioChannels = 2
            if (withVideo) g.pixelFormat = avutil.AV_PIX_FMT_BGRA
            g.start()
            // Tags can sit on the file (MP3, MP4, FLAC) or on the audio stream (Ogg: Opus, Vorbis).
            val meta = (g.audioMetadata.orEmpty() + g.metadata.orEmpty()).mapKeys { it.key.lowercase() }
            _state.update {
                it.copy(
                    durationMs = (g.lengthInTime / 1000).coerceAtLeast(0),
                    title = meta["title"]?.takeIf(String::isNotBlank),
                    artist = (meta["artist"] ?: meta["album_artist"])?.takeIf(String::isNotBlank),
                    album = meta["album"]?.takeIf(String::isNotBlank),
                    videoWidth = g.imageWidth, videoHeight = g.imageHeight,
                    playing = !paused, buffering = false,
                )
            }
            // Read the cover alongside, so playback doesn't wait for it.
            if (!withVideo && g.hasVideo()) Thread({ readCover() }, "cover").apply { isDaemon = true; start() }

            val hasAudio = g.audioChannels > 0 && g.hasAudio()
            if (hasAudio) {
                val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
                // No usable sound device: keep playing silently (video still shows, timing kept).
                line = if (System.getProperty("tgdrive.noaudio") != null) null // tests: act like there's no sound device
                // Music: a short output buffer (~0.15 s), so volume changes and seeks are heard quickly.
                // Video: files store sound ahead of the matching picture, so a longer buffer keeps
                // the decoder from falling behind on frames.
                else runCatching {
                    AudioSystem.getSourceDataLine(format).apply { open(format, if (withVideo) VIDEO_BUFFER_BYTES else MUSIC_BUFFER_BYTES); start() }
                }.getOrNull()
            }
            val l = line
            var clockBaseUs = 0L
            var framesAtBase = l?.longFramePosition ?: 0L
            var needBase = true
            var wallStartNs = System.nanoTime()
            var pausedAt = 0L
            var lastPublish = 0L
            var bytes = ByteArray(0)
            var pixels = ByteArray(0)

            fun clockUs(): Long = if (l != null) clockBaseUs + (l.longFramePosition - framesAtBase) * 1_000_000L / SAMPLE_RATE
            else clockBaseUs + (System.nanoTime() - wallStartNs) / 1000

            while (!closed) {
                val seekMs = seekRequestMs
                if (seekMs >= 0) {
                    seekRequestMs = -1
                    // Music: jump straight there. Video: decode up to the exact frame so the picture matches.
                    g.setTimestamp(seekMs * 1000, withVideo)
                    l?.flush()
                    clockBaseUs = seekMs * 1000; framesAtBase = l?.longFramePosition ?: 0L; wallStartNs = System.nanoTime()
                    needBase = true
                    _state.update { it.copy(buffering = false, ended = false) }
                }
                if (paused) {
                    if (l?.isRunning == true) l.stop()
                    if (pausedAt == 0L) pausedAt = System.nanoTime()
                    Thread.sleep(20)
                    continue
                }
                if (pausedAt != 0L) { wallStartNs += System.nanoTime() - pausedAt; pausedAt = 0L }
                if (l != null && !l.isRunning) l.start()

                val frame: Frame? = if (withVideo) g.grab() else g.grabSamples()
                if (frame == null) {
                    // End of the file: let the sound finish, then report it.
                    l?.drain()
                    _state.update { it.copy(ended = true, playing = false, positionMs = it.durationMs) }
                    paused = true
                    onEnded()
                    continue
                }
                val samples = frame.samples
                if (samples != null && l == null) {
                    // Silent playback: pace decoding like a sound card with a half-second buffer would
                    // (audio arrives ahead of the matching video, so waiting for each sample's exact
                    // moment would make every video frame late).
                    if (needBase) { clockBaseUs = frame.timestamp; wallStartNs = System.nanoTime(); needBase = false }
                    while (!closed && !paused && seekRequestMs < 0 && clockUs() < frame.timestamp - 500_000) Thread.sleep(5)
                } else if (samples != null && l != null) {
                    if (needBase) { clockBaseUs = frame.timestamp; framesAtBase = l.longFramePosition; needBase = false }
                    val sb = samples[0] as ShortBuffer
                    val n = sb.remaining()
                    if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                    val shorts = ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val v = volume
                    if (v >= 0.999f) shorts.put(sb)
                    else {
                        // Volume in software: works on every sound system and applies right away.
                        val gain = v * v // perceptual curve: the slider's middle sounds like half as loud
                        for (i in 0 until n) shorts.put(i, (sb.get(sb.position() + i) * gain).toInt().toShort())
                    }
                    l.write(bytes, 0, n * 2) // blocks while the output buffer is full, which paces decoding
                } else if (frame.image != null && withVideo) {
                    if (needBase && l == null) { clockBaseUs = frame.timestamp; wallStartNs = System.nanoTime(); needBase = false }
                    val ts = frame.timestamp
                    // Wait for the frame's moment; drop it if it's already well behind.
                    while (!closed && !paused && seekRequestMs < 0 && clockUs() < ts - 5_000) Thread.sleep(2)
                    if (clockUs() - ts < 120_000 && seekRequestMs < 0) {
                        val w = frame.imageWidth; val h = frame.imageHeight
                        val stride = frame.imageStride
                        val buf = frame.image[0] as ByteBuffer
                        val need = stride * h
                        if (pixels.size < need) pixels = ByteArray(need)
                        buf.position(0); buf.get(pixels, 0, minOf(need, buf.remaining()))
                        val img = org.jetbrains.skia.Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, stride)
                        _state.update { it.copy(frame = img.toComposeImageBitmap(), videoWidth = w, videoHeight = h) }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastPublish > 100) {
                    lastPublish = now
                    _state.update { it.copy(positionMs = clockUs() / 1000, playing = !paused) }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            // Includes native-library errors, so the player shows a message instead of freezing.
            if (!closed) _state.update { it.copy(error = e.message ?: e.javaClass.simpleName, buffering = false, playing = false) }
        } finally {
            runCatching { line?.stop(); line?.close() }
            runCatching { g.stop(); g.release() }
        }
    }

    /** Music files keep their cover as a one-frame "video" stream; read it with a second grabber. */
    private fun readCover() {
        runCatching {
            FFmpegFrameGrabber(url).use { cg ->
                cg.pixelFormat = avutil.AV_PIX_FMT_BGRA
                cg.start()
                val f = cg.grabImage() ?: return
                val w = f.imageWidth; val h = f.imageHeight; val stride = f.imageStride
                val buf = f.image[0] as ByteBuffer
                val px = ByteArray(stride * h)
                buf.position(0); buf.get(px, 0, minOf(px.size, buf.remaining()))
                val img = org.jetbrains.skia.Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), px, stride)
                _state.update { it.copy(cover = img.toComposeImageBitmap()) }
                cg.stop()
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        /** 16-bit stereo: 4 bytes per frame. 0.15 s of sound for music, 0.5 s for video. */
        const val MUSIC_BUFFER_BYTES = SAMPLE_RATE * 4 * 15 / 100
        const val VIDEO_BUFFER_BYTES = SAMPLE_RATE * 4 / 2
    }
}
