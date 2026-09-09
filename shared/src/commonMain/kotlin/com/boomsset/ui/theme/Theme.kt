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
 * 旺资的品牌配色 —— **中玫瑰**（源自「会飞的猪」吉祥物图标的猪身粉，H 354）。
 *
 * ## 这版是怎么来的：从吉祥物色反推主题色
 *
 * app icon 换成了「破环而出」的抽象环 → 一只会飞的粉色小猪之后，
 * 顺手问了一句"猪身粉能不能直接当主题色"——**不能，但同色相能**。
 * 猪身原色 `#EFA8C4`（L 0.807，给吉祥物用的浅粉）对页面底只有 **1.81:1**，
 * FAB、导航指示条这些要跳出页面的元素会糊进背景。同一个色相 H 354 往下压出三档候选
 * （亮玫瑰 L 0.68 / 中玫瑰 L 0.59 / 深莓紫红 L 0.43），套进真实界面预览逐个看过。
 *
 * ## ⚠️ 先上过亮玫瑰，实机反馈"和其他颜色割裂"，才换到中玫瑰
 *
 * 亮玫瑰 `#E961A0` 的问题**不在色相，在响度**：它的彩度 **0.180 比五个大类色都高**
 * （大类色 0.110~0.152），亮度 0.68 又**正好落在大类色的区间中间**（0.60~0.72）——
 * 等于它在视觉上"报名参加了大类色那一组"，还是最吵的一个。实机上的表现就是
 * 净值页六个高彩度色互相抢戏、配置页反而看不到品牌色、资产页（几乎没有大类填色）最和谐。
 * 中玫瑰把亮度压到 **0.590，低于全部五个大类色**，于是退回"框架色"那一层：
 * 数据归数据色，品牌归品牌色，层级重新分开。**这是旧深紫檀（L 0.40）从来不打架的同一个机制。**
 *
 * 顺带解决了两处别扭：**白字终于合格（4.54:1）**，所以 `onPrimary` 回到白色、
 * 开关滑块也不用再手动指定（M3 默认就吃 `onPrimary`）；
 * 导航栏选中态也能直接用 `primary`（对导航栏底 4.07:1），
 * 不用再单独解一个"够亮的玫瑰"（亮玫瑰那版只有 2.82:1，被迫另开一个常量）。
 *
 * ## 「保障类」不用再挪
 *
 * 上一版（深紫檀 H 315）把「保障类」从紫挪去了金黄，因为紫占住了那个色相位。
 * 玫瑰 H 354 离五个大类色更远（最小 ΔE 21.4），**不需要再挪**——
 * ChartColors.kt 保持金黄不变，这条只是记录"这次没有连带影响"，不是新决定。
 *
 * ## 三条不能动的判据（改色值要重新验，方法见 ChartColorsTest 注释里那套验证器）
 *
 * 1. **与五个大类图表色的最小 ΔE ≥ 15**（OKLab ×100，正常视力）。实测 **21.4**（浅）/ **20.7**（深）。
 * 2. **primary 对页面底 / 深色底 ≥ 3:1**。实测 **4.34:1**（浅）/ **6.26:1**（深）。
 *    亮玫瑰那版只有 3.00:1（压线），换到中玫瑰之后余量回到舒服的水平。
 * 3. **onPrimary 对 primary ≥ 4.5:1**。实测白字 **4.54:1**（浅，压线但合格）。
 *    ⚠️ 深色模式的 primary 更亮更艳，白字只有 2.98:1、**不合格**，所以深色那边
 *    `onPrimary` 仍然是近黑酒红。**深浅两套的 onPrimary 不是同一个色，这是有意的。**
 *
 * ## 中性面仍然**没有**跟着换色相
 *
 * 表面和文字仍然是暖色（H 70）——"不要改背景色"这条约束在换主题色这轮依然成立。
 * 冷暖两个色相独立这件事本身没变，变的只是品牌色那一路从 H 315 换成了 H 354。
 *
 * ⚠️ **`tools/appicon/generate.py` 的 `BRAND_HUE` 暂时没有跟着改**——
 * 那套「破环而出」的抽象图标正在被「会飞的猪」取代，图标改版是独立任务，
 * 定下来之前先不动 generate.py，避免图标和主题色两条线互相打断。
 * 图标改版落地时记得回来同步这个常量。
 *
 * **必须逐个角色写全，不能只覆盖 primary。** `lightColorScheme()` 没传的参数会取
 * 基线默认值，而基线的 surface 家族是带紫调的灰 —— 只改 primary 会让整体看起来像换了一半。
 *
 * 品牌色和**涨跌语义色是两件事**，后者见 [com.boomsset.ui.GainLossColors]。
 */
private val BrandRose = Color(0xFFC94385)

private val LightScheme = lightColorScheme(
    primary = BrandRose,
    // 白色 —— 4.54:1，压线但合格。亮玫瑰那版只有 3.14:1，当时被迫用深酒红字；
    // 压暗到中玫瑰之后白字回来了，M3 的默认组件（开关滑块等）也就自动对了。
    onPrimary = Color(0xFFFFFFFF),
    // 净值 hero 卡片底 —— 淡玫瑰。对页面底只有 1.34:1，**必须靠描边勾出轮廓**，
    // 见 NetWorthScreen 的 SummaryCard（描边逻辑通用，换品牌色不用跟着改）。
    primaryContainer = Color(0xFFFBCEDF),
    onPrimaryContainer = Color(0xFF45142B),
    inversePrimary = Color(0xFFDF99B5),

    // 次要色走同色相的低彩中性 —— 只留一个强调色，其余全部近中性
    secondary = Color(0xFF70675E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1ECE6),
    onSecondaryContainer = Color(0xFF342C23),

    // 第三色刻意留在同色相内，**不用任何分类色的色相** ——
    // 否则它会和某个大类的颜色撞车，让读者以为两者有关
    tertiary = Color(0xFF7A4A5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7D3E0),
    onTertiaryContainer = Color(0xFF41162A),

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
    surfaceTint = BrandRose,
    inverseSurface = Color(0xFF30271D),
    // ↑ surfaceTint 跟着 primary 走，别写死色值
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
 * 深色模式是**另选的一组步进**，不是浅色的自动翻转 —— 同一个色相角（H 354），
 * 按深底重新取亮度并单独验过（primary 对底 6.26:1，与深色大类色最小 ΔE 22.0）。
 * ⚠️ 「保障类」这轮不用挪（见上方 LightScheme 文档的"不用再挪"一节），
 * 深色的金黄 `#9B8100` 原样保留。
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF53A8),
    // 同样是深酒红，不是白色 —— 深色模式的鲜艳玫瑰对白字只有 2.98:1，也不合格。
    onPrimary = Color(0xFF1C030F),
    primaryContainer = Color(0xFF673048),
    onPrimaryContainer = Color(0xFFFDD0E1),
    inversePrimary = BrandRose,

    secondary = Color(0xFFC5BCB3),
    onSecondary = Color(0xFF312A22),
    secondaryContainer = Color(0xFF413C36),
    onSecondaryContainer = Color(0xFFE6DED6),

    tertiary = Color(0xFFDBA4B9),
    onTertiary = Color(0xFF3A1024),
    tertiaryContainer = Color(0xFF612E44),
    onTertiaryContainer = Color(0xFFF7CDDD),

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
    surfaceTint = Color(0xFFFF53A8),
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
