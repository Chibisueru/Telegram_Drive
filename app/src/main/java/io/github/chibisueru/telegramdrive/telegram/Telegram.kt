package io.github.chibisueru.telegramdrive.telegram

import android.content.Context
import android.os.Build
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

class TelegramException(message: String, val code: Int = 0) : IOException(message)

/** Where the user is in Telegram's login flow. */
sealed interface LoginStep {
    data object Credentials : LoginStep
    data object Connecting : LoginStep
    data class Code(val phone: String, val inTelegramApp: Boolean) : LoginStep
    data class Password(val hint: String) : LoginStep
    data object Email : LoginStep
    data class EmailCode(val pattern: String) : LoginStep
    data object Ready : LoginStep
}

/** Unwraps a TDLib result, turning failures into readable exceptions. */
fun <T> TdlResult<T>.get(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TelegramException(friendlyTdlError(message), code)
}

fun friendlyTdlError(raw: String): String = when {
    "API_ID_INVALID" in raw || "API_ID_PUBLISHED_FLOOD" in raw -> "The API ID / API hash is invalid. Copy them again from my.telegram.org."
    "PHONE_NUMBER_INVALID" in raw -> "That phone number isn't valid. Use the international format, e.g. +491701234567."
    "PHONE_NUMBER_BANNED" in raw -> "This phone number is banned from Telegram."
    "PHONE_CODE_INVALID" in raw -> "Wrong code. Check it and try again."
    "PHONE_CODE_EXPIRED" in raw -> "The code expired. Start again to get a new one."
    "PASSWORD_HASH_INVALID" in raw -> "Wrong password."
    "EMAIL_CODE_INVALID" in raw -> "Wrong email code."
    "FILE_PARTS_INVALID" in raw || "FILE_TOO_BIG" in raw -> "The file is too big for Telegram (2 GB, or 4 GB with Premium)."
    "FLOOD_WAIT" in raw || "Too Many Requests" in raw -> "Too many attempts. Telegram asks you to wait before trying again ($raw)."
    else -> raw
}

/**
 * The app's single connection to the user's Telegram account (TDLib, MTProto). The session is
 * kept in app-private, non-backed-up storage so the user stays signed in across restarts.
 */
class Telegram(context: Context, private val scope: CoroutineScope) {
    private val dbDir = File(context.noBackupFilesDir, "tdlib")
    private val filesDir = File(context.cacheDir, "tdlib_files")

    @Volatile private var _client: TdlClient? = null
    private var stateJob: Job? = null
    @Volatile private var lastState: AuthorizationState? = null
    private val startMutex = Mutex()
    @Volatile private var closingOnPurpose = false

    private var apiId = 0
    private var apiHash = ""
    /** Null while resuming a saved session: a phone prompt then means the session is gone. */
    private var phone: String? = null

    private val _step = MutableStateFlow<LoginStep>(LoginStep.Credentials)
    val step: StateFlow<LoginStep> = _step
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** True when a saved session stopped working (e.g. ended from another device). */
    private val _sessionLost = MutableStateFlow(false)
    val sessionLost: StateFlow<Boolean> = _sessionLost

    val client: TdlClient get() = _client ?: throw TelegramException("Not connected to Telegram")

    /** Suspends until signed in, then returns the client. */
    suspend fun awaitReady(timeoutMs: Long = 60_000): TdlClient {
        withTimeoutOrNull(timeoutMs) { step.first { it is LoginStep.Ready } }
            ?: throw TelegramException(
                if (sessionLost.value) "Signed out of Telegram. Open the app to log in again." else "Couldn't connect to Telegram"
            )
        return client
    }

    /** Reconnects with the saved session at app start. */
    fun resume(apiId: Int, apiHash: String) = launchStart(apiId, apiHash, phone = null, fresh = false)

    /** Starts a brand-new login, discarding any previous session on this device. */
    fun login(apiId: Int, apiHash: String, phone: String) =
        launchStart(apiId, apiHash, phone.filter { it.isDigit() || it == '+' }, fresh = true)

    private fun launchStart(apiId: Int, apiHash: String, phone: String?, fresh: Boolean) = scope.launch {
        startMutex.withLock {
            shutdown()
            if (fresh) wipe()
            this@Telegram.apiId = apiId
            this@Telegram.apiHash = apiHash.trim()
            this@Telegram.phone = phone
            _error.value = null
            _sessionLost.value = false
            _step.value = LoginStep.Connecting
            closingOnPurpose = false
            val c = TdlClient.create()
            _client = c
            stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                c.authorizationStateUpdates.collect { onState(c, it.authorizationState) }
            }
            c.setLogVerbosityLevel(1)
            c.getAuthorizationState()
        }
    }

    private suspend fun onState(c: TdlClient, state: AuthorizationState) {
        lastState = state
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> request(resetOnFail = true) {
                c.setTdlibParameters(
                    useTestDc = false,
                    databaseDirectory = dbDir.absolutePath,
                    filesDirectory = filesDir.absolutePath,
                    databaseEncryptionKey = ByteArray(0),
                    useFileDatabase = true,
                    useChatInfoDatabase = true,
                    useMessageDatabase = true,
                    useSecretChats = false,
                    apiId = apiId,
                    apiHash = apiHash,
                    systemLanguageCode = "en",
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    applicationVersion = "Telegram Drive",
                ).get()
            }
            is AuthorizationStateWaitPhoneNumber -> {
                val p = phone
                if (p == null) {
                    _sessionLost.value = true
                    _step.value = LoginStep.Credentials
                } else {
                    request(resetOnFail = true) { c.setAuthenticationPhoneNumber(phoneNumber = p).get() }
                }
            }
            is AuthorizationStateWaitCode -> _step.value = LoginStep.Code(
                state.codeInfo.phoneNumber,
                state.codeInfo.type is AuthenticationCodeTypeTelegramMessage,
            )
            is AuthorizationStateWaitPassword -> _step.value = LoginStep.Password(state.passwordHint)
            is AuthorizationStateWaitEmailAddress -> _step.value = LoginStep.Email
            is AuthorizationStateWaitEmailCode -> _step.value = LoginStep.EmailCode(state.codeInfo.emailAddressPattern)
            is AuthorizationStateReady -> _step.value = LoginStep.Ready
            is AuthorizationStateWaitRegistration -> {
                _error.value = "This phone number has no Telegram account yet. Sign up in the Telegram app first."
                _step.value = LoginStep.Credentials
            }
            is AuthorizationStateClosed -> if (!closingOnPurpose) {
                // Logged out remotely (e.g. session terminated in Telegram's settings).
                _sessionLost.value = true
                _step.value = LoginStep.Credentials
            }
            else -> Unit
        }
    }

    fun submitCode(code: String) = launchRequest { client.checkAuthenticationCode(code.trim()).get() }
    fun submitPassword(password: String) = launchRequest { client.checkAuthenticationPassword(password).get() }
    fun submitEmail(email: String) = launchRequest { client.setAuthenticationEmailAddress(email.trim()).get() }
    fun submitEmailCode(code: String) = launchRequest {
        client.checkAuthenticationEmailCode(EmailAddressAuthenticationCode(code = code.trim())).get()
    }

    private fun launchRequest(block: suspend () -> Unit) = scope.launch { request(resetOnFail = false, block) }

    private suspend fun request(resetOnFail: Boolean, block: suspend () -> Unit) {
        _error.value = null
        try {
            block()
        } catch (e: Exception) {
            _error.value = e.message ?: "Login failed"
            if (resetOnFail) _step.value = LoginStep.Credentials
        }
    }

    /** Abandons a login in progress. */
    fun cancelLogin() = scope.launch {
        startMutex.withLock {
            shutdown()
            wipe()
            _error.value = null
            _step.value = LoginStep.Credentials
        }
    }

    /** Ends the session on Telegram's side (it disappears from "Active sessions") and deletes it here. */
    suspend fun logOut() = startMutex.withLock {
        _client?.let { c -> closeAndWait(c) { runCatching { c.logOut() } } }
        shutdown()
        wipe()
        _sessionLost.value = false
        _step.value = LoginStep.Credentials
    }

    private suspend fun shutdown() {
        val c = _client ?: return
        if (lastState !is AuthorizationStateClosed) closeAndWait(c) { runCatching { c.close() } }
        _client = null
        stateJob?.cancel()
        stateJob = null
        lastState = null
    }

    /** Runs [action] and waits until TDLib reports the client closed, so its files can be deleted. */
    private suspend fun closeAndWait(c: TdlClient, action: suspend () -> Unit) = coroutineScope {
        closingOnPurpose = true
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(15_000) { c.authorizationStateUpdates.first { it.authorizationState is AuthorizationStateClosed } }
        }
        action()
        closed.await()
        lastState = AuthorizationStateClosed()
    }

    private suspend fun wipe() = withContext(Dispatchers.IO) {
        dbDir.deleteRecursively()
        filesDir.deleteRecursively()
    }
}
