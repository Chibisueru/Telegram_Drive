package io.github.chibisueru.telegramdrive.desktop.data

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, APK, OTHER }

private val textExtensions = setOf(
    "txt", "md", "json", "xml", "csv", "log", "yml", "yaml", "ini", "conf", "cfg", "properties",
    "kt", "kts", "java", "py", "js", "ts", "c", "h", "cpp", "rs", "go", "sh", "html", "css", "sql", "gradle", "toml",
)
private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst")

/** Types the JDK doesn't know; the Android app picks its viewer from these. */
private val extraMimeTypes = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
    "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "bmp" to "image/bmp", "svg" to "image/svg+xml",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
    "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/opus",
    "flac" to "audio/flac", "wav" to "audio/wav",
    "pdf" to "application/pdf", "json" to "application/json", "zip" to "application/zip",
    "apk" to "application/vnd.android.package-archive",
    "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun guessMimeType(name: String): String {
    val ext = extensionOf(name)
    return extraMimeTypes[ext]
        ?: URLConnection.guessContentTypeFromName(name)
        ?: if (ext in textExtensions) "text/plain" else "application/octet-stream"
}

fun kindOf(mime: String, name: String): FileKind {
    val ext = extensionOf(name)
    return when {
        mime.startsWith("image/") -> FileKind.IMAGE
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime == "application/pdf" -> FileKind.PDF
        mime == "application/vnd.android.package-archive" -> FileKind.APK
        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" ||
            ext in textExtensions -> FileKind.TEXT
        ext in archiveExtensions -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

/** Images the built-in viewer (Skia) can show. */
fun canPreviewImage(name: String) = extensionOf(name) in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ico")

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024; i++
    }
    return if (value >= 100) "%.0f %s".format(value, units[i]) else "%.1f %s".format(value, units[i])
}

fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** A file name that is safe to use on disk (Windows is the strictest). */
fun safeFileName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_").trim().trimEnd('.').ifBlank { "file" }

/** [dir]/[name], or "name (2).ext" etc. if that already exists. */
fun uniqueFile(dir: File, name: String): File {
    val safe = safeFileName(name)
    var candidate = File(dir, safe)
    val base = safe.substringBeforeLast('.', safe)
    val ext = safe.substringAfterLast('.', "").let { if (it.isEmpty() || it == safe) "" else ".$it" }
    var n = 2
    while (candidate.exists()) candidate = File(dir, "$base ($n)$ext").also { n++ }
    return candidate
}

/** Opens a file or folder with the system's default app. */
fun openWithSystem(file: File) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file); return
        }
    }
    if (!System.getProperty("os.name").lowercase().contains("windows")) {
        ProcessBuilder("xdg-open", file.absolutePath).start()
    } else {
        ProcessBuilder("explorer.exe", file.absolutePath).start()
    }
}

fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url)); return
        }
    }
    if (!System.getProperty("os.name").lowercase().contains("windows")) ProcessBuilder("xdg-open", url).start()
    else ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
}
