package io.github.chibisueru.telegramdrive.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.chibisueru.telegramdrive.telegram.LoginStep
import io.github.chibisueru.telegramdrive.ui.appViewModel

@Composable
fun SetupScreen(onConnected: () -> Unit) {
    val vm = appViewModel { c, _ -> SetupViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val step by vm.telegram.step.collectAsStateWithLifecycle()
    val loginError by vm.telegram.error.collectAsStateWithLifecycle()
    val sessionLost by vm.telegram.sessionLost.collectAsStateWithLifecycle()

    LaunchedEffect(state.connected) { if (state.connected) onConnected() }
    val inLoginFlow = step !is LoginStep.Credentials && step !is LoginStep.Connecting
    BackHandler(enabled = inLoginFlow && !state.busy) { vm.startOver() }

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("Welcome to Telegram Drive", style = MaterialTheme.typography.headlineMedium)

            if (sessionLost && step is LoginStep.Credentials) {
                Text(
                    "Your Telegram session on this phone ended. Log in again to get back to your drive.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            run {
                AnimatedContent(step::class, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "login") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (val s = step) {
                            LoginStep.Credentials, LoginStep.Connecting -> CredentialsForm(vm, state, connecting = s == LoginStep.Connecting)
                            is LoginStep.Code -> SingleFieldStep(
                                text = if (s.inTelegramApp) "Telegram sent a login code to your Telegram app (chat “Telegram”). Enter it here."
                                else "Enter the login code sent to ${s.phone}.",
                                label = "Login code",
                                value = state.code,
                                onValue = { v -> vm.update { it.copy(code = v) } },
                                keyboardType = KeyboardType.NumberPassword,
                                onSubmit = vm::submitCode,
                                onStartOver = vm::startOver,
                            )
                            is LoginStep.Password -> SingleFieldStep(
                                text = "Your account has two-step verification. Enter your Telegram password." +
                                    if (s.hint.isNotBlank()) "\nHint: ${s.hint}" else "",
                                label = "Password",
                                value = state.password,
                                onValue = { v -> vm.update { it.copy(password = v) } },
                                keyboardType = KeyboardType.Password,
                                secret = true,
                                onSubmit = vm::submitPassword,
                                onStartOver = vm::startOver,
                            )
                            LoginStep.Email -> SingleFieldStep(
                                text = "Telegram asks for an email address to finish logging in.",
                                label = "Email",
                                value = state.email,
                                onValue = { v -> vm.update { it.copy(email = v) } },
                                keyboardType = KeyboardType.Email,
                                onSubmit = vm::submitEmail,
                                onStartOver = vm::startOver,
                            )
                            is LoginStep.EmailCode -> SingleFieldStep(
                                text = "Enter the code sent to ${s.pattern}.",
                                label = "Email code",
                                value = state.code,
                                onValue = { v -> vm.update { it.copy(code = v) } },
                                keyboardType = KeyboardType.NumberPassword,
                                onSubmit = vm::submitEmailCode,
                                onStartOver = vm::startOver,
                            )
                            LoginStep.Ready -> Provisioning(vm, state)
                        }
                    }
                }
            }

            (state.error ?: loginError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        }
    }
}

@Composable
private fun CredentialsForm(vm: SetupViewModel, state: SetupState, connecting: Boolean) {
    val uri = LocalUriHandler.current
    Text(
        "Your files are stored in a private channel in your own Telegram account. Log in once and the app sets everything up.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(1, "Open my.telegram.org and log in with your phone number.")
            Step(2, "Tap “API development tools” and create an app. Any title and short name are fine.")
            Step(3, "Copy “App api_id” and “App api_hash” into the fields below.")
            FilledTonalButton(onClick = { uri.openUri("https://my.telegram.org/apps") }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(end = 8.dp).size(18.dp))
                Text("Open my.telegram.org")
            }
        }
    }
    OutlinedTextField(
        value = state.apiId,
        onValueChange = { v -> vm.update { it.copy(apiId = v.filter(Char::isDigit)) } },
        label = { Text("API ID") },
        placeholder = { Text("1234567") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.apiHash,
        onValueChange = { v -> vm.update { it.copy(apiHash = v.trim()) } },
        label = { Text("API hash") },
        placeholder = { Text("0123456789abcdef0123456789abcdef") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.phone,
        onValueChange = { v -> vm.update { it.copy(phone = v) } },
        label = { Text("Phone number") },
        placeholder = { Text("+491701234567") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = vm::login, enabled = !connecting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (connecting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Log in")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "The app talks to Telegram directly with your own credentials; there is no server in between. " +
                "If you already made a drive on another phone, it is reconnected instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SingleFieldStep(
    text: String,
    label: String,
    value: String,
    onValue: (String) -> Unit,
    keyboardType: KeyboardType,
    onSubmit: () -> Unit,
    onStartOver: () -> Unit,
    secret: Boolean = false,
) {
    var show by remember { mutableStateOf(false) }
    Text(text, style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        trailingIcon = if (secret) {
            { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show") } }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onSubmit, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue") }
    TextButton(onClick = onStartOver) { Text("Start over") }
}

@Composable
private fun Provisioning(vm: SetupViewModel, state: SetupState) {
    if (state.busy) {
        Text(state.status ?: "Setting up…", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "This takes a few seconds. A private channel called “Telegram Drive” appears in your Telegram.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.error != null) {
        Text("Setup didn't finish", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.provision() }) { Text("Try again") }
            TextButton(onClick = vm::startOver) { Text("Cancel") }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
