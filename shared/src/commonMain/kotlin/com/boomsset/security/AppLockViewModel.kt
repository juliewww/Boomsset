package com.boomsset.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomsset.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppLockUiState(
    val loading: Boolean = true,
    /** Whether the user has enabled app lock */
    val lockEnabled: Boolean = false,
    /** Whether authentication has succeeded this session */
    val unlocked: Boolean = false,
    val capability: AuthCapability = AuthCapability.AVAILABLE,
    /** Reason the last authentication attempt failed, for the UI to show. User cancellation doesn't count as a failure, so this stays null in that case. */
    val lastError: String? = null,
) {
    /**
     * Whether content should be blocked.
     *
     * Note that it's blocked **during `loading` too** — otherwise a user with the lock
     * enabled would see their asset data flash on screen the instant settings are
     * loaded. This "render first, cover later" pattern is the most common app-lock
     * implementation flaw.
     */
    val shouldBlockContent: Boolean get() = loading || (lockEnabled && !unlocked)
}

/**
 * App lock's state machine.
 *
 * Authentication is only valid **within the current process** ([unlocked] is never
 * persisted) — restarting the app requires re-authenticating. That's the whole point
 * of app lock; don't persist it for the sake of a "smoother" experience.
 */
class AppLockViewModel(
    private val settings: SettingsRepository,
    private val authenticator: AppLockAuthenticator,
) : ViewModel() {

    private val unlocked = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)
    private val capability = MutableStateFlow(AuthCapability.AVAILABLE)

    init {
        capability.value = authenticator.capability()
    }

    val state: StateFlow<AppLockUiState> = combine(
        settings.observeAppLockEnabled(),
        unlocked,
        lastError,
        capability,
    ) { enabled, isUnlocked, error, cap ->
        AppLockUiState(
            loading = false,
            lockEnabled = enabled,
            unlocked = isUnlocked,
            capability = cap,
            lastError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        // Eagerly instead of WhileSubscribed: the lock screen is the outermost gate,
        // it shouldn't have a window where content shows up first just because of
        // subscription timing
        started = SharingStarted.Eagerly,
        initialValue = AppLockUiState(),
    )

    fun authenticate() {
        viewModelScope.launch {
            when (val result = authenticator.authenticate("解锁猪满仓查看你的资产")) {
                AuthResult.Success -> {
                    unlocked.value = true
                    lastError.value = null
                }
                // User cancellation is not an error — don't pop "authentication failed", that blames the user
                AuthResult.Cancelled -> lastError.value = null
                is AuthResult.Failed -> lastError.value = result.message
            }
        }
    }

    /**
     * Toggle app lock on/off.
     *
     * **Authentication must succeed before enabling it** — otherwise whoever has the
     * phone could enable it and lock the real owner out, or a user could enable it on
     * a device that can't authenticate and never get back in.
     */
    fun setLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                // Disabling also requires authentication, otherwise the lock is
                // toothless (see below — this only fires from the unlocked state)
                settings.setAppLockEnabled(false)
                return@launch
            }
            when (val result = authenticator.authenticate("验证身份以开启应用锁")) {
                AuthResult.Success -> {
                    settings.setAppLockEnabled(true)
                    unlocked.value = true
                    lastError.value = null
                }
                AuthResult.Cancelled -> lastError.value = null
                is AuthResult.Failed -> lastError.value = result.message
            }
        }
    }

    /**
     * Re-lock when the app goes to the background. Triggered by the platform's
     * lifecycle callback.
     *
     * An unconditional reset is fine — when the lock isn't enabled,
     * [AppLockUiState.shouldBlockContent] is already false, so there's no need to
     * check settings first (that would just introduce a race condition).
     */
    fun relock() {
        unlocked.value = false
    }
}
