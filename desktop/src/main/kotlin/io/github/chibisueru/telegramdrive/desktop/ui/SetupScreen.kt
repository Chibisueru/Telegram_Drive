package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.data.openUrl
import io.github.chibisueru.telegramdrive.desktop.telegram.DriveProvisioner
import io.github.chibisueru.telegramdrive.desktop.telegram.LoginStep
import io.github.chibisueru.telegramdrive.desktop.telegram.get
import kotlinx.coroutines.launch

/** First start: API credentials from my.telegram.org, Telegram login, then find or create the drive channel. */
@Composable
fun SetupScreen(c: AppContainer, onConnected: () -> Unit) {
    val step by c.telegram.step.collectAsState()
    val loginError by c.telegram.error.collectAsState()
    val scope = rememberCoroutineScope()
    val saved = remember { c.settings.current }
    var apiId by remember { mutableStateOf(saved.apiId.takeIf { it != 0 }?.toString() ?: "") }
    var apiHash by remember { mutableStateOf(saved.apiHash) }
    var phone by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var provisioning by remember { mutableStateOf(false) }

    fun provision() = scope.launch {
        if (provisioning) return@launch
        provisioning = true
        setupError = null
        status = "Looking for your drive…"
        try {
            val client = c.telegram.awaitReady()
            val provisioner = DriveProvisioner(client)
            val existing = provisioner.findExistingDrive()
            val channel = existing ?: run {
                status = "Creating your private storage channel…"
                provisioner.createChannel()
            }
            val me = client.getMe().get()
            c.settings.update {
                it.copy(
                    apiId = apiId.trim().toInt(), apiHash = apiHash.trim(),
                    chatId = channel.chatId, chatTitle = channel.title,
                    accountName = listOf(me.firstName, me.lastName).filter { n -> n.isNotBlank() }.joinToString(" "),
                    indexMessageId = 0, lastSyncAt = 0,
                )
            }
            c.drive.clearLocal()
            status = if (existing != null) "Restoring your folders…" else "Finishing up…"
            val restored = existing != null && runCatching { c.drive.restoreFromTelegram() }.getOrDefault(false)
            if (!restored) runCatching { c.drive.syncNow() }
            status = null
            onConnected()
        } catch (e: Exception) {
            status = null
            setupError = e.message ?: "Setup failed"
        } finally {
            provisioning = false
        }
    }

    // A saved session that still works (e.g. the drive was never chosen): go straight on.
    LaunchedEffect(step) {
        input = ""
        if (step is LoginStep.Ready) provision()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("Welcome to Telegram Drive", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your files are stored in a private Telegram channel that only you can see. " +
                    "Phones and computers signed in to the same account share the same drive.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val error = setupError ?: loginError
            when {
                status != null || step is LoginStep.Connecting || step is LoginStep.Ready && setupError == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(status ?: "Connecting to Telegram…", style = MaterialTheme.typography.titleMedium)
                    }
                    if (step is LoginStep.Connecting) TextButton(onClick = { c.telegram.cancelLogin() }) { Text("Cancel") }
                }
                setupError != null -> {
                    ErrorText(setupError!!)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { provision() }) { Text("Try again") }
                        TextButton(onClick = { setupError = null; c.telegram.cancelLogin() }) { Text("Start over") }
                    }
                }
                step is LoginStep.Credentials -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Get your API ID and hash", style = MaterialTheme.typography.titleMedium)
                            StepLine(1, "Open my.telegram.org and log in with your phone number.")
                            StepLine(2, "Choose “API development tools” and create an app (any name works).")
                            StepLine(3, "Copy the “App api_id” and “App api_hash” below.")
                            FilledTonalButton(onClick = { openUrl("https://my.telegram.org/apps") }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                                Text("  Open my.telegram.org")
                            }
                        }
                    }
                    OutlinedTextField(apiId, { apiId = it.filter(Char::isDigit) }, label = { Text("API ID") },
                        placeholder = { Text("1234567") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OutlinedTextField(apiHash, { apiHash = it.trim() }, label = { Text("API hash") },
                        placeholder = { Text("0123456789abcdef0123456789abcdef") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val canLogin = apiId.isNotBlank() && apiHash.length >= 16 && phone.count(Char::isDigit) >= 6
                    fun login() { if (canLogin) c.telegram.login(apiId.trim().toInt(), apiHash.trim(), phone) }
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") },
                        placeholder = { Text("+491701234567") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { login() }))
                    error?.let { ErrorText(it) }
                    Button(onClick = { login() }, enabled = canLogin, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Log in") }
                }
                else -> {
                    val (title, label, secret) = when (val s = step) {
                        is LoginStep.Code -> Triple(
                            if (s.inTelegramApp) "Telegram sent a code to your Telegram app on another device." else "Telegram sent a code to ${s.phone}.",
                            "Code", false,
                        )
                        is LoginStep.Password -> Triple("Your account has two-step verification. Enter your cloud password.",
                            if (s.hint.isNotBlank()) "Password (hint: ${s.hint})" else "Password", true)
                        is LoginStep.Email -> Triple("Telegram asks for an email address for login codes.", "Email address", false)
                        is LoginStep.EmailCode -> Triple("Enter the code sent to ${s.pattern}.", "Email code", false)
                        else -> Triple("", "", false)
                    }
                    fun submit() {
                        if (input.isBlank()) return
                        when (step) {
                            is LoginStep.Code -> c.telegram.submitCode(input)
                            is LoginStep.Password -> c.telegram.submitPassword(input)
                            is LoginStep.Email -> c.telegram.submitEmail(input)
                            is LoginStep.EmailCode -> c.telegram.submitEmailCode(input)
                            else -> Unit
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(input, { input = it }, label = { Text(label) }, singleLine = true,
                        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                    error?.let { ErrorText(it) }
                    Button(onClick = { submit() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continue") }
                    TextButton(onClick = { c.telegram.cancelLogin() }) { Text("Start over") }
                }
            }
        }
    }
}

@Composable
private fun StepLine(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
