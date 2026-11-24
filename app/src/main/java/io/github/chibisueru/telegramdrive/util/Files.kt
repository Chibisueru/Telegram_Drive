package io.github.chibisueru.telegramdrive.util

import android.webkit.MimeTypeMap
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, APK, OTHER }

private val textExtensions = setOf(
    "txt", "md", "json", "xml", "csv", "log", "yml", "yaml", "ini", "conf", "cfg", "properties",
    "kt", "kts", "java", "py", "js", "ts", "c", "h", "cpp", "rs", "go", "sh", "html", "css", "sql", "gradle", "toml",
)
private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst")

fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun guessMimeType(name: String): String {
    val ext = extensionOf(name)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
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

fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** A file name that is safe to use on disk. */
fun safeFileName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_").ifBlank { "file" }
