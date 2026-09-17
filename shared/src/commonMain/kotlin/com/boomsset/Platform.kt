package com.boomsset

/**
 * Platform info. Its main purpose is to verify that the expect/actual mechanism works on
 * both platforms. Real platform implementations (SQLDelight driver, Keychain/Keystore,
 * biometrics) are added later following the same pattern.
 */
expect val platformName: String
