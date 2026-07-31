package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 旺资的品牌配色 —— **墨蓝**。
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
 */
private val BrandBlue = Color(0xFF1F4E85)

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3F7),
    onPrimaryContainer = Color(0xFF001B3D),
    inversePrimary = Color(0xFFA6C8FF),

    secondary = Color(0xFF52606F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E4F2),
    onSecondaryContainer = Color(0xFF0F1D2A),

    // 暖金作为第三色 —— 呼应「旺」，只用在少量强调上，不参与主色调
    tertiary = Color(0xFF7A5A1E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E2BE),
    onTertiaryContainer = Color(0xFF2A1A00),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFDFE3EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = BrandBlue,
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),

    surfaceDim = Color(0xFFDBDCE0),
    surfaceBright = Color(0xFFFBFCFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6FA),
    surfaceContainer = Color(0xFFEFF1F6),
    surfaceContainerHigh = Color(0xFFE9ECF2),
    surfaceContainerHighest = Color(0xFFE3E7EE),

    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF14477C),
    onPrimaryContainer = Color(0xFFD6E3F7),
    inversePrimary = BrandBlue,

    secondary = Color(0xFFBAC8D8),
    onSecondary = Color(0xFF24323F),
    secondaryContainer = Color(0xFF3A4856),
    onSecondaryContainer = Color(0xFFD6E4F2),

    tertiary = Color(0xFFE7C38C),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5E4104),
    onTertiaryContainer = Color(0xFFF7E2BE),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceTint = Color(0xFFA6C8FF),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),

    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),

    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
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
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
