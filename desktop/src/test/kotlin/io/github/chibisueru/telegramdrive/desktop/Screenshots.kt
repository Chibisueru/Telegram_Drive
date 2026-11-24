package io.github.chibisueru.telegramdrive.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Density
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.data.ViewMode
import io.github.chibisueru.telegramdrive.desktop.ui.BrowserState
import io.github.chibisueru.telegramdrive.desktop.ui.TgDriveTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/** Renders the main screens to PNGs without a display: `./gradlew :desktop:screenshots`. */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/screenshots" }).apply { mkdirs() }
    val configured = args.getOrNull(1) == "configured"
    AppDirs.ensure()
    val c = AppContainer()
    // Configured after start-up, so the fake account isn't connected (and found signed out) while rendering.
    if (configured) c.settings.update {
        it.copy(apiId = 1, apiHash = "0".repeat(32), chatId = -1001, chatTitle = "Telegram Drive", accountName = "Test User")
    }
    if (configured) {
        val d = c.drive
        val photos = d.createFolder(null, "Photos")
        d.createFolder(null, "Documents"); d.createFolder(null, "Music"); d.createFolder(photos, "2026")
        val now = System.currentTimeMillis()
        listOf(
            "Holiday video.mp4" to 734_003_200L, "Tax return 2025.pdf" to 2_345_678L, "notes.md" to 4_096L,
            "Backup.zip" to 1_288_490_188L, "Song.flac" to 31_457_280L, "Screenshot.png" to 845_000L,
        ).forEachIndexed { i, (name, size) ->
            d.addFile(DriveFile("f$i", null, name, io.github.chibisueru.telegramdrive.desktop.data.guessMimeType(name), size, now - i * 86_400_000L, 1L + i, "r$i", starred = i == 1))
        }
    }
    val browser = BrowserState(c)
    fun shot(name: String, theme: Int, setup: () -> Unit = {}) {
        setup()
        val scene = ImageComposeScene(1280, 800, Density(1.25f)) {
            TgDriveTheme(theme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { App(c, browser) } }
        }
        var t = 0L
        repeat(30) { scene.render(t); t += 50_000_000L; Thread.sleep(20) }
        val img = scene.render(t)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name.png")
    }
    if (configured) {
        shot("files-light", 1)
        shot("files-grid-dark", 2) { c.settings.update { it.copy(viewMode = ViewMode.GRID) } }
        c.settings.update { it.copy(viewMode = ViewMode.LIST) }
        shot("files-selected-light", 1) { browser.selection = setOf("f1", "f3") }
    } else {
        shot("setup-light", 1)
        shot("setup-dark", 2)
    }
    if (configured) {
        // TDLib's native library must load and answer, even though the session is fake.
        c.telegram.resume(1, "0".repeat(32))
        Thread.sleep(4000)
        println("telegram step: " + c.telegram.step.value + ", error: " + c.telegram.error.value)
    }
    exitProcess(0)
}
