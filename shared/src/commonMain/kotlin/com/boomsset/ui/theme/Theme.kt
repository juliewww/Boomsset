package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 旺资的品牌配色 —— **中性表面 + 一个更鲜亮的暖色强调**。
 *
 * 表面和文字梯度取自**有知有行**的 design token（页面 `#FFFFFF`、区域 `#FAFAFA`、
 * 分隔 `#E0E0E0`，文字 `#262626` → `#5C5C5C` → `#808080` → `#BFBFBF`）。
 *
 * **为什么换掉暖米色底：**"不够高级"的主因不在色相，在底色。暖米色底本身就读作
 * "米黄/复古"，而且它让所有强调色的对比度都变差。中性白灰底 + 一个暖色强调
 * 是更稳的组合。
 *
 * **`BrandAmber` 从 `#8A5A18` 改成了 `#BD4D03`** —— 原色偏暗沉，反馈是"不够积极向上"。
 * 直接沿旧色相拉高亮度和彩度不可行：算出来的候选在 sRGB 里会被裁剪，
 * 裁剪本身会**偷偷改变色相角**（越裁越像纯橙），裁到最后新 primary 和图表的
 * 「权益类」橙 `#E58A26` 正常视力分离度只剩 ΔE 14.2（门槛 15，验证器会 FAIL）——
 * 两者在配置页会挨在一起（FAB 和权益类那根条），必须分得开。
 * 所以改成**在色相角上小幅偏移**（往红那一侧偏 25°）找亮度和彩度都更高的候选，
 * 新色相角 44.7°、与权益类分离度 ΔE 16.2，同时保住"积极"的观感。
 *
 * 在此之前 `App()` 里只有一句裸 `MaterialTheme {}`，界面跑的是 Material 3 库自带的
 * 默认紫。那不是设计决策，只是没人配过 —— 淡紫 FAB 和紫色导航指示条都是从那来的。
 *
 * ⚠️ **这些色值和 `tools/appicon/generate.py` 里的常量必须一致**，图标和界面才不会脱节。
 * 改配色要两边一起改，改完跑那个脚本重新生成图标。
 *
 * **必须逐个角色写全，不能只覆盖 primary。** `lightColorScheme()` 没传的参数会取
 * 基线默认值，而基线的 surface 家族是**带紫调**的灰（#F3EDF7 那一类）——
 * 只改 primary 的话，Card 和 BottomBar 的底色仍然是紫的，看起来像换了一半。
 *
 * 品牌色和**涨跌语义色是两件事**：中国股市红涨绿跌，将来给盈亏上色要另开一组常量，
 * 不要复用 primary/error。
 *
 * 第三色（tertiary）用**冷灰蓝**而不是 M3 从暖色种子自动推出来的绿 ——
 * 绿色在中文理财语境里读作"跌"，哪怕只是个强调色也别用。
 */
private val BrandAmber = Color(0xFFBD4D03)

private val LightScheme = lightColorScheme(
    primary = BrandAmber,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCCD),
    onPrimaryContainer = Color(0xFF310E00),
    inversePrimary = Color(0xFFE9A78A),

    // 次要色走中性灰 —— 有知有行的做法：只留一个强调色，其余全部中性
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF262626),

    // 第三色刻意留在琥珀色系内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFF914F30),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6D6C9),
    onTertiaryContainer = Color(0xFF310F00),

    // error 和「超配」是两回事，色值也不同（超配是 #C5453F）
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF262626),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF262626),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceTint = BrandAmber,
    inverseSurface = Color(0xFF262626),
    inverseOnSurface = Color(0xFFFAFAFA),

    surfaceDim = Color(0xFFF0F0F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFC),
    surfaceContainer = Color(0xFFFAFAFA),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFF0F0F0),

    outline = Color(0xFF808080),
    outlineVariant = Color(0xFFE0E0E0),
    scrim = Color(0xFF000000),
)

/**
 * 深色模式是**另选的一组中性灰**，不是浅色的自动翻转。
 * 暖色强调在深底上要提亮（`#E9A78A`），否则会糊进背景 ——
 * 这个值同样避开了深色模式下权益类图表色 `#CF7600`（ΔE 15.1）。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE9A78A),
    onPrimary = Color(0xFF3D1200),
    primaryContainer = Color(0xFF51240E),
    onPrimaryContainer = Color(0xFFF6D6C9),
    inversePrimary = BrandAmber,

    secondary = Color(0xFFC6C6C6),
    onSecondary = Color(0xFF2E2E2E),
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFEDEDED),

    tertiary = Color(0xFFE9A588),
    onTertiary = Color(0xFF381200),
    tertiaryContainer = Color(0xFF572914),
    onTertiaryContainer = Color(0xFFEFD0C2),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFC6C6C6),
    surfaceTint = Color(0xFFE9A78A),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF262626),

    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF383838),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),

    outline = Color(0xFF8F8F8F),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color(0xFF000000),
)

/**
 * 两端共用的主题入口。跟随系统深浅色 —— 之前是恒亮，深色模式下白得刺眼。
 */
@Composable
fun BoomssetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 状态栏图标要跟着主题反色 —— 见 ApplySystemBarsAppearance 的注释，
    // 不设的话浅色主题下状态栏是白字压白底
    ApplySystemBarsAppearance(darkTheme)

    // 图表配色和主题用**同一个** darkTheme —— 让图表自己去读系统深浅色会和主题不一致
    CompositionLocalProvider(LocalChartColors provides chartColorsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
