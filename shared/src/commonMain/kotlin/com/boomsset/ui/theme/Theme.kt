package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 旺资的品牌配色 —— **琥珀棕**。
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
    primaryContainer = Color(0xFFF7E1BC),
    onPrimaryContainer = Color(0xFF2C1700),
    inversePrimary = Color(0xFFF5BC6E),

    secondary = Color(0xFF705B41),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF9E3C7),
    onSecondaryContainer = Color(0xFF271905),

    // 冷灰蓝，不是绿 —— 见文件头注释
    tertiary = Color(0xFF3F5A73),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E3F2),
    onTertiaryContainer = Color(0xFF0B1D2B),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFFFBF5),
    onBackground = Color(0xFF201B14),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF201B14),
    surfaceVariant = Color(0xFFEFE0CC),
    onSurfaceVariant = Color(0xFF4E4536),
    surfaceTint = BrandAmber,
    inverseSurface = Color(0xFF35302A),
    inverseOnSurface = Color(0xFFFAEFE3),

    surfaceDim = Color(0xFFE4D8C8),
    surfaceBright = Color(0xFFFFFBF5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF5EA),
    surfaceContainer = Color(0xFFF7EEE1),
    surfaceContainerHigh = Color(0xFFF2E8DA),
    surfaceContainerHighest = Color(0xFFECE2D3),

    outline = Color(0xFF80765F),
    outlineVariant = Color(0xFFD2C5B0),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF5BC6E),
    onPrimary = Color(0xFF4A2E00),
    primaryContainer = Color(0xFF6A4400),
    onPrimaryContainer = Color(0xFFF7E1BC),
    inversePrimary = BrandAmber,

    secondary = Color(0xFFDFC3A2),
    onSecondary = Color(0xFF3E2E18),
    secondaryContainer = Color(0xFF56442D),
    onSecondaryContainer = Color(0xFFF9E3C7),

    tertiary = Color(0xFFA8C7E0),
    onTertiary = Color(0xFF0C2F45),
    tertiaryContainer = Color(0xFF26455C),
    onTertiaryContainer = Color(0xFFD3E3F2),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF17130D),
    onBackground = Color(0xFFECE0D2),
    surface = Color(0xFF17130D),
    onSurface = Color(0xFFECE0D2),
    surfaceVariant = Color(0xFF4E4536),
    onSurfaceVariant = Color(0xFFD2C5B0),
    surfaceTint = Color(0xFFF5BC6E),
    inverseSurface = Color(0xFFECE0D2),
    inverseOnSurface = Color(0xFF35302A),

    surfaceDim = Color(0xFF17130D),
    surfaceBright = Color(0xFF3E382F),
    surfaceContainerLowest = Color(0xFF110E08),
    surfaceContainerLow = Color(0xFF201B14),
    surfaceContainer = Color(0xFF241F17),
    surfaceContainerHigh = Color(0xFF2F2921),
    surfaceContainerHighest = Color(0xFF3A342B),

    outline = Color(0xFF9A8E76),
    outlineVariant = Color(0xFF4E4536),
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

    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
