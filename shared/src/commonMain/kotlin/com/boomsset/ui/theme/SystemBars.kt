package com.boomsset.ui.theme

import androidx.compose.runtime.Composable

/**
 * Sets the system bar (status bar / navigation bar) icon brightness to the opposite of the
 * current theme.
 *
 * **Why this must be set explicitly: starting with Android 15 (SDK 35), apps with
 * targetSdk ≥ 35 are forced into edge-to-edge** — content is drawn behind the status bar,
 * and the system has no idea whether your background is light or dark, so it defaults to
 * **white icons**, assuming a dark background. In light theme, the result is white text on
 * a near-white background — the clock and signal icons become **nearly invisible**.
 *
 * This bug only surfaced when run on a real device (Android 16 / Xiaomi 15 Pro) —
 * on hand, the API 34 emulator predates forced edge-to-edge, so the system draws an opaque
 * status bar and the icon color is correct on its own. **Testing edge-to-edge-related
 * issues requires a device with SDK ≥ 35.**
 */
@Composable
expect fun ApplySystemBarsAppearance(darkTheme: Boolean)
