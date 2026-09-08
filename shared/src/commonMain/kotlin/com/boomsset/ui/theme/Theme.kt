package com.boomsset.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.boomsset.ui.LocalGainLossColors
import com.boomsset.ui.gainLossColorsFor

/**
 * 旺资的品牌配色 —— **莫兰迪暖色：中明度、低彩度、灰调**。
 *
 * 全部色值由 OKLCH 三元组解出，**唯一的输入是色相角 H = 82°（橄榄金）**。
 * 表面、容器、文字、图标共用这一个 H，所以换品牌色时整套跟着走。
 *
 * ## 为什么从 `#BD4D03` 改成 `#918163`
 *
 * 反馈是"不够高级"。问题不在色相，在**角色分配**：`#BD4D03` 的 OKLCH 彩度是
 * **0.160**，而它坐在 `primary` 上 —— FAB、导航指示、开关、净值柱全归它。
 * 高彩度橙做大面积填充，在中文 App 语境里就是电商那一档的读感。
 * 现在彩度降到 **0.047**（低了三倍多），彩色全部让给数据本身。
 *
 * 中间试过"深墨锚定"（primary L 0.33、深色 hero 卡片），反馈是**太暗**；
 * 又试过冷色，但冷色区被四个大类色在亮度轴上占满了（保障类紫 L.48、
 * 流动资金蓝 L.60、固定收益绿和另类实物青 L.70），冷色 primary 最亮只能到
 * **L 0.42**，必然比暖色深一档。橄榄金能做到 **L 0.61**，这是选暖色的实际理由。
 *
 * ## 三条不能动的判据（改色值要重新验）
 *
 * 1. **与五个大类图表色的最小 ΔE ≥ 15**（OKLab ×100，正常视力）。
 *    实测 15.2；旧的 `#BD4D03` 对「权益类」橙只有 16.2，一直贴着门槛。
 *    配置页上 FAB 和权益类那根条会挨在一起，必须分得开。
 * 2. **primary 对页面底 ≥ 3:1** —— 低于它 FAB 会糊进背景。实测 3.70:1。
 * 3. **onPrimary 对 primary ≥ 4.5:1**。`primary` 亮到 L 0.61 之后
 *    **白字只有 3.86:1、不合格**，所以 `onPrimary` 是深棕不是白 ——
 *    莫兰迪体系普遍如此：亮到有阳光感的颜色压不住白字。
 *
 * ## 中性面不是纯灰
 *
 * 表面和文字都带 H=82 的微量彩度（近白面 0.008、中灰 0.013、深色文字 0.020）——
 * 纯 `#FFFFFF/#F5F5F5` 是"正确但临床"，微染才是"贵"的来源。
 * 上一版用的是有知有行的纯中性梯度，这一版把亮度阶保留、只加彩度，
 * 所以正文对比度几乎没变（13.1:1）。
 *
 * ⚠️ **这些色值和 `tools/appicon/generate.py` 的 `BRAND_HUE` 必须一致**，
 * 改配色两边一起改，改完跑那个脚本重新生成图标。
 *
 * **必须逐个角色写全，不能只覆盖 primary。** `lightColorScheme()` 没传的参数会取
 * 基线默认值，而基线的 surface 家族是**带紫调**的灰 —— 只改 primary 的话
 * Card 和 BottomBar 仍然发紫，看起来像换了一半。
 *
 * 品牌色和**涨跌语义色是两件事**，后者见 [com.boomsset.ui.GainLossColors]。
 */
private val BrandOlive = Color(0xFF918163)

private val LightScheme = lightColorScheme(
    primary = BrandOlive,
    // 不是白色 —— 白字在 L 0.61 的 primary 上只有 3.86:1，不到正文门槛。
    // 深棕给到 5.03:1。
    onPrimary = Color(0xFF160E02),
    // 净值 hero 卡片底。柔和暖沙，不是上一版的深墨实底（那个"太暗"）
    primaryContainer = Color(0xFFE6D7BC),
    onPrimaryContainer = Color(0xFF392805),
    inversePrimary = Color(0xFFBFAF93),

    // 次要色走同色相的低彩中性 —— 只留一个强调色，其余全部近中性
    secondary = Color(0xFF6E685D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1ECE4),
    onSecondaryContainer = Color(0xFF332D22),

    // 第三色刻意留在同色相内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFF68593E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEADEC8),
    onTertiaryContainer = Color(0xFF352607),

    // error 和「超配」是两回事，色值也不同（超配是 #C5453F）
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFDFAF3),
    onBackground = Color(0xFF2E281C),
    surface = Color(0xFFFDFAF3),
    onSurface = Color(0xFF2E281C),
    surfaceVariant = Color(0xFFEAE7E0),
    onSurfaceVariant = Color(0xFF6A6458),
    surfaceTint = BrandOlive,
    inverseSurface = Color(0xFF2E281C),
    inverseOnSurface = Color(0xFFF6F2EB),

    surfaceDim = Color(0xFFE5E1DB),
    surfaceBright = Color(0xFFFFFCF7),
    surfaceContainerLowest = Color(0xFFFFFEFD),
    surfaceContainerLow = Color(0xFFFAF6EF),
    surfaceContainer = Color(0xFFF6F2EB),
    surfaceContainerHigh = Color(0xFFF0ECE5),
    surfaceContainerHighest = Color(0xFFEAE7E0),

    outline = Color(0xFF989186),
    outlineVariant = Color(0xFFDED8CE),
    scrim = Color(0xFF000000),
)

/**
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 —— 同一个色相角，
 * 按深底重新取亮度并单独验过（primary 对底 9.6:1，与深色大类色最小 ΔE 17.4）。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCBB897),
    onPrimary = Color(0xFF2A1D03),
    primaryContainer = Color(0xFF524225),
    onPrimaryContainer = Color(0xFFEBDABB),
    inversePrimary = BrandOlive,

    secondary = Color(0xFFC3BDB2),
    onSecondary = Color(0xFF302B21),
    secondaryContainer = Color(0xFF403D36),
    onSecondaryContainer = Color(0xFFE4DFD5),

    tertiary = Color(0xFFC3B294),
    onTertiary = Color(0xFF2C1F05),
    tertiaryContainer = Color(0xFF4C3D23),
    onTertiaryContainer = Color(0xFFE6D7BC),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF15120D),
    onBackground = Color(0xFFEBE5DC),
    surface = Color(0xFF15120D),
    onSurface = Color(0xFFEBE5DC),
    surfaceVariant = Color(0xFF3C3932),
    onSurfaceVariant = Color(0xFFC3BDB2),
    surfaceTint = Color(0xFFCBB897),
    inverseSurface = Color(0xFFEBE5DC),
    inverseOnSurface = Color(0xFF2E281C),

    surfaceDim = Color(0xFF15120D),
    surfaceBright = Color(0xFF423E37),
    surfaceContainerLowest = Color(0xFF0F0C07),
    surfaceContainerLow = Color(0xFF1C1913),
    surfaceContainer = Color(0xFF201D17),
    surfaceContainerHigh = Color(0xFF2D2A23),
    surfaceContainerHighest = Color(0xFF38352F),

    outline = Color(0xFF888279),
    outlineVariant = Color(0xFF3D3930),
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

    // 图表配色和涨跌色都用**同一个** darkTheme —— 让它们自己去读系统深浅色
    // 会和主题不一致（预览和测试里尤其容易出现）
    CompositionLocalProvider(
        LocalChartColors provides chartColorsFor(darkTheme),
        LocalGainLossColors provides gainLossColorsFor(darkTheme),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}
