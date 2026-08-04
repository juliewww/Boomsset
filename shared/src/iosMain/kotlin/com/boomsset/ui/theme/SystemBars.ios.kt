package com.boomsset.ui.theme

import androidx.compose.runtime.Composable

/**
 * iOS 上不需要做任何事。
 *
 * 状态栏文字颜色由系统按 App 的 `UIUserInterfaceStyle` 推导：浅色外观下自动是深色文字。
 * 模拟器上实测过 —— 浅色主题时状态栏时间是深色的，可读。
 *
 * 如果哪天要在 iOS 上强制某种样式，走 `UIViewController.preferredStatusBarStyle`，
 * 不要试图在这里改。
 */
@Composable
actual fun ApplySystemBarsAppearance(darkTheme: Boolean) {
    // 故意留空，理由见上
}
