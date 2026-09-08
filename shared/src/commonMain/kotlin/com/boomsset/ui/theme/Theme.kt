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
 * 旺资的品牌配色 —— **深紫檀（紫气东来）**。
 *
 * ## 为什么是紫，以及它比前几版顺在哪
 *
 * 色值走过 `#8A5A18` → `#BD4D03` → `#918163` → `#986E00` → `#955E00` → `#D3BC7D`，
 * 分别被否为"不够积极/不够高级/太沉闷/不好看/还是暗沉/…"。
 * 这一版换到**紫**（H 315，L 0.40，C 0.110），结构上比前面所有版本都简单，原因是：
 * **紫在 L 0.40 就有足够彩度**，对页面底 9.31:1、白字 9.74:1 —— 而黄/金必须在
 * L 0.53~0.63 之间挣扎，浅了看不见、深了显沉。奶黄那一版甚至被迫把 FAB 和净值柱
 * 拆成两个色（FAB 靠投影、柱子另给深色），紫色**不需要拆**，回到单一品牌色。
 *
 * ## ⚠️ 代价：「保障类」从紫挪到了金黄
 *
 * 品牌色和五个大类图表色必须 ΔE ≥ 15，而保障类原本就是紫 `#585CA2` ——
 * 它占着位置，品牌紫就站不下。挪的方向是**算出来的**：先试过挪到洋红（H340），
 * 那是错的，洋红反而堵住 H300~330 的紫、逼它把彩度提到 0.19（太艳）。
 * 保障类必须挪到**离紫最远的一侧**，紫的彩度下限才从 0.135 掉到 0.060。
 * 新旧两套大类配色都跑过 dataviz 验证器，六项全过。详见 ChartColors.kt。
 *
 * ## 三条不能动的判据（改色值要重新验）
 *
 * 1. **与五个大类图表色的最小 ΔE ≥ 15**（OKLab ×100，正常视力）。实测 **24.6**，
 *    是历版里余量最宽的一次。配置页上 FAB 和大类色块会同屏。
 * 2. **primary 对页面底 ≥ 3:1**。实测 **9.31:1**。
 *    ⚠️ 这条是真正卡住前几版亮度的那一条 —— 净值柱和导航指示条都用 primary，
 *    柱子是数据标记，浅色品牌色会让它糊进背景。
 * 3. **onPrimary 对 primary ≥ 4.5:1**。实测白字 **9.74:1**。
 *
 * ## 中性面**没有**跟着换色相
 *
 * 表面和文字仍然是暖色（H 70）—— 这是明确要求"不要改背景色"。
 * 所以这一版是**品牌紫 + 暖中性面**两个色相，不再是"全套由一个 H 解出"。
 * 冷紫配暖米白是成立的组合，但**改动时要记得它们是两个独立的输入**。
 *
 * ⚠️ **品牌色和 `tools/appicon/generate.py` 的 `BRAND_HUE` 必须一致**，
 * 改配色两边一起改，改完跑那个脚本重新生成图标、再跑 validate.py。
 *
 * **必须逐个角色写全，不能只覆盖 primary。** `lightColorScheme()` 没传的参数会取
 * 基线默认值，而基线的 surface 家族是带紫调的灰 —— 只改 primary 会让整体看起来像换了一半。
 *
 * 品牌色和**涨跌语义色是两件事**，后者见 [com.boomsset.ui.GainLossColors]。
 */
private val BrandPurple = Color(0xFF5D3270)

private val LightScheme = lightColorScheme(
    primary = BrandPurple,
    // 白色 —— 9.74:1。⚠️ 这一条**和亮度强耦合**：更早那版 primary 亮到 L 0.61 时
    // 白字只有 3.86:1、不合格，只能改用深色字。改 primary 亮度必须重算这里。
    onPrimary = Color(0xFFFFFFFF),
    // 净值 hero 卡片底 —— 淡紫。对页面底只有 1.33:1，**必须靠描边勾出轮廓**，
    // 见 NetWorthScreen 的 SummaryCard。
    primaryContainer = Color(0xFFEAD2F6),
    onPrimaryContainer = Color(0xFF371A43),
    inversePrimary = Color(0xFFC7A0D9),

    // 次要色走同色相的低彩中性 —— 只留一个强调色，其余全部近中性
    secondary = Color(0xFF70675E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1ECE6),
    onSecondaryContainer = Color(0xFF342C23),

    // 第三色刻意留在同色相内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFF6A4F76),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAD6F3),
    onTertiaryContainer = Color(0xFF341B3F),

    // error 和「超配」是两回事，色值也不同（超配是 #C5453F）
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFEF9F4),
    onBackground = Color(0xFF30271D),
    surface = Color(0xFFFEF9F4),
    onSurface = Color(0xFF30271D),
    surfaceVariant = Color(0xFFEBE6E2),
    onSurfaceVariant = Color(0xFF6C6359),
    surfaceTint = BrandPurple,
    inverseSurface = Color(0xFF30271D),
    inverseOnSurface = Color(0xFFF6F2ED),

    surfaceDim = Color(0xFFE5E1DC),
    surfaceBright = Color(0xFFFFFCF8),
    surfaceContainerLowest = Color(0xFFFFFEFD),
    surfaceContainerLow = Color(0xFFFAF6F1),
    surfaceContainer = Color(0xFFF6F2ED),
    surfaceContainerHigh = Color(0xFFF0ECE7),
    surfaceContainerHighest = Color(0xFFEBE6E2),

    outline = Color(0xFF9A9187),
    outlineVariant = Color(0xFFE0D8CE),
    scrim = Color(0xFF000000),
)

/**
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 —— 同一个色相角，
 * 按深底重新取亮度并单独验过（primary 对底 6.46:1，与深色大类色最小 ΔE 18.9）。
 * ⚠️ 深色的「保障类」也跟着换了（`#5F63AA` → `#9B8100`），两套都跑过验证器。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC778E8),
    onPrimary = Color(0xFF2A0D36),
    primaryContainer = Color(0xFF563664),
    onPrimaryContainer = Color(0xFFECD4F8),
    inversePrimary = BrandPurple,

    secondary = Color(0xFFC5BCB3),
    onSecondary = Color(0xFF312A22),
    secondaryContainer = Color(0xFF413C36),
    onSecondaryContainer = Color(0xFFE6DED6),

    tertiary = Color(0xFFC7A9D5),
    onTertiary = Color(0xFF2E1538),
    tertiaryContainer = Color(0xFF51335E),
    onTertiaryContainer = Color(0xFFE8D1F2),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    background = Color(0xFF16120D),
    onBackground = Color(0xFFECE5DC),
    surface = Color(0xFF16120D),
    onSurface = Color(0xFFECE5DC),
    surfaceVariant = Color(0xFF3D3833),
    onSurfaceVariant = Color(0xFFC5BCB3),
    surfaceTint = Color(0xFFC778E8),
    inverseSurface = Color(0xFFECE5DC),
    inverseOnSurface = Color(0xFF30271D),

    surfaceDim = Color(0xFF16120D),
    surfaceBright = Color(0xFF433D38),
    surfaceContainerLowest = Color(0xFF100B07),
    surfaceContainerLow = Color(0xFF1D1914),
    surfaceContainer = Color(0xFF211C17),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39342F),

    outline = Color(0xFF8A8279),
    outlineVariant = Color(0xFF3F3830),
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
