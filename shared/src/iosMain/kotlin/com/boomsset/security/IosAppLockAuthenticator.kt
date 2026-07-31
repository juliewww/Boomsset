package com.boomsset.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.coroutines.resume

/**
 * iOS 侧的应用锁认证。
 *
 * **不需要 cinterop** —— `LocalAuthentication` 是 Kotlin/Native 的一等 platform library，
 * 直接 `import platform.LocalAuthentication.*` 即可，零 Gradle 配置、零 `.def` 文件。
 * （这一点在 docs/stack.md 里核实过。）
 *
 * 用 `LAPolicyDeviceOwnerAuthentication` 而不是
 * `LAPolicyDeviceOwnerAuthenticationWithBiometrics`：前者在生物识别失败或未录入时
 * **自动回落到设备密码**。只用后者的话，没录 Face ID 的用户根本没法开应用锁。
 *
 * ⚠️ `Info.plist` 必须有 `NSFaceIDUsageDescription`，否则首次调用 Face ID 直接崩溃
 * （AGENTS.md 约束 7）。已经在 `iosApp/project.yml` 里配好了。
 */
class IosAppLockAuthenticator : AppLockAuthenticator {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun capability(): AuthCapability = memScoped {
        // canEvaluatePolicy 的 NSError 是出参，Kotlin/Native 上要显式分配指针
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val canEvaluate = LAContext()
            .canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, errorPtr.ptr)

        if (canEvaluate) return@memScoped AuthCapability.AVAILABLE

        when (errorPtr.value?.code) {
            LAErrorBiometryNotEnrolled, LAErrorPasscodeNotSet -> AuthCapability.NOT_ENROLLED
            LAErrorBiometryNotAvailable -> AuthCapability.NO_HARDWARE
            else -> AuthCapability.TEMPORARILY_UNAVAILABLE
        }
    }

    @OptIn(BetaInteropApi::class)
    override suspend fun authenticate(reason: String): AuthResult =
        suspendCancellableCoroutine { cont ->
            // 每次新建 LAContext —— 复用会带上上一次的认证状态缓存
            LAContext().evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthentication,
                localizedReason = reason,
            ) { success, error ->
                if (!cont.isActive) return@evaluatePolicy
                when {
                    success -> cont.resume(AuthResult.Success)
                    // 用户主动取消不是错误
                    error?.code == LAErrorUserCancel ||
                        error?.code == LAErrorSystemCancel ||
                        error?.code == LAErrorUserFallback -> cont.resume(AuthResult.Cancelled)
                    error?.code == LAErrorAuthenticationFailed ->
                        cont.resume(AuthResult.Failed("验证未通过"))
                    else -> cont.resume(AuthResult.Failed(error?.localizedDescription))
                }
            }
        }
}
