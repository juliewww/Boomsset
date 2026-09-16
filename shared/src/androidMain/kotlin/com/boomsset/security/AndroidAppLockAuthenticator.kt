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
 * Holder for whichever Activity is currently in the foreground.
 *
 * `BiometricPrompt`'s constructor hard-requires a `FragmentActivity` (AGENTS.md
 * constraint 6), and the shared layer can't hold an Activity. So `MainActivity`
 * registers it in onCreate and unregisters it in onDestroy.
 *
 * Uses a [WeakReference] rather than a strong reference — a strong reference would
 * leak the whole Activity (and its View tree along with it) across configuration
 * changes or on exit.
 */
object CurrentActivityHolder {
    private var ref: WeakReference<FragmentActivity>? = null

    fun set(activity: FragmentActivity) {
        ref = WeakReference(activity)
    }

    fun clear(activity: FragmentActivity) {
        // Only clear if it's still the same activity — otherwise A.onDestroy firing
        // after B.onCreate would wipe out B
        if (ref?.get() === activity) ref = null
    }

    fun current(): FragmentActivity? = ref?.get()
}

/**
 * Android-side app lock authentication.
 *
 * Uses the stable `androidx.biometric:1.1.0` + `BiometricPrompt`.
 * **Deliberately not using the 1.4.0-alpha Compose API** — docs/stack.md's conclusion:
 * don't put an alpha dependency on a finance app's authentication path just for a
 * nicer-to-use API.
 *
 * The authenticator allows `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`: users without
 * fingerprint/face enrollment can use their device passcode. Otherwise app lock
 * would be unusable on a large fraction of devices.
 */
class AndroidAppLockAuthenticator(private val context: Context) : AppLockAuthenticator {

    private val allowed =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * ⚠️ **Must check biometrics and device passcode separately — never just the combined value.**
     *
     * `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` returns
     * `BIOMETRIC_ERROR_NONE_ENROLLED` when no biometric is enrolled, **even if the
     * device has a passcode set and authentication would actually succeed**. Checking
     * only the combined value would judge a device with a PIN but no enrolled
     * fingerprint as "can't use app lock" — that's exactly how this was discovered on
     * a real run (the prompt didn't change after setting a PIN on the emulator).
     *
     * So: available via either path counts as available.
     */
    override fun capability(): AuthCapability {
        val manager = BiometricManager.from(context)
        val biometric = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (biometric == BiometricManager.BIOMETRIC_SUCCESS) return AuthCapability.AVAILABLE

        val credential = manager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (credential == BiometricManager.BIOMETRIC_SUCCESS) return AuthCapability.AVAILABLE

        // Neither path works — use the biometric path's reason to explain (more
        // specific for the user)
        return when (biometric) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthCapability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            -> AuthCapability.NOT_ENROLLED  // No hardware, but a passcode can still be set — still "go add one in settings"
            else -> AuthCapability.TEMPORARILY_UNAVAILABLE
        }
    }

    override suspend fun authenticate(reason: String): AuthResult {
        val activity = CurrentActivityHolder.current()
            ?: return AuthResult.Failed("界面不在前台，无法弹出验证")

        // BiometricPrompt must be constructed and invoked on the main thread
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
                            // User tapping cancel/back is not an error, categorize it separately
                            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                errorCode == BiometricPrompt.ERROR_CANCELED
                            cont.resume(
                                if (cancelled) AuthResult.Cancelled
                                else AuthResult.Failed(errString.toString()),
                            )
                        }

                        // onAuthenticationFailed means "this one attempt wasn't recognized" —
                        // the system will let the user retry, it doesn't mean the flow is
                        // over — so we don't resume here, we wait for error or success.
                    },
                )

                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("猪满仓")
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
