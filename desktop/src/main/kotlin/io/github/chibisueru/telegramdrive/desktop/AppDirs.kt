package io.github.chibisueru.telegramdrive.desktop

import java.io.File

/** Where the app keeps its files, following each OS's conventions. */
object AppDirs {
    private val home = File(System.getProperty("user.home"))
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    /** For tests: keep everything under one folder instead of the user's profile. */
    private val override = System.getProperty("tgdrive.home")?.let(::File)

    /** The Telegram session and the local copy of the drive index. Private to this user. */
    val data: File = if (override != null) File(override, "data") else if (isWindows) {
        File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "TelegramDrive")
    } else {
        File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "telegram-drive")
    }

    /** Thumbnails and files opened from the drive. Safe to delete. */
    val cache: File = if (override != null) File(override, "cache") else if (isWindows) {
        File(data, "Cache")
    } else {
        File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", "telegram-drive")
    }

    /** Default folder for "Download": the user's Downloads folder. */
    val downloads: File = File(home, "Downloads").takeIf { it.isDirectory } ?: home

    /** Set by the packaged launcher (see desktop/build.gradle.kts). */
    val version: String = System.getProperty("tgdrive.version") ?: "dev"

    fun ensure() {
        listOf(data, cache).forEach { dir ->
            dir.mkdirs()
            // The session gives full access to the Telegram account: keep it to this user.
            if (!isWindows) {
                dir.setReadable(false, false); dir.setReadable(true, true)
                dir.setWritable(false, false); dir.setWritable(true, true)
                dir.setExecutable(false, false); dir.setExecutable(true, true)
            }
        }
    }
}
