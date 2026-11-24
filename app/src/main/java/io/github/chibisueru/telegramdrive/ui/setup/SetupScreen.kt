package io.github.chibisueru.telegramdrive.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val token: String = "",
    val chatId: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class SetupViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(SetupState())

    fun setToken(v: String) = state.update { it.copy(token = v.trim(), error = null) }
    fun setChatId(v: String) = state.update { it.copy(chatId = v.trim(), error = null) }

    /** Finds the channel the bot was most recently added to or saw a post in. */
    fun detectChat() = viewModelScope.launch {
        val token = state.value.token
        if (token.isBlank()) {
            state.update { it.copy(error = "Enter the bot token first") }
            return@launch
        }
        state.update { it.copy(busy = true, error = null, message = null) }
        try {
            val updates = c.bot.getUpdates(token)
            var found: Pair<Long, String>? = null
            for (i in updates.length() - 1 downTo 0) {
                val u = updates.getJSONObject(i)
                val chat = (u.optJSONObject("channel_post") ?: u.optJSONObject("my_chat_member")
                    ?: u.optJSONObject("message"))?.optJSONObject("chat") ?: continue
                if (chat.optString("type") in setOf("channel", "supergroup", "group")) {
                    found = chat.getLong("id") to chat.optString("title")
                    break
                }
            }
            state.update {
                if (found != null) it.copy(busy = false, chatId = found.first.toString(), message = "Found “${found.second}”")
                else it.copy(busy = false, error = "No channel found yet. Add the bot as an admin, post any message in the channel, then try again.")
            }
        } catch (e: Exception) {
            state.update { it.copy(busy = false, error = e.message ?: "Detection failed") }
        }
    }

    fun connect(onConnected: () -> Unit) = viewModelScope.launch {
        val s = state.value
        if (s.token.isBlank() || s.chatId.isBlank()) {
            state.update { it.copy(error = "Enter both the bot token and the channel ID") }
            return@launch
        }
        state.update { it.copy(busy = true, error = null, message = "Checking bot…") }
        try {
            val me = c.bot.getMe(s.token)
            val chat = c.bot.getChat(s.chatId, s.token)
            val chatId = chat.getLong("id").toString()
            val member = c.bot.getChatMember(chatId, me.getLong("id"), s.token)
            if (member.optString("status") != "administrator") {
                error("The bot must be an administrator of the channel")
            }
            if (chat.optString("type") == "channel" &&
                (!member.optBoolean("can_post_messages") || !member.optBoolean("can_delete_messages"))
            ) {
                error("Give the bot the “Post messages” and “Delete messages” admin rights")
            }
            c.settings.saveConnection(s.token, chatId, me.optString("username"), chat.optString("title"))
            c.dao.clearAll()
            state.update { it.copy(message = "Looking for an existing drive…") }
            val restored = runCatching { c.indexSync.restoreFromTelegram() }.getOrDefault(false)
            if (!restored) c.indexSync.requestSync()
            state.update { it.copy(busy = false, message = null) }
            onConnected()
        } catch (e: Exception) {
            c.settings.clearConnection()
            state.update { it.copy(busy = false, message = null, error = e.message ?: "Could not connect") }
        }
    }
}

@Composable
fun SetupScreen(onConnected: () -> Unit) {
    val vm = appViewModel { c, _ -> SetupViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showToken by remember { mutableStateOf(false) }

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
            Text(
                "Your files are stored in a private Telegram channel through your own bot. Set it up once:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Step(1, "In Telegram, open @BotFather, send /newbot and copy the token it gives you.")
                    Step(2, "Create a new private channel (this is your storage).")
                    Step(3, "Add your bot to the channel as an administrator with Post, Edit and Delete messages rights.")
                    Step(4, "Post any message in the channel, then tap “Detect” below.")
                }
            }

            OutlinedTextField(
                value = state.token,
                onValueChange = vm::setToken,
                label = { Text("Bot token") },
                placeholder = { Text("123456789:AA…") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show token")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.chatId,
                    onValueChange = vm::setChatId,
                    label = { Text("Channel ID") },
                    placeholder = { Text("-100… or @channel") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { vm.detectChat() }, enabled = !state.busy) { Text("Detect") }
            }

            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.connect(onConnected) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Connect")
            }
            Text(
                "If the channel already holds a drive from another device, it is restored automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
