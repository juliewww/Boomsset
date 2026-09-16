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
 * iOS-side app lock authentication.
 *
 * **No cinterop needed** — `LocalAuthentication` is a first-class Kotlin/Native
 * platform library, so a plain `import platform.LocalAuthentication.*` is enough:
 * zero Gradle config, zero `.def` file. (Verified in docs/stack.md.)
 *
 * Uses `LAPolicyDeviceOwnerAuthentication` rather than
 * `LAPolicyDeviceOwnerAuthenticationWithBiometrics`: the former **automatically falls
 * back to the device passcode** when biometrics fail or aren't enrolled. Using only
 * the latter would leave users without Face ID enrolled completely unable to enable
 * app lock.
 *
 * ⚠️ `Info.plist` must have `NSFaceIDUsageDescription`, otherwise the first Face ID
 * call crashes outright (AGENTS.md constraint 7). Already configured in
 * `iosApp/project.yml`.
 */
class IosAppLockAuthenticator : AppLockAuthenticator {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun capability(): AuthCapability = memScoped {
        // canEvaluatePolicy's NSError is an out-parameter; on Kotlin/Native the pointer must be explicitly allocated
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
            // Create a new LAContext each time — reusing one would carry over the cached authentication state from the previous attempt
            LAContext().evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthentication,
                localizedReason = reason,
            ) { success, error ->
                if (!cont.isActive) return@evaluatePolicy
                when {
                    success -> cont.resume(AuthResult.Success)
                    // User-initiated cancellation is not an error
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
