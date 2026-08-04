package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 旺资的品牌配色 —— **中性表面 + 琥珀棕作唯一暖色强调**。
 *
 * 表面和文字梯度取自**有知有行**的 design token（页面 `#FFFFFF`、区域 `#FAFAFA`、
 * 分隔 `#E0E0E0`，文字 `#262626` → `#5C5C5C` → `#808080` → `#BFBFBF`）。
 *
 * **为什么换掉暖米色底：**"不够高级"的主因不在色相，在底色。暖米色底本身就读作
 * "米黄/复古"，而且它让所有强调色的对比度都变差。中性白灰底 + 一个暖色强调
 * 是更稳的组合 —— 品牌琥珀棕在中性底上反而更突出，图标也不用重做。
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
private val BrandAmber = Color(0xFF8A5A18)

private val LightScheme = lightColorScheme(
    primary = BrandAmber,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5E7D0),
    onPrimaryContainer = Color(0xFF2C1700),
    inversePrimary = Color(0xFFE8B871),

    // 次要色走中性灰 —— 有知有行的做法：只留一个强调色，其余全部中性
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF262626),

    // 第三色刻意留在琥珀色系内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFFA97B33),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E4CB),
    onTertiaryContainer = Color(0xFF2A1A00),

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
 * 暖色强调在深底上要提亮（`#E8B871`），否则琥珀棕会糊进背景。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE8B871),
    onPrimary = Color(0xFF452B00),
    primaryContainer = Color(0xFF654100),
    onPrimaryContainer = Color(0xFFF5E7D0),
    inversePrimary = BrandAmber,

    secondary = Color(0xFFC6C6C6),
    onSecondary = Color(0xFF2E2E2E),
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFEDEDED),

    tertiary = Color(0xFFC9A46A),
    onTertiary = Color(0xFF3E2A00),
    tertiaryContainer = Color(0xFF56401A),
    onTertiaryContainer = Color(0xFFF0DFC4),

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
    surfaceTint = Color(0xFFE8B871),
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
