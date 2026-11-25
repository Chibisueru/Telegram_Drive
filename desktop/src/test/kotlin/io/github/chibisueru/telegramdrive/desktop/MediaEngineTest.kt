package io.github.chibisueru.telegramdrive.desktop

import io.github.chibisueru.telegramdrive.desktop.media.MediaEngine
import io.github.chibisueru.telegramdrive.desktop.media.PlaybackState
import io.github.chibisueru.telegramdrive.desktop.media.RangeServer
import io.github.chibisueru.telegramdrive.desktop.media.RangeSource
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plays real files through [MediaEngine] over HTTP with byte ranges, the way the app's stream
 * server serves Telegram files. Needs the samples made in build/media-samples (see the README)
 * and an audio device, so it is skipped elsewhere. Playback is muted.
 */
class MediaEngineTest {
    companion object {
        private val dir = File("build/media-samples")
        private var server: RangeServer? = null
        private val port get() = server!!.port

        /** The app's own HTTP range server, reading local files instead of Telegram. */
        @BeforeClass @JvmStatic fun start() {
            if (!dir.isDirectory) return
            server = RangeServer { path ->
                val f = File(dir, path.trimStart('/'))
                if (!f.isFile) null else object : RangeSource {
                    val raf = RandomAccessFile(f, "r")
                    override val size = f.length()
                    override val contentType = "application/octet-stream"
                    override fun read(pos: Long, buf: ByteArray, max: Int): Int { raf.seek(pos); return raf.read(buf, 0, max) }
                    override fun close() = raf.close()
                }
            }
        }

        @AfterClass @JvmStatic fun stop() { server?.close() }
    }

    private fun url(name: String) = "http://127.0.0.1:$port/$name"

    private fun waitFor(e: MediaEngine, timeoutMs: Long = 8_000, cond: (PlaybackState) -> Boolean): PlaybackState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = e.state.value
            if (cond(s) || s.error != null) return s
            Thread.sleep(50)
        }
        return e.state.value
    }

    private fun playMusic(name: String, expectTitle: String?, expectCover: Boolean) {
        assumeTrue("samples not generated", File(dir, name).exists())
        val e = MediaEngine(url(name), withVideo = false)
        try {
            e.setVolume(0f)
            e.play()
            val s = waitFor(e) { it.positionMs > 800 }
            assertNull(s.error, "$name: ${s.error}")
            assertTrue(s.positionMs > 800, "$name should be playing, position ${s.positionMs}")
            assertTrue(s.durationMs in 5_500..6_500, "$name duration ${s.durationMs}")
            if (expectTitle != null) assertEquals(expectTitle, s.title)
            if (expectCover) assertNotNull(waitFor(e) { it.cover != null }.cover, "$name cover art")
            // Seeking over HTTP ranges, then playing to the end.
            e.seek(4_500)
            val after = waitFor(e) { it.positionMs >= 4_600 }
            assertTrue(after.positionMs >= 4_600, "$name seek, position ${after.positionMs}, error ${after.error}")
            assertTrue(waitFor(e) { it.ended }.ended, "$name should reach the end")
        } finally {
            e.close()
        }
    }

    /** Seeking should be heard quickly (the output buffer is short and music seeks skip exact-frame decoding). */
    @Test fun seekIsQuick() {
        assumeTrue("samples not generated", File(dir, "tone.flac").exists())
        val e = MediaEngine(url("tone.flac"), withVideo = false)
        try {
            e.setVolume(0f)
            e.play()
            waitFor(e) { it.positionMs > 500 }
            val times = listOf(4_000L, 1_000L, 3_000L).map { target ->
                val t0 = System.currentTimeMillis()
                e.seek(target)
                // Playing from the new spot: the clock has moved past the target.
                waitFor(e) { !it.buffering && it.positionMs in (target + 50)..(target + 1_500) }
                System.currentTimeMillis() - t0
            }
            println("seek times: $times ms")
            assertTrue(times.all { it < 700 }, "seeks took $times ms")
        } finally {
            e.close()
        }
    }

    @Test fun mp3() = playMusic("tone.mp3", "Tone MP3", expectCover = true)
    @Test fun flac() = playMusic("tone.flac", "Tone FLAC", expectCover = true)
    @Test fun opus() = playMusic("tone.opus", "Tone Opus", expectCover = false)
    @Test fun vorbis() = playMusic("tone.ogg", "Tone Vorbis", expectCover = false)
    @Test fun aac() = playMusic("tone.m4a", "Tone AAC", expectCover = false)
    @Test fun wav() = playMusic("tone.wav", null, expectCover = false)

    private fun playVideo(name: String) {
        assumeTrue("samples not generated", File(dir, name).exists())
        val e = MediaEngine(url(name), withVideo = true)
        try {
            e.setVolume(0f)
            e.play()
            val s = waitFor(e) { it.frame != null && it.positionMs > 800 }
            assertNull(s.error, "$name: ${s.error}")
            assertNotNull(s.frame, "$name should show frames: error ${s.error}, buffering ${s.buffering}, pos ${s.positionMs}, playing ${s.playing}")
            assertEquals(640, s.videoWidth)
            assertEquals(360, s.videoHeight)
            e.seek(4_000)
            val after = waitFor(e) { it.positionMs >= 4_200 }
            assertTrue(after.positionMs >= 4_200, "$name seek, position ${after.positionMs}, error ${after.error}")
        } finally {
            e.close()
        }
    }

    @Test fun h264Mp4() = playVideo("video.mp4")
    @Test fun vp9WebM() = playVideo("video.webm")
    @Test fun matroska() = playVideo("video.mkv")
}
