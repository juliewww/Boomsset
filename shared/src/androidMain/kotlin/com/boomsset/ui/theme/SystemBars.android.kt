package com.boomsset.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun ApplySystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    // SideEffect rather than LaunchedEffect: this just syncs state to the window,
    // there's no suspending work, and it should take effect after every recomposition
    // (no extra trigger needed when the theme follows the system toggle).
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            // Light theme → dark icons. The name is easy to misread the wrong way:
            // "Light" refers to the **background** being light, so the icons need to
            // be drawn dark.
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/**
 * Walk the ContextWrapper chain to find the Activity.
 *
 * Can't just do `view.context as Activity` — the context Compose's LocalView gets may
 * be wrapped (theme wrapping, AppCompat's ContextThemeWrapper, etc.), and casting
 * directly would throw ClassCastException in some scenarios.
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
