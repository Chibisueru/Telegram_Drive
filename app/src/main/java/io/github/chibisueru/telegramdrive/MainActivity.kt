package io.github.chibisueru.telegramdrive

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.chibisueru.telegramdrive.security.canUseBiometrics
import io.github.chibisueru.telegramdrive.security.promptBiometric
import io.github.chibisueru.telegramdrive.transfer.TransferNotifications
import io.github.chibisueru.telegramdrive.ui.MainScreen
import io.github.chibisueru.telegramdrive.ui.Tab
import io.github.chibisueru.telegramdrive.ui.security.LockScreen
import io.github.chibisueru.telegramdrive.ui.setup.SetupScreen
import io.github.chibisueru.telegramdrive.ui.theme.TgDriveTheme
import io.github.chibisueru.telegramdrive.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** A FragmentActivity because the system biometric prompt needs one. */
class MainActivity : FragmentActivity() {
    /** Set when a notification asks to open a specific tab. */
    private val requestedTab = MutableStateFlow<Tab?>(null)
    /** Files shared into the app ("Share → Telegram Drive"), waiting for a destination folder. */
    private val sharedFiles = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val c = container
        setContent {
            val settings by c.settings.flow.collectAsStateWithLifecycle(initialValue = null)
            val locked by c.appLock.locked.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            // Keep the app's content out of the recent-apps preview while a PIN is set.
            LaunchedEffect(s.pinEnabled) { setRecentsScreenshotEnabled(!s.pinEnabled) }

            TgDriveTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        AppNav(
                            startConfigured = s.isConfigured,
                            sessionLost = c.telegram.sessionLost,
                            requestedTab = requestedTab,
                            sharedFiles = sharedFiles,
                        )
                    }
                    if (s.pinEnabled && locked) {
                        val biometric = s.biometricUnlock && remember { canUseBiometrics(this@MainActivity) }
                        LockScreen(
                            onVerify = c.appLock::verify,
                            onBiometric = if (biometric) ({
                                promptBiometric(
                                    title = "Unlock Telegram Drive",
                                    subtitle = "Use your fingerprint or face, or cancel to enter your PIN",
                                    onSuccess = { c.appLock.unlock() },
                                )
                            }) else null,
                            onLeave = { moveTaskToBack(true) },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Pick up uploads/downloads left over from earlier (the service can only start while visible).
        container.transfers.resumeIfPending()
        // Pick up changes made on another device (another phone or the desktop app).
        container.scope.launch { runCatching { container.indexSync.refreshIfChanged() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(TransferNotifications.EXTRA_OPEN_TAB) == TransferNotifications.TAB_TRANSFERS) {
            requestedTab.value = Tab.TRANSFERS
        }
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (shared.isEmpty()) {
            Toast.makeText(this, "Only files can be shared to Telegram Drive", Toast.LENGTH_SHORT).show()
            return
        }
        sharedFiles.value = shared
    }
}

@Composable
private fun AppNav(
    startConfigured: Boolean,
    sessionLost: StateFlow<Boolean>,
    requestedTab: MutableStateFlow<Tab?>,
    sharedFiles: MutableStateFlow<List<Uri>>,
) {
    val nav = rememberNavController()
    val start = remember { if (startConfigured) "main" else "setup" }
    val lost by sessionLost.collectAsStateWithLifecycle()
    LaunchedEffect(lost) {
        // The saved session was ended (e.g. from Telegram's "Active sessions"): log in again.
        if (lost && nav.currentDestination?.route != "setup") {
            nav.navigate("setup") { popUpTo(0) { inclusive = true } }
        }
    }
    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable("setup") {
            val pending by sharedFiles.collectAsStateWithLifecycle()
            val context = LocalContext.current
            LaunchedEffect(pending) {
                if (pending.isNotEmpty()) {
                    Toast.makeText(context, "Log in first, then share the files again", Toast.LENGTH_LONG).show()
                    sharedFiles.value = emptyList()
                }
            }
            SetupScreen(onConnected = {
                nav.navigate("main") { popUpTo("setup") { inclusive = true } }
            })
        }
        composable("main") {
            MainScreen(
                requestedTab = requestedTab,
                sharedFiles = sharedFiles,
                onOpenFile = { nav.navigate("viewer/$it") },
                onSignedOut = { nav.navigate("setup") { popUpTo(0) { inclusive = true } } },
            )
        }
        composable("viewer/{id}") { entry ->
            ViewerScreen(fileId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
