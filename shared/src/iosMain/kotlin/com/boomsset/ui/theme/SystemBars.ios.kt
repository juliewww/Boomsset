package com.boomsset.ui.theme

import androidx.compose.runtime.Composable

/**
 * Nothing needs to be done on iOS.
 *
 * The status bar text color is derived by the system from the app's
 * `UIUserInterfaceStyle`: under a light appearance the text is automatically dark.
 * Verified on the simulator — the status bar clock is dark (and readable) under the
 * light theme.
 *
 * If some day a specific style needs to be forced on iOS, go through
 * `UIViewController.preferredStatusBarStyle` — don't try to do it here.
 */
@Composable
actual fun ApplySystemBarsAppearance(darkTheme: Boolean) {
    // Deliberately empty, see reasoning above
}
