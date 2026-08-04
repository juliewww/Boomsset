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

    // SideEffect 而不是 LaunchedEffect：这只是把状态同步到窗口，没有挂起操作，
    // 而且每次重组后都该生效（主题跟随系统切换时不需要额外触发）。
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            // 浅色主题 → 深色图标。名字容易读反：Light 指的是**背景**浅，
            // 所以图标要画成深色。
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/**
 * 顺着 ContextWrapper 链找 Activity。
 *
 * 不能直接 `view.context as Activity` —— Compose 的 LocalView 拿到的 context
 * 可能是包了一层的（主题包装、AppCompat 的 ContextThemeWrapper 等），
 * 直接强转在部分场景下会 ClassCastException。
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
