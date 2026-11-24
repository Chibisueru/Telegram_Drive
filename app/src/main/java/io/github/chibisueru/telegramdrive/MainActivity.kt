package io.github.chibisueru.telegramdrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.chibisueru.telegramdrive.ui.browser.BrowserScreen
import io.github.chibisueru.telegramdrive.ui.settings.SettingsScreen
import io.github.chibisueru.telegramdrive.ui.setup.SetupScreen
import io.github.chibisueru.telegramdrive.ui.theme.TgDriveTheme
import io.github.chibisueru.telegramdrive.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsFlow = container.settings.flow
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            TgDriveTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    AppNav(startConfigured = s.isConfigured, sessionLost = container.telegram.sessionLost)
                }
            }
        }
    }
}

@Composable
private fun AppNav(startConfigured: Boolean, sessionLost: StateFlow<Boolean>) {
    val nav = rememberNavController()
    val start = remember { if (startConfigured) "browser" else "setup" }
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
            SetupScreen(onConnected = {
                nav.navigate("browser") { popUpTo("setup") { inclusive = true } }
            })
        }
        composable("browser") {
            BrowserScreen(
                onOpenFile = { nav.navigate("viewer/$it") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("viewer/{id}") { entry ->
            ViewerScreen(fileId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onSignedOut = { nav.navigate("setup") { popUpTo(0) { inclusive = true } } },
            )
        }
    }
}
