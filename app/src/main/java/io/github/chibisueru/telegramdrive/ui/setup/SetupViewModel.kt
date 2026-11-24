package io.github.chibisueru.telegramdrive.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.telegram.DriveProvisioner
import io.github.chibisueru.telegramdrive.telegram.LoginStep
import io.github.chibisueru.telegramdrive.telegram.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val apiId: String = "",
    val apiHash: String = "",
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val email: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val connected: Boolean = false,
)

class SetupViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(SetupState())
    val telegram = c.telegram
    private var provisioning = false

    init {
        viewModelScope.launch {
            // After a lost session, keep the API credentials the user entered before.
            val s = c.settings.current()
            if (s.apiId != 0) state.update { it.copy(apiId = s.apiId.toString(), apiHash = s.apiHash) }
        }
        viewModelScope.launch {
            telegram.step.collect { step ->
                if (step is LoginStep.Ready && !provisioning && !state.value.connected) provision()
            }
        }
    }

    fun update(transform: (SetupState) -> SetupState) = state.update { transform(it).copy(error = null) }

    fun login() {
        val s = state.value
        val id = s.apiId.trim().toIntOrNull()
        when {
            id == null -> state.update { it.copy(error = "The API ID is a number, e.g. 1234567") }
            s.apiHash.trim().length < 20 -> state.update { it.copy(error = "Paste the full API hash (32 characters)") }
            s.phone.count { it.isDigit() } < 7 -> state.update { it.copy(error = "Enter your phone number with country code, e.g. +491701234567") }
            else -> {
                state.update { it.copy(error = null) }
                telegram.login(id, s.apiHash, s.phone)
            }
        }
    }

    fun submitCode() = telegram.submitCode(state.value.code).also { state.update { it.copy(code = "") } }
    fun submitPassword() = telegram.submitPassword(state.value.password).also { state.update { it.copy(password = "") } }
    fun submitEmail() = telegram.submitEmail(state.value.email)
    fun submitEmailCode() = telegram.submitEmailCode(state.value.code).also { state.update { it.copy(code = "") } }

    fun startOver() {
        telegram.cancelLogin()
        state.update { it.copy(busy = false, status = null, error = null, code = "", password = "") }
    }

    /** Signed in: reconnect to the drive channel made earlier, or create one. */
    fun provision() = viewModelScope.launch {
        provisioning = true
        state.update { it.copy(busy = true, error = null, status = "Looking for your drive…") }
        try {
            val client = telegram.awaitReady()
            val provisioner = DriveProvisioner(client)
            val existing = provisioner.findExistingDrive()
            val channel = existing ?: run {
                state.update { it.copy(status = "Creating your private storage channel…") }
                provisioner.createChannel()
            }
            val me = client.getMe().get()
            val s = state.value
            c.settings.saveConnection(
                apiId = s.apiId.trim().toInt(),
                apiHash = s.apiHash.trim(),
                chatId = channel.chatId,
                chatTitle = channel.title,
                accountName = listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" "),
            )
            c.dao.clearAll()
            c.indexSync.forgetLocal()
            state.update { it.copy(status = if (existing != null) "Restoring your folders…" else "Finishing up…") }
            val restored = existing != null && runCatching { c.indexSync.restoreFromTelegram() }.getOrDefault(false)
            if (!restored) runCatching { c.indexSync.syncNow() }
            state.update { it.copy(busy = false, status = null, connected = true) }
        } catch (e: Exception) {
            state.update { it.copy(busy = false, status = null, error = e.message ?: "Setup failed") }
        } finally {
            provisioning = false
        }
    }
}
