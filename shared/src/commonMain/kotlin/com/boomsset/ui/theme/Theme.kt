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
 * 旺资的品牌配色 —— **暖金：中明度、饱和的琥珀金**。
 *
 * 全部色值由 OKLCH 三元组解出，**唯一的输入是色相角 H = 82°**。
 * 表面、容器、文字、图标共用这一个 H，所以换品牌色时整套跟着走。
 *
 * ## 色值的两次改版（当前是 `#986E00`）
 *
 * `#BD4D03`（彩度 0.160）反馈"不够高级" —— 问题不在色相，在**角色分配**：
 * 高彩度橙坐在 `primary` 上做大面积填充，在中文 App 语境里就是电商那一档的读感。
 * 于是压到莫兰迪区间 `#918163`（彩度 **0.047**）。
 *
 * 但那一版反馈"太沉闷"。**低彩度换来的克制，代价是所有靠颜色表示状态的地方都变弱**
 * （底部导航的选中态就因此比未选中还淡，见 App.kt）。现在取中间路线：
 * **彩度回到 0.120，但亮度压到 L 0.565** —— 关键在于**往深走反而能放更多彩度**，
 * 因为「权益类」橙坐在 L 0.715，离得远了 ΔE 自然拉开（实测 16.0，旧的莫兰迪版只有 15.2）。
 * 结果是比莫兰迪版更鲜明、比电商橙更克制，而且**白字终于合格**（4.60:1）——
 * 莫兰迪版亮到 L 0.61，白字只有 3.86:1，只能用深棕。
 *
 * 中间试过"深墨锚定"（primary L 0.33、深色 hero 卡片），反馈是**太暗**；
 * 又试过冷色，但冷色区被四个大类色在亮度轴上占满了（保障类紫 L.48、
 * 流动资金蓝 L.60、固定收益绿和另类实物青 L.70），冷色 primary 最亮只能到
 * **L 0.42**，必然比暖色深一档 —— 这是选暖色的实际理由，不是审美偏好。
 *
 * ## 三条不能动的判据（改色值要重新验）
 *
 * 1. **与五个大类图表色的最小 ΔE ≥ 15**（OKLab ×100，正常视力）。实测 **16.0**。
 *    配置页上 FAB 和权益类那根条会挨在一起，必须分得开。
 *    ⚠️ **这条是彩度的上限来源**：想更金就得更深，`#AC8137`（图标柱子那个金，
 *    L 0.63）对权益类只有 ΔE 10.3，直接不合格。
 * 2. **primary 对页面底 ≥ 3:1** —— 低于它 FAB 会糊进背景。实测 4.41:1。
 * 3. **onPrimary 对 primary ≥ 4.5:1**。实测白字 4.60:1。
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
private val BrandGold = Color(0xFF986E00)

private val LightScheme = lightColorScheme(
    primary = BrandGold,
    // 白色 —— 4.60:1，过正文门槛。⚠️ 这一条**和亮度强耦合**：
    // 上一版 primary 亮到 L 0.61 时白字只有 3.86:1、不合格，只能用深棕。
    // 改 primary 的亮度必须重算这里。
    onPrimary = Color(0xFFFFFFFF),
    // 净值 hero 卡片底。柔和暖沙 —— 试过深墨实底，反馈"太暗"
    primaryContainer = Color(0xFFE8D7B8),
    onPrimaryContainer = Color(0xFF3A2800),
    inversePrimary = Color(0xFFD6B26F),

    // 次要色走同色相的低彩中性 —— 只留一个强调色，其余全部近中性
    secondary = Color(0xFF6E685D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1ECE4),
    onSecondaryContainer = Color(0xFF332D22),

    // 第三色刻意留在同色相内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFF705624),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECDEC4),
    onTertiaryContainer = Color(0xFF372500),

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
    surfaceTint = BrandGold,
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
 * 按深底重新取亮度并单独验过（primary 对底 9.68:1，与深色大类色最小 ΔE 15.3）。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE8B245),
    onPrimary = Color(0xFF2E1F00),
    primaryContainer = Color(0xFF5E4200),
    onPrimaryContainer = Color(0xFFEDDCBD),
    inversePrimary = BrandGold,

    secondary = Color(0xFFC3BDB2),
    onSecondary = Color(0xFF302B21),
    secondaryContainer = Color(0xFF403D36),
    onSecondaryContainer = Color(0xFFE4DFD5),

    tertiary = Color(0xFFCFB17A),
    onTertiary = Color(0xFF2E1F00),
    tertiaryContainer = Color(0xFF553C00),
    onTertiaryContainer = Color(0xFFE7D8BD),

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
    surfaceTint = Color(0xFFE8B245),
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
