package com.boomsset.security

/**
 * Authentication capabilities available on the device.
 *
 * Distinguishing these states matters because they mean completely different things
 * to the user: "device unsupported" has no fix, while "not enrolled" just means the
 * user needs to go add one in system settings. Showing both as "can't be enabled"
 * leaves the latter user with no idea what to do.
 */
enum class AuthCapability {
    /** Available (either biometrics or device passcode works) */
    AVAILABLE,

    /** Hardware supports it but the user hasn't enrolled any biometric, and has no passcode set — direct them to system settings */
    NOT_ENROLLED,

    /** Device has no relevant hardware — no fix, don't send the user on a wild goose chase */
    NO_HARDWARE,

    /** Temporarily unavailable (e.g. locked out after repeated failures) */
    TEMPORARILY_UNAVAILABLE,
}

/** The result of a single authentication attempt. */
sealed interface AuthResult {
    data object Success : AuthResult

    /** User actively cancelled (tapped cancel or back). **Not an error** — don't show an error message. */
    data object Cancelled : AuthResult

    /** Authentication failed or unavailable. [message] is user-facing, may be null (nothing extra to say). */
    data class Failed(val message: String?) : AuthResult
}

/**
 * Biometric / device passcode authentication.
 *
 * Uses an interface + per-platform implementations rather than `expect class` — same
 * reasoning as [com.boomsset.data.DatabaseDriverFactory] (expect class is still Beta in
 * Kotlin 2.4, and the Android implementation needs an Activity).
 *
 * **Deliberately avoids pulling in a third-party KMP biometrics library.** docs/stack.md
 * records the finding: there's no mature KMP library in this space (moko-biometry
 * unmaintained for three years, biometrik has only 9 stars, KMPAuth is actually OAuth).
 * Writing a few dozen lines per platform ourselves is more trustworthy than putting a
 * niche project on the security boundary.
 */
interface AppLockAuthenticator {
    fun capability(): AuthCapability

    /**
     * Shows the system authentication UI. Suspends until the user completes or cancels it.
     *
     * @param reason Explanation shown to the user. iOS's `LAContext` requires it non-null; Android shows it as the subtitle.
     */
    suspend fun authenticate(reason: String): AuthResult
}
