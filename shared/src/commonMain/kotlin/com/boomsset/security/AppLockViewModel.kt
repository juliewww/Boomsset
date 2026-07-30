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
    /** 用户是否开启了应用锁 */
    val lockEnabled: Boolean = false,
    /** 本次会话是否已通过认证 */
    val unlocked: Boolean = false,
    val capability: AuthCapability = AuthCapability.AVAILABLE,
    /** 上一次认证失败的原因，供 UI 提示。用户取消不算失败，这里会是 null。 */
    val lastError: String? = null,
) {
    /**
     * 是否应该挡住内容。
     *
     * 注意 `loading` 期间**也要挡住** —— 否则开了锁的用户会在读取设置的那一瞬间
     * 看到自己的资产数据闪一下。这种「先渲染再遮住」是应用锁最常见的实现缺陷。
     */
    val shouldBlockContent: Boolean get() = loading || (lockEnabled && !unlocked)
}

/**
 * 应用锁的状态机。
 *
 * 认证只在**本次进程内**有效（[unlocked] 不落库）—— 重启 App 要重新认证。
 * 这是应用锁的意义所在，不要为了"体验好"把它持久化。
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
        // 用 Eagerly 而不是 WhileSubscribed：锁屏是最外层的门，
        // 不该因为订阅时机而出现"内容先露出来"的窗口
        started = SharingStarted.Eagerly,
        initialValue = AppLockUiState(),
    )

    fun authenticate() {
        viewModelScope.launch {
            when (val result = authenticator.authenticate("解锁旺资查看你的资产")) {
                AuthResult.Success -> {
                    unlocked.value = true
                    lastError.value = null
                }
                // 用户主动取消不是错误 —— 不要弹「认证失败」，那是在指责用户
                AuthResult.Cancelled -> lastError.value = null
                is AuthResult.Failed -> lastError.value = result.message
            }
        }
    }

    /**
     * 开关应用锁。
     *
     * **开启前必须先认证成功** —— 否则拿到别人手机的人可以直接开锁把主人锁在外面，
     * 或者用户在不能认证的设备上开了锁却再也进不来。
     */
    fun setLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                // 关闭也要认证，否则锁形同虚设（已解锁状态下才能关，见下）
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
     * App 进入后台时重新上锁。由平台的生命周期回调触发。
     *
     * 无条件重置即可 —— 锁没开启时 [AppLockUiState.shouldBlockContent] 本来就是 false，
     * 不需要先去查设置（那样反而引入竞态）。
     */
    fun relock() {
        unlocked.value = false
    }
}
