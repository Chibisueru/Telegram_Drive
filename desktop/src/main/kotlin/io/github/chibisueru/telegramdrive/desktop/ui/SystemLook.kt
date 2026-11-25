package io.github.chibisueru.telegramdrive.desktop.ui

import io.github.chibisueru.telegramdrive.desktop.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The desktop's dark-mode preference and accent color; null where the system doesn't say. */
data class SystemLook(val dark: Boolean? = null, val accent: Int? = null)

/**
 * Reads the system's appearance settings and keeps them current, so "System" theme and the
 * accent color follow the desktop live.
 *
 * - Linux: the freedesktop settings portal (GNOME, KDE, …), falling back to gsettings.
 * - Windows: the registry (light/dark app mode and the accent color).
 */
object SystemLookWatcher {
    private val _look = MutableStateFlow(SystemLook())
    val look: StateFlow<SystemLook> = _look

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                refresh()
                delay(5_000)
            }
        }
    }

    suspend fun refresh() {
        val now = withContext(Dispatchers.IO) { runCatching { if (AppDirs.isWindows) readWindows() else readLinux() }.getOrDefault(SystemLook()) }
        if (now != _look.value) _look.value = now
    }

    // ---- Linux ----

    private fun portal(key: String): String? = run(
        "gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
        "--object-path", "/org/freedesktop/portal/desktop",
        "--method", "org.freedesktop.portal.Settings.ReadOne", "org.freedesktop.appearance", key,
    )

    private fun readLinux(): SystemLook {
        // color-scheme: 0 = no preference, 1 = prefer dark, 2 = prefer light.
        val scheme = portal("color-scheme")?.let { Regex("""uint32 (\d)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        var dark: Boolean? = when (scheme) {
            1 -> true
            2 -> false
            else -> null
        }
        if (dark == null) {
            val gnome = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            dark = when {
                gnome == null -> null
                "dark" in gnome -> true
                "light" in gnome -> false
                else -> run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.lowercase()?.contains("dark")
            }
        }
        // accent-color: (r, g, b) as 0..1 doubles; values outside 0..1 mean "not set".
        val accent = portal("accent-color")?.let { out ->
            val parts = Regex("""-?\d+(?:\.\d+)?(?:[eE]-?\d+)?""").findAll(out.substringAfter("(<(")).map { it.value.toDouble() }.take(3).toList()
            if (parts.size == 3 && parts.all { it in 0.0..1.0 }) rgb(parts[0], parts[1], parts[2]) else null
        } ?: run("gsettings", "get", "org.gnome.desktop.interface", "accent-color")?.trim('\'', ' ', '\n')?.let(gnomeAccents::get)
        return SystemLook(dark, accent)
    }

    /** GNOME 47+ named accents. */
    private val gnomeAccents = mapOf(
        "blue" to 0xFF3584E4.toInt(), "teal" to 0xFF2190A4.toInt(), "green" to 0xFF3A944A.toInt(),
        "yellow" to 0xFFC88800.toInt(), "orange" to 0xFFED5B00.toInt(), "red" to 0xFFE62D42.toInt(),
        "pink" to 0xFFD56199.toInt(), "purple" to 0xFF9141AC.toInt(), "slate" to 0xFF6F8396.toInt(),
    )

    private fun rgb(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or ((r * 255).toInt().coerceIn(0, 255) shl 16) or ((g * 255).toInt().coerceIn(0, 255) shl 8) or (b * 255).toInt().coerceIn(0, 255)

    // ---- Windows ----

    private fun regDword(key: String, value: String): Long? =
        run("reg", "query", key, "/v", value)?.let { Regex("""0x([0-9a-fA-F]+)""").find(it)?.groupValues?.get(1)?.toLongOrNull(16) }

    private fun readWindows(): SystemLook {
        val light = regDword("""HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize""", "AppsUseLightTheme")
        // AccentColor is stored as 0xAABBGGRR.
        val abgr = regDword("""HKCU\Software\Microsoft\Windows\DWM""", "AccentColor")
            ?: regDword("""HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Accent""", "AccentColorMenu")
        val accent = abgr?.let {
            val r = (it and 0xFF).toInt(); val g = ((it shr 8) and 0xFF).toInt(); val b = ((it shr 16) and 0xFF).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return SystemLook(dark = light?.let { it == 0L }, accent = accent)
    }

    /** Runs a small command and returns its output, or null if it fails or takes too long. */
    private fun run(vararg command: String): String? = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() != 0) return null
        p.inputStream.bufferedReader().readText().trim()
    }.getOrNull()
}
