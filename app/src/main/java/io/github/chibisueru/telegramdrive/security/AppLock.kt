package io.github.chibisueru.telegramdrive.security

import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.chibisueru.telegramdrive.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    data class LockedOut(val seconds: Int) : PinResult
}

/**
 * The optional PIN lock. The PIN is stored only as a salted PBKDF2 hash. The app locks on
 * every cold start and after it has been in the background for the chosen time.
 */
class AppLock(private val scope: CoroutineScope, private val settings: SettingsStore) : DefaultLifecycleObserver {
    companion object {
        private const val ITERATIONS = 120_000
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MS = 30_000L
    }

    private val _locked = MutableStateFlow(true)
    /** Only meaningful while a PIN is set; the UI checks both. */
    val locked: StateFlow<Boolean> = _locked

    private var backgroundedAt = 0L
    /** Set right before the app itself opens another app (file picker, share sheet). */
    @Volatile private var externalActivityAllowed = false
    private var failedAttempts = 0
    private var lockedOutUntil = 0L

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundedAt == 0L) return // first start: already locked
        if (externalActivityAllowed) {
            externalActivityAllowed = false
            return
        }
        scope.launch {
            val s = settings.current()
            if (s.pinEnabled && SystemClock.elapsedRealtime() - backgroundedAt >= s.lockTimeout.millis) _locked.value = true
        }
    }

    /** Call before launching the file picker, share sheet etc., so returning doesn't ask for the PIN. */
    fun allowExternalActivity() {
        externalActivityAllowed = true
    }

    fun unlock() {
        failedAttempts = 0
        _locked.value = false
    }

    suspend fun verify(pin: String): PinResult {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedOutUntil) return PinResult.LockedOut(((lockedOutUntil - now) / 1000).toInt() + 1)
        val s = settings.current()
        val ok = s.pinEnabled && MessageDigest.isEqual(hash(pin, s.pinSalt).toByteArray(), s.pinHash.toByteArray())
        if (ok) {
            unlock()
            return PinResult.Ok
        }
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0
            lockedOutUntil = now + LOCKOUT_MS
            return PinResult.LockedOut((LOCKOUT_MS / 1000).toInt())
        }
        return PinResult.Wrong(MAX_ATTEMPTS - failedAttempts)
    }

    /** Sets a new PIN. The app stays unlocked for the current session. */
    suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltText = Base64.encodeToString(salt, Base64.NO_WRAP)
        settings.setPin(hash(pin, saltText), saltText)
        _locked.value = false
    }

    suspend fun removePin() {
        settings.clearPin()
        _locked.value = false
    }

    private suspend fun hash(pin: String, saltText: String): String = withContext(Dispatchers.Default) {
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        Base64.encodeToString(key, Base64.NO_WRAP)
    }
}
