package io.github.chibisueru.telegramdrive.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.security.PinResult
import kotlinx.coroutines.launch

const val PIN_MIN = 4
const val PIN_MAX = 8

/**
 * Full-screen lock shown over the app. [onBiometric] (if set) opens the system prompt; the PIN
 * pad always stays usable, so a failed fingerprint or face scan never locks the user out.
 */
@Composable
fun LockScreen(onVerify: suspend (String) -> PinResult, onBiometric: (() -> Unit)?, onLeave: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { onLeave() } // back leaves the app instead of revealing it
    LaunchedEffect(Unit) { onBiometric?.invoke() }

    fun submit() {
        if (pin.length < PIN_MIN || checking) return
        checking = true
        scope.launch {
            when (val r = onVerify(pin)) {
                PinResult.Ok -> message = null
                is PinResult.Wrong -> message = "Wrong PIN. ${r.attemptsLeft} attempt${if (r.attemptsLeft == 1) "" else "s"} left."
                is PinResult.LockedOut -> message = "Too many attempts. Try again in ${r.seconds} s."
            }
            pin = ""
            checking = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("Enter your PIN", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.padding(vertical = 24.dp).height(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(PIN_MIN, pin.length)) { i ->
                    Box(
                        Modifier.size(14.dp).background(
                            if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                    )
                }
            }
            Text(
                message ?: " ",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { key ->
                            FilledTonalIconButton(
                                onClick = {
                                    when (key) {
                                        "⌫" -> pin = pin.dropLast(1)
                                        "✓" -> submit()
                                        else -> if (pin.length < PIN_MAX) pin += key
                                    }
                                },
                                enabled = !checking && (key != "✓" || pin.length >= PIN_MIN),
                                modifier = Modifier.size(72.dp),
                            ) {
                                when (key) {
                                    "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                                    "✓" -> Icon(Icons.Default.Check, "Unlock")
                                    else -> Text(key, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (onBiometric != null) {
                OutlinedButton(onClick = onBiometric, modifier = Modifier.padding(top = 24.dp)) {
                    Icon(Icons.Default.Fingerprint, null, Modifier.padding(end = 8.dp))
                    Text("Use fingerprint or face")
                }
            }
        }
    }
}

/** Asks for a new PIN twice. */
@Composable
fun NewPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = first.length in PIN_MIN..PIN_MAX
    val mismatch = second.isNotEmpty() && second != first
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$PIN_MIN–$PIN_MAX digits. You'll need it to open the app.")
                PinField(first, { first = it }, "New PIN")
                PinField(second, { second = it }, "Repeat PIN", isError = mismatch)
                if (mismatch) Text("The PINs don't match", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(first) }, enabled = valid && second == first) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for the current PIN before changing or removing it. */
@Composable
fun ConfirmPinDialog(title: String, onDismiss: () -> Unit, onVerify: suspend (String) -> PinResult, onConfirmed: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField(pin, { pin = it; error = null }, "Current PIN", isError = error != null)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        when (val r = onVerify(pin)) {
                            PinResult.Ok -> onConfirmed()
                            is PinResult.Wrong -> { error = "Wrong PIN. ${r.attemptsLeft} attempts left."; pin = "" }
                            is PinResult.LockedOut -> { error = "Too many attempts. Try again in ${r.seconds} s."; pin = "" }
                        }
                    }
                },
                enabled = pin.length >= PIN_MIN,
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(PIN_MAX)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
