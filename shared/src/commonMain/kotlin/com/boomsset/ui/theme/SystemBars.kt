package com.boomsset.ui.theme

import androidx.compose.runtime.Composable

/**
 * 把系统栏（状态栏 / 导航栏）的图标明暗调成和当前主题相反。
 *
 * **为什么必须显式设：Android 15（SDK 35）起，targetSdk ≥ 35 的 App 被强制
 * edge-to-edge** —— 内容画到状态栏底下，而系统并不知道你的背景是浅色还是深色，
 * 默认按深色背景给**白色图标**。浅色主题下的结果是白字压在近乎白色的底上，
 * 时间和信号图标**几乎完全看不见**。
 *
 * 这个 bug 只有真机（Android 16 / Xiaomi 15 Pro）跑出来才发现 ——
 * 手上的 API 34 模拟器在强制 edge-to-edge 之前，系统会画一条不透明状态栏，
 * 图标颜色自己就是对的。**验 edge-to-edge 相关的问题必须用 SDK ≥ 35 的设备。**
 */
@Composable
expect fun ApplySystemBarsAppearance(darkTheme: Boolean)
