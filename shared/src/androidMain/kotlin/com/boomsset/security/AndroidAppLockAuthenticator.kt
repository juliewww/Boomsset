package com.boomsset.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * 当前处于前台的 Activity 的持有者。
 *
 * `BiometricPrompt` 的构造函数硬性要求 `FragmentActivity`（AGENTS.md 约束 6），
 * 而共享层不能持有 Activity。所以由 `MainActivity` 在 onCreate 注册、onDestroy 注销。
 *
 * 用 [WeakReference] 而不是强引用 —— 强引用会在配置变更或退出时泄漏整个 Activity，
 * 连带泄漏它的 View 树。
 */
object CurrentActivityHolder {
    private var ref: WeakReference<FragmentActivity>? = null

    fun set(activity: FragmentActivity) {
        ref = WeakReference(activity)
    }

    fun clear(activity: FragmentActivity) {
        // 只在还是自己时清除 —— 否则 A.onDestroy 晚于 B.onCreate 时会把 B 清掉
        if (ref?.get() === activity) ref = null
    }

    fun current(): FragmentActivity? = ref?.get()
}

/**
 * Android 侧的应用锁认证。
 *
 * 用 stable 的 `androidx.biometric:1.1.0` + `BiometricPrompt`。
 * **没有用 1.4.0-alpha 的 Compose API** —— docs/stack.md 的结论：
 * 不在财务 App 的认证路径上用 alpha 依赖，为了一个更顺手的 API 不值得。
 *
 * 认证器允许 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`：没有指纹/面容的用户
 * 可以用锁屏密码。否则大量设备上应用锁根本开不了。
 */
class AndroidAppLockAuthenticator(private val context: Context) : AppLockAuthenticator {

    private val allowed =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * ⚠️ **必须分别查生物识别和设备密码，不能只查组合值。**
     *
     * `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` 在没有录入生物识别时会返回
     * `BIOMETRIC_ERROR_NONE_ENROLLED`，**即使设备设了锁屏密码、认证实际上能成功**。
     * 只看组合值的话，一台设了 PIN 但没录指纹的设备会被判成"不能用应用锁" ——
     * 实跑时就是这么发现的（模拟器设了 PIN 之后提示没变）。
     *
     * 所以：任一路径可用就是可用。
     */
    override fun capability(): AuthCapability {
        val manager = BiometricManager.from(context)
        val biometric = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (biometric == BiometricManager.BIOMETRIC_SUCCESS) return AuthCapability.AVAILABLE

        val credential = manager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (credential == BiometricManager.BIOMETRIC_SUCCESS) return AuthCapability.AVAILABLE

        // 两条路都不通，用生物识别那条的原因来解释（对用户更具体）
        return when (biometric) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthCapability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            -> AuthCapability.NOT_ENROLLED  // 没硬件但可以设锁屏密码 —— 仍然是「去设置里加」
            else -> AuthCapability.TEMPORARILY_UNAVAILABLE
        }
    }

    override suspend fun authenticate(reason: String): AuthResult {
        val activity = CurrentActivityHolder.current()
            ?: return AuthResult.Failed("界面不在前台，无法弹出验证")

        // BiometricPrompt 必须在主线程构造和调用
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat_getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) {
                            if (cont.isActive) cont.resume(AuthResult.Success)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (!cont.isActive) return
                            // 用户点取消/返回不是错误，单独归类
                            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                errorCode == BiometricPrompt.ERROR_CANCELED
                            cont.resume(
                                if (cancelled) AuthResult.Cancelled
                                else AuthResult.Failed(errString.toString()),
                            )
                        }

                        // onAuthenticationFailed 是"这一次没认出来"，系统会让用户重试，
                        // 不代表流程结束 —— 所以这里不 resume，等 error 或 success。
                    },
                )

                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("旺资")
                    .setSubtitle(reason)
                    .setAllowedAuthenticators(allowed)
                    .build()

                prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
    }
}

private fun ContextCompat_getMainExecutor(context: Context) =
    androidx.core.content.ContextCompat.getMainExecutor(context)
