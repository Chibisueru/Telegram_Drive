package io.github.chibisueru.telegramdrive.desktop

import io.github.chibisueru.telegramdrive.desktop.data.Drive
import io.github.chibisueru.telegramdrive.desktop.data.SettingsStore
import io.github.chibisueru.telegramdrive.desktop.telegram.LoginStep
import io.github.chibisueru.telegramdrive.desktop.telegram.Telegram
import io.github.chibisueru.telegramdrive.desktop.telegram.TgFiles
import io.github.chibisueru.telegramdrive.desktop.transfer.Transfers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the app shares, created once at start. */
class AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore()
    val telegram = Telegram(scope)
    val files = TgFiles(telegram)
    val drive = Drive(scope, telegram, files, settings)
    val transfers = Transfers(scope, drive, files, settings)

    init {
        val s = settings.current
        if (s.isConfigured) telegram.resume(s.apiId, s.apiHash)
        scope.launch {
            var purged = false
            // Pick up changes made on phones or other computers, once a minute.
            while (true) {
                telegram.step.first { it is LoginStep.Ready }
                if (settings.current.isConfigured) {
                    if (!purged) { purged = true; runCatching { drive.purgeOldTrash() } }
                    runCatching { drive.refreshIfChanged() }
                }
                delay(60_000)
            }
        }
    }

    /** Called when the window gets focus: check right away instead of waiting for the next minute. */
    fun refreshSoon() {
        if (settings.current.isConfigured && telegram.step.value is LoginStep.Ready) {
            scope.launch { runCatching { drive.refreshIfChanged() } }
        }
    }
}
