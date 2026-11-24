package io.github.chibisueru.telegramdrive.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import io.github.chibisueru.telegramdrive.backup.CameraBackup
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.platform.LocalContext
import io.github.chibisueru.telegramdrive.data.LockTimeout
import io.github.chibisueru.telegramdrive.security.canUseBiometrics
import io.github.chibisueru.telegramdrive.security.findFragmentActivity
import io.github.chibisueru.telegramdrive.security.promptBiometric
import io.github.chibisueru.telegramdrive.ui.security.ConfirmPinDialog
import io.github.chibisueru.telegramdrive.ui.security.NewPinDialog
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.data.SyncState
import io.github.chibisueru.telegramdrive.ui.appViewModel
import io.github.chibisueru.telegramdrive.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val totalSize = c.dao.totalSize().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val fileCount = c.dao.fileCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val syncState = c.indexSync.state
    val busy = MutableStateFlow(false)

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setDynamicColor(v: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(v) }
    fun setThemeMode(v: Int) = viewModelScope.launch { c.settings.setThemeMode(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { c.settings.setWifiOnly(v) }
    fun setLockTimeout(v: LockTimeout) = viewModelScope.launch { c.settings.setLockTimeout(v) }
    fun setBiometric(v: Boolean) = viewModelScope.launch { c.settings.setBiometricUnlock(v) }
    fun setPin(pin: String) = run("PIN saved. The app now locks when you leave it.") { c.appLock.setPin(pin) }
    fun removePin() = run("PIN removed") { c.appLock.removePin() }
    suspend fun verifyPin(pin: String) = c.appLock.verify(pin)

    /** Turns camera backup on; [includeExisting] also queues the photos already on the phone. */
    fun enableCameraBackup(includeExisting: Boolean) = run(
        if (includeExisting) "Camera backup is on. Existing photos are being queued." else "Camera backup is on. New photos will be uploaded."
    ) {
        val last = if (includeExisting) 0L else withContext(Dispatchers.IO) { CameraBackup.newestMediaId(c.context) }
        c.settings.setCameraBackup(true, last)
        CameraBackup.schedule(c.context, c.settings.current())
        if (includeExisting && CameraBackup.queueNewMedia(c) > 0) c.transfers.start()
    }

    fun disableCameraBackup() = viewModelScope.launch {
        c.settings.setCameraBackup(false)
        CameraBackup.schedule(c.context, c.settings.current())
    }

    fun setCameraOption(update: suspend () -> Unit) = viewModelScope.launch {
        update()
        CameraBackup.schedule(c.context, c.settings.current())
    }
    fun setCameraVideos(v: Boolean) = setCameraOption { c.settings.setCameraVideos(v) }
    fun setCameraWifiOnly(v: Boolean) = setCameraOption { c.settings.setCameraWifiOnly(v) }
    fun setCameraChargingOnly(v: Boolean) = setCameraOption { c.settings.setCameraChargingOnly(v) }

    fun syncNow() = run("Index saved to Telegram") { c.indexSync.syncNow() }

    fun restore() = run("Drive restored from Telegram") {
        if (!c.indexSync.restoreFromTelegram()) error("No saved index found in the channel")
    }

    fun clearCache() = run("Cache cleared") {
        c.files.clearCache()
        withContext(Dispatchers.IO) { java.io.File(c.context.cacheDir, "upload").deleteRecursively() }
    }

    /** Saves the index first so nothing is lost, then signs out of Telegram on this phone. */
    fun signOut(onDone: () -> Unit) = run(null) {
        c.indexSync.syncNow()
        c.transfers.cancelAll()
        c.transfers.clearFinished()
        c.appLock.removePin()
        c.settings.setCameraBackup(false)
        CameraBackup.schedule(c.context, c.settings.current())
        c.telegram.logOut()
        c.settings.clearConnection()
        c.dao.clearAll()
        c.indexSync.forgetLocal()
        onDone()
    }

    fun showMessage(message: String) = viewModelScope.launch { _events.send(message) }

    private fun run(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            block()
            success?.let { _events.send(it) }
        } catch (e: Exception) {
            _events.send(e.message ?: "Something went wrong")
        } finally {
            busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSignedOut: () -> Unit) {
    val vm = appViewModel { c, _ -> SettingsViewModel(c) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val total by vm.totalSize.collectAsStateWithLifecycle()
    val count by vm.fileCount.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    val settings = s ?: return

    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_MEDIA_IMAGES] == true) confirm = "camera_existing"
        else vm.showMessage("Camera backup needs access to all photos and videos")
    }

    fun toggleCameraBackup(enable: Boolean) {
        if (!enable) { vm.disableCameraBackup(); return }
        val granted = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        if (granted) confirm = "camera_existing"
        else mediaPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
    }

    /** Turning biometrics on requires one successful scan, so we know it works. */
    fun toggleBiometric(enable: Boolean) {
        if (!enable) { vm.setBiometric(false); return }
        context.findFragmentActivity()?.promptBiometric(
            title = "Turn on biometric unlock",
            subtitle = "Confirm with your fingerprint or face",
            onSuccess = { vm.setBiometric(true) },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        // The bottom navigation bar (in MainScreen) already handles the bottom inset.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                actions = { if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Storage")
            ListItem(
                leadingContent = { Icon(Icons.Default.Storage, null) },
                headlineContent = { Text("${formatSize(total)} used") },
                supportingContent = { Text("$count file${if (count == 1) "" else "s"} · up to 2 GB per file (4 GB with Premium)") },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.AccountCircle, null) },
                headlineContent = { Text(settings.accountName.ifBlank { "Telegram account" }) },
                supportingContent = { Text("Files are stored in your channel “${settings.chatTitle.ifBlank { "Telegram Drive" }}”") },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Appearance")
            ListItem(
                modifier = Modifier.clickable { vm.setDynamicColor(!settings.dynamicColor) },
                leadingContent = { Icon(Icons.Default.Palette, null) },
                headlineContent = { Text("Dynamic color") },
                supportingContent = { Text("Use colors from your wallpaper (Material You)") },
                trailingContent = { Switch(checked = settings.dynamicColor, onCheckedChange = vm::setDynamicColor) },
            )
            val modes = listOf("System", "Light", "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                modes.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = settings.themeMode == i,
                        onClick = { vm.setThemeMode(i) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Transfers")
            ListItem(
                modifier = Modifier.clickable { vm.setWifiOnly(!settings.wifiOnly) },
                leadingContent = { Icon(Icons.Default.Wifi, null) },
                headlineContent = { Text("Wi-Fi only") },
                supportingContent = { Text("Upload and download only on Wi-Fi. On mobile data, transfers wait until you connect.") },
                trailingContent = { Switch(checked = settings.wifiOnly, onCheckedChange = vm::setWifiOnly) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Camera backup")
            ListItem(
                modifier = Modifier.clickable { toggleCameraBackup(!settings.cameraBackup) },
                leadingContent = { Icon(Icons.Default.PhotoCamera, null) },
                headlineContent = { Text("Back up camera photos") },
                supportingContent = { Text("New photos from the camera folder are uploaded to the “${CameraBackup.FOLDER_NAME}” folder automatically") },
                trailingContent = { Switch(checked = settings.cameraBackup, onCheckedChange = { toggleCameraBackup(it) }) },
            )
            if (settings.cameraBackup) {
                ListItem(
                    modifier = Modifier.clickable { vm.setCameraVideos(!settings.cameraVideos) },
                    leadingContent = { Icon(Icons.Default.Videocam, null) },
                    headlineContent = { Text("Include videos") },
                    trailingContent = { Switch(checked = settings.cameraVideos, onCheckedChange = vm::setCameraVideos) },
                )
                ListItem(
                    modifier = Modifier.clickable { vm.setCameraWifiOnly(!settings.cameraWifiOnly) },
                    leadingContent = { Icon(Icons.Default.Wifi, null) },
                    headlineContent = { Text("Only on Wi-Fi") },
                    trailingContent = { Switch(checked = settings.cameraWifiOnly, onCheckedChange = vm::setCameraWifiOnly) },
                )
                ListItem(
                    modifier = Modifier.clickable { vm.setCameraChargingOnly(!settings.cameraChargingOnly) },
                    leadingContent = { Icon(Icons.Default.BatteryChargingFull, null) },
                    headlineContent = { Text("Only while charging") },
                    trailingContent = { Switch(checked = settings.cameraChargingOnly, onCheckedChange = vm::setCameraChargingOnly) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Security")
            ListItem(
                modifier = Modifier.clickable { confirm = if (settings.pinEnabled) "pin_change" else "pin_new" },
                leadingContent = { Icon(Icons.Default.Pin, null) },
                headlineContent = { Text(if (settings.pinEnabled) "Change PIN" else "Lock with a PIN") },
                supportingContent = {
                    Text(if (settings.pinEnabled) "The app asks for your PIN when you open it" else "Ask for a PIN before anyone can open the app")
                },
            )
            if (settings.pinEnabled) {
                val biometricAvailable = remember { canUseBiometrics(context) }
                ListItem(
                    modifier = Modifier.clickable(enabled = biometricAvailable) { toggleBiometric(!settings.biometricUnlock) },
                    leadingContent = { Icon(Icons.Default.Fingerprint, null) },
                    headlineContent = { Text("Unlock with fingerprint or face") },
                    supportingContent = {
                        Text(
                            if (biometricAvailable) "Your PIN still works if biometrics fail"
                            else "Set up a fingerprint or face unlock in Android settings first"
                        )
                    },
                    trailingContent = {
                        Switch(checked = settings.biometricUnlock, onCheckedChange = { toggleBiometric(it) }, enabled = biometricAvailable)
                    },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Default.Timer, null) },
                    headlineContent = { Text("Lock after leaving the app") },
                )
                val timeouts = listOf(LockTimeout.IMMEDIATELY to "Immediately", LockTimeout.ONE_MINUTE to "1 min", LockTimeout.FIVE_MINUTES to "5 min")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    timeouts.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = settings.lockTimeout == value,
                            onClick = { vm.setLockTimeout(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, timeouts.size),
                        ) { Text(label) }
                    }
                }
                ListItem(
                    modifier = Modifier.clickable { confirm = "pin_remove" },
                    leadingContent = { Icon(Icons.Default.LockOpen, null) },
                    headlineContent = { Text("Remove PIN") },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Sync")
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { vm.syncNow() },
                leadingContent = { Icon(Icons.Default.CloudSync, null) },
                headlineContent = { Text("Save index now") },
                supportingContent = {
                    Text(
                        when (val st = sync) {
                            SyncState.Syncing -> "Saving…"
                            is SyncState.Failed -> "Last save failed: ${st.message}"
                            SyncState.Idle -> if (settings.lastSyncAt > 0) {
                                "Last saved " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSyncAt))
                            } else "The folder structure is saved in your channel"
                        },
                        color = if (sync is SyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { confirm = "restore" },
                leadingContent = { Icon(Icons.Default.CloudDownload, null) },
                headlineContent = { Text("Restore from Telegram") },
                supportingContent = { Text("Replace this phone's folder list with the one saved in the channel") },
            )
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { vm.clearCache() },
                leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                headlineContent = { Text("Clear cache") },
                supportingContent = { Text("Delete thumbnails, previews and streamed media stored on this phone") },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) { confirm = "signout" },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Your files stay in the channel; sign in again to get them back") },
            )
        }
    }

    when (confirm) {
        "camera_existing" -> AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.PhotoCamera, null) },
            title = { Text("Back up existing photos too?") },
            text = { Text("Choose whether to upload only photos you take from now on, or also everything already in your camera folder.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.enableCameraBackup(includeExisting = true) }) { Text("Include existing") } },
            dismissButton = { TextButton(onClick = { confirm = null; vm.enableCameraBackup(includeExisting = false) }) { Text("Only new photos") } },
        )
        "pin_new" -> NewPinDialog(onDismiss = { confirm = null }) { vm.setPin(it); confirm = null }
        "pin_change" -> ConfirmPinDialog("Change PIN", onDismiss = { confirm = null }, onVerify = vm::verifyPin) { confirm = "pin_new" }
        "pin_remove" -> ConfirmPinDialog("Remove PIN", onDismiss = { confirm = null }, onVerify = vm::verifyPin) { vm.removePin(); confirm = null }
        "restore" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restore from Telegram?") },
            text = { Text("Changes on this device that weren't saved yet will be lost.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.restore() }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "signout" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sign out?") },
            text = { Text("The index is saved to the channel first, then this phone's Telegram session is ended. Running transfers are cancelled.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.signOut(onSignedOut) }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
